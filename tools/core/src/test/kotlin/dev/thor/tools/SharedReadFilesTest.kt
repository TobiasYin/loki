package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AccessDeniedException
import java.nio.file.attribute.FileTime

class SharedReadFilesTest {
    private fun fixture(block: (Path, SharedReadFiles) -> Unit) {
        val root = Files.createTempDirectory("shared-read-").toRealPath()
        try { block(root, SharedReadFiles(root)) }
        finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    private fun failure(code: Code, action: () -> Unit) {
        try { action(); fail("Expected $code") }
        catch (e: ToolFailure) { assertEquals(e.message, code, e.code) }
    }
    private fun entries(result: Map<String, Any?>, key: String = "entries") =
        (result[key] as List<*>).map { it as Map<*, *> }

    @Test fun readsTheActualSmapiLayoutWithoutAnyDiagnosticDirectory() = fixture { root, reader ->
        val log = root.resolve("Android/data/abc.smapi.gameloader/files/ErrorLogs/SMAPI-latest.txt")
        Files.createDirectories(log.parent)
        Files.writeString(log, "[DEBUG SMAPI] Loading mod metadata...\n[DEBUG SMAPI] Loading mods...\n")
        val result = reader.execute("files.read", mapOf("path" to "/sdcard/Android/data/abc.smapi.gameloader/files/ErrorLogs/SMAPI-latest.txt"))
        assertEquals(log.toString(), result["path"])
        assertEquals(Files.readString(log), result["content"])
        assertEquals(true, result["eof"])
        assertEquals(false, result["truncated"])
        assertFalse(result.containsKey("hash"))
        assertFalse(result.containsKey("text"))
        assertEquals(hash(Files.readAllBytes(log)), result["sliceHash"])
        assertFalse(Files.exists(root.resolve("Download/ThorToolPack")))
    }

    @Test fun missingRootIsNotCreatedByConstructionOrAnyOperation() = fixture { root, _ ->
        val missing = root.resolve("not-present")
        val reader = SharedReadFiles(missing)
        listOf("files.list", "files.stat", "files.read", "files.find").forEach { operation ->
            failure(Code.IO_ERROR) { reader.execute(operation, mapOf("path" to ".", "name" to "SMAPI")) }
        }
        assertFalse(Files.exists(missing))
    }

    @Test fun allPlatformAliasesRelativePathsAndExplicitRemovableRootWork() = fixture { root, _ ->
        Files.writeString(root.resolve("test.txt"), "primary")
        val removable = Files.createTempDirectory("shared-removable-").toRealPath()
        try {
            Files.writeString(removable.resolve("sd.txt"), "removable")
            val reader = SharedReadFiles(root, listOf(removable))
            listOf("/sdcard/test.txt", "/mnt/sdcard/test.txt", "/storage/self/primary/test.txt", root.resolve("test.txt").toString(), "test.txt").forEach { path ->
                assertEquals("primary", reader.execute("files.read", mapOf("path" to path))["content"])
            }
            assertEquals("removable", reader.execute("files.read", mapOf("path" to removable.resolve("sd.txt").toString()))["content"])
        } finally { Files.walk(removable).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    @Test fun trustedRootAliasIsResolvedOnceWhileDescendantAliasesAreRefused() = fixture { root, _ ->
        val actual = root.resolve("real"); Files.createDirectory(actual)
        Files.writeString(actual.resolve("log.txt"), "ok")
        val trustedAlias = root.resolve("trusted"); Files.createSymbolicLink(trustedAlias, actual)
        val reader = SharedReadFiles(trustedAlias)
        assertEquals("ok", reader.execute("files.read", mapOf("path" to "log.txt"))["content"])
        Files.delete(trustedAlias)
        Files.createSymbolicLink(trustedAlias, root)
        assertEquals("ok", reader.execute("files.read", mapOf("path" to "log.txt"))["content"])
        Files.createSymbolicLink(actual.resolve("child-link"), root)
        failure(Code.PERMISSION_MISSING) { reader.execute("files.stat", mapOf("path" to "child-link")) }
    }

    @Test fun directoryPagesAreSortedAndHavePreciseResumeOffsets() = fixture { root, reader ->
        listOf("z.txt", "a.txt", "c.txt", "b.txt").forEach { Files.writeString(root.resolve(it), it) }
        Files.createDirectory(root.resolve("d"))
        val one = reader.execute("files.list", mapOf("limit" to 2))
        assertEquals(listOf("a.txt", "b.txt"), entries(one).map { it["name"] })
        assertEquals(2, one["nextOffset"])
        assertEquals(true, one["truncated"])
        val two = reader.execute("files.list", mapOf("offset" to 2, "limit" to 2))
        assertEquals(listOf("c.txt", "d"), entries(two).map { it["name"] })
        assertEquals("directory", entries(two).last()["type"])
        val last = reader.execute("files.list", mapOf("offset" to 4, "limit" to 2))
        assertEquals(listOf("z.txt"), entries(last).map { it["name"] })
        assertNull(last["nextOffset"])
        assertEquals(false, last["truncated"])
        assertTrue(entries(reader.execute("files.list", mapOf("offset" to 100))).isEmpty())
    }

    @Test fun utf8PagesAndLargeTailPreserveChineseAndEmoji() = fixture { root, reader ->
        val original = "中文🙂开始\n".repeat(12000)
        val path = root.resolve("中文.txt")
        Files.writeString(path, original)
        val bytes = Files.readAllBytes(path)
        var offset = 0L
        val rebuilt = StringBuilder()
        do {
            val result = reader.execute("files.read", mapOf("path" to "中文.txt", "offset" to offset, "maxBytes" to 4097))
            val next = result["nextOffset"] as Long
            assertTrue(next > offset)
            assertEquals(offset, result["offset"])
            val raw = bytes.copyOfRange(offset.toInt(), next.toInt())
            assertEquals(hash(raw), result["sliceHash"])
            assertEquals(raw.size, result["bytesRead"])
            rebuilt.append(result["content"])
            offset = next
        } while (result["eof"] != true)
        assertEquals(original, rebuilt.toString())
        val tail = reader.execute("files.read", mapOf("path" to "中文.txt", "tail" to true, "maxBytes" to 65536))
        val start = tail["offset"] as Long
        assertTrue(start > 0)
        assertEquals(String(bytes.copyOfRange(start.toInt(), bytes.size), Charsets.UTF_8), tail["content"])
        assertEquals(bytes.size.toLong(), tail["nextOffset"])
        assertEquals(true, tail["eof"])
        assertEquals(true, tail["truncated"])
    }

    @Test fun arbitraryOffsetsSkipPartialUtf8AndTooSmallPagesDoNotPretendBinary() = fixture { root, reader ->
        Files.writeString(root.resolve("log.txt"), "中文🙂abc")
        val middle = reader.execute("files.read", mapOf("path" to "log.txt", "offset" to 1, "maxBytes" to 9))
        assertEquals(3L, middle["offset"])
        assertEquals("文🙂", middle["content"])
        failure(Code.LIMIT_EXCEEDED) { reader.execute("files.read", mapOf("path" to "log.txt", "maxBytes" to 1)) }
        val beyond = reader.execute("files.read", mapOf("path" to "log.txt", "offset" to 10000))
        assertEquals("", beyond["content"])
        assertEquals(true, beyond["eof"])
    }

    @Test fun rejectsBinaryOrMalformedUtf8AndRedactsSecretsWithoutInventingFullHash() = fixture { root, reader ->
        Files.write(root.resolve("binary"), byteArrayOf(0x00, 0x61))
        Files.write(root.resolve("invalid"), byteArrayOf(0xc3.toByte(), 0x28))
        listOf("binary", "invalid").forEach { file -> failure(Code.UNSUPPORTED) { reader.execute("files.read", mapOf("path" to file)) } }
        val raw = "api_key=secret-token\nhello".toByteArray()
        Files.write(root.resolve("redact"), raw)
        val result = reader.execute("files.read", mapOf("path" to "redact"))
        assertEquals("api_key=[REDACTED]\nhello", result["content"])
        assertEquals(true, result["redacted"])
        assertEquals(hash(raw), result["sliceHash"])
        assertEquals("raw_slice_bytes", result["hashScope"])
        assertFalse(result.containsKey("hash"))
        assertFalse(result.containsKey("fileHash"))
    }

    @Test fun traversalAbsoluteEscapeSymlinkAndSpecialFilesAreNeverRead() = fixture { root, reader ->
        listOf("../outside", "/etc/passwd", "/sdcard/../../etc/passwd", "/sdcard-other/log", "../../").forEach { path ->
            failure(Code.PERMISSION_MISSING) { reader.execute("files.read", mapOf("path" to path)) }
        }
        Files.createSymbolicLink(root.resolve("escape"), root.parent)
        Files.createSymbolicLink(root.resolve("dangling"), root.resolve("missing"))
        listOf("escape", "escape/file", "dangling").forEach { path ->
            failure(Code.PERMISSION_MISSING) { reader.execute("files.stat", mapOf("path" to path)) }
        }
        val process = ProcessBuilder("mkfifo", root.resolve("pipe").toString()).start()
        assertEquals(0, process.waitFor())
        failure(Code.PERMISSION_MISSING) { reader.execute("files.read", mapOf("path" to "pipe")) }
        val listing = reader.execute("files.list", emptyMap())
        assertTrue(entries(listing).isEmpty())
        assertEquals(2, listing["skippedSymlinks"])
        assertEquals(1, listing["skippedSpecialFiles"])
    }

    @Test fun findIsLiteralCaseInsensitiveBoundedAndDoesNotFollowLinks() = fixture { root, reader ->
        Files.createDirectories(root.resolve("Android/data/abc.smapi/files/ErrorLogs"))
        Files.writeString(root.resolve("Android/data/abc.smapi/files/ErrorLogs/SMAPI-latest.txt"), "log")
        Files.writeString(root.resolve("smapi[1].txt"), "literal")
        Files.createSymbolicLink(root.resolve("outside-link"), root.parent)
        val result = reader.execute("files.find", mapOf("name" to "smapi", "maxDepth" to 8))
        assertEquals(3, entries(result, "matches").size)
        assertEquals(1, result["skippedSymlinks"])
        assertEquals(false, result["truncated"])
        val literal = reader.execute("files.find", mapOf("name" to "[1]"))
        assertEquals(listOf("smapi[1].txt"), entries(literal, "matches").map { it["name"] })
        val bounded = reader.execute("files.find", mapOf("name" to "smapi", "maxEntries" to 2))
        assertEquals(2, bounded["scanned"])
        assertEquals(true, bounded["scanTruncated"])
        assertEquals(true, bounded["truncated"])
        val zero = reader.execute("files.find", mapOf("name" to "SMAPI", "maxDepth" to 0))
        assertEquals(1, zero["scanned"])
        assertTrue(entries(zero, "matches").isEmpty())
        assertEquals(true, zero["depthLimited"])
    }

    @Test fun schemaAndMissingTargetsAreReportedAccurately() = fixture { _, reader ->
        failure(Code.IO_ERROR) { reader.execute("files.read", mapOf("path" to "missing")) }
        failure(Code.INVALID_ARGUMENT) { reader.execute("files.read", emptyMap()) }
        failure(Code.INVALID_ARGUMENT) { reader.execute("files.read", mapOf("path" to ".", "tail" to "yes")) }
        failure(Code.INVALID_ARGUMENT) { reader.execute("files.read", mapOf("path" to ".", "tail" to true, "offset" to 1)) }
        listOf(-1, 0, 101, 1.5, "10", Double.NaN).forEach { value ->
            failure(Code.INVALID_ARGUMENT) { reader.execute("files.list", mapOf("limit" to value)) }
        }
        failure(Code.INVALID_ARGUMENT) { reader.execute("files.find", mapOf("name" to "../logs")) }
        failure(Code.INVALID_ARGUMENT) { reader.execute("files.find", mapOf("name" to "", "maxDepth" to 0)) }
        failure(Code.UNSUPPORTED) { reader.execute("files.write", emptyMap()) }
        failure(Code.PERMISSION_MISSING) { reader.execute("files.list", emptyMap()) { throw AccessDeniedException("fixture") } }
    }

    @Test fun cancellationIsCheckedDuringWalkAndDoesNotMutateStorage() = fixture { root, reader ->
        repeat(30) { Files.writeString(root.resolve("log-$it.txt"), "untouched") }
        val before = Files.list(root).use { it.toList().associate { path -> path.fileName.toString() to hash(Files.readAllBytes(path)) } }
        var checks = 0
        failure(Code.CANCELLED) {
            reader.execute("files.find", mapOf("name" to "log")) {
                if (++checks == 12) throw ToolFailure(Code.CANCELLED, "Stopped")
            }
        }
        assertEquals(12, checks)
        val after = Files.list(root).use { it.toList().associate { path -> path.fileName.toString() to hash(Files.readAllBytes(path)) } }
        assertEquals(before, after)
        assertFalse(Files.exists(root.resolve("Download/ThorToolPack")))
    }

    @Test fun metadataResponseBudgetProducesResumablePagesRatherThanDroppingJson() = fixture { root, reader ->
        var directory = root
        // macOS also limits the complete path to 1024 bytes; stay below that boundary.
        repeat(3) { directory = directory.resolve("long-" + "d".repeat(190)); Files.createDirectory(directory) }
        repeat(100) { Files.writeString(directory.resolve("log-${it.toString().padStart(3, '0')}-" + "文".repeat(40)), "log") }
        val page = reader.execute("files.list", mapOf("path" to directory.toString(), "limit" to 100))
        val items = entries(page)
        assertTrue(items.size in 1..99)
        assertEquals(items.size, page["nextOffset"])
        assertEquals(true, page["truncated"])
        val find = reader.execute("files.find", mapOf("path" to directory.toString(), "name" to "log-", "limit" to 100))
        assertTrue(entries(find, "matches").size in 1..99)
        assertEquals(true, find["matchesTruncated"])
    }

    @Test fun escapedOutputBudgetKeepsAccurateUtf8OffsetsAndTailRetainsTheEnd() = fixture { root, reader ->
        val original = "\"\\\n中文🙂\n".repeat(13000)
        val bytes = original.toByteArray(Charsets.UTF_8)
        Files.write(root.resolve("escaped.txt"), bytes)
        val rebuilt = StringBuilder()
        var offset = 0L
        var sawBudgetLimit = false
        do {
            val page = reader.execute("files.read", mapOf("path" to "escaped.txt", "offset" to offset))
            assertTrue(jsonBytes(page) < 64 * 1024)
            val start = page["offset"] as Long
            val end = page["nextOffset"] as Long
            assertEquals(offset, start)
            assertTrue(end > offset)
            assertEquals(hash(bytes.copyOfRange(start.toInt(), end.toInt())), page["sliceHash"])
            rebuilt.append(page["content"])
            offset = end
            sawBudgetLimit = sawBudgetLimit || page["outputBudgetLimited"] == true
        } while (page["eof"] != true)
        assertTrue(sawBudgetLimit)
        assertEquals(original, rebuilt.toString())
        val tail = reader.execute("files.read", mapOf("path" to "escaped.txt", "tail" to true))
        assertTrue(jsonBytes(tail) < 64 * 1024)
        assertEquals(true, tail["outputBudgetLimited"])
        assertEquals(true, tail["truncated"])
        assertEquals(true, tail["eof"])
        val start = (tail["offset"] as Long).toInt()
        assertEquals(bytes.size.toLong(), tail["nextOffset"])
        assertEquals(String(bytes.copyOfRange(start, bytes.size), Charsets.UTF_8), tail["content"])
    }

    private fun jsonBytes(value: Any?): Int = when (value) {
        null -> 4
        is String -> 2 + value.toByteArray(Charsets.UTF_8).size + value.sumOf { c -> when {
            c == '"' || c == '\\' -> 1
            c.code < 32 -> 5
            else -> 0
        } }
        is Map<*, *> -> 2 + value.entries.sumOf { jsonBytes(it.key.toString()) + 1 + jsonBytes(it.value) + 1 }
        is List<*> -> 2 + value.sumOf { jsonBytes(it) + 1 }
        else -> value.toString().toByteArray(Charsets.UTF_8).size
    }

    @Test fun virtualStorageRootListsOnlyConfiguredAccessibleRootsAndSupportsPaging() = fixture { primary, _ ->
        val removable = Files.createTempDirectory("removable-visible-").toRealPath()
        val unconfigured = Files.createTempDirectory("not-visible-").toRealPath()
        try {
            Files.writeString(unconfigured.resolve("secret"), "not configured")
            val reader = SharedReadFiles(primary, listOf(removable, primary.resolve("missing-volume")))
            val first = reader.execute("files.list", mapOf("path" to "/storage", "limit" to 1))
            assertEquals(true, first["virtual"])
            assertEquals(1, first["nextOffset"])
            assertEquals(1, first["unavailableRoots"])
            val second = reader.execute("files.list", mapOf("path" to "/storage/", "limit" to 1, "offset" to 1))
            assertEquals(false, second["truncated"])
            val roots = entries(first) + entries(second)
            assertEquals(setOf(primary.toString(), removable.toString()), roots.map { it["path"] }.toSet())
            assertEquals(setOf("primary", "removable"), roots.map { it["storageRole"] }.toSet())
            assertFalse(roots.any { it["path"] == unconfigured.toString() })
            failure(Code.PERMISSION_MISSING) { reader.execute("files.stat", mapOf("path" to "/storage/emulated")) }
        } finally {
            Files.walk(removable).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            Files.walk(unconfigured).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test fun concurrentMetadataChangesAreReportedAsBestEffortNotAnAtomicSnapshot() = fixture { root, reader ->
        val path = root.resolve("live.txt")
        Files.writeString(path, "content remains readable")
        var observedTime = System.currentTimeMillis()
        val result = reader.execute("files.read", mapOf("path" to "live.txt")) {
            Files.setLastModifiedTime(path, FileTime.fromMillis(++observedTime))
        }
        assertEquals("content remains readable", result["content"])
        assertEquals(true, result["changedDuringRead"])
        assertEquals("best_effort_metadata_check", result["stability"])
        assertFalse(result.containsKey("fileHash"))
        assertFalse(result.containsKey("stable"))
    }
}
