package dev.thor.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in real ASR with generated silence, no microphone, model task or UI input. */
@RunWith(AndroidJUnit4::class)
class PhysicalVoiceSilenceTest {
    @Test fun realServiceSilenceEndsWithoutErrorOrSubmission() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        SqliteStore(context,"product.db").use {store->
            store.readableDatabase.rawQuery("SELECT payload FROM runs",null).use {rows->while(rows.moveToNext())
                assertTrue("Never interrupt ongoing work",JSONObject(rows.getString(0)).optString("status") in setOf("completed","failed","cancelled","interrupted"))
            }
        }
        val names=listOf("profiles","voice","native-credentials","display-roles")
        val before=names.associateWith {context.getSharedPreferences(it,0).all.toMap()}
        fun dbDigest():String {
            val digest=MessageDigest.getInstance("SHA-256")
            context.getDatabasePath("product.db").inputStream().use {input->val buffer=ByteArray(16384);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
            return digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
        }
        val dbBefore=dbDigest()
        val prefs=context.getSharedPreferences("voice",0);val vault=CredentialVault(context)
        val modern=prefs.getInt("auth",0)==0
        val config=VoiceConfig(if(modern)vault.readNative("voice.api") else null,prefs.getString("appId",null),
            if(!modern)vault.readNative("voice.access") else null,prefs.getString("resource","volc.bigasr.sauc.duration")!!)
        assertTrue("Reuse configured voice credentials only",if(modern)!config.apiKey.isNullOrBlank() else !config.appId.isNullOrBlank() && !config.accessKey.isNullOrBlank())
        val packets=AtomicInteger();val stopped=AtomicBoolean();val audioReady=CountDownLatch(1);val sourceClosed=CountDownLatch(1);val done=CountDownLatch(1)
        val events=CopyOnWriteArrayList<JSONObject>()
        val source=object:PcmInput {
            override fun start() {}
            override fun read(bytes:ByteArray):Int {
                Thread.sleep(200)
                if(stopped.get())return -1
                bytes.fill(0)
                if(packets.incrementAndGet()>=15)audioReady.countDown()
                return bytes.size
            }
            override fun stop() {stopped.set(true);audioReady.countDown()}
            override fun close() {sourceClosed.countDown()}
        }
        val report=obj("schemaVersion" to 1,"passed" to false,"scope" to "real cloud ASR with generated silent PCM; no microphone/UI/model execution",
            "endpoint" to VolcAsrProtocol.ENDPOINT,"resource" to config.resource,"cloudCalled" to true,"audioSource" to "generated-zero-pcm16-16000-mono","hostGameInputs" to JSONArray())
        val voice=VoiceInput(config,{source},{event->events.add(event);if(event.optString("phase") in setOf("empty","error","complete","cancelled"))done.countDown()})
        try {
            voice.start()
            assertTrue("Silent audio capture did not finish",audioReady.await(15,TimeUnit.SECONDS))
            assertTrue("No audio was captured",packets.get()>0)
            assertFalse("No terminal submission before release",events.any {it.optString("phase") in setOf("empty","complete")})
            voice.finish()
            assertTrue("No terminal ASR result",done.await(20,TimeUnit.SECONDS))
            assertTrue("Audio source not released",sourceClosed.await(3,TimeUnit.SECONDS))
            assertEquals("Silent ASR must finish normally","empty",events.last().getString("phase"))
            assertFalse("Silence must not emit an error",events.any {it.optString("phase")=="error"})
            assertFalse("Silence must not submit recognized text",events.any {!it.isNull("submit") && it.optString("submit").isNotBlank()})
            assertEquals("",events.last().optString("text"))
            report.put("passed",true)
        }finally {
            voice.close()
            val unchanged=names.associateWith {before.getValue(it)==context.getSharedPreferences(it,0).all}
            val databaseUnchanged=dbBefore==dbDigest()
            report.put("passed",report.optBoolean("passed") && unchanged.values.all {it} && databaseUnchanged)
                .put("packets",packets.get()).put("generatedAudioMs",packets.get()*200).put("preferencesUnchanged",JSONObject(unchanged)).put("databaseBytesUnchanged",databaseUnchanged)
                .put("events",JSONArray(events.map {e->obj("phase" to e.optString("phase"),"textLength" to e.optString("text").length,"hasNonblankSubmit" to (!e.isNull("submit") && e.optString("submit").isNotBlank()),"error" to if(e.isNull("error"))null else e.optString("error"))}))
            File(context.filesDir,"physical-voice-silence.json").writeText(report.toString(2))
            assertTrue("Voice/model/credential/display preferences must remain unchanged",unchanged.values.all {it})
            assertTrue("No conversation data changes permitted",databaseUnchanged)
        }
    }
}
