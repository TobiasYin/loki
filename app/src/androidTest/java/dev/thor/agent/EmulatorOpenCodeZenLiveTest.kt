package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit opt-in live acceptance. Consumes a private cache input; keeps the requested provider. */
@RunWith(AndroidJUnit4::class)
class EmulatorOpenCodeZenLiveTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private val physical get()=InstrumentationRegistry.getArguments().getString("zenPhysicalAcceptance")=="true"
    private val providerName get()=if(physical)"OpenCode Zen · 真机测试" else "OpenCode Zen · 模拟器测试"
    private val reportPrefix get()=if(physical)"physical-opencode-zen" else "emulator-opencode-zen"
    private fun verifyTarget() {
        if(physical)assertEquals("AYN Thor",android.os.Build.MODEL)
        else assertTrue("Physical testing requires its separate explicit opt-in",android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        assertNotNull(context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(4))
    }
    private fun ui(action:()->Unit)=inst.runOnMainSync(action)
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList())
    private fun until(seconds:Int=60,check:()->Boolean) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong());while(System.nanoTime()<end){if(check())return;Thread.sleep(150)};fail("Live Zen acceptance timed out")}
    private fun js(web:android.webkit.WebView,code:String):String {
        val latch=CountDownLatch(1);var result=""
        ui {web.evaluateJavascript(code){result=it;latch.countDown()}}
        assertTrue(latch.await(5,TimeUnit.SECONDS));return result
    }
    @Test fun exportSavedKeyForAuthorizedPhysicalTest() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("zenTransferCredential")=="true")
        assertTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val store=ProviderStore(context)
        val saved=store.list().single {it.optString("name")=="OpenCode Zen · 模拟器测试"}
        val key=requireNotNull(store.credential(saved.getString("id")))
        File(context.cacheDir,"opencode-zen-transfer-key").writeText(key)
    }
    @Test fun showSavedConnectionResult() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("zenShowConfiguration")=="true")
        verifyTarget()
        val store=ProviderStore(context);val config=store.list().single {it.optString("name")==providerName}
        val activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(4).toBundle()) as MainActivity
        try {
            assertFalse(EmulatorRuntimeTestSupport.awaitReady(activity).host.hasActiveRuns())
            ui {MainActivity::class.java.getDeclaredMethod("showProviderEditor",JSONObject::class.java,String::class.java).apply {isAccessible=true}.invoke(activity,config,OpenCodeZenCatalog.KIND)}
            inst.waitForIdleSync()
            // Wait for the automatic catalog load before testing this saved draft.
            Thread.sleep(1000)
            until {var enabled=false;ui {enabled=views(activity.window.decorView).filterIsInstance<Button>().single {it.text.toString()=="刷新 Zen 免费模型"}.isEnabled};enabled}
            ui {activity.window.decorView.findViewWithTag<Button>("model-test").performClick()}
            until(100) {var done=false;ui {done=activity.window.decorView.findViewWithTag<Button>("model-test").isEnabled};done}
            var message=""
            ui {
                val status=activity.window.decorView.findViewWithTag<TextView>("model-test-status");message=status.text.toString()
                status.requestRectangleOnScreen(android.graphics.Rect(0,0,status.width,status.height),true)
            }
            inst.waitForIdleSync()
            ui {
                val view=activity.window.decorView;val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(context.filesDir,"$reportPrefix-connection.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            File(context.filesDir,"$reportPrefix-connection.json").writeText(obj("providerConfigured" to true,"credentialStatus" to store.credentialStatus(config.getString("id")),"model" to config.getString("model"),"display" to 4,"message" to message).toString(2))
        }finally {ui {activity.finish()}}
    }
    @Test fun diagnoseConfiguredLiveService() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("zenDiagnose")=="true")
        assertTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val store=ProviderStore(context);val config=store.list().single {it.optString("name")=="OpenCode Zen · 模拟器测试"}
        val key=requireNotNull(store.credential(config.getString("id")))
        val report=obj("model" to config.getString("model"),"probes" to JSONArray())
        SqliteStore(context,"product.db").use {reader->
            val previous=reader.readableDatabase.rawQuery("SELECT payload FROM runs WHERE id=?",arrayOf(JSONObject(File(context.filesDir,"emulator-opencode-zen-live.json").readText()).optString("activeRunId"))).use {cursor->if(cursor.moveToFirst())JSONObject(cursor.getString(0)) else JSONObject()}
            report.put("previousTransport",previous.optJSONArray("modelTransport"))
        }
        fun save()=File(context.filesDir,"emulator-opencode-zen-diagnosis.json").writeText(report.toString(2))
        save()
        for(base in listOf(OpenCodeZenCatalog.BASE_URL,"https://opencode.ai/inference/openai/v1")) {
            val result=ModelConnectionTest.run(ModelConnectionTest.Draft(base,"openai-completions",config.getString("model"),key,reasoningEffort="low",maxOutput=4096))
            report.getJSONArray("probes").put(obj("base" to base,"passed" to result.success,"elapsedMs" to result.elapsedMs,"message" to result.message));save()
        }
    }
    @Test fun configureKeyAndRunLiveChatAndReadOnlyTool() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("zenLiveAcceptance")=="true")
        verifyTarget()
        val initialStore=ProviderStore(context)
        val previousDefault=initialStore.defaultId()
        val previousRecords=initialStore.list().associate {it.getString("id") to it.toString()}
        val previousCredentials=context.getSharedPreferences("native-credentials",0).all.toMap()
        val input=File(context.cacheDir,"opencode-zen-test-key")
        val key=if(input.exists())try {input.readText().trim()}finally {input.delete()} else {
            val savedStore=ProviderStore(context)
            val saved=savedStore.list().single {it.optString("name")==providerName}
            requireNotNull(savedStore.credential(saved.getString("id")))
        }
        require(key.isNotBlank() && key.length<=8192 && key.none {it=='\r' || it=='\n'})
        val report=obj("passed" to false,"cloudCalled" to true,"display" to 4,"physical" to physical,"device" to android.os.Build.MODEL,"android" to android.os.Build.VERSION.RELEASE,"runs" to JSONArray())
        fun save()=File(context.filesDir,"$reportPrefix-live.json").writeText(report.toString(2))
        var activity:MainActivity?=null;var activeRun:String?=null;var activeHost:ProductHost?=null;var previousSession:String?=null;var activeConnection:String?=null
        try {
            val opened=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(4).toBundle()) as MainActivity
            activity=opened
            val initialReady=EmulatorRuntimeTestSupport.awaitReady(opened)
            assertFalse("Never interrupt existing tasks",initialReady.host.hasActiveRuns())
            previousSession=initialReady.host.selectedSession()
            val store=ProviderStore(context)
            val existing=store.list().firstOrNull {it.optString("kind")==OpenCodeZenCatalog.KIND && it.optString("name")==providerName}
            if(physical)assertNull("Physical acceptance must not overwrite an existing test provider",existing)
            ui {MainActivity::class.java.getDeclaredMethod("showProviderEditor",JSONObject::class.java,String::class.java).apply {isAccessible=true}.invoke(opened,existing,OpenCodeZenCatalog.KIND)}
            inst.waitForIdleSync()
            ui {
                views(opened.window.decorView).filterIsInstance<EditText>().single {it.hint.toString()=="服务名称（便于区分账号）"}.setText(providerName)
                opened.window.decorView.findViewWithTag<EditText>("provider-api-key").setText(key)
            }
            val catalog=OpenCodeZenCatalog(context)
            until {var status="";ui {status=opened.window.decorView.findViewWithTag<TextView>("model-catalog-status").text.toString()};status.contains("OpenCode Zen 免费模型 + Models.dev") && !status.contains("正在读取")}
            val models=catalog.models().objects();assertTrue(models.isNotEmpty());assertTrue(models.all {it.optBoolean("free")})
            val selected=listOf("glm-5.3-flash-free","big-pickle","nemotron-3.5-lightning-free").firstNotNullOfOrNull {id->models.firstOrNull {it.getString("id")==id}} ?: models.first()
            report.put("models",JSONArray(models.map {obj("id" to it.getString("id"),"contextWindow" to it.getInt("contextWindow"),"maxInput" to it.getInt("maxInput"),"maxOutput" to it.getInt("maxOutput"))})).put("model",selected.getString("id"));save()
            ui {
                opened.window.decorView.findViewWithTag<Spinner>("model-picker").setSelection(models.indexOf(selected))
                views(opened.window.decorView).filterIsInstance<EditText>().single {it.hint.toString()=="输出上限（tokens；留空跟随模型）"}.setText("4096")
            }
            inst.waitForIdleSync()
            ui {views(opened.window.decorView).filterIsInstance<Button>().single {it.text.toString()=="保存"}.performClick()}
            until {store.list().any {it.optString("name")==providerName}}
            val config=store.list().single {it.optString("name")==providerName}
            val providerId=config.getString("id");assertTrue("Requested credential must be encrypted in its own record",key==store.credential(providerId))
            store.setDefault(providerId)
            val ready=EmulatorRuntimeTestSupport.reloadIdle(opened);val host=ready.host;activeHost=host
            js(ready.executionWeb,"""(()=>{
              window.__zenWire=[];const requests=new Map();const native=window.ThorNative;const post=native.postMessage.bind(native);
              const receive=native.onmessage;native.onmessage=(event)=>{try {
                let h,bytes;if(typeof event.data==='string'){h=JSON.parse(event.data);bytes=h.bytes?Uint8Array.from(atob(h.bytes),c=>c.charCodeAt(0)):undefined;}
                else {const data=new Uint8Array(event.data),n=new DataView(data.buffer).getUint32(0);h=JSON.parse(new TextDecoder().decode(data.slice(4,4+n)));bytes=data.slice(4+n);}
                const row=requests.get(h.requestId);
                if(row&&h.type==='response.head')row.status=h.status;
                if(row&&row.status>=400&&h.type==='response.chunk'&&bytes){const text=new TextDecoder().decode(bytes);
                  if(text.includes('abusive traffic'))row.providerRejection='network_abuse';
                  else if(text.includes('only be used from within OpenCode'))row.providerRejection='client_contract';
                  else row.providerRejection='other';}
              }catch{}return receive(event);};
              native.postMessage=(raw)=>{try {
                let h,bytes;if(typeof raw==='string'){h=JSON.parse(raw);bytes=h.bytes?Uint8Array.from(atob(h.bytes),c=>c.charCodeAt(0)):undefined;}
                else {const data=new Uint8Array(raw),n=new DataView(data.buffer).getUint32(0);h=JSON.parse(new TextDecoder().decode(data.slice(4,4+n)));bytes=data.slice(4+n);}
                if(h.type==='request.start'&&bytes){const body=JSON.parse(new TextDecoder().decode(bytes));const row={
                  browserPreservesUserAgent:h.headers?.['user-agent']===${JSONObject.quote(OpenCodeZenRequestContract.userAgent)},
                  stream:body.stream===true,hasRead:body.tools?.some(t=>t.function?.name==='read')===true,
                  toolCount:body.tools?.length??0,bodyFields:Object.keys(body).sort()
                };window.__zenWire.push(row);requests.set(h.requestId,row);}
              }catch{}return post(raw);};return true;})()""")
            val connection=ProductHost::class.java.getDeclaredField("connection").apply {isAccessible=true}.get(host) as String
            activeConnection=connection
            fun command(value:JSONObject) {val response=host.rpc(connection,obj("op" to "ui","data" to value)).get(20,TimeUnit.SECONDS);assertTrue("Native UI command failed",response.isNull("error"))}
            ui {MainActivity::class.java.getDeclaredMethod("closeSettings").apply {isAccessible=true}.invoke(opened)}
            command(obj("op" to "new"));val sid=host.selectedSession()
            val runModel=InstrumentationRegistry.getArguments().getString("zenModel")?.let {id->models.single {it.getString("id")==id}} ?: selected
            val efforts=runModel.getJSONArray("efforts").let {a->(0 until a.length()).map {a.getString(it)}}
            val effort=if("low" in efforts)"low" else if("off" in efforts)"off" else efforts.first()
            command(obj("op" to "sessionOptions","sessionId" to sid,"modelId" to runModel.getString("id"),"thinkingEffort" to effort,"selectedSkills" to JSONArray()))
            for(cap in listOf("perception","screen_control","search","debug"))command(obj("op" to "capability","sessionId" to sid,"capability" to cap,"enabled" to false))
            val routing=OpenCodeSessionIds(context).forSession(sid)
            assertTrue(OpenCodeSessionIds.valid(routing));assertEquals(routing,OpenCodeSessionIds(context).forSession(sid))
            report.put("nativeSessionFormatValid",true).put("nativeSessionPersisted",true)
            report.put("providerConfigId",providerId).put("sessionId",sid).put("keyEncrypted",true).put("effort",effort).put("model",runModel.getString("id"));save()
            val marker="ZEN_THOR_LIVE_OK"
            for((index,prompt) in listOf("这是合成连接测试。请只回复 $marker，不调用任何工具。","这是合成工具测试。请先调用 skills_list 获取可用技能列表，不要调用其他工具。成功后简短回复 $marker。无需操作设备。", "这是只读测试。请调用 read，name 为 adb-operations，path 为 SKILL.md。不要调用其他工具，不操作设备。读取成功后只回复 $marker。").withIndex()) {
                val begun=host.prepareForRunner(prompt).get(20,TimeUnit.SECONDS)
                val runId=begun.getJSONObject("lease").getString("runId");activeRun=runId
                report.put("activeRunId",runId);save()
                js(ready.executionWeb,"window.runPreparedNative(JSON.parse(${JSONObject.quote(begun.toString())}));true")
                var completed=JSONObject()
                SqliteStore(context,"product.db").use {reader->
                    until(180) {
                        completed=reader.readableDatabase.rawQuery("SELECT payload FROM runs WHERE id=?",arrayOf(runId)).use {cursor->check(cursor.moveToFirst());JSONObject(cursor.getString(0))}
                        completed.optString("status") in setOf("completed","failed","cancelled","interrupted") && !host.hasActiveRuns()
                    }
                    val events=reader.readableDatabase.rawQuery("SELECT payload FROM events WHERE runId=? ORDER BY sequence",arrayOf(runId)).use {cursor->buildList {while(cursor.moveToNext())add(JSONObject(cursor.getString(0)))}}
                    val messages=reader.readableDatabase.rawQuery("SELECT payload FROM messages WHERE runId=?",arrayOf(runId)).use {cursor->buildList {while(cursor.moveToNext())add(JSONObject(cursor.getString(0)))}}
                    val answer=messages.filter {it.optString("role")=="assistant"}.joinToString("\n") {it.optString("text")}
                    val tools=events.filter {it.optString("type")=="tool_result"}.map {it.getJSONObject("data")}
                    val rounds=events.filter {it.optString("type")=="model_round"}.map {it.getJSONObject("data")}
                    val toolSucceeded=tools.any {it.optString("toolId")=="skills.list" && it.isNull("error")}
                    val readerSucceeded=tools.any {it.optString("toolId")=="skills.read" && it.isNull("error")}
                    report.put("wire",JSONArray(org.json.JSONTokener(js(ready.executionWeb,"JSON.stringify(window.__zenWire)")).nextValue() as String))
                    report.getJSONArray("runs").put(obj("index" to index,"runId" to runId,"status" to completed.optString("status"),"error" to completed.optString("error"),"transport" to completed.optJSONArray("modelTransport"),"markerMatches" to answer.contains(marker),"readOnlyToolSucceeded" to toolSucceeded,"skillReadSucceeded" to readerSucceeded,"modelRounds" to JSONArray(rounds)));save()
                    assertEquals("completed",completed.optString("status"));assertTrue("Expected synthetic marker",answer.contains(marker))
                    if(index==1)assertTrue("Real skills.list result required",toolSucceeded)
                    if(index==2)assertTrue("Real skills.read result required",readerSucceeded)
                }
                activeRun=null
            }
            ui {
                val view=opened.window.decorView;val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(context.filesDir,"$reportPrefix-live.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            report.put("passed",true).put("providerKeptConfigured",true)
        }catch(error:Throwable){report.put("failureType",error.javaClass.simpleName);throw error}
        finally {
            if(activeRun!=null && activeHost?.hasActiveRuns()==true)activeHost?.stopFromChat(runId=activeRun)
            if(physical) {
                if(previousDefault!=null)initialStore.setDefault(previousDefault)
                if(previousSession!=null && activeHost?.hasActiveRuns()==false && activeConnection!=null)
                    activeHost?.rpc(activeConnection,obj("op" to "ui","data" to obj("op" to "select","sessionId" to previousSession)))?.get(20,TimeUnit.SECONDS)
                report.put("originalDefaultRestored",initialStore.defaultId()==previousDefault)
                report.put("existingProvidersPreserved",previousRecords.all {(id,value)->initialStore.get(id)?.toString()==value})
                report.put("existingCredentialsPreserved",previousCredentials.all {(name,value)->context.getSharedPreferences("native-credentials",0).all[name]==value})
            }
            save();activity?.let {ui {it.finish()}}
        }
    }
}
