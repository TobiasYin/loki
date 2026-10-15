package dev.thor.agent

import android.content.Intent
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.bridge.HttpStart
import dev.thor.bridge.HttpEvent
import dev.thor.bridge.Purpose
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Live agent writes the skill. Tests never supply a game answer or synthesize its files. */
@RunWith(AndroidJUnit4::class)
class EmulatorLiveSkillTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val name="stardew-valley-guide"
    private val output get()=File(context.filesDir,"skill-live").apply {mkdirs()}
    private lateinit var evidence:JSONObject
    private lateinit var activity:MainActivity
    private lateinit var web:WebView
    private lateinit var host:ProductHost
    private val requests=CopyOnWriteArrayList<JSONObject>()
    private val payloads=java.util.concurrent.ConcurrentHashMap<String,JSONObject>()
    private fun save() {synchronized(this){File(output,"report.json").writeText(evidence.toString(2))}}
    private val reader by lazy {SqliteStore(context,"product.db")}
    private fun <T> readDatabase(read:(android.database.sqlite.SQLiteDatabase)->T):T {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
        while(true)try {return read(reader.readableDatabase)}catch(error:android.database.sqlite.SQLiteDatabaseLockedException) {
            if(System.nanoTime()>=deadline)throw error
            Thread.sleep(100)
        }
    }
    private fun rows(table:String,where:String="",args:Array<String>?=null):List<JSONObject> {
        val result=mutableListOf<JSONObject>()
        readDatabase {db->db.rawQuery("SELECT payload FROM $table ${if(where.isEmpty())"" else "WHERE $where"} ORDER BY rowid",args).use {c->while(c.moveToNext())result.add(JSONObject(c.getString(0)))}}
        return result
    }
    private fun session(sid:String)=rows("sessions","id=?",arrayOf(sid)).single()
    private fun latestRun(sid:String):JSONObject?=readDatabase {db->db.rawQuery("SELECT payload FROM runs WHERE sessionId=? ORDER BY rowid DESC LIMIT 1",arrayOf(sid)).use {if(it.moveToFirst())JSONObject(it.getString(0)) else null}}
    private fun js(code:String):String {val latch=CountDownLatch(1);var value="";ins.runOnMainSync {web.evaluateJavascript(code){value=it;latch.countDown()}};assertTrue(latch.await(5,TimeUnit.SECONDS));return value}
    private fun waitFor(condition:String,seconds:Int=30) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong());while(System.nanoTime()<end){if(js(condition)=="true")return;Thread.sleep(100)};fail("Web condition timed out: $condition")}
    private fun events(run:JSONObject)=rows("events","runId=?",arrayOf(run.getString("id")))
    private fun calls(run:JSONObject)=run.getJSONObject("turn").getJSONArray("messages").objects().filter {it.getString("kind")=="assistant"}.flatMap {it.getJSONArray("calls").objects()}
    private fun results(run:JSONObject,tool:String)=events(run).filter {it.getString("type")=="tool_result" && it.getJSONObject("data").optString("toolId")==tool}.map {it.getJSONObject("data")}
    private fun textResult(result:JSONObject)=result.getJSONArray("content").objects().filter {it.optString("type")=="text"}.joinToString("\n"){it.getString("text")}
    private fun answer(run:JSONObject)=rows("messages","runId=?",arrayOf(run.getString("id"))).last {it.getString("role")=="assistant"}.getString("text")
    private fun launch() {
        activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        ins.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        web=activity.findViewById(android.R.id.custom)
        host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val bridge=MainActivity::class.java.getDeclaredField("bridge").apply {isAccessible=true}.get(activity)!!
        bridge.javaClass.getDeclaredField("networkObserver").apply {isAccessible=true}.set(bridge,{start:HttpStart,event:HttpEvent->
            host.auditHttp(start,event)
            if(event is HttpEvent.Head && start.envelope.purpose==Purpose.model) {
                val body=host.policy.vault.redact(start.body?.toString(Charsets.UTF_8).orEmpty())
                val payload=JSONObject(body);payloads[event.requestId]=payload
                val file=File(output,"requests/${event.requestId}.json").apply {parentFile!!.mkdirs();writeText(body)}
                requests.add(obj("requestId" to event.requestId,"runId" to start.envelope.binding.runId,"sessionId" to start.envelope.binding.sessionId,"httpStatus" to event.status,"sha256" to SkillStore.digest(file.readBytes()),"bytes" to file.length()))
            }
        })
        waitFor("window.productReady===true && document.querySelectorAll('.session-row').length>0 && !document.getElementById('new').disabled")
        readDatabase {it.isOpen}
    }
    private fun fresh(mounted:Boolean,network:Boolean,effort:String="low"):String {
        val count=js("document.querySelectorAll('.session-row').length").toInt();js("document.getElementById('new').click()")
        waitFor("document.querySelectorAll('.session-row').length===${count+1}")
        val sid=host.selectedSession()
        val before=session(sid)
        val opts=obj("op" to "sessionOptions","sessionId" to sid,"selectedSkills" to JSONArray(),"thinkingEffort" to effort)
        if(before.optString("thinkingEffort")!=effort || before.optJSONArray("selectedSkills")?.length()!=0)js("ThorUI.postMessage(${JSONObject.quote(opts.toString())})")
        val desired=listOf("perception" to false,"screen_control" to false,"debug" to false,"search" to network)
        for((cap,on) in desired)if(before.getJSONObject("capabilities").getBoolean(cap)!=on) {
            js("ThorUI.postMessage(${JSONObject.quote(obj("op" to "capability","sessionId" to sid,"capability" to cap,"enabled" to on).toString())})")
        }
        val configured=System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
        var actual=session(sid)
        fun configurationReady()=actual.optString("thinkingEffort")==effort && actual.optJSONArray("selectedSkills")?.length()==0 && desired.all {(key,on)->actual.getJSONObject("capabilities").getBoolean(key)==on}
        while(!configurationReady() && System.nanoTime()<configured){Thread.sleep(100);actual=session(sid)}
        assertTrue("Wait for Native session options before selecting a Skill",configurationReady())
        waitFor("document.querySelectorAll('#skill-selection input:checked:not(:disabled)').length===0")
        assertTrue(rows("messages","sessionId=?",arrayOf(sid)).isEmpty())
        if(mounted) {
            js("document.getElementById('more').click();document.getElementById('skill-choose').click()")
            waitFor("[...document.querySelectorAll('#skill-selection label')].some(r=>r.textContent.includes('$name'))")
            js("[...document.querySelectorAll('#skill-selection label')].find(r=>r.querySelector('span')?.firstChild?.textContent==='$name').querySelector('input').click()")
            waitFor("[...document.querySelectorAll('#skill-selection label')].find(r=>r.textContent.includes('$name')).querySelector('input').checked")
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
            while(System.nanoTime()<end && !session(sid).getJSONArray("selectedSkills").toString().contains(name))Thread.sleep(50)
            assertEquals(JSONArray(listOf(name)).toString(),session(sid).getJSONArray("selectedSkills").toString())
            js("document.getElementById('skill-choose').click()")
        }
        return sid
    }
    private fun task(label:String,prompt:String,approveSave:Boolean=false,allowHistory:Boolean=false):JSONObject {
        waitFor("document.getElementById('send').dataset.running==='false'",60)
        val sid=host.selectedSession();val prior=rows("runs","sessionId=?",arrayOf(sid)).map {it.getString("id")}.toSet()
        val detail=obj("prompt" to prompt,"sessionId" to sid);evidence.getJSONObject("cases").put(label,detail);evidence.put("phase",label);save()
        js("document.getElementById('input').value=${JSONObject.quote(prompt)};document.getElementById('composer').requestSubmit()")
        val approved=mutableSetOf<String>();var current:JSONObject?=null;var lastProgress=0L;val submitted=System.nanoTime()
        while(true) {
            current=latestRun(sid)?.takeIf {it.getString("id") !in prior && !it.optBoolean("probe")}
            if(current!=null) {
                if(System.nanoTime()-lastProgress>TimeUnit.SECONDS.toNanos(5)) {
                    val intents=rows("events","runId=? AND payload LIKE ?",arrayOf(current.getString("id"),"%\"type\":\"tool_intent\"%"))
                    detail.put("run",current).put("progressTools",JSONArray(intents.map {it.getJSONObject("data").optString("toolId")})).put("requests",JSONArray(requests.filter {it.getString("runId")==current.getString("id")}));save();lastProgress=System.nanoTime()
                }
                current.optJSONObject("approval")?.takeIf {it.optString("status")=="pending" && it.getString("id") !in approved}?.let {a->
                    assertTrue("Unexpected persistent change requested",approveSave)
                    assertTrue("Only the requested Skill may be saved",a.getString("summary").startsWith("Skill 变更：\n保存 $name\n"))
                    approved.add(a.getString("id"));waitFor("[...document.querySelectorAll('button')].some(b=>b.textContent==='批准此操作')",10)
                    js("[...document.querySelectorAll('button')].find(b=>b.textContent==='批准此操作').click()")
                    detail.put("approvals",JSONArray(approved)).put("approvalInteraction","instrumentation clicked real product UI; not a human click");save()
                }
                if(current.getString("status") in setOf("completed","failed","cancelled","interrupted"))break
            }
            if(current==null && System.nanoTime()-submitted>TimeUnit.SECONDS.toNanos(40))error("Input UI did not start a task; no model request is pending")
            Thread.sleep(750)
        }
        val run=current ?: error("No new task run")
        detail.put("run",run).put("events",JSONArray(events(run))).put("requests",JSONArray(requests.filter {it.getString("runId")==run.getString("id")})).put("answer",answer(run));save()
        assertEquals("$label must finish", "completed",run.getString("status"))
        assertTrue(run.getJSONObject("turn").getBoolean("complete"))
        val first=payloads.getValue(requests.first {it.getString("runId")==run.getString("id")}.getString("requestId"))
        val tail=first.getJSONArray("input").objects().last()
        assertEquals("Latest actual model input must be this user message","user",tail.optString("role"))
        assertEquals(prompt,tail.getJSONArray("content").objects().single {it.optString("type")=="input_text"}.getString("text"))
        assertEquals("This run must record one new user message",1,run.getJSONObject("turn").getJSONArray("messages").objects().count {it.optString("kind")=="user"})
        if(!allowHistory)assertTrue("Fresh session must not receive creation chat history",run.getJSONArray("contexts").objects().first().getJSONArray("includedMessageIds").objectsOrStrings().all {it=="current:${run.getString("id")}"})
        return run
    }
    private fun JSONArray.objectsOrStrings()=(0 until length()).map {getString(it)}
    private fun screenshot(label:String) {
        val bitmap=android.graphics.Bitmap.createBitmap(web.width,web.height,android.graphics.Bitmap.Config.ARGB_8888)
        ins.runOnMainSync {web.draw(android.graphics.Canvas(bitmap))};File(output,"$label.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun contains(value:Any?,needle:String,depth:Int=0):Boolean {
        if(depth>15)return false
        return when(value) {
            is JSONObject -> value.keys().asSequence().any {contains(value.get(it),needle,depth+1)}
            is JSONArray -> (0 until value.length()).any {contains(value.get(it),needle,depth+1)}
            is String -> value.contains(needle) || if(value.startsWith("{"))runCatching {contains(JSONObject(value),needle,depth+1)}.getOrDefault(false) else if(value.startsWith("["))runCatching {contains(JSONArray(value),needle,depth+1)}.getOrDefault(false) else false
            else -> false
        }
    }
    private fun checkLazy(label:String,run:JSONObject,files:Map<String,String>,expectRead:Boolean) {
        val sent=requests.filter {it.getString("runId")==run.getString("id")};assertTrue(sent.isNotEmpty())
        val first=payloads.getValue(sent.first().getString("requestId"))
        for(content in files.values)assertFalse("$label eagerly included Skill file",contains(first,content))
        val readCalls=calls(run).filter {it.getString("name")=="skills_read" && it.getJSONObject("arguments").optString("name")==name}
        if(expectRead) {
            assertTrue("Agent did not discover/read mounted Skill",readCalls.isNotEmpty())
            assertEquals("SKILL.md",readCalls.first().getJSONObject("arguments").optString("path","SKILL.md"))
            val relevant=readCalls.drop(1).map {it.getJSONObject("arguments").getString("path")};assertTrue("Expected on-demand reference read",relevant.any {it.startsWith("references/")})
            for(path in readCalls.map {it.getJSONObject("arguments").optString("path","SKILL.md")}) {
                val content=files.getValue(path);assertTrue("Read file missing from next actual model request",sent.drop(1).any {contains(payloads.getValue(it.getString("requestId")),content)})
            }
            assertEquals("Read only the reference relevant to this question",1,relevant.size)
            assertTrue(results(run,"skills.read").all {it.isNull("error")})
            assertFalse(calls(run).any {it.getString("name") in setOf("skills_save","skills_delete","skills_enable","web_fetch","web_request","web_search")})
        } else assertTrue("Unrelated/unmounted Skill was read",readCalls.isEmpty())
        val readPaths=readCalls.map {it.getJSONObject("arguments").optString("path","SKILL.md")}.toSet()
        for((path,content) in files)if(path !in readPaths)for(request in sent)assertFalse("Unread resource entered model context: $path",contains(payloads.getValue(request.getString("requestId")),content))
        evidence.getJSONObject("cases").getJSONObject(label).put("lazyLoadingVerified",true).put("skillReadPaths",JSONArray(readCalls.map {it.getJSONObject("arguments").optString("path","SKILL.md")}));save()
    }
    @Test fun agentCreatesResearchesAndReusesStardewSkillAcrossFreshSessions() {
        val args=InstrumentationRegistry.getArguments();org.junit.Assume.assumeTrue(args.getString("emulatorLiveSkill")=="true")
        val qemu=android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")).bufferedReader().use {it.readText().trim()};assertEquals("1",qemu)
        val phase=args.getString("skillPhase") ?: "create"
        evidence=obj("emulator" to true,"model" to "deepseek-v4.1-flash","testPhase" to phase,"cases" to JSONObject(),"passed" to false);save()
        if(phase=="create") {
            val credentials=File(context.cacheDir,"skill-live-credentials.json")
            if(credentials.exists()){val input=try {JSONObject(credentials.readText())}finally {assertTrue(credentials.delete())};CredentialVault(context).put("search",input.getString("searchKey"));input.remove("searchKey")}
            val p=context.getSharedPreferences("profiles",0)
            assertEquals("https://opencode.ai/zen/go/v1",p.getString("url",null));assertEquals("deepseek-v4.1-flash",p.getString("model",null))
            assertTrue(p.edit().putString("name","OpenCode Go · Skill live acceptance").putInt("maxOutput",8192).putString("searchBackend","Brave").putString("searchUrl","https://api.search.brave.com/res/v1/web/search").commit())
        }
        launch()
        try {
            if(phase in setOf("refine","tighten")) {
                val store=SkillStore(File(context.filesDir,"skills"));val before=store.read(name)
                val cropBefore=store.read(name,"references/spring-crops-bundle.md").getString("content")
                evidence.put("beforeSkillHash",before.getString("hash"))
                if(phase=="tighten") {
                    val sid=args.getString("skillResumeSession")?:error("Existing research session required")
                    js("ThorUI.postMessage(${JSONObject.quote(obj("op" to "select","sessionId" to sid).toString())})")
                    val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                    while(host.selectedSession()!=sid && System.nanoTime()<end)Thread.sleep(100)
                    assertEquals(sid,host.selectedSession())
                } else fresh(true,true,"minimal")
                val prompt=if(phase=="tighten") "在刚才的查证和修订基础上，再精简一次 stardew-valley-guide。请利用本会话已查到的资料，分开说明鱼竿的购买解锁条件与已经持有后能否装备 bait/tackle；不要把购买门槛当成装备使用条件，资料不足再核实。只保留 Bamboo Pole、Fiberglass Rod、Iridium Rod 三种新手相关鱼竿的必要限制，以及 Trap Bobber 的装备前提；删除与本春季专题无关的高级竿、训练竿、罕见获得途径、详细鼠标操作。根正文先按当前实际持有的鱼竿判断槽位，再给当前可执行的方案；需要购买升级时才讨论购买条件。保持原两个专题和按题读取，春季作物文件原样保留，来源与访问日期保留。请使用当前 hash 和 files 数组实际保存，仅修改这个 Skill，简短报告。" else "请复核并改进本会话挂载的 stardew-valley-guide。真实测试中，攻略回答建议一个只有普通竹鱼竿、钓鱼等级 2 的玩家挂上 Trap Bobber。请自己联网查证这条建议和装备限制是否一致，检查现有 Skill 是否遗漏了操作前提；至少核对两个相关鱼竿/钓具的可靠条目，优先 Wiki。不要只修正这一次回答，要把查证结论和适用条件写回 Skill，先读取内置创建指南、目录及当前 Skill/钓鱼参考文件，使用当前 hash 更新。根正文补充先确认装备和等级、按当前状态给可执行动作的步骤；装备事实并入原钓鱼专题，标明来源和访问日期；不要扩展无关鱼类，对不确定的译名保留英文。春季作物专题已经核对正确，其现有文件原样保留。仍只保留两个专题文件，按需读取；保持简洁。使用 skills_save 的 files 数组实际保存，只修改这一个 Skill，不改其他配置。最后简短报告查证和修订结果。"
                val run=task(phase,prompt,true,allowHistory=phase=="tighten")
                if(phase=="refine") {
                assertTrue(calls(run).any {it.getString("name")=="skills_read" && it.getJSONObject("arguments").optString("name")=="skill-creator"})
                assertTrue(calls(run).any {it.getString("name")=="skills_read" && it.getJSONObject("arguments").optString("name")==name})
                assertTrue(results(run,"web.search").any {it.isNull("error")})
                assertTrue(results(run,"web.fetch").count {it.isNull("error")}>=2)
                }
                assertTrue(results(run,"skills.save").any {it.isNull("error")})
                val current=SkillStore(File(context.filesDir,"skills"));val root=current.read(name)
                assertNotEquals(before.getString("hash"),root.getString("hash"))
                assertEquals(cropBefore,current.read(name,"references/spring-crops-bundle.md").getString("content"))
                val all=root.getJSONArray("resources").objectsOrStrings().associateWith {current.read(name,it).getString("content")}
                assertEquals(2,all.keys.count {it.startsWith("references/")})
                all.forEach {(path,content)->File(output,"export/$path").apply {parentFile!!.mkdirs();writeText(content)}}
                evidence.put("skill",current.catalog().objects().single {it.getString("name")==name}).put("fileHashes",JSONObject(all.mapValues {SkillStore.digest(it.value.toByteArray())})).put("files",JSONObject(all)).put("unchangedCropReferenceVerified",true);screenshot(if(phase=="refine")"refined" else "tightened")
            } else if(phase=="create") {
                val resume=args.getString("skillResumeSession")
                val prior=if(resume!=null) {
                    js("ThorUI.postMessage(${JSONObject.quote(obj("op" to "select","sessionId" to resume).toString())})")
                    waitFor("document.querySelector('.session-row.selected')?.textContent!==undefined")
                    val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                    while(host.selectedSession()!=resume && System.nanoTime()<end)Thread.sleep(100)
                    assertEquals(resume,host.selectedSession());latestRun(resume)!!.also {evidence.put("resumedFrom",it)}
                } else {fresh(false,true,"minimal");null}
                val prompt=if(resume!=null) "继续刚才创建 stardew-valley-guide 的任务。保留前面已读取的可靠网页和来源，不要重新研究；先按需读取新版内置 skill-creator 指南，再根据已有资料实际保存。两个专题仍是春季作物献祭/种植期限，以及春季雨天鲶鱼。SKILL.md 简短，专题分两份 references/ 文件，各注明来源、访问日期、适用范围，总计约 800 汉字。使用当前 skills_save 的 files 数组，每项只有 path、content，最外层只放 content、expectedHash、files；新建 expectedHash 为空。不要把路径放在最外层。只保存这个 Skill，不修改其他配置，成功后简短报告。" else "请自己联网收集资料并创建星露谷攻略 Skill，名称 stardew-valley-guide。适用星露谷物语 1.6、普通社区中心路线；只做春季作物献祭/种植期限、春季雨天鲶鱼两个专题。先读内置创建指南并查重，自己搜索可靠资料并至少读取两个主题页面，优先 Wiki，资料够用就保存。正文与参考资料合计约 800 汉字；事实分成 references/ 两份 Markdown，正文按题引用；注明来源 URL、访问日期和适用范围。必须调用 skills_save 实际保存：使用 files 数组，每项 path、content，新建 expectedHash 为空。只保存这个 Skill，成功后简短报告。"
                val run=task("create",prompt,true,allowHistory=resume!=null)
                assertTrue(calls(run).any {it.getString("name")=="skills_read" && it.getJSONObject("arguments").optString("name")=="skill-creator"})
                val research=(prior?.let {events(it)}?:emptyList())+events(run)
                evidence.put("researchEvidence",JSONArray(research.filter {it.optString("type") in setOf("tool_started","tool_result")}))
                assertTrue(research.any {it.optString("type")=="tool_started" && it.getJSONObject("data").optString("toolId")=="skills.list"})
                assertTrue("Real search required",research.any {it.optString("type")=="tool_result" && it.getJSONObject("data").optString("toolId")=="web.search" && it.getJSONObject("data").isNull("error")})
                assertTrue("Read multiple real sources",research.count {it.optString("type")=="tool_result" && it.getJSONObject("data").optString("toolId") in setOf("web.fetch","web.request") && it.getJSONObject("data").isNull("error")}>=2)
                assertTrue("No successful persistent save",results(run,"skills.save").any {it.isNull("error")})
                if(prior!=null) {
                    assertTrue("Failed research history omitted",run.getJSONArray("contexts").objects().first().getJSONArray("includedMessageIds").objectsOrStrings().contains(prior.getJSONObject("turn").getString("turnId")))
                    evidence.put("failedResearchHistoryRetained",true)
                }
                val store=SkillStore(File(context.filesDir,"skills"));val root=store.read(name);val all=root.getJSONArray("resources").objectsOrStrings().associateWith {store.read(name,it).getString("content")}
                assertTrue(all.keys.count {it.startsWith("references/")}>=2);assertTrue(all.values.joinToString("\n").contains("https://"))
                all.forEach {(path,content)->File(output,"export/$path").apply {parentFile!!.mkdirs();writeText(content)}}
                evidence.put("skill",store.catalog().objects().single {it.getString("name")==name}).put("fileHashes",JSONObject(all.mapValues {SkillStore.digest(it.value.toByteArray())})).put("files",JSONObject(all));screenshot("created")
                val followup=task("createFollowup","刚才保存的那个 Skill 叫什么、包含哪两个专题？只根据这段会话回答，不重新读取、不联网、不修改。",allowHistory=true)
                assertTrue(answer(followup).contains(name));assertTrue(calls(followup).isEmpty())
                assertTrue(events(followup).any {it.optString("type")=="context" && it.getJSONObject("data").optBoolean("piSessionReused")})
                evidence.put("samePiAgentFollowupVerified",true)
            } else {
                val store=SkillStore(File(context.filesDir,"skills"));val root=store.read(name)
                val files=root.getJSONArray("resources").objectsOrStrings().associateWith {store.read(name,it).getString("content")}
                evidence.put("skill",store.catalog().objects().single {it.getString("name")==name}).put("fileHashes",JSONObject(files.mapValues {SkillStore.digest(it.value.toByteArray())}))
                args.getString("skillResumeSession")?.let {sid->
                    js("ThorUI.postMessage(${JSONObject.quote(obj("op" to "select","sessionId" to sid).toString())})")
                    val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                    while(host.selectedSession()!=sid && System.nanoTime()<end)Thread.sleep(100)
                    assertEquals(sid,host.selectedSession())
                    val continued=task("continuedAfterFailure","现在请根据本会话之前的记录，告诉我刚才保存的 Skill 名称和两个专题。无需重读、联网或修改。",allowHistory=true)
                    assertTrue(answer(continued).contains(name));assertTrue(calls(continued).isEmpty())
                    val followup=task("sameSessionFollowup","承接上一条，请再用一句话概括这个 Skill 的用途，并带上它的名称。不要调用工具。",allowHistory=true)
                    assertTrue(answer(followup).contains(name));assertTrue(calls(followup).isEmpty())
                    assertTrue(events(followup).any {it.optString("type")=="context" && it.getJSONObject("data").optBoolean("piSessionReused")})
                    evidence.put("failedContextContinuationVerified",true).put("samePiAgentFollowupVerified",true)
                    screenshot("followup")
                }
                val chosen=args.getString("skillCases")?.split(',')?.toSet()?:setOf("unrelated","unmounted","fish","crops","equipment")
                require(chosen.isNotEmpty() && chosen.all {it in setOf("unrelated","unmounted","fish","crops","equipment")})
                val freshIds=mutableListOf<String>()
                if("unrelated" in chosen) {
                fresh(true,false)
                val unrelated=task("unrelated","请只计算 17×23，直接给出结果，不用展开解释。")
                assertTrue(answer(unrelated).contains("391"));checkLazy("unrelated",unrelated,files,false)
                }
                if("unmounted" in chosen) {
                fresh(false,false)
                val unmounted=task("unmounted","请简短说明这个会话现在可使用哪些已挂载的 Skill。只根据当前提供的目录回答，不需要读取正文，也不要创建技能。")
                assertFalse(answer(unmounted).contains(name));checkLazy("unmounted",unmounted,files,false)
                }
                if("fish" in chosen) {
                val fishSid=fresh(true,false);freshIds.add(fishSid)
                val fish=task("fish","我在星露谷物语 1.6 第一年春季第 6 天，雨天上午 11 点，刚拿到普通竹鱼竿，钓鱼等级 2，没有魔法鱼饵，也进不了秘密森林。想给普通社区中心留鲶鱼，现在站在山湖边。今天该怎么行动？请用本会话已有资料，给出地点、有效时间、原因和收获后要保留什么，不需要重新联网。简洁回答。")
                checkLazy("fish",fish,files,true)
                assertTrue(answer(fish).isNotBlank());screenshot("fish")
                }
                if("crops" in chosen) {
                val cropSid=fresh(true,false);freshIds.add(cropSid)
                val crop=task("crops","我在星露谷物语 1.6 第一年春季第 19 天，普通社区中心春季作物献祭，已留防风草、土豆和花椰菜，缺豆类，手上只有现在可以买到的种子；没有温室、种植加速职业或肥料，也不算随机商店购买。请根据本会话已有资料判断今年春天还能种出来完成吗？列出缺的作物、基础生长天数、最迟种植日和下一步处理；不要假定我已种过。简洁回答。")
                checkLazy("crops",crop,files,true)
                assertTrue(answer(crop).isNotBlank());screenshot("crops")
                }
                if("equipment" in chosen) {
                val equipmentSid=fresh(true,false);freshIds.add(equipmentSid)
                val equipment=task("equipment","我在星露谷物语 1.6 联机普通存档里，朋友已把一根 Iridium Rod 和一个 Trap Bobber 放到我的背包中，我的钓鱼等级只有 2。这两个东西已经实际持有，不讨论商店购买或制作。我现在能把这个钓具装到这根竿上吗？请用本会话资料说明原因，不重新联网，简洁回答。")
                checkLazy("equipment",equipment,files,true)
                assertTrue(answer(equipment).isNotBlank());screenshot("equipment")
                }
                val inherited=session(host.selectedSession()).getJSONArray("selectedSkills").toString()
                val nextCount=js("document.querySelectorAll('.session-row').length").toInt();js("document.getElementById('new').click()");waitFor("document.querySelectorAll('.session-row').length===${nextCount+1}")
                assertEquals(inherited,session(host.selectedSession()).getJSONArray("selectedSkills").toString())
                evidence.put("freshSessions",JSONArray(freshIds)).put("newSessionInheritedSkillSelection",true).put("persistedAfterHostRecreation",true)
            }
            evidence.put("requests",JSONArray(requests)).put("flowPassed",true).put("evaluationScope","tool flow, persistence and lazy loading; gameplay answers require independent semantic review").put("passed",true);save()
        } finally {evidence.put("requests",JSONArray(requests));save();ins.runOnMainSync {activity.finish()};ins.waitForIdleSync();reader.close()}
    }
}
