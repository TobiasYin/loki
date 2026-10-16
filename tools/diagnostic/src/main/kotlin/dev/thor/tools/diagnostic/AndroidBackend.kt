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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class AndroidBackend(val context: Context, val clock: Clock, val snapshots: Snapshots, val shizuku: DebugConnection,
    val files: LocalFiles, val transactions: ConfigTransactions) : DeviceBackend {
    private val schemas = InputSchemas(context)
    private val main = Handler(Looper.getMainLooper())
    private val activeInputs = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val frames = java.util.concurrent.ConcurrentHashMap<String, Pair<String, FrameTransform>>()
    var approval: Approval? = null
    var shellApproval: String? = null
    var installApproval: String? = null
    val downloads=NativeDownloads(context)
    @Volatile private var inputUnknown = false
    private val gestureTools = setOf("input.tap", "input.long_press", "input.swipe")
    override fun availability(toolId: String): Availability {
        val backend = when {
            toolId in setOf("web.request","files.download","files.download_info") -> "native-http/private-downloads"
            toolId.startsWith("apps.") -> "shizuku-userservice/shell-uid-2000"
            toolId.startsWith("perception.") -> "android-sdk/accessibility"
            toolId.startsWith("input.") -> "accessibility-dispatchGesture"
            toolId.startsWith("debug.") -> "shizuku-userservice"
            else -> "synthetic-config/android-app-owned-root"
        }
        if (toolId in setOf("input.press_button", "input.set_axes")) return shizuku.gamepadAvailability(toolId)
        if (toolId in setOf("debug.shell","debug.thor_brightness","apps.install","apps.package_info")) return shizuku.availability()
        if (toolId == "debug.command_policy") return Availability(Status.READY,"Native command policy; no device mutation","native-command-policy")
        if (toolId in gestureTools && inputUnknown) return Availability(Status.TEMPORARILY_UNAVAILABLE, "Previous submitted gesture has no completion/cancel callback; stop status unknown", backend)
        if (toolId == "web.request" || toolId == "perception.device_info" || toolId.startsWith("files.") || toolId == "input.release_inputs") return Availability(Status.READY, "Manual diagnostics only", backend)
        if (ThorAccessibilityService.instance == null) return Availability(Status.PERMISSION_MISSING, "Enable this diagnostic Accessibility service in Android settings", backend)
        return Availability(Status.READY, "Android API exists; delivery on Thor is unverified", backend)
    }
    override fun availability(toolId: String, args: Map<String, Any?>): Availability =
        if (toolId in setOf("files.list", "files.read") && args["backend"] == "shizuku")
            shizuku.availability().copy(backend = "shizuku-userservice/shared-root-read-only") else availability(toolId)
    private fun service() = ThorAccessibilityService.instance ?: fail(Code.PERMISSION_MISSING, "Accessibility disconnected")
    private fun result(e: Envelope, data: Map<String, Any?>, truncated: Boolean = false, outcome: String = "observed", actualBackend: String? = null): ToolResult {
        val a = if (actualBackend == null) availability(e.toolId) else shizuku.availability().copy(backend = actualBackend)
        val target = if (ToolPack.descriptors[e.toolId]?.targetRequired == true) runCatching { snapshots.selected() }.getOrNull() else null
        val common = if (target == null) data else data + mapOf("displayRole" to "upper", "displayId" to target.id)
        return ToolResult(clock.now(), a.backend, a, common, truncated = truncated, outcome = outcome, snapshotId = e.snapshotId)
    }
    override fun execute(envelope: Envelope, args: Map<String, Any?>, checkpoint: Checkpoint): ToolResult {
        schemas.validate(envelope.toolId, args)
        // Read the current app/window/tree even after the model has spent more
        // than an observation lifetime reasoning. The original source still fixes the
        // authorized display/epoch; a fresh target must survive the whole read.
        // Screenshot already owns this lifecycle, including its async callback.
        val observation=if(envelope.toolId in ToolPack.freshReads && envelope.toolId!="perception.capture_screen") {
            checkpoint.check();snapshots.captureForRead(envelope.snapshotId)
        } else null
        val e=if(observation==null)envelope else envelope.copy(snapshotId=observation.id)
        val cp=if(observation==null)checkpoint else Checkpoint({checkpoint.check();snapshots.validate(observation.id)},checkpoint.cancellation)
        cp.check()
        val output=when (e.toolId) {
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
                val snapshot = snapshots.validate(e.snapshotId)
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
            "input.press_button", "input.set_axes" -> {
                val snap=snapshots.validate(e.snapshotId);cp.check()
                if(e.toolId in setOf("input.press_button","input.set_axes")) {
                    val window=service().windowsOnAllDisplays[snap.display.id].orEmpty().singleOrNull {it.type==AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused}
                    val root=window?.root
                    val focusedPackage=try {root?.packageName?.toString()} finally {if(root!=null) {@Suppress("DEPRECATION") root.recycle()}}
                    requireTool(focusedPackage==snap.target.packageName && focusedPackage!=null,Code.TARGET_CHANGED,"Physical controller requires the observed upper app to have window focus")
                }
                val held=cp.checkingTarget(snapshots,e.snapshotId)
                result(e,shizuku.gamepad(e.runId,e.toolId,args,snap.display.id,held,expectedPackage=snap.target.packageName),outcome="submitted",actualBackend=if(e.toolId=="input.set_axes")"shizuku-evdev/odin-six-axes" else "shizuku-evdev/odin-buttons")
            }
            "input.tap", "input.long_press", "input.swipe" -> gesture(e, args, cp)
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
            cp.check() // Reject the whole response if the observed target moved while reading.
            return output.copy(data=output.data+mapOf("requestSnapshotId" to envelope.snapshotId,"targetSnapshotId" to observation.id,"observedTargetPackage" to observation.target.packageName))
        }
        return output
    }
    private fun windowInfo(e: Envelope, args: Map<String, Any?>): ToolResult {
        args.only("maxWindows")
        val limit = args.int("maxWindows", 16); requireTool(limit in 1..16, Code.LIMIT_EXCEEDED, "Window limit is 1..16")
        val snap = snapshots.validate(e.snapshotId)
        val windows = service().windowsOnAllDisplays[snap.display.id].orEmpty()
        return result(e, mapOf("displayRole" to "upper", "displayId" to snap.display.id, "activity" to null, "completeSystemTaskList" to false,
            "windows" to windows.take(limit).map { w -> val r = Rect(); w.getBoundsInScreen(r); mapOf("windowId" to w.id, "type" to w.type, "layer" to w.layer, "focused" to w.isFocused, "active" to w.isActive, "bounds" to listOf(r.left, r.top, r.right, r.bottom)) }), windows.size > limit)
    }
    private fun tree(e: Envelope, args: Map<String, Any?>, cp: Checkpoint): ToolResult {
        args.only("windowId", "maxDepth", "maxNodes", "maxChars")
        val depthLimit = args.int("maxDepth", 5); val nodeLimit = args.int("maxNodes", 80); val charLimit = args.int("maxChars", 12000)
        requireTool(depthLimit in 0..5 && nodeLimit in 1..80 && charLimit in 1..12000, Code.LIMIT_EXCEEDED, "UI tree budget exceeded")
        val snap = snapshots.validate(e.snapshotId)
        val windows = service().windowsOnAllDisplays[snap.display.id].orEmpty()
        val window = if (args.containsKey("windowId")) windows.singleOrNull { it.id == args.int("windowId", -1) } else windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.singleOrNull()
        if (window == null) return result(e, mapOf("nodes" to emptyList<Any>(), "emptyReason" to "no_unique_application_window"))
        val root = window.root ?: return result(e, mapOf("nodes" to emptyList<Any>(), "emptyReason" to "null_root_or_surface_content"))
        val nodes = mutableListOf<Map<String, Any?>>()
        var chars = 0; var truncated = false
        fun visit(node: AccessibilityNodeInfo, depth: Int, ref: String) {
            try {
                cp.check()
                if (nodes.size >= nodeLimit || chars >= charLimit) { truncated = true; return }
                fun text(v: CharSequence?): String? { val s = v?.toString() ?: return null; val bound = s.take(charLimit - chars); chars += bound.length; if (bound.length < s.length) truncated = true; return bound }
                val r = Rect(); node.getBoundsInScreen(r)
                nodes.add(mapOf("ref" to ref, "role" to text(node.className), "text" to if (node.isPassword) text("[password hidden]") else text(node.text), "description" to if (node.isPassword) null else text(node.contentDescription), "clickable" to node.isClickable, "enabled" to node.isEnabled, "bounds" to listOf(r.left, r.top, r.right, r.bottom)))
                if (depth >= depthLimit) { if (node.childCount > 0) truncated = true; return }
                for (i in 0 until node.childCount) {
                    if (nodes.size >= nodeLimit || chars >= charLimit) { truncated = true; break }
                    node.getChild(i)?.let { visit(it, depth + 1, "$ref/$i") }
                }
            } finally { @Suppress("DEPRECATION") node.recycle() }
        }
        visit(root, 0, "${e.snapshotId}/${window.id}")
        return result(e, mapOf("nodes" to nodes, "windowId" to window.id, "nodeRefsReusable" to false, "emptyReason" to if (nodes.size == 1 && nodes[0]["text"] == null) "may_be_self_drawn_surface" else null), truncated)
    }
    private fun screenshot(e: Envelope, args: Map<String, Any?>, requestCheckpoint: Checkpoint): ToolResult {
        args.only("maxEdge", "quality", "region", "format")
        requireTool(args.string("format", "jpeg") == "jpeg", Code.UNSUPPORTED, "Only JPEG implemented")
        val edge = args.int("maxEdge", 1280); val quality = args.int("quality", 80)
        requireTool(edge in 64..1280 && quality in 30..95, Code.LIMIT_EXCEEDED, "Screenshot edge/quality budget exceeded")
        // A screenshot is a new observation, not permission to rebind an input.
        // Its own identity/freshness lifetime begin now; every image callback checks
        // this fresh target and discards output if it changes during capture.
        val snap = snapshots.captureForRead(e.snapshotId)
        val cp=Checkpoint({requestCheckpoint.check();snapshots.validate(snap.id)},requestCheckpoint.cancellation)
        val region = args["region"] as? List<*>
        val rect = if (region == null) Rect(0, 0, snap.display.width, snap.display.height) else {
            requireTool(region.size == 4 && region.all { it is Number }, message = "Region is [left,top,right,bottom]")
            Rect((region[0] as Number).toInt(), (region[1] as Number).toInt(), (region[2] as Number).toInt(), (region[3] as Number).toInt())
        }
        requireTool(rect.left >= 0 && rect.top >= 0 && rect.right <= snap.display.width && rect.bottom <= snap.display.height && rect.width() > 0 && rect.height() > 0, message = "Crop outside display")
        requireTool(snap.display.width.toLong() * snap.display.height <= 16_000_000, Code.LIMIT_EXCEEDED, "Screenshot source exceeds 16MP allocation budget")
        val done = CountDownLatch(1)
        val bitmap = java.util.concurrent.atomic.AtomicReference<Bitmap?>()
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        var error: Int? = null
        var conversionError: ToolFailure? = null
        cp.cancellation.register { abandoned.set(true); bitmap.getAndSet(null)?.recycle() }
        cp.check()
        service().takeScreenshot(snap.display.id, context.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                try {
                    if (!cp.cancellation.cancelled && !abandoned.get()) {
                        val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        try {
                            val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            bitmap.set(copy)
                            if (abandoned.get()) bitmap.getAndSet(null)?.recycle()
                        } finally { hw?.recycle() }
                    }
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
        try {
            cp.check()
            requireTool(original.width == snap.display.width && original.height == snap.display.height, Code.TARGET_CHANGED, "Screenshot/display geometry mismatch; recalibrate")
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
                val output = File(attachments, "$frame.jpg"); output.writeBytes(encoded)
                frames.entries.removeIf { !File(attachments, "${it.key}.jpg").exists() }
                val transform = FrameTransform(rect.left, rect.top, rect.width(), rect.height(), scaled!!.width, scaled!!.height)
                cp.check()
                frames[frame] = snap.id to transform
                return result(e.copy(snapshotId=snap.id), mapOf("displayRole" to "upper", "displayId" to snap.display.id, "frameId" to frame, "attachmentRef" to output.absolutePath,
                    "requestSnapshotId" to e.snapshotId,"targetSnapshotId" to snap.id,"observedTargetPackage" to snap.target.packageName,
                    "touchableWindowBounds" to snap.target.touchableBounds?.let {mapOf("left" to it.left,"top" to it.top,"right" to it.right,"bottom" to it.bottom)},
                    "mime" to "image/jpeg", "bytes" to encoded.size, "width" to scaled!!.width, "height" to scaled!!.height, "quality" to maxOf(30, q + 15), "rotation" to snap.display.rotation,
                    "transform" to mapOf("left" to rect.left, "top" to rect.top, "displayWidth" to rect.width(), "displayHeight" to rect.height(), "imageWidth" to scaled!!.width, "imageHeight" to scaled!!.height)))
            } finally { if (scaled !== crop) scaled?.recycle(); if (crop !== original) crop.recycle() }
        } catch (_: OutOfMemoryError) { fail(Code.LIMIT_EXCEEDED, "Screenshot processing exceeded memory budget") }
        finally { original.recycle() }
    }
    private fun gesture(e: Envelope, args: Map<String, Any?>, cp: Checkpoint): ToolResult {
        args.only("x", "y", "points", "durationMs", "coordinateSpace", "frameId", "expectedPackage")
        requireTool(activeInputs.isEmpty(), Code.BACKEND_UNAVAILABLE, "Previous gesture has not finished")
        val snap = snapshots.validate(e.snapshotId)
        val expected = args["expectedPackage"] as? String
        requireTool(expected == null || expected == snap.target.packageName, Code.TARGET_CHANGED, "Expected app does not match upper")
        val coordinateSpace = args.string("coordinateSpace")
        requireTool(coordinateSpace in setOf("display_logical", "frame_pixels"), message = "Explicit coordinateSpace required")
        val raw = if (e.toolId == "input.swipe") {
            val list = args["points"] as? List<*> ?: fail(Code.INVALID_ARGUMENT, "Swipe points required")
            requireTool(list.size in 2..32, Code.LIMIT_EXCEEDED, "Swipe needs 2..32 points")
            list.map { point -> val a = point as? List<*> ?: fail(Code.INVALID_ARGUMENT, "Point must be [x,y]"); requireTool(a.size == 2 && a.all { it is Number }, message = "Point must be [x,y]"); Point((a[0] as Number).toDouble(), (a[1] as Number).toDouble()) }
        } else listOf(Point(args.number("x", Double.NaN).toDouble(), args.number("y", Double.NaN).toDouble()))
        val points = if (coordinateSpace == "frame_pixels") {
            val frame = frames[args.string("frameId")] ?: fail(Code.TARGET_CHANGED, "Frame missing/evicted")
            requireTool(frame.first == e.snapshotId, Code.TARGET_CHANGED, "Frame must belong to this current snapshot")
            raw.map(frame.second::toDisplay)
        } else raw
        val duration = args.int("durationMs", when (e.toolId) { "input.tap" -> 80; "input.long_press" -> 700; else -> 500 }).toLong()
        val input = Gesture(points, duration); input.validate(snap.display)
        snapshots.validateInputPoints(e.snapshotId,points)
        val path = Path().apply { moveTo(points[0].x.toFloat(), points[0].y.toFloat()); points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) } }
        val gesture = GestureDescription.Builder().setDisplayId(snap.display.id).addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val done = CountDownLatch(1); var completed = false; var accepted = false; var dispatchError: ToolFailure? = null
        main.post {
            try {
                cp.check() // Final target/auth check immediately before the actual system dispatch.
                cp.effect {
                    snapshots.validateInputPoints(e.snapshotId,points)
                    accepted = service().dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { completed = true; activeInputs.remove(e.runId); inputUnknown = activeInputs.isNotEmpty(); done.countDown() }
                    override fun onCancelled(g: GestureDescription?) { activeInputs.remove(e.runId); inputUnknown = activeInputs.isNotEmpty(); done.countDown() }
                }, main)
                    requireTool(accepted, Code.BACKEND_UNAVAILABLE, "System rejected gesture")
                    activeInputs[e.runId] = true
                }
                if (!accepted) done.countDown()
            } catch (t: ToolFailure) { dispatchError = t; done.countDown() }
              catch (t: Exception) {
                dispatchError = ToolFailure(if (t is SecurityException) Code.PERMISSION_MISSING else Code.IO_ERROR, "Gesture dispatch failed: ${t.javaClass.simpleName}")
                done.countDown()
            }
        }
        await(done, e, cp)
        dispatchError?.let { throw it }
        requireTool(accepted, Code.BACKEND_UNAVAILABLE, "System rejected gesture")
        return result(e, mapOf("submitted" to true, "systemCallback" to if (completed) "completed" else "cancelled", "gameEffectObserved" to false,
            "displayId" to snap.display.id, "durationMs" to duration), outcome = if (completed) "submitted" else "unknown")
    }
    private fun await(done: CountDownLatch, e: Envelope, cp: Checkpoint) {
        while (!done.await(50, TimeUnit.MILLISECONDS)) { cp.check(); requireTool(clock.now() < e.deadline, Code.TIMEOUT, "Android callback deadline expired") }
        cp.check()
    }
    override fun release(runId: String): String {
        val gamepad=shizuku.releaseRunInputs(runId)
        if (activeInputs.containsKey(runId)) { inputUnknown = true; return "unknown: Android has no public cancelGesture; already submitted bounded stroke may finish within 5s; further gestures blocked until callback" }
        return "observed: $gamepad; no gesture pending"
    }
}
fun displayFacts(context: Context): List<DisplayFact> = context.getSystemService(DisplayManager::class.java).displays.map { d ->
    val metrics = android.util.DisplayMetrics(); @Suppress("DEPRECATION") d.getRealMetrics(metrics)
    // Public display name + ID is an observation, not a durable physical identifier; reconfirm after process restart.
    DisplayFact(d.displayId, d.name, metrics.widthPixels, metrics.heightPixels, d.rotation, metrics.densityDpi)
}
