package dev.thor.agent

import org.json.JSONObject

/** Lossless durable folding of adjacent deltas from one explicit runtime content block.
 *
 * This is intentionally separate from presentation text windows: persisted text is never
 * truncated or prefix-deduplicated. Returning null means the caller must retain the incoming
 * event separately. The caller enforces retained row ID uniqueness and its run's source
 * sequence high-water mark. Interior fragment IDs do not have a separate durable index.
 */
internal object StoredStreamEvent {
    // A bounded row keeps SQLite/WebView reads manageable even for very long generations.
    // Count UTF-8 bytes so Chinese and supplementary characters obey the same storage bound.
    const val MAX_TEXT_BYTES = 128 * 1024

    /** Compact a snapshot without moving a run's first event past interleaved runs.
     * Each run's most recent row is its only merge candidate, so its own tools/state
     * boundaries are preserved while unrelated concurrent runs do not split a stream.
     */
    fun compact(events: List<JSONObject>): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val lastPositions = mutableMapOf<Pair<String, String>, Int>()
        for (event in events) {
            val session = event.string("sessionId")
            val run = event.string("runId")
            val key = if (session != null && run != null) session to run else null
            val position = key?.let(lastPositions::get)
            val folded = position?.let { merge(result[it], event) }
            if (position != null && folded != null) {
                result[position] = folded
            } else {
                if (key != null) lastPositions[key] = result.size
                result.add(JSONObject(event.toString()))
            }
        }
        return result
    }

    /** Returns an independent folded row, or null when this pair is not safely foldable.
     * Supports raw runtime eventId and the durable ledger's id without changing either.
     * Only a fresh single delta may extend a folded row; replayed aggregate rows may not.
     */
    fun merge(previous: JSONObject, incoming: JSONObject): JSONObject? {
        val type = previous.optString("type")
        if (type !in setOf("text_delta", "thinking_delta") || incoming.optString("type") != type) return null
        for (key in listOf("sessionId", "runId")) {
            val identity = previous.string(key) ?: return null
            if (identity != incoming.string(key)) return null
        }
        val firstId = previous.eventId() ?: return null
        val incomingId = incoming.eventId() ?: return null
        if (incomingId == firstId || incomingId == previous.string("lastEventId")) return null
        val firstSequence = previous.integer("sequence") ?: return null
        val lastSequence = if (previous.has("lastSequence")) previous.integer("lastSequence") ?: return null else firstSequence
        val nextSequence = incoming.integer("sequence") ?: return null
        if (firstSequence <= 0 || lastSequence < firstSequence || lastSequence == Long.MAX_VALUE || nextSequence != lastSequence + 1) return null
        if (incoming.has("lastSequence") && incoming.integer("lastSequence") != nextSequence) return null
        if (incoming.has("fragmentCount") && incoming.integer("fragmentCount") != 1L) return null
        val count = if (previous.has("fragmentCount")) previous.integer("fragmentCount") ?: return null else 1L
        if (count <= 0 || count == Long.MAX_VALUE) return null
        val oldData = previous.optJSONObject("data") ?: return null
        val newData = incoming.optJSONObject("data") ?: return null
        val block = oldData.string("blockId") ?: return null
        if (block != newData.string("blockId")) return null
        val oldText = oldData.opt("text") as? String ?: return null
        val newText = newData.opt("text") as? String ?: return null
        if (oldText.toByteArray(Charsets.UTF_8).size.toLong() + newText.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return null

        return JSONObject(previous.toString()).apply {
            getJSONObject("data").put("text", oldText + newText)
            put("lastSequence", nextSequence)
            put("lastEventId", incomingId)
            put("fragmentCount", count + 1)
            if (!has("firstWallTime") && previous.has("wallTime")) put("firstWallTime", previous.get("wallTime"))
            for (key in listOf("wallTime", "elapsedMs")) if (incoming.has(key)) put(key, incoming.get(key))
        }
    }

    private fun JSONObject.string(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }
    private fun JSONObject.eventId(): String? = string("eventId") ?: string("id")
    private fun JSONObject.integer(key: String): Long? = when (val value = opt(key)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }
}
