package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Per-hold ASR hints, built locally from already available, session-scoped text.
 * This is a recognition hint, not an instruction and never a rewritten transcript.
 * Bounds below are deliberately small UTF-8 limits, not an assumed provider tokenizer.
 */
class VoiceRecognitionContext private constructor(private val serialized: String) {
    data class Message(val role: String, val text: String)
    data class App(val label: String?, val packageName: String?, val windowTitle: String? = null)

    internal fun serialize(): String = serialized

    companion object {
        const val MAX_MESSAGES = 6
        const val MAX_TEXT_BYTES = 1000
        const val MAX_SERIALIZED_BYTES = 2048
        private const val MAX_MESSAGE_BYTES = 240
        private const val MAX_HOTWORD_BYTES = 72
        private val credential = Regex("(?i)(?:oc_sk_|sk-[a-z0-9]|bearer\\s+[a-z0-9]|(?:api[_ -]?key|access[_ -]?key|authorization|密钥)\\s*(?:[:：=]|为|是)\\s*[^\\s，。；,;]{8,})")

        /** The caller supplies newest messages first. A game title must be known
         * from current observations; an emulator label alone does not identify it.
         * App/game fields must be omitted when screen perception is unavailable.
         */
        fun build(recentMessagesNewestFirst: List<Message>, upperApp: App? = null, gameTitle: String? = null): VoiceRecognitionContext? {
            val label = clean(upperApp?.label.orEmpty(), 96)
            val pkg = upperApp?.packageName?.takeIf { it.length <= 255 && it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")) }
                ?.let { clean(it, 120) }.orEmpty()
            val game = clean(gameTitle.orEmpty(), 96)
            val title = clean(upperApp?.windowTitle.orEmpty(), 96).takeUnless { it.equals(label, ignoreCase = true) }.orEmpty()
            val scene = listOfNotNull(
                label.takeIf(String::isNotEmpty)?.let { "当前上屏应用：$it" },
                title.takeIf(String::isNotEmpty)?.let { "上屏窗口标题：$it" },
                game.takeIf(String::isNotEmpty)?.let { "当前运行游戏：$it" },
                pkg.takeIf(String::isNotEmpty)?.let { "应用包名：$it" },
            ).joinToString("；").let { clean(it, 240) }
            val data = JSONArray()
            var remaining = MAX_TEXT_BYTES
            if (scene.isNotEmpty()) {
                data.put(JSONObject().put("text", scene))
                remaining -= scene.toByteArray(Charsets.UTF_8).size
            }
            val seen = HashSet<String>()
            var messages = 0
            // Input lists may be large; neither full history nor tool payloads are scanned.
            for (message in recentMessagesNewestFirst.take(24)) {
                if (messages >= MAX_MESSAGES || remaining <= 0) break
                if (message.role !in setOf("user", "assistant")) continue
                val text = clean(message.text, minOf(MAX_MESSAGE_BYTES, remaining))
                if (text.isEmpty() || !seen.add("${message.role}:$text")) continue
                data.put(JSONObject().put("speaker", if (message.role == "assistant") "bot" else "user").put("text", text))
                remaining -= text.toByteArray(Charsets.UTF_8).size
                messages++
            }
            val hotwords = JSONArray()
            var hotwordBytes = MAX_HOTWORD_BYTES
            val words = HashSet<String>()
            // A window title is an observed label, not necessarily a game name.
            // Put more specific observed names before their host application.
            for ((raw, word) in listOf(gameTitle to game, upperApp?.windowTitle to title, upperApp?.label to label)) {
                // Validate the complete original name before using its cleaned
                // scene prefix as a hotword. Even an unusually long title with
                // mostly control characters must not produce a shortened name.
                if (raw == null || raw.length > MAX_HOTWORD_BYTES || raw.toByteArray(Charsets.UTF_8).size > MAX_HOTWORD_BYTES) continue
                if (word.isEmpty() || !words.add(word.lowercase(Locale.ROOT))) continue
                val size = word.toByteArray(Charsets.UTF_8).size
                if (size > hotwordBytes) continue
                hotwords.put(JSONObject().put("word", word)); hotwordBytes -= size
            }
            val result = JSONObject()
            if (data.length() > 0) result.put("context_type", "dialog_ctx").put("context_data", data)
            if (hotwords.length() > 0) result.put("hotwords", hotwords)
            if (result.length() == 0) return null
            // JSON escaping can expand unusual input; discard oldest turns first.
            while (result.toString().toByteArray(Charsets.UTF_8).size > MAX_SERIALIZED_BYTES && data.length() > 1) data.remove(data.length() - 1)
            val json = result.toString()
            return if (json.toByteArray(Charsets.UTF_8).size <= MAX_SERIALIZED_BYTES) VoiceRecognitionContext(json) else null
        }

        private fun clean(value: String, maxBytes: Int): String {
            // Avoid spending time on an arbitrarily large pasted message. Keep
            // the beginning, where task/game names normally occur, without ever
            // cutting an emoji/surrogate pair in the emitted text.
            val source = value.take(16_384)
            if (credential.containsMatchIn(source)) return ""
            val result = StringBuilder(); var bytes = 0; var index = 0
            while (index < source.length) {
                val point = source.codePointAt(index); index += Character.charCount(point)
                if (point in 0xD800..0xDFFF || Character.isISOControl(point)) {
                    if ((point == 10 || point == 13 || point == 9) && result.isNotEmpty() && result.last() != ' ' && bytes < maxBytes) {result.append(' ');bytes++}
                    continue
                }
                val chars = String(Character.toChars(point)); val size = chars.toByteArray(Charsets.UTF_8).size
                if (bytes + size > maxBytes) break
                result.append(chars); bytes += size
            }
            return result.toString().trim()
        }
    }
}
