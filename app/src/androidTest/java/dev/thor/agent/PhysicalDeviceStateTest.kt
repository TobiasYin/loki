package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.Status
import dev.thor.tools.diagnostic.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Fixed Native cleanup/readback, explicitly distinct from real Agent acceptance. */
@RunWith(AndroidJUnit4::class)
class PhysicalDeviceStateTest {
    @Test fun readStateAndOptionallyRestoreRecordedBrightnessBaseline() {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        val args=InstrumentationRegistry.getArguments();org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay")!!.toInt()).toBundle()) as MainActivity
        ins.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val backend=ProductHost::class.java.getDeclaredField("backend").apply {isAccessible=true}.get(host) as AndroidBackend
        val end=SystemClock.elapsedRealtime()+30000
        while(backend.availability("debug.shell").status!=Status.READY && SystemClock.elapsedRealtime()<end)Thread.sleep(100)
        assertEquals(Status.READY,backend.availability("debug.shell").status)
        val remote=DebugConnection::class.java.getDeclaredField("service").apply {isAccessible=true}.get(backend.shizuku) as IDebugService
        fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)).use {it.readBytes().toString(Charsets.UTF_8).trim()}
        fun panel(screen:String,level:Int?=null):JSONObject {
            val token="state:${id()}";remote.openLease(token,SystemClock.elapsedRealtime()+10000,DebugUserService.ROOT,Binder())
            try {val r=JSONObject(remote.thorBrightness(token,if(level==null)"read" else "set",screen,level?:-1));assertFalse(r.toString(),r.has("error"));return r.getJSONObject("data")}finally {remote.cancel(token)}
        }
        fun snapshot():JSONObject {
            val settings=JSONObject();for(k in listOf("screen_brightness","screen_brightness_mode","fan_mode","min_refresh_rate","peak_refresh_rate"))settings.put(k,shell("settings get system $k"))
            val dns=JSONObject();for(k in listOf("private_dns_mode","private_dns_specifier"))dns.put(k,shell("settings get global $k"))
            return obj("upper" to panel("upper"),"lower" to panel("lower"),"settings" to settings,"privateDns" to dns,"displays" to JSONArray(context.getSystemService(DisplayManager::class.java).displays.filter {it.displayId==0 || it.name=="Screen-2"}.map {obj("id" to it.displayId,"refreshRate" to it.mode.refreshRate)}))
        }
        val evidence=obj("scope" to "Native cleanup/readback only; not Agent execution proof","brightnessUnit" to "percent","legacyBrightnessUnit" to "raw_0_255")
        try {
            evidence.put("before",snapshot())
            args.getString("cleanupSession")?.let {sid->
                assertEquals("Cleanup may only target the explicitly recorded test session",sid,host.selectedSession())
                val web=activity.findViewById<android.webkit.WebView>(android.R.id.custom)
                val ready=SystemClock.elapsedRealtime()+30000
                var delivered=false
                while(!delivered && SystemClock.elapsedRealtime()<ready) {
                    val latch=java.util.concurrent.CountDownLatch(1)
                    ins.runOnMainSync {web.evaluateJavascript("window.productReady===true") {delivered=it=="true";latch.countDown()}}
                    assertTrue(latch.await(5,java.util.concurrent.TimeUnit.SECONDS));if(!delivered)Thread.sleep(100)
                }
                assertTrue(delivered)
                for(cap in listOf("debug","screen_control")) {
                    val command=obj("op" to "capability","sessionId" to sid,"capability" to cap,"enabled" to false)
                    ins.runOnMainSync {web.evaluateJavascript("ThorUI.postMessage(${JSONObject.quote(command.toString())})",null)}
                }
                val stop=SystemClock.elapsedRealtime()+30000
                fun cleared():Boolean {val caps=host.view().getJSONArray("sessions").objects().single {it.getString("id")==sid}.getJSONObject("capabilities");return !caps.getBoolean("debug") && !caps.getBoolean("screen_control")}
                while(!cleared() && SystemClock.elapsedRealtime()<stop)Thread.sleep(100)
                assertTrue(cleared());evidence.put("testControlsDisabled",true).put("cleanupSession",sid)
            }
            if(args.getString("restoreBaseline")=="true") {
                for(screen in listOf("upper","lower"))panel(screen,args.getString(if(screen=="upper")"baselineUpperPercent" else "baselineLowerPercent")!!.toInt())
                Thread.sleep(500)
                val legacy=args.getString("legacyBrightness")!!.toInt();assertTrue(legacy in 0..255);shell("settings put system screen_brightness $legacy")
                Thread.sleep(500)
                evidence.put("restoredRecordedBrightnessBaseline",true)
            }
            val after=snapshot();evidence.put("after",after)
            if(args.getString("restoreBaseline")=="true")for(screen in listOf("upper","lower"))assertTrue(kotlin.math.abs(after.getJSONObject(screen).getInt("brightness")-args.getString(if(screen=="upper")"baselineUpperPercent" else "baselineLowerPercent")!!.toInt())<=1)
            evidence.put("passed",true)
        } finally {File(context.filesDir,"physical-device-final-state.json").writeText(evidence.toString(2));ins.runOnMainSync {activity.finish()};ins.waitForIdleSync()}
    }
}
