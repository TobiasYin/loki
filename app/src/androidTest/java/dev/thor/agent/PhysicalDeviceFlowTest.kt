package dev.thor.agent

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real saved cloud profile -> Pi -> Native tools -> independent physical receiver.
 * Does not configure credentials, clear history, or replay fixture input. */
@RunWith(AndroidJUnit4::class)
class PhysicalDeviceFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun js(web: WebView, script: String): String {
        val latch=CountDownLatch(1);var result=""
        instrumentation.runOnMainSync {web.evaluateJavascript(script) {result=it;latch.countDown()}}
        assertTrue(latch.await(5,TimeUnit.SECONDS));return result
    }
    private fun until(seconds: Int=30, condition: () -> Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong())
        while(System.nanoTime()<deadline) {if(condition())return;Thread.sleep(100)}
        fail("Physical device flow timed out")
    }
    private fun receiver(method: String, args: Bundle): JSONObject {
        val reply=context.contentResolver.call(Uri.parse("content://dev.thor.inputlab.runner"),method,null,args) ?: error("Receiver unavailable")
        return JSONObject().put("lease",reply.getString("lease")).put("snapshot",JSONObject(reply.getString("snapshot")!!))
    }
    @Test fun liveModelLogicalAndScreenshotCoordinatesReachUpperReceiver() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit physical cloud acceptance required",args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val profile=context.getSharedPreferences("profiles",0)
        assertEquals("passed",profile.getString("probe",null))
        assertEquals("deepseek-v4.1-flash",profile.getString("model",null))
        assertEquals("https://opencode.ai/zen/go/v1",profile.getString("url",null))
        val upper=args.getString("labDisplay")!!.toInt();val lower=args.getString("chatDisplay")!!.toInt();assertNotEquals(upper,lower)
        // Keep existing Accessibility services active throughout the test.
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun launchReceiver() {
            context.startActivity(Intent().setComponent(ComponentName("dev.thor.inputlab","dev.thor.inputlab.LabActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(upper).toBundle())
            Thread.sleep(1000)
        }
        launchReceiver()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(lower).toBundle()) as MainActivity
        val web=activity.findViewById<WebView>(android.R.id.custom)
        val host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val evidence=JSONObject().put("upperDisplay",upper).put("chatDisplay",lower).put("model","deepseek-v4.1-flash").put("remoteService","live OpenCode Go Responses").put("attempts",JSONArray())
        try {
            until {js(web,"window.productReady === true && document.querySelectorAll('.session-row').length>0 && !document.getElementById('new').disabled")=="true"}
            until {dev.thor.tools.diagnostic.ThorAccessibilityService.instance!=null && host.accessibilitySetupStatus().contains("已通过 Shizuku")}
            instrumentation.runOnMainSync {host.calibrate(upper,lower)}
            for(kind in listOf("logical","frame")) {
                if(kind=="frame")launchReceiver()
                val count=js(web,"document.querySelectorAll('.session-row').length").toInt();js(web,"document.getElementById('new').click()")
                until {js(web,"document.querySelectorAll('.session-row').length === ${count+1}")=="true"}
                js(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host.selectedSession())},capability:'screen_control',enabled:true}))")
                until {js(web,"document.querySelector('input[aria-label=屏幕操作]').checked")=="true"}
                val description=receiver("describe",Bundle().apply {putInt("version",1)}).getJSONObject("snapshot")
                assertEquals(upper,description.getJSONObject("geometry").getInt("actualWindowDisplayId"))
                val target=description.getJSONObject("target");val x=((target.getDouble("left")+target.getDouble("right"))/2).toInt();val y=((target.getDouble("top")+target.getDouble("bottom"))/2).toInt()
                val text=if(kind=="logical") "请只调用一次 input_tap，x=$x,y=$y,coordinateSpace=display_logical,expectedPackage=dev.thor.inputlab，点击上屏测试目标。成功后简短回答，不要再调用任何工具。" else "请先调用 perception_capture_screen，region=[${x-50},${y-50},${x+50},${y+50}],maxEdge=100,format=jpeg。然后用该结果的 frameId 调用且只调用一次 input_tap，x=50,y=50,coordinateSpace=frame_pixels,expectedPackage=dev.thor.inputlab。成功后简短回答，不要调用其他工具或重复点击。"
                val begun=host.prepareForRunner(text).get(5,TimeUnit.SECONDS);val runId=begun.getJSONObject("lease").getString("runId");val testRun=id()
                val setup=receiver("prepare",Bundle().apply {putInt("version",1);putInt("observationSeconds",30);putString("testRunId",testRun);putString("caseId","LAB-02");putString("agentRunId",runId);putString("senderTraceRef","live-product:$runId:$kind")})
                val receiverArgs=Bundle().apply {putInt("version",1);putString("testRunId",testRun);putString("lease",setup.getString("lease"))}
                try {
                    var snapshot=setup.getJSONObject("snapshot")
                    until(3) {snapshot=receiver("report",receiverArgs).getJSONObject("snapshot");snapshot.optString("testRunId")==testRun}
                    Thread.sleep(700)
                    val targets=JSONArray();var previousTarget=""
                    fun observeTarget() {
                        val live=dev.thor.tools.diagnostic.ThorAccessibilityService.instance?.target(upper)
                        val key=live?.let {it.revision+"|"+it.touchableBounds}
                        if(live!=null && key!=previousTarget) {
                            previousTarget=key!!
                            val geometry=JSONArray()
                            for(window in dev.thor.tools.diagnostic.ThorAccessibilityService.instance!!.windowsOnAllDisplays[upper].orEmpty()) {
                                val bounds=android.graphics.Rect();window.getBoundsInScreen(bounds)
                                val root=window.root;var rootBounds: String?=null
                                try {if(root!=null) {root.refresh();val rect=android.graphics.Rect();root.getBoundsInScreen(rect);rootBounds=rect.toString()}}
                                finally {if(root!=null) {@Suppress("DEPRECATION") root.recycle()}}
                                geometry.put(obj("windowId" to window.id,"type" to window.type,"bounds" to bounds.toString(),"rootBounds" to rootBounds))
                            }
                            targets.put(obj("elapsedRealtimeMs" to android.os.SystemClock.elapsedRealtime(),"packageName" to live.packageName,"validity" to live.validity,"revision" to live.revision,"touchableBounds" to live.touchableBounds?.toString(),"geometry" to geometry))
                        }
                    }
                    observeTarget()
                    js(web,"window.runPreparedNative(JSON.parse(${JSONObject.quote(begun.toString())}));true")
                    until(32) {observeTarget();snapshot=receiver("report",receiverArgs).getJSONObject("snapshot");!snapshot.isNull("report")}
                    val report=snapshot.getJSONObject("report")
                    File(context.filesDir,"physical-cloud-$kind-report.json").writeText(report.toString(2))
                    val state=SqliteStore(context,"product.db").use {it.load()}
                    val native=state.getJSONArray("events").objects().filter {it.getString("runId")==runId && it.getString("type")=="tool_result"}
                    val record=obj("kind" to kind,"runId" to runId,"receiverStatus" to report.getString("status"),"attemptId" to report.getJSONObject("metadata").getString("attemptId"),"targets" to targets,"results" to JSONArray(native.map {it.getJSONObject("data")}))
                    evidence.getJSONArray("attempts").put(record)
                    File(context.filesDir,"physical-cloud-input.json").writeText(evidence.toString(2))
                    // Keep independent frame coverage even when the logical case
                    // is refused; retain both reports and fail the suite at the end.
                    if(report.getString("status")!="pass") {
                        until {js(web,"document.getElementById('send').textContent === '发送'")=="true"}
                        continue
                    }
                    assertEquals("Physical receiver must observe exactly one complete target tap","pass",report.getString("status"));assertEquals(runId,report.getString("agentRunId"))
                    assertEquals(if(kind=="logical")1 else 2,native.size)
                    assertTrue(native.all {it.getJSONObject("data").isNull("error")})
                    assertEquals("input.tap",native.last().getJSONObject("data").getString("toolId"))
                    if(kind=="frame") {
                        val capture=native.first().getJSONObject("data").getJSONArray("content").objects().first {it.getString("type")=="text"}
                        val details=JSONObject(capture.getString("text"))
                        assertNotEquals("A fresh image must not inherit the old request identity",details.getString("requestSnapshotId"),details.getString("targetSnapshotId"))
                        val run=state.getJSONArray("runs").objects().single {it.getString("id")==runId}
                        assertTrue("Next model context must use the actual captured target",run.getJSONArray("contexts").objects().any {it.optString("l0SnapshotId")==details.getString("targetSnapshotId")})
                        record.put("freshFrameSnapshotId",details.getString("targetSnapshotId"))
                    }
                    until {js(web,"document.getElementById('send').textContent === '发送'")=="true"}
                    val completed=SqliteStore(context,"product.db").use {it.load().getJSONArray("runs").objects().single {r -> r.getString("id")==runId}}
                    record.put("runStatus",completed.getString("status"))
                    assertEquals("completed",completed.getString("status"))
                } finally {
                    runCatching {receiver("release",receiverArgs)}
                    // Each test created its own session; leave it with control off.
                    runCatching {js(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host.selectedSession())},capability:'screen_control',enabled:false}))")}
                }
            }
            assertTrue("Both independent physical receiver cases must pass",evidence.getJSONArray("attempts").objects().all {it.getString("receiverStatus")=="pass"})
            evidence.put("passed",true)
        } finally {
            File(context.filesDir,"physical-cloud-input.json").writeText(evidence.toString(2))
            instrumentation.runOnMainSync {activity.finish()};instrumentation.waitForIdleSync()
        }
    }
}
