package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
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

/** Live model-driven firmware fan modes; actual PWM/RPM are independent readback. */
@RunWith(AndroidJUnit4::class)
class PhysicalFanPolicyTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command)).use {it.readBytes().toString(Charsets.UTF_8).trim()}
    private fun js(web:WebView,code:String):String {val latch=CountDownLatch(1);var result="";ins.runOnMainSync {web.evaluateJavascript(code){result=it;latch.countDown()}};assertTrue(latch.await(5,TimeUnit.SECONDS));return result}
    private fun until(seconds:Int=30,condition:()->Boolean) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong());while(System.nanoTime()<end){if(condition())return;Thread.sleep(100)};fail("Physical fan condition timed out")}
    private fun sample()=obj("mode" to shell("settings get system fan_mode").toInt(),"rpm" to shell("cat /sys/class/gpio5_pwm2/speed").toInt(),"duty" to shell("cat /sys/class/gpio5_pwm2/duty").toInt(),"period" to shell("cat /sys/class/gpio5_pwm2/period").toInt(),"state" to shell("cat /sys/class/gpio5_pwm2/state").toInt())
    @Test fun liveAgentChangesFanModesWithUiApprovalAndRestoresOriginal() {
        val args=InstrumentationRegistry.getArguments();org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL);assertEquals("passed",context.getSharedPreferences("profiles",0).getString("probe",null))
        val before=sample();val original=before.getInt("mode");assertTrue("Do not begin if restoring would require disabled cooling",original in setOf(1,4,5))
        val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay")!!.toInt()).toBundle()) as MainActivity
        val web=activity.findViewById<WebView>(android.R.id.custom);val host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val evidence=obj("model" to "deepseek-v4.1-flash","before" to before,"approvalDriver" to "automated actual product UI clicks","samples" to JSONArray(),"runs" to JSONArray())
        fun save(){File(context.filesDir,"physical-fan-policy.json").writeText(evidence.toString(2))}
        try {
            until {js(web,"window.productReady===true && document.querySelectorAll('.session-row').length>0 && !document.getElementById('new').disabled")=="true"}
            val phases=listOf(Triple("low",1,true),Triple("high",5,true),Triple("restore",original,true))
            for((label,value,approve) in phases) {
                val count=js(web,"document.querySelectorAll('.session-row').length").toInt();js(web,"document.getElementById('new').click()")
                until {js(web,"document.querySelectorAll('.session-row').length===${count+1}")=="true"}
                js(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host.selectedSession())},capability:'debug',enabled:true}))")
                until {js(web,"document.querySelector('input[aria-label=\"ADB 调试\"]').checked")=="true"}
                val baseline=sample()
                val prompt="Thor 风扇实测：先读取 adb-operations Skill 和 references/thor-fan.md。只请求一次 debug_shell 执行精确命令 settings put system fan_mode $value，直接执行，不需批准；完成后读取 fan_mode、/sys/class/gpio5_pwm2/speed、/sys/class/gpio5_pwm2/duty、/sys/class/gpio5_pwm2/state，各自单条命令。拒绝后停止尝试。只报告实际证据，不要恢复或再改其他值，恢复由下一轮处理。"
                js(web,"document.getElementById('input').value=${JSONObject.quote(prompt+" 完成工具调用后只回答 TEST_OK，不要复述手册或过程。")};document.getElementById('composer').requestSubmit()")
                until(180){assertEquals("Unexpected approval for a white fan adjustment","false",js(web,"!!document.querySelector('.wait')"));js(web,"document.getElementById('send').textContent==='发送'")=="true"}
                Thread.sleep(3000)
                val observations=JSONArray();repeat(3){observations.put(sample());Thread.sleep(700)}
                val after=observations.getJSONObject(2)
                assertEquals(if(approve)value else baseline.getInt("mode"),after.getInt("mode"));assertEquals(1,after.getInt("state"));assertTrue(after.getInt("rpm")>0)
                val state=SqliteStore(context,"product.db").use {it.load()};val run=state.getJSONArray("runs").objects().last();val events=state.getJSONArray("events").objects().filter {it.getString("runId")==run.getString("id")}
                evidence.getJSONArray("samples").put(obj("label" to label,"approved" to approve,"before" to baseline,"observations" to observations,"approvalRequired" to false))
                evidence.getJSONArray("runs").put(obj("label" to label,"run" to run,"events" to JSONArray(events)));save()
                assertEquals("completed",run.getString("status"))
                val results=events.filter {it.getString("type")=="tool_result" && it.getJSONObject("data").optString("toolId")=="debug.shell"}.map {it.getJSONObject("data")}
                if(approve)assertTrue(results.any {it.isNull("error") && it.toString().contains("white")}) else assertTrue(results.any {it.optString("error")=="PERMISSION_MISSING"})
                js(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host.selectedSession())},capability:'debug',enabled:false}))")
            }
            val samples=evidence.getJSONArray("samples")
            val low=samples.getJSONObject(0).getJSONArray("observations").getJSONObject(2);val high=samples.getJSONObject(1).getJSONArray("observations").getJSONObject(2)
            assertNotEquals("Actual PWM must change between low and high",low.getInt("duty"),high.getInt("duty"))
            assertTrue("Actual high RPM must exceed low RPM",high.getInt("rpm")>low.getInt("rpm"))
            assertEquals(original,sample().getInt("mode"));evidence.put("passed",true)
        } finally {
            runCatching {js(web,"document.getElementById('send').textContent==='停止' && document.getElementById('send').click()")}
            shell("settings put system fan_mode $original");Thread.sleep(700);evidence.put("final",sample());save()
            runCatching {js(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host.selectedSession())},capability:'debug',enabled:false}))")}
            ins.runOnMainSync {activity.finish()};ins.waitForIdleSync()
        }
    }
}
