package dev.thor.tools.diagnostic

import android.os.*
import dev.thor.tools.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real Shizuku UserService. No input method or shell shortcut exists in the screen-control interface. */
class DebugUserService : IDebugService.Stub() {
    private val clock = Clock { SystemClock.elapsedRealtime() }
    private data class Lease(val token: String, val caller: Int, val deadline: Long, val root: String, val cancel: Cancellation, val owner: IBinder, val death: IBinder.DeathRecipient)
    @Volatile private var lease: Lease? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor().apply {
        scheduleWithFixedDelay({ synchronized(this@DebugUserService) { lease?.let { if (clock.now() >= it.deadline) retire(it) } } }, 100, 100, TimeUnit.MILLISECONDS)
    }
    override fun identity() = Process.myUid()
    @Synchronized override fun openLease(token: String, deadlineElapsed: Long, root: String, owner: IBinder) {
        requireTool(Process.myUid() == 2000, Code.PERMISSION_MISSING, "Only shell UID 2000 allowed; Root is refused")
        requireTool(deadlineElapsed > clock.now() && deadlineElapsed - clock.now() <= 60_000 && token.length in 16..128, message = "Invalid short lease")
        requireTool(root == ROOT, Code.PERMISSION_MISSING, "Only explicit shared diagnostic root allowed")
        lease?.let(::retire)
        val cancellation = Cancellation()
        val death = IBinder.DeathRecipient {
            cancellation.cancel()
            synchronized(this) { lease?.takeIf { it.token == token }?.let(::retire) }
        }
        val created = Lease(token, Binder.getCallingUid(), deadlineElapsed, root, cancellation, owner, death)
        owner.linkToDeath(death, 0)
        lease = created
    }
    private fun check(token: String): Lease {
        requireTool(Process.myUid() == 2000, Code.PERMISSION_MISSING, "Unexpected UserService UID")
        val l = lease ?: fail(Code.CANCELLED, "Service lease absent")
        requireTool(Binder.getCallingUid() == l.caller && token == l.token, Code.STALE_AUTHORIZATION, "Service caller/token differs")
        requireTool(clock.now() < l.deadline, Code.TIMEOUT, "UserService lease expired")
        l.cancel.check()
        return l
    }
    override fun execute(token: String, request: String): String {
        return try {
            val l = check(token)
            requireTool(request.toByteArray().size <= 16 * 1024, Code.LIMIT_EXCEEDED, "Binder request too large")
            val obj = JSONObject(request); val args = obj.args()
            val data: Map<String, Any?> = when (args.string("operation")) {
                "shell" -> {
                    args.only("operation", "command", "timeoutMs", "maxBytes")
                    val out = BoundedShell("/system/bin/sh", clock).execute(args.string("command"), args.int("timeoutMs", 15000), args.int("maxBytes", Budgets.TEXT), l.cancel) { check(token) }
                    mapOf("stdout" to out.stdout, "stderr" to out.stderr, "exitCode" to out.exitCode, "durationMs" to out.durationMs, "truncated" to out.truncated,
                        "error" to out.error?.name, "terminationConfirmed" to out.terminationConfirmed, "descendantsMayRemain" to true, "uid" to Process.myUid())
                }
                "files.read", "files.list" -> {
                    args.only("operation", "path", "maxBytes", "limit")
                    // Do not create the directory or infer shell access from its name. User must provision it.
                    val root = File(l.root)
                    requireTool(root.isDirectory, Code.PERMISSION_MISSING, "Create/grant diagnostic directory first")
                    val local = LocalFiles(Paths.get(l.root))
                    if (args.string("operation") == "files.list") {
                        val (names, truncated) = local.list(args.string("path", "."), args.int("limit", 100))
                        mapOf("entries" to names, "truncated" to truncated, "root" to l.root, "uid" to Process.myUid())
                    } else {
                        val max = args.int("maxBytes", Budgets.TEXT); requireTool(max in 1..Budgets.TEXT, Code.LIMIT_EXCEEDED, "Read max 64KiB")
                        val path = local.path(args.string("path"))
                        val read = java.nio.file.Files.newInputStream(path, java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { Budgets.read(it, max) { check(token) } }
                        val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(read.bytes)).toString() }.getOrNull()
                        mapOf("content" to text?.let { Budgets.safeText(it) }, "encoding" to if (text == null) "binary/metadata-only" else "utf-8", "truncated" to read.truncated,
                            "prefixHash" to hash(read.bytes), "size" to java.nio.file.Files.size(path), "uid" to Process.myUid())
                    }
                }
                else -> fail(Code.UNSUPPORTED, "UserService only offers explicit debug shell/shared file reads")
            }
            check(token)
            (json(mapOf("data" to data)) as JSONObject).toString()
        } catch (t: ToolFailure) { JSONObject().put("error", t.code.name).put("message", t.message).toString() }
          catch (t: Exception) { JSONObject().put("error", Code.IO_ERROR.name).put("message", "UserService failure: ${t.javaClass.simpleName}").toString() }
    }
    @Synchronized private fun retire(l: Lease) {
        l.cancel.cancel()
        runCatching { l.owner.unlinkToDeath(l.death, 0) }
        if (lease === l) lease = null
    }
    @Synchronized override fun cancel(token: String) {
        lease?.takeIf { token == it.token && Binder.getCallingUid() == it.caller }?.let(::retire)
    }
    override fun destroy() { synchronized(this) { lease?.let(::retire) }; watchdog.shutdownNow(); kotlin.system.exitProcess(0) }
    companion object { const val ROOT = "/storage/emulated/0/Download/ThorToolPack" }
}
