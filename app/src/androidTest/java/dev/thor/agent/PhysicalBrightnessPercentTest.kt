package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.Status
import dev.thor.tools.ThorBrightnessPercent
import dev.thor.tools.diagnostic.AndroidBackend
import dev.thor.tools.diagnostic.DebugConnection
import dev.thor.tools.diagnostic.DebugUserService
import dev.thor.tools.diagnostic.IDebugService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Focused live-model percentage acceptance. Raw values are independent diagnostic/cleanup
 * evidence only, never supplied to the model. Does not change automatic-brightness mode. */
@RunWith(AndroidJUnit4::class)
class PhysicalBrightnessPercentTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val readerDelegate=lazy {SqliteStore(context,"product.db")}
    private val reader get()=readerDelegate.value
    @After fun closeReader(){if(readerDelegate.isInitialized())reader.close()}
    private fun <T> read(block:(SQLiteDatabase)->T):T {
        val end=SystemClock.elapsedRealtime()+40000
        while(true)try {return block(reader.readableDatabase)}catch(e:android.database.sqlite.SQLiteDatabaseLockedException){if(SystemClock.elapsedRealtime()>=end)throw e;Thread.sleep(100)}
    }
    private fun until(condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+30000
        while(SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(100)}
        fail("Brightness test setup did not become ready")
    }
    private fun js(web:WebView,script:String):String {
        val latch=CountDownLatch(1);var value=""
        ins.runOnMainSync {web.evaluateJavascript(script){value=it;latch.countDown()}}
        assertTrue(latch.await(5,TimeUnit.SECONDS));return value
    }
    private fun ui(web:WebView,command:JSONObject){js(web,"ThorUI.postMessage(${JSONObject.quote(command.toString())});true")}
    private fun rows(table:String,runId:String?=null):List<JSONObject> {
        require(table in setOf("runs","events","sessions","messages"))
        return read {db->
            val where=if(runId==null)"" else if(table=="runs")" WHERE id=?" else " WHERE runId=?"
            db.rawQuery("SELECT payload FROM $table$where ORDER BY ${if(table=="events")"sequence" else "rowid"}",runId?.let {arrayOf(it)}).use {c->buildList {while(c.moveToNext())add(JSONObject(c.getString(0)))}}
        }
    }
    private fun historyIds():Map<String,Set<String>> = read {db->
        listOf("sessions","runs","messages","events","attachments").associateWith {table->db.rawQuery("SELECT id FROM $table",null).use {c->buildSet {while(c.moveToNext())add(c.getString(0))}}}
    }
    private fun percent(data:JSONObject,key:String):Int {
        val number=data.get(key) as? Number ?: error("Non-numeric $key")
        assertTrue("$key must be an integer percentage",number.toDouble().isFinite() && number.toDouble()==number.toInt().toDouble() && number.toInt() in 0..100)
        return number.toInt()
    }

    @Test fun liveAgentUsesIntegerPercentagesAndRestoresBothScreens() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val profiles=context.getSharedPreferences("profiles",0);val credentials=context.getSharedPreferences("native-credentials",0)
        assertEquals("passed",profiles.getString("probe",null));assertEquals("deepseek-v4.1-flash",profiles.getString("model",null))
        assertFalse("Do not launch instrumentation over an existing nonterminal task",rows("runs").any {it.optString("status") !in setOf("completed","failed","cancelled","interrupted")})
        val profilesBefore=profiles.all.toMap();val credentialsBefore=credentials.all.toMap();val historyBefore=historyIds()
        val automation=ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {it.readBytes().toString(Charsets.UTF_8).trim()}
        val mode=shell("settings get system screen_brightness_mode")
        assertEquals("Do not override automatic brightness; this acceptance requires existing manual mode","0",mode)
        val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay")!!.toInt()).toBundle()) as MainActivity
        ins.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val ready=EmulatorRuntimeTestSupport.awaitReady(activity)
        val web=ready.web
        val executionWeb=ready.executionWeb
        val host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val backend=ProductHost::class.java.getDeclaredField("backend").apply {isAccessible=true}.get(host) as AndroidBackend
        val selectedBefore=args.getString("restoreSession") ?: host.selectedSession()
        var remote:IDebugService?=null;var sessionId:String?=null;var runId:String?=null
        val originals=linkedMapOf<String,JSONObject>();val cleanupFailures=mutableListOf<String>()
        val evidence=obj("schemaVersion" to 1,"evidenceMode" to "agent_e2e","modelTimeoutMs" to 0,"publicUnit" to "percent","rawUnit" to "firmware_0_255","originalMode" to mode,"passed" to false)
        fun save(){File(context.filesDir,"physical-brightness-percent.json").writeText(evidence.toString(2))}
        fun panel(screen:String,value:Int?=null):JSONObject {
            val service=remote!!;val token="percent-test:${id()}"
            service.openLease(token,SystemClock.elapsedRealtime()+10000,DebugUserService.ROOT,Binder())
            try {
                val reply=JSONObject(service.thorBrightness(token,if(value==null)"read" else "set",screen,value?:-1))
                assertFalse(reply.toString(),reply.has("error"));return reply.getJSONObject("data")
            } finally {service.cancel(token)}
        }
        fun raw(data:JSONObject)=data.getJSONObject("firmwareDiagnostics").getInt("brightness")
        try {
            until {js(web,"window.productReady===true && !document.getElementById('new').disabled")=="true"}
            assertFalse("Do not interrupt a user task",host.hasActiveRuns())
            until {backend.availability("debug.thor_brightness").status==Status.READY}
            remote=DebugConnection::class.java.getDeclaredField("service").apply {isAccessible=true}.get(backend.shizuku) as IDebugService
            for(screen in listOf("upper","lower")) {
                val before=panel(screen);assertEquals("percent",before.getString("unit"));percent(before,"brightness")
                assertEquals(ThorBrightnessPercent.fromFirmware(raw(before)),before.getInt("brightness"));originals[screen]=before
            }
            evidence.put("originalPanels",JSONObject(originals as Map<*,*>));save()
            val previous=host.selectedSession()
            ui(web,obj("op" to "new"));until {host.selectedSession()!=previous};sessionId=host.selectedSession()
            ui(web,obj("op" to "sessionOptions","sessionId" to sessionId,"modelId" to "deepseek-v4.1-flash","thinkingEffort" to "low","selectedSkills" to JSONArray(listOf("adb-operations"))))
            for((cap,enabled) in mapOf("debug" to true,"search" to false,"screen_control" to false,"perception" to false))ui(web,obj("op" to "capability","sessionId" to sessionId,"capability" to cap,"enabled" to enabled))
            until {val session=rows("sessions").single {it.getString("id")==sessionId};session.optString("modelId")=="deepseek-v4.1-flash" && session.getJSONObject("capabilities").getBoolean("debug") && !session.getJSONObject("capabilities").getBoolean("perception") && !session.getJSONObject("capabilities").getBoolean("search") && !session.getJSONObject("capabilities").getBoolean("screen_control") && session.getJSONArray("selectedSkills").toString().contains("adb-operations")}
            val task="请用双屏独立亮度工具完成一次百分比调整验收。先按需读取 adb-operations 和亮度手册，再分别读取上屏、下屏的当前整数百分比；把上屏设为42%，分别回读两屏，确认下屏未变；接着把下屏设为37%，分别回读两屏，确认上屏仍为42%。两次写入均无需批准，每屏只写一次，不修改自动亮度模式，不使用 Shell 或其他写工具，不在本任务自行恢复（测试结束会自动恢复）。只有回验成功才报告成功，最后用格式“上屏：实际百分比%；下屏：实际百分比%”简短回答。"
            val begun=host.prepareForRunner(task).get(10,TimeUnit.SECONDS)
            assertEquals(0,begun.getInt("runTimeoutMs"));assertEquals(0,begun.getJSONObject("network").getInt("timeoutMs"));assertEquals(0,begun.getJSONArray("history").length())
            assertEquals("Model must execute the exact configured test session",sessionId,begun.getJSONObject("lease").getString("sessionId"))
            runId=begun.getJSONObject("lease").getString("runId");evidence.put("sessionId",sessionId).put("runId",runId).put("prompt",task)
            val marker="brightness-${id()}"
            js(executionWeb,"window.__brightnessPercent={marker:${JSONObject.quote(marker)},done:false};Promise.resolve(window.runPreparedNative(JSON.parse(${JSONObject.quote(begun.toString())}))).then(()=>window.__brightnessPercent.done=true,e=>{window.__brightnessPercent.done=true;window.__brightnessPercent.error=String(e)});true")
            val started=SystemClock.elapsedRealtime();var heartbeat=0L
            // No model/task deadline. These read-only observer updates never restart the session.
            while(js(executionWeb,"window.__brightnessPercent.done===true")!="true") {
                assertEquals("White brightness actions must not request approval","false",js(web,"!!document.querySelector('.wait')"))
                Thread.sleep(500)
                if(SystemClock.elapsedRealtime()-heartbeat>=5000){heartbeat=SystemClock.elapsedRealtime();evidence.put("runningElapsedMs",heartbeat-started);save()}
            }
            val run=rows("runs",runId).single();val events=rows("events",runId);val messages=rows("messages",runId)
            evidence.put("run",run).put("events",JSONArray(events))
            assertEquals("completed",run.getString("status"));assertEquals("deepseek-v4.1-flash",run.getString("modelId"))
            assertFalse(events.any {it.getString("type")=="awaiting_user"})
            val calls=events.filter {it.getString("type")=="tool_started"}
            assertFalse("Routine brightness must use the percent tool",calls.any {it.getJSONObject("data").getString("toolId")=="debug.shell"})
            assertTrue("The operation manual must be read lazily",calls.any {it.getJSONObject("data").getString("toolId")=="skills.read"})
            val brightness=calls.filter {it.getJSONObject("data").getString("toolId")=="debug.thor_brightness"}.map {call->
                val input=call.getJSONObject("data").getJSONObject("arguments")
                val result=events.single {it.getString("type")=="tool_result" && it.optString("toolCallId")==call.getString("toolCallId")}.getJSONObject("data")
                assertTrue("Brightness action failed",result.isNull("error"))
                val data=JSONObject(result.getJSONArray("content").objects().single {it.getString("type")=="text"}.getString("text"))
                assertEquals("percent",data.getString("unit"));assertFalse(data.has("firmwareDiagnostics"));assertEquals(2000,data.getInt("uid"))
                percent(data,"brightness");percent(data,"before");assertTrue(data.getBoolean("verified"));assertEquals(input.getString("screen"),data.getString("screen"))
                if(input.getString("action")=="set") {assertEquals(percent(input,"value"),percent(data,"requested"))} else assertTrue(data.isNull("requested"))
                obj("sequence" to call.getLong("sequence"),"arguments" to input,"result" to data)
            }
            evidence.put("brightnessCalls",JSONArray(brightness))
            val writes=brightness.filter {it.getJSONObject("arguments").getString("action")=="set"}
            assertEquals(2,writes.size)
            for((index,screen) in listOf("upper","lower").withIndex()) {
                assertEquals(screen,writes[index].getJSONObject("arguments").getString("screen"))
                assertEquals(if(screen=="upper")42 else 37,writes[index].getJSONObject("arguments").getInt("value"))
            }
            fun reads(screen:String,min:Long,max:Long)=brightness.filter {it.getJSONObject("arguments").getString("action")=="read" && it.getJSONObject("arguments").getString("screen")==screen && it.getLong("sequence") in min until max}
            val upperSeq=writes[0].getLong("sequence");val lowerSeq=writes[1].getLong("sequence")
            val reported=linkedMapOf<String,Int>()
            for(screen in listOf("upper","lower")) {
                val initial=reads(screen,0,upperSeq);assertTrue("Both screens must be read before writing",initial.isNotEmpty())
                assertEquals(originals.getValue(screen).getInt("brightness"),initial.last().getJSONObject("result").getInt("brightness"))
                val middle=reads(screen,upperSeq+1,lowerSeq);assertTrue("Both screens must be read after the first write",middle.isNotEmpty())
                val middleValue=middle.last().getJSONObject("result").getInt("brightness")
                if(screen=="upper")assertTrue(kotlin.math.abs(42-middleValue)<=1) else assertEquals(originals.getValue("lower").getInt("brightness"),middleValue)
                val last=reads(screen,lowerSeq+1,Long.MAX_VALUE);assertTrue("Both screens must be read after the second write",last.isNotEmpty())
                val finalValue=last.last().getJSONObject("result").getInt("brightness");reported[screen]=finalValue
                assertTrue(kotlin.math.abs((if(screen=="upper")42 else 37)-finalValue)<=1)
                if(screen=="upper")assertEquals("The lower-screen change must not move upper brightness",middleValue,finalValue)
                val actual=panel(screen);assertTrue(ThorBrightnessPercent.verified(if(screen=="upper")42 else 37,raw(actual)))
                assertEquals(finalValue,actual.getInt("brightness"))
                evidence.put("independentAfter-$screen",actual)
            }
            val answer=messages.single {it.getString("role")=="assistant"}.getString("text")
            evidence.put("answer",answer)
            val normalized=answer.replace("**", "").replace("：", ":").replace(Regex("\\s+"), "")
            assertTrue(normalized.contains("上屏:${reported.getValue("upper")}%"));assertTrue(normalized.contains("下屏:${reported.getValue("lower")}%"))
            evidence.put("modelFlowPassed",true)
        } catch(t:Throwable) {evidence.put("failure","${t.javaClass.simpleName}: ${t.message}");throw t}
        finally {
            if(runId!=null)runCatching {
                if(rows("runs",runId).single().optString("status") !in setOf("completed","failed","cancelled","interrupted")) {
                    ui(web,obj("op" to "stop","runId" to runId))
                    until {rows("runs",runId).single().optString("status") in setOf("completed","failed","cancelled","interrupted")}
                }
            }.onFailure {cleanupFailures.add("stop: ${it.message}")}
            // Restore only saved percentages. Never substitute an old fixed baseline or touch mode.
            for((screen,before) in originals)runCatching {
                panel(screen,before.getInt("brightness"))
                val end=SystemClock.elapsedRealtime()+3000;var after:JSONObject
                do {Thread.sleep(50);after=panel(screen)}while(kotlin.math.abs(raw(after)-raw(before))>1 && SystemClock.elapsedRealtime()<end)
                evidence.put("restored-$screen",after)
                assertTrue("Restoration exceeds original raw firmware quantization",kotlin.math.abs(raw(after)-raw(before))<=1)
            }.onFailure {cleanupFailures.add("$screen: ${it.message}")}
            val modeAfter=shell("settings get system screen_brightness_mode")
            val preserved=historyIds().let {after->historyBefore.all {(table,ids)->after.getValue(table).containsAll(ids)}}
            evidence.put("finalMode",modeAfter).put("restorationErrors",JSONArray(cleanupFailures)).put("historyPreserved",preserved)
                .put("profileConfigurationUnchanged",profilesBefore==profiles.all).put("encryptedCredentialsUnchanged",credentialsBefore==credentials.all)
            if(sessionId!=null)runCatching {ui(web,obj("op" to "capability","sessionId" to sessionId,"capability" to "debug","enabled" to false))}
            runCatching {ui(web,obj("op" to "select","sessionId" to selectedBefore));until {host.selectedSession()==selectedBefore}}.onFailure {cleanupFailures.add("restore selected session: ${it.message}")}
            evidence.put("passed",evidence.optBoolean("modelFlowPassed") && cleanupFailures.isEmpty() && modeAfter==mode && preserved && profilesBefore==profiles.all && credentialsBefore==credentials.all);save()
            ins.runOnMainSync {activity.finish()};ins.waitForIdleSync()
            assertTrue("Restore failed: $cleanupFailures",cleanupFailures.isEmpty());assertEquals(mode,modeAfter)
            assertTrue(preserved);assertEquals(profilesBefore,profiles.all);assertEquals(credentialsBefore,credentials.all)
        }
    }
}
