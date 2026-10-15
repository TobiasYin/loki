package dev.thor.agent

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class RecoveryHistoryTest {
    @Test fun legacyFailureRetainsCommittedResearchAndClosesOnlyUnknownOperation() {
        val original=obj("complete" to false,"messages" to JSONArray().put(obj("kind" to "user","content" to JSONArray().put(obj("type" to "text","text" to "research")))))
        val before=original.toString()
        val events=listOf(
            obj("type" to "text_delta","data" to obj("text" to "已找到来源")),
            obj("type" to "tool_started","toolCallId" to "read","data" to obj("toolId" to "web.fetch","arguments" to obj("url" to "https://example.org/source"))),
            obj("type" to "tool_result","toolCallId" to "read","data" to obj("content" to JSONArray().put(obj("type" to "text","text" to "verified fact")))),
            obj("type" to "tool_started","toolCallId" to "pending","data" to obj("toolId" to "skills.save","arguments" to obj("content" to "draft"))))
        val recovered=recoverTurnHistory(original,events)
        assertEquals(before,original.toString());assertFalse(recovered.getBoolean("complete"))
        val messages=recovered.getJSONArray("messages").objects()
        assertEquals(6,messages.size);assertTrue(recovered.toString().contains("verified fact"))
        assertEquals("web_fetch",messages[2].getJSONArray("calls").getJSONObject(0).getString("name"))
        assertFalse(messages[3].getBoolean("isError"));assertTrue(messages.last().getBoolean("isError"))
        assertTrue(messages.last().toString().contains("outcome unknown"))
        assertEquals(recovered.toString(),recoverTurnHistory(recovered,events).toString())
    }
}
