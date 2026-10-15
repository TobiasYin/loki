package dev.thor.agent

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Platform stimulus negative controls. Does not run a model or claim product input delivery. */
@RunWith(AndroidJUnit4::class)
class PhysicalReceiverNegativeTest {
    @Test fun fullscreenReceiverRejectsNoInputWrongTargetAndUpperAppChange() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val context=instrumentation.targetContext
        val upper=args.getString("labDisplay")!!.toInt()
        val automation=instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) {ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {it.readBytes()}}
        fun receiver(method: String, values: Bundle): JSONObject {
            val reply=context.contentResolver.call(Uri.parse("content://dev.thor.inputlab.runner"),method,null,values) ?: error("Receiver unavailable")
            return JSONObject().put("lease",reply.getString("lease")).put("snapshot",JSONObject(reply.getString("snapshot")!!))
        }
        fun launch() {
            context.startActivity(Intent().setComponent(ComponentName("dev.thor.inputlab","dev.thor.inputlab.LabActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(upper).toBundle())
            Thread.sleep(1000)
        }
        fun until(seconds: Int, condition: () -> Boolean) {
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong())
            while(System.nanoTime()<end) {if(condition())return;Thread.sleep(100)}
            fail("Receiver negative control timed out")
        }
        val evidence=JSONObject().put("stimulusSource","platform UiAutomation shell; no model or product input tool").put("upperDisplay",upper).put("cases",JSONArray())
        try {
            for(kind in listOf("no-input","wrong-target","target-switch")) {
                launch()
                val description=receiver("describe",Bundle().apply {putInt("version",1)}).getJSONObject("snapshot")
                assertTrue(description.getJSONObject("geometry").getJSONObject("windowInsets").getBoolean("fullscreenReady"))
                val testRun="platform-negative-${id()}"
                val setup=receiver("prepare",Bundle().apply {
                    putInt("version",1);putInt("observationSeconds",5);putString("evidenceMode","manual_platform_api")
                    putString("testRunId",testRun);putString("caseId",if(kind=="no-input")"LAB-14" else "LAB-02")
                    putString("agentRunId","no-agent-run:$testRun");putString("senderTraceRef","platform-negative-control:$kind; no Agent input tool")
                })
                val receiverArgs=Bundle().apply {putInt("version",1);putString("testRunId",testRun);putString("lease",setup.getString("lease"))}
                try {
                    var snapshot=setup.getJSONObject("snapshot")
                    until(3) {snapshot=receiver("report",receiverArgs).getJSONObject("snapshot");snapshot.optString("testRunId")==testRun}
                    Thread.sleep(700)
                    if(kind=="wrong-target") {
                        val end=snapshot.getJSONObject("endTarget")
                        val x=((end.getDouble("left")+end.getDouble("right"))/2).toInt();val y=((end.getDouble("top")+end.getDouble("bottom"))/2).toInt()
                        shell("input -d $upper tap $x $y")
                    } else if(kind=="target-switch") {
                        context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(upper).toBundle())
                    }
                    until(9) {snapshot=receiver("report",receiverArgs).getJSONObject("snapshot");!snapshot.isNull("report")}
                    val report=snapshot.getJSONObject("report")
                    File(context.filesDir,"physical-negative-$kind-report.json").writeText(report.toString(2))
                    val expected=if(kind=="target-switch")"inconclusive" else "fail"
                    val reasons=report.getJSONObject("assertions").getJSONArray("reasons")
                    val correct=report.getString("status")==expected && (kind!="target-switch" || reasons.toString().contains("focus") || reasons.toString().contains("activity_paused"))
                    evidence.getJSONArray("cases").put(obj("kind" to kind,"expected" to expected,"actual" to report.getString("status"),"correct" to correct,"reasons" to reasons,"attemptId" to report.getJSONObject("metadata").getString("attemptId")))
                } finally {
                    runCatching {receiver("release",receiverArgs)}
                    if(kind=="target-switch")launch()
                }
            }
            assertTrue("Each negative control must produce its expected independent verdict",evidence.getJSONArray("cases").objects().all {it.getBoolean("correct")})
            evidence.put("passed",true)
        } finally {File(context.filesDir,"physical-receiver-negatives.json").writeText(evidence.toString(2))}
    }
}
