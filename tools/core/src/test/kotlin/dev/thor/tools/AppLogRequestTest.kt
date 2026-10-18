package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class AppLogRequestTest {
    @Test fun parsesStrictIntegerBudgetsAndUsesOnlyFixedArgv() {
        val r = AppLogRequest.parse(mapOf("packageName" to "abc.smapi.gameloader"))
        assertEquals(300, r.maxLines); assertEquals(65536, r.maxBytes)
        assertEquals(listOf("/system/bin/ps", "-A", "-o", "PID,NAME"), r.processArgv())
        assertEquals(listOf("/system/bin/logcat", "-d", "--pid=123", "-v", "threadtime"), r.logArgv(123))
        assertFalse(r.logArgv(123).contains("-t"))
        assertEquals(2000, AppLogRequest.parse(mapOf("packageName" to "a.b", "maxLines" to 2000.0)).maxLines)
        for (value in listOf(0, -1, 2001, 2.5, "2", Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(ToolFailure::class.java) { AppLogRequest.parse(mapOf("packageName" to "a.b", "maxLines" to value)) }
        }
        for (value in listOf(0, 65537, 3.5, "10")) {
            assertThrows(ToolFailure::class.java) { AppLogRequest.parse(mapOf("packageName" to "a.b", "maxBytes" to value)) }
        }
    }

    @Test fun rejectsShellSyntaxPathsMissingPackageAndArbitraryFlags() {
        for (name in listOf("", "abc;id", "a.b --pid=1", "a.b:service", "a..b", "/sdcard/log", "a.b\n", "a.b\u0000", "a.1b")) {
            assertThrows(ToolFailure::class.java) { AppLogRequest.parse(mapOf("packageName" to name)) }
        }
        assertThrows(ToolFailure::class.java) { AppLogRequest.parse(emptyMap()) }
        assertThrows(ToolFailure::class.java) { AppLogRequest.parse(mapOf("packageName" to "a.b", "flags" to "-c")) }
    }

    @Test fun onlyMatchesExactPackageAndValidChildProcesses() {
        val r = AppLogRequest.parse(mapOf("packageName" to "abc.smapi.gameloader"))
        val ps = """
            PID NAME
            123 abc.smapi.gameloader
            124 abc.smapi.gameloader:remote
            124 abc.smapi.gameloader:remote
            125 abc.smapi.gameloader.fake
            126 abc.smapi.gameloaderx
            127 abc.smapi.gameloader:
            128 abc.smapi.gameloader::remote
            129 abc.smapi.gameloader:remote extra
            0 abc.smapi.gameloader
            -1 abc.smapi.gameloader
            junk abc.smapi.gameloader
            9999999999999999999 abc.smapi.gameloader
            130 other.package:abc.smapi.gameloader
        """.trimIndent()
        assertEquals(listOf(123, 124), r.matchingPids(ps))
        assertEquals(emptyList<Int>(), r.matchingPids("PID NAME\n1 other.package"))
    }

    @Test fun keepsLatestLinesWhileBoundingUtf8Bytes() {
        val tail = AppLogTail(2, 100)
        tail.append("one\ntwo\nth"); tail.append("ree\n"); tail.finish()
        assertEquals("two\nthree", tail.content()); assertEquals(2, tail.count()); assertTrue(tail.truncated)
        val utf8 = AppLogTail(20, 9)
        utf8.append("old\n中文😀末\n"); utf8.finish()
        assertEquals("😀末", utf8.content())
        assertTrue(utf8.content().toByteArray(Charsets.UTF_8).size <= 9)
        assertFalse(utf8.content().contains('\uFFFD'))
        assertTrue(utf8.truncated)
    }

    @Test fun redactsBeforeApplyingOutputByteBudgetAndPreservesChunkedSurrogates() {
        val tail = AppLogTail(10, 100)
        tail.append("api_ke"); tail.append("y=secret bearer sensitive token=another\n")
        tail.append("\uD83D"); tail.append("\uDE00"); tail.finish()
        assertEquals("api_key=[REDACTED] bearer [REDACTED] token=[REDACTED]\n😀", tail.content())
        val small = AppLogTail(10, 5)
        small.append("api_key=do-not-leak\n"); small.finish()
        assertFalse(small.content().contains("leak")); assertTrue(small.content().toByteArray().size <= 5)
    }

    @Test fun emptyAndHeaderOnlyLogsAreSuccessfulEmptyAndOverlongLinesDoNotLeak() {
        val empty = AppLogTail(300, 65536)
        empty.append("--------- beginning of main\n"); empty.finish()
        assertEquals("", empty.content()); assertEquals(0, empty.count())
        assertFalse(empty.truncated)
        val tail = AppLogTail(300, 65536)
        tail.append("token=" + "secret".repeat(20000) + "\nreal log\n"); tail.finish()
        assertEquals("real log", tail.content()); assertTrue(tail.truncated)
        val exact = AppLogTail(2, 5)
        exact.append("a\nb\nc\n"); exact.finish()
        assertEquals("b\nc", exact.content())
    }

    @Test fun boundsJsonEscapingForQuotesBackslashesAndControls() {
        for (character in listOf('"', '\\', '\u0001', '\t', '/', '\u2000', '\u2028', '\u0080')) {
            val tail = AppLogTail(2000, 65536)
            tail.append(character.toString().repeat(65536)); tail.finish()
            assertTrue(tail.truncated)
            assertTrue(AppLogTail.encodedTextBytes(tail.content()) <= 48 * 1024)
            assertTrue(tail.content().toByteArray(Charsets.UTF_8).size <= 65536)
            assertTrue(tail.content().isNotEmpty())
        }
        assertEquals(12, AppLogTail.encodedTextBytes("\u0001\u0002"))
        assertEquals(10, AppLogTail.encodedTextBytes("\"\\\n\r\t"))
    }

    @Test fun includesNewlineEscapingInAggregateBudgetAndKeepsRecentLines() {
        val tail = AppLogTail(2000, 65536)
        repeat(2000) { tail.append("$it:" + "\"\\".repeat(30) + "\n") }
        tail.finish()
        assertTrue(tail.truncated)
        assertTrue(AppLogTail.encodedTextBytes(tail.content()) <= 48 * 1024)
        assertTrue(tail.content().toByteArray(Charsets.UTF_8).size <= 65536)
        assertTrue(tail.content().lineSequence().last().startsWith("1999:"))
        assertEquals(tail.count(), tail.content().lineSequence().count())
    }

    @Test fun jsonBudgetKeepsCompleteUnicodeCodePoints() {
        val tail = AppLogTail(10, 65536)
        tail.append("中文😀".repeat(10000)); tail.finish()
        assertTrue(tail.truncated)
        assertTrue(AppLogTail.encodedTextBytes(tail.content()) <= 48 * 1024)
        assertTrue(tail.content().endsWith("😀"))
        assertFalse(tail.content().contains('\uFFFD'))
        assertFalse(tail.content().first().isLowSurrogate())
    }
}
