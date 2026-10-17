package dev.thor.tools.diagnostic

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.*
import android.hardware.display.DisplayManager
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.thor.tools.*
import dev.thor.tools.Point
import dev.thor.tools.Target
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class AndroidBackend(val context: Context, val clock: Clock, val snapshots: Snapshots, val shizuku: DebugConnection,
    val files: LocalFiles, val transactions: ConfigTransactions) : DeviceBackend {
    private val schemas = InputSchemas(context)
    private val main = Handler(Looper.getMainLooper())
    private val inputState=BoundedInputState()
    private val frames = java.util.concurrent.ConcurrentHashMap<String, InputFrameBinding>()
    private data class PresentationFrame(val sessionId:String,val runId:String,val capabilityEpoch:Long,val snapshot:Snapshot,val transform:FrameTransform,val order:Long)
    private val presentationFrames=java.util.concurrent.ConcurrentHashMap<String,PresentationFrame>()
    private val presentationOrder=java.util.concurrent.atomic.AtomicLong()
    /** Pure cached provenance only: a reference picture is never fresh input authority. */
    private fun actionPresentation(e:Envelope,snapshot:Snapshot,points:List<Point>?=null,bounds:TouchableBounds?=null,exactFrameId:String?=null):Map<String,Any?> = runCatching {
        val marker=points ?: bounds?.let {listOf(Point(it.left.toDouble(),it.top.toDouble()),Point(it.right.toDouble(),it.bottom.toDouble()))}.orEmpty()
        val frameId=exactFrameId ?: presentationFrames.entries.asSequence().filter {(_,frame)->
            val old=frame.snapshot;val t=frame.transform
            frame.sessionId==e.sessionId && frame.runId==e.runId && frame.capabilityEpoch==e.capabilityEpoch &&
                old.role==snapshot.role && old.display==snapshot.display && old.displayEpoch==snapshot.displayEpoch &&
                old.target.sameInputTarget(snapshot.target) && old.target.touchableBounds==snapshot.target.touchableBounds &&
                marker.all {it.x>=t.left && it.y>=t.top && it.x<=t.left+t.width && it.y<=t.top+t.height}
        }.maxByOrNull {it.value.order}?.key
        val visual=linkedMapOf<String,Any?>("kind" to e.toolId,"targetSnapshotId" to snapshot.id,
            "displayRole" to snapshot.role,"displayId" to snapshot.display.id,"coordinateSpace" to "display_logical")
        if(frameId!=null) {
            val transform=presentationFrames[frameId]?.transform ?: frames[frameId]?.transform
            if(transform!=null) {
                visual["frameId"]=frameId
                visual["transform"]=mapOf("left" to transform.left,"top" to transform.top,
                    "displayWidth" to transform.width,"displayHeight" to transform.height,
                    "imageWidth" to transform.imageWidth,"imageHeight" to transform.imageHeight)
            }
        }
        if(points!=null)visual["points"]=points.map {mapOf("x" to it.x,"y" to it.y)}
        if(bounds!=null)visual["bounds"]=mapOf("left" to bounds.left,"top" to bounds.top,"right" to bounds.right,"bottom" to bounds.bottom)
        mapOf<String,Any?>("visual" to visual)
    }.getOrDefault(emptyMap())
    var approval: Approval? = null
    var shellApproval: String? = null
    var installApproval: String? = null
    @Volatile var backgroundAgentAction:((Checkpoint)->Map<String,Any?>)?=null
    val downloads=NativeDownloads(context)
    private val launcher=AppLauncher(context)
    private data class LowerSafety(val key:String,val at:Long,val state:CompanionWindowState)
    @Volatile private var lowerSafetyCache:LowerSafety?=null
    @Volatile private var lastScreenshotAttempt=Long.MIN_VALUE
    init {snapshots.installLowerGuard {display,target->lowerSafety(display,target)}}
    private val gestureTools = setOf("input.tap", "input.long_press", "input.swipe", "input.drag", "input.scroll", "input.activate", "input.type_text", "input.key_press")
    private data class NodeBinding(val snapshotId:String,val windowId:Int,val packageName:String?,val node:AccessibilityNodeInfo,val signature:String,val expires:Long,val runId:String,val sessionId:String,val path:String,val uniqueIdentity:Boolean)
    private val nodeRefs=LinkedHashMap<String,NodeBinding>()
    private val nodeLock=Any()
    private var nodeExpiry:Runnable?=null
    private fun invalidateNodeRefs()=synchronized(nodeLock){nodeExpiry?.let {main.removeCallbacks(it)};nodeExpiry=null;nodeRefs.values.forEach { @Suppress("DEPRECATION") it.node.recycle() };nodeRefs.clear()}
    private fun nodeSignature(node:AccessibilityNodeInfo):String {
        val r=Rect();node.getBoundsInScreen(r)
        return hash(listOf(node.windowId,node.packageName,node.className,node.viewIdResourceName,node.isPassword,
            if(node.isPassword)null else node.text,if(node.isPassword)null else node.contentDescription,node.isEnabled,node.isVisibleToUser,node.isScrollable,node.isClickable,node.childCount,
            node.collectionInfo?.rowCount,node.collectionInfo?.columnCount,node.collectionItemInfo?.rowIndex,node.collectionItemInfo?.columnIndex,node.isChecked,node.isSelected,
            node.isEditable,if(node.isPassword)null else node.textSelectionStart,if(node.isPassword)null else node.textSelectionEnd,
            if(node.isPassword)null else node.stateDescription,node.rangeInfo?.current,r.toShortString(),node.actionList.map {it.id}).joinToString("|").toByteArray())
    }
    @Volatile private var accessibilitySettingCheckedAt=Long.MIN_VALUE
    @Volatile private var accessibilitySettingEnabled=false
    private fun accessibilityAvailability(backend:String):Availability {
        if(ThorAccessibilityService.instance!=null)return AccessibilitySetup.availability(true,true,false,backend)
        if(shizuku.accessibilityRestartRequired)return Availability(Status.TEMPORARILY_UNAVAILABLE,shizuku.accessibilityRestartMessage,backend)
        val now=clock.now()
        if(accessibilitySettingCheckedAt==Long.MIN_VALUE || now-accessibilitySettingCheckedAt>=500) {
            try {
                val own=android.content.ComponentName(context,ThorAccessibilityService::class.java)
                val enabled=android.provider.Settings.Secure.getInt(context.contentResolver,android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,0)==1
                val services=android.provider.Settings.Secure.getString(context.contentResolver,android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                accessibilitySettingEnabled=enabled && services.split(':').any {android.content.ComponentName.unflattenFromString(it)==own}
                accessibilitySettingCheckedAt=now
            }catch(_:Exception){return Availability(Status.TEMPORARILY_UNAVAILABLE,"正在确认本应用无障碍的系统连接状态，请稍后重试",backend)}
        }
        return AccessibilitySetup.availability(accessibilitySettingEnabled,false,shizuku.accessibilityRecoveryPending,backend)
    }
    override fun availability(toolId: String): Availability {
        if(toolId=="input.key_press") {
            val base=shizuku.availability().copy(backend="shizuku-inputmanager/virtual-keyboard")
            val accessibility=accessibilityAvailability(base.backend)
            return if(accessibility.status!=Status.READY)accessibility
                else if(inputState.hasUnknown())Availability(Status.TEMPORARILY_UNAVAILABLE,"Previous submitted gesture has no completion/cancel callback; stop status unknown",base.backend)
                else base.copy(reason=if(base.status==Status.READY)"Fixed keyboard keys/modifiers; selected-screen focus and display routing are verified before submission, receiver handling requires observation" else base.reason)
        }
        if(toolId=="agent.background")return if(backgroundAgentAction!=null)
            Availability(Status.READY,"Fixed assistant chat-task background action; no arbitrary app/task routing","android-sdk/assistant-background")
            else Availability(Status.BACKEND_MISSING,"Assistant background host callback is not installed","android-sdk/assistant-background")
        if(toolId in setOf("perception.find_apps","apps.launch"))return Availability(Status.READY,"Fixed public MAIN/LAUNCHER PackageManager API; actual foreground requires observation","android-sdk/launcher")
        if(toolId=="input.navigate") {
            val base=shizuku.availability()
            return base.copy(backend="shizuku-inputmanager/android-navigation",reason=if(base.status==Status.READY)"Fixed Android BACK; reflection and focused selected-screen target are verified before submission, handling remains unverified" else base.reason)
        }
        val backend = when {
            toolId in setOf("web.request","files.download","files.download_info") -> "native-http/private-downloads"
            toolId.startsWith("apps.") -> "shizuku-userservice/shell-uid-2000"
            toolId.startsWith("perception.") -> "android-sdk/accessibility"
            toolId=="input.scroll" -> "accessibility-node-scroll"
            toolId=="input.activate" -> "accessibility-node-activate"
            toolId=="input.type_text" -> "accessibility-node-set-text"
            toolId.startsWith("input.") -> "accessibility-dispatchGesture"
            toolId.startsWith("debug.") -> "shizuku-userservice"
            else -> "synthetic-config/android-app-owned-root"
        }
        if (toolId in setOf("input.press_button", "input.set_axes")) return shizuku.gamepadAvailability(toolId)
        if (toolId in setOf("debug.shell","debug.thor_brightness","apps.install","apps.package_info")) return shizuku.availability()
        if (toolId == "debug.command_policy") return Availability(Status.READY,"Native command policy; no device mutation","native-command-policy")
        if (toolId in gestureTools && inputState.hasUnknown()) return Availability(Status.TEMPORARILY_UNAVAILABLE, "Previous submitted gesture has no completion/cancel callback; stop status unknown", backend)
        if (toolId == "web.request" || toolId == "perception.device_info" || toolId.startsWith("files.") || toolId == "input.release_inputs") return Availability(Status.READY, "Manual diagnostics only", backend)
        val accessibility=accessibilityAvailability(backend)
        if(accessibility.status!=Status.READY)return accessibility
        if(toolId=="input.type_text")return Availability(Status.READY,"Unicode ACTION_SET_TEXT; each current nodeRef must resolve to a visible enabled non-password editable field advertising set_text",backend)
        return Availability(Status.READY, "Android API exists; delivery on Thor is unverified", backend)
    }
    override fun availability(toolId: String, args: Map<String, Any?>): Availability =
        if (toolId in setOf("files.list", "files.read") && args["backend"] == "shizuku")
            shizuku.availability().copy(backend = "shizuku-userservice/shared-root-read-only") else availability(toolId)
    private fun service() = ThorAccessibilityService.instance ?: accessibilityAvailability("android-sdk/accessibility").let {
        fail(if(it.status==Status.PERMISSION_MISSING)Code.PERMISSION_MISSING else Code.BACKEND_UNAVAILABLE,it.reason)
    }
    private fun result(e: Envelope, data: Map<String, Any?>, truncated: Boolean = false, outcome: String = "observed", actualBackend: String? = null): ToolResult {
        val a = if (actualBackend == null) availability(e.toolId) else shizuku.availability().copy(backend = actualBackend)
        val target = if (ToolPack.descriptors[e.toolId]?.targetRequired == true) runCatching { snapshots.validateReadSource(e.snapshotId) }.getOrNull() else null
        val common = if (target == null) data else data + mapOf("displayRole" to target.role, "displayId" to target.display.id)
        return ToolResult(clock.now(), a.backend, a, common, truncated = truncated, outcome = outcome, snapshotId = e.snapshotId)
    }
    override fun prepareObservation(envelope:Envelope,args:Map<String,Any?>,cancellation:Cancellation) {
        if(ToolPack.descriptors[envelope.toolId]?.targetRequired!=true)return
        val source=snapshots.validateReadSource(envelope.snapshotId)
        val displays=mutableSetOf(source.display.id)
        if(envelope.toolId=="perception.select_screen")displays.add(snapshots.selected(ScreenSelection.parse(args)).id)
        if(envelope.toolId=="perception.capture_companion")displays.add(snapshots.selected("lower").id)
        service().preparePresentationEvidence(displays,cancellation)
    }
    override fun execute(envelope: Envelope, args: Map<String, Any?>, checkpoint: Checkpoint): ToolResult {
        lowerSafetyCache=null // A new tool never inherits a prior password scan.
        schemas.validate(envelope.toolId, args)
        if(ToolPack.descriptors[envelope.toolId]?.let {it.effect==Effect.INPUT && it.targetRequired}==true)
            InputTargetPolicy.check(envelope.toolId,snapshots.validateReadSource(envelope.snapshotId).target,setOf(context.packageName,"dev.thor.agent"))
        if(envelope.toolId in ToolPack.freshReads)invalidateNodeRefs()
        // Read the current app/window/tree even after the model has spent more
        // than an observation lifetime reasoning. The original source still fixes the
        // authorized display/epoch; a fresh target must survive the whole read.
        // Screenshot already owns this lifecycle, including its async callback.
        val observation=if(envelope.toolId in ToolPack.freshReads && envelope.toolId !in setOf("perception.capture_screen","perception.capture_companion","perception.select_screen")) {
            checkpoint.check();snapshots.captureForRead(envelope.snapshotId)
        } else null
        val e=if(observation==null)envelope else envelope.copy(snapshotId=observation.id)
        val cp=if(observation==null)checkpoint else Checkpoint({
            checkpoint.check()
            snapshots.validateObservation(observation.id)
        },checkpoint.cancellation)
        cp.check()
        if(ToolPack.descriptors[e.toolId]?.targetRequired==true && e.toolId !in setOf("perception.select_screen","perception.capture_screen","perception.capture_companion")) {
            val selected=snapshots.validateReadSource(e.snapshotId)
            if(selected.role=="lower")lowerSecurityProbe(selected,e,cp)
        }
        val output=when (e.toolId) {
            "agent.background" -> backgroundAgent(e,args,cp)
            "perception.select_screen" -> {
                val role=ScreenSelection.parse(args)
                cp.check();invalidateNodeRefs()
                val chosen=snapshots.selectScreen(e.snapshotId,role)
                if(role=="lower")lowerSecurityProbe(chosen,e,cp)
                val completed=snapshots.captureReadCompletion(chosen.id)
                result(e.copy(snapshotId=completed.id),mapOf("selectedScreen" to completed.role,"targetSnapshotId" to completed.id,
                    "requestSnapshotId" to e.snapshotId,"readOnlySelection" to true,"calibrationChanged" to false,
                    "nextStep" to "Only this Run selected the calibrated screen. Re-observe its current UI/screenshot before input; prior role frames/refs remain invalid for this target."))
            }
            "input.activate" -> activate(e,args,cp)
            "input.type_text" -> activate(e,args,cp,TextInputCommand.parse(args))
            "input.key_press" -> {
                KeyboardCommand.parse(args)
                requireTool(!inputState.hasPending(),Code.BACKEND_UNAVAILABLE,"Previous gesture has not finished")
                val snap=snapshots.validateObservation(e.snapshotId)
                cp.check();invalidateNodeRefs()
                val data=shizuku.keyboard(e.runId,args,snap.display.id,snap.target.packageName,cp)
                cp.check();snapshots.validateReadSource(snap.id)
                result(e,data,outcome="submitted")
            }
            "input.navigate" -> {
                NavigationCommand.parse(args)
                val snap=snapshots.validateObservation(e.snapshotId)
                cp.check();invalidateNodeRefs()
                val data=shizuku.navigate(e.runId,args,snap.display.id,snap.target.packageName,cp)
                cp.check();snapshots.validateReadSource(snap.id)
                result(e,data,outcome="submitted")
            }
            "perception.find_apps" -> result(e,launcher.find(args,cp))
            "apps.launch" -> {
                val snap=snapshots.validate(e.snapshotId)
                val data=launcher.launch(args,snap.display.id,cp,snap.role) {snapshots.validate(e.snapshotId);invalidateNodeRefs()}
                cp.check();snapshots.validateReadSource(snap.id) // Launch changes the app, but must not change the authorized display/role/epoch.
                result(e,data,outcome="submitted")
            }
            "web.request", "files.download", "files.download_info" -> {
                val data=try {downloads.execute(e.toolId,args,cp)}catch(t:Exception){cp.check();throw t}
                result(e,data,truncated=data["truncated"]==true,actualBackend=null)
            }
            "apps.install" -> {
                val preview=downloads.installPreview(args.string("downloadId"),args.string("sha256"))
                val approved=installApproval;installApproval=null
                val data=shizuku.install(e.runId,downloads.apkFile(args.string("downloadId")),args.string("sha256"),approved,cp)
                result(e,data+mapOf("apk" to preview.getJSONObject("apk").args()),outcome="submitted")
            }
            "apps.package_info" -> {
                val pkg=args.string("packageName")
                requireTool(pkg.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")),message="Invalid package")
                val data=shizuku.shell(e.runId,"pm list packages --show-versioncode $pkg",15000,16384,cp)
                val stdout=data["stdout"] as? String ?: ""
                val installed=stdout.lineSequence().firstOrNull {it.startsWith("package:$pkg versionCode:")}
                val path=if(installed!=null)shizuku.shell(e.runId,"pm path $pkg",15000,16384,cp)["stdout"] else null
                result(e,data+mapOf("packageName" to pkg,"installed" to (installed!=null),"versionCode" to installed?.substringAfter("versionCode:")?.trim(),"apkPath" to path))
            }
            "perception.device_info" -> {
                args.only("sections")
                val sections = args["sections"] as? List<*> ?: listOf("system")
                requireTool(sections.size <= 3 && sections.all { it in listOf("system", "displays", "resources") }, message = "Unknown device_info sections")
                val data = mutableMapOf<String, Any?>()
                if ("system" in sections) {data["gamepad"]=shizuku.gamepadInfo();data["system"] = mapOf("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "android" to Build.VERSION.RELEASE, "api" to Build.VERSION.SDK_INT, "thorVerified" to false)}
                if ("displays" in sections) data["displays"] = displayFacts(context).map { mapOf("displayId" to it.id, "identity" to it.identity, "width" to it.width, "height" to it.height, "rotation" to it.rotation, "density" to it.density) }
                if ("resources" in sections) {
                    val info = android.app.ActivityManager.MemoryInfo(); context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(info)
                    data["resources"] = mapOf("availableMemory" to info.availMem, "lowMemory" to info.lowMemory, "batteryCapacity" to context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                }
                result(e, data)
            }
            "perception.app_info" -> {
                args.only("package")
                val snapshot = snapshots.validateObservation(e.snapshotId)
                val pkg = args["package"] as? String ?: snapshot.target.packageName ?: fail(Code.TARGET_CHANGED, "Upper application is ambiguous/unknown; specify a known visible package")
                requireTool(pkg.length in 1..255 && pkg.matches(Regex("[A-Za-z0-9_.]+")), message = "Invalid package")
                try {
                    @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(pkg, 0)
                    result(e, mapOf("package" to pkg, "label" to info.applicationInfo?.loadLabel(context.packageManager)?.toString()?.take(256), "versionName" to info.versionName, "longVersionCode" to info.longVersionCode, "firstInstallTime" to info.firstInstallTime, "lastUpdateTime" to info.lastUpdateTime, "activity" to null))
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    // An observed package is useful even when Android package visibility hides metadata.
                    requireTool(pkg==snapshot.target.packageName,Code.PERMISSION_MISSING,"Package metadata unavailable")
                    result(e,mapOf("package" to pkg,"observedOnUpper" to true,"metadataAvailable" to false,"activity" to null))
                }
            }
            "perception.window_info" -> windowInfo(e, args)
            "perception.ui_tree" -> tree(e, args, cp)
            "perception.capture_screen" -> screenshot(e, args, cp)
            "perception.capture_companion" -> screenshot(e,args,cp,companion=true)
            "input.press_button", "input.set_axes" -> {
                val snap=snapshots.validate(e.snapshotId);cp.check();invalidateNodeRefs()
                if(e.toolId in setOf("input.press_button","input.set_axes")) {
                    val window=service().windowsOnAllDisplays[snap.display.id].orEmpty().singleOrNull {it.isFocused && service().isApplicationWindow(it)}
                    val root=window?.root
                    val focusedPackage=try {root?.packageName?.toString()} finally {if(root!=null) {@Suppress("DEPRECATION") root.recycle()}}
                    requireTool(focusedPackage==snap.target.packageName && focusedPackage!=null,Code.TARGET_CHANGED,"Physical controller requires the observed selected-screen app to have window focus")
                }
                val held=cp.checkingTarget(snapshots,e.snapshotId)
                result(e,shizuku.gamepad(e.runId,e.toolId,args,snap.display.id,held,expectedPackage=snap.target.packageName),outcome="submitted",actualBackend=if(e.toolId=="input.set_axes")"shizuku-evdev/odin-six-axes" else "shizuku-evdev/odin-buttons")
            }
            "input.scroll" -> scroll(e,args,cp)
            "input.tap", "input.long_press", "input.swipe", "input.drag" -> gesture(e, args, cp)
            "input.release_inputs" -> { args.only(); result(e, mapOf("release" to release(e.runId)), outcome = "unknown") }
            "debug.shell" -> {
                args.only("command", "timeoutMs", "maxBytes")
                val command = args.string("command")
                val timeout = args.int("timeoutMs", 15000); val maxBytes = args.int("maxBytes", Budgets.TEXT)
                requireTool(timeout in 1..60_000 && maxBytes in 1..Budgets.TEXT, Code.LIMIT_EXCEEDED, "Shell time/output budget exceeded")
                requireTool(command.isNotBlank() && command.toByteArray().size <= 8192 && !command.contains('\u0000'), message = "Invalid Shell command")
                val policy=ShellCommandPolicy.classify(command)
                val approved=shellApproval
                shellApproval = null
                policy.requireAllowed(approved,command)
                val output = shizuku.shell(e.runId, command, timeout, maxBytes, cp,approved) + policy.data()
                val exit = output["exitCode"] as? Number
                result(e, output, truncated = output["truncated"] == true, outcome = "submitted").copy(
                    error = if (exit == null || exit.toInt() != 0) Code.IO_ERROR else null,
                    message = if (exit == null || exit.toInt() != 0) "Shell command exited unsuccessfully; see exitCode/output" else null)
            }
            "debug.command_policy" -> {
                args.only("command")
                result(e,if(args.containsKey("command"))ShellCommandPolicy.classify(args.string("command")).data() else ShellCommandPolicy.describe())
            }
            "debug.thor_brightness" -> {
                val command=ThorBrightnessCommand.parse(args);shellApproval=null
                val data=shizuku.call(e.runId,args+mapOf("operation" to "thor.brightness"),cp)
                result(e,data,outcome=if(command.mutates)"submitted" else "observed").copy(error=if(data["verified"]==true)null else Code.IO_ERROR)
            }
            "files.list" -> {
                args.only("path", "limit", "backend")
                if (args.string("backend", "synthetic") == "shizuku") { val data = shizuku.call(e.runId, mapOf("operation" to "files.list", "path" to args.string("path", "."), "limit" to args.int("limit", 100)), cp); return result(e, data, data["truncated"] == true, actualBackend = "shizuku-userservice/shared-root-read-only") }
                requireTool(args.string("backend", "synthetic") == "synthetic", message = "Unknown file backend")
                val (entries, truncated) = files.list(args.string("path", "."), args.int("limit", 100))
                result(e, mapOf("entries" to entries, "root" to files.root.toString()), truncated)
            }
            "files.read" -> {
                args.only("path", "maxBytes", "offset", "backend")
                if (args.string("backend", "synthetic") == "shizuku") {
                    requireTool(args.int("offset", 0) == 0, Code.UNSUPPORTED, "Shizuku read supports offset 0 only")
                    val data = shizuku.call(e.runId, mapOf("operation" to "files.read", "path" to args.string("path"), "maxBytes" to args.int("maxBytes", Budgets.TEXT)), cp); return result(e, data, data["truncated"] == true, actualBackend = "shizuku-userservice/shared-root-read-only")
                }
                requireTool(args.string("backend", "synthetic") == "synthetic", message = "Unknown file backend")
                val offset = args.int("offset", 0); requireTool(offset >= 0, message = "Offset must be nonnegative")
                val max = args.int("maxBytes", Budgets.TEXT); requireTool(max in 1..Budgets.TEXT, Code.LIMIT_EXCEEDED, "Read budget is 1..64KiB")
                val content = files.read(args.string("path"))
                val end = minOf(content.size.toLong(), offset.toLong() + max).toInt()
                requireTool(offset <= content.size, message = "Offset past EOF")
                val slice = content.copyOfRange(offset, end)
                val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(slice)).toString() }.getOrNull()
                result(e, mapOf("content" to text?.let { Budgets.safeText(it) }, "encoding" to if (text == null) "binary/metadata-only" else "utf-8", "hash" to hash(content), "size" to content.size, "offset" to offset), end < content.size)
            }
            "files.backup" -> {
                args.only("path", "expectedHash")
                val b = transactions.backup(args.string("path"), args.string("expectedHash"), cp)
                result(e, mapOf("backupId" to b.id, "path" to b.path, "originalHash" to b.oldHash, "size" to b.bytes.size, "createdAt" to java.time.Instant.ofEpochMilli(b.time).toString(), "metadata" to b.metadata, "encrypted" to true))
            }
            "files.write", "files.restore" -> {
                val approved = approval; approval = null
                val receipt = if (e.toolId == "files.write") {
                    args.only("path", "expectedHash", "content", "backupId")
                    transactions.write(args.string("path"), args.string("expectedHash"), args.string("content").toByteArray(), args.string("backupId"), approved, cp)
                } else {
                    args.only("backupId", "expectedCurrentHash")
                    transactions.restore(args.string("backupId"), args.string("expectedCurrentHash"), approved, cp)
                }
                result(e, mapOf("path" to receipt.path, "hash" to receipt.newHash, "backupId" to receipt.backupId, "atomic" to receipt.atomic, "verified" to receipt.verified))
            }
            else -> fail(Code.UNSUPPORTED, "Unsupported tool/backend")
        }
        if(observation!=null) {
            cp.check() // Content-only updates do not invalidate pure reads; node refs stay strict.
            val completed=if(envelope.toolId=="perception.ui_tree")observation else snapshots.captureReadCompletion(observation.id)
            return output.copy(snapshotId=completed.id,data=output.data+mapOf("requestSnapshotId" to envelope.snapshotId,"captureSnapshotId" to observation.id,"targetSnapshotId" to completed.id,"observedTargetPackage" to observation.target.packageName))
        }
        return output
    }
    private fun backgroundAgent(e:Envelope,args:Map<String,Any?>,cp:Checkpoint):ToolResult {
        AgentBackground.parse(args)
        val action=backgroundAgentAction ?: fail(Code.BACKEND_UNAVAILABLE,"Assistant background callback disappeared")
        val abandoned=java.util.concurrent.atomic.AtomicBoolean(false)
        val end=minOf(e.deadline,clock.now()+10000)
        val guarded=cp.checking {requireTool(!abandoned.get() && clock.now()<end,Code.TIMEOUT,"Queued background request expired")}
        val done=CountDownLatch(1);var data:Map<String,Any?>?=null;var failure:ToolFailure?=null
        val work=Runnable {
            try {guarded.check();data=action(guarded)}
            catch(t:ToolFailure){failure=t}
            catch(t:Exception){failure=ToolFailure(if(t is SecurityException)Code.PERMISSION_MISSING else Code.IO_ERROR,"Assistant background failed: ${t.javaClass.simpleName}")}
            finally {if(cp.submitted){invalidateNodeRefs();frames.clear()};done.countDown()}
        }
        requireTool(main.post(work),Code.BACKEND_UNAVAILABLE,"Assistant UI executor is unavailable")
        try {
            while(!done.await(50,TimeUnit.MILLISECONDS))guarded.check()
            cp.check();failure?.let {throw it}
        } catch(t:Exception) {abandoned.set(true);main.removeCallbacks(work);throw t}
        val receipt=data ?: fail(Code.IO_ERROR,"Assistant background result is missing")
        AgentBackground.verifyReceipt(receipt,cp.submitted)
        return result(e,receipt+mapOf("submitted" to cp.submitted,"scope" to "assistant-chat-task-only",
            "nextStep" to "Re-observe the desired calibrated screen, then select upper/lower for this Run as needed. No target app has been started or configured."),
            outcome=if(cp.submitted)"submitted" else "observed")
    }
    private fun windowInfo(e: Envelope, args: Map<String, Any?>): ToolResult {
        args.only("maxWindows")
        val limit = args.int("maxWindows", 16); requireTool(limit in 1..16, Code.LIMIT_EXCEEDED, "Window limit is 1..16")
        val snap = snapshots.validateObservation(e.snapshotId)
        val windows = service().windowsOnAllDisplays[snap.display.id].orEmpty()
        return result(e, mapOf("displayRole" to snap.role, "displayId" to snap.display.id, "activity" to null, "completeSystemTaskList" to false,
            "windows" to windows.take(limit).map { w -> val r = Rect(); w.getBoundsInScreen(r); mapOf("windowId" to w.id, "type" to w.type, "layer" to w.layer, "focused" to w.isFocused, "active" to w.isActive, "bounds" to listOf(r.left, r.top, r.right, r.bottom)) }), windows.size > limit)
    }
    private fun tree(e: Envelope, args: Map<String, Any?>, cp: Checkpoint): ToolResult {
        args.only("windowId","maxDepth","maxNodes","maxChars","maxVisited","offset","query","visibility","textOffset")
        val depthLimit=args.int("maxDepth",5);val nodeLimit=args.int("maxNodes",80);val charLimit=args.int("maxChars",12000)
        val visitLimit=args.int("maxVisited",1024)
        requireTool(depthLimit in 0..20 && nodeLimit in 1..128 && charLimit in 1..12000 && visitLimit in 1..2048,Code.LIMIT_EXCEEDED,"UI tree budget exceeded")
        val snap=snapshots.validateObservation(e.snapshotId)
        val windows=service().windowsOnAllDisplays[snap.display.id].orEmpty().filter {service().isApplicationWindow(it)}
        val window=if(args.containsKey("windowId"))windows.singleOrNull {it.id==args.int("windowId",-1)} else windows.singleOrNull()
        if(window==null)return result(e,mapOf("nodes" to emptyList<Any>(),"emptyReason" to "no_unique_application_window","coverage" to "Accessibility-exposed nodes only"))
        val root=window.root ?: return result(e,mapOf("nodes" to emptyList<Any>(),"emptyReason" to "null_root_or_surface_content","coverage" to "No exposed Accessibility tree; use screenshot for visual evidence"))
        val raw=mutableListOf<UiElement>();val handles=LinkedHashMap<String,AccessibilityNodeInfo>();val reasons=linkedSetOf<String>()
        var scannedChars=0
        fun extract(node:AccessibilityNodeInfo,path:String,parent:String?):UiElement {
            fun textSlice(value:CharSequence?,body:Boolean=false):Triple<String?,Int,Int?> {
                if(value==null)return Triple(null,0,null)
                if(scannedChars>=524288){reasons.add("scanTextBudget");return Triple(null,0,value.length)}
                val query=args.string("query","")
                val searchLength=minOf(value.length,1000000)
                val hit=if(body && query.isNotEmpty())value.subSequence(0,searchLength).toString().indexOf(query,ignoreCase=true) else -1
                if(body && query.isNotEmpty() && searchLength<value.length)reasons.add("sourceTextSearchLimit")
                val start=if(!body)0 else if(hit>=0)(hit-100).coerceAtLeast(0) else args.int("textOffset",0).coerceAtMost(value.length)
                val take=minOf(value.length-start,262144,(524288-scannedChars).coerceAtLeast(0));scannedChars+=take
                if(take<value.length-start || start>0)reasons.add("scanTextBudget")
                return Triple(value.subSequence(start,start+take).toString(),start,value.length)
            }
            fun text(value:CharSequence?)=textSlice(value).first
            val bounds=Rect();node.getBoundsInScreen(bounds)
            val secret=node.isPassword
            val range=node.rangeInfo?.let {mapOf("type" to it.type,"min" to it.min,"max" to it.max,"current" to it.current)}
            val collection=node.collectionInfo?.let {mapOf("rows" to it.rowCount,"columns" to it.columnCount,"hierarchical" to it.isHierarchical,"selectionMode" to it.selectionMode)}
            val item=node.collectionItemInfo?.let {mapOf("rowIndex" to it.rowIndex,"rowSpan" to it.rowSpan,"columnIndex" to it.columnIndex,"columnSpan" to it.columnSpan,"selected" to it.isSelected)}
            val actions=node.actionList.mapNotNull {actionNames[it.id]}.distinct()
            val body=if(secret)Triple(null,0,null) else textSlice(node.text,true)
            val description=if(secret)Triple(null,0,null) else textSlice(node.contentDescription,true)
            val value=if(secret)Triple(null,0,null) else textSlice(node.stateDescription ?: if(node.isEditable)node.text else null,true)
            return UiElement(path,parent,text(node.className),if(secret)null else text(node.viewIdResourceName),body.first,description.first,
                value.first,secret,node.isClickable,node.isCheckable,node.isChecked,node.isSelected,node.isEnabled,node.isVisibleToUser,node.isScrollable,node.isEditable,
                listOf(bounds.left,bounds.top,bounds.right,bounds.bottom),actions,collection,item,range,body.second,body.third,description.second,description.third,value.second,
                selectionStart=if(!secret && node.isEditable)node.textSelectionStart else null,
                selectionEnd=if(!secret && node.isEditable)node.textSelectionEnd else null,focused=!secret && node.isFocused)
        }
        fun visit(node:AccessibilityNodeInfo,depth:Int,path:String,parent:String?) {
            try {
                cp.check()
                if(raw.size>=visitLimit){reasons.add("maxVisited");return}
                val entry=extract(node,path,parent);raw.add(entry)
                @Suppress("DEPRECATION") val copy=AccessibilityNodeInfo.obtain(node);handles[path]=copy
                val semanticDepth=depth+if(entry.semantic())1 else 0
                if(semanticDepth>depthLimit || path.count {it=='/'}>=32){if(node.childCount>0)reasons.add("maxDepth");return}
                for(index in 0 until node.childCount) {
                    if(raw.size>=visitLimit){reasons.add("maxVisited");break}
                    node.getChild(index)?.let {visit(it,semanticDepth,"$path/$index",path)}
                }
            }finally { @Suppress("DEPRECATION") node.recycle() }
        }
        var rootHandedOff=false
        try {
            requireTool(root.packageName?.toString()==snap.target.packageName,Code.TARGET_CHANGED,"Tree window does not belong to the selected-screen app")
            rootHandedOff=true;visit(root,0,"root",null)
            val treeId=java.util.UUID.randomUUID().toString()
            fun ref(path:String)="$treeId:$path"
            val page=SemanticTree.project(raw,::ref,nodeLimit,charLimit,args.int("offset",0),args.string("query",""),args.string("visibility","all"),args.int("textOffset",0))
            reasons.addAll(page.reasons);cp.check()
            synchronized(nodeLock) {
                invalidateNodeRefs()
                val identities=handles.values.groupingBy {it}.eachCount()
                for(path in page.paths)handles.remove(path)?.let {node->nodeRefs[ref(path)]=NodeBinding(snap.id,window.id,snap.target.packageName,node,nodeSignature(node),snap.expires,e.runId,e.sessionId,path,identities[node]==1)}
                nodeExpiry=Runnable {synchronized(nodeLock){if(nodeRefs.values.firstOrNull()?.snapshotId==snap.id)invalidateNodeRefs()}}
                main.postDelayed(nodeExpiry!!,(snap.expires-clock.now()).coerceAtLeast(1))
            }
            return result(e,mapOf("nodes" to page.nodes,"windowId" to window.id,"treeId" to treeId,"nodeRefsReusable" to false,"nodeRefScope" to "this latest tree snapshot until input/new observation/expiry; scroll once then re-observe",
                "pagination" to mapOf("offset" to args.int("offset",0),"nextOffset" to page.nextOffset,"matchedExposedNodes" to page.matched,"reobservedEachPage" to true,"blocked" to page.blocked,"resumeHint" to if(page.blocked)"Increase maxNodes/maxChars to fit ancestor context; do not repeat the same offset" else null),
                "coverage" to mapOf("source" to "Accessibility current exposed application nodes","visibleExposedNodes" to page.visible,"offscreenExposedNodes" to page.offscreen,"visitedNodes" to raw.size,
                    "virtualizedContentMayBeUnexposed" to true,"hiddenOrUnloadedContentGuaranteed" to false,"autoScrolled" to false,"offscreenFilterDefinition" to "visibleToUser=false; may include occluded or offscreen exposed nodes",
                    "limits" to "Only nodes provided by the app/AccessibilityNodeProvider. visibleToUser=false is exposed offscreen evidence, not proof of scrolling or access to unexposed/private content. Surface/virtualized lists may omit content; scroll and observe again."),
                "truncationReasons" to reasons.toList(),"emptyReason" to if(page.nodes.isEmpty())"no_matching_exposed_semantics_or_budget" else null),reasons.isNotEmpty())
        } finally {if(!rootHandedOff){@Suppress("DEPRECATION") root.recycle()};handles.values.forEach {@Suppress("DEPRECATION") it.recycle()}}
    }
    private fun activate(e:Envelope,args:Map<String,Any?>,cp:Checkpoint,textCommand:TextInputCommand?=null):ToolResult {
        val reference=textCommand?.nodeRef ?: UiActivationGuard.parse(args)
        requireTool(!inputState.hasPending(),Code.BACKEND_UNAVAILABLE,"Previous gesture has not finished")
        val snapshot=snapshots.validateObservation(e.snapshotId)
        requireTool(snapshot.target.validity=="valid" && !snapshot.target.packageName.isNullOrBlank(),Code.TARGET_CHANGED,"Activation requires an unambiguous selected-screen app")
        val done=CountDownLatch(1);var accepted=false;var error:ToolFailure?=null
        var presentation:Map<String,Any?> = emptyMap()
        main.post {
            val resolved=mutableListOf<AccessibilityNodeInfo>()
            try {
                cp.check()
                synchronized(nodeLock) {
                    val binding=nodeRefs[reference] ?: fail(Code.TARGET_CHANGED,"Activation ref is stale/missing; read ui_tree again")
                    requireTool(binding.uniqueIdentity,Code.TARGET_CHANGED,"Node was exposed at multiple tree paths; choose an unambiguous clickable node")
                    val guard=UiActivationGuard(binding.runId,binding.sessionId,binding.snapshotId,binding.windowId,binding.packageName,binding.path,binding.signature,binding.expires)
                    cp.effect {
                        snapshots.validateObservation(e.snapshotId)
                        val windows=service().windowsOnAllDisplays[snapshot.display.id].orEmpty()
                        val windowId=UiActionWindow.select(snapshot.display,snapshot.target,binding.windowId,binding.packageName,
                            companionWindows(snapshot.display.id,windows))
                        val window=windows.singleOrNull {it.id==windowId && service().isApplicationWindow(it)}
                            ?: fail(Code.TARGET_CHANGED,"Activation window disappeared from the selected screen")
                        var node=window.root ?: fail(Code.TARGET_CHANGED,"Activation tree root disappeared")
                        resolved.add(node)
                        requireTool(node.packageName?.toString()==binding.packageName,Code.TARGET_CHANGED,"Activation root package changed")
                        val path=binding.path.split('/')
                        requireTool(path.firstOrNull()=="root" && path.size<=33,Code.TARGET_CHANGED,"Invalid stored activation path")
                        for(part in path.drop(1)) {
                            val index=part.toIntOrNull() ?: fail(Code.TARGET_CHANGED,"Invalid activation path index")
                            requireTool(index in 0 until node.childCount,Code.TARGET_CHANGED,"Activation path was removed")
                            val child=node.getChild(index) ?: fail(Code.TARGET_CHANGED,"Activation path node disappeared")
                            val cyclic=resolved.any {it==child};resolved.add(child)
                            requireTool(!cyclic,Code.TARGET_CHANGED,"Activation tree path is cyclic/ambiguous")
                            node=child
                        }
                        requireTool(node.refresh(),Code.TARGET_CHANGED,"Activation node disappeared")
                        guard.check(e.runId,e.sessionId,e.snapshotId,node.windowId,node.packageName?.toString(),binding.path,
                            node==binding.node,nodeSignature(node),clock.now())
                        val bounds=Rect();node.getBoundsInScreen(bounds)
                        val actionBounds=TouchableBounds(bounds.left,bounds.top,bounds.right,bounds.bottom)
                        if(textCommand==null)UiActivationGuard.requireClickable(node.isVisibleToUser,node.isEnabled,node.isClickable,node.isPassword,
                            node.actionList.any {it.id==AccessibilityNodeInfo.ACTION_CLICK},actionBounds,snapshot.display,snapshot.target.touchableBounds)
                        else UiTextInputGuard.requireEditable(node.isVisibleToUser,node.isEnabled,node.isEditable,node.isPassword,
                            node.actionList.any {it.id==AccessibilityNodeInfo.ACTION_SET_TEXT},actionBounds,snapshot.display,snapshot.target.touchableBounds)
                        val bundle=if(textCommand==null)null else Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                textCommand.updatedText(node.text?.toString().orEmpty(),node.textSelectionStart,node.textSelectionEnd))
                        }
                        snapshots.validateObservation(e.snapshotId) // Recheck structure/clip after resolving the node.
                        cp.check()
                        if(textCommand==null)presentation=actionPresentation(e,snapshot,bounds=actionBounds)
                        cp.submitted() // The action can reach the app even if its Binder reply fails.
                        accepted=if(textCommand==null)node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            else node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,bundle)
                    }
                    invalidateNodeRefs()
                }
            } catch(t:ToolFailure){error=t}
              catch(t:Exception){error=ToolFailure(if(t is SecurityException)Code.PERMISSION_MISSING else Code.IO_ERROR,"Accessibility node action failed: ${t.javaClass.simpleName}")}
            finally {
                resolved.forEach {@Suppress("DEPRECATION") it.recycle()}
                if(cp.submitted)invalidateNodeRefs()
                done.countDown()
            }
        }
        await(done,e,cp);error?.let {throw it}
        snapshots.validateReadSource(snapshot.id)
        val detail=if(textCommand==null)emptyMap() else mapOf("mode" to textCommand.mode,"characterCount" to textCommand.text.codePointCount(0,textCommand.text.length),"enterSubmitted" to false)
        return result(e,detail+mapOf("submitted" to true,"action" to if(textCommand==null)"click" else "set_text","actionAccepted" to accepted,"foregroundVerified" to false,
            "nodeRefsInvalidated" to true,"nextStep" to "Re-observe ui_tree or screenshot to verify the result before any next input"),outcome="submitted")
            .copy(error=if(accepted)null else Code.BACKEND_UNAVAILABLE,message=if(accepted)null else "App rejected the Accessibility action; re-observe before another action",presentation=presentation)
    }
    private val actionNames=mapOf(
        AccessibilityNodeInfo.ACTION_CLICK to "click",AccessibilityNodeInfo.ACTION_LONG_CLICK to "long_click",AccessibilityNodeInfo.ACTION_FOCUS to "focus",
        AccessibilityNodeInfo.ACTION_SELECT to "select",AccessibilityNodeInfo.ACTION_SET_TEXT to "set_text",AccessibilityNodeInfo.ACTION_EXPAND to "expand",AccessibilityNodeInfo.ACTION_COLLAPSE to "collapse",
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD to "scroll_forward",AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD to "scroll_backward",
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id to "scroll_up",AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id to "scroll_down",
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id to "scroll_left",AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id to "scroll_right",
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id to "scroll_to_position",AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id to "set_progress")
    private fun scroll(e:Envelope,args:Map<String,Any?>,cp:Checkpoint):ToolResult {
        args.only("nodeRef","direction","row","column","expectedPackage")
        requireTool(!inputState.hasPending(),Code.BACKEND_UNAVAILABLE,"Previous gesture has not finished")
        val snapshot=snapshots.validate(e.snapshotId)
        requireTool(snapshot.target.validity=="valid" && !snapshot.target.packageName.isNullOrBlank(),Code.TARGET_CHANGED,"Scroll requires an unambiguous selected-screen app")
        requireTool(args["expectedPackage"]==null || args["expectedPackage"]==snapshot.target.packageName,Code.TARGET_CHANGED,"Expected app does not match selected screen")
        val done=CountDownLatch(1);var accepted=false;var error:ToolFailure?=null;var actionName=""
        main.post {
            try {
                cp.check()
                synchronized(nodeLock) {
                    val binding=nodeRefs[args.string("nodeRef")] ?: fail(Code.TARGET_CHANGED,"Node ref is stale/missing; read ui_tree again")
                    requireTool(binding.runId==e.runId && binding.sessionId==e.sessionId && binding.snapshotId==e.snapshotId && binding.packageName==snapshot.target.packageName,Code.TARGET_CHANGED,"Node ref belongs to another observation/app")
                    val node=binding.node
                    requireTool(node.refresh(),Code.TARGET_CHANGED,"Referenced node disappeared; observe again")
                    val refGuard=UiNodeRefGuard(binding.snapshotId,binding.windowId,binding.packageName,binding.signature,binding.expires)
                    refGuard.check(e.snapshotId,node.windowId,node.packageName?.toString(),nodeSignature(node),clock.now())
                    requireTool(node.isEnabled && node.isVisibleToUser,Code.INVALID_ARGUMENT,"Scroll requires a visible enabled container")
                    val direction=args.string("direction");val supported=node.actionList.map {it.id}.toSet()
                    val names=when(direction){"forward"->listOf("scroll_forward");"backward"->listOf("scroll_backward");"up"->listOf("scroll_up","scroll_backward");"down"->listOf("scroll_down","scroll_forward");"left"->listOf("scroll_left");"right"->listOf("scroll_right");"to_position"->listOf("scroll_to_position");else->emptyList()}
                    val action=names.firstNotNullOfOrNull {name->actionNames.entries.firstOrNull {it.value==name && it.key in supported}} ?: fail(Code.UNSUPPORTED,"Node does not advertise this scroll action")
                    actionName=action.value
                    val bundle=Bundle()
                    if(direction=="to_position") {
                        val info=node.collectionInfo ?: fail(Code.INVALID_ARGUMENT,"Collection dimensions unknown; directional scroll instead")
                        val row=args.int("row",-1);val column=args.int("column",-1)
                        requireTool(row in 0 until info.rowCount && column in 0 until info.columnCount,Code.INVALID_ARGUMENT,"Scroll position outside advertised collection")
                        bundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT,row);bundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT,column)
                    }else requireTool(!args.containsKey("row") && !args.containsKey("column"),message="row/column only for to_position")
                    cp.effect {
                        snapshots.validate(e.snapshotId)
                        requireTool(node.refresh(),Code.TARGET_CHANGED,"Node disappeared immediately before scroll")
                        refGuard.check(e.snapshotId,node.windowId,node.packageName?.toString(),nodeSignature(node),clock.now())
                        cp.submitted() // The IPC may have reached the app even if its reply throws.
                        accepted=node.performAction(action.key,bundle)
                    }
                    invalidateNodeRefs()
                }
            } catch(t:ToolFailure){error=t}
              catch(t:Exception){error=ToolFailure(if(t is SecurityException)Code.PERMISSION_MISSING else Code.IO_ERROR,"Accessibility scroll failed: ${t.javaClass.simpleName}")}
            finally {if(cp.submitted)invalidateNodeRefs();done.countDown()}
        }
        await(done,e,cp)
        error?.let {throw it}
        snapshots.validateHeld(e.snapshotId) // Own scroll/content callbacks may update revision; target structure must remain.
        return result(e,mapOf("submitted" to true,"action" to actionName,"actionAccepted" to accepted,"movementObserved" to false,"nodeRefsInvalidated" to true,"nextStep" to "Read ui_tree or screenshot again to verify position and obtain fresh refs"),outcome="submitted").copy(error=if(accepted)null else Code.BACKEND_UNAVAILABLE,message=if(accepted)null else "App rejected scroll or reached boundary; re-observe before choosing another action")
    }
    private fun lowerKey(display:DisplayFact,target:Target)="$display:${target.layoutRevision}:${target.revision}:${target.touchableBounds}"
    private fun lowerSafety(display:DisplayFact,target:Target):CompanionWindowState {
        val key=lowerKey(display,target)
        lowerSafetyCache?.takeIf {it.key==key && clock.now()-it.at<100}?.let {return it.state}
        val state=CompanionWindows.verify(display,target.packageName,companionWindows(display.id),setOf(context.packageName,"dev.thor.agent"))
        val window=service().windowsOnAllDisplays[display.id].orEmpty().singleOrNull {it.id==state.windowId}
            ?: fail(Code.TARGET_CHANGED,"Lower application window disappeared")
        val root=window.root ?: fail(Code.SECURE_CONTENT,"Lower application exposes no safe window identity")
        var visited=0
        val scanDeadline=clock.now()+2500
        fun scan(node:AccessibilityNodeInfo,depth:Int) {
            try {
                requireTool(++visited<=2048 && depth<=32 && clock.now()<scanDeadline,Code.SECURE_CONTENT,"Lower safety scan exceeded exposed-node/time budget")
                requireTool(!(node.isPassword && node.isVisibleToUser),Code.SECURE_CONTENT,"Lower screen contains a visible password field")
                for(index in 0 until node.childCount)node.getChild(index)?.let {scan(it,depth+1)}
            } finally {@Suppress("DEPRECATION") node.recycle()}
        }
        scan(root,0)
        lowerSafetyCache=LowerSafety(key,clock.now(),state)
        return state
    }
    /** FLAG_SECURE has no reliable public Accessibility metadata getter. Probe
     * through the actual screenshot API without copying/encoding any pixels.
     * No proof is reused across tools: FLAG_SECURE can change without a public
     * Accessibility content event. */
    private fun lowerSecurityProbe(snapshot:Snapshot,e:Envelope,parent:Checkpoint) {
        requireTool(snapshot.role=="lower",message="Lower security probe requires a lower-role snapshot")
        val cp=Checkpoint({parent.check();snapshots.validateObservation(snapshot.id)},parent.cancellation)
        cp.check()
        val done=CountDownLatch(1);var failure:Int?=null;var success=false
        awaitScreenshotInterval(e,cp)
        service().takeScreenshot(snapshot.display.id,context.mainExecutor,object:AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result:AccessibilityService.ScreenshotResult) {try {success=true}finally {result.hardwareBuffer.close();done.countDown()}}
            override fun onFailure(errorCode:Int) {failure=errorCode;done.countDown()}
        })
        await(done,e,cp)
        failure?.let {fail(when(it) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW->Code.SECURE_CONTENT
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY->Code.DISPLAY_GONE
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT->Code.RATE_LIMITED
            else->Code.IO_ERROR
        },"Lower screenshot safety check rejected ($it); no fallback")}
        requireTool(success,Code.SECURE_CONTENT,"Lower secure-window status is unverified")
        cp.check()
    }
    private fun awaitScreenshotInterval(e:Envelope,cp:Checkpoint) {
        while(lastScreenshotAttempt!=Long.MIN_VALUE && clock.now()-lastScreenshotAttempt<350) {
            cp.check();requireTool(clock.now()<e.deadline,Code.TIMEOUT,"Screenshot safety wait expired")
            Thread.sleep(25)
        }
        cp.check();lastScreenshotAttempt=clock.now()
    }
    private fun companionWindows(display:Int,windows:List<AccessibilityWindowInfo> = service().windowsOnAllDisplays[display].orEmpty()):List<TargetWindowEvidence> =
        windows.map {service().windowEvidence(it)}
    private fun screenshot(e: Envelope, args: Map<String, Any?>, requestCheckpoint: Checkpoint,companion:Boolean=false): ToolResult {
        if(companion)args.only("maxEdge","quality","format") else args.only("maxEdge", "quality", "region", "format")
        requireTool(args.string("format", "jpeg") == "jpeg", Code.UNSUPPORTED, "Only JPEG implemented")
        val edge = args.int("maxEdge", 1280); val quality = args.int("quality", 80)
        requireTool(edge in 64..1280 && quality in 30..95, Code.LIMIT_EXCEEDED, "Screenshot edge/quality budget exceeded")
        // A screenshot is a new observation, not permission to rebind an input.
        // Its own identity/freshness lifetime begin now; every image callback checks
        // this fresh window/geometry and discards output if structure changes.
        // Content counters may update; old pixels keep their capture identity.
        val forbidden=setOf(context.packageName,"dev.thor.agent")
        val companionObservation=if(companion)snapshots.captureCompanion(e.snapshotId,forbidden) else null
        val snap = companionObservation?.upper ?: snapshots.captureForRead(e.snapshotId)
        val captureDisplay=companionObservation?.lowerDisplay ?: snap.display
        val selectedLowerState=if(!companion && snap.role=="lower")lowerSafety(captureDisplay,snap.target) else null
        val companionState=companionObservation?.let {CompanionWindows.verify(captureDisplay,snap.target.packageName,companionWindows(captureDisplay.id),forbidden)}
        val cp=Checkpoint({
            requestCheckpoint.check();snapshots.validateObservation(snap.id)
            if(companionObservation!=null) {
                snapshots.validateCompanion(companionObservation)
                requireTool(CompanionWindows.verify(captureDisplay,snap.target.packageName,companionWindows(captureDisplay.id),forbidden)==companionState,
                    Code.TARGET_CHANGED,"Companion visible windows changed during capture")
            }
        },requestCheckpoint.cancellation)
        val region = args["region"] as? List<*>
        val safeLowerClip=companionState?.clip ?: selectedLowerState?.clip
        val rect = if(safeLowerClip!=null && region==null)safeLowerClip.let {Rect(it.left,it.top,it.right,it.bottom)} else if (region == null) Rect(0, 0, captureDisplay.width, captureDisplay.height) else {
            requireTool(region.size == 4 && region.all { it is Number }, message = "Region is [left,top,right,bottom]")
            Rect((region[0] as Number).toInt(), (region[1] as Number).toInt(), (region[2] as Number).toInt(), (region[3] as Number).toInt())
        }
        requireTool(rect.left >= 0 && rect.top >= 0 && rect.right <= captureDisplay.width && rect.bottom <= captureDisplay.height && rect.width() > 0 && rect.height() > 0, message = "Crop outside display")
        if(safeLowerClip!=null)requireTool(rect.left>=safeLowerClip.left && rect.top>=safeLowerClip.top && rect.right<=safeLowerClip.right && rect.bottom<=safeLowerClip.bottom,
            Code.SECURE_CONTENT,"Lower capture must remain inside the verified application-only region")
        requireTool(captureDisplay.width.toLong() * captureDisplay.height <= 16_000_000, Code.LIMIT_EXCEEDED, "Screenshot source exceeds 16MP allocation budget")
        val done = CountDownLatch(1)
        val bitmap = java.util.concurrent.atomic.AtomicReference<Bitmap?>()
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        var error: Int? = null
        var conversionError: ToolFailure? = null
        cp.cancellation.register { abandoned.set(true); bitmap.getAndSet(null)?.recycle() }
        cp.check()
        awaitScreenshotInterval(e,cp)
        service().takeScreenshot(captureDisplay.id, context.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                try {
                    if (!cp.cancellation.cancelled && !abandoned.get()) {
                        cp.check()
                        val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        try {
                            val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            bitmap.set(copy)
                            if (abandoned.get()) bitmap.getAndSet(null)?.recycle()
                        } finally { hw?.recycle() }
                    }
                } catch(t:ToolFailure) {conversionError=t
                } catch (_: OutOfMemoryError) {
                    conversionError = ToolFailure(Code.LIMIT_EXCEEDED, "Screenshot conversion exceeded memory budget")
                } catch (t: Exception) {
                    conversionError = ToolFailure(Code.IO_ERROR, "Screenshot conversion failed: ${t.javaClass.simpleName}")
                } finally { screenshot.hardwareBuffer.close(); done.countDown() }
            }
            override fun onFailure(errorCode: Int) { error = errorCode; done.countDown() }
        })
        try { await(done, e, cp) } catch (t: Exception) {
            abandoned.set(true); bitmap.getAndSet(null)?.recycle(); throw t
        }
        conversionError?.let { throw it }
        error?.let { fail(when (it) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> Code.SECURE_CONTENT
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> Code.DISPLAY_GONE
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> Code.RATE_LIMITED
            else -> Code.IO_ERROR
        }, "Accessibility screenshot error $it; no Shell fallback") }
        val original = bitmap.getAndSet(null) ?: fail(Code.IO_ERROR, "Missing screenshot bitmap")
        var storedImage:File?=null
        try {
            cp.check()
            requireTool(original.width == captureDisplay.width && original.height == captureDisplay.height, Code.TARGET_CHANGED, "Screenshot/display geometry mismatch; recalibrate")
            val crop = Bitmap.createBitmap(original, rect.left, rect.top, rect.width(), rect.height())
            var scaled: Bitmap? = null
            try {
                val ratio = minOf(1.0, edge.toDouble() / maxOf(crop.width, crop.height))
                scaled = Bitmap.createScaledBitmap(crop, maxOf(1, (crop.width * ratio).roundToInt()), maxOf(1, (crop.height * ratio).roundToInt()), true)
                var encoded = ByteArray(0); var q = quality
                repeat(4) {
                    if (encoded.size > Budgets.IMAGE || encoded.isEmpty()) { cp.check(); encoded = ByteArrayOutputStream().also { scaled!!.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray(); q = maxOf(30, q - 15) }
                }
                requireTool(encoded.size <= Budgets.IMAGE, Code.LIMIT_EXCEEDED, "Compressed image exceeds 2MiB; choose a smaller maxEdge")
                val attachments = File(context.cacheDir, "tool-frames").apply { mkdirs() }
                attachments.listFiles()?.sortedBy { it.lastModified() }?.dropLast(7)?.forEach { it.delete() }
                val frame = java.util.UUID.randomUUID().toString()
                val output = File(attachments, "$frame.jpg"); output.writeBytes(encoded);storedImage=output
                frames.entries.removeIf { !File(attachments, "${it.key}.jpg").exists() }
                runCatching {presentationFrames.keys.retainAll(frames.keys)}
                val transform = FrameTransform(rect.left, rect.top, rect.width(), rect.height(), scaled!!.width, scaled!!.height)
                cp.check()
                val completed=if(companionObservation!=null)snapshots.completeCompanion(companionObservation) else snapshots.captureReadCompletion(snap.id)
                if(companionObservation!=null) {
                    cp.check()
                    val metadata=mapOf("displayRole" to "lower-companion-read-only","displayId" to captureDisplay.id,
                        "authorizedInputDisplayId" to snap.display.id,"companionObservationId" to companionObservation.id,
                        "attachmentRef" to output.absolutePath,"readOnly" to true,"frameInputUsable" to false,"coordinateSpace" to "companion_read_only",
                        "requestSnapshotId" to e.snapshotId,"captureUpperSnapshotId" to snap.id,"targetSnapshotId" to completed.id,
                        "observedTargetPackage" to snap.target.packageName,"sameAppCompanion" to true,
                        "mime" to "image/jpeg","bytes" to encoded.size,"width" to scaled!!.width,"height" to scaled!!.height,
                        "quality" to maxOf(30,q+15),"rotation" to captureDisplay.rotation,
                        "companionImageTransform" to mapOf("identity" to companionObservation.id,"left" to rect.left,"top" to rect.top,
                            "displayWidth" to rect.width(),"displayHeight" to rect.height(),"imageWidth" to scaled!!.width,"imageHeight" to scaled!!.height),
                        "nextStep" to "Read-only same-app companion pixels. No frameId or lower input authority exists; never reuse these image coordinates for upper input. Re-observe the upper screen before input.")
                    // Keep the tool envelope on an UPPER snapshot. The companion
                    // image identity is deliberately absent from the input frames map.
                    return result(e.copy(snapshotId=completed.id),emptyMap()).copy(data=metadata)
                }
                val contentUnchanged=snap.target.sameLayout(completed.target)
                val inputCompatible=snap.target.sameInputTarget(completed.target)
                // Captured pixels retain their original identity below. Content
                // animation does not change the coordinate transform or input
                // target: bind that frame to the completion observation only if
                // window, exact clipping and interaction layout still match.
                // Scrolling/reflow cannot make an older image newly input-usable.
                frames[frame] = InputFrameBinding(e.sessionId,e.runId,if(inputCompatible)completed.id else snap.id,transform)
                // UI provenance retains the captured pixels, not a replacement observation.
                runCatching {presentationFrames[frame]=PresentationFrame(e.sessionId,e.runId,e.capabilityEpoch,snap,transform,presentationOrder.incrementAndGet())}
                return result(e.copy(snapshotId=completed.id), mapOf("displayRole" to snap.role, "displayId" to snap.display.id, "frameId" to frame, "attachmentRef" to output.absolutePath,
                    "requestSnapshotId" to e.snapshotId,"captureSnapshotId" to snap.id,"targetSnapshotId" to completed.id,"frameInputUsable" to inputCompatible,"contentChangedDuringCapture" to !contentUnchanged,
                    "inputTargetChangedDuringCapture" to !inputCompatible,"frameInputPolicy" to "same_window_geometry_and_interaction_layout","observedTargetPackage" to snap.target.packageName,
                    "touchableWindowBounds" to snap.target.touchableBounds?.let {mapOf("left" to it.left,"top" to it.top,"right" to it.right,"bottom" to it.bottom)},
                    "mime" to "image/jpeg", "bytes" to encoded.size, "width" to scaled!!.width, "height" to scaled!!.height, "quality" to maxOf(30, q + 15), "rotation" to snap.display.rotation,
                    "transform" to mapOf("left" to rect.left, "top" to rect.top, "displayWidth" to rect.width(), "displayHeight" to rect.height(), "imageWidth" to scaled!!.width, "imageHeight" to scaled!!.height)))
            } finally { if (scaled !== crop) scaled?.recycle(); if (crop !== original) crop.recycle() }
        } catch (_: OutOfMemoryError) { if(companion)storedImage?.delete();fail(Code.LIMIT_EXCEEDED, "Screenshot processing exceeded memory budget") }
        catch(t:Exception) {if(companion)storedImage?.delete();throw t}
        finally { original.recycle() }
    }
    private fun gesture(e: Envelope, args: Map<String, Any?>, cp: Checkpoint): ToolResult {
        args.only("x", "y", "points", "durationMs", "coordinateSpace", "frameId", "expectedPackage")
        requireTool(!inputState.hasPending(), Code.BACKEND_UNAVAILABLE, "Previous gesture has not finished")
        val snap = snapshots.validate(e.snapshotId)
        val expected = args["expectedPackage"] as? String
        requireTool(expected == null || expected == snap.target.packageName, Code.TARGET_CHANGED, "Expected app does not match selected screen")
        val coordinateSpace = args.string("coordinateSpace")
        requireTool(coordinateSpace in setOf("display_logical", "frame_pixels"), message = "Explicit coordinateSpace required")
        val raw = if (e.toolId in setOf("input.swipe","input.drag")) {
            val list = args["points"] as? List<*> ?: fail(Code.INVALID_ARGUMENT, "Swipe points required")
            requireTool(list.size in 2..32, Code.LIMIT_EXCEEDED, "Swipe needs 2..32 points")
            list.map { point -> val a = point as? List<*> ?: fail(Code.INVALID_ARGUMENT, "Point must be [x,y]"); requireTool(a.size == 2 && a.all { it is Number }, message = "Point must be [x,y]"); Point((a[0] as Number).toDouble(), (a[1] as Number).toDouble()) }
        } else listOf(Point(args.number("x", Double.NaN).toDouble(), args.number("y", Double.NaN).toDouble()))
        val frameId=if(coordinateSpace=="frame_pixels")args.string("frameId") else null
        val frame=frameId?.let {frames[it] ?: fail(Code.TARGET_CHANGED,"Frame missing/evicted")}
        val points = if(frame!=null)snapshots.validateInputFrame(frame,e.sessionId,e.runId,e.snapshotId,raw) else raw
        val duration = args.int("durationMs", when (e.toolId) { "input.tap" -> 80; "input.long_press" -> 700; else -> 500 }).toLong()
        val input = Gesture(points, duration); input.validate(snap.display)
        snapshots.validateInputPoints(e.snapshotId,points)
        val path = Path().apply { moveTo(points[0].x.toFloat(), points[0].y.toFloat()); points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) } }
        val gesture = GestureDescription.Builder().setDisplayId(snap.display.id).addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val done = CountDownLatch(1); var completed = false; var accepted = false; var dispatchError: ToolFailure? = null
        var presentation:Map<String,Any?> = emptyMap()
        main.post {
            try {
                cp.check() // Final target/auth check immediately before the actual system dispatch.
                cp.effect {
                    if(frame!=null) {
                        requireTool(frames[frameId]===frame,Code.TARGET_CHANGED,"Frame evicted before input submission")
                        requireTool(snapshots.validateInputFrame(frame,e.sessionId,e.runId,e.snapshotId,raw)==points,Code.TARGET_CHANGED,"Frame mapping changed before input submission")
                    }
                    snapshots.validateInputPoints(e.snapshotId,points)
                    presentation=actionPresentation(e,snap,points=points,exactFrameId=frameId)
                    invalidateNodeRefs()
                    // Register before Binder submission so an early callback cannot be lost.
                    // A normally pending stroke is owned by the Gateway resource lock,
                    // not an unknown input: its own checkpoint must remain available.
                    inputState.register(e.runId,e.requestId)
                    cp.submitted() // A throwing Binder call may still have submitted this bounded stroke.
                    accepted = service().dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { completed = true; inputState.complete(e.runId,e.requestId); done.countDown() }
                    override fun onCancelled(g: GestureDescription?) { inputState.complete(e.runId,e.requestId); done.countDown() }
                }, main)
                    if(!accepted){inputState.complete(e.runId,e.requestId)}
                    requireTool(accepted, Code.BACKEND_UNAVAILABLE, "System rejected gesture")
                }
                if (!accepted) done.countDown()
            } catch (t: ToolFailure) { inputState.abandon(e.runId,e.requestId);dispatchError = t; done.countDown() }
              catch (t: Exception) {
                inputState.abandon(e.runId,e.requestId)
                dispatchError = ToolFailure(if (t is SecurityException) Code.PERMISSION_MISSING else Code.IO_ERROR, "Gesture dispatch failed: ${t.javaClass.simpleName}")
                done.countDown()
            }
        }
        try {await(done,e,cp)}catch(t:Exception){inputState.abandon(e.runId,e.requestId);throw t}
        dispatchError?.let { throw it }
        requireTool(accepted, Code.BACKEND_UNAVAILABLE, "System rejected gesture")
        return result(e, mapOf("submitted" to true, "systemCallback" to if (completed) "completed" else "cancelled", "gameEffectObserved" to false,
            "displayId" to snap.display.id, "durationMs" to duration,"gestureKind" to if(e.toolId=="input.drag")"bounded-drag-down-move-up" else e.toolId,"nodeRefsInvalidated" to true), outcome = if (completed) "submitted" else "unknown").copy(presentation=presentation)
    }
    private fun await(done: CountDownLatch, e: Envelope, cp: Checkpoint) {
        while (!done.await(50, TimeUnit.MILLISECONDS)) { cp.check(); requireTool(clock.now() < e.deadline, Code.TIMEOUT, "Android callback deadline expired") }
        cp.check()
    }
    override fun release(runId: String): String {
        runCatching {presentationFrames.entries.removeIf {it.value.runId==runId}}
        synchronized(nodeLock){if(nodeRefs.values.any {it.runId==runId})invalidateNodeRefs()}
        val gamepad=shizuku.releaseRunInputs(runId)
        if (inputState.abandonRun(runId)) { return "unknown: Android has no public cancelGesture; already submitted bounded stroke may finish within 5s; further gestures blocked until callback" }
        return "observed: $gamepad; no gesture pending"
    }
}
fun displayFacts(context: Context): List<DisplayFact> = context.getSystemService(DisplayManager::class.java).displays.map { d ->
    val metrics = android.util.DisplayMetrics(); @Suppress("DEPRECATION") d.getRealMetrics(metrics)
    // Public display name + ID is an observation, not a durable physical identifier; reconfirm after process restart.
    DisplayFact(d.displayId, d.name, metrics.widthPixels, metrics.heightPixels, d.rotation, metrics.densityDpi)
}
