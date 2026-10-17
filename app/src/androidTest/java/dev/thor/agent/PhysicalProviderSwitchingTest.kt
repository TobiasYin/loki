package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.Intent
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

/** Explicit cloud acceptance. Root supplies the key through app-private cache,
 * never instrumentation arguments. Only owned synthetic conversations are removed;
 * the newly saved official provider intentionally remains available to the user.
 * No model generation wall-clock limit is added by this test. */
@RunWith(AndroidJUnit4::class)
class PhysicalProviderSwitchingTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val terminals=setOf("completed","failed","cancelled","interrupted")
    private fun field(host:ProductHost,name:String):Any=requireNotNull(ProductHost::class.java.getDeclaredField(name).apply {isAccessible=true}.get(host))
    private fun rpc(host:ProductHost,c:String,data:JSONObject):JSONObject {
        val response=host.rpc(c,obj("op" to "ui","data" to data)).get(30,TimeUnit.SECONDS)
        assertTrue("Native UI command was rejected",response.isNull("error"));return response
    }
    private fun view(host:ProductHost):JSONObject {
        val future=CompletableFuture<JSONObject>()
        (field(host,"serial") as ExecutorService).execute {try {future.complete(host.view())}catch(error:Throwable){future.completeExceptionally(error)}}
        return future.get(30,TimeUnit.SECONDS)
    }
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private fun preferenceHashes()=listOf("profiles","voice","display-roles").associateWith {name->
        File(context.applicationInfo.dataDir,"shared_prefs/$name.xml").takeIf {it.exists()}?.readBytes()?.let(::digest) ?: "absent"
    }
    private fun historyHashes():Map<String,String> =SqliteStore(context,"product.db").use {store->
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
    private fun allRunsTerminal()=SqliteStore(context,"product.db").use {store->
        store.readableDatabase.rawQuery("SELECT payload FROM runs",null).use {cursor->
            while(cursor.moveToNext())if(JSONObject(cursor.getString(0)).optString("status") !in terminals)return@use false
            true
        }
    }
    private data class Finished(val run:JSONObject,val events:List<JSONObject>,val answer:String)
    private fun readRun(runId:String):Finished=SqliteStore(context,"product.db").use {store->
        val run=store.readableDatabase.rawQuery("SELECT payload FROM runs WHERE id=?",arrayOf(runId)).use {cursor->check(cursor.moveToFirst());JSONObject(cursor.getString(0))}
        val events=store.readableDatabase.rawQuery("SELECT payload FROM events WHERE runId=? ORDER BY sequence",arrayOf(runId)).use {cursor->buildList {while(cursor.moveToNext())add(JSONObject(cursor.getString(0)))}}
        val answer=store.readableDatabase.rawQuery("SELECT payload FROM messages WHERE runId=?",arrayOf(runId)).use {cursor->buildList {while(cursor.moveToNext())add(JSONObject(cursor.getString(0)))}}
            .single {it.optString("role")=="assistant"}.optString("text")
        Finished(run,events,answer)
    }
    private fun dispatch(ready:EmulatorRuntimeTestSupport.Ready, prepared:List<JSONObject>) {
        val latch=CountDownLatch(1)
        val script=prepared.joinToString(";") {"window.runPreparedNative(JSON.parse(${JSONObject.quote(it.toString())}))"}+";true"
        ins.runOnMainSync {ready.executionWeb.evaluateJavascript(script) {latch.countDown()}}
        assertTrue("Execution document did not acknowledge dispatch",latch.await(5,TimeUnit.SECONDS))
    }

    @Test fun savedProvidersSwitchAndRunConcurrently() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        assertTrue("Never interrupt existing user tasks",allRunsTerminal())
        val store=ProviderStore(context)
        val beforeDefault=store.defaultId()
        val beforeRows=store.list().associate {it.getString("id") to it.toString()}
        val oldCredentialPrefs=HashMap(context.getSharedPreferences("native-credentials",0).all)
        val beforePrefs=preferenceHashes();val beforeHistory=historyHashes()
        val go=store.list().firstOrNull {it.getString("kind")=="opencode-go" && it.getBoolean("enabled") && store.credential(it.getString("id"))!=null}
        requireNotNull(go) {"An enabled saved OpenCode Go configuration is required"}
        val report=obj("schemaVersion" to 1,"scope" to "real saved-provider Native/Runtime acceptance","syntheticPromptsOnly" to true,
            "runs" to JSONArray(),"passed" to false,"cleanupErrors" to 0)
        fun save(){File(context.filesDir,"physical-provider-switching.json").writeText(report.toString(2))}
        var activity:MainActivity?=null;var ready:EmulatorRuntimeTestSupport.Ready?=null
        var host:ProductHost?=null;var connection:String?=null;var originalSelected:String?=null;var engineIdentity:Any?=null
        val ownedSessions=mutableListOf<String>();val ownedRuns=linkedMapOf<String,JSONObject>()
        var primary:Throwable?=null;var importedId:String?=null
        try {
            val keyFile=File(context.cacheDir,"provider-key.txt")
            val idFile=File(context.cacheDir,"provider-acceptance-config-id.txt")
            val retainedId=idFile.takeIf {it.exists()}?.readText()?.trim()?.takeIf {store.get(it)?.getString("kind")=="deepseek"}
            val configured=if(keyFile.exists()) {
                assertTrue("Key file exceeds the accepted private configuration size",keyFile.length() in 1..8193)
                val key=keyFile.readText().trim()
                val current=retainedId?.let(store::get)
                val next=current ?: obj("kind" to "deepseek","name" to "DeepSeek 官方","enabled" to true,"url" to DeepSeekCatalog.BASE_URL,
                    "model" to "deepseek-flash","protocol" to 0,"contextBudget" to 1048576,"contextBudgetText" to "","maxOutput" to 2048,
                    "maxOutputText" to "","supportsImages" to true,"headerName" to "","headerTemplate" to "")
                val result=store.save(next,key)
                idFile.writeText(result.getString("id"))
                check(keyFile.delete()) {"Private one-time key file could not be removed"}
                report.put("oneTimeKeyImported",true)
                result
            }else {
                requireNotNull(retainedId?.let(store::get)) {"Supply app-private cache/provider-key.txt once, or retain its saved acceptance config ID for reruns"}
            }
            importedId=configured.getString("id")
            report.put("deepSeekConfigId",importedId).put("keyFileRemoved",!keyFile.exists())
            assertEquals(beforeDefault,store.defaultId())
            for((id,record) in beforeRows)if(id!=importedId)assertEquals("Existing provider metadata was changed",record,store.get(id)?.toString())
            assertNotNull("Saved DeepSeek credential must remain Native",store.credential(importedId!!))
            // A new store instance must recover both provider records and credentials.
            val reopened=ProviderStore(context)
            assertNotNull(reopened.credential(go.getString("id")));assertNotNull(reopened.credential(importedId!!))
            assertEquals(beforeDefault,reopened.defaultId())
            report.put("providerStoreReopenPassed",true);save()
            if(args.getString("providerSetupOnly")=="true") {
                val model=DeepSeekCatalog(context).models().objects().first {it.getString("id")==configured.getString("model")}
                val draft=ModelConnectionTest.Draft(configured.getString("url"),model.getString("protocol"),model.getString("id"),requireNotNull(store.credential(importedId!!)))
                    .withProfile(model,configured.optString("maxOutputText"))
                val result=ModelConnectionTest.run(draft)
                report.put("connectionTest",obj("success" to result.success,"elapsedMs" to result.elapsedMs))
                assertTrue("Official DeepSeek connection test failed; inspect Native settings for the safe error",result.success)
                report.put("passed",true)
                return
            }
            ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay","4").toInt()).toBundle()) as MainActivity
            ins.runOnMainSync {activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            ready=EmulatorRuntimeTestSupport.awaitReady(activity!!)
            assertFalse("Never reload user work",ready!!.host.hasActiveRuns())
            // Saved metadata is loaded once. Composer switches below must retain this exact engine.
            ready=EmulatorRuntimeTestSupport.reloadIdle(activity!!)
            val rdy=ready!!;val h=rdy.host;host=h;val c=field(h,"connection") as String;connection=c
            originalSelected=h.selectedSession()
            ins.runOnMainSync {engineIdentity=MainActivity::class.java.getDeclaredField("attachedEngine").apply {isAccessible=true}.get(activity)}
            fun nativeResidency(label:String,idle:Boolean) {
                val result=CompletableFuture<JSONObject>()
                (field(h,"serial") as ExecutorService).execute {try {
                    val resident=field(h,"state") as JSONObject
                    val active=(field(h,"bindings") as Map<*,*>).keys.filterIsInstance<String>().toSet()
                    val counts=obj("phase" to label,"activeBindings" to active.size)
                    for(table in listOf("runs","messages","events","attachments")) {
                        val rows=resident.getJSONArray(table).objects();counts.put(table,rows.size)
                        assertTrue("Native must retain child rows only for active Runs",rows.all {row->row.optString(if(table=="runs")"id" else "runId") in active})
                        if(idle)assertEquals("Idle Native child history must be unloaded",0,rows.size)
                    }
                    if(idle)assertTrue("Idle verification requires all own Runs ended",active.isEmpty())
                    result.complete(counts)
                }catch(error:Throwable){result.completeExceptionally(error)}}
                if(!report.has("nativeResidencyChecks"))report.put("nativeResidencyChecks",JSONArray())
                report.getJSONArray("nativeResidencyChecks").put(result.get(30,TimeUnit.SECONDS));save()
            }
            fun idleRuntime(label:String) {
                val end=SystemClock.elapsedRealtime()+5_000
                while(true) {
                    val value=CompletableFuture<String>()
                    ins.runOnMainSync {rdy.executionWeb.evaluateJavascript("window.__thorRuntimeDiagnostics()") {value.complete(it)}}
                    val diagnostic=JSONObject(value.get(5,TimeUnit.SECONDS))
                    if(diagnostic.getInt("executionCount")==0 && diagnostic.getInt("residentSessionCount")==0 && diagnostic.getInt("pendingRpcCount")==0) {
                        if(!report.has("runtimeResidencyChecks"))report.put("runtimeResidencyChecks",JSONArray())
                        report.getJSONArray("runtimeResidencyChecks").put(obj("phase" to label,"executionCount" to 0,"residentSessionCount" to 0,"pendingRpcCount" to 0));save();return
                    }
                    assertTrue("Idle Runtime or RPC retained after its completed Run",SystemClock.elapsedRealtime()<end)
                    Thread.sleep(50)
                }
            }
            nativeResidency("startup",true);idleRuntime("startup")
            fun assertEngineUnchanged() {
                var current:Any?=null
                ins.runOnMainSync {current=MainActivity::class.java.getDeclaredField("attachedEngine").apply {isAccessible=true}.get(activity)}
                assertSame("Switching saved provider must not recreate the engine",engineIdentity,current)
            }
            fun newChat():String {
                rpc(h,c,obj("op" to "new"));val sid=h.selectedSession();ownedSessions.add(sid)
                for(cap in listOf("perception","screen_control","search","debug"))rpc(h,c,obj("op" to "capability","sessionId" to sid,"capability" to cap,"enabled" to false))
                rpc(h,c,obj("op" to "sessionOptions","sessionId" to sid,"selectedSkills" to JSONArray()))
                return sid
            }
            fun choose(sid:String,id:String,modelId:String?=null):JSONObject {
                h.activateProvider(id).get(30,TimeUnit.SECONDS)
                rpc(h,c,obj("op" to "select","sessionId" to sid))
                if(modelId!=null)rpc(h,c,obj("op" to "sessionOptions","sessionId" to sid,"modelId" to modelId))
                val currentView=view(h)
                val choices=currentView.getJSONArray("modelChoices").objects()
                assertTrue("Only the currently active provider belongs in the composer model picker",choices.isNotEmpty() && choices.all {it.getString("providerConfigId")==id})
                var profile=currentView.getJSONObject("modelSelection")
                assertEquals("Selected provider record did not become effective",id,profile.getString("providerConfigId"))
                val efforts=profile.getJSONArray("efforts");val effort=if((0 until efforts.length()).any {efforts.getString(it)=="off"})"off" else efforts.getString(0)
                rpc(h,c,obj("op" to "sessionOptions","sessionId" to sid,"thinkingEffort" to effort))
                profile=view(h).getJSONObject("modelSelection")
                assertTrue("Saved official provider must be immediately usable",profile.getBoolean("configured") && profile.getBoolean("providerMetadataKnown") && !profile.getBoolean("probeRequired"))
                assertEngineUnchanged();return profile
            }
            fun prepare(prompt:String):JSONObject {
                val begun=h.prepareForRunner(prompt).get(30,TimeUnit.SECONDS)
                assertTrue("Official provider begin failed",begun.has("lease"))
                val rid=begun.getJSONObject("lease").getString("runId")
                ownedRuns[rid]=begun.getJSONObject("lease")
                assertEquals(0,begun.getInt("runTimeoutMs"));assertEquals(0,begun.getJSONObject("network").getInt("timeoutMs"))
                return begun
            }
            fun awaitRun(begun:JSONObject):Finished {
                val rid=begun.getJSONObject("lease").getString("runId")
                while(true) {
                    val finished=readRun(rid)
                    if(finished.run.optString("status") in terminals && view(h).getJSONArray("activeRuns").objects().none {it.getString("id")==rid})return finished
                    Thread.sleep(100)
                }
            }
            fun validate(finished:Finished,expectedProfile:JSONObject,marker:String,kind:String,followup:Boolean):JSONObject {
                val tools=finished.events.count {it.optString("type")=="tool_intent"}
                val piReused=finished.events.any {it.optString("type")=="context" && it.getJSONObject("data").optBoolean("piSessionReused")}
                val historyRestored=finished.events.any {it.optString("type")=="context" && it.getJSONObject("data").optBoolean("historyRestored")}
                val receipts=finished.run.optJSONArray("modelTransport")?.objects().orEmpty()
                val record=obj("kind" to kind,"provider" to expectedProfile.getString("provider"),"modelId" to expectedProfile.getString("modelId"),
                    "status" to finished.run.optString("status"),"markerMatches" to finished.answer.contains(marker),"toolCalls" to tools,"piSessionReused" to piReused,"historyRestored" to historyRestored,
                    "modelRounds" to JSONArray(finished.events.filter {it.optString("type")=="model_round"}.map {it.getJSONObject("data")}),
                    "transportCount" to receipts.size,"httpStatuses" to JSONArray(receipts.map {it.optInt("status",0)}),
                    "allHttp200" to (receipts.isNotEmpty() && receipts.all {it.optInt("status")==200}))
                finished.run.optJSONObject("runtime")?.optString("error")?.takeIf {it.matches(Regex("[A-Z_]{1,64}"))}?.let {record.put("runtimeErrorCode",it)}
                report.getJSONArray("runs").put(record);save()
                assertEquals("completed",finished.run.optString("status"));assertEquals(0,tools)
                assertTrue("Synthetic marker was not retained",finished.answer.contains(marker))
                assertEquals(expectedProfile.getString("providerConfigId"),finished.run.getString("providerConfigId"))
                assertEquals(expectedProfile.getString("modelId"),finished.run.getString("modelId"))
                assertTrue("Real cloud HTTP 200 receipt is required",receipts.isNotEmpty() && receipts.all {it.optInt("status")==200})
                assertFalse("Idle Pi Agents must be released after every Run",piReused)
                if(followup)assertTrue("Follow-up must reconstruct its persisted conversation history",historyRestored)
                if(!h.hasActiveRuns()){nativeResidency(kind,true);idleRuntime(kind)}
                assertEngineUnchanged();return record
            }
            val sid=newChat();val marker="THOR_PROVIDER_MEMORY_73"
            val goId=go.getString("id")
            val goModel=args.getString("goModel") ?: go.getString("model")
            val goProfile=choose(sid,goId,goModel)
            val first=prepare("请记住本会话测试口令 $marker，只回复这个口令，不调用任何工具。")
            dispatch(rdy,listOf(first));validate(awaitRun(first),goProfile,marker,"go-initial",false)
            val deepseekProfile=choose(sid,importedId!!)
            val second=prepare("刚才用户指定的本会话测试口令是什么？只输出口令，不调用任何工具。")
            assertEquals(sid,second.getJSONObject("lease").getString("sessionId"))
            assertNotEquals(first.getJSONObject("network").getString("credentialRef"),second.getJSONObject("network").getString("credentialRef"))
            dispatch(rdy,listOf(second));validate(awaitRun(second),deepseekProfile,marker,"deepseek-followup",true)
            val goAgain=choose(sid,goId,goModel)
            val third=prepare("继续同一个会话，再输出最初用户指定的测试口令，不调用任何工具。")
            assertEquals(first.getJSONObject("network").getString("profileRef"),third.getJSONObject("network").getString("profileRef"))
            assertEquals(first.getJSONObject("network").getString("credentialRef"),third.getJSONObject("network").getString("credentialRef"))
            dispatch(rdy,listOf(third));validate(awaitRun(third),goAgain,marker,"go-followup",true)
            report.put("sameChatProviderSwitchPassed",true);save()
            val parallelGo=choose(sid,goId,goModel)
            val goParallel=prepare("请只回复 THOR_GO_PARALLEL_A，不调用工具，不输出其他内容。")
            dispatch(rdy,listOf(goParallel))
            val parallelSid=newChat()
            val parallelDeepseek=choose(parallelSid,importedId!!)
            val deepseekParallel=prepare("请只回复 THOR_DEEPSEEK_PARALLEL_B，不调用工具，不输出其他内容。")
            assertNotEquals(goParallel.getJSONObject("lease").getString("sessionId"),deepseekParallel.getJSONObject("lease").getString("sessionId"))
            val live=view(h).getJSONArray("activeRuns").objects()
            val liveIds=live.map {it.getString("id")}.toSet()
            assertTrue("Switching current provider must preserve the already running Go task",goParallel.getJSONObject("lease").getString("runId") in liveIds)
            assertTrue(deepseekParallel.getJSONObject("lease").getString("runId") in liveIds)
            nativeResidency("parallel-active",false)
            report.put("oldProviderRunPreservedDuringSwitch",true).put("twoIndependentRunsAdmitted",true);save()
            dispatch(rdy,listOf(deepseekParallel))
            val goResult=awaitRun(goParallel);val deepseekResult=awaitRun(deepseekParallel)
            validate(goResult,parallelGo,"THOR_GO_PARALLEL_A","parallel-go",true)
            validate(deepseekResult,parallelDeepseek,"THOR_DEEPSEEK_PARALLEL_B","parallel-deepseek",false)
            assertFalse("Another provider answer leaked across chats",goResult.answer.contains("THOR_DEEPSEEK_PARALLEL_B"))
            assertFalse("Another provider answer leaked across chats",deepseekResult.answer.contains("THOR_GO_PARALLEL_A"))
            fun interval(result:Finished):LongRange {
                val generating=result.events.first {it.optString("type")=="state" && it.getJSONObject("data").optString("state")=="generating"}.getLong("wallTime")
                val ended=result.events.last {it.optString("type")=="state" && it.getJSONObject("data").optString("state") in terminals}.getLong("wallTime")
                return generating..ended
            }
            val goInterval=interval(goResult);val deepseekInterval=interval(deepseekResult)
            val overlap=minOf(goInterval.last,deepseekInterval.last)-maxOf(goInterval.first,deepseekInterval.first)
            report.put("parallelGeneratingOverlapMs",overlap).put("parallelOutputsIsolated",true)
            assertTrue("Both real provider generations should overlap",overlap>0)
            assertFalse(h.hasActiveRuns());report.put("passed",true)
        }catch(error:Throwable){primary=error;report.put("failureType",error.javaClass.simpleName);throw error}
        finally {
            var cleanupFailure:Throwable?=null
            fun cleanup(block:()->Unit){try {block()}catch(error:Throwable){report.put("cleanupErrors",report.getInt("cleanupErrors")+1).put("passed",false);if(primary!=null)primary!!.addSuppressed(error) else if(cleanupFailure==null)cleanupFailure=error}}
            cleanup {
                val h=host
                if(h!=null)for((rid,lease) in ownedRuns)if(readRun(rid).run.optString("status") !in terminals) {
                    h.stopFromChat(runId=rid)
                    val end=SystemClock.elapsedRealtime()+15_000
                    while(readRun(rid).run.optString("status") !in terminals && SystemClock.elapsedRealtime()<end)Thread.sleep(100)
                    // A prepared Run may never have reached JS, so only that owned
                    // lease is explicitly ended after cancellation has settled.
                    if(readRun(rid).run.optString("status") !in terminals && connection!=null)
                        h.rpc(connection!!,obj("op" to "end","data" to lease)).get(30,TimeUnit.SECONDS)
                }
            }
            cleanup {if(host!=null && beforeDefault!=null)host!!.activateProvider(beforeDefault!!).get(30,TimeUnit.SECONDS)}
            cleanup {if(host!=null && connection!=null && originalSelected!=null)rpc(host!!,connection!!,obj("op" to "select","sessionId" to originalSelected))}
            for(sid in ownedSessions)cleanup {if(host!=null && connection!=null)rpc(host!!,connection!!,obj("op" to "delete","sessionId" to sid))}
            cleanup {assertEquals(beforeDefault,store.defaultId());for((id,record) in beforeRows)if(id!=importedId)assertEquals(record,store.get(id)?.toString());report.put("originalProvidersAndDefaultPreserved",true)}
            cleanup {val now=context.getSharedPreferences("native-credentials",0).all;assertTrue("An existing credential record changed",oldCredentialPrefs.all {(key,value)->now[key]==value});report.put("originalCredentialRecordsPreserved",true)}
            cleanup {assertEquals(beforePrefs,preferenceHashes());report.put("originalConfigurationPreserved",true)}
            cleanup {assertEquals(beforeHistory,historyHashes());report.put("originalHistoryPreserved",true)}
            cleanup {activity?.let {value->ins.runOnMainSync {value.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);value.finish()}}}
            save();if(primary==null)cleanupFailure?.let {throw it}
        }
    }
}
