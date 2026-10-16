package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewTreeObserver
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real installed product WebView + shell platform touch. ASR phases and outgoing
 * sends are intercepted locally: this is UI component evidence, not microphone,
 * ASR, model, hardware finger, or end-to-end Agent acceptance. */
@RunWith(AndroidJUnit4::class)
class PhysicalVoiceGestureTest {
    @Test fun trustedLongSwipeCanReturnToSendAndOnlyCancelsOnRelease() {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext;val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val display=args.getString("chatDisplay","4").toInt();assertEquals("Thor lower screen must be explicit",4,display)
        val automation=ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()) as MainActivity
        val web=activity.findViewById<WebView>(android.R.id.custom)
        ins.runOnMainSync {activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val evidence=obj("testScope" to "installed-product-WebView/UI-component; simulated-ASR-state", "physicalDevice" to true,"platformTouch" to true,"humanFinger" to false,"microphoneUsed" to false,"audioUploaded" to false,"modelCalled" to false,"outgoingVoiceAndSendSuppressed" to true,"displayId" to display,"stages" to JSONArray(),"injectorTrace" to JSONArray())
        fun js(code:String):String {val latch=CountDownLatch(1);var raw="";ins.runOnMainSync {web.evaluateJavascript(code){raw=it;latch.countDown()}};assertTrue(latch.await(5,TimeUnit.SECONDS));return raw}
        fun jsValue(code:String)=org.json.JSONTokener(js("JSON.stringify($code)")).nextValue() as String
        fun until(condition:String) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(System.nanoTime()<end){if(js(condition)=="true")return;Thread.sleep(50)};fail("UI condition not met: $condition")}
        fun hashes():JSONObject {
            val result=JSONObject()
            SqliteStore(context,"product.db").use {store->for(table in listOf("sessions","runs","messages","events","attachments","metadata")) {
                val digest=MessageDigest.getInstance("SHA-256");val order=if(table=="metadata")"name,value" else "id,payload"
                store.readableDatabase.rawQuery("SELECT $order FROM $table ORDER BY 1",null).use {c->while(c.moveToNext()){digest.update(c.getString(0).toByteArray());digest.update(0.toByte());digest.update(c.getString(1).toByteArray());digest.update(0.toByte())}}
                result.put("db:$table",digest.digest().joinToString(""){"%02x".format(it)})
            }}
            for(name in listOf("profiles","voice","native-credentials","display-roles"))result.put("prefs:$name",SkillStore.digest(context.getSharedPreferences(name,0).all.toSortedMap().toString().toByteArray()))
            return result
        }
        fun save(){File(context.filesDir,"physical-voice-gesture.json").writeText(evidence.toString(2))}
        fun sample(name:String):JSONObject {
            val s=JSONObject(js("(() => {const b=document.getElementById('voice-hold'),r=b.getBoundingClientRect(),o=document.getElementById('voice-overlay');return {name:${JSONObject.quote(name)},holding:b.classList.contains('holding'),disabled:b.disabled,overlay:!o.hidden,cancelPending:o.classList.contains('cancel-pending'),label:b.textContent,transcript:document.getElementById('voice-transcript').textContent,transcriptHidden:document.getElementById('voice-transcript').hidden,left:r.left,top:r.top,width:r.width,height:r.height,viewportWidth:innerWidth,viewportHeight:innerHeight,threshold:Math.max(120,Math.min(240,innerHeight*.4))}})()"))
            evidence.getJSONArray("stages").put(s);save();return s
        }
        fun screenshot(name:String) {
            val copied=CountDownLatch(1);var code=-1;var bitmap:Bitmap?=null
            val requestId=System.nanoTime();var draws=0
            ins.runOnMainSync {
                web.postVisualStateCallback(requestId,object:WebView.VisualStateCallback() {
                    override fun onComplete(id:Long) {
                        // DOM evaluation can precede the compositor. The visual
                        // callback guarantees its updates in the next WebView draw.
                        // Wait two real window traversals before copying the GPU
                        // window buffer; WebView.draw(Canvas) can reuse an old frame.
                        val observer=web.viewTreeObserver
                        val listener=object:ViewTreeObserver.OnDrawListener {
                            override fun onDraw() {
                                draws++
                                if(draws<2)web.postOnAnimation {web.invalidate()}
                                else web.post {
                                    if(observer.isAlive)observer.removeOnDrawListener(this)
                                    if(bitmap!=null)return@post
                                    val origin=IntArray(2);web.getLocationInWindow(origin)
                                    val output=Bitmap.createBitmap(web.width,web.height,Bitmap.Config.ARGB_8888);bitmap=output
                                    try {PixelCopy.request(activity.window,Rect(origin[0],origin[1],origin[0]+web.width,origin[1]+web.height),output,{result->code=result;copied.countDown()},Handler(Looper.getMainLooper()))}
                                    catch(error:Exception){code=PixelCopy.ERROR_UNKNOWN;evidence.put("screenshotError",error.javaClass.simpleName);copied.countDown()}
                                }
                            }
                        }
                        observer.addOnDrawListener(listener);web.invalidate()
                    }
                })
            }
            assertTrue("No stable committed window frame for $name",copied.await(10,TimeUnit.SECONDS))
            evidence.put("screenshots",evidence.optJSONArray("screenshots") ?: JSONArray())
            evidence.getJSONArray("screenshots").put(obj("name" to name,"visualStateCallback" to requestId,"draws" to draws,"method" to "postVisualStateCallback + two actual draws + PixelCopy window buffer","pixelCopyResult" to code));save()
            try {assertEquals("Window PixelCopy failed for $name",PixelCopy.SUCCESS,code);File(context.filesDir,"physical-voice-gesture-$name.png").outputStream().use {bitmap!!.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap?.recycle()}
        }
        val reader=Executors.newFixedThreadPool(2);var descriptors:Array<ParcelFileDescriptor>?=null;var output:java.io.BufferedReader?=null;var commands:java.io.BufferedWriter?=null;var before:JSONObject?=null
        val stderr=StringBuffer()
        try {
            until("window.productReady===true && document.getElementById('send').dataset.running==='false'")
            before=hashes();evidence.put("beforeHashes",before)
            assertEquals("true",js("(() => {window.__gestureCommands=[];window.__gestureTouches=[];window.__gestureLastStart=null;const post=ThorUI.postMessage.bind(ThorUI);ThorUI.postMessage=raw=>{const c=JSON.parse(raw);if(c.op.startsWith('voice.')||c.op==='send'){window.__gestureCommands.push({...c,at:performance.now()});if(c.op==='voice.start'){window.__gestureLastStart=c;setTimeout(()=>window.receiveVoiceState({...c,phase:'recording',text:'中间识别文本不显示'}),0);}if(c.op==='voice.finish')setTimeout(()=>window.receiveVoiceState({...c,phase:'complete',text:'测试语音消息',submit:'测试语音消息'}),0);return;}post(raw);};for(const type of ['pointerdown','pointermove','pointerup','pointercancel'])document.addEventListener(type,e=>{if(e.target===document.getElementById('voice-hold')||window.__gesturePointer===e.pointerId){if(type==='pointerdown')window.__gesturePointer=e.pointerId;window.__gestureTouches.push({type,trusted:e.isTrusted,pointerId:e.pointerId,x:e.clientX,y:e.clientY,at:performance.now()});}},true);if(document.getElementById('voice-panel').hidden)document.getElementById('voice-toggle').click();return true;})()"))
            until("!document.getElementById('voice-panel').hidden && !document.getElementById('voice-hold').disabled")
            val idle=sample("idle");screenshot("idle")
            val origin=IntArray(2);var webWidth=0;var webHeight=0
            ins.runOnMainSync {web.getLocationOnScreen(origin);webWidth=web.width;webHeight=web.height}
            val scaleX=webWidth/idle.getDouble("viewportWidth");val scaleY=webHeight/idle.getDouble("viewportHeight")
            val x=origin[0]+(idle.getDouble("left")+idle.getDouble("width")/2)*scaleX
            val y=origin[1]+(idle.getDouble("top")+idle.getDouble("height")/2)*scaleY
            val small=y-70*scaleY;val far=y-(idle.getDouble("threshold")+30)*scaleY
            assertTrue("Viewport must allow the configured long upward gesture",far>origin[1]+5)
            val apk=ins.context.applicationInfo.sourceDir
            require(apk.matches(Regex("^/data/app/[A-Za-z0-9_./=+~-]+\\.apk$"))) {"Unexpected test APK path; no shell interpolation is allowed"}
            // UiAutomation executes an argv command, not a shell script. env sets
            // CLASSPATH without assignments, quotes, redirection or other syntax.
            val helperCommand="/system/bin/env CLASSPATH=$apk /system/bin/app_process / dev.thor.agent.testinput.VoiceGestureInjector $display"
            evidence.put("helperCommand",helperCommand).put("helperStartupAttempted",true);save()
            descriptors=automation.executeShellCommandRwe(helperCommand)
            output=ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader();commands=ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).bufferedWriter()
            val errorFd=descriptors[2]
            reader.submit {try {ParcelFileDescriptor.AutoCloseInputStream(errorFd).use {stream->val bytes=ByteArray(1024);while(true){val count=stream.read(bytes);if(count<0)break;if(stderr.length<8192)stderr.append(String(bytes,0,minOf(count,8192-stderr.length),Charsets.UTF_8))}}}catch(_:Exception){}}
            fun line():String {
                val read=reader.submit<String>{output!!.readLine()?:error("Input helper closed unexpectedly")}
                val value=try {read.get(10,TimeUnit.SECONDS)}catch(error:Exception) {
                    // Close raw fds first: BufferedReader.close() would wait for
                    // the same monitor held by its blocked readLine() call.
                    descriptors?.forEach {runCatching {it.close()}};read.cancel(true)
                    evidence.put("helperReadError",error.javaClass.simpleName).put("helperStderr",stderr.toString());save();throw error
                }
                evidence.getJSONArray("injectorTrace").put(value);save();return value
            }
            var ready="";repeat(12){if(!ready.startsWith("READY"))ready=line()};assertTrue("Shell input helper not ready: $ready",ready.startsWith("READY uid=2000 display=$display"))
            fun inject(op:String,targetY:Double){commands!!.write("$op $x $targetY\n");commands!!.flush();assertTrue(line().startsWith("ACK $op "))}
            fun stable(name:String):JSONObject {val s=sample(name);for(key in listOf("left","top","width","height"))assertEquals("Held target moved at $name ($key)",idle.getDouble(key),s.getDouble(key),0.5);assertTrue(s.getBoolean("holding"));return s}
            fun count(op:String)=js("window.__gestureCommands.filter(c=>c.op===${JSONObject.quote(op)}).length").toInt()
            inject("DOWN",y);until("document.getElementById('voice-hold').classList.contains('holding')");stable("held");screenshot("held")
            inject("MOVE",small);Thread.sleep(100);assertFalse(stable("small-up").getBoolean("cancelPending"));assertEquals(0,count("voice.cancel"));assertEquals(0,count("send"))
            inject("MOVE",far);until("document.getElementById('voice-overlay').classList.contains('cancel-pending')");assertTrue(stable("long-up-pending").getBoolean("cancelPending"));assertEquals(0,count("voice.cancel"));assertEquals(0,count("voice.finish"));assertEquals(0,count("send"));screenshot("cancel-pending")
            inject("MOVE",y);until("!document.getElementById('voice-overlay').classList.contains('cancel-pending')");stable("returned");assertEquals(0,count("voice.cancel"));screenshot("returned")
            inject("UP",y);until("window.__gestureCommands.filter(c=>c.op==='send').length===1 && document.getElementById('voice-hold').textContent==='按住说话'")
            val sent=sample("sent-idle");assertFalse(sent.getBoolean("overlay"));assertEquals("",sent.getString("transcript"));assertTrue(sent.getBoolean("transcriptHidden"));assertEquals(1,count("voice.finish"));assertEquals(0,count("voice.cancel"));screenshot("sent-idle")
            inject("DOWN",y);until("document.getElementById('voice-hold').classList.contains('holding')");stable("cancel-held")
            inject("MOVE",far);until("document.getElementById('voice-overlay').classList.contains('cancel-pending')");stable("cancel-long-up-pending");assertEquals(0,count("voice.cancel"));assertEquals(1,count("send"))
            inject("UP",far);until("window.__gestureCommands.filter(c=>c.op==='voice.cancel').length===1 && document.getElementById('voice-overlay').hidden")
            assertEquals(1,count("send"));assertEquals(1,count("voice.finish"));assertEquals(2,count("voice.start"));val cancelled=sample("cancel-idle");assertEquals("按住说话",cancelled.getString("label"));assertEquals("",cancelled.getString("transcript"))
            val touches=JSONArray(jsValue("window.__gestureTouches"));assertTrue(touches.length()>8);assertTrue(touches.objects().all {it.getBoolean("trusted")});assertEquals(2,touches.objects().count {it.getString("type")=="pointerdown"});assertEquals(2,touches.objects().count {it.getString("type")=="pointerup"});assertFalse(touches.objects().any {it.getString("type")=="pointercancel"})
            val injected=evidence.getJSONArray("injectorTrace").let {a->(0 until a.length()).map {a.getString(it)}.filter {it.startsWith("ACK ")}}
            assertEquals(8,injected.size)
            val downTimes=injected.map {Regex("downTime=(\\d+)").find(it)!!.groupValues[1]}
            assertEquals("Every event in the returning gesture must share one downTime",1,downTimes.take(5).distinct().size)
            assertEquals("Every event in the cancelling gesture must share one downTime",1,downTimes.drop(5).distinct().size)
            assertTrue(injected.all {it.endsWith("display=$display")})
            evidence.put("touchEvents",touches).put("commands",JSONArray(jsValue("window.__gestureCommands")))
            val after=hashes();evidence.put("afterHashes",after);assertEquals(before.toString(),after.toString());evidence.put("historyProfilesAndKeysPreserved",true).put("passed",true)
        } catch(error:Throwable) {
            evidence.put("failure",error.javaClass.simpleName+": "+error.message).put("helperStderr",stderr.toString());save();throw error
        } finally {
            // Raw input EOF asks an idle helper to cancel any held pointer. Never
            // take BufferedReader's monitor while its worker may be reading.
            descriptors?.forEach {runCatching {it.close()}};reader.shutdownNow()
            runCatching {js("document.getElementById('voice-cancel').click()")};runCatching {evidence.put("touchEvents",JSONArray(jsValue("window.__gestureTouches??[]"))).put("commands",JSONArray(jsValue("window.__gestureCommands??[]")))};evidence.put("helperStderr",stderr.toString());save()
            ins.runOnMainSync {activity.finish()};ins.waitForIdleSync()
        }
    }
}
