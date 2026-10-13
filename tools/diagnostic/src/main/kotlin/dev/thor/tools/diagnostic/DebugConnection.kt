package dev.thor.tools.diagnostic

import android.content.*
import android.os.*
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import dev.thor.tools.*
import org.json.JSONObject
import java.util.UUID

class DebugConnection(private val context: Context, private val invalidated: () -> Unit) : ServiceConnection {
    @Volatile private var service: IDebugService? = null
    private val hostBinder = Binder()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val args = Shizuku.UserServiceArgs(ComponentName(context, DebugUserService::class.java))
        .daemon(false).processNameSuffix("toolpack_debug").tag("thor-toolpack-debug").version(1)
    private val dead = Shizuku.OnBinderDeadListener { service = null; invalidated() }
    private val received = Shizuku.OnBinderReceivedListener { /* User must explicitly bind after permission */ }
    init { Shizuku.addBinderDeadListener(dead); Shizuku.addBinderReceivedListenerSticky(received) }
    fun availability(): Availability = try {
        val backend = "shizuku-userservice/shell-uid-2000"
        when {
            !Shizuku.pingBinder() -> Availability(Status.BACKEND_MISSING, "Install/start Shizuku with ADB; no app-UID fallback", backend)
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Availability(Status.PERMISSION_MISSING, "Explicit Shizuku grant missing", backend)
            Shizuku.getUid() != 2000 -> Availability(Status.UNSUPPORTED, "Root/other backend refused", backend)
            service == null -> Availability(Status.TEMPORARILY_UNAVAILABLE, "Bind UserService, then refresh availability", backend)
            else -> Availability(Status.READY, "Bound UID-checked UserService; Thor permissions unverified", backend)
        }
    } catch (_: Exception) { Availability(Status.TEMPORARILY_UNAVAILABLE, "Shizuku binder/permission changed", "shizuku-userservice/shell-uid-2000") }
    fun requestAndBind() {
        requireTool(Shizuku.pingBinder(), Code.BACKEND_UNAVAILABLE, "Install/start Shizuku first")
        requireTool(Shizuku.getUid() == 2000, Code.UNSUPPORTED, "Root backend is refused")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(41)
        else Shizuku.bindUserService(args, this)
    }
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        try {
            val actual = IDebugService.Stub.asInterface(binder)
            requireTool(actual.identity() == 2000, Code.UNSUPPORTED, "Actual UserService UID is not shell")
            binder?.linkToDeath({ service = null; invalidated() }, 0)
            service = actual
        } catch (_: Exception) { service = null; invalidated() }
    }
    override fun onServiceDisconnected(name: ComponentName?) { service = null; invalidated() }
    fun call(runId: String, request: Map<String, Any?>, cp: Checkpoint): Map<String, Any?> {
        val available = availability()
        requireTool(available.status == Status.READY, Code.BACKEND_UNAVAILABLE, available.reason)
        val remote = service ?: fail(Code.BACKEND_UNAVAILABLE, "UserService disappeared")
        val token = "$runId:${UUID.randomUUID()}"
        cp.check()
        remote.openLease(token, SystemClock.elapsedRealtime() + 60_000, DebugUserService.ROOT, hostBinder)
        cp.cancellation.register { worker.submit { runCatching { remote.cancel(token) } } }
        try {
            cp.check()
            requireTool(availability().status == Status.READY, Code.PERMISSION_MISSING, "Shizuku readiness changed before IPC")
            cp.submitted()
            val response = JSONObject(remote.execute(token, (json(request) as JSONObject).toString()))
            if (response.has("error")) fail(Code.valueOf(response.getString("error")), response.optString("message"))
            val data = response.getJSONObject("data").args()
            (data["error"] as? String)?.let { fail(Code.valueOf(it), "Shell stopped; direct child termination does not confirm descendants stopped") }
            cp.check()
            requireTool(availability().status == Status.READY, Code.PERMISSION_MISSING, "Shizuku readiness changed; discard late output")
            return data
        } finally { runCatching { remote.cancel(token) } }
    }
    fun shell(run: String, command: String, timeout: Int, max: Int, cp: Checkpoint) = call(run, mapOf("operation" to "shell", "command" to command, "timeoutMs" to timeout, "maxBytes" to max), cp)
    fun close() { invalidated(); runCatching { Shizuku.unbindUserService(args, this, true) }; service = null; Shizuku.removeBinderDeadListener(dead); Shizuku.removeBinderReceivedListener(received); worker.shutdownNow() }
}
