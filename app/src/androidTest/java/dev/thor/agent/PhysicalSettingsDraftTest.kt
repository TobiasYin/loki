package dev.thor.agent

import android.app.ActivityOptions
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
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Additive: exercises real drafts without saving, deleting, or resetting any profile/history. */
@RunWith(AndroidJUnit4::class)
class PhysicalSettingsDraftTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun views(root:View):List<View> = listOf(root)+(if(root is ViewGroup)(0 until root.childCount).flatMap {views(root.getChildAt(it))} else emptyList())
    private fun onUi(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun until(seconds:Int=120,condition:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong())
        while(System.nanoTime()<end){if(condition())return;Thread.sleep(150)}
        fail("Settings draft test timed out")
    }
    private fun js(web:WebView,code:String):String {
        val latch=CountDownLatch(1);var value=""
        onUi {web.evaluateJavascript(code){value=it;latch.countDown()}}
        assertTrue(latch.await(10,TimeUnit.SECONDS));return value
    }
    private fun show(activity:MainActivity,section:String) {
        onUi {MainActivity::class.java.getDeclaredMethod("showSettings",String::class.java).apply {isAccessible=true}.invoke(activity,section)}
        instrumentation.waitForIdleSync()
    }
    private fun button(activity:MainActivity,label:String):Button {
        var result:Button?=null;onUi {result=views(activity.window.decorView).filterIsInstance<Button>().single {it.text.toString()==label}}
        return result!!
    }
    private fun field(activity:MainActivity,hint:String):EditText {
        var result:EditText?=null;onUi {result=views(activity.window.decorView).filterIsInstance<EditText>().single {it.hint?.toString()==hint}}
        return result!!
    }
    private fun textContaining(activity:MainActivity,text:String):String {
        var result="";onUi {result=views(activity.window.decorView).filterIsInstance<TextView>().map {it.text.toString()}.firstOrNull {text in it}.orEmpty()}
        return result
    }
    @Test fun currentDraftModelAndBraveAreTestedWithoutChangingSavedProfile() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit real-device test required",args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay","4").toInt()).toBundle()) as MainActivity
        val web=activity.findViewById<WebView>(android.R.id.custom)
        val report=JSONObject().put("saved",false)
        try {
            until {js(web,"window.productReady===true")=="true"}
            val prefs=context.getSharedPreferences("profiles",0)
            val before=HashMap(prefs.all)
            val vault=CredentialVault(context)
            val modelHash=hash(vault.readNative("model") ?: error("Saved model Key required"))
            val searchHash=hash(vault.readNative("search") ?: error("Saved Brave Key required"))
            val countBefore=SqliteStore(context,"product.db").use {it.load().getJSONArray("sessions").length()}
            show(activity,"model")
            // Switch to custom editing so the actual draft model field is visible.
            onUi {views(activity.window.decorView).filterIsInstance<Spinner>().first().setSelection(1)}
            instrumentation.waitForIdleSync()
            val modelTest=button(activity,"测试当前填写的连接")
            onUi {modelTest.performClick()}
            until {var enabled=false;onUi {enabled=modelTest.isEnabled};enabled}
            val modelStatus=textContaining(activity,"草稿未保存，点击右上角保存后生效")
            report.put("modelDraft",modelStatus)
            File(context.filesDir,"physical-settings-draft.json").writeText(report.toString(2))
            assertTrue("Draft model test must receive text streaming: $modelStatus",modelStatus.contains("文本流式连接可用"))
            val modelField=field(activity,"模型 ID")
            onUi {modelField.setText("thor-missing-model-for-draft-test")}
            onUi {modelTest.performClick()}
            until {var enabled=false;onUi {enabled=modelTest.isEnabled};enabled}
            val rejected=textContaining(activity,"草稿未保存，点击右上角保存后生效")
            assertFalse("Invalid draft model must not pass using the saved profile",rejected.contains("文本流式连接可用"))
            assertTrue("Invalid model must show a draft failure",rejected.contains("失败") || rejected.contains("不存在") || rejected.contains("错误") || rejected.contains("没有收到文本增量"))
            report.put("invalidModelDraft",rejected)
            show(activity,"search") // Discard model draft by leaving; never click Save.
            val searchTest=button(activity,"测试当前搜索连接")
            onUi {searchTest.performClick()}
            until {var enabled=false;onUi {enabled=searchTest.isEnabled};enabled}
            val searchStatus=textContaining(activity," ms")
            report.put("searchDraft",searchStatus)
            assertTrue("Draft Brave test must return search results: $searchStatus",searchStatus.contains("Brave 搜索连接可用"))
            assertTrue("Profile preferences must remain identical",before==prefs.all)
            assertTrue("Model credential must remain identical",modelHash==hash(vault.readNative("model").orEmpty()))
            assertTrue("Search credential must remain identical",searchHash==hash(vault.readNative("search").orEmpty()))
            val countAfter=SqliteStore(context,"product.db").use {it.load().getJSONArray("sessions").length()}
            assertEquals("Draft testing must not create conversations",countBefore,countAfter)
            report.put("profileUnchanged",true).put("credentialHashesUnchanged",true).put("sessionCountUnchanged",true).put("passed",true)
        }finally {
            File(context.filesDir,"physical-settings-draft.json").writeText(report.toString(2))
            onUi {activity.finish()};instrumentation.waitForIdleSync()
        }
    }
}
