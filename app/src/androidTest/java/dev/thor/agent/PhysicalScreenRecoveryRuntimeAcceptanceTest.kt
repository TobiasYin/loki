package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.webkit.WebView
import dev.thor.tools.Snapshots
import dev.thor.tools.diagnostic.ThorAccessibilityService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Opt-in saved-provider acceptance through the actual Pi execution document.
 * Only the independent Input Lab settings fixture is observed or clicked. No
 * system-setting action, direct input injection, model deadline, credential
 * configuration, user-session deletion or manual accessibility repair exists. */
@RunWith(AndroidJUnit4::class)
class PhysicalScreenRecoveryRuntimeAcceptanceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val terminal = setOf("completed", "failed", "cancelled", "interrupted")
    private val requiredTools = setOf("perception.app_info", "perception.capture_screen", "perception.ui_tree", "input.tap")
    private val nativeFailures = mutableListOf<JSONObject>()
    private fun field(host: ProductHost, name: String): Any = requireNotNull(
        ProductHost::class.java.getDeclaredField(name).apply { isAccessible = true }.get(host))
    private fun rpc(host: ProductHost, connection: String, data: JSONObject): JSONObject {
        val response = host.rpc(connection, obj("op" to "ui", "data" to data)).get(30, TimeUnit.SECONDS)
        // Successful UI RPC returns the full view, whose `error` is the page's
        // existing lastError. Only an error-only RPC result rejects the action.
        val accepted = response.optJSONArray("sessions") != null && response.optJSONArray("activeRuns") != null
        if (!accepted) {
            val operations = setOf("new", "sessionOptions", "capability", "select", "delete")
            val codes = setOf("IO_ERROR", "INVALID_ARGUMENT", "STALE_AUTHORIZATION", "CANCELLED", "PERMISSION_MISSING", "PERSISTENCE_ERROR")
            nativeFailures.add(obj("operation" to data.optString("op").takeIf { it in operations }.orEmpty().ifEmpty { "other" },
                "code" to response.optString("error").takeIf { it in codes }.orEmpty().ifEmpty { "UNKNOWN" }))
        }
        assertTrue("Native UI operation returned an error-only result", accepted)
        return response
    }
    private fun executionDiagnostics(web: WebView): JSONObject {
        val done = CountDownLatch(1); var raw = "null"
        ins.runOnMainSync {
            web.evaluateJavascript("JSON.stringify({documentReady:window.productReady===true,runPreparedAvailable:typeof window.runPreparedNative==='function',runtime:typeof window.__thorRuntimeDiagnostics==='function'?window.__thorRuntimeDiagnostics():null,dispatch:window.__thorScreenRecoveryDispatch||null})") {
                raw = it; done.countDown()
            }
        }
        if (!done.await(3, TimeUnit.SECONDS)) return obj("diagnosticsAvailable" to false, "code" to "JS_CALLBACK_TIMEOUT")
        return try { JSONObject(org.json.JSONTokener(raw).nextValue() as String) }
            catch (_: Exception) { obj("diagnosticsAvailable" to false, "code" to "JS_DIAGNOSTIC_UNAVAILABLE") }
    }
    private fun view(host: ProductHost): JSONObject {
        val result = CompletableFuture<JSONObject>()
        (field(host, "serial") as ExecutorService).execute {
            try { result.complete(host.view()) } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        return result.get(30, TimeUnit.SECONDS)
    }
    private fun until(message: String, seconds: Int = 45, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            Thread.sleep(40)
        }
        fail(message)
    }
    private fun preferences(): Map<String, String> = File(context.applicationInfo.dataDir, "shared_prefs")
        .listFiles().orEmpty().filter { it.name.endsWith(".xml") }.associate { it.name to SkillStore.digest(it.readBytes()) }

    /** Stream even large image/Pi records through bounded SQLite chunks. Only
     * digest equality is reported; existing user content never enters evidence. */
    private fun history(): Map<String, String> = SqliteStore(context, "product.db").use { store ->
        val db = store.readableDatabase
        listOf("sessions", "runs", "messages", "events", "attachments", "metadata", "pi_sessions", "pi_session_entries").associateWith { table ->
            val digest = MessageDigest.getInstance("SHA-256")
            val columns = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
            val rowIds = db.rawQuery("SELECT rowid FROM $table ORDER BY rowid", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
            }
            for (row in rowIds) for (column in columns) {
                val size = db.rawQuery("SELECT length(CAST($column AS BLOB)) FROM $table WHERE rowid=?", arrayOf(row.toString())).use { cursor ->
                    check(cursor.moveToFirst()); if (cursor.isNull(0)) -1L else cursor.getLong(0)
                }
                digest.update("$column:$size:".toByteArray())
                var offset = 1L
                while (offset <= size) {
                    val bytes = db.rawQuery("SELECT substr(CAST($column AS BLOB),?,65536) FROM $table WHERE rowid=?", arrayOf(offset.toString(), row.toString())).use { cursor ->
                        check(cursor.moveToFirst()); cursor.getBlob(0)
                    }
                    digest.update(bytes); offset += bytes.size
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    private fun otherAccessibilityServices(): Set<String> = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':')
        .filter(String::isNotBlank).filterNot { ComponentName.unflattenFromString(it)?.packageName == context.packageName }.toSet()
    private fun noExistingWork(): Boolean = SqliteStore(context, "product.db").use { store -> store.loadRecoveryRuns().isEmpty() }
    private data class RunResult(val run: JSONObject, val events: List<JSONObject>)
    private fun readRun(id: String): RunResult = SqliteStore(context, "product.db").use { store ->
        val run = requireNotNull(store.getRun(id))
        val events = store.readableDatabase.rawQuery("SELECT payload FROM events WHERE runId=? ORDER BY sequence", arrayOf(id)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) }
        }
        RunResult(run, events)
    }
    private fun rejectedBeforeDispatch(event: JSONObject): Boolean {
        val data = event.getJSONObject("data")
        return data.optString("toolId") == "input.tap" && data.optString("error") == "TARGET_CHANGED" &&
            data.optJSONObject("recovery")?.optString("submission") == "not_dispatched"
    }
    private fun acceptedFailure(event: JSONObject): Boolean {
        if (rejectedBeforeDispatch(event)) return true
        val data = event.getJSONObject("data")
        // Screen reads have no device effect, but this acceptance still
        // requires the fixed unambiguous Native no-dispatch receipt.
        return data.optString("toolId") in requiredTools - "input.tap" && data.optString("error") == "TARGET_CHANGED" &&
            data.optJSONObject("recovery")?.optString("submission") == "not_dispatched"
    }
    /** Fixed vocabulary only in the shareable report. Full diagnostic bodies
     * belong to the separate app-private file for this owned synthetic run. */
    private fun safeFailedTools(events: List<JSONObject>): JSONArray = JSONArray().apply {
        val codes = setOf("TARGET_CHANGED", "BACKEND_UNAVAILABLE", "TIMEOUT", "CANCELLED", "STALE_AUTHORIZATION",
            "INVALID_ARGUMENT", "LIMIT_EXCEEDED", "PROTOCOL_ERROR", "IO_ERROR", "PERMISSION_MISSING", "UNSUPPORTED", "PERSISTENCE_ERROR")
        val diagnostics = setOf("snapshot_missing", "snapshot_expired_or_display_changed", "input_layout_changed", "frame_missing",
            "frame_snapshot_mismatch", "input_clip_changed", "input_outside_clip", "expected_package_mismatch")
        for (event in events.filter { it.optString("type") == "tool_result" && !it.getJSONObject("data").isNull("error") }) {
            val data = event.getJSONObject("data"); val recovery = data.optJSONObject("recovery")
            val code = data.optString("error").takeIf { it in codes } ?: "UNKNOWN"
            val reason = when (recovery?.optString("reason")) { "input_focus_mismatch" -> "input_focus_mismatch"; else -> "not_reported" }
            put(obj("sequence" to event.optLong("sequence"), "toolId" to data.optString("toolId").takeIf { it in requiredTools }.orEmpty().ifEmpty { "other" },
                "code" to code, "reason" to reason,
                "diagnostic" to (data.optString("diagnostic").takeIf { it in diagnostics }
                    ?: recovery?.optString("diagnostic")?.takeIf { it in diagnostics } ?: "not_reported"),
                "detail" to when { reason == "input_focus_mismatch" -> "selected_target_did_not_own_input_focus"
                    code == "TARGET_CHANGED" -> "target_guard_rejected_action_observe_again"
                    code == "BACKEND_UNAVAILABLE" -> "backend_unavailable"
                    else -> "tool_did_not_succeed" },
                "outcome" to data.optString("outcome").takeIf { it in setOf("observed", "submitted", "not_submitted", "unknown") }.orEmpty().ifEmpty { "unknown" },
                "submission" to recovery?.optString("submission")?.takeIf { it in setOf("not_dispatched", "submitted", "unknown") }.orEmpty().ifEmpty { "unknown" },
                "contentDiscarded" to data.optBoolean("contentDiscarded"),
                "continueRun" to (recovery?.optBoolean("continueRun") ?: false),
                "retryPreviousAction" to (recovery?.optBoolean("retryPreviousAction") ?: false)))
        }
    }
    private fun saveOwnedDebug(sessionId: String, result: RunResult, phase: String) {
        val runId = result.run.getString("id")
        require(result.run.getString("sessionId") == sessionId)
        require(result.events.all { it.getString("sessionId") == sessionId && it.getString("runId") == runId })
        val answer = SqliteStore(context, "product.db").use { store ->
            store.readableDatabase.rawQuery("SELECT payload FROM messages WHERE sessionId=? AND runId=? ORDER BY rowid", arrayOf(sessionId, runId)).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val message = JSONObject(cursor.getString(0))
                        if (message.optString("role") == "assistant") add(message.optString("text"))
                    }
                }.joinToString("\n")
            }
        }
        // This file never includes original user chats, credentials, provider
        // configuration, image data or leases. The new run has no Pi history.
        File(context.filesDir, "physical-screen-recovery-debug.json").writeText(obj(
            "schemaVersion" to 1, "scope" to "owned independent Input Lab fixture only", "phase" to phase,
            "run" to result.run, "events" to JSONArray(result.events), "answer" to answer,
            "failedTools" to safeFailedTools(result.events)).toString(2))
    }
    private fun details(toolResult: JSONObject): JSONObject = toolResult.getJSONArray("content").objects()
        .first { it.optString("type") == "text" }.let { JSONObject(it.getString("text")) }
    private fun receiver(method: String, args: Bundle): JSONObject {
        val response = context.contentResolver.call(Uri.parse("content://dev.thor.inputlab.scenario"), method, null, args)
            ?: error("Independent scenario receiver is unavailable")
        return obj("lease" to response.getString("lease"), "snapshot" to JSONObject(requireNotNull(response.getString("snapshot"))))
    }
    private fun dispatch(ready: EmulatorRuntimeTestSupport.Ready, begun: JSONObject) {
        val done = CountDownLatch(1)
        ins.runOnMainSync {
            ready.executionWeb.evaluateJavascript("""
                window.__thorScreenRecoveryDispatch={phase:'dispatching',errorCode:'NONE',errorType:'NONE'};
                Promise.resolve().then(()=>window.runPreparedNative(JSON.parse(${JSONObject.quote(begun.toString())}))).then(()=>{
                    window.__thorScreenRecoveryDispatch.phase='resolved';
                },error=>{
                    const codes=['BACKEND_UNAVAILABLE','STALE_AUTHORIZATION','CANCELLED','TIMEOUT','INVALID_ARGUMENT','PROTOCOL_ERROR','IO_ERROR','PERSISTENCE_ERROR','LIMIT_EXCEEDED'];
                    const types=['RuntimeError','Error','TypeError','ReferenceError','SyntaxError','RangeError','AbortError'];
                    window.__thorScreenRecoveryDispatch={phase:'rejected',errorCode:codes.includes(error?.code)?error.code:'UNKNOWN',errorType:types.includes(error?.name)?error.name:'OtherError'};
                });true
            """.trimIndent()) { done.countDown() }
        }
        assertTrue("Execution document did not acknowledge dispatch", done.await(5, TimeUnit.SECONDS))
    }
    private fun independentJournal(automation: UiAutomation, snapshot: JSONObject): List<JSONObject> {
        val attempt = snapshot.getString("attemptId")
        require(UUID.fromString(attempt).toString() == attempt)
        val evidence = snapshot.getJSONObject("evidence")
        val relative = "scenario-reports/$attempt/events.jsonl"
        assertEquals(relative, evidence.getString("relativePath"))
        val raw = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("run-as dev.thor.inputlab cat files/$relative")).use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                assertTrue("Independent fixture journal exceeds its collection budget", output.size() + count <= 8 * 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        assertEquals("Sealed journal must have no uncollected tail", raw.size.toLong(), evidence.getLong("eventPrefixBytes"))
        assertEquals(evidence.getString("eventPrefixSha256"), SkillStore.digest(raw))
        val rows = raw.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).map { JSONObject(it) }.toList()
        assertEquals(evidence.getLong("eventCount"), rows.size.toLong())
        for ((index, row) in rows.withIndex()) {
            assertEquals(index + 1L, row.getLong("sequence"))
            for (key in listOf("attemptId", "testRunId", "agentRunId", "evidenceMode")) assertEquals(snapshot.getString(key), row.getString(key))
        }
        return rows
    }

    @Test fun automaticallyRecoveredServicePublishesAndExecutesRealScreenTools() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit connected real-provider acceptance required", args.getString("physicalAcceptance") == "true")
        assertEquals("AYN Thor", Build.MODEL)
        assertTrue("Never interrupt existing user work", noExistingWork())
        val upper = requireNotNull(args.getString("labDisplay")).toInt()
        val lower = requireNotNull(args.getString("chatDisplay")).toInt()
        assertNotEquals(upper, lower)
        val providerStore = ProviderStore(context)
        val originalProvider = providerStore.defaultId()
        val originalPrefs = preferences(); val originalHistory = history(); val otherServices = otherAccessibilityServices()
        val report = obj("schemaVersion" to 1, "scope" to "physical automatic accessibility recovery and real Pi screen tools",
            "deviceKind" to "physical", "fixtureOnly" to true, "modelTimeoutMs" to 0, "upperDisplay" to upper, "chatDisplay" to lower,
            "phase" to "startup", "passed" to false, "cleanupErrors" to 0, "rejectedNotDispatchedCount" to 0)
        var activity: MainActivity? = null; var host: ProductHost? = null; var connection: String? = null
        var executionWeb: WebView? = null
        var selected: String? = null; var own: String? = null; var lease: JSONObject? = null
        var receiverArgs: Bundle? = null; var sealed = false; var primary: Throwable? = null
        val recoverySamples = ArrayDeque<JSONObject>()
        val executionSamples = ArrayDeque<JSONObject>()
        fun save() {
            report.put("nativeOperationFailures", JSONArray(nativeFailures))
            host?.let { h ->
                runCatching {
                    val status = h.accessibilitySetupStatus()
                    val diagnostic = h.accessibilityRecoveryDiagnostics()
                    report.put("accessibilitySetupStatus", status).put("accessibilityRecoveryDiagnostics", diagnostic)
                    recoverySamples.addLast(obj("phase" to report.optString("phase"), "elapsedRealtimeMs" to SystemClock.elapsedRealtime(),
                        "accessibilitySetupStatus" to status, "accessibilityRecoveryDiagnostics" to diagnostic))
                    while (recoverySamples.size > 120) recoverySamples.removeFirst()
                    report.put("accessibilitySamples", JSONArray(recoverySamples.toList()))
                }.onFailure { report.put("accessibilityDiagnosticFailureType", it.javaClass.simpleName) }
            }
            if (activity?.isFinishing == false) executionWeb?.let { web ->
                val diagnostic = executionDiagnostics(web)
                report.put("executionDiagnostics", diagnostic)
                host?.let { h -> report.put("nativeConnectionMatches", connection != null && runCatching { field(h, "connection") == connection }.getOrDefault(false)) }
                executionSamples.addLast(obj("phase" to report.optString("phase"), "elapsedRealtimeMs" to SystemClock.elapsedRealtime(), "diagnostic" to diagnostic))
                while (executionSamples.size > 120) executionSamples.removeFirst()
                report.put("executionSamples", JSONArray(executionSamples.toList()))
            }
            File(context.filesDir, "physical-screen-recovery-runtime.json").writeText(report.toString(2))
        }
        File(context.filesDir, "physical-screen-recovery-debug.json").delete()
        save()
        try {
            val automation = ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            activity = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(lower).toBundle()) as MainActivity
            ins.runOnMainSync { activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val ready = EmulatorRuntimeTestSupport.awaitReady(activity!!); val h = ready.host; host = h
            executionWeb = ready.executionWeb
            assertFalse("Never replace active user work", h.hasActiveRuns())
            val c = field(h, "connection") as String; connection = c; selected = h.selectedSession()
            val snapshots = field(h, "snapshots") as Snapshots
            assertEquals("Normal startup must resolve the upper panel without test calibration", upper, snapshots.selected().id)

            // Exercise the real lifecycle failure, not a mocked availability bit.
            // Recovery is exclusively the existing Shizuku health monitor: this
            // test never enables Settings or reconnects the Shizuku backend.
            var recoveryHeartbeat = 0L
            until("Accessibility did not initially connect") {
                val now = SystemClock.elapsedRealtime()
                if (now - recoveryHeartbeat >= 1000) { recoveryHeartbeat = now; save() }
                ThorAccessibilityService.instance != null
            }
            val previous = requireNotNull(ThorAccessibilityService.instance)
            // Let the independent 1 Hz monitor record an initially healthy
            // connection, so this exercises its disconnect/reconnect path.
            Thread.sleep(1100)
            report.put("phase", "automatic-recovery").put("initiallyConnected", true); save()
            ins.runOnMainSync { previous.disableSelf() }
            var disconnectedObserved = false
            until("Authorized health recovery did not reconnect accessibility", 60) {
                val now = SystemClock.elapsedRealtime()
                if (now - recoveryHeartbeat >= 1000) { recoveryHeartbeat = now; save() }
                val current = ThorAccessibilityService.instance
                if (current == null) disconnectedObserved = true
                current != null && current !== previous
            }
            assertNotSame("A new actual Android service instance must bind", previous, ThorAccessibilityService.instance)
            assertEquals("Recovery must preserve every other enabled accessibility service", otherServices, otherAccessibilityServices())
            assertFalse("Recovery may not create user work", h.hasActiveRuns())
            report.put("automaticRecovery", true).put("actualInstanceReplaced", true)
                .put("disconnectedStateObserved", disconnectedObserved).put("otherAccessibilityServicesPreserved", true); save()

            rpc(h, c, obj("op" to "new")); val sid = h.selectedSession(); own = sid
            rpc(h, c, obj("op" to "sessionOptions", "sessionId" to sid, "selectedSkills" to JSONArray()))
            for ((cap, enabled) in mapOf("perception" to true, "screen_control" to true, "search" to false, "debug" to false))
                rpc(h, c, obj("op" to "capability", "sessionId" to sid, "capability" to cap, "enabled" to enabled))
            val selection = view(h).getJSONObject("modelSelection")
            assertTrue("A saved real provider must be configured", selection.getBoolean("configured"))
            assertTrue("Screen acceptance needs an image-capable saved model", selection.getJSONArray("input").let { input ->
                (0 until input.length()).any { input.getString(it) == "image" }
            })
            val efforts = selection.getJSONArray("efforts").let { rows -> (0 until rows.length()).map { rows.getString(it) } }
            rpc(h, c, obj("op" to "sessionOptions", "sessionId" to sid, "thinkingEffort" to when {
                "off" in efforts -> "off"; "low" in efforts -> "low"; else -> efforts.first()
            }))
            context.startActivity(Intent().setComponent(ComponentName("dev.thor.inputlab", "dev.thor.inputlab.ScenarioActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                ActivityOptions.makeBasic().setLaunchDisplayId(upper).toBundle())
            Thread.sleep(700)
            val prompt = "这是独立上屏演练，只操作当前 Input Lab 页面。请依次调用 perception_app_info 获取上屏应用信息、perception_capture_screen 获取完整截图（maxEdge=1280，format=jpeg）、perception_ui_tree 读取当前可见组件树。必须根据实际观察找到‘设备偏好’按钮，不猜测坐标。使用 input_tap 点击它，coordinateSpace=display_logical，expectedPackage=dev.thor.inputlab，目标是只成功点击一次。如果明确收到 TARGET_CHANGED 且 recovery.submission=not_dispatched，说明这次动作未派发，可以继续本任务：必须重新读取完整截图和当前组件树，丢弃旧坐标、frameId 和 nodeRef，根据新观察重新决定新的动作，不能直接重放旧动作。contentDiscarded只表示结果正文被弃置，与动作是否执行无关；即使contentDiscarded=true，只要明确not_dispatched，仍按上述流程重新观察并决定新动作。任何已提交（submitted）或提交状态未知（unknown）的点击都禁止重发。不要使用 input_activate，不点击其他地方，不修改任何设置。成功点击后重新读取组件树，确认出现‘选择模式’和‘夜间阅读’，再简短报告应用与页面。只允许上述观察工具和仅一次成功点击，不读 Skill、不用 shell、不启动其他应用。"
            val begun = h.prepareForRunner(prompt).get(30, TimeUnit.SECONDS)
            assertTrue("The saved provider must begin a new owned task", begun.has("lease"))
            val binding = begun.getJSONObject("lease"); lease = binding; val runId = binding.getString("runId")
            assertEquals(sid, binding.getString("sessionId"))
            assertTrue("Owned task must not import unrelated user history", begun.isNull("piSession"))
            assertEquals(0, begun.getInt("runTimeoutMs")); assertEquals(0, begun.getJSONObject("network").getInt("timeoutMs"))
            val testRun = "screen-recovery-${UUID.randomUUID()}"
            val setup = receiver("prepare", Bundle().apply {
                putInt("version", 1); putString("scene", "settings"); putLong("seed", UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)
                putString("testRunId", testRun); putString("agentRunId", runId); putString("senderTraceRef", "live-product:$runId:screen-recovery")
                putString("evidenceMode", "agent_e2e")
            })
            receiverArgs = Bundle().apply { putInt("version", 1); putString("testRunId", testRun); putString("lease", setup.getString("lease")) }
            val initial = setup.getJSONObject("snapshot")
            assertEquals(upper, initial.getJSONObject("geometry").getInt("displayId"))
            assertEquals("home", initial.getString("page")); assertEquals(0, initial.getJSONObject("state").getInt("settingNavigationCount"))
            Thread.sleep(700)
            report.put("phase", "agent-running").put("fixturePreparedWithoutInput", true); save()
            assertSame("The prepared task must use the currently attached execution document", ready.executionWeb, EmulatorRuntimeTestSupport.executionWeb(activity!!))
            assertTrue("Native execution connection must stay the same through fixture setup", c == field(h, "connection"))
            val started = SystemClock.elapsedRealtime(); dispatch(ready, begun)
            var heartbeat = 0L
            fun active() = view(h).getJSONArray("activeRuns").objects().any { it.getString("id") == runId }
            // Intentionally no generation deadline. Heartbeats are observation,
            // never a reason to stop, restart or replace the model's context.
            fun awaitFinished(): RunResult {
                while (true) {
                    val current = readRun(runId)
                    if (current.run.optString("status") in terminal && !active()) {
                        // Runtime commits its terminal status before Native end
                        // releases the binding and persists HTTP receipts. The
                        // serialized view above confirms end has completed;
                        // re-read instead of returning that older DB snapshot.
                        return readRun(runId)
                    }
                    if (SystemClock.elapsedRealtime() - heartbeat >= 5000) {
                        heartbeat = SystemClock.elapsedRealtime()
                        report.put("runningElapsedMs", heartbeat - started).put("lastStatus", current.run.optString("status"))
                            .put("toolResultsObserved", current.events.count { it.optString("type") == "tool_result" }); save()
                    }
                    Thread.sleep(100)
                }
            }
            val result = awaitFinished()
            // Persist before assertions and cleanup: a failed guard must remain
            // diagnosable after the owned chat is deleted.
            saveOwnedDebug(sid, result, "model-finished")
            report.put("failedTools", safeFailedTools(result.events)).put("rejectedNotDispatchedCount",
                result.events.count { it.optString("type") == "tool_result" && rejectedBeforeDispatch(it) }); save()
            val final = receiver("release", receiverArgs!!).getJSONObject("snapshot"); sealed = true
            val journal = independentJournal(automation, final)
            val tools = result.events.filter { it.optString("type") == "tool_result" }
            val successes = tools.filter { it.getJSONObject("data").isNull("error") }
            val failures = tools.filter { !it.getJSONObject("data").isNull("error") }
            val rejected = failures.filter(::rejectedBeforeDispatch)
            val published = result.run.getJSONArray("contexts").objects().map { context -> context.getJSONArray("effectiveTools").let { rows ->
                (0 until rows.length()).map { rows.getString(it) }.toSet()
            } }
            val transport = result.run.optJSONArray("modelTransport")?.objects().orEmpty()
            report.put("runStatus", result.run.optString("status")).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("effectiveTools", JSONArray(published.flatten().toSet().sorted()))
                .put("successfulToolCounts", JSONObject(successes.groupingBy { it.getJSONObject("data").getString("toolId") }.eachCount()))
                .put("toolErrorCodes", JSONArray(tools.mapNotNull { it.getJSONObject("data").takeUnless { data -> data.isNull("error") }?.optString("error") }))
                .put("rejectedNotDispatchedCount", rejected.size)
                .put("readNotDispatchedCount", failures.count { !rejectedBeforeDispatch(it) && acceptedFailure(it) })
                .put("httpReceiptsRecorded", result.run.opt("modelTransport") is JSONArray)
                .put("httpStatuses", JSONArray(transport.map { it.optInt("status") }))
                .put("receiverPage", final.getString("page")).put("receiverNavigationCount", final.getJSONObject("state").getInt("settingNavigationCount"))
                .put("receiverAttemptId", final.getString("attemptId")).put("receiverEvidence", final.getJSONObject("evidence"))
                .put("receiverLifecycle", JSONArray(journal.filter { it.optString("eventKind") in setOf("scene_ready", "configuration_changed", "window_focus", "navigation") }
                    .map { obj("sequence" to it.getLong("sequence"), "eventKind" to it.getString("eventKind"), "page" to it.getString("page"), "data" to it.getJSONObject("data")) })); save()
            assertEquals("Real Agent task must complete", "completed", result.run.getString("status"))
            assertTrue("Actual request contexts must publish every screen tool", published.isNotEmpty() && published.all { it.containsAll(requiredTools) })
            assertTrue("Native end must persist the canonical HTTP receipt array", result.run.opt("modelTransport") is JSONArray)
            assertTrue("Real provider HTTP receipts are required", transport.isNotEmpty() && transport.all { it.optInt("status") == 200 })
            assertTrue("Only explicitly requested screen tools may run", tools.all { it.getJSONObject("data").getString("toolId") in requiredTools })
            assertTrue("Only explicit TARGET_CHANGED refusals before dispatch from requested screen tools may recover; every other failure must fail acceptance", failures.all(::acceptedFailure))
            for (tool in requiredTools) assertTrue("Missing successful real tool: $tool", successes.any { it.getJSONObject("data").optString("toolId") == tool })
            val app = details(successes.first { it.getJSONObject("data").getString("toolId") == "perception.app_info" }.getJSONObject("data"))
            assertEquals("dev.thor.inputlab", app.getString("package"))
            assertEquals("dev.thor.inputlab", app.getString("observedTargetPackage"))
            val tap = successes.single { it.getJSONObject("data").getString("toolId") == "input.tap" }
            val tapData = tap.getJSONObject("data"); val tapDetails = details(tapData)
            assertEquals("submitted", tapData.getString("outcome")); assertTrue(tapDetails.getBoolean("submitted"))
            assertEquals("completed", tapDetails.getString("systemCallback")); assertEquals(upper, tapDetails.getInt("displayId"))
            assertEquals("Only one input may succeed", 1, successes.count { it.getJSONObject("data").getString("toolId").startsWith("input.") })
            val inputResults = tools.filter { it.getJSONObject("data").getString("toolId").startsWith("input.") }
            assertEquals("Other input attempts must all be proven not dispatched", 1 + rejected.size, inputResults.size)
            for (refusal in rejected) {
                val refusedAt = refusal.getLong("sequence")
                assertTrue("No input may be attempted after the one successful click", refusedAt < tap.getLong("sequence"))
                val nextInput = inputResults.first { it.getLong("sequence") > refusedAt }.getLong("sequence")
                for (read in listOf("perception.capture_screen", "perception.ui_tree")) assertTrue("A rejected action needs fresh $read before a newly decided action", successes.any {
                    it.getJSONObject("data").getString("toolId") == read && it.getLong("sequence") > refusedAt && it.getLong("sequence") < nextInput
                })
            }
            for (tool in requiredTools - "input.tap") assertTrue("$tool must observe before the click", successes.any {
                it.getJSONObject("data").getString("toolId") == tool && it.getLong("sequence") < tap.getLong("sequence")
            })
            val captures = successes.filter { it.getJSONObject("data").getString("toolId") == "perception.capture_screen" }
            for (capture in captures) {
                val body = capture.getJSONObject("data"); val image = details(body)
                assertEquals(upper, image.getInt("displayId")); assertEquals("dev.thor.inputlab", image.getString("observedTargetPackage"))
                assertTrue(image.getInt("width") > 0 && image.getInt("height") > 0)
                val attachments = body.getJSONArray("content").objects().filter { it.optString("type") == "attachment" }
                assertTrue("Captured pixels must enter the actual Pi tool result", attachments.isNotEmpty())
                SqliteStore(context, "product.db").use { store ->
                    for (attachment in attachments) {
                        // The production scoped reader uses bounded substr()
                        // chunks and JSONObject; Thor SQLite has no JSON1.
                        val stored = requireNotNull(store.getAttachment(attachment.getString("attachmentId"), sid)) {
                            "Captured pixels must be durably owned by this run"
                        }
                        assertEquals(sid, stored.getString("sessionId")); assertEquals(runId, stored.getString("runId"))
                        assertEquals("image/jpeg", stored.getString("mimeType")); assertTrue(stored.getString("data").isNotEmpty())
                    }
                }
            }
            assertTrue("UI tree must verify the destination after the actual click", successes.any {
                it.getJSONObject("data").getString("toolId") == "perception.ui_tree" && it.getLong("sequence") > tap.getLong("sequence") &&
                    it.getJSONObject("data").getJSONArray("content").toString().let { tree -> tree.contains("夜间阅读") && tree.contains("选择模式") }
            })
            assertEquals("preferences", final.getString("page")); assertEquals(1, final.getJSONObject("state").getInt("settingNavigationCount"))
            assertFalse("Acceptance must never save or mutate settings", final.getJSONObject("state").getBoolean("settingsSaved"))
            assertEquals(0, final.getJSONObject("state").getInt("settingsSaveCount"))
            val evidence = final.getJSONObject("evidence")
            assertTrue(evidence.getBoolean("sealed")); assertFalse(evidence.getBoolean("incomplete")); assertEquals(0L, evidence.getLong("droppedSamples"))
            assertEquals(1, journal.count { it.optString("eventKind") == "click" && it.getJSONObject("data").optString("viewId") == "scenario_preferences" })
            assertTrue("Independent receiver must observe Android input dispatch", journal.any { it.optString("recordOrigin") == "android_dispatch" && it.optString("eventKind") == "touch" })
            val touches = journal.filter { it.optString("recordOrigin") == "android_dispatch" && it.optString("eventKind") == "touch" }
            assertEquals("Refused actions must not dispatch a second touch down", 1, touches.count { it.getJSONObject("data").optInt("actionMasked", -1) == android.view.MotionEvent.ACTION_DOWN })
            assertEquals("Exactly one physical touch release must reach the fixture", 1, touches.count { it.getJSONObject("data").optInt("actionMasked", -1) == android.view.MotionEvent.ACTION_UP })
            assertFalse("The single successful click must not leave a cancelled touch", touches.any { it.getJSONObject("data").optInt("actionMasked", -1) == android.view.MotionEvent.ACTION_CANCEL })
            report.put("actualDeclaredToolsVerified", true).put("screenshotAttachmentVerified", true).put("submittedClickReceiptVerified", true)
                .put("independentAndroidDispatchVerified", true).put("receiverJournalSha256Verified", true).put("passed", true).put("phase", "cleanup")
        } catch (error: Throwable) {
            primary = error; report.put("failureType", error.javaClass.simpleName); save(); throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(block: () -> Unit) { try { block() } catch (error: Throwable) {
                report.put("cleanupErrors", report.getInt("cleanupErrors") + 1).put("passed", false)
                if (primary != null) primary!!.addSuppressed(error) else if (cleanupFailure == null) cleanupFailure = error
            } }
            cleanup { save() }
            cleanup {
                val h = host; val b = lease
                if (h != null && b != null) {
                    val rid = b.getString("runId")
                    if (readRun(rid).run.optString("status") !in terminal) {
                        h.stopFromChat(runId = rid)
                        val end = SystemClock.elapsedRealtime() + 15_000
                        while (readRun(rid).run.optString("status") !in terminal && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
                        if (readRun(rid).run.optString("status") !in terminal && connection != null)
                            h.rpc(connection!!, obj("op" to "end", "data" to b)).get(30, TimeUnit.SECONDS)
                    }
                }
            }
            cleanup {
                val b = lease; val sid = own
                if (b != null && sid != null) {
                    val result = readRun(b.getString("runId"))
                    saveOwnedDebug(sid, result, "before-owned-cleanup")
                    report.put("failedTools", safeFailedTools(result.events)).put("rejectedNotDispatchedCount",
                        result.events.count { it.optString("type") == "tool_result" && rejectedBeforeDispatch(it) })
                }
            }
            cleanup { if (!sealed && receiverArgs != null) receiver("release", receiverArgs!!) }
            cleanup { if (host != null && connection != null && selected != null) rpc(host!!, connection!!, obj("op" to "select", "sessionId" to selected)) }
            cleanup { if (host != null && connection != null && own != null) rpc(host!!, connection!!, obj("op" to "delete", "sessionId" to own)) }
            cleanup { assertEquals(originalProvider, providerStore.defaultId()); assertEquals(originalPrefs, preferences()); report.put("originalConfigurationPreserved", true) }
            cleanup { assertEquals(originalHistory, history()); report.put("originalUserHistoryAndPiEntriesPreserved", true) }
            cleanup { assertEquals(otherServices, otherAccessibilityServices()); report.put("otherAccessibilityServicesPreserved", true) }
            cleanup { activity?.let { value -> ins.runOnMainSync { value.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); value.finish() } } }
            save(); if (primary == null) cleanupFailure?.let { throw it }
        }
    }
}
