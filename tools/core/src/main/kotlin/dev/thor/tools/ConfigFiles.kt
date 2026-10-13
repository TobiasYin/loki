package dev.thor.tools

import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
/** Deliberately only used for the host's user-approved synthetic config root, never session/credential storage. */
interface ConfigFilePort {
    fun path(value: String, directory: Boolean = false): Path
    fun metadata(value: String): Map<String, String> = emptyMap()
    fun read(value: String, maxBytes: Int = Budgets.CONFIG): ByteArray
    fun replace(value: String, expected: String, bytes: ByteArray, checkpoint: Checkpoint): Boolean
}
class LocalFiles(requestedRoot: Path) : ConfigFilePort {
    val root: Path
    init {
        Files.createDirectories(requestedRoot)
        requireTool(!Files.isSymbolicLink(requestedRoot), Code.PERMISSION_MISSING, "Explicit root cannot itself be a symlink")
        // Resolve platform-owned aliases such as macOS /var once for the trusted root only.
        root = requestedRoot.toRealPath()
        path(root.toString(), directory = true)
    }
    override fun path(value: String, directory: Boolean): Path {
        val input = Paths.get(value)
        val p = (if (input.isAbsolute) input else root.resolve(input)).toAbsolutePath().normalize()
        requireTool(p.startsWith(root), Code.PERMISSION_MISSING, "Path outside explicit config root")
        var cursor: Path? = p
        while (cursor != null) {
            requireTool(!Files.isSymbolicLink(cursor), Code.PERMISSION_MISSING, "Symlink paths are refused")
            cursor = cursor.parent
        }
        requireTool(if (directory) Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) else Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS), Code.IO_ERROR, "Regular existing target required")
        return p
    }
    override fun read(value: String, maxBytes: Int): ByteArray {
        val p = path(value)
        Files.newInputStream(p, READ, LinkOption.NOFOLLOW_LINKS).use {
            val read = Budgets.read(it, maxBytes)
            requireTool(!read.truncated, Code.LIMIT_EXCEEDED, "Config exceeds read budget")
            return read.bytes
        }
    }
    override fun metadata(value: String): Map<String, String> {
        val p = path(value)
        return mapOf("size" to Files.size(p).toString(), "mtime" to Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis().toString(),
            "permissions" to Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS).map { it.name }.sorted().joinToString(","), "source" to "explicit-synthetic-root")
    }
    fun list(value: String, limit: Int = 100): Pair<List<String>, Boolean> {
        requireTool(limit in 1..100, Code.LIMIT_EXCEEDED, "List limit must be 1..100")
        Files.newDirectoryStream(path(value, true)).use { stream ->
            val names = mutableListOf<String>()
            for (f in stream) {
                if (names.size == limit) return names.sorted() to true
                names.add(f.fileName.toString().take(512))
            }
            return names.sorted() to false
        }
    }
    override fun replace(value: String, expected: String, bytes: ByteArray, checkpoint: Checkpoint): Boolean {
        val p = path(value)
        val perms = Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS)
        val temporary = Files.createTempFile(p.parent, ".thor-", ".pending")
        try {
            FileChannel.open(temporary, WRITE, LinkOption.NOFOLLOW_LINKS).use { ch ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) { checkpoint.check(); ch.write(buffer) }
                ch.force(true)
            }
            Files.setPosixFilePermissions(temporary, perms)
            checkpoint.check()
            requireTool(hash(read(value)) == expected, Code.HASH_CONFLICT, "Target changed before atomic commit")
            path(value) // Recheck each parent; hostile shared-directory races still need OS support (see notes).
            checkpoint.check()
            checkpoint.effect { Files.move(temporary, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            return true
        } catch (_: AtomicMoveNotSupportedException) {
            fail(Code.UNSUPPORTED, "Filesystem cannot provide atomic replace; no silent non-atomic fallback")
        } finally { Files.deleteIfExists(temporary) }
    }
}
data class Backup(val id: String, val path: String, val oldHash: String, val bytes: ByteArray, val time: Long, val metadata: Map<String, String> = emptyMap())
class EncryptedVault(private val dir: Path, private val key: SecretKey, private val clock: Clock,
    private val maxPerTarget: Int = 10, private val maxTotalBytes: Long = 100L * 1024 * 1024) {
    init { Files.createDirectories(dir) }
    @Synchronized fun save(path: String, bytes: ByteArray, metadata: Map<String, String> = emptyMap()): Backup {
        requireTool(bytes.size <= Budgets.CONFIG - 4096 && path.toByteArray().size <= 2048, Code.LIMIT_EXCEEDED, "Backup exceeds config budget")
        requireTool(metadata.size <= 16 && metadata.entries.sumOf { it.key.toByteArray().size + it.value.toByteArray().size } <= 1024, Code.LIMIT_EXCEEDED, "Backup metadata exceeds budget")
        val all = Files.newDirectoryStream(dir, "*.backup").use { it.toList() }
        requireTool(all.count { load(it.fileName.toString().removeSuffix(".backup")).path == path } < maxPerTarget, Code.LIMIT_EXCEEDED, "10 backups per target; explicitly clean up before writing")
        requireTool(all.sumOf { Files.size(it) } + bytes.size + 4096 <= maxTotalBytes, Code.LIMIT_EXCEEDED, "Backup quota full; preserve recovery points")
        val backup = Backup(UUID.randomUUID().toString(), path, hash(bytes), bytes, clock.now(), metadata)
        val plain = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { it.writeInt(1); it.writeUTF(path); it.writeUTF(backup.oldHash); it.writeLong(backup.time); it.writeInt(metadata.size); metadata.forEach { (k,v) -> it.writeUTF(k); it.writeUTF(v) }; it.writeInt(bytes.size); it.write(bytes) } }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(backup.id.toByteArray())
        val encrypted = cipher.iv + cipher.doFinal(plain)
        val file = dir.resolve("${backup.id}.backup")
        FileChannel.open(file, CREATE_NEW, WRITE).use { ch ->
            val buffer = ByteBuffer.wrap(encrypted)
            while (buffer.hasRemaining()) ch.write(buffer)
            ch.force(true)
        }
        requireTool(load(backup.id).oldHash == backup.oldHash, Code.IO_ERROR, "Backup readback failed")
        return backup
    }
    @Synchronized fun load(id: String): Backup {
        requireTool(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false), message = "Invalid backup ID")
        val encoded = Files.newInputStream(dir.resolve("$id.backup"), READ, LinkOption.NOFOLLOW_LINKS).use {
            val bytes = Budgets.read(it, Budgets.CONFIG)
            requireTool(!bytes.truncated, Code.LIMIT_EXCEEDED, "Encrypted backup exceeds budget")
            bytes.bytes
        }
        requireTool(encoded.size >= 28, Code.IO_ERROR, "Invalid encrypted backup")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encoded.copyOfRange(0, 12)))
        cipher.updateAAD(id.toByteArray())
        try {
            DataInputStream(ByteArrayInputStream(cipher.doFinal(encoded.copyOfRange(12, encoded.size)))).use {
                requireTool(it.readInt() == 1, Code.IO_ERROR, "Unsupported backup format")
                val path = it.readUTF(); val hash = it.readUTF(); val time = it.readLong()
                val entries = it.readInt(); requireTool(entries in 0..16, Code.IO_ERROR, "Corrupt backup metadata")
                val metadata = (0 until entries).associate { _ -> it.readUTF() to it.readUTF() }
                val length = it.readInt()
                requireTool(length in 0..Budgets.CONFIG, Code.IO_ERROR, "Corrupt backup length")
                val bytes = ByteArray(length); it.readFully(bytes)
                requireTool(hash(bytes) == hash && it.read() == -1, Code.IO_ERROR, "Backup hash failed")
                return Backup(id, path, hash, bytes, time, metadata)
            }
        } catch (_: javax.crypto.AEADBadTagException) { fail(Code.IO_ERROR, "Backup authentication failed") }
    }
}
data class Approval(val path: String, val oldHash: String, val newHash: String)
data class WriteReceipt(val path: String, val newHash: String, val backupId: String, val atomic: Boolean, val verified: Boolean)
class ConfigTransactions(private val files: ConfigFilePort, private val vault: EncryptedVault, private val validate: (ByteArray) -> Unit) {
    @Synchronized fun backup(path: String, expectedHash: String, checkpoint: Checkpoint): Backup {
        checkpoint.check()
        val p = files.path(path).toString()
        val bytes = files.read(p)
        requireTool(hash(bytes) == expectedHash, Code.HASH_CONFLICT, "Hash changed before backup")
        val b = vault.save(p, bytes, files.metadata(p))
        checkpoint.check()
        return b
    }
    @Synchronized fun write(path: String, expectedHash: String, content: ByteArray, backupId: String, approval: Approval?, checkpoint: Checkpoint): WriteReceipt {
        requireTool(content.size <= Budgets.CONFIG - 4096, Code.LIMIT_EXCEEDED, "Config exceeds 1 MiB including backup envelope reserve")
        val p = files.path(path).toString()
        requireTool(approval == Approval(p, expectedHash, hash(content)), Code.APPROVAL_REQUIRED, "Approve exact path + old/new hashes and diff")
        validate(content)
        checkpoint.check()
        requireTool(hash(files.read(p)) == expectedHash, Code.HASH_CONFLICT, "Concurrent config change")
        val b = vault.load(backupId)
        requireTool(b.path == p && b.oldHash == expectedHash, Code.HASH_CONFLICT, "Backup does not match this original target")
        val atomic = files.replace(p, expectedHash, content, checkpoint)
        checkpoint.submitted()
        val readback = files.read(p)
        requireTool(hash(readback) == hash(content), Code.IO_ERROR, "Committed config readback differs; recovery backup retained")
        validate(readback)
        checkpoint.check()
        return WriteReceipt(p, hash(readback), backupId, atomic, true)
    }
    @Synchronized fun restore(backupId: String, expectedCurrentHash: String, approval: Approval?, checkpoint: Checkpoint): WriteReceipt {
        checkpoint.check()
        val desired = vault.load(backupId)
        requireTool(approval == Approval(desired.path, expectedCurrentHash, desired.oldHash), Code.APPROVAL_REQUIRED, "Approve exact restore diff")
        val before = backup(desired.path, expectedCurrentHash, checkpoint)
        return write(desired.path, expectedCurrentHash, desired.bytes, before.id, approval, checkpoint)
    }
}
