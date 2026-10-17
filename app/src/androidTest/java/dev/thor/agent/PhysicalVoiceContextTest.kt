package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.Snapshots
import dev.thor.tools.diagnostic.ThorAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Explicit connected-device acceptance. The fixture messages below are synthetic,
 * not Agent replies. No model Run, user microphone, screen input or Agent submit.
 * Reads the saved voice credentials/configuration without modifying them.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalVoiceContextTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private fun field(host:ProductHost,name:String):Any = requireNotNull(ProductHost::class.java.getDeclaredField(name).apply {isAccessible=true}.get(host))
    private fun rpc(host:ProductHost,c:String,op:String,data:JSONObject):JSONObject {
        val result=host.rpc(c,obj("op" to op,"data" to data)).get(30,TimeUnit.SECONDS)
        assertTrue("Native fixture RPC rejected",result.isNull("error"));return result
    }
    private fun prefs():Map<String,String> = listOf("profiles","voice","native-credentials","display-roles").associateWith {name->
        File(context.applicationInfo.dataDir,"shared_prefs/$name.xml").takeIf {it.exists()}?.readBytes()?.let(SkillStore::digest) ?: "absent"
    }
    private fun contextJson(value:VoiceRecognitionContext?):JSONObject = JSONObject(requireNotNull(value).serialize())
    private fun texts(value:JSONObject)=value.getJSONArray("context_data").objects().map {it.getString("text")}
    private fun setPerception(host:ProductHost,c:String,sid:String,on:Boolean) {
        rpc(host,c,"ui",obj("op" to "capability","sessionId" to sid,"capability" to "perception","enabled" to on))
    }
    /** The nullable runId is legal in messages; session FK parents are created by
     * ui.new. Only this test's session is modified, under the host's serial owner.
     * This deliberately creates neither fake model runs nor synthetic tool events.
     */
    private fun insertSyntheticMessages(host:ProductHost,sid:String,messages:List<Pair<String,String>>) {
        val result=CompletableFuture<Unit>()
        (field(host,"serial") as ExecutorService).execute {
            try {
                check(!host.hasActiveRuns() && host.selectedSession()==sid)
                val state=field(host,"state") as JSONObject
                check(state.getJSONArray("sessions").objects().any {it.getString("id")==sid})
                val rows=messages.map {(role,text)->obj("id" to UUID.randomUUID().toString(),"sessionId" to sid,"runId" to null,
                    "role" to role,"text" to text,"attachments" to JSONArray(),"status" to "complete")}
                val db=(field(host,"store") as SqliteStore).writableDatabase
                db.beginTransaction()
                try {
                    rows.forEach {row->db.insertOrThrow("messages",null,ContentValues().apply {
                        put("id",row.getString("id"));put("sessionId",sid);putNull("runId");put("payload",row.toString())
                    })}
                    db.setTransactionSuccessful()
                }finally {db.endTransaction()}
                rows.forEach {state.getJSONArray("messages").put(it)}
                result.complete(Unit)
            }catch(t:Throwable){result.completeExceptionally(t)}
        }
        result.get(30,TimeUnit.SECONDS)
    }
    private fun rejected(host:ProductHost,sid:String):Boolean = try {
        host.requestVoiceRecognitionContext(sid).get(5,TimeUnit.SECONDS);false
    }catch(_:ExecutionException){true}

    @Test fun savedVoiceUsesRecentSessionAndUpperAppHintsWithKnownSyntheticAudio() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",Build.MODEL)
        val before=prefs()
        val report=obj("schemaVersion" to 1,"fixture" to "volc-voice-03-game-mixed","audioSource" to "known-synthetic-pcm",
            "modelCalled" to false,"agentSubmitted" to false,"microphoneUsed" to false,"passed" to false,"cleanupErrors" to 0)
        val own=mutableListOf<String>()
        var activity:MainActivity?=null;var host:ProductHost?=null;var connection:String?=null;var selected:String?=null
        var primary:Throwable?=null;var originalRunCount=-1
        try {
            // Abort before starting an Activity if a user Run is in progress.
            SqliteStore(context,"product.db").use {store->
                store.readableDatabase.rawQuery("SELECT payload FROM runs",null).use {cursor->
                    originalRunCount=cursor.count
                    while(cursor.moveToNext())assertTrue("Never interrupt user work",JSONObject(cursor.getString(0)).optString("status") in setOf("completed","failed","cancelled","interrupted"))
                }
            }
            ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay","4").toInt()).toBundle()) as MainActivity
            val h=EmulatorRuntimeTestSupport.awaitReady(activity!!).host;host=h;selected=h.selectedSession()
            assertFalse("Never interrupt user work",h.hasActiveRuns())
            val c=field(h,"connection") as String;connection=c
            rpc(h,c,"ui",obj("op" to "new"));val sid=h.selectedSession();own.add(sid)
            insertSyntheticMessages(h,sid,listOf(
                "user" to "请查看星露谷物语攻略，这是独立语音上下文测试。",
                "assistant" to "这是合成测试消息，尚未执行任何攻略操作。",
                "user" to "接下来打开 Skill 管理，检查 Wi-Fi 和 USB 连接状态。",
                "assistant" to "好的，继续核对星露谷物语、Skill、Wi-Fi 和 USB 这些词。",
            ))
            rpc(h,c,"ui",obj("op" to "new"));val other=h.selectedSession();own.add(other)
            insertSyntheticMessages(h,other,listOf("user" to "独立隔离样本红宝石飞船，不应出现在其他会话。"))
            assertTrue("Unselected session must not be exported",rejected(h,sid))
            val otherText=texts(contextJson(h.requestVoiceRecognitionContext(other).get(5,TimeUnit.SECONDS)))
            assertTrue(otherText.any {it.contains("红宝石飞船")});assertFalse(otherText.any {it.contains("星露谷物语")})
            rpc(h,c,"ui",obj("op" to "select","sessionId" to sid))
            assertTrue("Unknown session must be rejected",rejected(h,"voice-context-missing-${UUID.randomUUID()}"))
            setPerception(h,c,sid,false)
            val withoutScene=contextJson(h.requestVoiceRecognitionContext(sid).get(5,TimeUnit.SECONDS))
            assertEquals(4,texts(withoutScene).size)
            assertFalse(withoutScene.has("hotwords"))
            assertFalse(texts(withoutScene).any {it.contains("当前上屏应用：") || it.contains("上屏窗口标题：") || it.contains("应用包名：")})
            assertFalse(texts(withoutScene).any {it.contains("红宝石飞船")})
            assertTrue(texts(withoutScene).first().startsWith("好的，继续核对"))
            assertEquals(listOf("bot","user","bot","user"),withoutScene.getJSONArray("context_data").objects().map {it.getString("speaker")})
            report.put("recentTwoTurns",true).put("newestFirst",true).put("sessionIsolation",true).put("unselectedSessionRejected",true)
                .put("unknownSessionRejected",true).put("perceptionOffOmitsAppAndWindow",true)
            setPerception(h,c,sid,true)
            val serviceDeadline=SystemClock.elapsedRealtime()+30_000
            while(ThorAccessibilityService.instance==null && SystemClock.elapsedRealtime()<serviceDeadline)Thread.sleep(100)
            report.put("accessibilityConnected",ThorAccessibilityService.instance!=null).put("accessibilitySetup",h.accessibilitySetupStatus())
            val withScene=contextJson(h.requestVoiceRecognitionContext(sid).get(5,TimeUnit.SECONDS))
            val scene=texts(withScene).first()
            val upper=(field(h,"snapshots") as Snapshots).selected("upper").id
            val service=requireNotNull(ThorAccessibilityService.instance)
            val target=service.target(upper)
            report.put("upperDiagnostic",obj("display" to upper,"validity" to target.validity,"packagePresent" to !target.packageName.isNullOrBlank(),
                "hasVisibleClip" to (target.touchableBounds?.let {it.left<it.right && it.top<it.bottom}==true),
                "presentation" to JSONArray(service.presentationDiagnostics())))
            assertTrue("Calibrated upper foreground metadata unavailable",scene.contains("应用包名："))
            assertEquals("valid",target.validity)
            val pkg=requireNotNull(target.packageName)
            assertTrue("ASR app hint must match the current upper app",scene.contains("应用包名：$pkg"))
            val title=service.foregroundWindowTitle(upper,pkg)
            // A title equal to the application name is intentionally deduplicated.
            // Only compare a stable, short title here; long/unusual titles are
            // bounded by the separate pure formatter tests.
            val appLabel=runCatching {context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg,0)).toString()}.getOrNull()
            val compareTitle=!title.isNullOrBlank() && !title.equals(appLabel,ignoreCase=true) && title.toByteArray(Charsets.UTF_8).size<=96 && title.none {Character.isISOControl(it.code)}
            if(compareTitle)assertTrue("Distinct upper window title missing",scene.contains("上屏窗口标题：$title"))
            report.put("upperAppMatchesObservation",true).put("windowTitleAvailable",!title.isNullOrBlank())
                .put("distinctWindowTitleIncluded",scene.contains("上屏窗口标题：")).put("windowTitleCompared",compareTitle).put("syntheticMessageCount",4)
            assertFalse(texts(withScene).any {it.contains("红宝石飞船")})

            // Exact fixture hash prevents accidentally uploading arbitrary cache audio.
            val pcm=File(context.cacheDir,"voice-context-reference.pcm").readBytes()
            assertEquals("f4b3ce58f4bb9087aac175ccb96aec97c2406ad60bac840bfb759108178f2787",SkillStore.digest(pcm))
            val samples=pcm+ByteArray(32000);val eof=CountDownLatch(1);val done=CountDownLatch(1);val closed=CountDownLatch(1)
            val stopped=AtomicBoolean(false);val providerCalls=AtomicInteger();val packets=AtomicInteger()
            val phases=CopyOnWriteArrayList<String>();val interim=AtomicBoolean(false);val phrase=AtomicBoolean(false)
            var index=0;var providerMs=0L;var contextBytes=0;var firstTextMs=-1L
            val started=SystemClock.elapsedRealtime()
            val source=object:PcmInput {
                override fun start() {}
                override fun read(bytes:ByteArray):Int {
                    Thread.sleep(200);if(stopped.get())return -1
                    val n=minOf(bytes.size,samples.size-index)
                    if(n>0){System.arraycopy(samples,index,bytes,0,n);index+=n;packets.incrementAndGet();return n}
                    eof.countDown();bytes.fill(0);return bytes.size
                }
                override fun stop(){stopped.set(true)}
                override fun close(){closed.countDown()}
            }
            val voicePrefs=context.getSharedPreferences("voice",0);val vault=CredentialVault(context)
            val auth=voicePrefs.getInt("auth",0)
            val config=VoiceConfig(if(auth==0)vault.readNative("voice.api") else null,voicePrefs.getString("appId",null),
                if(auth==1)vault.readNative("voice.access") else null,voicePrefs.getString("resource","volc.bigasr.sauc.duration")!!)
            val voice=VoiceInput(config,{source},{event->
                val phase=event.getString("phase");phases.add(phase)
                if(event.optString("text").isNotBlank() && firstTextMs<0)firstTextMs=SystemClock.elapsedRealtime()-started
                if(phase=="recording" && event.optString("text").isNotBlank())interim.set(true)
                if(phase=="complete")phrase.set(event.optString("submit").contains("攻略"))
                if(phase in setOf("complete","error","empty"))done.countDown()
            },recognitionContextProvider={
                providerCalls.incrementAndGet();val at=SystemClock.elapsedRealtime()
                val hints=h.requestVoiceRecognitionContext(sid).get(250,TimeUnit.MILLISECONDS)
                providerMs=SystemClock.elapsedRealtime()-at
                contextBytes=requireNotNull(hints).serialize().toByteArray(Charsets.UTF_8).size
                check(texts(contextJson(hints)).any {it.contains("星露谷物语")})
                hints
            })
            try {
                voice.start()
                val end=SystemClock.elapsedRealtime()+45_000
                while(eof.count!=0L && done.count!=0L && SystemClock.elapsedRealtime()<end)Thread.sleep(40)
                assertEquals("ASR ended before the synthetic source completed",0L,eof.count)
                assertFalse("No submission before release",phases.contains("complete"))
                val release=SystemClock.elapsedRealtime();voice.finish()
                assertTrue("No final ASR result",done.await(20,TimeUnit.SECONDS));assertTrue(closed.await(3,TimeUnit.SECONDS))
                assertEquals("Context provider must resolve once",1,providerCalls.get());assertTrue("Provider context must be present",contextBytes>0)
                assertEquals("complete",phases.last());assertEquals(1,phases.count {it=="complete"})
                assertTrue("Known phrase not recognized",phrase.get());assertTrue("Interim results missing",interim.get())
                report.put("cloudVoicePassed",true).put("contextProviderCalls",providerCalls.get()).put("contextLookupMs",providerMs)
                    .put("contextBytes",contextBytes).put("packets",packets.get()).put("firstTextMs",firstTextMs)
                    .put("finalAfterReleaseMs",SystemClock.elapsedRealtime()-release).put("interimReceived",interim.get())
                    .put("recognizedKnownPhrase",phrase.get()).put("singleCompletion",true).put("passed",true)
            }finally {voice.close()}
            assertFalse("Voice must not create any Agent Run",h.hasActiveRuns())
        }catch(t:Throwable){primary=t;report.put("passed",false).put("failureType",t.javaClass.simpleName);throw t}
        finally {
            var cleanupFailure:Throwable?=null
            fun cleanup(block:()->Unit){try{block()}catch(t:Throwable){report.put("cleanupErrors",report.getInt("cleanupErrors")+1).put("passed",false);if(primary!=null)primary!!.addSuppressed(t) else if(cleanupFailure==null)cleanupFailure=t else cleanupFailure!!.addSuppressed(t)}}
            cleanup {if(host!=null && connection!=null && selected!=null)rpc(host!!,connection!!,"ui",obj("op" to "select","sessionId" to selected))}
            own.forEach {sid->cleanup {if(host!=null && connection!=null)rpc(host!!,connection!!,"ui",obj("op" to "delete","sessionId" to sid))}}
            cleanup {if(originalRunCount>=0)SqliteStore(context,"product.db").use {store->store.readableDatabase.rawQuery("SELECT COUNT(*) FROM runs",null).use {cursor->
                assertTrue(cursor.moveToFirst());assertEquals("Voice acceptance must not create model Runs",originalRunCount,cursor.getInt(0));report.put("runCountUnchanged",true)
            }}}
            cleanup {assertEquals("Saved configuration must remain unchanged",before,prefs());report.put("configurationPreserved",true)}
            cleanup {activity?.let {a->ins.runOnMainSync {a.finish()}}}
            File(context.filesDir,"physical-voice-context.json").writeText(report.toString(2))
            if(primary==null)cleanupFailure?.let {throw it}
        }
    }
}
