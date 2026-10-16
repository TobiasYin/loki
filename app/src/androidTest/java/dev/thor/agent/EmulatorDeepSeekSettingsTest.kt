package dev.thor.agent

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Emulator-only UI/contract test. Uses disposable fixture keys, never production keys or cloud calls. */
@RunWith(AndroidJUnit4::class)
class EmulatorDeepSeekSettingsTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun ui(action:()->Unit) {if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())action() else inst.runOnMainSync(action)}
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList())
    private fun show(activity:MainActivity) {ui {MainActivity::class.java.getDeclaredMethod("showSettings",String::class.java).apply {isAccessible=true}.invoke(activity,"model")};inst.waitForIdleSync()}
    private fun field(a:MainActivity,hint:String):EditText {var v:EditText?=null;ui {v=views(a.window.decorView).filterIsInstance<EditText>().single {it.hint?.toString()==hint}};return v!!}
    private fun button(a:MainActivity,text:String):Button {var v:Button?=null;ui {v=views(a.window.decorView).filterIsInstance<Button>().single {it.text.toString()==text}};return v!!}
    private fun until(seconds:Int=20,check:()->Boolean) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong());while(System.nanoTime()<end){if(check())return;Thread.sleep(100)};fail("Emulator settings condition did not become ready")}
    private fun js(web:WebView,code:String):String {val latch=CountDownLatch(1);var value="";ui {web.evaluateJavascript(code){value=it;latch.countDown()}};assertTrue(latch.await(10,TimeUnit.SECONDS));return value}
    private fun status(a:MainActivity):String {var value="";ui {value=a.window.decorView.findViewWithTag<TextView>("model-test-status").text.toString()};return value}
    private fun snapshot(a:MainActivity,name:String) {val image=inst.uiAutomation.takeScreenshot();try {File(context.filesDir,name).outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally {image.recycle()}}
    private fun nativeHost(a:MainActivity)=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(a) as ProductHost
    private fun assertOfficialBeginWithoutCloud(a:MainActivity,provider:String) {
        val host=nativeHost(a);val connection=ProductHost::class.java.getDeclaredField("connection").apply {isAccessible=true}.get(host) as String
        val check=host.rpc(connection,obj("op" to "profile.check","data" to obj("sessionId" to host.selectedSession(),"checkOnly" to true))).get(10,TimeUnit.SECONDS)
        assertEquals(provider,check.getString("provider"));assertTrue(check.getBoolean("configured"));assertTrue(check.getBoolean("metadataKnown"));assertFalse(check.getBoolean("probeNeeded"));assertFalse(check.getBoolean("probeRequired"));assertFalse(check.has("token"))
        // Deliberately do not call runPreparedNative: this verifies only the Native begin gate.
        val begun=host.prepareForRunner("Emulator provider admission fixture; no model execution.").get(10,TimeUnit.SECONDS)
        val lease=begun.getJSONObject("lease")
        try {assertEquals(host.selectedSession(),lease.getString("sessionId"));assertEquals(0,begun.getInt("runTimeoutMs"));assertEquals(0,begun.getJSONObject("network").getInt("timeoutMs"))}
        finally {val ended=host.rpc(connection,obj("op" to "end","data" to lease)).get(10,TimeUnit.SECONDS);assertFalse(ended.toString(),ended.has("error"))}
        assertFalse("Admission must not falsify a configuration probe",context.getSharedPreferences("profiles",0).getString("probe","")=="passed")
    }
    @Test fun emptyConfigurationShowsOfficialDefaultsWithoutRequests() {
        assertTrue("Emulator only",android.os.Build.HARDWARE in setOf("ranchu","goldfish") && android.os.Build.MODEL.contains("sdk"))
        assertFalse("Requires an emulator without a model key",context.getSharedPreferences("native-credentials",0).contains("model"))
        val prefs=context.getSharedPreferences("profiles",0)
        val oldProvider=prefs.getString("provider",null);val oldUrl=prefs.getString("url",null)
        var activity:MainActivity?=null
        try {
            check(prefs.edit().remove("provider").remove("url").commit())
            val opened=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity;activity=opened
            EmulatorRuntimeTestSupport.reloadIdle(opened);show(opened)
            ui {assertEquals(2,opened.window.decorView.findViewWithTag<Spinner>("model-provider").selectedItemPosition)}
            assertEquals(DeepSeekCatalog.BASE_URL,field(opened,"模型基础 URL（HTTP / HTTPS）").text.toString())
            assertEquals("deepseek-flash",field(opened,"模型 ID").text.toString())
            ui {button(opened,"测试当前填写的连接").performClick()}
            assertTrue(status(opened).contains("请填写该服务有效的 API Key"));assertFalse(prefs.contains("provider"));assertFalse(prefs.contains("url"))
            snapshot(opened,"deepseek-default-settings.png")
            File(context.filesDir,"emulator-deepseek-defaults.json").writeText(JSONObject().put("passed",true).put("emptyConfigurationDefaultsToOfficial",true).put("officialCloudCalled",false).toString(2))
        }finally {
            val edit=prefs.edit();if(oldProvider==null)edit.remove("provider") else edit.putString("provider",oldProvider);if(oldUrl==null)edit.remove("url") else edit.putString("url",oldUrl);check(edit.commit())
            activity?.let {try {EmulatorRuntimeTestSupport.reloadIdle(it)} finally {ui {it.finish()}}};inst.waitForIdleSync()
        }
    }
    @Test fun officialDropdownCredentialBoundarySaveAndControlledDraftTest() {
        assertTrue("Never run this fixture test on a physical device",android.os.Build.HARDWARE in setOf("ranchu","goldfish") && android.os.Build.MODEL.contains("sdk"))
        val prefs=context.getSharedPreferences("profiles",0)
        assertFalse("Requires an emulator without a saved model key",context.getSharedPreferences("native-credentials",0).contains("model"))
        val before=HashMap(prefs.all);val vault=CredentialVault(context)
        val report=JSONObject().put("scope","emulator Native settings and controlled loopback SSE; not official cloud acceptance").put("officialCloudCalled",false)
        var launched:MainActivity?=null
        try {
            check(prefs.edit().putString("provider","opencode-go").putString("url","https://opencode.ai/zen/go/v1").putString("model","deepseek-v4.1-flash").putString("probe","untested").putInt("revision",1).putInt("protocol",0).commit())
            vault.put("model","go-fixture-not-a-real-key")
            val activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            launched=activity
            EmulatorRuntimeTestSupport.reloadIdle(activity)
            assertOfficialBeginWithoutCloud(activity,"opencode-go");report.put("goAdmissionWithoutProbeOrCloud",true)
            show(activity)
            var provider:Spinner?=null;var picker:Spinner?=null
            ui {provider=activity.window.decorView.findViewWithTag("model-provider");picker=activity.window.decorView.findViewWithTag("model-picker");assertEquals(0,provider!!.selectedItemPosition);provider!!.setSelection(2)}
            inst.waitForIdleSync()
            assertEquals("",field(activity,"API Key（留空保留）").text.toString())
            ui {assertEquals(2,picker!!.adapter.count);assertEquals("DeepSeek-V4.1-Flash",picker!!.adapter.getItem(0));assertEquals("DeepSeek-V4-Pro",picker!!.adapter.getItem(1));assertTrue(picker!!.performClick())}
            inst.waitForIdleSync();snapshot(activity,"deepseek-model-dropdown.png")
            assertTrue(inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));inst.waitForIdleSync()
            ui {picker!!.setSelection(1)};inst.waitForIdleSync()
            assertEquals("deepseek-v4-pro",field(activity,"模型 ID").text.toString())
            var metadata="";ui {metadata=activity.window.decorView.findViewWithTag<TextView>("model-catalog-status").text.toString()}
            assertTrue(metadata.contains("1048576"));assertTrue(metadata.contains("仅支持文字"));assertTrue(metadata.contains("默认高"))
            ui {button(activity,"测试当前填写的连接").performClick()}
            assertTrue(status(activity).contains("请填写该服务有效的 API Key"));assertEquals("go-fixture-not-a-real-key",vault.readNative("model"))
            report.put("crossProviderBlankKeyRejected",true).put("officialModelsSelectable",true).put("metadataAccurate",true)
            ui {field(activity,"API Key（留空保留）").setText("official-fixture-not-a-real-key")}
            val oldRuntime=EmulatorRuntimeTestSupport.awaitReady(activity)
            assertFalse("Saving settings must not interrupt a running task",oldRuntime.host.hasActiveRuns())
            ui {button(activity,"保存").performClick()}
            val newRuntime=EmulatorRuntimeTestSupport.awaitReady(activity,oldRuntime.web)
            assertNotSame("Settings save must replace the idle profile host",oldRuntime.host,newRuntime.host)
            assertFalse("Settings save reuses the Activity",activity.isDestroyed)
            report.put("settingsSaveReloadedIdleServiceHost",true).put("settingsSaveKeptActivity",true)
            val reopened=activity
            assertEquals("deepseek",prefs.getString("provider",null));assertEquals(DeepSeekCatalog.BASE_URL,prefs.getString("url",null))
            assertEquals("deepseek-v4-pro",prefs.getString("model",null));assertEquals("",prefs.getString("headerName",null));assertEquals(0,prefs.getInt("protocol",-1))
            assertEquals("official-fixture-not-a-real-key",vault.readNative("model"));assertFalse(prefs.all.toString().contains("fixture-not-a-real-key"))
            assertEquals("false",js(reopened.findViewById(android.R.id.custom),"document.documentElement.outerHTML.includes('official-fixture-not-a-real-key')"))
            val host=nativeHost(reopened)
            val selection=host.view().getJSONObject("modelSelection")
            assertEquals("deepseek",selection.getString("provider"));assertEquals("deepseek-v4-pro",selection.getString("modelId"))
            assertEquals(1048576,selection.getInt("contextWindow"));assertEquals("high",selection.getString("thinkingEffort"))
            assertEquals("[\"text\"]",selection.getJSONArray("input").toString());assertTrue(selection.getBoolean("providerMetadataKnown"));assertFalse(selection.getBoolean("probeRequired"))
            assertFalse(selection.toString().contains("official-fixture-not-a-real-key"));report.put("officialProfileMetadataAndNoProbeGate",true)
            assertOfficialBeginWithoutCloud(reopened,"deepseek");report.put("officialAdmissionWithoutProbeOrCloud",true)
            show(reopened);snapshot(reopened,"deepseek-settings-saved.png");report.put("officialSaveAndReopen",true).put("keyExcludedFromWebViewAndProfilePrefs",true)
            ui {provider=reopened.window.decorView.findViewWithTag("model-provider");provider!!.setSelection(1)};inst.waitForIdleSync()
            ServerSocket(0,2,InetAddress.getByName("127.0.0.1")).use {server->
                server.soTimeout=15000
                val received=mutableListOf<JSONObject>();var fixtureError:Throwable?=null
                val thread=Thread {
                    try {for(code in listOf(200,401))server.accept().use {socket->
                        socket.soTimeout=10000;val input=socket.getInputStream().bufferedReader();val first=input.readLine();val headers=mutableMapOf<String,String>()
                        while(true){val line=input.readLine() ?: error("Missing headers");if(line.isEmpty())break;headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
                        val chars=CharArray(headers.getValue("content-length").toInt());var n=0;while(n<chars.size){val read=input.read(chars,n,chars.size-n);if(read<0)error("Incomplete body");n+=read}
                        val body=JSONObject(String(chars));received.add(body)
                        assertEquals("POST /responses HTTP/1.1",first);assertEquals("Bearer loopback-fixture-key",headers["authorization"]);assertEquals("deepseek-flash",body.getString("model"));assertTrue(body.getBoolean("stream"))
                        val text=if(code==200)"event: response.output_text.delta\ndata: {\"delta\":\"连接成功\"}\n\n" else "{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"fixture rejected\"}}"
                        val bytes=text.toByteArray();socket.getOutputStream().write(("HTTP/1.1 $code Result\r\nContent-Type: "+if(code==200)"text/event-stream" else "application/json").toByteArray())
                        socket.getOutputStream().write("\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());socket.getOutputStream().write(bytes);socket.getOutputStream().flush()
                    }}catch(error:Throwable){fixtureError=error}
                }.apply {start()}
                ui {field(reopened,"模型基础 URL（HTTP / HTTPS）").setText("http://127.0.0.1:${server.localPort}");field(reopened,"模型 ID").setText("deepseek-flash");field(reopened,"API Key（留空保留）").setText("loopback-fixture-key")}
                val test=button(reopened,"测试当前填写的连接")
                ui {test.performClick()};until {var enabled=false;ui {enabled=test.isEnabled};enabled}
                assertTrue(status(reopened),status(reopened).contains("文本流式连接可用"));report.put("controlledSseDraftPassed",true)
                ui {test.performClick()};until {var enabled=false;ui {enabled=test.isEnabled};enabled}
                assertTrue(status(reopened),status(reopened).contains("401"));report.put("controlledAuthenticationFailureDisplayed",true)
                thread.join(10000);assertFalse(thread.isAlive);fixtureError?.let {throw AssertionError("Controlled fixture failed",it)};assertEquals(2,received.size)
                assertEquals("deepseek",prefs.getString("provider",null));assertEquals("official-fixture-not-a-real-key",vault.readNative("model"))
                snapshot(reopened,"deepseek-draft-failure.png")
            }
            report.put("passed",true)
        }catch(error:Throwable){report.put("passed",false).put("failureType",error.javaClass.simpleName);throw error}
        finally {
            vault.remove("model")
            val edit=prefs.edit().clear();for((key,value) in before)when(value){is String->edit.putString(key,value);is Int->edit.putInt(key,value);is Boolean->edit.putBoolean(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value);is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())};check(edit.commit())
            launched?.let {try {EmulatorRuntimeTestSupport.reloadIdle(it)} finally {ui {it.finish()}}};inst.waitForIdleSync()
            report.put("fixtureCredentialsRemoved",!context.getSharedPreferences("native-credentials",0).contains("model"))
            File(context.filesDir,"emulator-deepseek-settings.json").writeText(report.toString(2))
        }
    }
}
