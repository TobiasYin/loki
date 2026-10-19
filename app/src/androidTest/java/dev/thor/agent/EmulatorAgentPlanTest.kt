package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in emulator-only live acceptance. A single private input Key configures all services.
 * Report contains synthetic text and public metadata only; no Key or provider response body. */
@RunWith(AndroidJUnit4::class)
class EmulatorAgentPlanTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun until(seconds:Int=40,condition:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong())
        while(System.nanoTime()<end){if(condition())return;Thread.sleep(100)}
        fail("AgentPlan emulator acceptance timed out")
    }
    private fun js(web:WebView,script:String):String {
        val done=CountDownLatch(1);var result=""
        instrumentation.runOnMainSync {web.evaluateJavascript(script){result=it;done.countDown()}}
        assertTrue(done.await(5,TimeUnit.SECONDS));return result
    }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun invoke(activity:MainActivity,method:String)=instrumentation.runOnMainSync {MainActivity::class.java.getDeclaredMethod(method).apply {isAccessible=true}.invoke(activity)}
    @Test fun coldBootKeepsCredentialsAndThorScreenRoles() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("agentPlanColdBoot")=="true")
        assertTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val store=ProviderStore(context)
        assertEquals("volc-agentplan-emulator",store.defaultId())
        assertEquals("已加密保存",store.credentialStatus("volc-agentplan-emulator"))
        val key=store.credential("volc-agentplan-emulator")
        assertFalse(key.isNullOrBlank())
        assertTrue(key==VoiceServices.resolve(context)?.apiKey)
        assertTrue(key==SearchServices.resolve(context)?.key)
        val displays=context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val screens=JSONArray()
        for((id,size) in mapOf(0 to (1920 to 1080),4 to (1240 to 1080))) {
            val metrics=android.util.DisplayMetrics()
            requireNotNull(displays.getDisplay(id)).getRealMetrics(metrics)
            assertEquals(size.first,metrics.widthPixels);assertEquals(size.second,metrics.heightPixels);assertEquals(369,metrics.densityDpi)
            screens.put(obj("id" to id,"width" to metrics.widthPixels,"height" to metrics.heightPixels,"densityDpi" to metrics.densityDpi))
        }
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(4).toBundle()) as MainActivity
        try {
            EmulatorRuntimeTestSupport.awaitReady(activity)
            invoke(activity,"showVoiceSettingsPage");instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(4,activity.display!!.displayId)
                assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any {it.text.contains("已共享当前 AgentPlan Key")})
                val view=activity.window.decorView
                val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(context.filesDir,"emulator-agentplan-voice.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            File(context.filesDir,"emulator-agentplan-cold-boot.json").writeText(obj("passed" to true,"credentialsPersisted" to true,"sameKeyForVoiceAndSearch" to true,"screens" to screens,"uiDisplay" to 4).toString(2))
        }finally {instrumentation.runOnMainSync {activity.finish()}}
    }
    @Test fun cleanupSavedAcceptanceProvider() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("cleanupAgentPlan")=="true")
        assertTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val store=ProviderStore(context)
        store.get("volc-agentplan-emulator")?.let {store.delete(it.getString("id"))}
    }
    @Test fun singleKeyCatalogSelectionTextSearchAsrAndTts() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("agentPlanAcceptance")=="true")
        assertTrue("Emulator only",android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val display=InstrumentationRegistry.getArguments().getString("chatDisplay","4").toInt()
        val input=File(context.cacheDir,"agentplan-test-key")
        val key=try {input.readText().trim()}finally {assertTrue(input.delete())}
        require(key.isNotBlank() && key.length<=8192 && key.none {it=='\r' || it=='\n'})
        val store=ProviderStore(context);val config=store.save(obj("id" to "volc-agentplan-emulator","kind" to VolcAgentPlan.KIND,"name" to "火山 AgentPlan · 项目模拟器",
            "url" to VolcAgentPlan.BASE_URL,"protocol" to 0,"model" to "deepseek-v4.1-flash","contextBudget" to 1024000,"maxOutput" to 65536,"maxOutputText" to "2048"),key)
        store.setDefault(config.getString("id"))
        context.getSharedPreferences("voice",0).edit().putBoolean("standalone",false).commit()
        context.getSharedPreferences("profiles",0).edit().putString("searchBackend","自动选择").commit()
        context.getSharedPreferences("onboarding",0).edit().putBoolean("completed",true).commit()
        val report=obj("emulator" to true,"chatDisplay" to display,"credentials" to "one Native Keystore reference","source" to VolcAgentPlanCatalog.SOURCE)
        fun save()=File(context.filesDir,"emulator-agentplan.json").writeText(report.toString(2))
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()) as MainActivity
        try {
            val ready=EmulatorRuntimeTestSupport.awaitReady(activity);val web=ready.web
            assertEquals(display,activity.display!!.displayId)
            assertEquals(VolcAgentPlan.SEARCH_NAME,SearchServices.resolve(context)?.backend)
            assertTrue("ASR must resolve the same Native credential",key==VoiceServices.resolve(context)?.apiKey)
            assertEquals(VolcAgentPlan.ASR_ENDPOINT,VoiceServices.resolve(context)?.endpoint)
            val catalog=VolcAgentPlanCatalog(context)
            val models=catalog.refresh();assertTrue(models.length()>=10)
            report.put("discoveredModels",JSONArray(models.objects().map {it.getString("id")}));save()
            instrumentation.runOnMainSync {MainActivity::class.java.getDeclaredMethod("showProviderEditor",JSONObject::class.java,String::class.java).apply {isAccessible=true}.invoke(activity,store.get(config.getString("id")),VolcAgentPlan.KIND)}
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val all=views(activity.window.decorView)
                val picker=all.filterIsInstance<Spinner>().single {it.tag=="model-picker"}
                assertEquals(models.length(),picker.count)
                assertTrue(all.filterIsInstance<TextView>().any {it.text.contains("发现 / 刷新火山")})
                val keyField=all.filterIsInstance<TextView>().single {it.tag=="provider-api-key"}
                assertEquals("",keyField.text.toString())
                picker.setSelection(models.objects().indexOfFirst {it.getString("id")=="doubao-seed-2.1-lite"})
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val picker=views(activity.window.decorView).filterIsInstance<Spinner>().single {it.tag=="model-picker"}
                assertTrue(picker.selectedItem.toString().contains("doubao-seed-2.1-lite"))
                picker.setSelection(models.objects().indexOfFirst {it.getString("id")=="deepseek-v4.1-flash"})
            }
            instrumentation.waitForIdleSync()
            report.put("modelPicker",true).put("secretNotRefilled",true);save()
            invoke(activity,"closeSettings")
            val model=models.objects().single {it.getString("id")=="deepseek-v4.1-flash"}
            val connection=ModelConnectionTest.run(ModelConnectionTest.Draft(VolcAgentPlan.BASE_URL,"openai-responses",model.getString("id"),key,maxOutput=2048).withProfile(model,"2048"))
            report.put("textStreaming",obj("passed" to connection.success,"elapsedMs" to connection.elapsedMs));save()
            assertTrue(connection.message,connection.success)
            val search=SearchConnectionTest.run(VolcAgentPlan.SEARCH_ENDPOINT,key,VolcAgentPlan.SEARCH_NAME)
            report.put("search",obj("passed" to search.success,"elapsedMs" to search.elapsedMs));save();assertTrue(search.message,search.success)
            val beforeCount=js(web,"document.querySelectorAll('.session-row').length").toInt()
            js(web,"document.getElementById('new').click()")
            until {js(web,"document.querySelectorAll('.session-row').length===${beforeCount+1} && document.getElementById('send').dataset.running==='false' && !document.getElementById('new').disabled")=="true"}
            js(web,"document.getElementById('input').value='请调用 web_search 搜索 Android 官方文档，返回一个来源链接。';document.getElementById('composer').requestSubmit()")
            until(20){js(web,"document.getElementById('send').dataset.running==='true' || !document.getElementById('notice').hidden")=="true"}
            report.put("sendNotice",js(web,"document.getElementById('notice').textContent"));save()
            assertEquals("No Native send error expected","true",js(web,"document.getElementById('notice').hidden"))
            until(180){js(web,"document.getElementById('send').dataset.running==='false'")=="true"}
            val state=SqliteStore(context,"product.db").use {it.load()}
            val run=state.getJSONArray("runs").objects().last()
            val results=state.getJSONArray("events").objects().filter {it.optString("runId")==run.getString("id") && it.optString("type")=="tool_result"}
            val searched=results.any {it.getJSONObject("data").optString("toolId")=="web.search" && it.getJSONObject("data").isNull("error")}
            report.put("agentSearch",obj("status" to run.getString("status"),"toolSucceeded" to searched));save()
            assertEquals("completed",run.getString("status"));assertTrue("Real search tool result required",searched)
            val pcm=File(context.cacheDir,"agentplan-reference.pcm").readBytes()+ByteArray(32000)
            val eof=CountDownLatch(1);val terminal=CountDownLatch(1);val closed=CountDownLatch(1);val stopped=AtomicBoolean(false);var index=0
            val source=object:PcmInput {
                override fun start(){}
                override fun read(bytes:ByteArray):Int {
                    Thread.sleep(200);if(stopped.get())return -1
                    val size=minOf(bytes.size,pcm.size-index)
                    if(size>0){System.arraycopy(pcm,index,bytes,0,size);index+=size;return size}
                    eof.countDown();bytes.fill(0);return bytes.size
                }
                override fun stop(){stopped.set(true)}
                override fun close(){closed.countDown()}
            }
            val events=CopyOnWriteArrayList<JSONObject>()
            val voice=VoiceInput(requireNotNull(VoiceServices.resolve(context)),{source},{event->events.add(event);if(event.optString("phase") in setOf("complete","empty","error"))terminal.countDown()})
            try {
                voice.start();assertTrue("Reference not consumed",eof.await(40,TimeUnit.SECONDS));assertFalse(events.any {it.optString("phase")=="complete"});voice.finish()
                assertTrue(terminal.await(25,TimeUnit.SECONDS));assertTrue(closed.await(5,TimeUnit.SECONDS))
                val final=events.last();report.put("asr",obj("source" to "known synthetic PCM, emulator network; no microphone","phase" to final.optString("phase"),"phraseMatched" to final.optString("submit").contains("语音测试成功")));save()
                assertEquals("complete",final.optString("phase"));assertTrue(final.optString("submit").contains("语音测试成功"))
            }finally {voice.close()}
            val speech=VolcPlanSpeech()
            try {speech.play(key,"火山套餐语音合成测试成功。");report.put("tts",obj("passed" to true,"model" to "doubao-seed-tts-2.0","playback" to "emulator AudioTrack"));save()}finally {speech.close()}
            invoke(activity,"showVoiceSettingsPage");instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any {it.text.contains("合成并播放")})}
            instrumentation.runOnMainSync {
                val view=activity.window.decorView
                val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(context.filesDir,"emulator-agentplan-voice.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            report.put("voiceSettings",true).put("passed",true);save()
        }finally {save();instrumentation.runOnMainSync {activity.finish()}}
    }
}
