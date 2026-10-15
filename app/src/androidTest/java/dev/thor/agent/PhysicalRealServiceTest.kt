package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit physical-only acceptance. Secrets enter Native private cache via stdin,
 * are imported into Keystore-backed storage, and never enter WebView or test output. */
@RunWith(AndroidJUnit4::class)
class PhysicalRealServiceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun js(web: WebView, script: String): String {
        val latch=CountDownLatch(1);var result=""
        instrumentation.runOnMainSync {web.evaluateJavascript(script) {result=it;latch.countDown()}}
        assertTrue(latch.await(5,TimeUnit.SECONDS));return result
    }
    private fun wait(web: WebView, condition: String, seconds: Int=180) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong())
        while(System.nanoTime()<deadline) {if(js(web,condition)=="true")return;Thread.sleep(100)}
        fail("Physical service condition timed out")
    }
    @Test fun liveResponsesProbeDeviceInfoAndBraveSearch() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Live service acceptance requires explicit opt-in",args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val prefs=context.getSharedPreferences("profiles",0)
        if(args.getString("reuseProfile") != "true") {
        val input=File(context.cacheDir,"physical-credentials.json")
        val config=try {JSONObject(input.readText())} finally {assertTrue(input.delete())}
        val vault=CredentialVault(context)
        vault.put("model",config.getString("modelKey"));vault.put("search",config.getString("searchKey"))
        assertTrue(prefs.edit().clear().putString("name","OpenCode Go · DeepSeek V4.1 Flash")
            .putString("url","https://opencode.ai/zen/go/v1").putString("model","deepseek-v4.1-flash")
            .putInt("protocol",0).putInt("revision",1001).putString("probe","untested")
            .putInt("contextBudget",32768).putInt("maxOutput",2048)
            .putString("searchBackend","Brave").putString("searchUrl","https://api.search.brave.com/res/v1/web/search").commit())
        config.remove("modelKey");config.remove("searchKey")
        } else {
            assertEquals("https://opencode.ai/zen/go/v1",prefs.getString("url",null))
            assertEquals("deepseek-v4.1-flash",prefs.getString("model",null))
            assertEquals("已加密保存",CredentialVault(context).status("model"))
        }
        val display=args.getString("chatDisplay")!!.toInt()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()) as MainActivity
        instrumentation.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val web=activity.findViewById<WebView>(android.R.id.custom)
        val host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val evidence=JSONObject().put("model","deepseek-v4.1-flash").put("endpoint","https://opencode.ai/zen/go/v1/responses").put("protocol","openai-responses").put("credentialsStored","native-keystore").put("chatDisplay",display)
        // Retain only redacted HTTP error diagnostics in the Native test process.
        // Production continues to discard upstream error bodies from chat/history.
        val errorBytes=java.io.ByteArrayOutputStream()
        var failedRequest=false
        val bridge=MainActivity::class.java.getDeclaredField("bridge").apply {isAccessible=true}.get(activity)!!
        val observer=bridge.javaClass.getDeclaredField("networkObserver").apply {isAccessible=true}
        observer.set(bridge, {start:dev.thor.bridge.HttpStart,event:dev.thor.bridge.HttpEvent ->
            host.auditHttp(start,event)
            when(event) {
                is dev.thor.bridge.HttpEvent.Head -> failedRequest=event.status>=400
                is dev.thor.bridge.HttpEvent.Chunk -> if(failedRequest && errorBytes.size()<4096)errorBytes.write(event.bytes,0,minOf(event.bytes.size,4096-errorBytes.size()))
                else -> Unit
            }
        })
        fun save() {File(context.filesDir,"physical-live-services.json").writeText(evidence.toString(2))}
        fun newSession() {
            val count=js(web,"document.querySelectorAll('.session-row').length").toInt()
            js(web,"document.getElementById('new').click()")
            wait(web,"document.querySelectorAll('.session-row').length === ${count+1}",10)
        }
        fun request(label: String, text: String, expectedTool: String) {
            newSession()
            js(web,"document.getElementById('input').value=${JSONObject.quote(text)};document.getElementById('composer').requestSubmit()")
            wait(web,"document.getElementById('send').textContent === '停止'",10)
            wait(web,"document.getElementById('send').textContent === '发送'")
            val state=SqliteStore(context,"product.db").use {it.load()}
            val run=state.getJSONArray("runs").objects().last()
            val results=state.getJSONArray("events").objects().filter {it.getString("runId")==run.getString("id") && it.getString("type")=="tool_result"}
            evidence.put(label,obj("runId" to run.getString("id"),"status" to run.getString("status"),"results" to JSONArray(results.map {it.getJSONObject("data")})))
            save()
            assertEquals("completed",run.getString("status"))
            assertTrue("Required live tool result missing",results.any {it.getJSONObject("data").optString("toolId")==expectedTool && it.getJSONObject("data").isNull("error")})
        }
        try {
            wait(web,"window.productReady === true && document.querySelectorAll('.session-row').length > 0 && !document.getElementById('new').disabled",30);assertEquals(display,activity.display!!.displayId)
            newSession()
            var token="";var sid=""
            instrumentation.runOnMainSync {token=host.requestProbe();sid=host.selectedSession()}
            js(web,"window.runNativeProbe(${JSONObject.quote(token)},${JSONObject.quote(sid)});true")
            wait(web,"document.getElementById('send').textContent === '停止'",10)
            wait(web,"document.getElementById('send').textContent === '发送'")
            val state=SqliteStore(context,"product.db").use {it.load()}
            val run=state.getJSONArray("runs").objects().last()
            val heads=ProductHost::class.java.getDeclaredField("probeHeads").apply {isAccessible=true}.get(host) as Map<*,*>
            evidence.put("probe",obj("status" to prefs.getString("probe","untested"),"runId" to run.getString("id"),"httpStatuses" to JSONArray(heads.values.toList()),"contexts" to run.getJSONArray("contexts").length(),"events" to JSONArray(state.getJSONArray("events").objects().filter {it.getString("runId")==run.getString("id")})))
            if(prefs.getString("probe",null) != "passed") {
                val raw=host.policy.vault.redact(errorBytes.toString("UTF-8"))
                val error=runCatching {JSONObject(raw).opt("error")?.toString()}.getOrNull()
                evidence.put("probeError",error?.take(1000) ?: "No structured HTTP error")
                // Independent Native connectivity diagnostics, explicitly distinct from
                // the product Pi/Fetch gate; no credentials cross into JavaScript.
                fun diagnostic(url: String, body: String?, header: String, key: String): JSONObject {
                    val connection=java.net.URL(url).openConnection() as javax.net.ssl.HttpsURLConnection
                    connection.instanceFollowRedirects=false;connection.connectTimeout=15000;connection.readTimeout=30000
                    connection.setRequestProperty(header,key);connection.setRequestProperty("Accept","application/json")
                    if(connection.url.host == "opencode.ai")connection.setRequestProperty("x-opencode-session",host.selectedSession())
                    try {
                        if(body!=null) {connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use {it.write(body.toByteArray())}}
                        val status=connection.responseCode
                        val stream=if(status in 200..299)connection.inputStream else connection.errorStream
                        val text=stream?.use {it.readNBytes(32768).toString(Charsets.UTF_8)}.orEmpty()
                        val data=runCatching {JSONObject(text)}.getOrNull()
                        return obj("httpStatus" to status,"error" to data?.opt("error")?.toString()?.let {host.policy.vault.redact(it).take(1000)},"searchResults" to data?.optJSONObject("web")?.optJSONArray("results")?.length(),"responseId" to data?.optString("id"))
                    } catch(e:Exception) {return obj("exceptionClass" to e.javaClass.simpleName)} finally {connection.disconnect()}
                }
                val nativeVault=CredentialVault(context)
                evidence.put("nativeMinimalResponses",diagnostic("https://opencode.ai/zen/go/v1/responses",obj("model" to "deepseek-v4.1-flash","input" to "Reply OK only.","stream" to false,"max_output_tokens" to 64).toString(),"Authorization","Bearer ${nativeVault.readNative("model")!!}"))
                evidence.put("nativeBraveConnectivity",diagnostic("https://api.search.brave.com/res/v1/web/search?q=AYN%20Thor%20official&count=3",null,"X-Subscription-Token",nativeVault.readNative("search")!!))
            }
            save();assertEquals("passed",prefs.getString("probe",null))
            request("deviceInfo","请调用 perception_device_info 工具，sections 只选 system，读取当前真实设备型号与 Android 版本，然后用中文简短报告。不要调用其他工具。","perception.device_info")
            request("braveSearch","请只调用一次 web_search，查询 AYN Thor official specifications，返回搜索结果中一个来源链接和一行摘要。不要调用 web_fetch 或其他工具。","web.search")
            val bitmap=android.graphics.Bitmap.createBitmap(web.width,web.height,android.graphics.Bitmap.Config.ARGB_8888)
            instrumentation.runOnMainSync {web.draw(android.graphics.Canvas(bitmap))}
            File(context.filesDir,"physical-live-chat.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            evidence.put("passed",true);save()
        } finally {save();instrumentation.runOnMainSync {activity.finish()};instrumentation.waitForIdleSync()}
    }
}
