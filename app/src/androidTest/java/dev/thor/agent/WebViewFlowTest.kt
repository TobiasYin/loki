package dev.thor.agent

import android.content.Intent
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Real product WebView -> locked Pi -> native Fetch -> local model fixture. No runtime/network port mocks. */
@RunWith(AndroidJUnit4::class)
class WebViewFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val chatDisplay get() = args.getString("chatDisplay")?.toInt() ?: 0
    private fun javascript(web: WebView, script: String): String {
        var value="";val latch=CountDownLatch(1)
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { value=it;latch.countDown() } }
        assertTrue("WebView callback timeout",latch.await(5,TimeUnit.SECONDS));return value
    }
    private fun waitFor(web: WebView, condition: String, seconds: Int=30) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong())
        while(System.nanoTime()<end) { if(javascript(web,condition)=="true")return;Thread.sleep(100) }
        fail("WebView condition not met: $condition; body=${javascript(web,"document.body?.textContent")}")
    }
    private fun configure(protocol: Int, revision: Int) {
        // Fixture runs deliberately replace the test app's database/connection. A physical
        // runner may opt in only after proving it installed a new disposable app instance.
        assertEquals("Fixture requires disposableInstallation=true", "true", args.getString("disposableInstallation"))
        val existingUrl=context.getSharedPreferences("profiles",0).getString("url","").orEmpty()
        assertTrue("Existing live profile must not be reset by fixture tests",existingUrl.isBlank() || existingUrl in setOf("http://127.0.0.1:8877/v1","http://127.0.0.1:8877"))
        context.deleteDatabase("product.db")
        val base = if(protocol==0) "http://127.0.0.1:8877/v1" else "http://127.0.0.1:8877"
        assertTrue(context.getSharedPreferences("profiles",0).edit().clear().putString("provider","custom").putString("name","Integration Fixture").putString("url",base).putString("model","fixture-model").putInt("protocol",protocol).putInt("revision",revision).putString("probe","untested").commit())
        CredentialVault(context).put("model","integration-only-secret")
        CredentialVault(context).put("search","integration-search-secret")
        assertTrue(context.getSharedPreferences("profiles",0).edit().putString("searchBackend","Brave").putString("searchUrl","http://127.0.0.1:8877/res/v1/web/search").commit())
    }
    private fun launch(): MainActivity = instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK), android.app.ActivityOptions.makeBasic().setLaunchDisplayId(chatDisplay).toBundle()) as MainActivity
    private fun host(activity: MainActivity): ProductHost = MainActivity::class.java.getDeclaredField("host").apply { isAccessible=true }.get(activity) as ProductHost
    private fun finish(activity: MainActivity) { instrumentation.runOnMainSync {activity.finish()}; instrumentation.waitForIdleSync();Thread.sleep(500) }
    private fun send(web: WebView, text: String) { javascript(web,"document.getElementById('input').value=${JSONObject.quote(text)};document.getElementById('composer').requestSubmit()") }
    private fun probe(activity: MainActivity, web: WebView) {
        var token="";var sid=""
        instrumentation.runOnMainSync { token=host(activity).requestProbe();sid=host(activity).selectedSession() }
        javascript(web,"window.runNativeProbe(${JSONObject.quote(token)},${JSONObject.quote(sid)});true")
        waitFor(web,"document.getElementById('send').textContent === '停止'",60)
        waitFor(web,"document.getElementById('send').textContent === '发送'",30)
        assertEquals("passed",context.getSharedPreferences("profiles",0).getString("probe",null))
    }
    @Test fun allProtocolsImagesToolRoundTripTextDevicePersistenceErrorsAndCancellation() {
        for(protocol in 0..2) {
            configure(protocol, protocol+1)
            val activity=launch();val web=activity.findViewById<WebView>(android.R.id.custom)
            try {
                waitFor(web,"window.productReady === true && document.querySelectorAll('.session-row').length > 0")
                // Probe runs remain Native-owned and are hidden from the conversation.
                probe(activity,web)
                assertFalse(javascript(web,"document.getElementById('chat').textContent").contains("PROBE_OK"))
                val receipt=SqliteStore(context,"product.db").use {it.load().getJSONArray("runs").objects().last().getJSONObject("probeTransport")}
                java.io.File(context.filesDir,"integrated-probe-$protocol.json").writeText(receipt.toString(2))
                if(protocol==2)assertTrue("Delayed EOF must exercise successful SDK reader close: $receipt",receipt.getInt("clientClosedAfterBodyRequests")>0)
                send(web,"DEVICE_INFO: inspect actual device")
                waitFor(web,"document.getElementById('send').textContent === '停止'")
                waitFor(web,"document.getElementById('send').textContent === '发送'")
                var data=SqliteStore(context,"product.db").use {it.load()}
                val latest=data.getJSONArray("runs").objects().last()
                assertEquals("completed",latest.getString("status"))
                assertTrue(data.getJSONArray("events").objects().any {it.optString("toolCallId").isNotBlank() && it.getString("type")=="tool_result" && it.getJSONObject("data").getString("source")=="android-sdk/accessibility"})
                assertTrue(latest.getJSONArray("contexts").length()>=2)
                assertTrue(latest.getJSONObject("turn").getBoolean("complete"))
                assertFalse(data.toString().contains("integration-only-secret"));assertFalse(javascript(web,"document.body.textContent").contains("integration-only-secret"))
                assertEquals("false",javascript(web,"document.querySelectorAll('details.run')[document.querySelectorAll('details.run').length-1].open"))
                send(web,"WEB_SEARCH: get source results")
                waitFor(web,"document.getElementById('send').textContent === '停止'"); waitFor(web,"document.getElementById('send').textContent === '发送'")
                assertTrue(SqliteStore(context,"product.db").use {it.load().getJSONArray("events").objects().any {it.getString("type")=="tool_result" && it.getJSONObject("data").optString("toolId")=="web.search" && it.getJSONObject("data").optString("source")=="BraveSearch"}})
                val bitmap=android.graphics.Bitmap.createBitmap(web.width,web.height,android.graphics.Bitmap.Config.ARGB_8888)
                instrumentation.runOnMainSync {web.draw(android.graphics.Canvas(bitmap))}
                java.io.File(context.filesDir,"integrated-chat.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                send(web,"HTTP_429")
                // A fast error may finish before the coalesced UI paints a running frame.
                waitFor(web,"document.getElementById('chat').textContent.includes('HTTP_429') && document.getElementById('send').textContent === '发送'")
                assertEquals("failed",SqliteStore(context,"product.db").use {it.load().getJSONArray("runs").objects().last().getString("status")})
                send(web,"WAIT_CANCEL")
                waitFor(web,"document.getElementById('send').textContent === '停止'")
                javascript(web,"document.getElementById('send').click()")
                waitFor(web,"document.getElementById('send').textContent === '发送'")
                data=SqliteStore(context,"product.db").use {it.load()};assertEquals("cancelled",data.getJSONArray("runs").objects().last().getString("status"))
                // Revision edits cannot keep a previous successful probe.
                assertTrue(context.getSharedPreferences("profiles",0).edit().putInt("revision",protocol+30).commit())
                instrumentation.runOnMainSync {
                    assertEquals("https://appassets.androidplatform.net/assets/chat/index.html",web.url)
                    assertFalse(web.settings.allowFileAccess);assertFalse(web.settings.allowContentAccess)
                }
            } finally { finish(activity) }
        }
    }
    @Test fun cancelledConfigurationCheckNeverPasses() {
        configure(2,1051)
        val activity=launch();val web=activity.findViewById<WebView>(android.R.id.custom)
        try {
            waitFor(web,"window.productReady===true")
            var token="";instrumentation.runOnMainSync {token=host(activity).requestProbe()}
            javascript(web,"window.runNativeProbe(${JSONObject.quote(token)},${JSONObject.quote(host(activity).selectedSession())});true")
            waitFor(web,"document.getElementById('send').textContent==='停止'")
            javascript(web,"document.getElementById('send').click()")
            waitFor(web,"document.getElementById('send').textContent==='发送'")
            assertNotEquals("passed",context.getSharedPreferences("profiles",0).getString("probe",null))
            val r=SqliteStore(context,"product.db").use {it.load().getJSONArray("runs").objects().last()}
            assertEquals("cancelled",r.getString("status"));assertFalse(r.optBoolean("probePassed"))
        } finally {finish(activity)}
    }
    @Test fun textOnlyCustomProfileChecksToolsWithoutRequiringImages() {
        configure(2,1001)
        assertTrue(context.getSharedPreferences("profiles",0).edit().putBoolean("supportsImages",false).commit())
        val activity=launch();val web=activity.findViewById<WebView>(android.R.id.custom)
        try {
            waitFor(web,"window.productReady===true");probe(activity,web)
            assertEquals("[\"text\"]",host(activity).view().getJSONObject("modelSelection").getJSONArray("input").toString())
            assertEquals(0,SqliteStore(context,"product.db").use {it.load().getJSONArray("attachments").length()})
            send(web,"DEVICE_INFO: text-only profile remains usable")
            waitFor(web,"document.getElementById('send').textContent==='停止'");waitFor(web,"document.getElementById('send').textContent==='发送'")
            assertEquals("completed",SqliteStore(context,"product.db").use {it.load().getJSONArray("runs").objects().last().getString("status")})
            java.io.File(context.filesDir,"integrated-text-only-profile.json").writeText(obj("passed" to true,"imagesRequired" to false,"toolConnectionVerified" to true).toString(2))
        } finally {finish(activity)}
    }
    @Test fun fullSettingsPageDoesNotInterruptRunsAndKeepsVoiceConfigurationOutOfComposer() {
        configure(0,901)
        val activity=launch();val web=activity.findViewById<WebView>(android.R.id.custom)
        fun capture(name:String) {
            instrumentation.runOnMainSync {
                val view=activity.window.decorView;val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap));java.io.File(context.filesDir,name).outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
        }
        fun buttons(view:android.view.View):List<android.widget.Button> = when(view) {
            is android.widget.Button->listOf(view)
            is android.view.ViewGroup->(0 until view.childCount).flatMap {buttons(view.getChildAt(it))}
            else->emptyList()
        }
        try {
            waitFor(web,"window.productReady===true");probe(activity,web)
            assertEquals("true",javascript(web,"document.getElementById('voice-settings')===null && document.getElementById('notice').hidden"))
            capture("integrated-composer-native.png")
            send(web,"WAIT_CANCEL settings page")
            waitFor(web,"document.getElementById('send').textContent==='停止'")
            val rid=host(activity).view().getString("activeRunId")
            javascript(web,"document.getElementById('settings').click()")
            var page:android.view.View?=null
            repeat(30){instrumentation.runOnMainSync {page=activity.window.decorView.findViewWithTag("thor-settings-page")};if(page==null)Thread.sleep(50)}
            assertNotNull("Settings must be a full page in the Activity",page)
            capture("integrated-settings-native.png")
            instrumentation.runOnMainSync {buttons(page!!).single {it.text.toString()=="火山流式语音识别 ›"}.performClick()}
            capture("integrated-voice-settings-native.png")
            instrumentation.runOnMainSync {buttons(activity.window.decorView.findViewWithTag("thor-settings-page")).single {it.text.toString()=="‹ 返回"}.performClick()}
            instrumentation.runOnMainSync {buttons(activity.window.decorView.findViewWithTag("thor-settings-page")).single {it.text.toString()=="‹ 返回"}.performClick()}
            assertTrue(host(activity).view().getJSONArray("activeRuns").objects().any {it.getString("id")==rid && it.optString("status") !in terminal})
            javascript(web,"document.getElementById('send').click()")
            waitFor(web,"document.getElementById('send').textContent==='发送'")
            java.io.File(context.filesDir,"integrated-settings-page.json").writeText(obj("passed" to true,"fullPage" to true,"runPreserved" to true,"voiceNestedInSettings" to true,"probeHidden" to true).toString(2))
        } finally {finish(activity)}
    }
    @Test fun approvalWaitingDoesNotBlockAnotherSessionsDeviceTool() {
        configure(0,951)
        val config=java.io.File(context.filesDir,"approved-synthetic-config/settings.json").apply {parentFile!!.mkdirs();writeText("{\"volume\":60,\"unknown\":\"keep\"}")}
        val activity=launch();val web=activity.findViewById<WebView>(android.R.id.custom)
        try {
            waitFor(web,"window.productReady===true");probe(activity,web);val first=host(activity).selectedSession()
            javascript(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(first)},capability:'debug',enabled:true}))")
            waitFor(web,"document.querySelector('input[aria-label=\"ADB 调试\"]').checked")
            send(web,"WRITE_CONFIG_${dev.thor.tools.hash(config.readBytes())}: adjust synthetic volume")
            waitFor(web,"!!document.querySelector('.wait')")
            javascript(web,"document.getElementById('new').click()")
            waitFor(web,"document.getElementById('send').textContent==='发送'")
            send(web,"DEVICE_INFO while another session waits for approval")
            waitFor(web,"document.getElementById('send').textContent==='停止'");waitFor(web,"document.getElementById('send').textContent==='发送'")
            assertTrue(host(activity).view().getJSONArray("activeRuns").objects().any {it.getString("sessionId")==first && it.getString("status")=="awaiting_user"})
            assertEquals(60,JSONObject(config.readText()).getInt("volume"))
            javascript(web,"ThorUI.postMessage(JSON.stringify({op:'select',sessionId:${JSONObject.quote(first)}}))")
            waitFor(web,"!!document.querySelector('.wait')");javascript(web,"document.querySelectorAll('.wait button')[1].click()")
            waitFor(web,"document.getElementById('send').textContent==='发送'")
            assertEquals(60,JSONObject(config.readText()).getInt("volume"))
            java.io.File(context.filesDir,"integrated-parallel-approval.json").writeText(obj("passed" to true,"otherSessionToolCompleted" to true,"waitingWriteNotSubmitted" to true).toString(2))
        } finally {finish(activity)}
    }
    @Test fun concurrentSessionsInheritOptionsAndStopOnlyTheirOwnRun() {
        configure(0,801)
        assertTrue(context.getSharedPreferences("profiles",0).edit().putString("provider","opencode-go").putString("model","deepseek-v4.1-flash").commit())
        val activity=launch();val web=activity.findViewById<WebView>(android.R.id.custom)
        try {
            waitFor(web,"window.productReady===true")
            val first=host(activity).selectedSession()
            probe(activity,web)
            assertEquals(1000000,host(activity).view().getJSONObject("modelSelection").getInt("contextWindow"))
            javascript(web,"ThorUI.postMessage(JSON.stringify({op:'sessionOptions',sessionId:${JSONObject.quote(first)},thinkingEffort:'max',selectedSkills:['adb-operations']}))")
            waitFor(web,"document.getElementById('effort-select').value==='max'")
            javascript(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(first)},capability:'search',enabled:false}))")
            waitFor(web,"!document.querySelector('input[aria-label=\"联网搜索\"]').checked")
            send(web,"WAIT_CANCEL first session")
            waitFor(web,"document.getElementById('send').textContent==='停止'")
            val firstRun=host(activity).view().getString("activeRunId")
            javascript(web,"document.getElementById('new').click()")
            waitFor(web,"document.getElementById('send').textContent==='发送'")
            val second=host(activity).selectedSession()
            assertNotEquals(first,second)
            val inherited=host(activity).view();assertEquals("max",inherited.getJSONObject("modelSelection").getString("thinkingEffort"))
            assertFalse(inherited.getJSONArray("sessions").objects().single {it.getString("id")==second}.getJSONObject("capabilities").getBoolean("search"))
            assertEquals("[\"adb-operations\"]",inherited.getJSONArray("sessions").objects().single {it.getString("id")==second}.getJSONArray("selectedSkills").toString())
            send(web,"DEVICE_INFO concurrent second")
            waitFor(web,"document.getElementById('send').textContent==='停止'");waitFor(web,"document.getElementById('send').textContent==='发送'")
            assertEquals("completed",SqliteStore(context,"product.db").use {it.load().getJSONArray("runs").objects().last().getString("status")})
            assertTrue(host(activity).view().getJSONArray("activeRuns").objects().any {it.getString("id")==firstRun && it.optString("status") !in terminal})
            send(web,"WAIT_CANCEL second session")
            waitFor(web,"document.getElementById('send').textContent==='停止'")
            val secondRun=host(activity).view().getString("activeRunId")
            javascript(web,"ThorUI.postMessage(JSON.stringify({op:'select',sessionId:${JSONObject.quote(first)}}))")
            waitFor(web,"document.querySelector('.session-row.selected')?.dataset.sessionId===${JSONObject.quote(first)}")
            waitFor(web,"document.getElementById('send').textContent==='停止'")
            javascript(web,"document.getElementById('send').click()")
            waitFor(web,"document.getElementById('send').textContent==='发送'")
            val after=SqliteStore(context,"product.db").use {it.load()}
            assertEquals("cancelled",after.getJSONArray("runs").objects().single {it.getString("id")==firstRun}.getString("status"))
            assertTrue(after.getJSONArray("runs").objects().single {it.getString("id")==secondRun}.getString("status") !in terminal)
            javascript(web,"ThorUI.postMessage(JSON.stringify({op:'select',sessionId:${JSONObject.quote(second)}}))")
            waitFor(web,"document.querySelector('.session-row.selected')?.dataset.sessionId===${JSONObject.quote(second)}")
            waitFor(web,"document.getElementById('send').textContent==='停止'");javascript(web,"document.getElementById('send').click()")
            waitFor(web,"document.getElementById('send').textContent==='发送'")
            java.io.File(context.filesDir,"integrated-concurrent-sessions.json").writeText(obj("passed" to true,"contextWindow" to 1000000,"effort" to "max","optionsInherited" to true,"backgroundRunPreserved" to true,"targetedStop" to true).toString(2))
        } finally {finish(activity)}
    }
    @Test fun anonymousHttpRedirectCrossesAndroidBridgeWithoutCredentials() {
        // This regression exercises Android JSONObject null parsing, not the JVM shim.
        for(protocol in 0..2) {
            configure(protocol, 101+protocol)
            val activity=launch();val web=activity.findViewById<WebView>(android.R.id.custom)
            try {
                waitFor(web,"window.productReady === true")
                probe(activity,web)
                send(web,"WEB_FETCH: read the anonymous local HTTP page")
                waitFor(web,"document.getElementById('send').textContent === '停止'")
                waitFor(web,"document.getElementById('send').textContent === '发送'")
                val state=SqliteStore(context,"product.db").use {it.load()}
                val run=state.getJSONArray("runs").objects().last()
                assertEquals("completed",run.getString("status"))
                val result=state.getJSONArray("events").objects().single {it.getString("runId")==run.getString("id") && it.getString("type")=="tool_result"}.getJSONObject("data")
                assertEquals("web.fetch",result.getString("toolId"))
                assertTrue("Anonymous native request failed: $result",result.isNull("error"))
                assertTrue(result.getJSONArray("content").toString().contains("ANONYMOUS_WEB_OK"))
                assertTrue(javascript(web,"document.getElementById('chat').textContent.includes('ANONYMOUS_WEB_OK')")=="true")
                assertFalse(javascript(web,"document.body.textContent").contains("integration-only-secret"))
            } finally {finish(activity)}
        }
    }
    @Test fun productInputReachesIndependentReceiverAndReportsNativeAssociation() {
        val displayId=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("labDisplay")?.toInt() ?: throw AssertionError("Explicit emulator labDisplay argument required")
        configure(0,201)
        val automation=instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) { android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {it.readBytes()} }
        // Keep the user's existing services. The outer physical runner restores these settings.
        val existing=android.provider.Settings.Secure.getString(context.contentResolver,"enabled_accessibility_services").orEmpty()
        val services=(existing.split(':').filter {it.isNotBlank()} + "dev.thor.agent/dev.thor.tools.diagnostic.ThorAccessibilityService").distinct().joinToString(":")
        shell("settings put secure enabled_accessibility_services $services")
        shell("settings put secure accessibility_enabled 1")
        context.startActivity(Intent().setComponent(android.content.ComponentName("dev.thor.inputlab","dev.thor.inputlab.LabActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        Thread.sleep(800)
        val activity=launch();val web=activity.findViewById<WebView>(android.R.id.custom)
        val uri=android.net.Uri.parse("content://dev.thor.inputlab.runner")
        fun receiver(method: String, values: android.os.Bundle): android.os.Bundle = context.contentResolver.call(uri,method,null,values) ?: error("No receiver reply")
        try {
            waitFor(web,"window.productReady === true")
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(dev.thor.tools.diagnostic.ThorAccessibilityService.instance==null && System.nanoTime()<until)Thread.sleep(100)
            assertNotNull("Actual product Accessibility service must connect",dev.thor.tools.diagnostic.ThorAccessibilityService.instance)
            probe(activity,web)
            instrumentation.runOnMainSync {host(activity).calibrate(displayId,chatDisplay)}
            Thread.sleep(200)
            javascript(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host(activity).selectedSession())},capability:'screen_control',enabled:true}))")
            waitFor(web,"document.querySelector('input[aria-label=屏幕操作]').checked")
            val info=JSONObject(receiver("describe",android.os.Bundle().apply {putInt("version",1)}).getString("snapshot")!!)
            assertEquals(displayId,info.getJSONObject("geometry").getInt("actualWindowDisplayId"))
            val bounds=info.getJSONObject("target");val x=((bounds.getDouble("left")+bounds.getDouble("right"))/2).toInt();val y=((bounds.getDouble("top")+bounds.getDouble("bottom"))/2).toInt()
            val begun=host(activity).prepareForRunner("LAB_TAP_${x}_${y}: tap upper START once").get(5,TimeUnit.SECONDS)
            val rid=begun.getJSONObject("lease").getString("runId"); val testRun=id()
            val initial=receiver("prepare",android.os.Bundle().apply {putInt("version",1);putString("testRunId",testRun);putString("caseId","LAB-02");putString("agentRunId",rid);putString("senderTraceRef","product-native:$rid:lab_call")})
            val lease=initial.getString("lease")!!
            val args=android.os.Bundle().apply {putInt("version",1);putString("testRunId",testRun);putString("lease",lease)}
            var snapshot=JSONObject(initial.getString("snapshot")!!)
            val start=System.nanoTime()
            while(snapshot.optString("testRunId") != testRun && System.nanoTime()-start<TimeUnit.SECONDS.toNanos(3)) {Thread.sleep(100);snapshot=JSONObject(receiver("report",args).getString("snapshot")!!)}
            assertEquals(testRun,snapshot.getString("testRunId"))
            Thread.sleep(500) // Allow Accessibility window/content notifications from receiver preparation to settle.
            javascript(web,"window.runPreparedNative(JSON.parse(${JSONObject.quote(begun.toString())}));true")
            waitFor(web,"document.getElementById('send').textContent === '发送'",30)
            while(snapshot.isNull("report") && System.nanoTime()-start<TimeUnit.SECONDS.toNanos(12)) {Thread.sleep(100);snapshot=JSONObject(receiver("report",args).getString("snapshot")!!)}
            val report=snapshot.getJSONObject("report")
            java.io.File(context.filesDir,"integrated-lab-report.json").writeText(report.toString(2))
            assertEquals(rid,report.getString("agentRunId"));assertEquals("unqualified-physical-baseline-pending",report.getString("receiverQualificationVersion"))
            val state=SqliteStore(context,"product.db").use {it.load()};val native=state.getJSONArray("events").objects().single {it.getString("runId")==rid && it.getString("type")=="tool_result"}
            assertEquals("input.tap",native.getJSONObject("data").getString("toolId"));assertEquals("submitted",native.getJSONObject("data").getString("outcome"))
            assertFalse(state.toString().contains("integration-only-secret"))
            java.io.File(context.filesDir,"integrated-lab-native.json").writeText(obj("runId" to rid,"run" to state.getJSONArray("runs").objects().single {it.getString("id")==rid},"events" to org.json.JSONArray(state.getJSONArray("events").objects().filter {it.getString("runId")==rid})).toString(2))
            receiver("release",args)
            Thread.sleep(500)
            send(web,"LAB_CAPTURE: capture actual upper pixels with a structured region")
            waitFor(web,"document.getElementById('send').textContent === '停止'");waitFor(web,"document.getElementById('send').textContent === '发送'")
            val capture=SqliteStore(context,"product.db").use {it.load().getJSONArray("events").objects().last {it.getString("type")=="tool_result"}}.getJSONObject("data")
            assertEquals("perception.capture_screen",capture.getString("toolId"));assertTrue("Actual Accessibility screenshot must succeed: $capture",capture.isNull("error"))
            assertEquals("attachment",capture.getJSONArray("content").getJSONObject(0).getString("type"))
            java.io.File(context.filesDir,"integrated-capture-result.json").writeText(capture.toString(2))
            try {receiver("report",args);fail("Expired receiver lease accepted")}catch(_: RuntimeException){}
            val frameBegun=host(activity).prepareForRunner("LAB_FRAME_${x}_${y}: capture START region then tap its centre using frame pixels").get(5,TimeUnit.SECONDS)
            val frameRid=frameBegun.getJSONObject("lease").getString("runId");val frameTest=id()
            val frameSetup=receiver("prepare",android.os.Bundle().apply {putInt("version",1);putString("testRunId",frameTest);putString("caseId","LAB-02");putString("agentRunId",frameRid);putString("senderTraceRef","product-native:$frameRid:frame-input")})
            val frameArgs=android.os.Bundle().apply {putInt("version",1);putString("testRunId",frameTest);putString("lease",frameSetup.getString("lease"))}
            Thread.sleep(1000)
            javascript(web,"window.runPreparedNative(JSON.parse(${JSONObject.quote(frameBegun.toString())}));true")
            waitFor(web,"document.getElementById('send').textContent === '发送'")
            var frameSnapshot=JSONObject(receiver("report",frameArgs).getString("snapshot")!!);val frameEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
            while(frameSnapshot.isNull("report") && System.nanoTime()<frameEnd) {Thread.sleep(100);frameSnapshot=JSONObject(receiver("report",frameArgs).getString("snapshot")!!)}
            val frameEvents=SqliteStore(context,"product.db").use {it.load().getJSONArray("events").objects().filter {it.getString("runId")==frameRid && it.getString("type")=="tool_result"}}
            java.io.File(context.filesDir,"integrated-frame-report.json").writeText(frameSnapshot.getJSONObject("report").toString(2))
            java.io.File(context.filesDir,"integrated-frame-native.json").writeText(obj("runId" to frameRid,"events" to org.json.JSONArray(frameEvents)).toString(2))
            assertEquals("Actual logical-coordinate touch receiver verdict", "pass",report.getString("status"))
            assertEquals("Actual screenshot-coordinate touch must reach the independent receiver", "pass",frameSnapshot.getJSONObject("report").getString("status"))
            assertEquals(2,frameEvents.size);assertTrue(frameEvents.all {it.getJSONObject("data").isNull("error")})
            receiver("release",frameArgs)
        } finally {finish(activity)}
    }
    @Test fun actualFileApprovalsRejectApproveAndHashConflict() {
        configure(0,301);java.io.File(context.filesDir,"config-backups").listFiles()?.forEach {assertTrue(it.delete())};val file=java.io.File(context.filesDir,"approved-synthetic-config/settings.json").apply {parentFile!!.mkdirs();writeText("{\"volume\":60,\"unknown\":\"keep\"}")}
        val activity=launch();val web=activity.findViewById<WebView>(android.R.id.custom)
        try {
            waitFor(web,"window.productReady === true");probe(activity,web)
            javascript(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host(activity).selectedSession())},capability:'debug',enabled:true}))")
            waitFor(web,"document.querySelector('input[aria-label=\"ADB 调试\"]').checked")
            for(mode in listOf("reject","approve","conflict")) {
                val previousCount=javascript(web,"document.querySelectorAll('.session-row').length").toInt()
                javascript(web,"document.getElementById('new').click()")
                waitFor(web,"document.querySelectorAll('.session-row').length === ${previousCount+1}")
                // New Session starts with debug disabled; enable explicitly for this file-only task.
                javascript(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host(activity).selectedSession())},capability:'debug',enabled:true}))")
                waitFor(web,"document.querySelector('input[aria-label=\"ADB 调试\"]').checked")
                val before=file.readBytes();send(web,"WRITE_CONFIG_${dev.thor.tools.hash(before)}: change synthetic volume")
                waitFor(web,"!!document.querySelector('.wait')",30)
                assertArrayEquals(before,file.readBytes())
                if(mode=="conflict")file.writeText("{\"volume\":80,\"unknown\":\"keep\"}")
                javascript(web,"document.querySelectorAll('.wait button')[${if(mode=="reject")1 else 0}].click()")
                waitFor(web,"document.getElementById('send').textContent === '发送'")
                val result=SqliteStore(context,"product.db").use {it.load().getJSONArray("events").objects().last {it.getString("type")=="tool_result"}}
                if(mode=="reject") {assertArrayEquals(before,file.readBytes());assertEquals("PERMISSION_MISSING",result.getJSONObject("data").getString("error"))}
                else if(mode=="approve") {assertEquals(70,JSONObject(file.readText()).getInt("volume"));assertEquals("keep",JSONObject(file.readText()).getString("unknown"))}
                else {assertEquals(80,JSONObject(file.readText()).getInt("volume"));assertEquals("HASH_CONFLICT",result.getJSONObject("data").getString("error"))}
            }
        } finally {finish(activity)}
    }
    @Test fun lifecycleRecoveryNeverReplaysAndStorageFailureRevokesBeforeExecution() {
        configure(0,401);var activity=launch();var web=activity.findViewById<WebView>(android.R.id.custom)
        waitFor(web,"window.productReady === true")
        javascript(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host(activity).selectedSession())},capability:'perception',enabled:false}))")
        waitFor(web,"!document.querySelector('input[aria-label=上屏感知]').checked")
        probe(activity,web);send(web,"WAIT_CANCEL")
        waitFor(web,"document.getElementById('send').textContent === '停止'")
        val before=SqliteStore(context,"product.db").use {it.load()};val rid=before.getJSONArray("runs").objects().last().getString("id")
        finish(activity);activity=launch();web=activity.findViewById<WebView>(android.R.id.custom)
        try {
            waitFor(web,"window.productReady === true && document.getElementById('send').textContent === '发送'")
            val recovered=SqliteStore(context,"product.db").use {it.load()}
            assertEquals("interrupted",recovered.getJSONArray("runs").objects().single {it.getString("id")==rid}.getString("status"))
            assertEquals(before.getJSONArray("runs").length(),recovered.getJSONArray("runs").length())
            val nativeHost=host(activity)
            val nativeStore=ProductHost::class.java.getDeclaredField("store").apply {isAccessible=true}.get(nativeHost) as SqliteStore
            nativeStore.writableDatabase.execSQL("PRAGMA query_only=ON")
            send(web,"DEVICE_INFO: storage failure must stop before intent")
            waitFor(web,"document.getElementById('send').disabled === true")
            assertNull(nativeHost.authority.currentBinding())
            assertEquals(recovered.getJSONArray("runs").length(),SqliteStore(context,"product.db").use {it.load().getJSONArray("runs").length()})
            nativeStore.writableDatabase.execSQL("PRAGMA query_only=OFF")
            finish(activity);activity=launch();web=activity.findViewById<WebView>(android.R.id.custom)
            waitFor(web,"window.productReady === true")
            send(web,"WAIT_CANCEL");waitFor(web,"document.getElementById('send').textContent === '停止'")
            instrumentation.runOnMainSync {assertTrue(web.webViewRenderProcess!!.terminate())}
            val timeout=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);var rendererNotice=false
            while(!rendererNotice && System.nanoTime()<timeout) {instrumentation.runOnMainSync {val content=activity.findViewById<android.view.ViewGroup>(android.R.id.content);rendererNotice=(content.getChildAt(0) as? android.widget.LinearLayout)?.getChildAt(0)?.let {(it as? android.widget.TextView)?.text?.contains("渲染进程") }==true};Thread.sleep(100)}
            assertTrue("Renderer exit must expose a Native recovery action",rendererNotice);assertNull(host(activity).authority.currentBinding())
        } finally {finish(activity)}
    }
    @Test fun realShizukuReviewedShellRequiresApprovalAndRunsAsShellUid() {
        org.junit.Assume.assumeTrue("Shizuku companion/permission explicitly required for this run",androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("requireShizuku")=="true")
        configure(0,501);val activity=launch();val web=activity.findViewById<WebView>(android.R.id.custom)
        try {
            waitFor(web,"window.productReady === true")
            val backend=ProductHost::class.java.getDeclaredField("backend").apply {isAccessible=true}.get(host(activity)) as dev.thor.tools.diagnostic.AndroidBackend
            var deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(backend.availability("debug.shell").status==dev.thor.tools.Status.BACKEND_MISSING && System.nanoTime()<deadline)Thread.sleep(100)
            instrumentation.runOnMainSync {host(activity).bindDebug()}
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60)
            while(backend.availability("debug.shell").status!=dev.thor.tools.Status.READY && System.nanoTime()<deadline)Thread.sleep(100)
            assertEquals(dev.thor.tools.Status.READY,backend.availability("debug.shell").status)
            probe(activity,web)
            for(approve in listOf(false,true)) {
                val count=javascript(web,"document.querySelectorAll('.session-row').length").toInt();javascript(web,"document.getElementById('new').click()")
                waitFor(web,"document.querySelectorAll('.session-row').length === ${count+1}")
                javascript(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host(activity).selectedSession())},capability:'debug',enabled:true}))")
                waitFor(web,"document.querySelector('input[aria-label=\"ADB 调试\"]').checked")
                send(web,"SHELL_REJECT: run exact harmless command only if approved")
                waitFor(web,"!!document.querySelector('.wait')")
                javascript(web,"document.querySelectorAll('.wait button')[${if(approve)0 else 1}].click()")
                waitFor(web,"document.getElementById('send').textContent === '发送'")
                val result=SqliteStore(context,"product.db").use {it.load().getJSONArray("events").objects().last {it.getString("type")=="tool_result"}}.getJSONObject("data")
                if(!approve)assertEquals("PERMISSION_MISSING",result.getString("error"))
                else {
                    assertTrue(result.isNull("error"));assertEquals("submitted",result.getString("outcome"))
                    val data=JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"))
                    assertEquals(2000,data.getInt("uid"));assertEquals(0,data.getInt("exitCode"));assertEquals("gray",data.getString("policyTier"))
                    java.io.File(context.filesDir,"integrated-shell-result.json").writeText(result.toString(2))
                }
            }
            val count=javascript(web,"document.querySelectorAll('.session-row').length").toInt();javascript(web,"document.getElementById('new').click()")
            waitFor(web,"document.querySelectorAll('.session-row').length === ${count+1}")
            javascript(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host(activity).selectedSession())},capability:'debug',enabled:true}))")
            waitFor(web,"document.querySelector('input[aria-label=\"ADB 调试\"]').checked")
            send(web,"SHELL_WAIT: cancel running exact harmless shell")
            waitFor(web,"!!document.querySelector('.wait')")
            // Stopping while an unapproved gray command is pending must not submit it.
            javascript(web,"document.getElementById('send').click()")
            waitFor(web,"document.getElementById('send').textContent === '发送'")
            val stopped=SqliteStore(context,"product.db").use {it.load()}
            assertEquals("cancelled",stopped.getJSONArray("runs").objects().last().getString("status"))
            val stoppedRun=stopped.getJSONArray("runs").objects().last().getString("id")
            assertFalse(stopped.getJSONArray("events").objects().any {it.getString("runId")==stoppedRun && it.getString("type")=="tool_result" && it.getJSONObject("data").optString("outcome")=="submitted"})
        } finally {finish(activity)}
    }
    @Test fun realBridgeRejectsUnknownCommandAndDirectNetwork() {
        configure(0,101); val activity=launch();val web=activity.findViewById<WebView>(android.R.id.custom)
        try {
            waitFor(web,"window.productReady === true")
            javascript(web,"window.ThorUI.postMessage(JSON.stringify({op:'readCredentials'}))")
            waitFor(web,"document.getElementById('notice').textContent.includes('操作被拒绝')")
            javascript(web,"window.directBlocked=false;fetch('http://127.0.0.1:8877/v1/responses').catch(()=>window.directBlocked=true)")
            waitFor(web,"window.directBlocked")
            javascript(web,"window.frame=document.createElement('iframe');frame.src='https://appassets.androidplatform.net/assets/chat/index.html';document.body.append(frame)")
            assertEquals("0",javascript(web,"document.querySelectorAll('iframe').length && document.querySelector('iframe').contentDocument?.body?.children.length || 0"))
            val state=SqliteStore(context,"product.db").use {it.load()};assertEquals(0,state.getJSONArray("runs").length())
        } finally {finish(activity)}
    }
}
