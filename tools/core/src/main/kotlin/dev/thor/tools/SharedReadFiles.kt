package dev.thor.tools

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.PriorityQueue
import java.util.Locale

/** Bounded, read-only shared-storage observations. No directory, grant, or file is created.
 * Trusted platform roots are canonicalized once; all untrusted descendants refuse symlinks.
 * Observations are not atomic snapshots: metadata changes are reported, never a whole-file hash.
 */
class SharedReadFiles(primaryRoot: Path, removableRoots: List<Path> = emptyList()) {
    private val primary = trustedRoot(primaryRoot)
    private val roots = (listOf(primary) + removableRoots.map(::trustedRoot)).distinct().sortedByDescending { it.nameCount }
    private val aliases = listOf("/sdcard", "/storage/self/primary", "/mnt/sdcard")

    fun execute(operation: String, args: Map<String, Any?>, check: () -> Unit = {}): Map<String, Any?> = io {
        check()
        when (operation) {
            "files.list" -> list(args, check)
            "files.read" -> read(args, check)
            "files.stat" -> stat(args, check)
            "files.find" -> find(args, check)
            else -> fail(Code.UNSUPPORTED, "Unsupported shared-storage read operation: $operation")
        }
    }

    private fun trustedRoot(value: Path): Path = io {
        val absolute = value.toAbsolutePath().normalize()
        // Missing roots remain missing; construction must never create a synthetic directory.
        try { absolute.toRealPath() } catch (_: NoSuchFileException) { absolute }
    }

    private fun target(value: String, check: () -> Unit): Path {
        requireTool(value.isNotBlank() && value.length <= 4096, message = "A non-empty path of at most 4096 characters is required")
        val alias = aliases.firstOrNull { value == it || value.startsWith("$it/") }
        val input = try {
            if (alias != null) primary.resolve(value.removePrefix(alias).removePrefix("/")) else Paths.get(value)
        } catch (_: InvalidPathException) { fail(Code.INVALID_ARGUMENT, "Invalid shared-storage path") }
        val path = (if (input.isAbsolute) input else primary.resolve(input)).toAbsolutePath().normalize()
        val root = roots.firstOrNull { path.startsWith(it) }
            ?: fail(Code.PERMISSION_MISSING, "Path is outside shared-storage roots; only primary and explicitly configured removable storage are readable")
        validate(path, root, check)
        return path
    }

