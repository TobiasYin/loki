package dev.thor.tools.diagnostic

import org.json.JSONArray
import org.json.JSONObject
import dev.thor.tools.*
import java.time.Instant

fun JSONObject.args(): Map<String, Any?> = keys().asSequence().associateWith { key -> unpack(get(key)) }
private fun unpack(v: Any?): Any? = when (v) {
    JSONObject.NULL -> null
    is JSONObject -> v.args()
    is JSONArray -> (0 until v.length()).map { unpack(v.get(it)) }
    else -> v
}
fun json(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { obj -> v.forEach { (k, value) -> obj.put(k.toString(), json(value)) } }
    is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(json(it)) } }
    else -> v
}
fun ToolResult.toJson(): String = (json(mapOf("observedAt" to Instant.now().toString(), "source" to source,
    "availability" to mapOf("status" to availability.status.name.lowercase(), "reason" to availability.reason, "backend" to availability.backend),
    "data" to data, "error" to error?.name, "message" to message, "truncated" to truncated,
    "outcome" to outcome, "snapshotId" to snapshotId)) as JSONObject).toString(2)
fun Map<String, Any?>.string(key: String, default: String? = null): String = (this[key] ?: default) as? String ?: fail(Code.INVALID_ARGUMENT, "$key must be a string")
fun Map<String, Any?>.number(key: String, default: Number): Number {
    val value = this[key] ?: default
    return value as? Number ?: fail(Code.INVALID_ARGUMENT, "$key must be numeric")
}
fun Map<String, Any?>.int(key: String, default: Int): Int {
    val v = number(key, default).toDouble()
    requireTool(v.isFinite() && v == v.toInt().toDouble(), message = "$key must be an integer")
    return v.toInt()
}
fun Map<String, Any?>.only(vararg keys: String) { requireTool(this.keys.all { it in keys }, message = "Unknown input field") }
