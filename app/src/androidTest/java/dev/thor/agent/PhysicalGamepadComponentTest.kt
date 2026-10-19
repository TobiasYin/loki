package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.*
import dev.thor.tools.diagnostic.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Additive native-component acceptance. Uses saved Shizuku permission, never a model/profile fixture.
 * Receiver attribution is manual_platform_api, not agent_e2e or a product-authority claim. */
@RunWith(AndroidJUnit4::class)
class PhysicalGamepadComponentTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun until(seconds: Int=30, condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+seconds*1000L
        while(SystemClock.elapsedRealtime()<end) {if(condition())return;Thread.sleep(50)}
        fail("Physical gamepad component condition timed out")
    }
    private fun receiver(method: String, args: Bundle): JSONObject {
        val response=context.contentResolver.call(Uri.parse("content://dev.thor.inputlab.runner"),method,null,args) ?: error("Input Lab unavailable")
        return obj("lease" to response.getString("lease"),"snapshot" to JSONObject(response.getString("snapshot")!!))
    }
    private fun rawEvents(automation: UiAutomation, attempt: String): ByteArray {
        require(java.util.UUID.fromString(attempt).toString()==attempt)
        // Fixed diagnostic read only. No input, settings write, fixture reset, or shell script.
        val descriptor=automation.executeShellCommand("run-as dev.thor.inputlab cat files/reports/$attempt/events.jsonl")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {input->
            val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {val count=input.read(buffer);if(count<0)break;assertTrue("Receiver raw log exceeds test budget",output.size()+count<=8*1024*1024);output.write(buffer,0,count)}
            output.toByteArray()
        }
    }
    private fun parseFocusDump(text: String,upper: Int): JSONObject {
        val lines=text.lines()
        val focusedDisplay=Regex("(?m)^[ \\t]*FocusedDisplayId:[ \\t]*(-?[0-9]+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val header=lines.indexOfFirst {it.trimStart().startsWith("FocusedWindows:") || it.trimStart().startsWith("FocusedWindow:")}
        val window=if(header<0)null else {
            val heading=lines[header].trim()
            if(heading.startsWith("FocusedWindow:") && heading.contains("name="))heading
            else lines.drop(header+1).takeWhile {it.isBlank() || it.trimStart().startsWith("displayId=")}
                .firstOrNull {Regex("\\bdisplayId=$focusedDisplay(?:,|[ \\t])").containsMatchIn(it)}?.trim()
        }
        return obj("observedUptimeMs" to SystemClock.uptimeMillis(),"focusedDisplayId" to focusedDisplay,
            "focusedWindow" to window,"matchesUpperLab" to (focusedDisplay==upper && window?.contains("dev.thor.inputlab/")==true))
    }
    private fun focusDump(automation: UiAutomation,upper: Int): JSONObject {
        // Read only the actual dispatcher focus. Per-display FocusedApplications
        // are not proof that the physical controller currently targets that display.
        val descriptor=automation.executeShellCommand("dumpsys -t 2 input")
        val output=ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {Budgets.read(it,Budgets.CONFIG)}
        assertFalse("Input focus dump exceeds test budget",output.truncated)
        return parseFocusDump(output.bytes.toString(Charsets.UTF_8),upper)
    }
    @Test fun focusParserRejectsAnotherDisplayOrApplicationOnlyEvidence() {
        val sample="""Input Dispatcher State:
          FocusedDisplayId: 0
          FocusedApplications:
            displayId=0, name='ActivityRecord dev.thor.inputlab/.LabActivity'
          FocusedWindows:
            displayId=0, name='window dev.thor.inputlab/dev.thor.inputlab.LabActivity'
            displayId=4, name='window com.tobias.loki/dev.thor.agent.MainActivity'
          TouchStatesByDisplay:
          FocusedDisplayId: 4
        """.trimIndent()
        assertTrue(parseFocusDump(sample,0).getBoolean("matchesUpperLab"))
        assertFalse(parseFocusDump(sample.replaceFirst("FocusedDisplayId: 0","FocusedDisplayId: 4"),0).getBoolean("matchesUpperLab"))
        assertFalse(parseFocusDump(sample.replace("name='window dev.thor.inputlab/dev.thor.inputlab.LabActivity'","name='window com.tobias.loki/dev.thor.agent.MainActivity'"),0).getBoolean("matchesUpperLab"))
        assertFalse(parseFocusDump(sample.substringBefore("  FocusedWindows:"),0).getBoolean("matchesUpperLab"))
    }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private data class Case(val name: String,val tool: String,val values: Map<String,Any?>,val cancel: Boolean=false)
    private fun toolResult(result: ToolResult)=obj("error" to result.error?.name,"message" to result.message,"outcome" to result.outcome,
        "backend" to result.source,"availability" to result.availability.status.name,"data" to json(result.data),"observedAtElapsedRealtimeMs" to result.observedAt)

    @Test fun realControllerButtonsAxesAndCancellationReachIndependentReceiver() {
        val arguments=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit connected-device acceptance required",arguments.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val upper=arguments.getString("labDisplay")!!.toInt();val lower=arguments.getString("chatDisplay")!!.toInt()
        assertNotEquals(upper,lower)
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun launchLab() {
            context.startActivity(Intent().setComponent(ComponentName("dev.thor.inputlab","dev.thor.inputlab.LabActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),ActivityOptions.makeBasic().setLaunchDisplayId(upper).toBundle())
            Thread.sleep(400)
        }
        launchLab()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(lower).toBundle()) as MainActivity
        instrumentation.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val backend=ProductHost::class.java.getDeclaredField("backend").apply {isAccessible=true}.get(host) as AndroidBackend
        val snapshots=ProductHost::class.java.getDeclaredField("snapshots").apply {isAccessible=true}.get(host) as Snapshots
        val debug=ProductHost::class.java.getDeclaredField("debug").apply {isAccessible=true}.get(host) as DebugConnection
        val clock=Clock {SystemClock.elapsedRealtime()}
        val executor=Executors.newSingleThreadExecutor()
        val evidence=obj("schemaVersion" to 1,"authority" to "ManualTestAuthority: native component only; no cloud model or product-authority Run",
            "evidenceMode" to "manual_platform_api","agentE2E" to false,"upperDisplay" to upper,"chatDisplay" to lower,
            "clockIds" to obj("senderInputAndStop" to "android_uptime_ms","toolObservedAt" to "android_elapsed_realtime_ms"),"cases" to JSONArray(),"passed" to false)
        val failures=mutableListOf<String>()
        try {
            until(40) {ThorAccessibilityService.instance!=null && host.accessibilitySetupStatus().contains("已通过 Shizuku")}
            assertFalse("Do not run component input alongside an active product task",host.hasActiveRuns())
            // Deliberately no snapshots.calibrate / host.calibrate: normal startup must discover panel roles.
            until {runCatching {snapshots.selected().id==upper}.getOrDefault(false)}
            until {debug.availability().status==Status.READY}
            evidence.put("controller",json(debug.gamepadInfo()))
            val axes=mapOf<String,Any?>("X" to 1,"Y" to -1,"Z" to 1,"RZ" to -1,"BRAKE" to 1,"GAS" to 1)
            val cases=listOf(
                Case("button","input.press_button",mapOf("button" to "A","phase" to "hold","durationMs" to 200)),
                Case("axes","input.set_axes",mapOf("axes" to axes,"durationMs" to 300)),
                Case("button-cancel","input.press_button",mapOf("button" to "A","phase" to "hold","durationMs" to 3000),true),
                Case("axes-cancel","input.set_axes",mapOf("axes" to axes,"durationMs" to 3000),true))
            for(case in cases) {
                val record=obj("name" to case.name,"toolId" to case.tool,"arguments" to json(case.values),"cancelAfterSubmissionMs" to if(case.cancel)100 else null,"passed" to false)
                evidence.getJSONArray("cases").put(record)
                var receiverArgs: Bundle?=null
                val authority=ManualTestAuthority(clock).apply {set(Capability.SCREEN_CONTROL,true)}
                var runId: String?=null
                var call: java.util.concurrent.Future<ToolResult>?=null
                try {
                    val focusHistory=JSONArray();record.put("focusDiagnostics",focusHistory)
                    var launches=1;launchLab();var lastLaunch=SystemClock.uptimeMillis()
                    var stableSince=0L;var stableWindow=""
                    until(12) {
                        val focus=focusDump(automation,upper);focus.put("phase","before-arm").put("launchCount",launches);focusHistory.put(focus)
                        val now=SystemClock.uptimeMillis();val signature=focus.optString("focusedWindow","")
                        if(focus.getBoolean("matchesUpperLab")) {
                            if(stableSince==0L || signature!=stableWindow){stableSince=now;stableWindow=signature}
                            now-stableSince>=500
                        } else {
                            stableSince=0L;stableWindow=""
                            // A normal foreground launch is allowed only before ARM.
                            // Never change focus settings or force a task/display through shell.
                            if(now-lastLaunch>=1500 && launches<3){launchLab();launches++;lastLaunch=SystemClock.uptimeMillis()}
                            false
                        }.also {if(!it)Thread.sleep(150)}
                    }
                    record.put("focusVerifiedBeforeArm",true).put("labForegroundLaunchCount",launches)
                    val availability=backend.availability(case.tool)
                    record.put("availability",obj("status" to availability.status.name,"reason" to availability.reason,"backend" to availability.backend))
                    val lease=authority.begin(backend);runId=lease.run
                    record.put("manualRunId",runId)
                    val testRun="gamepad-component-${id()}"
                    val setup=receiver("prepare",Bundle().apply {
                        putInt("version",1);putInt("observationSeconds",10);putString("evidenceMode","manual_platform_api")
                        putString("testRunId",testRun);putString("agentRunId","no-agent:${lease.run}")
                        putString("caseId",if(case.tool=="input.press_button")"LAB-06" else "LAB-07")
                        putString("senderTraceRef","native-component/ManualTestAuthority:${lease.run}:${case.name}; no model or product-authority Run")
                    })
                    receiverArgs=Bundle().apply {putInt("version",1);putString("testRunId",testRun);putString("lease",setup.getString("lease"))}
                    var state=setup.getJSONObject("snapshot")
                    until(3) {state=receiver("report",receiverArgs!!).getJSONObject("snapshot");state.optString("testRunId")==testRun && state.optString("phase") in setOf("ARMED","RECORDING")}
                    assertEquals(upper,state.getJSONObject("geometry").getInt("actualWindowDisplayId"))
                    assertEquals("Native gamepad backend unavailable: ${availability.reason}",Status.READY,availability.status)
                    // ARM creates UI feedback after its 500 ms receiver delay. Match the model
                    // harness settle period, then require a stable strict target before preparation.
                    // RECORDING begins only after input arrives, so waiting for it here would deadlock.
                    Thread.sleep(700)
                    var stable: Snapshot?=null
                    until(3) {
                        val candidate=snapshots.capture()
                        Thread.sleep(200)
                        runCatching {snapshots.validate(candidate.id);Thread.sleep(100);snapshots.validate(candidate.id);stable=candidate;true}.getOrDefault(false)
                    }
                    val snapshot=stable ?: error("Receiver target never stabilized")
                    record.put("stableSnapshotId",snapshot.id).put("stableTargetRevision",snapshot.target.revision)
                    val beforeSubmit=focusDump(automation,upper).put("phase","after-arm-before-submit")
                    focusHistory.put(beforeSubmit)
                    assertTrue("Physical controller focus changed after ARM; do not dispatch",beforeSubmit.getBoolean("matchesUpperLab"))
                    assertEquals("dev.thor.inputlab",snapshot.target.packageName)
                    val submitted=CountDownLatch(1)
                    val port=object: AuthorizationPort {
                        override fun validate(envelope: Envelope)=authority.validate(envelope)
                        override fun claim(e: Envelope)=authority.claim(e)
                        override fun commit(envelope: Envelope,effect:()->Unit) {authority.commit(envelope) {effect();submitted.countDown()}}
                    }
                    val gate=Gateway(port,backend,snapshots,clock)
                    val envelope=Envelope(lease.session,lease.run,id(),case.tool,lease.epoch,lease.deadline,snapshot.id)
                    record.put("callStartedUptimeMs",SystemClock.uptimeMillis())
                    call=executor.submit<ToolResult> {gate.execute(envelope,case.values)}
                    if(case.cancel) {
                        val began=submitted.await(3,TimeUnit.SECONDS)
                        if(!began) {
                            if(call!!.isDone)record.put("sender",toolResult(call!!.get()))
                            fail("No acknowledged native submission to cancel")
                        }
                        record.put("submissionAcknowledgedUptimeMs",SystemClock.uptimeMillis())
                        Thread.sleep(100)
                        record.put("cancelRequestedUptimeMs",SystemClock.uptimeMillis())
                        authority.stop()
                        record.put("releaseReply",backend.release(lease.run)).put("cleanupAcknowledgedUptimeMs",SystemClock.uptimeMillis())
                    }
                    val result=call!!.get(7,TimeUnit.SECONDS)
                    record.put("callFinishedUptimeMs",SystemClock.uptimeMillis()).put("sender",toolResult(result))
                    until(13) {state=receiver("report",receiverArgs!!).getJSONObject("snapshot");!state.isNull("report")}
                    val report=state.getJSONObject("report")
                    File(context.filesDir,"physical-gamepad-${case.name}-report.json").writeText(report.toString(2))
                    record.put("attemptId",report.getJSONObject("metadata").getString("attemptId")).put("receiverStatus",report.getString("status"))
                    Thread.sleep(400) // Include late raw records; never manufacture input to complete receiver state.
                    val raw=rawEvents(automation,record.getString("attemptId"))
                    File(context.filesDir,"physical-gamepad-${case.name}-events.jsonl").writeBytes(raw)
                    val prefixBytes=report.getLong("eventPrefixBytes")
                    assertTrue("Invalid receiver prefix length",prefixBytes in 1..raw.size.toLong())
                    val prefix=raw.copyOf(prefixBytes.toInt());assertEquals(report.getString("eventPrefixSha256"),sha(prefix))
                    record.put("rawBytes",raw.size).put("eventPrefixBytes",prefixBytes).put("eventPrefixSha256",sha(prefix))
                    val rawRows=raw.toString(Charsets.UTF_8).lineSequence().filter {it.isNotBlank()}.map {JSONObject(it)}.toList()
                    assertEquals("manual_platform_api",report.getJSONObject("metadata").getString("mode"))
                    assertEquals("no-agent:${lease.run}",report.getString("agentRunId"))
                    assertFalse(report.getBoolean("systemInjectionProven"))
                    assertFalse(report.getJSONObject("integrity").getBoolean("incomplete"))
                    verifyRaw(case,report,rawRows,record)
                    assertEquals("pass",report.getString("status"))
                    if(case.cancel) {
                        assertTrue("Cancellation must discard the active call",result.error in setOf(Code.CANCELLED,Code.STALE_AUTHORIZATION))
                        assertEquals("unknown",result.outcome)
                        assertTrue("A stopped 3-second hold must not wait for its original duration",record.getLong("callFinishedUptimeMs")-record.getLong("cancelRequestedUptimeMs")<1500)
                    } else {assertNull(result.message,result.error);assertEquals(true,result.data["released"])}
                    record.put("passed",true)
                } catch(t:Throwable) {
                    record.put("failure","${t.javaClass.simpleName}: ${t.message.orEmpty().take(2000)}")
                    failures.add("${case.name}: ${t.message}")
                    authority.stop();runId?.let {record.put("failureCleanupReply",backend.release(it))}
                    // Preserve report even if sender submission or assertions failed.
                    receiverArgs?.let {params->runCatching {
                        var state=receiver("report",params).getJSONObject("snapshot")
                        until(13) {state=receiver("report",params).getJSONObject("snapshot");!state.isNull("report")}
                        val report=state.getJSONObject("report");record.put("attemptId",report.getJSONObject("metadata").getString("attemptId")).put("receiverStatus",report.getString("status"))
                        File(context.filesDir,"physical-gamepad-${case.name}-report.json").writeText(report.toString(2))
                        File(context.filesDir,"physical-gamepad-${case.name}-events.jsonl").writeBytes(rawEvents(automation,record.getString("attemptId")))
                    }.onFailure {record.put("reportCollectionError",it.javaClass.simpleName+": "+it.message)}}
                } finally {
                    authority.stop();runId?.let {backend.release(it)}
                    runCatching {call?.get(2,TimeUnit.SECONDS)}
                    receiverArgs?.let {runCatching {receiver("release",it)}}
                    File(context.filesDir,"physical-gamepad-component.json").writeText(evidence.toString(2))
                }
            }
            evidence.put("passed",failures.isEmpty())
            assertTrue("Independent component failures: ${failures.joinToString("; ")}",failures.isEmpty())
        } finally {
            File(context.filesDir,"physical-gamepad-component.json").writeText(evidence.toString(2))
            executor.shutdownNow()
            instrumentation.runOnMainSync {activity.finish()};instrumentation.waitForIdleSync()
        }
    }

    private fun verifyRaw(case: Case,report: JSONObject,rows: List<JSONObject>,record: JSONObject) {
        val input=rows.filter {it.optString("recordOrigin")=="android_dispatch" && it.getString("eventKind").startsWith("raw_")}
        assertTrue("Independent receiver observed no raw input",input.isNotEmpty())
        assertTrue("Receiver must receive on the selected upper panel",input.all {it.getJSONObject("window").getInt("actualWindowDisplayId")==recordUpper(report)})
        val deviceIds=input.map {it.getJSONObject("data").getInt("deviceId")}.distinct()
        assertEquals("One physical controller must own the complete action",1,deviceIds.size)
        val deviceId=deviceIds.single();record.put("receiverDeviceId",deviceId).put("rawInputCount",input.size)
        record.put("receiverSources",JSONArray(input.map {it.getJSONObject("data").getInt("source")}.distinct()))
        val devices=report.getJSONObject("environmentBefore").getJSONArray("inputDevices").objects()
        val device=devices.single {it.getInt("deviceId")==deviceId}
        record.put("receiverDeviceName",device.getString("name")).put("receiverIsVirtual",device.getBoolean("isVirtual"))
        if(!case.cancel) {
            val sender=record.getJSONObject("sender").getJSONObject("data")
            record.put("senderDeclaredDeviceId",sender.getInt("deviceId"))
            assertEquals(deviceId,sender.getInt("deviceId"))
            assertEquals(recordUpper(report),sender.getInt("displayId"))
        }
        assertEquals("Odin Controller",device.getString("name"));assertFalse(device.getBoolean("isVirtual"))
        val stopAck=if(case.cancel)record.getLong("cleanupAcknowledgedUptimeMs") else Long.MAX_VALUE
        if(case.tool=="input.press_button") {
            val keys=input.filter {it.getString("eventKind")=="raw_key"}.map {it.getJSONObject("data")}
            assertEquals(2,keys.size);assertTrue(keys.all {it.getInt("source") and InputDevice.SOURCE_GAMEPAD==InputDevice.SOURCE_GAMEPAD && it.getInt("keyCode")==96})
            assertEquals(0,keys.first().getInt("action"));assertEquals(1,keys.last().getInt("action"))
            assertTrue("Stop must never generate a new DOWN after cleanup acknowledgement",keys.none {it.getInt("action")==0 && it.getLong("eventTime")>stopAck})
            val hold=keys.last().getLong("eventTime")-keys.first().getLong("eventTime")
            record.put("receiverHoldMs",hold)
            assertTrue("A bounded button hold must actually end promptly",hold in 1..1500)
            assertEquals(0,report.getJSONObject("sceneAfter").getJSONArray("pressed").length())
        } else {
            val motion=input.filter {it.getString("eventKind")=="raw_generic"}
            assertTrue(motion.isNotEmpty());assertTrue(motion.all {it.getJSONObject("data").getInt("source") and InputDevice.SOURCE_JOYSTICK==InputDevice.SOURCE_JOYSTICK})
            val axisIds=listOf(0,1,11,14,23,22)
            val ranges=device.getJSONArray("motionRanges").objects()
            val samples=motion.flatMap {row->row.getJSONObject("data").getJSONArray("samples").objects().map {sample->sample.getLong("eventTime") to sample.getJSONArray("pointers").getJSONObject(0).getJSONObject("axes")}}
            val axisEvidence=JSONArray()
            for(axis in axisIds) {
                val range=ranges.first {it.getInt("axis")==axis && it.getInt("source") and InputDevice.SOURCE_JOYSTICK==InputDevice.SOURCE_JOYSTICK}
                val min=range.getDouble("min");val max=range.getDouble("max");val center=if(min<0)0.0 else min
                val tolerance=maxOf(range.getDouble("flat"),(max-min)*.05)
                fun neutral(value:Double)=kotlin.math.abs(value-center)<=tolerance
                val values=samples.map {it.first to it.second.getDouble(axis.toString())}
                assertTrue("Axis $axis never made a real excursion",values.any {kotlin.math.abs(it.second-center)>=(max-min)*.5})
                assertTrue("Axis $axis did not finish neutral",neutral(values.last().second))
                val firstExcursion=values.indexOfFirst {(_,value)->!neutral(value)}
                val neutralOffset=values.drop(firstExcursion+1).indexOfFirst {(_,value)->neutral(value)}
                assertTrue("Axis $axis never returned to neutral",neutralOffset>=0)
                val firstNeutralAfterExcursion=firstExcursion+1+neutralOffset
                assertTrue("Axis $axis became nonzero again after release",values.drop(firstNeutralAfterExcursion).all {neutral(it.second)})
                assertTrue("Axis $axis generated a new nonzero event after cleanup acknowledgement",values.none {it.first>stopAck && !neutral(it.second)})
                axisEvidence.put(obj("axisId" to axis,"observedMin" to values.minOf {it.second},"observedMax" to values.maxOf {it.second},"finalValue" to values.last().second,"neutralTolerance" to tolerance))
            }
            record.put("axes",axisEvidence).put("motionSampleCount",samples.size)
        }
    }
    private fun recordUpper(report: JSONObject)=report.getJSONObject("environmentBefore").getJSONObject("window").getInt("actualWindowDisplayId")
}