    private fun validate(path: Path, root: Path, check: () -> Unit) {
        // NOFOLLOW at each descendant, including the trusted root if replaced after construction.
        var cursor = root
        check()
        var attrs = Files.readAttributes(cursor, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        requireTool(!attrs.isSymbolicLink, Code.PERMISSION_MISSING, "Shared-storage root changed to a symlink")
        requireTool(attrs.isDirectory, Code.IO_ERROR, "Shared-storage root is not a directory")
        for (segment in root.relativize(path)) {
            check()
            requireTool(attrs.isDirectory, Code.IO_ERROR, "A parent in the shared-storage path is not a directory")
            cursor = cursor.resolve(segment)
            attrs = Files.readAttributes(cursor, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            requireTool(!attrs.isSymbolicLink, Code.PERMISSION_MISSING, "Symlink paths are refused in shared-storage reads")
        }
        requireTool(attrs.isRegularFile || attrs.isDirectory, Code.PERMISSION_MISSING, "Only ordinary files and directories can be read; special files are refused")
    }

    private fun attrs(path: Path, check: () -> Unit): BasicFileAttributes {
        target(path.toString(), check)
        check()
        return Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).also {
            requireTool(!it.isSymbolicLink, Code.PERMISSION_MISSING, "Symlink paths are refused in shared-storage reads")
            requireTool(it.isRegularFile || it.isDirectory, Code.PERMISSION_MISSING, "Special files are refused")
        }
    }

    private fun metadata(path: Path, attributes: BasicFileAttributes): Map<String, Any?> = linkedMapOf(
        "name" to (path.fileName?.toString() ?: path.toString()), "path" to path.toString(),
        "type" to if (attributes.isDirectory) "directory" else "file", "size" to attributes.size(),
        "modifiedAt" to attributes.lastModifiedTime().toMillis()
    )

    private fun stat(args: Map<String, Any?>, check: () -> Unit): Map<String, Any?> {
        val path = target(string(args, "path"), check)
        return metadata(path, attrs(path, check)) + mapOf("source" to "shared-storage", "readOnly" to true)
    }

    private fun list(args: Map<String, Any?>, check: () -> Unit): Map<String, Any?> {
        val requestedPath = string(args, "path", ".")
        if (requestedPath == "/storage" || requestedPath == "/storage/") return listConfiguredRoots(args, check)
        val path = target(requestedPath, check)
        val before = attrs(path, check)
        requireTool(before.isDirectory, Code.INVALID_ARGUMENT, "files.list requires an existing directory")
        val limit = integer(args, "limit", 100, 1, 100)
        val offset = integer(args, "offset", 0, 0, 10000)
        val scanLimit = 20000
        val entries = mutableListOf<Pair<String, BasicFileAttributes>>()
        var scanned = 0
        var skippedSymlinks = 0
        var skippedSpecialFiles = 0
        var unreadable = 0
        var vanished = 0
        var scanTruncated = false
        Files.newDirectoryStream(path).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                check()
                if (scanned == scanLimit) { scanTruncated = true; break }
                val child = iterator.next()
                scanned++
                try {
                    val a = Files.readAttributes(child, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    when {
                        a.isSymbolicLink -> skippedSymlinks++
                        !a.isRegularFile && !a.isDirectory -> skippedSpecialFiles++
                        else -> entries += child.fileName.toString() to a
                    }
                } catch (_: NoSuchFileException) { vanished++ }
                catch (_: AccessDeniedException) { unreadable++ }
                catch (_: SecurityException) { unreadable++ }
            }
        }
        val after = attrs(path, check)
        requireTool(sameIdentity(before, after), Code.IO_ERROR, "Directory identity changed during listing; retry the observation")
        val sorted = entries.sortedBy { it.first }
        val page = budgetedMetadata(sorted.asSequence().drop(offset).take(limit).map { metadata(path.resolve(it.first), it.second) }, path)
        val more = offset + page.size < sorted.size
        return linkedMapOf(
            "path" to path.toString(), "entries" to page, "offset" to offset,
            "nextOffset" to if (more && offset + page.size <= 10000) offset + page.size else null,
            "paginationLimitReached" to (more && offset + page.size > 10000),
            "truncated" to (more || scanTruncated || unreadable > 0 || vanished > 0), "scanned" to scanned, "scanLimit" to scanLimit,
            "scanTruncated" to scanTruncated, "ordering" to "name_ascending",
            "outputBudgetLimited" to (page.size < minOf(limit, maxOf(0, sorted.size - offset))),
            "orderingScope" to if (scanTruncated) "scanned_entries" else "directory",
            "paginationStable" to false, "consistency" to "live_directory_best_effort",
            "changedDuringRead" to (changed(before, after) || vanished > 0),
            "skippedSymlinks" to skippedSymlinks, "skippedSpecialFiles" to skippedSpecialFiles,
            "unreadableEntries" to unreadable,
            "note" to if (scanTruncated) "Directory scan budget reached; ordering and pagination cover only scanned entries. Use a narrower directory." else "Directory contents may change between pages.",
            "source" to "shared-storage", "readOnly" to true
        )
    }

    private fun listConfiguredRoots(args: Map<String, Any?>, check: () -> Unit): Map<String, Any?> {
        val limit = integer(args, "limit", 100, 1, 100)
        val offset = integer(args, "offset", 0, 0, 10000)
        var unavailable = 0
        val accessible = mutableListOf<Map<String, Any?>>()
        for (root in roots) {
            check()
            try { accessible += metadata(root, attrs(root, check)) + mapOf("storageRole" to if (root == primary) "primary" else "removable") }
            catch (e: ToolFailure) {
                if (e.code == Code.CANCELLED || e.code == Code.STALE_AUTHORIZATION) throw e
                if (e.code == Code.PERMISSION_MISSING || e.code == Code.IO_ERROR) unavailable++ else throw e
            } catch (_: AccessDeniedException) { unavailable++ }
            catch (_: NoSuchFileException) { unavailable++ }
            catch (_: SecurityException) { unavailable++ }
        }
        val sorted = accessible.sortedBy { it["name"] as String }
        val page = budgetedMetadata(sorted.asSequence().drop(offset).take(limit), Paths.get("/storage"))
        val more = offset + page.size < sorted.size
        return linkedMapOf(
            "path" to "/storage", "virtual" to true, "entries" to page, "offset" to offset,
            "nextOffset" to if (more) offset + page.size else null, "truncated" to more,
            "scanned" to roots.size, "unavailableRoots" to unavailable,
            "ordering" to "name_ascending", "orderingScope" to "configured_storage_roots",
            "outputBudgetLimited" to (page.size < minOf(limit, maxOf(0, sorted.size - offset))),
            "note" to "Virtual listing of configured accessible storage roots only. The real /storage directory and other users' storage were not enumerated.",
            "source" to "shared-storage", "readOnly" to true
        )
    }

