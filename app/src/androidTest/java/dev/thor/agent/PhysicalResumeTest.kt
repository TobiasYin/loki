package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.bridge.HttpEvent
import dev.thor.bridge.HttpStart
import dev.thor.bridge.Purpose
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Additive, cloud-backed Thor acceptance. Never installs a fixture profile or clears history. */
@RunWith(AndroidJUnit4::class)
class PhysicalResumeTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val output get()=File(context.filesDir,"physical-resume").apply {mkdirs()}
    private val reader by lazy {SqliteStore(context,"product.db")}
    private lateinit var activity:MainActivity
    private lateinit var web:WebView
    private lateinit var host:ProductHost
    private lateinit var evidence:JSONObject
    private val requests=CopyOnWriteArrayList<JSONObject>()
    private fun save() {File(output,"report.json").writeText(evidence.toString(2))}
    private fun rows(table:String,where:String="",args:Array<String>?=null):List<JSONObject> {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
        while(true)try {
            return reader.readableDatabase.rawQuery("SELECT payload FROM $table ${if(where.isEmpty())"" else "WHERE $where"} ORDER BY rowid",args).use {c->buildList {while(c.moveToNext())add(JSONObject(c.getString(0)))}}
        }catch(e:android.database.sqlite.SQLiteDatabaseLockedException) {if(System.nanoTime()>=end)throw e;Thread.sleep(100)}
    }
    private fun latest(sid:String)=rows("runs","sessionId=?",arrayOf(sid)).lastOrNull()
    private fun js(code:String):String {val latch=CountDownLatch(1);var value="";ins.runOnMainSync {web.evaluateJavascript(code){value=it;latch.countDown()}};assertTrue(latch.await(5,TimeUnit.SECONDS));return value}
    private fun waitFor(condition:String,seconds:Int=40) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong());while(System.nanoTime()<end){if(js(condition)=="true")return;Thread.sleep(100)};fail("UI condition timed out: $condition")}
    private fun launch() {
        activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(4).toBundle()) as MainActivity
        ins.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        web=activity.findViewById(android.R.id.custom)
        host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val bridge=MainActivity::class.java.getDeclaredField("bridge").apply {isAccessible=true}.get(activity)!!
        bridge.javaClass.getDeclaredField("networkObserver").apply {isAccessible=true}.set(bridge,{start:HttpStart,event:HttpEvent->
            host.auditHttp(start,event)
            if(event is HttpEvent.Head && start.envelope.purpose==Purpose.model) {
                val body=host.policy.vault.redact(start.body?.toString(Charsets.UTF_8).orEmpty())
                val file=File(output,"requests/${event.requestId}.json").apply {parentFile!!.mkdirs();writeText(body)}
                requests.add(obj("requestId" to event.requestId,"runId" to start.envelope.binding.runId,"status" to event.status,"bodySha256" to SkillStore.digest(file.readBytes())))
            }
        })
        waitFor("window.productReady===true && !document.getElementById('new').disabled && document.querySelectorAll('.session-row').length>0")
        reader.readableDatabase
        assertEquals(4,activity.display!!.displayId)
    }
    private fun newSession():String {
        val before=host.selectedSession();js("document.getElementById('new').click()")
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
        while(host.selectedSession()==before && System.nanoTime()<end)Thread.sleep(100)
        assertNotEquals(before,host.selectedSession());val sid=host.selectedSession()
        waitFor("document.querySelector('.session-row.selected')?.dataset.sessionId===${JSONObject.quote(sid)}")
        return sid
    }
    private fun task(label:String,prompt:String,cancelAfterText:Boolean=false):JSONObject {
        waitFor("document.getElementById('send').dataset.running==='false'")
        val sid=host.selectedSession();val prior=latest(sid)?.getString("id")
        val detail=obj("prompt" to prompt,"sessionId" to sid);evidence.getJSONObject("cases").put(label,detail);evidence.put("phase",label);save()
        js("document.getElementById('input').value=${JSONObject.quote(prompt)};document.getElementById('composer').requestSubmit()")
        var run:JSONObject?=null;var stopped=false;val submitted=System.nanoTime();var lastProgress=0L
        while(true) {
            run=latest(sid)?.takeIf {it.getString("id")!=prior}
            if(run!=null) {
                if(System.nanoTime()-lastProgress>TimeUnit.SECONDS.toNanos(5)) {detail.put("run",run).put("requests",JSONArray(requests.filter {it.getString("runId")==run.getString("id")}));save();lastProgress=System.nanoTime()}
                if(run.getString("status") in setOf("completed","failed","cancelled","interrupted"))break
                if(cancelAfterText && !stopped && rows("events","runId=? AND payload LIKE ?",arrayOf(run.getString("id"),"%\"type\":\"text_delta\"%")).isNotEmpty()) {
                    waitFor("document.getElementById('send').dataset.running==='true'")
                    js("document.getElementById('send').click()");stopped=true;detail.put("stopClickedThroughUi",true);save()
                }
            }
            if(run==null && System.nanoTime()-submitted>TimeUnit.SECONDS.toNanos(40))error("UI did not submit a task; no model request pending")
            Thread.sleep(500)
        }
        val actual=run ?: error("No task")
        val events=rows("events","runId=?",arrayOf(actual.getString("id")))
        detail.put("run",actual).put("events",JSONArray(events)).put("requests",JSONArray(requests.filter {it.getString("runId")==actual.getString("id")}))
        val answer=rows("messages","runId=?",arrayOf(actual.getString("id"))).lastOrNull {it.getString("role")=="assistant"}?.optString("text").orEmpty()
        detail.put("answer",answer);save()
        assertEquals(if(cancelAfterText)"cancelled" else "completed",actual.getString("status"))
        assertTrue("Actual successful model HTTP response required",requests.any {it.getString("runId")==actual.getString("id") && it.getInt("status")==200})
        assertEquals(1_000_000,actual.getInt("contextWindow"))
        return detail
    }
    private fun screenshot(label:String) {
        ins.runOnMainSync {val bitmap=android.graphics.Bitmap.createBitmap(web.width,web.height,android.graphics.Bitmap.Config.ARGB_8888);web.draw(android.graphics.Canvas(bitmap));File(output,"$label.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    @Test fun latestUiSkillsAndSessionContinuationOnLowerScreen() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val profiles=context.getSharedPreferences("profiles",0).all.toString()
        val credentials=context.getSharedPreferences("native-credentials",0).all.toString()
        evidence=obj("physical" to true,"chatDisplay" to 4,"modelService" to "saved live OpenCode Go","cases" to obj(),"passed" to false)
        try {
            launch()
            assertEquals(1_000_000,host.view().getJSONObject("modelSelection").getInt("contextWindow"))
            assertEquals("true",js("document.getElementById('active').hidden && !document.getElementById('chat').textContent.includes('配置探查已通过')"))
            js("document.getElementById('more').click()")
            assertEquals("true",js("!document.getElementById('more-panel').hidden && !!document.getElementById('attach') && !!document.getElementById('skill-choose')"))
            js("document.getElementById('more').click();document.getElementById('voice-toggle').click()")
            assertEquals("true",js("document.getElementById('input').hidden && !document.getElementById('voice-panel').hidden"))
            js("document.getElementById('voice-toggle').click();document.getElementById('settings').click()")
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            var page:View?=null
            while(page==null && System.nanoTime()<end){ins.runOnMainSync {page=activity.window.decorView.findViewWithTag("thor-settings-page")};Thread.sleep(80)}
            assertNotNull("Full settings page must appear",page)
            fun buttons(v:View):List<Button> = if(v is ViewGroup)(0 until v.childCount).flatMap {buttons(v.getChildAt(it))} else if(v is Button)listOf(v) else emptyList()
            ins.runOnMainSync {assertTrue(buttons(page!!).any {it.text.toString()=="火山流式语音识别 ›"});buttons(page!!).first {it.text.toString()=="‹ 返回"}.performClick()}
            evidence.put("compactUiAndSettings",true);save()
            val sid=newSession()
            js("ThorUI.postMessage(${JSONObject.quote(obj("op" to "sessionOptions","sessionId" to sid,"selectedSkills" to JSONArray()).toString())})")
            js("document.getElementById('effort-select').value='low';document.getElementById('effort-select').dispatchEvent(new Event('change'))")
            val configured=System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
            while(rows("sessions","id=?",arrayOf(sid)).single().optString("thinkingEffort")!="low" && System.nanoTime()<configured)Thread.sleep(100)
            assertEquals("low",rows("sessions","id=?",arrayOf(sid)).single().getString("thinkingEffort"))
            val nonce="THOR_${System.currentTimeMillis()}"
            task("remember","请记住本会话的验收口令 $nonce。只回复已记住，不调用工具。")
            val follow=task("followup","上一条的验收口令是什么？只输出口令，不调用工具。")
            assertTrue(follow.getString("answer").contains(nonce))
            assertTrue(follow.getJSONArray("events").objects().any {it.getString("type")=="context" && it.getJSONObject("data").optBoolean("piSessionReused")})
            val interruptedNonce="STOP_${System.currentTimeMillis()}"
            task("stopped","请先记住新口令 $interruptedNonce，然后写一篇至少五千字的中文故事，持续输出故事正文，不调用工具。",true)
            val continued=task("continuedAfterStop","刚才被我停止的那条消息里，新口令是什么？只输出新口令，不要继续故事，不调用工具。")
            assertTrue(continued.getString("answer").contains(interruptedNonce))
            ins.runOnMainSync {activity.finish()};ins.waitForIdleSync();Thread.sleep(400)
            launch();assertEquals(sid,host.selectedSession())
            val restored=task("afterHostRecreation","重开页面后，请复述本会话最初的验收口令（不是新口令）。只输出最初口令，不调用工具。")
            assertTrue(restored.getString("answer").contains(nonce))
            val zip=File(context.cacheDir,"physical-guide.zip");assertTrue("Runner must stage the actual Agent-authored guide",zip.isFile)
            val existing=host.view().getJSONArray("skills").objects().singleOrNull {it.getString("name")=="stardew-valley-guide"}
            if(existing==null)host.importSkill(Uri.fromFile(zip)).get(10,TimeUnit.SECONDS)
            val guide=host.view().getJSONArray("skills").objects().single {it.getString("name")=="stardew-valley-guide"}
            assertEquals("ae10191293e4dc6277d77ff2181c94140a4b69098f9de0fba74a62811d73ead0",guide.getString("hash"))
            evidence.put("skillImport",guide).put("importedByRunner",existing==null)
            val skillSid=newSession()
            js("document.getElementById('more').click();document.getElementById('skill-choose').click()")
            waitFor("[...document.querySelectorAll('#skill-selection label')].some(e=>e.textContent.includes('stardew-valley-guide'))")
            js("(() => {const e=[...document.querySelectorAll('#skill-selection label')].find(e=>e.querySelector('span')?.firstChild?.textContent==='stardew-valley-guide').querySelector('input');if(!e.checked)e.click()})()")
            val selected=System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
            while(!rows("sessions","id=?",arrayOf(skillSid)).single().optJSONArray("selectedSkills")?.toString().orEmpty().contains("stardew-valley-guide") && System.nanoTime()<selected)Thread.sleep(100)
            assertTrue(rows("sessions","id=?",arrayOf(skillSid)).single().getJSONArray("selectedSkills").toString().contains("stardew-valley-guide"))
            js("document.getElementById('skill-choose').click()")
            val result=task("skillReuse","我在星露谷物语 1.6 普通存档里已经持有 Iridium Rod 和 Trap Bobber，钓鱼等级只有 2。现在能装上吗？只按本会话攻略说明，简短回答，不要联网。")
            val reads=result.getJSONArray("events").objects().filter {it.getString("type")=="tool_started"}.map {it.getJSONObject("data")}
            assertEquals(listOf("SKILL.md","references/rainy-catfish.md"),reads.filter {it.optString("toolId")=="skills.read" && it.getJSONObject("arguments").optString("name")=="stardew-valley-guide"}.map {it.getJSONObject("arguments").optString("path","SKILL.md")})
            assertTrue(reads.all {it.getString("toolId")=="skills.read"})
            assertTrue(result.getString("answer").contains("能") || result.getString("answer").contains("可以"))
            screenshot("skill-reuse")
            val inherited=newSession();val options=rows("sessions","id=?",arrayOf(inherited)).single()
            assertTrue(options.getJSONArray("selectedSkills").toString().contains("stardew-valley-guide"));assertEquals("low",options.getString("thinkingEffort"))
            assertTrue(rows("messages","sessionId=?",arrayOf(inherited)).isEmpty())
            evidence.put("newSessionInheritance",true).put("passed",true)
        } finally {
            evidence.put("profilesPreserved",profiles==context.getSharedPreferences("profiles",0).all.toString()).put("encryptedCredentialsPreserved",credentials==context.getSharedPreferences("native-credentials",0).all.toString());save()
            assertEquals(profiles,context.getSharedPreferences("profiles",0).all.toString());assertEquals(credentials,context.getSharedPreferences("native-credentials",0).all.toString())
            if(::activity.isInitialized)ins.runOnMainSync {activity.finish()}
            reader.close()
        }
    }
}
