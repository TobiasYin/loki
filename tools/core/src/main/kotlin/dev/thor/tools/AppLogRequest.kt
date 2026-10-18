package dev.thor.tools

import java.util.ArrayDeque

/** Fixed, read-only logcat projection. Caller values never become command syntax. */
data class AppLogRequest(val packageName: String, val maxLines: Int, val maxBytes: Int) {
    fun processArgv() = listOf("/system/bin/ps", "-A", "-o", "PID,NAME")
    fun logArgv(pid: Int): List<String> {
        requireTool(pid > 0, message = "Invalid application PID")
        return listOf("/system/bin/logcat", "-d", "--pid=$pid", "-v", "threadtime")
    }
    fun matchingPids(processes: String): List<Int> = processes.lineSequence().mapNotNull { line ->
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size != 2) return@mapNotNull null
        val pid = columns[0].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val name = columns[1]
        val matches = name == packageName || (name.startsWith("$packageName:") &&
            name.removePrefix("$packageName:").matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")))
        pid.takeIf { matches }
    }.distinct().sorted().toList()

    companion object {
        fun parse(args: Map<String, Any?>): AppLogRequest {
            requireTool(args.keys.all { it in setOf("packageName", "maxLines", "maxBytes") }, message = "Unknown application log parameter")
            val name = args["packageName"] as? String ?: fail(Code.INVALID_ARGUMENT, "Application packageName is required")
            requireTool(name.length in 3..255 && name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")), message = "Invalid application packageName")
            fun integer(key: String, default: Int, max: Int): Int {
                val value = (args[key] ?: default) as? Number ?: fail(Code.INVALID_ARGUMENT, "$key must be an integer")
                val n = value.toDouble()
                requireTool(n.isFinite() && n == kotlin.math.floor(n) && n in 1.0..max.toDouble(), message = "$key exceeds the application log budget")
                return n.toInt()
            }
            return AppLogRequest(name, integer("maxLines", 300, 2000), integer("maxBytes", 65536, 65536))
        }
    }
}

/** Streaming tail: bounded memory, complete UTF-8 code points, redact before clipping. */
class AppLogTail(private val maxLines: Int, private val maxBytes: Int) {
    private data class Line(val text: String, val bytes: Int, val encodedBytes: Int)
    private val lines = ArrayDeque<Line>()
    private val pending = StringBuilder()
    private var overlong = false
    private var bytes = 0
    private var encodedBytes = 0
    var truncated = false
        private set

    init { require(maxLines in 1..2000 && maxBytes in 1..65536) }

    fun append(text: String) {
        for (c in text) {
            if (c == '\n') finishLine()
            else if (!overlong) {
                if (pending.length >= 65536) { pending.setLength(0); overlong = true; truncated = true }
                else pending.append(c)
            }
        }
    }

    fun finish() { if (pending.isNotEmpty() || overlong) finishLine() }

    private fun finishLine() {
        if (!overlong && !pending.startsWith("--------- beginning of ") && !pending.startsWith("--------- switch to ")) {
            val safe = Budgets.safeText(pending.toString().removeSuffix("\r"), 65536)
            val clipped = utf8Tail(safe, maxBytes, JSON_TEXT_BYTES)
            if (clipped != safe) truncated = true
            if (clipped.isNotEmpty() || safe.isEmpty()) {
                val line = Line(clipped, clipped.toByteArray(Charsets.UTF_8).size, encodedTextBytes(clipped))
                while (lines.isNotEmpty() && (lines.size >= maxLines || bytes + line.bytes + 1 > maxBytes ||
                        encodedBytes + line.encodedBytes + 2 > JSON_TEXT_BYTES)) {
                    val removed = lines.removeFirst()
                    bytes -= removed.bytes; encodedBytes -= removed.encodedBytes
                    if (lines.isNotEmpty()) { bytes--; encodedBytes -= 2 }
                    truncated = true
                }
                bytes += line.bytes + if (lines.isEmpty()) 0 else 1
                encodedBytes += line.encodedBytes + if (lines.isEmpty()) 0 else 2
                lines.addLast(line)
            }
        }
        pending.setLength(0); overlong = false
    }

    fun content(): String = lines.joinToString("\n") { it.text }
    fun count(): Int = lines.size

    private fun utf8Tail(text: String, limit: Int, encodedLimit: Int): String {
        var start = text.length
        var used = 0
        var encodedUsed = 0
        while (start > 0) {
            val cp = text.codePointBefore(start)
            val size = when { cp <= 0x7f -> 1; cp <= 0x7ff -> 2; cp <= 0xffff -> 3; else -> 4 }
            val encoded = encodedCodePointBytes(cp)
            if (used + size > limit || encodedUsed + encoded > encodedLimit) break
            used += size; encodedUsed += encoded; start -= Character.charCount(cp)
        }
        return text.substring(start)
    }

    companion object {
        // Leave room for the tool envelope, package metadata and result wrapper under 64 KiB.
        const val JSON_TEXT_BYTES = 48 * 1024

        /** Conservative JSON UTF-8 payload cost; no per-character strings or full JSON copies. */
        internal fun encodedTextBytes(text: String): Int {
            var size = 0; var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                size += encodedCodePointBytes(cp); i += Character.charCount(cp)
            }
            return size
        }

        private fun encodedCodePointBytes(cp: Int): Int = when (cp) {
            0x22, 0x2f, 0x5c, 0x08, 0x0c, 0x0a, 0x0d, 0x09 -> 2
            else -> when {
                cp < 0x20 || cp in 0x7f..0x9f || cp in 0xd800..0xdfff || cp in 0x2000..0x20ff -> 6
                cp <= 0x7f -> 1
                cp <= 0x7ff -> 2
                cp <= 0xffff -> 3
                else -> 4
            }
        }
    }
}
