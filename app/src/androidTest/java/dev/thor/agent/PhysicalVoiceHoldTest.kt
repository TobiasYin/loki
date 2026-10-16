package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.os.ParcelFileDescriptor
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

/** Trusted platform touch, real mic/service; ambient speech never starts an Agent task. */
@RunWith(AndroidJUnit4::class)
class PhysicalVoiceHoldTest {
    @Test fun trustedStationaryHoldSurvivesUntilActualRelease() {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("physicalAcceptance")=="true")
        val dropCapture=InstrumentationRegistry.getArguments().getString("dropPointerCapture")=="true"
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val automation=ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(4).toBundle()) as MainActivity
        val web=activity.findViewById<WebView>(android.R.id.custom)
        ins.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val report=obj("physical" to true,"inputOrigin" to "platform touchscreen on display 4; not synthetic DOM events","agentSendSuppressed" to true)
        fun js(code:String):String {val latch=CountDownLatch(1);var result="";ins.runOnMainSync {web.evaluateJavascript(code){result=it;latch.countDown()}};assertTrue(latch.await(5,TimeUnit.SECONDS));return result}
        fun json(code:String)=org.json.JSONTokener(js("JSON.stringify($code)")).nextValue() as String
        fun save() {File(context.filesDir,"physical-voice-hold.json").writeText(report.toString(2))}
        var gesture:Thread?=null
        try {
            val ready=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(js("window.productReady===true && !document.getElementById('new').disabled")!="true" && System.nanoTime()<ready)Thread.sleep(100)
            assertEquals("true",js("window.productReady===true"))
            val count=js("document.querySelectorAll('.session-row').length").toInt();js("document.getElementById('new').click()")
            val created=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(js("document.querySelectorAll('.session-row').length===${count+1}")!="true" && System.nanoTime()<created)Thread.sleep(100)
            js("""(() => {
              window.__holdTrace=[];window.__holdVoice=[];window.__holdCommands=[];
              const post=ThorUI.postMessage.bind(ThorUI);ThorUI.postMessage=raw=>{const c=JSON.parse(raw);if(c.op.startsWith('voice.')||c.op==='send')window.__holdCommands.push({op:c.op,at:performance.now()});if(c.op!=='send')post(raw)};
              const receive=window.receiveVoiceState;window.receiveVoiceState=e=>{window.__holdVoice.push({phase:e.phase,error:e.error,textLength:e.text?.length,at:performance.now()});receive(e)};
              for(const type of ['pointerdown','pointerup','pointercancel','lostpointercapture','gotpointercapture'])document.addEventListener(type,e=>{const b=document.getElementById('voice-hold');if(type==='pointerdown' && e.target===b)window.__holdPointerId=e.pointerId;if(e.pointerId===window.__holdPointerId)window.__holdTrace.push({type,pointerId:e.pointerId,at:performance.now(),trusted:e.isTrusted,x:e.clientX,y:e.clientY,holding:b.classList.contains('holding')})},true);
              document.getElementById('voice-toggle').click();
            })()""")
            Thread.sleep(700)
            val position=JSONObject(js("(() => {const r=document.getElementById('voice-hold').getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2,w:innerWidth,h:innerHeight}})()"))
            val origin=IntArray(2);var x=0;var y=0
            ins.runOnMainSync {web.getLocationOnScreen(origin);x=(origin[0]+web.width*position.getDouble("x")/position.getDouble("w")).toInt();y=(origin[1]+web.height*position.getDouble("y")/position.getDouble("h")).toInt()}
            report.put("position",position).put("nativeX",x).put("nativeY",y);save()
            val endY=y+if(dropCapture)120 else 12
            report.put("requestedMovementPixels",obj("x" to 12,"y" to endY-y))
            gesture=Thread {ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input touchscreen -d 4 swipe $x $y ${x+12} $endY 5000")).use {it.readBytes()}}
            gesture.start()
            val samples=JSONArray();val started=System.nanoTime();var dropped=false
            while(gesture.isAlive) {
                val sample=JSONObject(js("(() => {const b=document.getElementById('voice-hold'),r=b.getBoundingClientRect();return {holding:b.classList.contains('holding'),disabled:b.disabled,status:document.getElementById('voice-status').textContent,top:r.top,bottom:r.bottom}})()"))
                sample.put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));samples.put(sample)
                if(dropCapture && !dropped && sample.getLong("elapsedMs")>=1500) {
                    assertEquals("true",js("(() => {const b=document.getElementById('voice-hold'),p=window.__holdPointerId;if(!b.hasPointerCapture(p))return false;b.releasePointerCapture(p);return true})()"))
                    dropped=true;report.put("captureExplicitlyReleasedWhileFingerDown",true)
                }
                report.put("samples",samples).put("touchEvents",JSONArray(json("window.__holdTrace"))).put("voiceEvents",JSONArray(json("window.__holdVoice"))).put("commands",JSONArray(json("window.__holdCommands")));save();Thread.sleep(150)
            }
            gesture.join();Thread.sleep(500)
            report.put("touchEvents",JSONArray(json("window.__holdTrace"))).put("voiceEvents",JSONArray(json("window.__holdVoice"))).put("commands",JSONArray(json("window.__holdCommands")));save()
            val events=report.getJSONArray("touchEvents").objects();assertTrue(events.any {it.getString("type")=="pointerdown" && it.getBoolean("trusted")})
            assertTrue("Hold ended before physical release; inspect touch/voice trace",samples.objects().filter {it.getLong("elapsedMs") in 700..4200}.all {it.getBoolean("holding")})
            assertTrue("Starting recording must not move the touch target",samples.objects().filter {it.optBoolean("holding")}.all {kotlin.math.abs(it.getDouble("top")-(position.getDouble("y")-22))<1})
            val commands=report.getJSONArray("commands").objects();assertEquals(1,commands.count {it.getString("op")=="voice.start"});assertEquals(1,commands.count {it.getString("op")=="voice.finish"});assertFalse(commands.any {it.getString("op")=="voice.cancel"})
            report.put("passed",true)
        } finally {
            gesture?.join(6000);runCatching {js("document.getElementById('voice-cancel').click()")};save()
            ins.runOnMainSync {activity.finish()};ins.waitForIdleSync()
        }
    }
}
