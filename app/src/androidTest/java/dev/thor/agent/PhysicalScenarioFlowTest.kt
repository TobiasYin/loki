package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.webkit.WebView
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
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real saved Go profile -> normal Pi/product authority -> independent seeded Android scenes.
 * No model timeout, manual calibration, history reset, injected success or hidden-answer prompt. */
@RunWith(AndroidJUnit4::class)
class PhysicalScenarioFlowTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val uri=Uri.parse("content://dev.thor.inputlab.scenario")
    private val reader by lazy {SqliteStore(context,"product.db")}
    private val scenes=listOf("settings","game","news","article")
    private fun until(seconds: Int=30,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+seconds*1000L
        while(SystemClock.elapsedRealtime()<end) {if(condition())return;Thread.sleep(100)}
        fail("Scenario setup did not become ready")
    }
    private fun js(web: WebView,script: String): String {
        val latch=CountDownLatch(1);var result=""
        instrumentation.runOnMainSync {web.evaluateJavascript(script){result=it;latch.countDown()}}
        assertTrue("WebView did not acknowledge test setup",latch.await(5,TimeUnit.SECONDS));return result
    }
    private fun ui(web: WebView,command: JSONObject) {
        js(web,"ThorUI.postMessage(${JSONObject.quote(command.toString())});true")
    }
    private fun receiver(method: String,args: Bundle): JSONObject {
        val response=context.contentResolver.call(uri,method,null,args) ?: error("Scenario receiver missing")
        return obj("lease" to response.getString("lease"),"snapshot" to JSONObject(response.getString("snapshot") ?: error("Scenario snapshot missing")))
    }
    private fun <T> retryRead(block:()->T):T {
        val end=SystemClock.elapsedRealtime()+40000
        while(true)try{return block()}catch(e:android.database.sqlite.SQLiteDatabaseLockedException){
            if(SystemClock.elapsedRealtime()>=end)throw e
            Thread.sleep(100)
        }
    }
    private fun readDbRows(table: String,runId: String?=null): List<JSONObject> {
        require(table in setOf("sessions","runs","messages","events"))
        return retryRead { reader.readableDatabase.let {db->
            val where=if(runId==null)"" else if(table=="runs")" WHERE id=?" else " WHERE runId=?"
            db.rawQuery("SELECT payload FROM $table$where ORDER BY ${if(table=="events")"sequence" else "rowid"}",runId?.let {arrayOf(it)}).use {cursor->
                buildList {while(cursor.moveToNext())add(JSONObject(cursor.getString(0)))}
            }
        } }
    }
    private fun historyIds(): Map<String,Set<String>> = retryRead { reader.readableDatabase.let {db->
        listOf("sessions","runs","messages","events","attachments").associateWith {table->
            db.rawQuery("SELECT id FROM $table",null).use {cursor->buildSet {while(cursor.moveToNext())add(cursor.getString(0))}}
        }
    }
    }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun collect(snapshot: JSONObject,scene: String,record: JSONObject,automation: UiAutomation) {
        val attempt=snapshot.getString("attemptId");require(java.util.UUID.fromString(attempt).toString()==attempt)
        File(context.filesDir,"physical-scenario-$scene-report.json").writeText(snapshot.toString(2))
        val evidence=snapshot.getJSONObject("evidence")
        val path="scenario-reports/$attempt/events.jsonl"
        assertEquals(path,evidence.getString("relativePath"))
        val descriptor=automation.executeShellCommand("run-as dev.thor.inputlab cat files/$path")
        val raw=ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {input->
            val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {val count=input.read(buffer);if(count<0)break;assertTrue("Scenario raw log exceeds receiver-sized collection budget",output.size()+count<=32*1024*1024);output.write(buffer,0,count)}
            output.toByteArray()
        }
        File(context.filesDir,"physical-scenario-$scene-events.jsonl").writeBytes(raw)
        val prefix=evidence.getLong("eventPrefixBytes")
        assertTrue("Invalid scenario event prefix length",prefix in 1..raw.size.toLong())
        val hash=sha(raw.copyOf(prefix.toInt()))
        record.put("attemptId",attempt).put("eventPrefixBytes",prefix).put("eventPrefixSha256",hash).put("rawBytes",raw.size)
        assertEquals(evidence.getString("eventPrefixSha256"),hash)
        assertEquals("The sealed journal must be the entire collected file",prefix,raw.size.toLong())
        val rows=raw.toString(Charsets.UTF_8).lineSequence().filter {it.isNotBlank()}.map {JSONObject(it)}.toList()
        assertEquals(evidence.getLong("eventCount"),rows.size.toLong())
        for((index,row) in rows.withIndex()) {
            assertEquals(index+1L,row.getLong("sequence"))
            for(key in listOf("attemptId","testRunId","agentRunId","evidenceMode"))assertEquals("Raw event belongs to another attempt: $key",snapshot.getString(key),row.getString(key))
        }
        if(scene=="settings" && snapshot.getJSONObject("state").getBoolean("settingsSaved")) {
            val savedPath="scenario-reports/$attempt/saved-settings.json"
            val savedDescriptor=automation.executeShellCommand("run-as dev.thor.inputlab cat files/$savedPath")
            val saved=ParcelFileDescriptor.AutoCloseInputStream(savedDescriptor).use {it.readBytes()}
            assertTrue("Saved settings must be a small independent file",saved.size in 1..4096)
            File(context.filesDir,"physical-scenario-settings-saved.json").writeBytes(saved)
            val savedValues=JSONObject(saved.toString(Charsets.UTF_8));val desired=snapshot.getJSONObject("expected").getJSONObject("settingsValues")
            for(key in desired.keys())assertEquals("Wrong persisted setting: $key",desired.get(key),savedValues.get(key))
            val persisted=rows.last {it.optString("eventKind")=="settings_persisted"}.getJSONObject("data")
            assertEquals(savedPath,persisted.getString("relativePath"));assertEquals(sha(saved),persisted.getString("sha256"))
            record.put("persistedSettingsReadbackVerified",true).put("persistedSettingsSha256",sha(saved))
        }
        assertTrue("Receiver must observe Android input dispatch",rows.any {it.optString("recordOrigin")=="android_dispatch"})
        assertEquals(record.getString("runId"),snapshot.getString("agentRunId"))
        assertEquals(record.getString("testRunId"),snapshot.getString("testRunId"))
        assertEquals(scene,snapshot.getString("scene"));assertEquals(record.getLong("seed"),snapshot.getLong("seed"))
        assertTrue("Only sealed receiver evidence is final",evidence.getBoolean("sealed"))
        assertFalse("Receiver journal is incomplete",evidence.getBoolean("incomplete"))
        assertEquals(0L,evidence.getLong("droppedSamples"))
        assertTrue("Receiver must record real scene events",evidence.getLong("eventCount")>0)
        assertEquals("agent_e2e",snapshot.getString("evidenceMode"))
        assertFalse("Receiver must not manufacture model answer verification",snapshot.getBoolean("agentAnswerVerified"))
        assertFalse("Android events are not authenticated input-sender evidence",snapshot.getBoolean("systemInjectionProven"))
    }
    private fun prompt(scene: String,description: String)=description+when(scene) {
        "settings" -> " 请通过上屏实际界面操作。优先读取可见 UI 树辨认设置项；滑块必须真实拖动，不能单击刻度替代。每次页面变化后重新观察，保存后回读核对，最后按自然字段格式简短报告：模式：实际模式；夜间阅读：开启或关闭；音量：实际百分比%。"
        "game" -> " 请仅通过实时截图辨认游戏提示与目标，不使用 UI 树。按每一步的新提示操作，避开相似干扰项，每次点击后重新截图确认，再进行下一步。"
        "news" -> " 请通过 UI 树查找目标，在列表中实际滚动到它可见后再打开，不能点击离屏位置或打开相似条目。每次滚动后重新观察，打开后读取任务要求的字段并简短回答。"
        else -> " 请在上屏实际滚动到长文末尾，确认文末核对码和文章结束文字整块内容都完整进入可见区域；如果只露出顶部请继续滚动。不能只读取离屏 UI 节点当作已经翻到底。请报告文首标识与文末验证码。"
    }
    private fun verify(scene: String,snapshot: JSONObject,answer: String,tools: List<JSONObject>,record: JSONObject) {
        val state=snapshot.getJSONObject("state");val expected=snapshot.getJSONObject("expected")
        val success=tools.filter {it.isNull("error")}
        fun count(tool: String)=success.count {it.getString("toolId")==tool}
        record.put("receiverState",state).put("receiverCompleted",snapshot.getBoolean("completed"))
            .put("toolCounts",JSONObject(success.groupingBy {it.getString("toolId")}.eachCount()))
        assertTrue("Independent receiver scene was not completed",snapshot.getBoolean("completed"))
        assertTrue("The model must return an actual final answer",answer.isNotBlank())
        when(scene) {
            "settings" -> {
                assertTrue(state.getBoolean("settingsSaved"))
                val desired=expected.getJSONObject("settingsValues");val actual=state.getJSONObject("settingsValues")
                for(key in desired.keys())assertEquals("Wrong saved setting: $key",desired.get(key),actual.get(key))
                for(key in listOf("settingsDragCount","settingsSaveCount","settingNavigationCount"))assertTrue("Missing real settings step: $key",state.getInt(key)>0)
                val normalized=answer.replace("**", "").replace("：", ":").replace(Regex("\\s+"), "")
                assertTrue("Final answer must report the saved mode",normalized.contains("模式:${desired.getString("mode")}"))
                assertTrue("Final answer must report the saved night-reading state",normalized.contains("夜间阅读:${if(desired.getBoolean("enabled"))"开启" else "关闭"}"))
                assertTrue("Final answer must report the saved volume",normalized.contains("音量:${desired.getInt("volume")}%"))
                assertTrue("Settings path must inspect the UI tree",count("perception.ui_tree")>0)
                assertTrue("Slider must use the real drag tool",count("input.drag")>0)
                assertTrue("Settings task must perform multiple real actions",success.count {it.getString("toolId").startsWith("input.")}>=3)
            }
            "game" -> {
                val steps=expected.getJSONArray("gameTargets").length()
                assertEquals(steps,state.getInt("gameStep"));assertEquals(0,state.getInt("gameWrong"))
                assertTrue("Every changing game target needs a fresh screenshot",count("perception.capture_screen")>=steps+1)
                assertTrue(count("input.tap")>=steps)
                assertFalse("The game path is explicitly visual",tools.any {it.getString("toolId")=="perception.ui_tree"})
                assertTrue("Final answer must read the victory-screen passphrase",answer.contains(expected.getString("gameResult")))
            }
            "news" -> {
                assertEquals(expected.getString("newsTargetId"),state.getString("openedArticleId"))
                assertEquals(1,state.getInt("articleOpenCount"))
                assertTrue("Finding an offscreen node is not a real scroll",state.getInt("listScrollY")>0)
                assertTrue(state.getInt("newsListScrollCount")>0)
                assertTrue(count("perception.ui_tree")>0 && count("input.scroll")>0 && count("input.tap")>0)
                assertTrue("The answer must contain the field read inside the correct article",answer.contains(expected.getString("newsCode")))
            }
            "article" -> {
                assertEquals("long-article",state.getString("openedArticleId"));assertTrue(state.getInt("detailScrollCount")>0)
                assertTrue(state.getInt("detailScrollY")>0);assertTrue(state.getBoolean("footerVisible"))
                assertTrue("Long article must actually scroll",count("input.scroll")>0)
                assertTrue("Wrong or missing article header field",answer.contains(expected.getString("articleTopCode")))
                assertTrue("Wrong or missing footer field",answer.contains(expected.getString("articleBottomCode")))
            }
        }
        record.put("testHarnessAnswerAndFlowVerified",true)
    }

    @Test fun liveModelCompletesIndependentMultiStepScreenTasks() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit connected real-model acceptance required",args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val profile=context.getSharedPreferences("profiles",0)
        assertEquals("passed",profile.getString("probe",null));assertEquals("deepseek-v4.1-flash",profile.getString("model",null))
        assertEquals("https://opencode.ai/zen/go/v1",profile.getString("url",null))
        val profileBefore=profile.all.toMap();val credentials=context.getSharedPreferences("native-credentials",0);val credentialsBefore=credentials.all.toMap()
        assertTrue("Do not launch over a pre-existing user task",readDbRows("runs").all {it.optString("status") in setOf("completed","failed","cancelled","interrupted")})
        val before=historyIds();val originalSession=readDbRows("sessions").firstOrNull()?.getString("id")
        val upper=args.getString("labDisplay")!!.toInt();val lower=args.getString("chatDisplay")!!.toInt();assertNotEquals(upper,lower)
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(lower).toBundle()) as MainActivity
        instrumentation.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val web=activity.findViewById<WebView>(android.R.id.custom)
        val host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val snapshots=ProductHost::class.java.getDeclaredField("snapshots").apply {isAccessible=true}.get(host) as Snapshots
        val evidence=obj("schemaVersion" to 1,"evidenceMode" to "agent_e2e","model" to "deepseek-v4.1-flash","service" to "live OpenCode Go Responses",
            "modelTimeoutMs" to 0,"upperDisplay" to upper,"chatDisplay" to lower,"cases" to JSONArray(),"passed" to false)
        val failures=mutableListOf<String>()
        val selectedBefore=args.getString("restoreSession") ?: host.selectedSession()
        try {
            until {js(web,"window.productReady === true && typeof window.runPreparedNative === 'function' && !document.getElementById('new').disabled")=="true"}
            assertFalse("Do not interrupt an existing user task",host.hasActiveRuns())
            until(40) {ThorAccessibilityService.instance!=null && host.accessibilitySetupStatus().contains("已通过 Shizuku")}
            assertEquals("Normal startup must resolve upper display roles",upper,snapshots.selected().id)
            val chosen=scenes.filter {args.getString("scene")==null || args.getString("scene")==it}
            assertTrue("Unknown scene filter",chosen.isNotEmpty())
            val seeds=mutableSetOf<Long>()
            for(scene in chosen) {
                val seed=(java.util.UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).also {assertTrue(seeds.add(it))}
                val record=obj("scene" to scene,"seed" to seed,"passed" to false);evidence.getJSONArray("cases").put(record)
                var sessionId: String?=null;var runId: String?=null;var receiverArgs: Bundle?=null;var sealed=false
                try {
                    val description=receiver("describe",Bundle().apply {putInt("version",1);putString("scene",scene);putLong("seed",seed)}).getJSONObject("snapshot")
                    val task=prompt(scene,description.getString("taskPrompt"))
                    assertFalse(task.contains("content://dev.thor.inputlab"));assertFalse(task.contains(seed.toString()))
                    val previous=host.selectedSession();ui(web,obj("op" to "new"));until {host.selectedSession()!=previous}
                    sessionId=host.selectedSession();record.put("sessionId",sessionId).put("prompt",task)
                    ui(web,obj("op" to "sessionOptions","sessionId" to sessionId,"modelId" to "deepseek-v4.1-flash","thinkingEffort" to "low","selectedSkills" to JSONArray()))
                    for((cap,enabled) in mapOf("perception" to true,"screen_control" to true,"search" to false,"debug" to false))ui(web,obj("op" to "capability","sessionId" to sessionId,"capability" to cap,"enabled" to enabled))
                    until {
                        val session=readDbRows("sessions").single {it.getString("id")==sessionId}
                        val c=session.getJSONObject("capabilities")
                        session.optString("modelId")=="deepseek-v4.1-flash" && session.optString("thinkingEffort")=="low" && c.getBoolean("perception") && c.getBoolean("screen_control") && !c.getBoolean("search") && !c.getBoolean("debug")
                    }
                    val begun=host.prepareForRunner(task).get(10,TimeUnit.SECONDS)
                    assertEquals(0,begun.getInt("runTimeoutMs"));assertEquals(0,begun.getJSONObject("network").getInt("timeoutMs"))
                    assertEquals("Each seeded case must start without unrelated conversation history",0,begun.getJSONArray("history").length())
                    assertEquals("Model must execute the configured scene session",sessionId,begun.getJSONObject("lease").getString("sessionId"))
                    runId=begun.getJSONObject("lease").getString("runId");record.put("runId",runId)
                    context.startActivity(Intent().setComponent(ComponentName("dev.thor.inputlab","dev.thor.inputlab.ScenarioActivity"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),ActivityOptions.makeBasic().setLaunchDisplayId(upper).toBundle())
                    Thread.sleep(800)
                    val testRun="scenario-${id()}"
                    record.put("testRunId",testRun)
                    val setup=receiver("prepare",Bundle().apply {putInt("version",1);putString("scene",scene);putLong("seed",seed);putString("testRunId",testRun);putString("agentRunId",runId);putString("senderTraceRef","live-product:$runId:$scene");putString("evidenceMode","agent_e2e")})
                    receiverArgs=Bundle().apply {putInt("version",1);putString("testRunId",testRun);putString("lease",setup.getString("lease"))}
                    val initial=setup.getJSONObject("snapshot")
                    assertEquals(upper,initial.getJSONObject("geometry").getInt("displayId"))
                    assertEquals(description.getString("taskPrompt"),initial.getString("taskPrompt"))
                    for(key in listOf("newsCode","articleTopCode","articleBottomCode","gameResult"))initial.getJSONObject("expected").optString(key).takeIf {it.isNotEmpty()}?.let {hidden->assertFalse("Prompt must not contain hidden answer $key",task.contains(hidden))}
                    Thread.sleep(700)
                    val marker="scenario-${id()}"
                    js(web,"window.__scenarioRuns=window.__scenarioRuns||{};window.__scenarioRuns[${JSONObject.quote(marker)}]={done:false};Promise.resolve(window.runPreparedNative(JSON.parse(${JSONObject.quote(begun.toString())}))).then(()=>{window.__scenarioRuns[${JSONObject.quote(marker)}].done=true},error=>{window.__scenarioRuns[${JSONObject.quote(marker)}]={done:true,error:String(error)}});true")
                    val started=SystemClock.elapsedRealtime();var heartbeat=0L
                    // No generation deadline: observer reporting does not end or restart the model task.
                    while(js(web,"window.__scenarioRuns[${JSONObject.quote(marker)}].done === true")!="true") {
                        Thread.sleep(500)
                        if(SystemClock.elapsedRealtime()-heartbeat>=5000) {
                            heartbeat=SystemClock.elapsedRealtime()
                            val current=receiver("report",receiverArgs!!).getJSONObject("snapshot")
                            record.put("runningElapsedMs",heartbeat-started).put("latestReceiverPage",current.optString("page")).put("latestReceiverState",current.getJSONObject("state"))
                            File(context.filesDir,"physical-scenario-flow.json").writeText(evidence.toString(2))
                        }
                    }
                    Thread.sleep(350)
                    val final=receiver("release",receiverArgs!!).getJSONObject("snapshot");sealed=true
                    val run=readDbRows("runs",runId).single();val messages=readDbRows("messages",runId)
                    val answer=messages.single {it.getString("role")=="assistant"}.getString("text")
                    val tools=readDbRows("events",runId).filter {it.getString("type")=="tool_result"}.map {it.getJSONObject("data")}
                    record.put("runStatus",run.getString("status")).put("runtime",run.optJSONObject("runtime")).put("modelTransport",run.optJSONArray("modelTransport")).put("modelId",run.getString("modelId")).put("timings",run.optJSONObject("timings"))
                        .put("assistantAnswer",answer).put("toolResults",JSONArray(tools)).put("toolErrors",JSONArray(tools.filter {!it.isNull("error")}))
                    File(context.filesDir,"physical-scenario-$scene-native.json").writeText(record.toString(2))
                    collect(final,scene,record,automation)
                    assertEquals("completed",run.getString("status"));assertEquals("deepseek-v4.1-flash",run.getString("modelId"))
                    assertEquals(task,messages.single {it.getString("role")=="user"}.getString("text"))
                    verify(scene,final,answer,tools,record)
                    record.put("passed",true)
                } catch(t:Throwable) {
                    failures.add("$scene: ${t.message}");record.put("failure","${t.javaClass.simpleName}: ${t.message.orEmpty().take(2500)}")
                } finally {
                    if(!sealed)receiverArgs?.let {params->runCatching {val final=receiver("release",params).getJSONObject("snapshot");collect(final,scene,record,automation)}.onFailure {record.put("receiverCollectionError",it.javaClass.simpleName+": "+it.message)}}
                    if(runId!=null)runCatching {if(readDbRows("runs",runId).single().optString("status") !in setOf("completed","failed","cancelled","interrupted"))ui(web,obj("op" to "stop","runId" to runId,"sessionId" to sessionId))}
                    if(sessionId!=null)runCatching {ui(web,obj("op" to "capability","sessionId" to sessionId,"capability" to "screen_control","enabled" to false))}
                    File(context.filesDir,"physical-scenario-flow.json").writeText(evidence.toString(2))
                    File(context.filesDir,"physical-scenario-$scene-native.json").writeText(record.toString(2))
                }
            }
            evidence.put("passed",failures.isEmpty())
            assertTrue("Independent multi-step failures: ${failures.joinToString("; ")}",failures.isEmpty())
        } finally {
            val after=historyIds();val historyPreserved=before.all {(table,ids)->after.getValue(table).containsAll(ids)}
            evidence.put("historyPreserved",historyPreserved).put("profileConfigurationUnchanged",profileBefore==profile.all).put("encryptedCredentialsUnchanged",credentialsBefore==credentials.all)
            runCatching {val restore=selectedBefore.ifBlank {originalSession.orEmpty()};ui(web,obj("op" to "select","sessionId" to restore));until {host.selectedSession()==restore}}
            File(context.filesDir,"physical-scenario-flow.json").writeText(evidence.toString(2))
            instrumentation.runOnMainSync {activity.finish()};instrumentation.waitForIdleSync()
            reader.close()
            assertTrue("Existing user history must remain intact",historyPreserved)
            assertTrue("Test must not change saved provider configuration",profileBefore==profile.all)
            assertTrue("Test must not change saved encrypted credentials",credentialsBefore==credentials.all)
        }
    }
}
