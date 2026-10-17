package dev.thor.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StoredStreamEventTest {
    private fun event(sequence: Int, text: String = "甲", type: String = "text_delta") = obj(
        "id" to "row-$sequence", "eventId" to "event-$sequence", "sessionId" to "s", "runId" to "r",
        "sequence" to sequence, "wallTime" to 1_000L + sequence, "elapsedMs" to sequence * 10,
        "type" to type, "data" to obj("blockId" to "block", "text" to text, "other" to obj("nested" to true))
    )
    @Test fun adjacentDeltasMergeLosslesslyAndKeepFirstIdentityAndLatestTiming() {
        for (type in listOf("text_delta", "thinking_delta")) {
            val first = event(1, "哈", type)
            val next = event(2, "哈", type)
            val merged = StoredStreamEvent.merge(first, next)!!
            assertEquals("哈哈", merged.getJSONObject("data").getString("text"))
            assertEquals("row-1", merged.getString("id"))
            assertEquals("event-1", merged.getString("eventId"))
            assertEquals(1L, merged.getLong("sequence"))
            assertEquals(2L, merged.getLong("lastSequence"))
            assertEquals("event-2", merged.getString("lastEventId"))
            assertEquals(2L, merged.getLong("fragmentCount"))
            assertEquals(1001L, merged.getLong("firstWallTime"))
            assertEquals(1002L, merged.getLong("wallTime"))
            assertEquals(20L, merged.getLong("elapsedMs"))
            val third = StoredStreamEvent.merge(merged, event(3, "哈", type))!!
            assertEquals("哈哈哈", third.getJSONObject("data").getString("text"))
            assertEquals(3L, third.getLong("fragmentCount"))
            assertEquals(1001L, third.getLong("firstWallTime"))
        }
    }

    @Test fun inputsAndNestedObjectsAreNeverMutatedOrBorrowed() {
        val previous = event(1); val incoming = event(2)
        val before = previous.toString(); val nextBefore = incoming.toString()
        val merged = StoredStreamEvent.merge(previous, incoming)!!
        assertEquals(before, previous.toString()); assertEquals(nextBefore, incoming.toString())
        merged.getJSONObject("data").getJSONObject("other").put("nested", false)
        assertTrue(previous.getJSONObject("data").getJSONObject("other").getBoolean("nested"))
        assertTrue(incoming.getJSONObject("data").getJSONObject("other").getBoolean("nested"))
    }

    @Test fun everyContextAndEventBoundaryPreventsMerging() {
        val first = event(1)
        val alternatives = listOf(
            event(2).put("sessionId", "other"), event(2).put("runId", "other"), event(2, type = "thinking_delta"),
            event(2).also { it.getJSONObject("data").put("blockId", "new-block") },
            event(2).also { it.getJSONObject("data").remove("blockId") },
            event(2).also { it.getJSONObject("data").put("blockId", " ") },
            event(2).put("sessionId", JSONObject.NULL), event(2).put("sequence", 2.0),
            event(2).also { it.getJSONObject("data").put("text", JSONObject.NULL) }
        )
        alternatives.forEach { assertNull(it.toString(), StoredStreamEvent.merge(first, it)) }
        for (type in listOf("tool_call_update", "tool_intent", "tool_started", "tool_result", "authorization_changed", "state", "model_round", "diagnostic", "context")) {
            assertNull(type, StoredStreamEvent.merge(event(1, type = type), event(2, type = type)))
        }
    }

    @Test fun duplicateOutOfOrderMissingAndAggregateFragmentsDoNotMerge() {
        val first = event(1)
        val merged = StoredStreamEvent.merge(first, event(2))!!
        assertNull(StoredStreamEvent.merge(merged, event(2)))
        assertNull(StoredStreamEvent.merge(merged, event(1)))
        assertNull(StoredStreamEvent.merge(merged, event(4)))
        assertNull(StoredStreamEvent.merge(merged, event(3).put("eventId", "event-1")))
        assertNull(StoredStreamEvent.merge(merged, event(3).put("eventId", "event-2")))
        assertNull(StoredStreamEvent.merge(merged, event(3).put("lastSequence", 4)))
        assertNull(StoredStreamEvent.merge(merged, event(3).put("fragmentCount", 2)))
        assertNull(StoredStreamEvent.merge(first, event(2).also { it.remove("id"); it.remove("eventId") }))
        assertNull(StoredStreamEvent.merge(first.put("lastSequence", Long.MAX_VALUE), event(2)))
    }

    @Test fun capacityUsesUtf8BytesAtExactBoundaryAndNeverTruncatesIncomingText() {
        val cap = StoredStreamEvent.MAX_TEXT_BYTES
        val first = event(1, "a".repeat(cap - 4))
        val exact = StoredStreamEvent.merge(first, event(2, "😀"))!!
        assertEquals(cap, exact.getJSONObject("data").getString("text").toByteArray(Charsets.UTF_8).size)
        assertNull(StoredStreamEvent.merge(exact, event(3, "a")))
        val oversized = event(2, "中".repeat(cap))
        val original = oversized.toString()
        assertNull(StoredStreamEvent.merge(event(1), oversized))
        assertEquals(original, oversized.toString())
        assertNull(StoredStreamEvent.merge(event(1, "a".repeat(cap - 2)), event(2, "中")))
    }

    @Test fun ledgerMappedIdWorksAndRawIdentityIsNotRequired() {
        val first = event(1).also { it.remove("eventId") }
        val incoming = event(2).also { it.remove("eventId") }
        val result = StoredStreamEvent.merge(first, incoming)!!
        assertEquals("row-1", result.getString("id"))
        assertEquals("row-2", result.getString("lastEventId"))
        assertFalse(result.has("eventId"))
        assertNull(StoredStreamEvent.merge(result, event(3).also { it.remove("eventId"); it.put("id", "row-2") }))
    }

    @Test fun compactKeepsThousandDeltasInOneIndependentRow() {
        val events = (1..1000).map { event(it, "哈") }
        val originals = events.map(JSONObject::toString)
        val rows = StoredStreamEvent.compact(events)
        assertEquals(1, rows.size)
        assertEquals("哈".repeat(1000), rows.single().getJSONObject("data").getString("text"))
        assertEquals(1000L, rows.single().getLong("fragmentCount"))
        assertEquals(1000L, rows.single().getLong("lastSequence"))
        assertEquals(originals, events.map(JSONObject::toString))
    }

    @Test fun compactPreservesFirstPositionsAcrossInterleavedRunsAndSessionOwnership() {
        val events = listOf(
            event(1, "a"), event(1, "b").put("runId", "other"),
            event(2, "c"), event(2, "d").put("runId", "other"),
            event(3, "e").put("sessionId", "other-session"), event(3, "f")
        )
        val rows = StoredStreamEvent.compact(events)
        assertEquals(listOf("acf", "bd", "e"), rows.map { it.getJSONObject("data").getString("text") })
        assertEquals(listOf("r", "other", "r"), rows.map { it.getString("runId") })
        assertEquals(listOf("s", "s", "other-session"), rows.map { it.getString("sessionId") })
        assertEquals(listOf(1L, 1L, 3L), rows.map { it.getLong("sequence") })
        rows.last().getJSONObject("data").put("text", "changed")
        assertEquals("e", events[4].getJSONObject("data").getString("text"))
    }

    @Test fun compactPreservesToolBoundariesAndSplitsAtCapacityWithoutDroppingText() {
        val half = "a".repeat(StoredStreamEvent.MAX_TEXT_BYTES / 2)
        val events = listOf(event(1, half), event(2, half), event(3, "extra"),
            event(4, "", "tool_started"), event(5, "tail"), event(6, "!"))
        val rows = StoredStreamEvent.compact(events)
        assertEquals(listOf("text_delta", "text_delta", "tool_started", "text_delta"), rows.map { it.getString("type") })
        assertEquals(listOf(1L, 3L, 4L, 5L), rows.map { it.getLong("sequence") })
        assertEquals(half + half + "extratail!", rows.filter { it.getString("type") == "text_delta" }
            .joinToString("") { it.getJSONObject("data").getString("text") })
        assertEquals("tail!", rows.last().getJSONObject("data").getString("text"))
    }
}
