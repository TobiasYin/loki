package dev.thor.tools

import java.io.InputStream
import java.io.ByteArrayOutputStream

object Budgets {
    const val TEXT = 64 * 1024
    const val IMAGE = 2 * 1024 * 1024
    const val CONFIG = 1024 * 1024
    data class Bytes(val bytes: ByteArray, val truncated: Boolean)
    fun read(input: InputStream, limit: Int, check: () -> Unit = {}): Bytes {
        requireTool(limit in 1..CONFIG, Code.LIMIT_EXCEEDED, "Invalid byte budget")
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (out.size() < limit) {
            check()
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) return Bytes(out.toByteArray(), false)
            out.write(buffer, 0, n)
        }
        check()
        return Bytes(out.toByteArray(), input.read() != -1)
    }
    fun safeText(value: String, max: Int = TEXT): String = value
        .replace(Regex("(?i)(bearer\\s+|api[_-]?key\\s*[=:]\\s*|token\\s*[=:]\\s*)[^\\s,;]+"), "$1[REDACTED]")
        .take(max)
}
