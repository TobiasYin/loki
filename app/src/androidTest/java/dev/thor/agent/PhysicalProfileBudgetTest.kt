package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual settings UI save: preserves credentials and invalidates the old probe. */
@RunWith(AndroidJUnit4::class)
class PhysicalProfileBudgetTest {
    @Test fun configure128KLocalBudgetWithoutReadingCredentialsOrForgingProbe() {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext;val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        val prefs=context.getSharedPreferences("profiles",0);assertEquals("deepseek-v4.1-flash",prefs.getString("model",null));assertEquals("https://opencode.ai/zen/go/v1",prefs.getString("url",null))
        if(prefs.getInt("contextBudget",32768)==131072)return
        val revision=prefs.getInt("revision",-1);val encrypted=context.getSharedPreferences("native-credentials",0).all
        val automation=ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val info=automation.serviceInfo;info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.serviceInfo=info
        val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay")!!.toInt()).toBundle()) as MainActivity
        val web=activity.findViewById<WebView>(android.R.id.custom)
        fun js(code:String):String {val latch=CountDownLatch(1);var result="";ins.runOnMainSync {web.evaluateJavascript(code){result=it;latch.countDown()}};assertTrue(latch.await(5,TimeUnit.SECONDS));return result}
        fun until(condition:()->Boolean) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(System.nanoTime()<end){if(condition())return;Thread.sleep(100)};fail("Native budget UI did not become ready")}
        fun find(node:AccessibilityNodeInfo?, predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            if(node==null)return null
            if(predicate(node))return node
            for(i in 0 until node.childCount){val found=find(node.getChild(i),predicate);if(found!=null)return found}
            return null
        }
        fun all(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            val windows=automation.windowsOnAllDisplays
            for(i in 0 until windows.size())for(window in windows.valueAt(i))find(window.root,predicate)?.let {return it}
            return null
        }
        until {js("window.productReady===true")=="true"};js("document.getElementById('settings').click()")
        var editor:AccessibilityNodeInfo?=null
        until {editor=all {it.isEditable && it.hintText?.contains("本地上下文预算")==true};editor!=null}
        assertTrue(editor!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"131072")}))
        val save=all {it.text?.toString()=="保存" && it.isClickable} ?: error("Native Save button unavailable")
        assertTrue(save.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        until {prefs.getInt("contextBudget",0)==131072 && prefs.getInt("revision",-1)==revision+1}
        assertEquals("untested",prefs.getString("probe",null));assertEquals(-1,prefs.getInt("probeRevision",-1));assertTrue("Encrypted credentials must remain unchanged",encrypted==context.getSharedPreferences("native-credentials",0).all)
        File(context.filesDir,"physical-profile-budget.json").writeText(obj("contextBudget" to 131072,"maxOutput" to prefs.getInt("maxOutput",0),"revision" to prefs.getInt("revision",0),"probe" to prefs.getString("probe",null),"savedThroughNativeUi" to true,"credentialsUnchanged" to true).toString(2))
    }
}
