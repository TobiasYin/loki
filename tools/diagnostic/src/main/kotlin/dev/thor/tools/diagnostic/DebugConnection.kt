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
    @Volatile private var closed = false
    @Volatile private var bindingRequested = false
    @Volatile private var accessibilityPrepared = false
    @Volatile private var setupMessage = "等待已授权的 Shizuku shell 服务"
    val accessibilitySetup: String get() = when {
        accessibilityPrepared && ThorAccessibilityService.instance!=null -> "已通过 Shizuku 自动开启本应用无障碍"
        accessibilityPrepared -> "Shizuku 已启用本应用无障碍，等待系统连接"
        else -> setupMessage
    }
    private val hostBinder = Binder()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val args = Shizuku.UserServiceArgs(ComponentName(context, DebugUserService::class.java))
        .daemon(false).processNameSuffix("toolpack_debug").tag("thor-toolpack-debug").version(14)
    private val presentationReader:(Int,IntArray)->List<PresentationWindowProof> = {display,ids->
        val remote=service ?: fail(Code.BACKEND_UNAVAILABLE,"Presentation metadata service is not bound")
        requireTool(!closed && availability().status==Status.READY,Code.PERMISSION_MISSING,"Presentation metadata backend is not authorized")
        val raw=remote.presentationEvidence(display,ids)
        requireTool(raw.toByteArray().size<=16384,Code.LIMIT_EXCEEDED,"Presentation metadata response too large")
        val response=JSONObject(raw)
        if(response.has("error")) {
            val safeCode=runCatching {Code.valueOf(response.getString("error"))}.getOrNull()
            ThorAccessibilityService.recordPresentationReaderFailure(display,safeCode)
        }
        requireTool(!response.has("error"),Code.UNSUPPORTED,"Presentation metadata unavailable")
        val values=response.getJSONArray("proofs")
        requireTool(values.length() in 1..8,Code.UNSUPPORTED,"Presentation metadata missing")
        (0 until values.length()).map {i->
            val p=values.getJSONObject(i);val f=p.getJSONArray("frame")
            requireTool(f.length()==4,Code.UNSUPPORTED,"Presentation frame missing")
            PresentationWindowProof(p.getInt("displayId"),p.getInt("windowId"),p.getString("token"),p.getInt("ownerUid"),p.getString("packageName"),p.getString("flags"),
                TouchableBounds(f.getInt(0),f.getInt(1),f.getInt(2),f.getInt(3)),p.getInt("rawWindowType"))
        }
    }
    private val dead = Shizuku.OnBinderDeadListener { bindingRequested=false; service = null; ThorAccessibilityService.invalidatePresentationEvidence(); invalidated() }
    private val permission = Shizuku.OnRequestPermissionResultListener { requestCode, grant ->
        if(requestCode == 41 && grant == PackageManager.PERMISSION_GRANTED) runCatching {
            requireTool(Shizuku.getUid() == 2000,Code.UNSUPPORTED,"Root backend refused")
            bindGranted()
        }.onFailure { invalidated() }
    }
    private val received = Shizuku.OnBinderReceivedListener { bindGranted() }
    init { ThorAccessibilityService.installPresentationReader(this,presentationReader); Shizuku.addRequestPermissionResultListener(permission); Shizuku.addBinderDeadListener(dead); Shizuku.addBinderReceivedListenerSticky(received) }
    @Synchronized private fun bindGranted() {
        if(closed || bindingRequested || service!=null)return
        runCatching {
            if(!Shizuku.pingBinder() || Shizuku.getUid()!=2000 || Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)return
            bindingRequested=true;Shizuku.bindUserService(args,this)
        }.onFailure {bindingRequested=false;setupMessage="Shizuku 绑定失败；可在设置中重试"}
    }
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
    @Volatile private var gamepadCheckedAt=0L
    @Volatile private var gamepadStatus: JSONObject?=null
    fun gamepadInfo(): Map<String,Any?> {
        val available=availability()
        if(available.status!=Status.READY)return mapOf("availability" to available.status.name.lowercase(),"reason" to available.reason)
        return try {
            val response=JSONObject(service!!.gamepadStatus())
            if(response.has("error"))mapOf("availability" to "unsupported","reason" to response.optString("message"))
            else response.getJSONObject("data").args()+mapOf("availability" to "ready")
        }catch(_:Exception){mapOf("availability" to "temporarily_unavailable","reason" to "Controller inspection interrupted")}
    }
    /** Read-only, lease-free inspection: availability polling must never cancel held input. */
    fun gamepadAvailability(toolId: String): Availability {
        val base=availability();if(base.status!=Status.READY)return base
        val backend=if(toolId=="input.set_axes")"shizuku-evdev/odin-six-axes" else "shizuku-evdev/odin-buttons"
        return try {
            val now=SystemClock.elapsedRealtime()
            val status=if(now-gamepadCheckedAt<1000)gamepadStatus ?: JSONObject(service!!.gamepadStatus()) else JSONObject(service!!.gamepadStatus()).also {gamepadStatus=it;gamepadCheckedAt=now}
            if(status.has("error"))Availability(Status.UNSUPPORTED,status.optString("message","Native gamepad inspection failed"),backend)
            else {
                val data=status.getJSONObject("data")
                val source=if(toolId=="input.press_button")"gamepad" else "joystick"
                if(!data.optBoolean(source))Availability(Status.UNSUPPORTED,data.optString("evdevBackendError","Fixed physical evdev backend unavailable"),backend)
                else Availability(Status.READY,"${data.optString("deviceName")} #${data.optInt("deviceId")}: bounded native $source submission available; receiver delivery requires independent verification",if(source=="joystick")"shizuku-evdev/odin-six-axes" else backend)
            }
        }catch(_:Exception){Availability(Status.TEMPORARILY_UNAVAILABLE,"Native gamepad inspection failed; refresh after Shizuku/controller reconnect",backend)}
    }
    fun gamepad(runId: String, toolId: String, args: Map<String,Any?>, displayId: Int, cp: Checkpoint,expectedPackage: String?=null): Map<String,Any?> {
        GamepadCommand.parse(toolId,args)
        return call(runId,mapOf("operation" to "gamepad","toolId" to toolId,"args" to args,"displayId" to displayId,"expectedPackage" to expectedPackage),cp)
    }
    fun navigate(runId:String,args:Map<String,Any?>,displayId:Int,expectedPackage:String?,cp:Checkpoint):Map<String,Any?> {
        val command=NavigationCommand.parse(args)
        return call(runId,mapOf("operation" to "navigation","action" to command.action,"displayId" to displayId,"expectedPackage" to expectedPackage),cp)
    }
    fun releaseRunInputs(runId: String): String = runCatching {
        val remote=service ?: return@runCatching "No bound gamepad backend"
        remote.releaseGamepad(runId)
    }.getOrDefault("Gamepad cleanup unavailable; service watchdog remains responsible for bounded release")
    fun requestAndBind() {
        requireTool(Shizuku.pingBinder(), Code.BACKEND_UNAVAILABLE, "Install/start Shizuku first")
        requireTool(Shizuku.getUid() == 2000, Code.UNSUPPORTED, "Root backend is refused")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(41)
        else bindGranted()
    }
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        try {
            val actual = IDebugService.Stub.asInterface(binder)
            requireTool(actual.identity() == 2000, Code.UNSUPPORTED, "Actual UserService UID is not shell")
            binder?.linkToDeath({ bindingRequested=false; service = null; ThorAccessibilityService.invalidatePresentationEvidence(); invalidated() }, 0)
            worker.submit {
                val token="accessibility:${UUID.randomUUID()}"
                try {
                    check(!closed)
                    actual.openLease(token,SystemClock.elapsedRealtime()+15000,DebugUserService.ROOT,hostBinder)
                    val request=JSONObject().put("operation","accessibility.ensure").put("reconnect",ThorAccessibilityService.instance==null)
                    val result=JSONObject(actual.execute(token,request.toString()))
                    check(!result.has("error") && result.getJSONObject("data").getBoolean("enabled"))
                    accessibilityPrepared=true
                } catch (_: Exception) {accessibilityPrepared=false;setupMessage="自动开启无障碍失败；可在系统设置中手动开启"}
                finally {
                    runCatching {actual.cancel(token)}
                    if(!closed){service=actual;ThorAccessibilityService.invalidatePresentationEvidence()}
                    bindingRequested=false
                }
            }
        } catch (_: Exception) { bindingRequested=false;service = null; invalidated() }
    }
    override fun onServiceDisconnected(name: ComponentName?) { bindingRequested=false;service = null;ThorAccessibilityService.invalidatePresentationEvidence(); invalidated() }
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
            val raw = if(request["operation"] == "shell") {
                val command=request.getValue("command") as String
                val approval=request["approvalHash"] as? String
                ShellCommandPolicy.classify(command).requireAllowed(approval,command)
                cp.effect {
                    val start=JSONObject(remote.startShell(token,command,(request["timeoutMs"] as Number).toInt(),(request["maxBytes"] as Number).toInt(),approval.orEmpty()))
                    if(start.has("error"))fail(Code.valueOf(start.getString("error")),start.optString("message"))
                    requireTool(start.optBoolean("started"),Code.IO_ERROR,"Command submission was not acknowledged")
                }
                remote.awaitShell(token)
            } else if(request["operation"]=="navigation") {
                val preparation=JSONObject(remote.execute(token,(json(request+mapOf("operation" to "navigation.prepare")) as JSONObject).toString()))
                if(preparation.has("error"))fail(Code.valueOf(preparation.getString("error")),preparation.optString("message"))
                requireTool(preparation.getJSONObject("data").optBoolean("prepared"),Code.IO_ERROR,"Navigation preparation was not acknowledged")
                var response=""
                cp.check()
                cp.effect {
                    // A lost Binder reply cannot prove that DOWN was not already enqueued.
                    cp.submitted()
                    response=remote.execute(token,"{\"operation\":\"navigation.start\"}")
                    val started=JSONObject(response)
                    if(started.has("error"))fail(Code.valueOf(started.getString("error")),started.optString("message"))
                }
                response
            } else if(request["operation"]=="gamepad") {
                val payload=request.filterKeys {it in setOf("toolId","displayId","args","expectedPackage")}
                val serialized=(json(payload) as JSONObject).toString()
                val preparation=JSONObject(remote.prepareGamepad(token,serialized))
                if(preparation.has("error"))fail(Code.valueOf(preparation.getString("error")),preparation.optString("message"))
                requireTool(preparation.getJSONObject("data").optBoolean("prepared"),Code.IO_ERROR,"Gamepad preparation was not acknowledged")
                cp.check()
                cp.effect {
                    val started=JSONObject(remote.startGamepad(token,serialized))
                    if(started.has("error")) {
                        if(started.optBoolean("submitted"))cp.submitted()
                        fail(Code.valueOf(started.getString("error")),started.optString("message"))
                    }
                    requireTool(started.getJSONObject("data").optBoolean("started"),Code.IO_ERROR,"Gamepad submission was not acknowledged")
                }
                var result: String
                while(true) {
                    // Waiting is outside the authority commit lock. A stop, target change or permission
                    // revocation can cancel the lease while the service timer owns final release.
                    cp.check()
                    result=remote.pollGamepad(token)
                    val response=JSONObject(result)
                    if(response.has("error") || response.getJSONObject("data").optBoolean("complete"))break
                    Thread.sleep(25)
                }
                result
            } else if(request["operation"]=="thor.brightness") {
                val args=request.filterKeys {it in setOf("action","screen","value")}
                val command=ThorBrightnessCommand.parse(args)
                var raw=""
                val invoke={
                    cp.check();raw=remote.thorBrightness(token,command.action,command.screen,command.value ?: -1)
                    val response=JSONObject(raw)
                    if(response.has("error") && !response.optBoolean("submitted"))fail(Code.valueOf(response.getString("error")),response.optString("message"))
                }
                if(command.mutates)cp.effect(invoke) else invoke()
                // The vendor queues persistent brightness asynchronously. Verify
                // outside the short authority commit without resubmitting writes.
                val response=JSONObject(raw)
                if(command.mutates && !response.has("error")) {
                    val data=response.getJSONObject("data");val end=SystemClock.elapsedRealtime()+1500
                    do {
                        cp.check()
                        val read=JSONObject(remote.thorBrightness(token,"read",command.screen,-1))
                        if(read.has("error"))fail(Code.valueOf(read.getString("error")),read.optString("message"))
                        val actual=read.getJSONObject("data")
                        requireTool(actual.getInt("displayId")==data.getInt("displayId"),Code.TARGET_CHANGED,"Thor display identity changed during brightness verification")
                        data.put("brightness",actual.getInt("brightness"))
                        data.put("verified",ThorBrightnessPercent.verified(command.value!!,actual.getJSONObject("firmwareDiagnostics").getInt("brightness")))
                        if(data.getBoolean("verified"))break
                        Thread.sleep(50)
                    } while(SystemClock.elapsedRealtime()<end)
                    raw=response.toString()
                }
                if(!response.has("error")) {
                    response.getJSONObject("data").remove("firmwareDiagnostics")
                    raw=response.toString()
                }
                raw
            } else { cp.check(); remote.execute(token, (json(request) as JSONObject).toString()) }
            val response = JSONObject(raw)
            if (response.has("error")) fail(Code.valueOf(response.getString("error")), response.optString("message"))
            val data = response.getJSONObject("data").args()
            (data["error"] as? String)?.let { fail(Code.valueOf(it), "Shell stopped; direct child termination does not confirm descendants stopped") }
            cp.check()
            requireTool(availability().status == Status.READY, Code.PERMISSION_MISSING, "Shizuku readiness changed; discard late output")
            return data
        } finally { runCatching { remote.cancel(token) } }
    }
    fun install(run:String,file:java.io.File,sha:String,approval:String?,cp:Checkpoint):Map<String,Any?> {
        requireTool(approval==sha,Code.APPROVAL_REQUIRED,"This APK requires UI confirmation")
        requireTool(availability().status==Status.READY,Code.BACKEND_UNAVAILABLE,"ADB backend unavailable")
        val remote=service ?: fail(Code.BACKEND_UNAVAILABLE,"ADB backend disappeared")
        val token="$run:${UUID.randomUUID()}";cp.check()
        remote.openLease(token,SystemClock.elapsedRealtime()+60000,DebugUserService.ROOT,hostBinder)
        cp.cancellation.register {worker.submit {runCatching {remote.cancel(token)}}}
        fun checked(raw:String):JSONObject {val o=JSONObject(raw);if(o.has("error"))fail(Code.valueOf(o.getString("error")),"ADB APK operation failed");return o}
        try {
            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->checked(remote.stageApk(token,fd,file.length(),sha))}
            cp.check();cp.effect {checked(remote.startInstall(token,sha,approval.orEmpty()))}
            val data=checked(remote.awaitShell(token)).getJSONObject("data").args()
            cp.check();requireTool(data["exitCode"]==0 && (data["stdout"] as? String)?.trim()=="Success",Code.IO_ERROR,"ADB install failed: "+("${data["stdout"]} ${data["stderr"]}").lineSequence().filter {it.isNotBlank()}.take(3).joinToString(" ").take(1000))
            return data+mapOf("sha256" to sha,"installed" to true,"backend" to "shizuku-adb/pm-install-stdin")
        } finally {runCatching {remote.cancel(token)}}
    }
    fun shell(run: String, command: String, timeout: Int, max: Int, cp: Checkpoint, approvalHash: String? = null) = call(run, mapOf("operation" to "shell", "command" to command, "timeoutMs" to timeout, "maxBytes" to max,"approvalHash" to approvalHash), cp)
    fun close() { closed=true;ThorAccessibilityService.removePresentationReader(this);invalidated(); runCatching { Shizuku.unbindUserService(args, this, true) }; service = null; Shizuku.removeRequestPermissionResultListener(permission); Shizuku.removeBinderDeadListener(dead); Shizuku.removeBinderReceivedListener(received); worker.shutdownNow() }
}
