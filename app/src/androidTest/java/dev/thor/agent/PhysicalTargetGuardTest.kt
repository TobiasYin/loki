package dev.thor.agent

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.*
import dev.thor.tools.diagnostic.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Real AndroidBackend/Gateway guard controls with explicitly labelled TEST authority.
 * No model, no product-authority claim, and no fixture resets. Expected input count is zero. */
@RunWith(AndroidJUnit4::class)
class PhysicalTargetGuardTest {
    @Test fun clippedFrameExpiredFrameAndChangedAppNeverDispatch() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val upper=args.getString("labDisplay")!!.toInt();val lower=args.getString("chatDisplay")!!.toInt()
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun launchLab() {context.startActivity(Intent().setComponent(ComponentName("dev.thor.inputlab","dev.thor.inputlab.LabActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(upper).toBundle());Thread.sleep(1000)}
        launchLab()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(lower).toBundle()) as MainActivity
        val host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val backend=ProductHost::class.java.getDeclaredField("backend").apply {isAccessible=true}.get(host) as AndroidBackend
        val snapshots=ProductHost::class.java.getDeclaredField("snapshots").apply {isAccessible=true}.get(host) as Snapshots
        val clock=Clock {SystemClock.elapsedRealtime()}
        fun until(seconds:Int,condition:()->Boolean) {val end=clock.now()+seconds*1000;while(clock.now()<end) {if(condition())return;Thread.sleep(100)};fail("Physical target guard timed out")}
        fun receiver(method:String,values:Bundle):JSONObject {
            val r=context.contentResolver.call(Uri.parse("content://dev.thor.inputlab.runner"),method,null,values) ?: error("Receiver missing")
            return obj("lease" to r.getString("lease"),"snapshot" to JSONObject(r.getString("snapshot")!!))
        }
        val evidence=obj("authority" to "ManualTestAuthority: component control, no model or product-authority Run","expectedInputCount" to 0,"cases" to JSONArray())
        try {
            until(30) {ThorAccessibilityService.instance!=null && host.accessibilitySetupStatus().contains("已通过 Shizuku")}
            snapshots.update(displayFacts(context));snapshots.calibrate(upper,lower)
            for(kind in listOf("clipping","switch")) {
                if(kind=="switch")launchLab()
                if(kind=="clipping")until(40) {
                    val t=ThorAccessibilityService.instance!!.target(upper);val b=t.touchableBounds
                    b!=null && (b.left>0 || b.top>0 || b.right<1920 || b.bottom<1080)
                }
                val testRun="component-guard-${id()}"
                val setup=receiver("prepare",Bundle().apply {putInt("version",1);putInt("observationSeconds",if(kind=="clipping")30 else 5);putString("evidenceMode","manual_platform_api");putString("testRunId",testRun);putString("agentRunId","no-agent:$testRun");putString("caseId","LAB-14");putString("senderTraceRef","component-gateway/ManualTestAuthority:$kind; no cloud or product-authority run")})
                val readArgs=Bundle().apply {putInt("version",1);putString("testRunId",testRun);putString("lease",setup.getString("lease"))}
                val authority=ManualTestAuthority(clock).apply {set(Capability.SCREEN_CONTROL,true)}
                val lease=authority.begin(backend);val gate=Gateway(authority,backend,snapshots,clock)
                fun call(tool:String,target:String,values:Map<String,Any?>)=gate.execute(Envelope(lease.session,lease.run,id(),tool,lease.epoch,lease.deadline,target),values)
                try {
                    var state=setup.getJSONObject("snapshot")
                    until(3) {state=receiver("report",readArgs).getJSONObject("snapshot");state.optString("testRunId")==testRun}
                    Thread.sleep(700)
                    var image=call("perception.capture_screen",snapshots.capture().id,mapOf("maxEdge" to 100,"format" to "jpeg"))
                    assertNull(image.error)
                    if(kind=="clipping")until(6) {
                        val b=snapshots.validate(image.snapshotId).target.touchableBounds
                        if(b!=null && (b.left>0 || b.top>0 || b.right<1920 || b.bottom<1080))true
                        else {Thread.sleep(500);image=call("perception.capture_screen",snapshots.capture().id,mapOf("maxEdge" to 100,"format" to "jpeg"));assertNull(image.error);false}
                    }
                    val captured=snapshots.validate(image.snapshotId)
                    val record=obj("kind" to kind,"capturedSnapshotId" to captured.id,"results" to JSONArray())
                    evidence.getJSONArray("cases").put(record)
                    fun remember(name:String,r:ToolResult) {record.getJSONArray("results").put(obj("name" to name,"error" to r.error?.name,"message" to r.message,"outcome" to r.outcome))}
                    if(kind=="clipping") {
                        val b=captured.target.touchableBounds!!
                        val point=when {b.left>0 -> Point(0.0,540.0);b.top>0 -> Point(960.0,0.0);b.right<1920 -> Point(1919.5,540.0);else -> Point(960.0,1079.5)}
                        val transform=image.data.getValue("transform") as Map<*,*>
                        val x=point.x*(transform["imageWidth"] as Number).toDouble()/(transform["displayWidth"] as Number).toDouble()
                        val y=point.y*(transform["imageHeight"] as Number).toDouble()/(transform["displayHeight"] as Number).toDouble()
                        val edge=call("input.tap",captured.id,mapOf("x" to x,"y" to y,"coordinateSpace" to "frame_pixels","frameId" to image.data.getValue("frameId"),"expectedPackage" to "dev.thor.inputlab"))
                        remember("clipped-frame-point",edge);assertEquals(Code.TARGET_CHANGED,edge.error);assertEquals("not_submitted",edge.outcome);assertTrue(edge.message.orEmpty().contains("observed/current touchable region"))
                        record.put("observedTouchableBounds",b.toString()).put("requestedDisplayPoint",JSONArray(listOf(point.x,point.y)))
                        Thread.sleep(10100)
                        val expired=call("input.tap",captured.id,mapOf("x" to 470,"y" to 600,"coordinateSpace" to "display_logical","expectedPackage" to "dev.thor.inputlab"))
                        remember("expired-snapshot",expired);assertEquals(Code.TARGET_CHANGED,expired.error);assertEquals("not_submitted",expired.outcome);assertTrue(expired.message.orEmpty().contains("expired"))
                    } else {
                        context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(upper).toBundle());Thread.sleep(700)
                        val switched=call("input.tap",captured.id,mapOf("x" to 470,"y" to 600,"coordinateSpace" to "display_logical","expectedPackage" to "dev.thor.inputlab"))
                        remember("changed-upper-app",switched);assertEquals(Code.TARGET_CHANGED,switched.error);assertEquals("not_submitted",switched.outcome)
                    }
                    until(35) {state=receiver("report",readArgs).getJSONObject("snapshot");!state.isNull("report")}
                    val report=state.getJSONObject("report");File(context.filesDir,"physical-guard-$kind-report.json").writeText(report.toString(2))
                    assertEquals(if(kind=="clipping")"fail" else "inconclusive",report.getString("status"))
                    assertEquals(0,report.getJSONObject("sceneAfter").getInt("rawCount"));assertEquals(0,report.getJSONObject("sceneAfter").getInt("hits"))
                    record.put("receiverStatus",report.getString("status")).put("inputCount",0).put("passed",true)
                } finally {authority.stop();backend.release(lease.run);runCatching {receiver("release",readArgs)}}
            }
            evidence.put("passed",true)
        } finally {File(context.filesDir,"physical-target-guards.json").writeText(evidence.toString(2));instrumentation.runOnMainSync {activity.finish()};instrumentation.waitForIdleSync()}
    }
}
