package dev.thor.tools.diagnostic

import android.os.*
import dev.thor.tools.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real Shizuku UserService. No input method or shell shortcut exists in the screen-control interface. */
class DebugUserService @JvmOverloads constructor(private val hostContext: android.content.Context? = null) : IDebugService.Stub() {
    private val clock = Clock { SystemClock.elapsedRealtime() }
    private data class Lease(val token: String, val caller: Int, val deadline: Long, val root: String, val cancel: Cancellation, val owner: IBinder, val death: IBinder.DeathRecipient)
    private data class ShellJob(val token: String,val process: java.lang.Process,val timeout: Int,val max: Int)
    @Volatile private var shellJob: ShellJob? = null
    @Volatile private var lease: Lease? = null
    private data class GamepadJob(val token: String,val requestHash: String,val action: NativeGamepad.Action)
    @Volatile private var gamepadJob: GamepadJob?=null
    private data class NavigationJob(val token:String,val action:NativeNavigation)
    private var navigationJob:NavigationJob?=null
    private var gamepad: NativeGamepad?=null
    private var staged: File? = null
    private var stagedHash: String? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor().apply {
        scheduleWithFixedDelay({ synchronized(this@DebugUserService) { lease?.let { if (clock.now() >= it.deadline) retire(it) } } }, 100, 100, TimeUnit.MILLISECONDS)
    }
    override fun identity() = Process.myUid()
    @Synchronized private fun nativeGamepad(): NativeGamepad = gamepad ?: NativeGamepad().also {gamepad=it}
    private fun shellIdentity(action:()->String): String {
        requireTool(Process.myUid()==2000,Code.PERMISSION_MISSING,"Only shell UID 2000 may inject gamepad events")
        val identity=Binder.clearCallingIdentity()
        return try {action()} finally {Binder.restoreCallingIdentity(identity)}
    }
    private fun gamepadResponse(action:()->Map<String,Any?>): String = try {
        shellIdentity {(json(mapOf("data" to action())) as JSONObject).toString()}
    } catch(t:Exception) {
        val cause=if(t is java.lang.reflect.InvocationTargetException)t.targetException ?: t else t
        val code=(cause as? ToolFailure)?.code ?: if(cause is SecurityException)Code.PERMISSION_MISSING else Code.UNSUPPORTED
        JSONObject().put("error",code.name)
            .put("message",(cause as? ToolFailure)?.message ?: "Native gamepad unavailable: ${cause.javaClass.simpleName}${if(cause is SecurityException)": ${cause.message.orEmpty().take(180)}" else ""}").toString()
    }
    // Acquire the service monitor only for lazy construction. Read-only metadata I/O must
    // never delay cancel/retire or the independent gamepad release timer.
    override fun gamepadStatus(): String = gamepadResponse {nativeGamepad().status()}
    @Synchronized override fun prepareGamepad(token: String, request: String): String {
        val l=try {check(token)}catch(t:ToolFailure){return JSONObject().put("error",t.code.name).put("message",t.message).toString()}
        val guard=GamepadLeaseGuard(l.caller,l.deadline,clock,l.cancel) {lease===l}
        val callerUid=Binder.getCallingUid()
        return gamepadResponse {
            guard.checkCaller(callerUid)
            requireTool(gamepadJob==null,Code.BACKEND_UNAVAILABLE,"A gamepad action is already active")
            requireTool(request.toByteArray().size<=4096,Code.LIMIT_EXCEEDED,"Gamepad request too large")
            val args=JSONObject(request).args();args.only("toolId","displayId","args","expectedPackage")
            val command=GamepadCommand.parse(args.string("toolId"),args["args"] as? Map<String,Any?> ?: fail(Code.INVALID_ARGUMENT,"Gamepad arguments required"))
            val displayId=args.int("displayId",-1)
            requireTool(displayId>=0,message="Gamepad displayId required")
            val action=nativeGamepad().prepare(command,displayId,args["expectedPackage"] as? String,guard::checkAlive)
            gamepadJob=GamepadJob(token,hash(request.toByteArray()),action)
            mapOf("prepared" to true,"durationMs" to command.durationMs)
        }
    }
    @Synchronized override fun startGamepad(token: String, request: String): String {
        try {check(token)}catch(t:ToolFailure){return JSONObject().put("error",t.code.name).put("message",t.message).toString()}
        val response=gamepadResponse {
            val job=gamepadJob ?: fail(Code.STALE_AUTHORIZATION,"Gamepad action was not prepared")
            requireTool(job.token==token && request.toByteArray().size<=4096 && job.requestHash==hash(request.toByteArray()),Code.STALE_AUTHORIZATION,"Prepared gamepad action differs")
            job.action.start()
            mapOf("started" to true,"submitted" to true)
        }
        return JSONObject(response).put("submitted",gamepadJob?.action?.mayHaveSubmitted==true).toString()
    }
    override fun pollGamepad(token: String): String {
        try {check(token)}catch(t:ToolFailure){return JSONObject().put("error",t.code.name).put("message",t.message).toString()}
        return gamepadResponse {
            val job=gamepadJob ?: fail(Code.CANCELLED,"No submitted gamepad action")
            requireTool(job.token==token,Code.STALE_AUTHORIZATION,"Gamepad token differs")
            if(job.action.complete)job.action.result()+mapOf("complete" to true) else mapOf("complete" to false)
        }
    }
    @Synchronized override fun releaseGamepad(runId: String): String {
        val l=lease ?: return "{\"released\":false,\"reason\":\"no-active-run-inputs\"}"
        requireTool(Binder.getCallingUid()==l.caller,Code.STALE_AUTHORIZATION,"Only the owning app can release its gamepad action")
        if(runId.length !in 1..96 || !l.token.startsWith("$runId:"))return "{\"released\":false,\"reason\":\"no-matching-run-inputs\"}"
        return gamepadResponse {
            val job=gamepadJob?.takeIf {it.token==l.token}
            if(job==null)mapOf("released" to false,"cleanupComplete" to true,"reason" to "no-active-run-inputs")
            else {
                job.action.finish(ToolFailure(Code.CANCELLED,"Run-owned gamepad action released"))
                job.action.cleanupStatus()
            }
        }
    }
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
        var submitted=false
        return try {
            val l = check(token)
            requireTool(request.toByteArray().size <= 16 * 1024, Code.LIMIT_EXCEEDED, "Binder request too large")
            val obj = JSONObject(request); val args = obj.args()
            val data: Map<String, Any?> = when (args.string("operation")) {
                "navigation.prepare" -> synchronized(this) {
                    check(token)
                    args.only("operation","action","displayId","expectedPackage")
                    val command=NavigationCommand.parse(mapOf("action" to args["action"]))
                    val guard=GamepadLeaseGuard(l.caller,l.deadline,clock,l.cancel) {lease===l}
                    guard.checkCaller(Binder.getCallingUid())
                    requireTool(navigationJob==null,Code.BACKEND_UNAVAILABLE,"Navigation is already prepared")
                    val identity=Binder.clearCallingIdentity()
                    try {
                        navigationJob=NavigationJob(token,NativeNavigation.prepare(command,args.int("displayId",-1),args["expectedPackage"] as? String,guard::checkAlive))
                        mapOf("prepared" to true)
                    } finally {Binder.restoreCallingIdentity(identity)}
                }
                "navigation.start" -> synchronized(this) {
                    check(token);args.only("operation")
                    val job=navigationJob ?: fail(Code.STALE_AUTHORIZATION,"Navigation was not prepared")
                    requireTool(job.token==token,Code.STALE_AUTHORIZATION,"Navigation token differs")
                    navigationJob=null
                    val guard=GamepadLeaseGuard(l.caller,l.deadline,clock,l.cancel) {lease===l}
                    guard.checkCaller(Binder.getCallingUid())
                    val identity=Binder.clearCallingIdentity()
                    try {job.action.submit(guard::checkAlive) {submitted=true}}
                    finally {Binder.restoreCallingIdentity(identity)}
                }
                "accessibility.ensure" -> {
                    args.only("operation","reconnect")
                    val reconnect=args["reconnect"] as? Boolean ?: false
                    val host=hostContext ?: fail(Code.UNSUPPORTED,"Shizuku v13 application context required")
                    requireTool(host.packageManager.getPackagesForUid(l.caller).orEmpty().contains(host.packageName),Code.PERMISSION_MISSING,"Caller must own the bound application")
                    // Android's per-user UID range, also used by Shizuku's factory.
                    val user=(l.caller/100000).toString()
                    fun setting(action: String, name: String, value: String? = null): String {
                        check(token)
                        val command=mutableListOf("/system/bin/settings","--user",user,action,"secure",name)
                        if(value!=null)command.add(value)
                        val process=ProcessBuilder(command).redirectErrorStream(true).start()
                        try {
                            requireTool(process.waitFor(3,TimeUnit.SECONDS),Code.TIMEOUT,"Accessibility settings command timed out")
                            val output=process.inputStream.use {Budgets.read(it,16384) {check(token)}}
                            requireTool(process.exitValue()==0 && !output.truncated,Code.IO_ERROR,"Accessibility settings command failed")
                            check(token);return output.bytes.toString(Charsets.UTF_8).trim()
                        } finally {if(process.isAlive)process.destroyForcibly()}
                    }
                    val current=setting("get","enabled_accessibility_services")
                    val merged=AccessibilitySetup.mergedServices(current,host.packageName)
                    // Refresh immediately before the read/merge/write; retain every observed service.
                    val latest=setting("get","enabled_accessibility_services")
                    val desired=AccessibilitySetup.mergedServices(if(latest==current)current else latest,host.packageName)
                    val own="${host.packageName}/dev.thor.tools.diagnostic.ThorAccessibilityService"
                    // Android can retain the enabled setting while a crashed service
                    // is disconnected. Cycle only our component when Native detects it.
                    if(reconnect && own in latest.split(':')) {
                        setting("put","enabled_accessibility_services",desired.split(':').filter {it!=own}.joinToString(":"))
                        // Let AccessibilityManager observe removal before re-adding;
                        // adjacent settings writes can otherwise be coalesced.
                        Thread.sleep(400);check(token)
                    }
                    if(reconnect || latest!=desired)setting("put","enabled_accessibility_services",desired)
                    setting("put","accessibility_enabled","1")
                    val actual=setting("get","enabled_accessibility_services")
                    requireTool(actual.split(':').toSet().containsAll(desired.split(':')),Code.IO_ERROR,"Accessibility readback differs")
                    mapOf("enabled" to true,"uid" to Process.myUid(),"component" to own,"changed" to (current!=merged),"reconnectRequested" to reconnect)
                }
                "shell" -> fail(Code.UNSUPPORTED, "Use guarded short shell submission")
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
        } catch (t: ToolFailure) { JSONObject().put("error", t.code.name).put("message", t.message).put("submitted",submitted).toString() }
          catch (t: Exception) { JSONObject().put("error", Code.IO_ERROR.name).put("message", "UserService failure: ${t.javaClass.simpleName}").put("submitted",submitted).toString() }
    }
    @Synchronized override fun startShell(token: String, command: String, timeoutMs: Int, maxBytes: Int, approvalHash: String): String {
        return try {
        val l = check(token)
        requireTool(shellJob == null && timeoutMs in 1..60000 && maxBytes in 1..Budgets.TEXT, Code.LIMIT_EXCEEDED, "Invalid shell submission/budget")
        val argv=ShellCommandPolicy.classify(command).requireAllowed(approvalHash,command)
        val process = BoundedShell("/system/bin/sh",clock).startArgv(argv,l.cancel) { check(token) }
        shellJob = ShellJob(token,process,timeoutMs,maxBytes)
        "{\"started\":true}"
        } catch(t: ToolFailure) {JSONObject().put("error",t.code.name).put("message",t.message).toString()}
          catch(_: Exception) {JSONObject().put("error",Code.IO_ERROR.name).toString()}
    }
    override fun awaitShell(token: String): String {
        return try {
            val l = check(token); val job = shellJob ?: fail(Code.CANCELLED,"No submitted shell")
            requireTool(job.token == token,Code.STALE_AUTHORIZATION,"Wrong shell job")
            val out = BoundedShell("/system/bin/sh",clock).collect(job.process,job.timeout,job.max,l.cancel) { check(token) }
            check(token)
            (json(mapOf("data" to mapOf("stdout" to out.stdout,"stderr" to out.stderr,"exitCode" to out.exitCode,"durationMs" to out.durationMs,"truncated" to out.truncated,
                "error" to out.error?.name,"terminationConfirmed" to out.terminationConfirmed,"descendantsMayRemain" to true,"uid" to Process.myUid()))) as JSONObject).toString()
        } catch(t: ToolFailure) {JSONObject().put("error",t.code.name).toString()} catch(t: Exception){JSONObject().put("error",Code.IO_ERROR.name).toString()}
        finally { synchronized(this) { shellJob?.takeIf {it.token == token}?.let {if(it.process.isAlive)it.process.destroyForcibly();shellJob=null} } }
    }
    @Synchronized override fun thorBrightness(token: String, action: String, screen: String, value: Int): String {
        var submitted=false
        return try {
            val l=check(token)
            val args=mutableMapOf<String,Any?>("action" to action,"screen" to screen)
            if(action=="set")args["value"]=value else requireTool(value == -1,message="Read does not accept a brightness value")
            val command=ThorBrightnessCommand.parse(args)
            // The caller/token and bounded operation were checked as the app caller. Only this
            // fixed operation adopts the service's own shell identity for vendor IPC.
            val identity=Binder.clearCallingIdentity()
            val data=try {ThorBrightness(hostContext ?: fail(Code.UNSUPPORTED,"Application context required")).execute(command) {
                l.cancel.check();requireTool(clock.now()<l.deadline,Code.TIMEOUT,"Brightness lease expired");submitted=true
            }} finally {Binder.restoreCallingIdentity(identity)}
            (json(mapOf("data" to (data+mapOf("uid" to Process.myUid())),"submitted" to submitted)) as JSONObject).toString()
        } catch(t: Exception) {JSONObject().put("error",(t as? ToolFailure)?.code?.name ?: Code.IO_ERROR.name).put("message",(t as? ToolFailure)?.message ?: "Firmware brightness operation failed: ${t.javaClass.simpleName}/${t.cause?.javaClass?.simpleName.orEmpty()} ${if(t is SecurityException)t.message.orEmpty().take(180) else ""}").put("submitted",submitted).toString()}
    }
    @Synchronized override fun stageApk(token:String,source:ParcelFileDescriptor,size:Long,sha256:String):String {
        return try {
            check(token)
            requireTool(size in 1..268435456 && sha256.matches(Regex("[a-f0-9]{64}")),message="Invalid APK budget/hash")
            staged?.delete();staged=null;stagedHash=null
            val f=File.createTempFile("thor-apk-",".apk",File("/data/local/tmp"));staged=f
            f.setReadable(false,false);f.setReadable(true,true);f.setWritable(false,false);f.setWritable(true,true)
            val md=java.security.MessageDigest.getInstance("SHA-256");var bytes=0L
            ParcelFileDescriptor.AutoCloseInputStream(source).use {input->f.outputStream().use {out->val b=ByteArray(65536);while(true){check(token);val n=input.read(b);if(n<0)break;bytes+=n;requireTool(bytes<=size,Code.LIMIT_EXCEEDED,"APK exceeds declared size");md.update(b,0,n);out.write(b,0,n)};out.fd.sync()}}
            val actual=md.digest().joinToString(""){"%02x".format(it)}
            requireTool(bytes==size && actual==sha256,Code.HASH_CONFLICT,"Staged APK checksum differs")
            check(token);stagedHash=actual
            "{\"staged\":true}"
        } catch(t:Exception){staged?.delete();staged=null;stagedHash=null;JSONObject().put("error",(t as? ToolFailure)?.code?.name ?: Code.IO_ERROR.name).toString()}
    }
    @Synchronized override fun startInstall(token:String,sha256:String,approvalHash:String):String {
        return try {
            val l=check(token);val f=staged ?: fail(Code.IO_ERROR,"No staged APK")
            requireTool(shellJob==null && stagedHash==sha256 && approvalHash==sha256,Code.APPROVAL_REQUIRED,"Exact APK approval required")
            // Fixed argv only. No caller flags, paths, uninstall, grant-all or downgrade.
            val process=ProcessBuilder("/system/bin/pm","install","--user",(l.caller/100000).toString(),"--restrict-permissions","-r","-S",f.length().toString()).start()
            shellJob=ShellJob(token,process,55000,Budgets.TEXT)
            l.cancel.register {process.destroy();if(process.isAlive)process.destroyForcibly()}
            Thread {
                try {f.inputStream().use {input->process.outputStream.use {out->val b=ByteArray(65536);while(true){l.cancel.check();requireTool(clock.now()<l.deadline,Code.TIMEOUT,"Install expired");val n=input.read(b);if(n<0)break;out.write(b,0,n)}}}}
                catch(_:Exception){runCatching {process.outputStream.close()};process.destroy()}
            }.apply {isDaemon=true;start()}
            "{\"started\":true}"
        } catch(t:Exception){JSONObject().put("error",(t as? ToolFailure)?.code?.name ?: Code.IO_ERROR.name).toString()}
    }
    @Synchronized private fun retire(l: Lease) {
        l.cancel.cancel()
        if(navigationJob?.token==l.token)navigationJob=null
        gamepadJob?.takeIf {it.token==l.token}?.let {
            // Release is a fixed, bounded cleanup and must retain service shell identity.
            runCatching {shellIdentity {it.action.finish(ToolFailure(Code.CANCELLED,"Gamepad lease retired"));""}}
            gamepadJob=null
        }
        staged?.delete();staged=null;stagedHash=null
        shellJob?.takeIf {it.token == l.token}?.let {if(it.process.isAlive)it.process.destroyForcibly();shellJob=null}
        runCatching { l.owner.unlinkToDeath(l.death, 0) }
        if (lease === l) lease = null
    }
    @Synchronized override fun cancel(token: String) {
        lease?.takeIf { token == it.token && Binder.getCallingUid() == it.caller }?.let(::retire)
    }
    override fun destroy() { synchronized(this) { lease?.let(::retire);runCatching {gamepad?.close()} }; watchdog.shutdownNow(); kotlin.system.exitProcess(0) }
    companion object { const val ROOT = "/storage/emulated/0/Download/ThorToolPack" }
}
