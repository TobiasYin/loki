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

/** Explicit real-model acceptance; no keys are read and no original chat is used.
 * Model generation has no added deadline. Only observing tool dispatch and Stop
 * have bounded acceptance deadlines. The report contains fixed flags/counts only. */
@RunWith(AndroidJUnit4::class)
class AgentSleepRuntimeAcceptanceTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val terminal=setOf("completed","failed","cancelled","interrupted")
    private fun field(host:ProductHost,name:String):Any=requireNotNull(ProductHost::class.java.getDeclaredField(name).apply {isAccessible=true}.get(host))
    private fun rpc(host:ProductHost,connection:String,data:JSONObject):JSONObject {
        val response=host.rpc(connection,obj("op" to "ui","data" to data)).get(30,TimeUnit.SECONDS)
        assertTrue("Native UI ${data.optString("op")} returned ${response.optString("error")}",response.isNull("error"));return response
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
    private fun noExistingWork()=SqliteStore(context,"product.db").use {store->
        store.readableDatabase.rawQuery("SELECT payload FROM runs",null).use {cursor->
            while(cursor.moveToNext())if(JSONObject(cursor.getString(0)).optString("status") !in terminal)return@use false
            true
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

    @Test fun realModelWaitCompletesAndLongWaitCanBeStopped() {
        val args=InstrumentationRegistry.getArguments()
        val physical=args.getString("physicalAcceptance")=="true" && Build.MODEL=="AYN Thor"
        val emulator=args.getString("emulatorAcceptance")=="true" && Build.HARDWARE in setOf("ranchu","goldfish")
        org.junit.Assume.assumeTrue(physical || emulator)
        assertTrue("Never interrupt existing user work",noExistingWork())
        val providerStore=ProviderStore(context)
        var provisioned=false
        if(args.getString("emulatorProvisionProvider")=="true") {
            require(emulator) {"Private provider provisioning is restricted to an explicit emulator acceptance"}
            require(providerStore.list().isEmpty()) {"Provider provisioning must never overwrite an existing configuration"}
            val privateKey=File(context.cacheDir,"agent-sleep-provider-key.txt")
            require(privateKey.isFile && privateKey.length() in 1..8193) {"An app-private one-time provider key file is required"}
            providerStore.save(obj("id" to "sleep-acceptance","kind" to "deepseek","name" to "DeepSeek 官方","enabled" to true,
                "url" to DeepSeekCatalog.BASE_URL,"model" to "deepseek-flash","protocol" to 0,
                "contextBudget" to 1048576,"contextBudgetText" to "","maxOutput" to 2048,"maxOutputText" to "",
                "supportsImages" to true,"headerName" to "","headerTemplate" to ""),privateKey.readText().trim())
            check(privateKey.delete()) {"The one-time private key file could not be removed after saving"}
            provisioned=true
        }
        val originalProvider=providerStore.defaultId()
        val provider=requireNotNull(originalProvider?.let(providerStore::get)) {"A current saved provider is required"}
        val originalPrefs=prefs();val originalHistory=history()
        val report=obj("schemaVersion" to 1,"scope" to "real-model agent sleep acceptance","deviceKind" to if(physical)"physical" else "emulator","syntheticPromptsOnly" to true,
            "runs" to JSONArray(),"passed" to false,"cleanupErrors" to 0)
        fun save(){File(context.filesDir,"agent-sleep-runtime.json").writeText(report.toString(2))}
        var activity:MainActivity?=null;var host:ProductHost?=null;var connection:String?=null;var selected:String?=null;var own:String?=null
        val ownedRuns=linkedMapOf<String,JSONObject>();var primary:Throwable?=null
        try {
            ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay","4").toInt()).toBundle()) as MainActivity
            ins.runOnMainSync {activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            var ready=EmulatorRuntimeTestSupport.awaitReady(activity!!)
            if(provisioned)ready=EmulatorRuntimeTestSupport.reloadIdle(activity!!)
            val h=ready.host;host=h
            assertFalse("Never replace or interrupt active user work",h.hasActiveRuns())
            val c=field(h,"connection") as String;connection=c;selected=h.selectedSession()
            val sleeps=field(h,"agentSleep") as AgentSleep
            assertEquals("No preexisting timer may be stopped by acceptance",0,sleeps.size)
            rpc(h,c,obj("op" to "new"));val sid=h.selectedSession();own=sid
            for(cap in listOf("perception","screen_control","search","debug"))rpc(h,c,obj("op" to "capability","sessionId" to sid,"capability" to cap,"enabled" to false))
            rpc(h,c,obj("op" to "sessionOptions","sessionId" to sid,"selectedSkills" to JSONArray(),"modelId" to provider.getString("model")))
            val initial=view(h).getJSONObject("modelSelection")
            assertEquals(originalProvider,initial.getString("providerConfigId"))
            assertTrue("Current saved provider must be configured",initial.getBoolean("configured"))
            val efforts=initial.getJSONArray("efforts").let {rows->(0 until rows.length()).map {rows.getString(it)}}
            val effort=if("off" in efforts)"off" else efforts.first()
            rpc(h,c,obj("op" to "sessionOptions","sessionId" to sid,"thinkingEffort" to effort))
            report.put("allDeviceCapabilitiesDisabled",true).put("currentProviderUnchanged",true).put("phase","normal-model-request");save()
            fun prepare(prompt:String):JSONObject {
                val begun=h.prepareForRunner(prompt).get(30,TimeUnit.SECONDS)
                assertTrue("Saved provider did not begin the owned task",begun.has("lease"))
                assertEquals(sid,begun.getJSONObject("lease").getString("sessionId"))
                assertEquals("No model generation wall-clock limit",0,begun.getInt("runTimeoutMs"))
                assertEquals("No model transport wall-clock limit",0,begun.getJSONObject("network").getInt("timeoutMs"))
                ownedRuns[begun.getJSONObject("lease").getString("runId")]=begun.getJSONObject("lease")
                return begun
            }
            fun active(id:String)=view(h).getJSONArray("activeRuns").objects().any {it.getString("id")==id}
            fun awaitFinished(id:String):Result {
                while(true) {
                    val value=readRun(id)
                    if(value.run.optString("status") in terminal && !active(id))return value
                    Thread.sleep(100)
                }
            }
            fun assertOnlySleep(value:Result) {
                val intents=value.events.filter {it.optString("type")=="tool_intent"}
                assertEquals("The real model must request exactly one timer",1,intents.size)
                assertTrue("The model must not execute any device, network or skill operation",intents.all {it.getJSONObject("data").optString("toolId")=="agent.sleep"})
                val turn=value.run.getJSONObject("turn")
                assertEquals("Sleep must not create device effects",0,turn.getJSONArray("effects").length())
                assertEquals("Sleep must not acquire device capability sources",0,turn.getJSONArray("source").length())
            }
            val first=prepare("这是一项等待工具测试。请调用且仅调用一次 agent_sleep，durationMs 为整数 200，等待结束后只回复 SLEEP_OK。不要调用任何其他工具，不需要读取 Skill，不需要观察屏幕。")
            val firstId=first.getJSONObject("lease").getString("runId");dispatch(ready,first)
            val completed=awaitFinished(firstId)
            assertEquals("completed",completed.run.optString("status"));assertOnlySleep(completed)
            assertTrue("Synthetic completion marker is missing",completed.answer.contains("SLEEP_OK"))
            val result=completed.events.single {it.optString("type")=="tool_result"}.getJSONObject("data")
            assertTrue("Native timer unexpectedly returned an error",result.isNull("error"))
            assertEquals("agent-timer",result.getString("source"));assertEquals("observed",result.getString("outcome"))
            val receipt=JSONObject(result.getJSONArray("content").objects().single {it.getString("type")=="text"}.getString("text"))
            assertEquals(200L,receipt.getLong("durationMs"));assertTrue("Timer returned before the requested monotonic duration",receipt.getLong("elapsedMs")>=200)
            assertEquals(0,sleeps.size)
            assertEquals("Expected tool request then final response",2,completed.events.count {it.optString("type")=="model_round"})
            report.getJSONArray("runs").put(obj("kind" to "normal","status" to "completed","toolCalls" to 1,"nativeTimerSource" to true,
                "durationMs" to receipt.getLong("durationMs"),"elapsedMs" to receipt.getLong("elapsedMs"),"completionMarkerMatches" to true,"pendingTimersAfter" to 0))
            report.put("phase","cancel-model-request");save()
            val second=prepare("继续同一会话的第二项等待工具测试。请立刻调用且仅调用一次 agent_sleep，durationMs 为整数 60000。只有完整等待结束后才回复 WAIT_FINISHED。不要调用其他工具，不要自行缩短或分段等待。")
            val secondId=second.getJSONObject("lease").getString("runId");dispatch(ready,second)
            val dispatchDeadline=SystemClock.elapsedRealtime()+60_000
            while(true) {
                val value=readRun(secondId)
                assertTrue("Model ended before requesting the long Native wait",value.run.optString("status") !in terminal)
                if(sleeps.size==1 && value.events.any {it.optString("type")=="tool_started" && it.getJSONObject("data").optString("toolId")=="agent.sleep"})break
                assertTrue("Real model did not dispatch the long wait within acceptance observation window",SystemClock.elapsedRealtime()<dispatchDeadline)
                Thread.sleep(50)
            }
            report.put("phase","stop-native-timer").put("pendingTimersBeforeStop",1);save()
            val stoppedAt=SystemClock.elapsedRealtime();h.stopFromChat(runId=secondId)
            val stopDeadline=stoppedAt+10_000
            while(true) {
                val value=readRun(secondId)
                if(value.run.optString("status") in terminal && !active(secondId))break
                assertTrue("Stop did not promptly terminate the owned timer task",SystemClock.elapsedRealtime()<stopDeadline)
                Thread.sleep(25)
            }
            val stopElapsed=SystemClock.elapsedRealtime()-stoppedAt;val cancelled=readRun(secondId)
            assertEquals("cancelled",cancelled.run.optString("status"));assertOnlySleep(cancelled)
            assertEquals("Stop must release Native timers",0,sleeps.size)
            assertTrue("Normal cancellation must not set a page error",view(h).isNull("error"))
            assertFalse("Cancelled wait must not claim completion",cancelled.answer.contains("WAIT_FINISHED"))
            assertEquals("Stop must not start a follow-up model generation",1,cancelled.events.count {it.optString("type")=="model_round"})
            report.getJSONArray("runs").put(obj("kind" to "stop","status" to "cancelled","toolCalls" to 1,"stopElapsedMs" to stopElapsed,"pendingTimersAfter" to 0))
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
            cleanup {assertEquals(originalProvider,providerStore.defaultId());assertEquals(originalPrefs,prefs());report.put("originalConfigurationPreserved",true)}
            cleanup {assertEquals(originalHistory,history());report.put("originalHistoryPreserved",true)}
            cleanup {if(host!=null)assertEquals(0,(field(host!!,"agentSleep") as AgentSleep).size)}
            cleanup {activity?.let {value->ins.runOnMainSync {value.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);value.finish()}}}
            save();if(primary==null)cleanupFailure?.let {throw it}
        }
    }
}
