package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
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

@RunWith(AndroidJUnit4::class)
class PhysicalRefreshPolicyTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)).use {it.readBytes().toString(Charsets.UTF_8).trim()}
    private fun js(web:WebView,code:String):String {val latch=CountDownLatch(1);var result="";ins.runOnMainSync {web.evaluateJavascript(code){result=it;latch.countDown()}};assertTrue(latch.await(5,TimeUnit.SECONDS));return result}
    private fun until(seconds:Int=30,condition:()->Boolean) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong());while(System.nanoTime()<end){if(condition())return;Thread.sleep(100)};fail("Physical refresh condition timed out")}
    private fun sample():JSONObject {
        val displays=context.getSystemService(DisplayManager::class.java).displays.filter {it.displayId==0 || it.name=="Screen-2"}
        return obj("min" to shell("settings get system min_refresh_rate"),"peak" to shell("settings get system peak_refresh_rate"),"displays" to JSONArray(displays.map {obj("displayId" to it.displayId,"name" to it.name,"refreshRate" to it.mode.refreshRate)}))
    }
    @Test fun liveAgentChangesRefreshWithoutApprovalAndRestoresSettings() {
        val args=InstrumentationRegistry.getArguments();org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL);assertEquals("passed",context.getSharedPreferences("profiles",0).getString("probe",null))
        val before=sample();val min=before.getString("min");val peak=before.getString("peak")
        assertTrue(min in setOf("60.0","60","120.0","120") && peak in setOf("60.0","60","120.0","120"))
        val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay")!!.toInt()).toBundle()) as MainActivity
        ins.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val web=activity.findViewById<WebView>(android.R.id.custom);val host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val evidence=obj("model" to "deepseek-v4.1-flash","before" to before,"runs" to JSONArray())
        try {
            until {js(web,"window.productReady===true && document.querySelectorAll('.session-row').length>0 && !document.getElementById('new').disabled")=="true"}
            for((label,commands) in listOf("120-hz" to listOf("settings put system peak_refresh_rate 120.0","settings put system min_refresh_rate 120.0"),"restore" to (if(peak.toDouble()==120.0)listOf("settings put system peak_refresh_rate $peak","settings put system min_refresh_rate $min") else listOf("settings put system min_refresh_rate $min","settings put system peak_refresh_rate $peak")))) {
                val count=js(web,"document.querySelectorAll('.session-row').length").toInt();js(web,"document.getElementById('new').click()")
                until {js(web,"document.querySelectorAll('.session-row').length===${count+1}")=="true"}
                js(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host.selectedSession())},capability:'debug',enabled:true}))")
                until {js(web,"document.querySelector('input[aria-label=\"ADB 调试\"]').checked")=="true"}
                val prompt="Thor 刷新率分阶段实测：读取 adb-operations Skill 和 references/thor-refresh.md。允许并需要按手册读取原值和检查分类，读取次数不限。写入操作仅以下两条，严格按给定顺序各执行一次 debug_shell：${commands.joinToString("，然后")}。两条都是白名单，不需批准。写完再分别读取 min_refresh_rate、peak_refresh_rate 回验。本阶段不要追加其他写入；恢复阶段由下一条请求处理。"
                js(web,"document.getElementById('input').value=${JSONObject.quote(prompt+" 完成工具调用后只回答 TEST_OK，不要复述手册或过程。")};document.getElementById('composer').requestSubmit()")
                until {js(web,"document.getElementById('send').textContent==='停止'")=="true"}
                until(180){assertEquals("false",js(web,"!!document.querySelector('.wait')"));js(web,"document.getElementById('send').textContent==='发送'")=="true"}
                Thread.sleep(1500);val after=sample()
                val state=SqliteStore(context,"product.db").use {it.load()};val run=state.getJSONArray("runs").objects().last();val events=state.getJSONArray("events").objects().filter {it.getString("runId")==run.getString("id")}
                evidence.getJSONArray("runs").put(obj("label" to label,"run" to run,"events" to JSONArray(events),"after" to after,"approvalRequired" to false));File(context.filesDir,"physical-refresh-policy.json").writeText(evidence.toString(2))
                assertEquals("completed",run.getString("status"));assertEquals(if(label=="120-hz")120.0 else min.toDouble(),after.getString("min").toDouble(),0.01);assertEquals(if(label=="120-hz")120.0 else peak.toDouble(),after.getString("peak").toDouble(),0.01)
                assertTrue(events.filter {it.getString("type")=="tool_result" && it.getJSONObject("data").optString("toolId")=="debug.shell"}.all {it.getJSONObject("data").isNull("error")})
                js(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host.selectedSession())},capability:'debug',enabled:false}))")
            }
            evidence.put("passed",true)
        } finally {
            runCatching {js(web,"document.getElementById('send').textContent==='停止' && document.getElementById('send').click()")}
            if(peak.toDouble()==120.0){shell("settings put system peak_refresh_rate $peak");shell("settings put system min_refresh_rate $min")}else{shell("settings put system min_refresh_rate $min");shell("settings put system peak_refresh_rate $peak")}
            evidence.put("final",sample());File(context.filesDir,"physical-refresh-policy.json").writeText(evidence.toString(2))
            runCatching {js(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host.selectedSession())},capability:'debug',enabled:false}))")}
            ins.runOnMainSync {activity.finish()};ins.waitForIdleSync()
        }
    }
}
