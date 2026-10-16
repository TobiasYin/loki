package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Sends only the whole user goal through the normal composer path. This observer
 * never opens the emulator/game, picks a save, clicks a game target or injects input. */
@RunWith(AndroidJUnit4::class)
class PhysicalLongGameFlowTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val readerDelegate=lazy {SqliteStore(context,"product.db")}
    private val reader get()=readerDelegate.value
    private var savedOutputBudget:Pair<Boolean,Int>?=null
    private fun restoreOutputBudget() {
        savedOutputBudget?.let {(existed,value)->
            val editor=context.getSharedPreferences("profiles",0).edit()
            if(existed)editor.putInt("maxOutput",value) else editor.remove("maxOutput")
            check(editor.commit());savedOutputBudget=null
        }
    }
    @After fun close(){restoreOutputBudget();if(readerDelegate.isInitialized())reader.close()}
    private fun rows(table:String,session:String?=null):List<JSONObject> {
        require(table in setOf("sessions","runs","messages","events"))
        val end=SystemClock.elapsedRealtime()+40000
        while(true)try {
            return reader.readableDatabase.rawQuery("SELECT payload FROM $table${if(session==null)"" else " WHERE sessionId=?"} ORDER BY rowid",session?.let {arrayOf(it)}).use {c->buildList {while(c.moveToNext())add(JSONObject(c.getString(0)))}}
        }catch(e:android.database.sqlite.SQLiteDatabaseLockedException){if(SystemClock.elapsedRealtime()>=end)throw e;Thread.sleep(100)}
    }
    private fun until(condition:()->Boolean){val end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(100)};error("Game task UI setup not ready")}
    private fun js(web:WebView,source:String):String {val latch=CountDownLatch(1);var result="";ins.runOnMainSync {web.evaluateJavascript(source){result=it;latch.countDown()}};assertTrue(latch.await(8,TimeUnit.SECONDS));return result}
    private fun ui(web:WebView,data:JSONObject){js(web,"ThorUI.postMessage(${JSONObject.quote(data.toString())});true")}
    @Test fun thorAgentReceivesWholeGameGoalWithoutHarnessGameSteps() {
        val args=InstrumentationRegistry.getArguments();org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val terminal=setOf("completed","failed","cancelled","interrupted")
        assertTrue("Do not interrupt an existing user task",rows("runs").all {it.optString("status") in terminal})
        val profile=context.getSharedPreferences("profiles",0);val credentials=context.getSharedPreferences("native-credentials",0)
        val profileBefore=profile.all.toMap();val credentialsBefore=credentials.all.toMap()
        val originalIds=rows("runs").map {it.getString("id")}.toSet()
        val testBudget=args.getString("gameOutputBudget")?.toInt()?.also {require(it in 2048..65536)}
        val captureRuntimeDiagnostics=args.getString("gameDiagnostics")=="true"
        if(testBudget!=null) {
            savedOutputBudget=profile.contains("maxOutput") to profile.getInt("maxOutput",2048)
            check(profile.edit().putInt("maxOutput",testBudget).commit())
        }
        ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(4).toBundle()) as MainActivity
        ins.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val ready=EmulatorRuntimeTestSupport.awaitReady(activity)
        val web=ready.web
        val executionWeb=ready.executionWeb
        val host=ready.host
        val directory=File(context.filesDir,"long-game-flow").apply {mkdirs()}
        val record=obj("schemaVersion" to 1,"evidenceMode" to "agent_e2e_observation","harnessGameSteps" to JSONArray(),"modelTimeoutMs" to 0,"goalIndependentlyVerified" to false,"scope" to "normal send; real cloud/Native tools; completion requires independent game screen review","passed" to false)
        record.put("temporaryTestOutputBudget",testBudget ?: JSONObject.NULL)
        fun save(){File(directory,"latest.json").writeText(record.toString(2))}
        try {
            until {js(web,"window.productReady===true && !document.getElementById('new').disabled")=="true"}
            assertFalse(host.hasActiveRuns());record.put("originalSelectedSession",host.selectedSession())
            val resume=args.getString("gameSession")
            if(resume==null) {
                val previous=host.selectedSession();ui(web,obj("op" to "new"));until {host.selectedSession()!=previous}
            } else {ui(web,obj("op" to "select","sessionId" to resume));until {host.selectedSession()==resume}}
            val sid=host.selectedSession();record.put("sessionId",sid)
            if(resume==null) {
                ui(web,obj("op" to "sessionOptions","sessionId" to sid,"modelId" to (args.getString("gameModel") ?: "deepseek-v4.1-flash"),"thinkingEffort" to "low","selectedSkills" to JSONArray(listOf("adb-operations"))))
                for(cap in listOf("perception","screen_control","search","debug"))ui(web,obj("op" to "capability","sessionId" to sid,"capability" to cap,"enabled" to true))
                until {val s=rows("sessions").single {it.getString("id")==sid};val caps=s.getJSONObject("capabilities");listOf("perception","screen_control","search","debug").all {caps.getBoolean(it)}}
            }
            var goal=if(resume==null)"请从打开 Citra 应用开始，自行在游戏列表找到《塞尔达传说 众神的三角力量2》，启动游戏，选择空白存档位创建名为 THOR 的新存档，按画面和游戏提示推进，到铁匠处拿到剑，并完成游戏开篇的第一个任务。必须通过实际屏幕、触摸或手柄操作完成，不要只提供攻略。请先观察当前页面，自行辨认应用和入口；需要攻略时自行搜索并按需读取 Skill。不得覆盖或删除已有存档，不得修改存档文件、内存或用作弊手段；无空槽时停下来说明。每次页面变化后重新观察，移动和按键都用有界动作并验证结果。卡住时保留当前进度并准确说明障碍，不要编造成功。最终以实际游戏画面为证据，明确区分创建存档、拿到剑和完成任务。" else args.getString("gameFollowup") ?: "继续同一会话里的 Citra 塞尔达新存档完整任务。先观察当前真实状态，从已经完成的地方继续，不重建存档、不重复已完成步骤，不覆盖旧存档；不要只口头提供攻略。"
            val maxTurns=(args.getString("gameTurns") ?: "1").toInt().coerceIn(1,16)
            val completedRunIds=JSONArray();record.put("completedRunIds",completedRunIds)
            for(round in 0 until maxTurns) {
            record.put("round",round+1)
            record.put("prompt",goal).put("resumed",resume!=null);val beforeRuns=rows("runs",sid).map {it.getString("id")}.toSet();save()
            // The same op as the visible Send button: preset/probe/startup behavior is exercised.
            ui(web,obj("op" to "send","sessionId" to sid,"text" to goal))
            var started:JSONObject?=null
            until {started=rows("runs",sid).lastOrNull {it.getString("id") !in beforeRuns};started!=null || js(web,"document.getElementById('send').dataset.running")=="\"false\"" && js(web,"!!document.querySelector('.error:not(:empty)')")=="true"}
            requireNotNull(started){"Normal send did not create a model Run"}
            val rid=started!!.getString("id");record.put("runId",rid);val begin=SystemClock.elapsedRealtime();var diagnosticAt=Long.MIN_VALUE;save()
            while(true) {
                val run=rows("runs",sid).single {it.getString("id")==rid}
                val events=rows("events",sid).filter {it.getString("runId")==rid}
                record.put("elapsedMs",SystemClock.elapsedRealtime()-begin).put("runStatus",run.optString("status")).put("runtimeState",run.optJSONObject("runtime")?.optString("state"))
                    .put("eventCount",events.size).put("toolResults",JSONArray(events.filter {it.optString("type")=="tool_result"}.map {it.getJSONObject("data")}))
                    .put("assistantAnswer",rows("messages",sid).lastOrNull {it.optString("runId")==rid && it.optString("role")=="assistant"}?.optString("text"))
                if(captureRuntimeDiagnostics && (diagnosticAt==Long.MIN_VALUE || SystemClock.elapsedRealtime()-diagnosticAt>=10000)) {
                    diagnosticAt=SystemClock.elapsedRealtime()
                    // Debug-only fixed numeric/phase snapshots. The observer does
                    // not inspect arguments, model text, URLs or credentials, and
                    // never waits for the asynchronous Native diagnostic reply.
                    val diagnostic=js(executionWeb,"""(()=>{
                      if(typeof window.__thorRuntimeDiagnostics!=='function')return {available:false};
                      if(typeof window.__thorRuntimeNativeDiagnostics==='function' && !window.__thorObserverDiagnosticPending){
                        window.__thorObserverDiagnosticPending=true;
                        window.__thorRuntimeNativeDiagnostics().then(value=>{window.__thorObserverNativeDiagnostic={ok:true,value};},()=>{window.__thorObserverNativeDiagnostic={ok:false};}).finally(()=>{window.__thorObserverDiagnosticPending=false;});
                      }
                      return {available:true,js:window.__thorRuntimeDiagnostics(),native:window.__thorObserverNativeDiagnostic??null};
                    })()""")
                    runCatching {JSONObject(diagnostic)}.getOrNull()?.let {record.put("runtimeDiagnostics",it)}
                }
                save()
                if(run.optString("status") in terminal && !host.hasActiveRuns()) {
                    record.put("runtime",run.optJSONObject("runtime")).put("timings",run.optJSONObject("timings")).put("modelTransport",run.optJSONArray("modelTransport"))
                    File(directory,"$rid-events.json").writeText(JSONArray(events).toString(2))
                    File(directory,"$rid-run.json").writeText(run.toString(2));record.put("observedTerminalRun",true);break
                }
                val stop=File(directory,"stop-requested")
                if(stop.exists()) {stop.delete();record.put("stoppedByObserver",true);ui(web,obj("op" to "stop","runId" to rid))}
                Thread.sleep(2000)
            }
            completedRunIds.put(rid)
            File(directory,"$rid-observation.json").writeText(record.toString(2))
            val ended=rows("runs",sid).single {it.getString("id")==rid}
            val exhausted=ended.optJSONObject("runtime")?.optString("error")=="LIMIT_EXCEEDED" || record.getJSONArray("toolResults").objects().any {it.optString("error")=="LIMIT_EXCEEDED"}
            if(!exhausted || record.optBoolean("stoppedByObserver") || round+1>=maxTurns)break
            goal="保留同一会话和当前实际游戏进度，继续原来的完整 Citra 塞尔达新档任务。上一轮达到调用预算，并非要求你重建任务。先重新观察当前画面，核对已完成动作，不重建存档，不重复已有副作用，不覆盖旧档，继续完成尚未完成的部分。"
            }
            record.put("passed",true) // Observer delivery only; never marks the game goal as achieved.
        } finally {
            restoreOutputBudget()
            record.put("profileConfigurationUnchanged",testBudget==null && profileBefore==profile.all).put("profileConfigurationRestored",profileBefore==profile.all).put("encryptedCredentialsUnchanged",credentialsBefore==credentials.all)
                .put("originalRunIdsPreserved",rows("runs").map {it.getString("id")}.toSet().containsAll(originalIds));save()
            File(directory,"${record.optString("runId","setup")}-observation.json").writeText(record.toString(2))
            assertEquals(profileBefore,profile.all);assertEquals(credentialsBefore,credentials.all)
            // Keep the ordinary conversation visible for inspection/follow-up; no target app operations.
        }
    }
}
