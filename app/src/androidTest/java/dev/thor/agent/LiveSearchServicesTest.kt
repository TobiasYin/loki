package dev.thor.agent

import android.content.Intent
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit opt-in. Real keys enter via private cache and never appear in test output. */
@RunWith(AndroidJUnit4::class)
class LiveSearchServicesTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun js(web:WebView,script:String):String {
        val done=CountDownLatch(1);var value=""
        inst.runOnMainSync {web.evaluateJavascript(script){value=it;done.countDown()}}
        assertTrue(done.await(10,TimeUnit.SECONDS));return value
    }
    private fun waitFor(seconds:Int=180,condition:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong())
        while(System.nanoTime()<deadline){if(condition())return;Thread.sleep(100)}
        fail("Search acceptance timed out")
    }
    @Test fun activateAutomaticSelectionAndVerifySharedCredential() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("configureAutomaticSearch")=="true")
        val prefs=context.getSharedPreferences("profiles",0)
        val deepseek=SearchServices.available(context).first {it.backend=="DeepSeek"}
        check(prefs.edit().putString("searchBackend","关闭").commit())
        assertNull(SearchServices.resolve(context))
        check(prefs.edit().putString("searchBackend","自动选择").commit())
        assertEquals("DeepSeek",SearchServices.resolve(context)?.backend)
        assertTrue(SearchServices.resolve(context)?.key==deepseek.key)
    }
    @Test fun realSearchAndDeepSeekFetchThroughAgent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSearch")=="true")
        val input=File(context.cacheDir,"tavily-test-key")
        if(input.exists()) {
            val key=try {input.readText().trim()} finally {assertTrue(input.delete())}
            CredentialVault(context).put("search.tavily",key)
        }
        val available=SearchServices.available(context)
        assertTrue("Tavily key required",available.any {it.backend=="Tavily"})
        val prefs=context.getSharedPreferences("profiles",0);val previous=prefs.getString("searchBackend",null)
        val report=JSONObject()
        var activity:MainActivity?=null
        try {
            val lower=dev.thor.tools.diagnostic.DisplayRoles(context).resolve(dev.thor.tools.diagnostic.displayFacts(context))?.second
            if(android.os.Build.MODEL.contains("Thor",true))assertNotNull("Thor search acceptance must use the lower screen",lower)
            val options=android.app.ActivityOptions.makeBasic().apply {if(lower!=null)setLaunchDisplayId(lower.id)}
            val opened=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),options.toBundle()) as MainActivity
            if(lower!=null)assertEquals(lower.id,opened.display!!.displayId)
            activity=opened
            inst.runOnMainSync {opened.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            for(backend in listOf("Tavily","DeepSeek")) {
                val service=available.firstOrNull {it.backend==backend} ?: continue
                val connection=SearchConnectionTest.run(service.url,service.key,backend)
                assertTrue(connection.message,connection.success)
                check(prefs.edit().putString("searchBackend",backend).commit())
                val ready=EmulatorRuntimeTestSupport.reloadIdle(opened)
                assertTrue(ready.host.onboardingSearchReady())
                val web=ready.web
                js(web,"document.getElementById('new').click()")
                Thread.sleep(300)
                val prompt=if(backend=="DeepSeek")"请先调用 web_search 查询 Android official，然后调用 web_fetch 读取 https://example.com 。最后用中文各写一句搜索和读取的结果并列出链接。必须实际调用这两个工具。" else "请调用 web_search 查询 Android official，只用搜索结果回答一句并附上来源链接。必须实际调用 web_search。"
                js(web,"document.getElementById('input').value=${JSONObject.quote(prompt)};document.getElementById('composer').requestSubmit()")
                waitFor(20){ready.host.hasActiveRuns()}
                waitFor { !ready.host.hasActiveRuns() }
                val state=SqliteStore(context,"product.db").use {it.load()}
                val runs=state.getJSONArray("runs");val run=runs.getJSONObject(runs.length()-1).getString("id")
                val events=state.getJSONArray("events").objects().filter {it.optString("runId")==run}
                val results=events.filter {it.optString("type")=="tool_result"}.map {it.getJSONObject("data")}
                for(tool in if(backend=="DeepSeek")listOf("web.search","web.fetch") else listOf("web.search")) {
                    val result=results.firstOrNull {it.optString("toolId")==tool} ?: error("$backend did not call $tool")
                    assertFalse("$backend $tool failed",result.has("error") && !result.isNull("error"))
                    val text=result.getJSONArray("content").getJSONObject(0).getString("text")
                    val payload=JSONObject(text)
                    if(tool=="web.search")assertTrue("Search results empty",payload.getJSONArray("results").length()>0)
                    else assertTrue("Fetch content empty",payload.getString("text").isNotBlank())
                    report.put("$backend:$tool",JSONObject().put("passed",true).put("payloadChars",text.length))
                }
            }
            File(context.filesDir,"live-search-services.json").writeText(report.toString(2))
        } finally {
            val edit=prefs.edit();if(previous==null)edit.remove("searchBackend") else edit.putString("searchBackend",previous);check(edit.commit())
            activity?.let {if(!it.isFinishing)inst.runOnMainSync {MainActivity::class.java.getDeclaredMethod("reloadRuntime").apply {isAccessible=true}.invoke(it)}}
        }
    }
}
