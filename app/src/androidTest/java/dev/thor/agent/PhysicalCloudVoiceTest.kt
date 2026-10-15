package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Explicit live service acceptance. Only known synthetic speech is used as the reference. */
@RunWith(AndroidJUnit4::class)
class PhysicalCloudVoiceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun requireLive() {org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("physicalAcceptance")=="true");assertEquals("AYN Thor",android.os.Build.MODEL)}
    private fun js(web:WebView,script:String):String {
        val latch=CountDownLatch(1);var result=""
        instrumentation.runOnMainSync {web.evaluateJavascript(script) {result=it;latch.countDown()}}
        assertTrue(latch.await(5,TimeUnit.SECONDS));return result
    }
    private fun until(seconds:Long=30,condition:()->Boolean) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<end){if(condition())return;Thread.sleep(80)};fail("Live voice acceptance timed out")}
    @Test fun nativeStreamRecognizesKnownSpeechOverDeviceNetwork() {
        requireLive()
        val profileBefore=context.getSharedPreferences("profiles",0).all.toString()
        val credentialFile=File(context.cacheDir,"physical-voice-credential")
        val vault=CredentialVault(context)
        if(credentialFile.exists()) {
            val secret=try {credentialFile.readText().trim()}finally {assertTrue(credentialFile.delete())}
            require(secret.isNotBlank() && secret.length<=8192)
            vault.put("voice.api",secret)
            check(context.getSharedPreferences("voice",0).edit().putInt("auth",0).putString("resource","volc.seedasr.sauc.duration").commit())
        }
        assertEquals("已加密保存",vault.status("voice.api"))
        val sourcePcm=File(context.cacheDir,"voice-reference.pcm").readBytes()
        val pcm=sourcePcm+ByteArray(32000)
        val metadata=File(context.cacheDir,"voice-reference.json").takeIf {it.exists()}?.readText()?.let {JSONObject(it)} ?: JSONObject()
        val reference=metadata.optString("text","请只回复语音测试成功，不要使用任何工具。")
        val expectedPhrase=metadata.optString("expectedPhrase","语音测试成功")
        val eof=CountDownLatch(1);val done=CountDownLatch(1);val packets=AtomicInteger();val closed=CountDownLatch(1)
        var index=0;val stopped=java.util.concurrent.atomic.AtomicBoolean(false)
        val source=object:PcmInput {
            override fun start() {}
            override fun read(bytes:ByteArray):Int {
                Thread.sleep(200)
                if(stopped.get())return -1
                val n=minOf(bytes.size,pcm.size-index)
                if(n>0){System.arraycopy(pcm,index,bytes,0,n);index+=n;packets.incrementAndGet();return n}
                eof.countDown();bytes.fill(0);return bytes.size
            }
            override fun stop() {stopped.set(true)}
            override fun close() {closed.countDown()}
        }
        val events=CopyOnWriteArrayList<JSONObject>();val start=android.os.SystemClock.elapsedRealtime()
        val report=obj("endpoint" to VolcAsrProtocol.ENDPOINT,"resource" to "volc.seedasr.sauc.duration","audioSource" to "known-synthetic-pcm","reference" to reference,"expectedPhrase" to expectedPhrase,"ttsProvider" to metadata.optString("provider","macOS say"),"pcmSha256" to SkillStore.digest(sourcePcm),"referenceDurationMs" to sourcePcm.size*1000/32000,"uploadedAudio" to true)
        val voice=VoiceInput(VoiceConfig(vault.readNative("voice.api"),null,null,"volc.seedasr.sauc.duration"),{source},{e->
            val item=JSONObject(e.toString()).put("elapsedMs",android.os.SystemClock.elapsedRealtime()-start);events.add(item)
            if(e.optString("phase") in setOf("complete","error","empty"))done.countDown()
        })
        try {
            voice.start();until(45) {eof.count==0L || done.count==0L};assertEquals("ASR ended before the reference completed",0L,eof.count)
            assertFalse("Must not submit before release",events.any {it.optString("phase")=="complete"})
            val releasedAt=android.os.SystemClock.elapsedRealtime()-start;voice.finish()
            assertTrue("No terminal ASR result",done.await(20,TimeUnit.SECONDS));assertTrue(closed.await(3,TimeUnit.SECONDS))
            val final=events.last();report.put("events",JSONArray(events)).put("packets",packets.get()).put("releasedAtMs",releasedAt).put("final",final)
            assertEquals("ASR should finish successfully","complete",final.getString("phase"))
            assertTrue("Recognized reference phrase missing",final.optString("submit").contains(expectedPhrase))
            assertTrue("No interim transcript",events.any {it.optString("phase")=="recording" && it.optString("text").isNotBlank()})
            assertEquals(1,events.count {it.optString("phase")=="complete"})
            assertEquals(profileBefore,context.getSharedPreferences("profiles",0).all.toString())
            report.put("firstTextMs",events.first {it.optString("text").isNotBlank()}.getLong("elapsedMs")).put("finalAfterReleaseMs",final.getLong("elapsedMs")-releasedAt).put("passed",true)
        } finally {
            voice.close();report.put("events",JSONArray(events));File(context.filesDir,"physical-cloud-voice.json").writeText(report.toString(2))
        }
    }
    @Test fun microphoneWebViewReleaseSendsRealAgentMessageAndCancelDoesNotSend() {
        requireLive()
        assertEquals("passed",context.getSharedPreferences("profiles",0).getString("probe",null))
        val args=InstrumentationRegistry.getArguments()
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay","4").toInt()).toBundle()) as MainActivity
        val web=activity.findViewById<WebView>(android.R.id.custom)
        val audio=context.getSystemService(android.media.AudioManager::class.java)
        val beforeVolume=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val report=obj("audioSource" to "device-speaker-to-physical-microphone","uploadedAudio" to true)
        val metadata=File(context.cacheDir,"voice-reference.json").takeIf {it.exists()}?.readText()?.let {JSONObject(it)} ?: JSONObject()
        report.put("ttsProvider",metadata.optString("provider","macOS say")).put("reference",metadata.optString("text","请只回复语音测试成功，不要使用任何工具。"))
        var player:android.media.MediaPlayer?=null
        try {
            until {js(web,"window.productReady===true")=="true"}
            js(web,"document.getElementById('new').click()")
            Thread.sleep(300)
            js(web,"window.__voiceEvents=[];const originalVoice=window.receiveVoiceState;window.receiveVoiceState=e=>{window.__voiceEvents.push({...e,at:Date.now()});originalVoice(e);};document.getElementById('voice-toggle').click()")
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,(audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)*0.65).toInt().coerceAtLeast(1),0)
            fun hold() {js(web,"document.getElementById('voice-hold').dispatchEvent(new PointerEvent('pointerdown',{pointerId:1,button:0,isPrimary:true,bubbles:true}));true")}
            fun release() {js(web,"document.getElementById('voice-hold').dispatchEvent(new PointerEvent('pointerup',{pointerId:1,button:0,isPrimary:true,bubbles:true}));true")}
            hold();Thread.sleep(900)
            val finished=CountDownLatch(1)
            player=android.media.MediaPlayer().apply {setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());setDataSource(File(context.cacheDir,"voice-reference.wav").path);setOnCompletionListener {finished.countDown()};prepare();start()}
            assertTrue(finished.await(20,TimeUnit.SECONDS));Thread.sleep(700)
            assertEquals("0",js(web,"document.querySelectorAll('.message.user').length"))
            release()
            until(30) {js(web,"window.__voiceEvents.some(e=>e.phase==='complete'||e.phase==='error'||e.phase==='empty')")=="true"}
            val events=JSONArray(js(web,"JSON.stringify(window.__voiceEvents)").let {org.json.JSONTokener(it).nextValue() as String})
            report.put("events",events)
            assertTrue("Physical microphone did not recognize speech",events.objects().any {it.optString("phase")=="complete" && it.optString("submit").contains("语音测试成功")})
            until(150) {js(web,"document.getElementById('active').hidden && document.querySelectorAll('.message.user').length===1 && document.querySelectorAll('.message.assistant').length===1")=="true"}
            val state=SqliteStore(context,"product.db").use {it.load()};val run=state.getJSONArray("runs").objects().last()
            assertEquals("completed",run.getString("status"))
            val messages=state.getJSONArray("messages").objects().filter {it.optString("runId")==run.getString("id")}
            assertTrue("Expected model reply missing",messages.any {it.getString("role")=="assistant" && it.optString("text").contains("语音测试成功")})
            report.put("runId",run.getString("id")).put("messages",JSONArray(messages)).put("status",run.getString("status"))
            hold();Thread.sleep(700);js(web,"document.getElementById('voice-cancel').click()")
            Thread.sleep(500)
            assertEquals("1",js(web,"document.querySelectorAll('.message.user').length"))
            report.put("cancelDidNotSend",true).put("passed",true)
        } finally {
            player?.release();audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,beforeVolume,0)
            File(context.filesDir,"physical-voice-agent.json").writeText(report.toString(2))
            instrumentation.runOnMainSync {activity.finish()};instrumentation.waitForIdleSync()
        }
    }
}