    private fun read(args: Map<String, Any?>, check: () -> Unit): Map<String, Any?> {
        val path = target(string(args, "path"), check)
        val maxBytes = integer(args, "maxBytes", 65536, 1, 65536)
        val requestedOffset = integer(args, "offset", 0, 0, Int.MAX_VALUE).toLong()
        val tail = when (val value = args["tail"]) {
            null -> false
            is Boolean -> value
            else -> fail(Code.INVALID_ARGUMENT, "tail must be a boolean")
        }
        requireTool(!tail || requestedOffset == 0L, message = "tail and a non-zero offset cannot be combined")
        val before = attrs(path, check)
        requireTool(before.isRegularFile, Code.INVALID_ARGUMENT, "files.read requires an ordinary file")
        val start = if (tail) maxOf(0L, before.size() - maxBytes) else minOf(requestedOffset, before.size())
        check()
        // Leaf NOFOLLOW plus pre/post ancestor and identity checks reject detected path races.
        val bytes = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            channel.position(start)
            val buffer = ByteBuffer.allocate(maxBytes + 1)
            while (buffer.hasRemaining()) {
                check()
                if (channel.read(buffer) < 0) break
            }
            buffer.array().copyOf(buffer.position())
        }
        val after = attrs(path, check)
        requireTool(sameIdentity(before, after), Code.IO_ERROR, "File identity changed during reading; retry the observation")
        var first = 0
        // An arbitrary byte offset or tail can begin within a valid UTF-8 code point.
        if (start > 0) while (first < minOf(bytes.size, 3) && continuation(bytes[first])) first++
        val availableEnd = minOf(bytes.size, maxBytes)
        var end = availableEnd
        if (bytes.size > maxBytes && end > first) {
            var lead = end - 1
            while (lead > first && continuation(bytes[lead])) lead--
            val expected = utf8Length(bytes[lead])
            if (expected > 1 && end - lead < expected) end = lead
        }
        if (first > end) first = end
        requireTool(end > first || bytes.isEmpty() || start + first >= after.size(), Code.LIMIT_EXCEEDED,
            "maxBytes is too small for the next complete UTF-8 character; use at least 4 bytes")
        var slice = bytes.copyOfRange(first, end)
        requireTool(slice.none { it == 0.toByte() }, Code.UNSUPPORTED, "File contains binary data; files.read supports UTF-8 text only")
        var text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(slice)).toString()
        } catch (_: CharacterCodingException) { fail(Code.UNSUPPORTED, "File is not valid UTF-8 text; binary or unsupported encoding") }
        var safe = Budgets.safeText(text)
        val outputBudget = minOf(48 * 1024, 60 * 1024 - jsonStringBytes(path.toString()) - 4096).coerceAtLeast(1024)
        val outputBudgetLimited = jsonStringBytes(safe) > outputBudget
        if (outputBudgetLimited) {
            // Preserve byte offsets by shortening the original text, then apply redaction again.
            // Tail reads retain the end of the file; forward reads retain the beginning of the slice.
            var low = 0
            var high = text.codePointCount(0, text.length)
            var chosen = ""
            var chosenSafe = ""
            while (low <= high) {
                check()
                val count = (low + high) / 2
                val candidate = if (tail) text.substring(text.offsetByCodePoints(text.length, -count))
                    else text.substring(0, text.offsetByCodePoints(0, count))
                val candidateSafe = Budgets.safeText(candidate)
                if (jsonStringBytes(candidateSafe) <= outputBudget) {
                    chosen = candidate; chosenSafe = candidateSafe; low = count + 1
                } else high = count - 1
            }
            text = chosen
            safe = chosenSafe
            slice = text.toByteArray(Charsets.UTF_8)
            if (tail) first = end - slice.size else end = first + slice.size
        }
        val actualOffset = start + first
        val nextOffset = start + end
        val eof = nextOffset >= after.size() && end == bytes.size
        return linkedMapOf(
            "path" to path.toString(), "content" to safe, "encoding" to "UTF-8",
            "requestedOffset" to requestedOffset, "offset" to actualOffset, "nextOffset" to nextOffset,
            "bytesRead" to slice.size, "maxBytes" to maxBytes, "tail" to tail,
            "size" to after.size(), "sizeAtOpen" to before.size(), "modifiedAt" to after.lastModifiedTime().toMillis(),
            "sliceHash" to hash(slice), "hashScope" to "raw_slice_bytes", "redacted" to (safe != text),
            "outputBudgetLimited" to outputBudgetLimited,
            "truncated" to (actualOffset > 0 || !eof), "eof" to eof,
            "changedDuringRead" to changed(before, after), "stability" to "best_effort_metadata_check",
            "source" to "shared-storage", "readOnly" to true
        )
    }

    private fun find(args: Map<String, Any?>, check: () -> Unit): Map<String, Any?> {
        val root = target(string(args, "path", "."), check)
        val query = string(args, "name")
        requireTool(query.isNotBlank() && query.length <= 256 && '/' !in query && '\\' !in query,
            message = "name must be a non-empty filename substring, at most 256 characters, without path separators")
        val maxDepth = integer(args, "maxDepth", 4, 0, 8)
        val maxEntries = integer(args, "maxEntries", 2000, 1, 10000)
        val limit = integer(args, "limit", 50, 1, 100)
        data class Directory(val path: Path, val depth: Int)
        val queue = PriorityQueue<Directory>(compareBy<Directory> { it.path.toString() })
        val matches = PriorityQueue<Map<String, Any?>>(compareByDescending { it["path"] as String })
        var matchCount = 0
        val queryLower = query.lowercase(Locale.ROOT)
        var scanned = 0
        var skippedSymlinks = 0
        var skippedSpecialFiles = 0
        var unreadable = 0
        var vanished = 0
        var scanTruncated = false
        var depthLimited = false
        var directoryChanged = false
        fun observe(path: Path, depth: Int, a: BasicFileAttributes) {
            if (path.fileName?.toString()?.lowercase(Locale.ROOT)?.contains(queryLower) == true) {
                matchCount++
                matches += metadata(path, a)
                if (matches.size > limit) matches.remove()
            }
            if (a.isDirectory) {
                if (depth < maxDepth) queue += Directory(path, depth) else depthLimited = true
            }
        }
        scanned++
        observe(root, 0, attrs(root, check))
        while (queue.isNotEmpty()) {
            check()
            val directory = queue.remove()
            try {
                val before = attrs(directory.path, check)
                Files.newDirectoryStream(directory.path).use { stream ->
                    val iterator = stream.iterator()
                    while (iterator.hasNext()) {
                        check()
                        if (scanned >= maxEntries) { scanTruncated = true; break }
                        val child = iterator.next()
                        scanned++
                        try {
                            val a = Files.readAttributes(child, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                            when {
                                a.isSymbolicLink -> skippedSymlinks++
                                !a.isRegularFile && !a.isDirectory -> skippedSpecialFiles++
                                else -> observe(child, directory.depth + 1, a)
                            }
                        } catch (_: NoSuchFileException) { vanished++ }
                        catch (_: AccessDeniedException) { unreadable++ }
                        catch (_: SecurityException) { unreadable++ }
                    }
                }
                val after = attrs(directory.path, check)
                requireTool(sameIdentity(before, after), Code.IO_ERROR, "Directory identity changed during search; retry the observation")
                directoryChanged = directoryChanged || changed(before, after)
            } catch (_: AccessDeniedException) { unreadable++ }
            catch (_: NoSuchFileException) { vanished++ }
            if (scanTruncated) break
        }
        val page = budgetedMetadata(matches.sortedBy { it["path"] as String }.asSequence(), root)
        return linkedMapOf(
            "path" to root.toString(), "name" to query, "matches" to page,
            "scanned" to scanned, "maxEntries" to maxEntries, "maxDepth" to maxDepth,
            "truncated" to (scanTruncated || matchCount > page.size || unreadable > 0 || vanished > 0), "scanTruncated" to scanTruncated,
            "matchesTruncated" to (matchCount > page.size), "depthLimited" to depthLimited,
            "outputBudgetLimited" to (page.size < matches.size),
            "ordering" to "path_ascending", "orderingScope" to "scanned_entries",
            "skippedSymlinks" to skippedSymlinks, "skippedSpecialFiles" to skippedSpecialFiles,
            "unreadableEntries" to unreadable, "changedDuringRead" to (vanished > 0 || directoryChanged),
            "note" to "Literal, case-insensitive filename substring search. Root depth is 0. Only inspected entries are reported; narrow the path or raise budgets if truncated.",
            "source" to "shared-storage", "readOnly" to true
        )
    }

    /** Reserve headroom below the outer 64 KiB tool budget, including JSON escaping. */
    private fun budgetedMetadata(values: Sequence<Map<String, Any?>>, root: Path): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        var remaining = minOf(48 * 1024, 60 * 1024 - jsonStringBytes(root.toString()) - 2048)
        for (value in values) {
            val estimate = 192 + value.values.sumOf { item ->
                if (item is String) jsonStringBytes(item) else 24
            }
            if (estimate > remaining) break
            remaining -= estimate
            result += value
        }
        return result
    }

    private fun jsonStringBytes(value: String): Int = value.sumOf { c ->
        when {
            c == '\\' || c == '"' || c == '/' -> 2
            c.code < 32 || c.code in 0x7f..0x9f || c.code in 0x2000..0x20ff -> 6
            c.code < 128 -> 1
            c.code < 2048 -> 2
            else -> 3 // Conservative for UTF-16 surrogate pairs as well.
        }
    }

    private fun string(args: Map<String, Any?>, key: String, default: String? = null): String =
        when (val value = args[key]) {
            null -> default ?: fail(Code.INVALID_ARGUMENT, "$key is required")
            is String -> value
            else -> fail(Code.INVALID_ARGUMENT, "$key must be a string")
        }

    private fun integer(args: Map<String, Any?>, key: String, default: Int, min: Int, max: Int): Int {
        val number = args[key] ?: return default
        requireTool(number is Number, message = "$key must be an integer")
        val value = (number as Number).toDouble()
        requireTool(value.isFinite() && value >= min && value <= max && value % 1.0 == 0.0,
            message = "$key must be an integer in $min..$max")
        return value.toInt()
    }

    private fun continuation(byte: Byte) = byte.toInt() and 0xc0 == 0x80
    private fun utf8Length(byte: Byte): Int = when (byte.toInt() and 0xff) {
        in 0xc2..0xdf -> 2
        in 0xe0..0xef -> 3
        in 0xf0..0xf4 -> 4
        else -> 1
    }
    private fun sameIdentity(a: BasicFileAttributes, b: BasicFileAttributes) =
        a.isRegularFile == b.isRegularFile && a.isDirectory == b.isDirectory &&
            (a.fileKey() == null || b.fileKey() == null || a.fileKey() == b.fileKey())
    private fun changed(a: BasicFileAttributes, b: BasicFileAttributes) =
        !sameIdentity(a, b) || a.size() != b.size() || a.lastModifiedTime() != b.lastModifiedTime()

    private fun <T> io(action: () -> T): T = try { action() }
    catch (e: ToolFailure) { throw e }
    catch (_: AccessDeniedException) { fail(Code.PERMISSION_MISSING, "System denied shared-storage access; check Shizuku/shell access or an explicit storage grant") }
    catch (_: SecurityException) { fail(Code.PERMISSION_MISSING, "System denied shared-storage access") }
    catch (_: NoSuchFileException) { fail(Code.IO_ERROR, "Shared-storage path does not exist; no directory was created") }
    catch (e: DirectoryIteratorException) {
        if (e.cause is AccessDeniedException) fail(Code.PERMISSION_MISSING, "System denied directory enumeration")
        fail(Code.IO_ERROR, "Shared-storage directory enumeration failed")
    }
    catch (e: java.nio.file.FileSystemException) {
        val reason = e.reason.orEmpty().lowercase(Locale.ROOT)
        if ("permission denied" in reason || "operation not permitted" in reason)
            fail(Code.PERMISSION_MISSING, "System denied shared-storage access")
        fail(Code.IO_ERROR, "Shared-storage observation failed; path may have changed or storage may be unavailable")
    }
    catch (_: java.io.IOException) { fail(Code.IO_ERROR, "Shared-storage observation failed; path may have changed or storage may be unavailable") }
}
