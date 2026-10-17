package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Opt-in real-provider test. Only synthetic text is sent, and cache hits are
 * observed rather than required: a provider can legitimately report a miss.
 * Model generation has no added deadline. The persisted report has safe counts,
 * local labels and booleans, never credentials, transcripts or provider IDs. */
@RunWith(AndroidJUnit4::class)
class PromptCacheRuntimeAcceptanceTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val terminal=setOf("completed","failed","cancelled","interrupted")
    private fun field(host:ProductHost,name:String):Any=requireNotNull(ProductHost::class.java.getDeclaredField(name).apply {isAccessible=true}.get(host))
    private fun rpc(host:ProductHost,connection:String,data:JSONObject):JSONObject {
        val response=host.rpc(connection,obj("op" to "ui","data" to data)).get(30,TimeUnit.SECONDS)
        assertTrue("Native UI operation failed",response.isNull("error"));return response
    }
    private fun view(host:ProductHost):JSONObject {
        val result=CompletableFuture<JSONObject>()
        (field(host,"serial") as ExecutorService).execute {try {result.complete(host.view())}catch(error:Throwable){result.completeExceptionally(error)}}
        return result.get(30,TimeUnit.SECONDS)
    }
    private fun prefs():Map<String,String> =listOf("profiles","provider-configs","voice","native-credentials","display-roles").associateWith {name->
        File(context.applicationInfo.dataDir,"shared_prefs/$name.xml").takeIf {it.exists()}?.readBytes()?.let(SkillStore::digest) ?: "absent"
    }
    private fun history():Map<String,String> =SqliteStore(context,"product.db").use {store->
        listOf("sessions","runs","messages","events","attachments","metadata").associateWith {table->
            val hash=MessageDigest.getInstance("SHA-256")
            store.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid",null).use {cursor->
                while(cursor.moveToNext())for(index in 0 until cursor.columnCount) {
                    val bytes=(if(cursor.isNull(index))"<null>" else cursor.getString(index)).toByteArray(Charsets.UTF_8)
                    hash.update(bytes.size.toString().toByteArray());hash.update(':'.code.toByte());hash.update(bytes)
                }
            }
            hash.digest().joinToString("") {"%02x".format(it)}
        }
    }
    private fun noExistingWork():Boolean=SqliteStore(context,"product.db").use {store->
        store.readableDatabase.rawQuery("SELECT payload FROM runs",null).use {cursor->
            var idle=true
            while(cursor.moveToNext())if(JSONObject(cursor.getString(0)).optString("status") !in terminal)idle=false
            idle
        }
    }
    private data class Result(val run:JSONObject,val events:List<JSONObject>,val answer:String)
    private fun readRun(id:String):Result=SqliteStore(context,"product.db").use {store->
        val run=store.readableDatabase.rawQuery("SELECT payload FROM runs WHERE id=?",arrayOf(id)).use {cursor->check(cursor.moveToFirst());JSONObject(cursor.getString(0))}
        val events=store.readableDatabase.rawQuery("SELECT payload FROM events WHERE runId=? ORDER BY sequence",arrayOf(id)).use {cursor->buildList {while(cursor.moveToNext())add(JSONObject(cursor.getString(0)))}}
        val answer=store.readableDatabase.rawQuery("SELECT payload FROM messages WHERE runId=?",arrayOf(id)).use {cursor->buildList {while(cursor.moveToNext())add(JSONObject(cursor.getString(0)))}}
            .filter {it.optString("role")=="assistant"}.joinToString("") {it.optString("text")}
        Result(run,events,answer)
    }
    private fun dispatch(ready:EmulatorRuntimeTestSupport.Ready,begun:JSONObject) {
        val latch=CountDownLatch(1)
        ins.runOnMainSync {ready.executionWeb.evaluateJavascript("window.runPreparedNative(JSON.parse(${JSONObject.quote(begun.toString())}));true") {latch.countDown()}}
        assertTrue("Execution document did not acknowledge dispatch",latch.await(5,TimeUnit.SECONDS))
    }
    private fun syntheticFacts():String=buildString {
        // Every record is stable but different, avoiding a single repeated filler
        // token whose tokenization could be unexpectedly tiny.
        for(index in 1..128) {
            val token=MessageDigest.getInstance("SHA-256").digest("thor-prompt-cache-fact-$index-v1".toByteArray())
                .joinToString("") {"%02x".format(it)}
            append("FACT_").append(index.toString().padStart(3,'0')).append(" synthetic reference ").append(token).append('\n')
        }
    }
    private fun safeRounds(events:List<JSONObject>):JSONArray=JSONArray().apply {
        val counters=listOf("modelRound","requestedMaxOutputTokens","inputTokens","totalInputTokens","outputTokens","reasoningTokens","cacheReadTokens","cacheWriteTokens","uncachedInputTokens")
        for(event in events.filter {it.optString("type")=="model_round"}) {
            val source=event.getJSONObject("data");val row=JSONObject()
            for(key in counters) {
                val number=source.opt(key)
                if(number is Number && number.toLong()>=0 && number.toDouble()==number.toLong().toDouble())row.put(key,number.toLong())
                else row.put(key,JSONObject.NULL)
            }
            row.put("cacheReadReported",!row.isNull("cacheReadTokens"))
            row.put("cacheWriteReported",!row.isNull("cacheWriteTokens"))
            val stop=source.optString("stopReason")
            row.put("stopReason",if(stop in setOf("pending","stop","length","toolUse","error","aborted","deferred"))stop else "unknown")
            put(row)
        }
    }

    @Test fun stablePrefixSurvivesToolsAndThreeConversationTurns() {
        val args=InstrumentationRegistry.getArguments()
        val physical=args.getString("physicalAcceptance")=="true" && Build.MODEL=="AYN Thor"
        val emulator=args.getString("emulatorAcceptance")=="true" && Build.HARDWARE in setOf("ranchu","goldfish")
        org.junit.Assume.assumeTrue(physical || emulator)
        assertTrue("Never interrupt existing user work",noExistingWork())
        val providerStore=ProviderStore(context)
        val originalProvider=providerStore.defaultId()
        val requestedKind=args.getString("providerKind")
        require(requestedKind==null || requestedKind in setOf("deepseek","opencode-go"))
        val provider=requireNotNull(if(requestedKind==null)originalProvider?.let(providerStore::get)
            else providerStore.list().firstOrNull {it.optString("kind")==requestedKind && it.optBoolean("enabled")}) {"A saved test provider is required"}
        val originalPrefs=prefs();val originalHistory=history();val facts=syntheticFacts()
        assertTrue("Synthetic prefix must be a substantial stable context",facts.length in 8_000..15_000)
        val report=obj("schemaVersion" to 1,"scope" to "real-provider stable prompt cache acceptance","deviceKind" to if(physical)"physical" else "emulator",
            "providerKind" to provider.optString("kind"),"syntheticPromptsOnly" to true,"syntheticPrefixCharacters" to facts.length,"cacheBuildDelayMs" to 3000,
            "cacheHitRateAsserted" to false,"missingCacheCountersAreZero" to false,"runs" to JSONArray(),"passed" to false,"cleanupErrors" to 0)
        fun save(){File(context.filesDir,"prompt-cache-runtime.json").writeText(report.toString(2))}
        var activity:MainActivity?=null;var host:ProductHost?=null;var connection:String?=null;var selected:String?=null;var own:String?=null
        val ownedRuns=linkedMapOf<String,JSONObject>();var primary:Throwable?=null
        try {
            ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay",if(physical)"4" else "0").toInt()).toBundle()) as MainActivity
            ins.runOnMainSync {activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            val ready=EmulatorRuntimeTestSupport.awaitReady(activity!!);val h=ready.host;host=h
            assertFalse("Never replace or interrupt active user work",h.hasActiveRuns())
            val c=field(h,"connection") as String;connection=c;selected=h.selectedSession()
            if(provider.getString("id")!=originalProvider)h.activateProvider(provider.getString("id")).get(30,TimeUnit.SECONDS)
            rpc(h,c,obj("op" to "new"));val sid=h.selectedSession();own=sid
            for(cap in listOf("perception","screen_control","search","debug"))rpc(h,c,obj("op" to "capability","sessionId" to sid,"capability" to cap,"enabled" to false))
            rpc(h,c,obj("op" to "sessionOptions","sessionId" to sid,"selectedSkills" to JSONArray(),"modelId" to provider.getString("model")))
            val initial=view(h).getJSONObject("modelSelection")
            assertEquals(provider.getString("id"),initial.getString("providerConfigId"));assertTrue("Current saved provider must be configured",initial.getBoolean("configured"))
            val efforts=initial.getJSONArray("efforts").let {rows->(0 until rows.length()).map {rows.getString(it)}}
            rpc(h,c,obj("op" to "sessionOptions","sessionId" to sid,"thinkingEffort" to if("off" in efforts)"off" else efforts.first()))
            report.put("allDeviceCapabilitiesDisabled",true).put("currentProviderUnchanged",provider.getString("id")==originalProvider);save()
            fun active(id:String)=view(h).getJSONArray("activeRuns").objects().any {it.getString("id")==id}
            fun awaitFinished(id:String):Result {
                while(true) {
                    val value=readRun(id)
                    if(value.run.optString("status") in terminal && !active(id))return value
                    Thread.sleep(100)
                }
            }
            val prompts=listOf(
                "这是合成上下文缓存测试，以下 FACT 都是虚构的稳定参考资料，请保留在本会话上下文中。\n$facts\n请仅调用一次 agent_sleep，durationMs 为整数 200；完成后只回复 CACHE_TURN_ONE_OK。不调用其他工具，不读取 Skill，不观察设备。",
                "继续同一会话，不调用任何工具，只回复 CACHE_TURN_TWO_OK。",
                "继续同一会话，不调用任何工具，只回复 CACHE_TURN_THREE_OK。"
            )
            val markers=listOf("CACHE_TURN_ONE_OK","CACHE_TURN_TWO_OK","CACHE_TURN_THREE_OK")
            for(index in prompts.indices) {
                if(index>0)Thread.sleep(3000)
                report.put("phase","turn-${index+1}");save()
                val begun=h.prepareForRunner(prompts[index]).get(30,TimeUnit.SECONDS)
                assertTrue("Saved provider did not begin the owned task",begun.has("lease"))
                val lease=begun.getJSONObject("lease");val id=lease.getString("runId");ownedRuns[id]=lease
                assertEquals(sid,lease.getString("sessionId"))
                assertEquals("No model generation wall-clock limit",0,begun.getInt("runTimeoutMs"))
                assertEquals("No model transport wall-clock limit",0,begun.getJSONObject("network").getInt("timeoutMs"))
                val started=SystemClock.elapsedRealtime();dispatch(ready,begun);val value=awaitFinished(id)
                val contexts=value.events.filter {it.optString("type")=="context"}.map {it.getJSONObject("data")}
                val restored=contexts.any {it.optBoolean("historyRestored") && it.optInt("restoredHistoryMessages")>0}
                val noClipping=contexts.all {(it.optJSONArray("droppedTurnIds")?.length() ?: 0)==0}
                val tools=value.events.filter {it.optString("type")=="tool_intent"}
                val row=obj("turn" to index+1,"status" to value.run.optString("status"),"elapsedMs" to SystemClock.elapsedRealtime()-started,
                    "contextEvents" to contexts.size,"historyRestored" to restored,"historyClipped" to !noClipping,
                    "toolCalls" to tools.size,"completionMarkerMatches" to value.answer.contains(markers[index]),"modelRounds" to safeRounds(value.events))
                report.getJSONArray("runs").put(row);save()
                assertEquals("Owned real-provider task must complete","completed",value.run.optString("status"))
                assertTrue("Synthetic completion marker is missing",row.getBoolean("completionMarkerMatches"))
                assertTrue("Every model request must record a context event",contexts.isNotEmpty())
                assertTrue("Synthetic history must not be clipped",noClipping)
                if(index>0)assertTrue("Subsequent task must restore the same session history",restored)
                assertEquals("Only the first turn may request one timer",if(index==0)1 else 0,tools.size)
                assertTrue("No device, network or Skill tool may execute",tools.all {it.getJSONObject("data").optString("toolId")=="agent.sleep"})
                assertEquals("Expected one model round per reply plus first timer round",if(index==0)2 else 1,row.getJSONArray("modelRounds").length())
                assertEquals("Synthetic task must have no device effects",0,value.run.getJSONObject("turn").getJSONArray("effects").length())
            }
            report.put("phase","cleanup").put("passed",true)
        }catch(error:Throwable){primary=error;report.put("failureType",error.javaClass.simpleName);throw error}
        finally {
            var cleanupFailure:Throwable?=null
            fun cleanup(block:()->Unit){try {block()}catch(error:Throwable){report.put("cleanupErrors",report.getInt("cleanupErrors")+1).put("passed",false);if(primary!=null)primary!!.addSuppressed(error) else if(cleanupFailure==null)cleanupFailure=error}}
            cleanup {
                val h=host
                if(h!=null)for((id,lease) in ownedRuns)if(readRun(id).run.optString("status") !in terminal) {
                    h.stopFromChat(runId=id)
                    val end=SystemClock.elapsedRealtime()+15_000
                    while(readRun(id).run.optString("status") !in terminal && SystemClock.elapsedRealtime()<end)Thread.sleep(100)
                    if(readRun(id).run.optString("status") !in terminal && connection!=null)h.rpc(connection!!,obj("op" to "end","data" to lease)).get(30,TimeUnit.SECONDS)
                }
            }
            cleanup {if(host!=null && connection!=null && selected!=null)rpc(host!!,connection!!,obj("op" to "select","sessionId" to selected))}
            cleanup {if(host!=null && connection!=null && own!=null)rpc(host!!,connection!!,obj("op" to "delete","sessionId" to own))}
            cleanup {if(host!=null && originalProvider!=null && providerStore.defaultId()!=originalProvider)host!!.activateProvider(originalProvider).get(30,TimeUnit.SECONDS)}
            cleanup {assertEquals(originalProvider,providerStore.defaultId());assertEquals(originalPrefs,prefs());report.put("originalConfigurationPreserved",true)}
            cleanup {assertEquals(originalHistory,history());report.put("originalHistoryPreserved",true)}
            cleanup {if(host!=null)assertEquals(0,(field(host!!,"agentSleep") as AgentSleep).size)}
            cleanup {activity?.let {value->ins.runOnMainSync {value.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);value.finish()}}}
            save();if(primary==null)cleanupFailure?.let {throw it}
        }
    }
}
