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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Emulator-only wizard acceptance, with an isolated provider document and a synthetic Key.
 * Restores original provider metadata, credentials, and initialization preferences afterward. */
@RunWith(AndroidJUnit4::class)
class AgentPlanOnboardingTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun until(condition:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45)
        while(System.nanoTime()<deadline){var passed=false;instrumentation.runOnMainSync {passed=condition()};if(passed)return;Thread.sleep(80)}
        fail("AgentPlan onboarding did not reach the expected page")
    }
    @Test fun firstRunConfiguresOneKeySelectsModelAndFinishesGuide() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("emulatorAcceptance")=="true")
        assertTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val providerPrefs=context.getSharedPreferences("provider-configs",0)
        val onboarding=context.getSharedPreferences("onboarding",0)
        val profiles=context.getSharedPreferences("profiles",0)
        val voice=context.getSharedPreferences("voice",0)
        val store=ProviderStore(context)
        val originalDocument=providerPrefs.getString("document",null)
        val hadCompleted=onboarding.contains("completed");val completed=onboarding.getBoolean("completed",false)
        val hadRevision=profiles.contains("revision");val revision=profiles.getInt("revision",0)
        val searchBackend=profiles.getString("searchBackend",null)
        val hadStandalone=voice.contains("standalone");val standalone=voice.getBoolean("standalone",false)
        var activity:MainActivity?=null
        var isolated=false
        val report=obj("emulator" to true,"credential" to "synthetic, no network request","passed" to false)
        try {
            check(providerPrefs.edit().putString("document",obj("version" to 1,"records" to JSONArray(),"defaultId" to "").toString()).commit())
            isolated=true
            check(onboarding.edit().putBoolean("completed",false).commit())
            check(profiles.edit().putString("searchBackend","自动选择").commit())
            check(voice.edit().putBoolean("standalone",false).commit())
            val screen=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(InstrumentationRegistry.getArguments().getString("chatDisplay","4").toInt()).toBundle()) as MainActivity
            activity=screen
            val initial=EmulatorRuntimeTestSupport.awaitReady(screen)
            fun all()=views(screen.window.decorView)
            fun text(value:String)=all().filterIsInstance<TextView>().single {it.text.toString()==value}
            fun click(value:String) {var view:View=text(value);while(!view.isClickable)view=view.parent as View;assertTrue(view.performClick())}
            until {all().filterIsInstance<TextView>().any {it.text.toString()=="配置火山 AgentPlan"}}
            instrumentation.runOnMainSync {
                assertFalse((text("下一步 · 配置操作权限") as Button).isEnabled)
                click("配置火山 AgentPlan")
                val picker=all().filterIsInstance<Spinner>().single {it.tag=="model-picker"}
                assertTrue(picker.count>=10)
                assertEquals("",all().filterIsInstance<EditText>().single {it.tag=="provider-api-key"}.text.toString())
                click("保存")
                assertTrue(store.list().isEmpty())
                assertTrue(all().filterIsInstance<TextView>().single {it.tag=="model-test-status"}.text.contains("填写 API Key"))
                all().filterIsInstance<EditText>().single {it.tag=="provider-api-key"}.setText("onboarding-fixture-key")
                val index=(0 until picker.count).single {picker.getItemAtPosition(it).toString()=="deepseek-v4.1-flash"}
                picker.setSelection(index)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {click("保存")}
            EmulatorRuntimeTestSupport.awaitReady(screen,previousWeb=initial.web)
            until {all().filterIsInstance<TextView>().any {it.text.toString()=="连接 AI 服务"}}
            val record=store.list().single();val createdId=record.getString("id")
            assertEquals(VolcAgentPlan.KIND,record.getString("kind"))
            assertEquals("deepseek-v4.1-flash",record.getString("model"))
            assertEquals(createdId,store.defaultId())
            assertTrue("Model Key saved once",store.credential(createdId) == "onboarding-fixture-key")
            assertTrue("Voice shares the saved Key",VoiceServices.resolve(context)?.apiKey=="onboarding-fixture-key")
            assertEquals(VolcAgentPlan.SEARCH_NAME,SearchServices.resolve(context)?.backend)
            assertTrue("Search shares the saved Key",SearchServices.resolve(context)?.key=="onboarding-fixture-key")
            instrumentation.runOnMainSync {
                assertTrue((text("下一步 · 配置操作权限") as Button).isEnabled)
                assertTrue(all().filterIsInstance<TextView>().any {it.text.toString()=="语音输入 · 已配置"})
                assertTrue(all().filterIsInstance<TextView>().any {it.text.toString()=="联网搜索 · 已配置"})
                val view=screen.window.decorView
                val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(context.filesDir,"agentplan-onboarding.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                click("下一步 · 配置操作权限")
                assertTrue(all().filterIsInstance<TextView>().any {it.text.toString()=="基础 · 无障碍服务"})
                click("完成，开始使用")
                assertFalse(all().filterIsInstance<TextView>().any {it.text.toString()=="连接 AI 服务"})
            }
            assertTrue(onboarding.getBoolean("completed",false))
            report.put("modelSelected",true).put("emptyKeyBlocked",true).put("sameKeyForVoiceAndSearch",true).put("guideCompleted",true).put("passed",true)
        }finally {
            // The temporary document contains only this test's provider; original Key references remain untouched.
            if(isolated)store.list().forEach {store.delete(it.getString("id"))}
            val providers=providerPrefs.edit();if(originalDocument==null)providers.remove("document") else providers.putString("document",originalDocument);check(providers.commit())
            val guide=onboarding.edit();if(hadCompleted)guide.putBoolean("completed",completed) else guide.remove("completed");check(guide.commit())
            val prefs=profiles.edit();if(hadRevision)prefs.putInt("revision",revision) else prefs.remove("revision");if(searchBackend==null)prefs.remove("searchBackend") else prefs.putString("searchBackend",searchBackend);check(prefs.commit())
            val input=voice.edit();if(hadStandalone)input.putBoolean("standalone",standalone) else input.remove("standalone");check(input.commit())
            report.put("originalConfigurationRestored",providerPrefs.getString("document",null)==originalDocument)
            File(context.filesDir,"agentplan-onboarding.json").writeText(report.toString(2))
            activity?.let {screen->instrumentation.runOnMainSync {screen.finish()}}
        }
    }
}
