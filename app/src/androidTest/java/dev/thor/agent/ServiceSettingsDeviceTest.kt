package dev.thor.agent

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ServiceSettingsDeviceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
    @Test fun modernModelAndVoiceFormsKeepCredentialsAndDraftsSafe() {
        val context=inst.targetContext
        val lower=dev.thor.tools.diagnostic.DisplayRoles(context).resolve(dev.thor.tools.diagnostic.displayFacts(context))?.second
        if(android.os.Build.MODEL.contains("Thor",true))assertNotNull(lower)
        val options=android.app.ActivityOptions.makeBasic().apply {if(lower!=null)setLaunchDisplayId(lower.id)}
        val activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),options.toBundle()) as MainActivity
        if(lower!=null)assertEquals(lower.id,activity.display!!.displayId)
        EmulatorRuntimeTestSupport.awaitReady(activity)
        val credentialBefore=HashMap(context.getSharedPreferences("native-credentials",0).all)
        val voiceBefore=HashMap(context.getSharedPreferences("voice",0).all)
        val providersBefore=ProviderStore(context).list().map {it.toString()}
        fun all()=views(activity.window.decorView)
        fun showEditor(kind:String)=inst.runOnMainSync {
            MainActivity::class.java.getDeclaredMethod("showProviderEditor",JSONObject::class.java,String::class.java).apply {isAccessible=true}.invoke(activity,null,kind)
        }
        fun screenshot(name:String) {
            inst.runOnMainSync {
                val v=activity.window.decorView;val image=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(image))
                File(context.filesDir,"service-settings-$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            }
        }
        try {
            inst.runOnMainSync {MainActivity::class.java.getDeclaredMethod("showModelProviders").apply {isAccessible=true}.invoke(activity)}
            screenshot("models")
            showEditor("deepseek")
            inst.runOnMainSync {
                assertTrue(all().filterIsInstance<Spinner>().single {it.tag=="model-picker"}.isShown)
                assertFalse(all().filterIsInstance<EditText>().any {it.hint=="自定义凭据头（可选）" && it.isShown})
            }
            screenshot("deepseek")
            showEditor("custom")
            inst.runOnMainSync {
                assertTrue(all().filterIsInstance<EditText>().any {it.hint=="模型基础 URL（HTTP / HTTPS）" && it.isShown})
                all().single {it.tag=="provider-api-key"}.let {assertFalse(it.isSaveEnabled);(it as EditText).setText("disposable-form-draft")}
                all().filterIsInstance<Button>().single {it.text.toString()=="高级选项 ›"}.performClick()
                assertTrue(all().filterIsInstance<EditText>().single {it.hint=="自定义凭据头（可选）"}.isShown)
            }
            inst.runOnMainSync {MainActivity::class.java.getDeclaredMethod("showVoiceSettingsPage").apply {isAccessible=true}.invoke(activity)}
            inst.runOnMainSync {activity.window.decorView.findViewWithTag<Spinner>("voice-auth").setSelection(0)}
            inst.waitForIdleSync()
            screenshot("voice")
            inst.runOnMainSync {
                val api=activity.window.decorView.findViewWithTag<EditText>("voice-api-key")
                assertTrue(api.isShown);assertFalse(activity.window.decorView.findViewWithTag<EditText>("voice-access-token").isShown)
                api.setText("disposable-voice-draft")
                activity.window.decorView.findViewWithTag<Spinner>("voice-auth").setSelection(1)
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                assertTrue(activity.window.decorView.findViewWithTag<EditText>("voice-access-token").isShown)
                assertFalse(activity.window.decorView.findViewWithTag<EditText>("voice-api-key").isShown)
                activity.window.decorView.findViewWithTag<Spinner>("voice-auth").setSelection(0)
            }
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val api=activity.window.decorView.findViewWithTag<EditText>("voice-api-key")
                assertEquals("disposable-voice-draft",api.text.toString())
                activity.window.decorView.findViewWithTag<Button>("voice-remove-keys").performClick();assertFalse(api.isEnabled)
                activity.window.decorView.findViewWithTag<Button>("voice-remove-keys").performClick();assertTrue(api.isEnabled)
                activity.window.decorView.findViewWithTag<Button>("voice-check").performClick()
                assertTrue(activity.window.decorView.findViewWithTag<TextView>("voice-check-status").text.contains("资料已齐全"))
            }
            assertTrue(credentialBefore==context.getSharedPreferences("native-credentials",0).all)
            assertTrue(voiceBefore==context.getSharedPreferences("voice",0).all)
            assertEquals(providersBefore,ProviderStore(context).list().map {it.toString()})
            File(context.filesDir,"service-settings-display.json").writeText(JSONObject().put("displayId",activity.display!!.displayId).put("expectedLowerDisplayId",lower?.id).put("passed",true).toString())
        } finally {inst.runOnMainSync {activity.finish()}}
    }
}
