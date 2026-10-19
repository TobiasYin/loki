package dev.thor.agent

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SearchSettingsDeviceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
    @Test fun serviceFormsKeepDraftsSeparateAndOnlyShowRelevantFields() {
        val savedCredentials=HashMap(context.getSharedPreferences("native-credentials",0).all)
        val prefs=context.getSharedPreferences("profiles",0);val before=prefs.getString("searchBackend",null)
        check(prefs.edit().putString("searchBackend","自动选择").commit())
        val oldMode=Appearance.mode(context)
        val lower=dev.thor.tools.diagnostic.DisplayRoles(context).resolve(dev.thor.tools.diagnostic.displayFacts(context))?.second
        if(android.os.Build.MODEL.contains("Thor",true))assertNotNull("Physical Thor tests must resolve the lower screen",lower)
        val options=android.app.ActivityOptions.makeBasic().apply {if(lower!=null)setLaunchDisplayId(lower.id)}
        val activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),options.toBundle()) as MainActivity
        if(lower!=null)assertEquals("Search settings must be tested on the lower screen",lower.id,activity.display!!.displayId)
        EmulatorRuntimeTestSupport.awaitReady(activity)
        fun all()=views(activity.window.decorView)
        fun screenshot(name:String) {
            inst.runOnMainSync {
                val view=activity.window.decorView
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File(context.filesDir,"search-settings-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
        }
        fun choose(name:String) {
            inst.runOnMainSync {
                all().single {it.tag=="search-service-picker"}.performClick()
            }
            inst.waitForIdleSync()
            val label=when(name){"DeepSeek"->"DeepSeek · 共享模型 Key";"自动选择"->"自动选择 · 推荐";"关闭"->"关闭联网搜索";else->name}
            val end=android.os.SystemClock.elapsedRealtime()+5000
            var clicked=false
            while(!clicked && android.os.SystemClock.elapsedRealtime()<end) {
                val root=inst.uiAutomation.rootInActiveWindow
                val node=root?.findAccessibilityNodeInfosByText(label)?.firstOrNull {it.text?.toString()==label}
                if(node!=null)clicked=node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                if(!clicked)Thread.sleep(50)
            }
            assertTrue("Service choice not reachable: $name",clicked)
            inst.waitForIdleSync();Thread.sleep(100)
        }
        try {
            inst.runOnMainSync {MainActivity::class.java.getDeclaredMethod("showSettings",String::class.java).apply {isAccessible=true}.invoke(activity,"search")}
            screenshot("automatic")
            choose("Tavily")
            inst.runOnMainSync {
                val input=all().filterIsInstance<EditText>().single()
                assertEquals("search-key-Tavily",input.tag);assertFalse(input.isSaveEnabled)
                input.setText("disposable-ui-draft")
            }
            screenshot("tavily")
            inst.runOnMainSync {
                val remove=all().filterIsInstance<Button>().firstOrNull {it.tag=="search-remove-Tavily"}
                if(remove!=null) {
                    remove.performClick()
                    assertTrue(all().filterIsInstance<TextView>().any {it.text.contains("已标记删除")})
                    all().filterIsInstance<Button>().single {it.text.toString()=="撤销删除"}.performClick()
                    assertTrue(all().filterIsInstance<TextView>().any {it.text.contains("已加密保存")})
                    all().filterIsInstance<EditText>().single().setText("disposable-ui-draft")
                }
            }
            choose("DeepSeek")
            inst.runOnMainSync {assertTrue(all().filterIsInstance<EditText>().isEmpty());assertTrue(all().filterIsInstance<TextView>().any {it.text.contains("共用一个 Key")})}
            screenshot("deepseek")
            choose("Tavily")
            inst.runOnMainSync {assertEquals("disposable-ui-draft",all().filterIsInstance<EditText>().single().text.toString())}
            choose("Brave")
            inst.runOnMainSync {
                assertEquals(1,all().filterIsInstance<EditText>().size)
                all().filterIsInstance<Button>().single {it.text.toString()=="高级设置 · 自定义 API 地址"}.performClick()
                assertEquals(2,all().filterIsInstance<EditText>().size)
            }
            choose("关闭")
            inst.runOnMainSync {assertFalse(all().any {it.tag=="search-test"});assertTrue(all().filterIsInstance<EditText>().isEmpty())}
            assertEquals("自动选择",prefs.getString("searchBackend",null))
            assertTrue("Draft editing must not change stored credentials",savedCredentials==context.getSharedPreferences("native-credentials",0).all)
            inst.runOnMainSync {
                Appearance.save(context,ColorMode.DARK)
                MainActivity::class.java.getDeclaredMethod("showSettings",String::class.java).apply {isAccessible=true}.invoke(activity,"search")
            }
            screenshot("dark")
            File(context.filesDir,"search-settings-display.json").writeText(org.json.JSONObject().put("displayId",activity.display!!.displayId).put("expectedLowerDisplayId",lower?.id).put("passed",true).toString())
        } finally {
            Appearance.save(context,oldMode)
            val edit=prefs.edit();if(before==null)edit.remove("searchBackend") else edit.putString("searchBackend",before);check(edit.commit())
            inst.runOnMainSync {activity.finish()}
        }
    }
}
