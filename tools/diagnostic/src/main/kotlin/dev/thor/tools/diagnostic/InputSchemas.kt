package dev.thor.tools.diagnostic

import android.content.Context
import dev.thor.tools.*
import org.json.JSONObject

/** Small fixed-schema validator; handles only the vocabulary in the audited bundled v1 contract. */
class InputSchemas(context: Context) {
    private val schemas: Map<String, JSONObject>
    init {
        val pack = context.assets.open("toolpack-v1.json").bufferedReader().use { JSONObject(it.readText()) }
        requireTool(pack.getInt("contractVersion") == 1, Code.UNSUPPORTED, "Unknown bundled contract version")
        val entries = pack.getJSONArray("tools")
        val pairs = (0 until entries.length()).map { index ->
            val descriptor = entries.getJSONObject(index)
            descriptor.getString("toolId") to descriptor.getJSONObject("inputSchema")
        }
        requireTool(pairs.map { it.first }.toSet().size == pairs.size, Code.UNSUPPORTED, "Duplicate bundled tool ID")
        requireTool(pairs.map { it.first }.toSet() == ToolPack.descriptors.keys, Code.UNSUPPORTED, "Schema/executor registration mismatch")
        schemas = pairs.toMap()
    }
    fun validate(tool: String, args: Map<String, Any?>) {
        val schema = schemas[tool] ?: fail(Code.UNSUPPORTED, "Unknown bundled tool")
        validateValue(args, schema, "input")
    }
    private fun validateValue(value: Any?, schema: JSONObject, field: String) {
        when (schema.getString("type")) {
            "object" -> {
                val map = value as? Map<*, *> ?: fail(Code.INVALID_ARGUMENT, "$field must be an object")
                val properties = schema.optJSONObject("properties")
                val required = schema.optJSONArray("required")
                if (required != null) for (i in 0 until required.length()) requireTool(map.containsKey(required.getString(i)), message = "$field.${required.getString(i)} is required")
                map.forEach { (k, v) ->
                    if (schema.optBoolean("additionalProperties", true).not()) requireTool(properties?.has(k.toString()) == true, message = "Unknown $field.$k")
                    if (properties?.has(k.toString()) == true) validateValue(v, properties.getJSONObject(k.toString()), "$field.$k")
                }
            }
            "string" -> {
                val s = value as? String ?: fail(Code.INVALID_ARGUMENT, "$field must be a string")
                requireTool(s.length >= schema.optInt("minLength", 0), message = "$field is too short")
                requireTool(s.length <= schema.optInt("maxLength", Budgets.CONFIG), Code.LIMIT_EXCEEDED, "$field exceeds string budget")
                schema.optJSONArray("enum")?.let { values -> requireTool((0 until values.length()).any { values.getString(it) == s }, message = "Unknown $field value") }
                if (schema.has("pattern")) requireTool(s.matches(Regex(schema.getString("pattern"))), message = "Invalid $field format")
                if (schema.optString("format") == "uuid") requireTool(runCatching { java.util.UUID.fromString(s).toString() == s }.getOrDefault(false), message = "Invalid $field UUID")
            }
            "number", "integer" -> {
                val n = (value as? Number)?.toDouble() ?: fail(Code.INVALID_ARGUMENT, "$field must be numeric")
                requireTool(n.isFinite(), message = "$field must be finite")
                if (schema.getString("type") == "integer") requireTool(n == kotlin.math.floor(n), message = "$field must be an integer")
                requireTool(n >= schema.optDouble("minimum", -Double.MAX_VALUE) && n <= schema.optDouble("maximum", Double.MAX_VALUE), Code.LIMIT_EXCEEDED, "$field exceeds numeric budget")
            }
            "array" -> {
                val values = value as? List<*> ?: fail(Code.INVALID_ARGUMENT, "$field must be an array")
                requireTool(values.size >= schema.optInt("minItems", 0), message = "$field needs more entries")
                requireTool(values.size <= schema.optInt("maxItems", 128), Code.LIMIT_EXCEEDED, "$field exceeds entry budget")
                schema.optJSONObject("items")?.let { item -> values.forEachIndexed { i, v -> validateValue(v, item, "$field[$i]") } }
            }
            else -> fail(Code.UNSUPPORTED, "Unknown bundled schema type")
        }
    }
}
