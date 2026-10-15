package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Additive acceptance only: no fixture model profile, credential access or history reset. */
@RunWith(AndroidJUnit4::class)
class PhysicalFeaturesTest {
    @Test fun skillsVoiceAndStructuredPresentationPreserveExistingInstallation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        val context=instrumentation.targetContext
        val profiles=context.getSharedPreferences("profiles",0).all.toString()
        val encrypted=context.getSharedPreferences("native-credentials",0).all.toString()
        val automation=instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val info=automation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo=info
        var activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay","4").toInt()).toBundle()) as MainActivity
        fun host()=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        fun js(script:String):String {
            val latch=CountDownLatch(1);var result=""
            instrumentation.runOnMainSync {activity.findViewById<WebView>(android.R.id.custom).evaluateJavascript(script) {result=it;latch.countDown()}}
            assertTrue(latch.await(5,TimeUnit.SECONDS));return result
        }
        fun until(condition:()->Boolean) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<end){if(condition())return;Thread.sleep(80)};fail("Feature acceptance timed out")}
        val name="acceptance-${System.currentTimeMillis()}";val report=JSONObject();val file=File(context.cacheDir,"$name.zip")
        val content="---\nname: $name\ndescription: 实机验收专用 Skill\n---\nBODY_DEVICE_SENTINEL\n按需读取 references/guide.md。"
        try {
            until {js("window.productReady===true")=="true"}
            if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
                js("document.getElementById('voice-toggle').click()")
                until {
                    val windows=instrumentation.uiAutomation.windowsOnAllDisplays
                    var clicked=false
                    val roots=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                    for(i in 0 until windows.size())for(w in windows.valueAt(i))w.root?.let {roots.add(it)}
                    automation.rootInActiveWindow?.let {roots.add(it)}
                    report.put("permissionWindowPackages",org.json.JSONArray(roots.map {it.packageName?.toString()}))
                    for(root in roots) {
                        if(root.packageName?.toString()?.endsWith("permissioncontroller")!=true)continue
                        val node=root.findAccessibilityNodeInfosByText("仅在使用该应用时允许").firstOrNull {it.isVisibleToUser}
                        if(node!=null)clicked=node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) || clicked
                    }
                    clicked || context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                until {context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED}
                assertEquals("true",js("window.productReady===true && !document.getElementById('voice-panel').hidden"))
                js("document.getElementById('voice-toggle').click()")
                report.put("microphonePermissionFlow",true)
            }
            val messagesBefore=host().view().getJSONArray("messages").length()
            ZipOutputStream(file.outputStream()).use {z->for((path,text) in mapOf("$name/SKILL.md" to content,"$name/references/guide.md" to "REFERENCE_DEVICE_SENTINEL")){z.putNextEntry(ZipEntry(path));z.write(text.toByteArray());z.closeEntry()}}
            host().importSkill(Uri.fromFile(file)).get(5,TimeUnit.SECONDS)
            assertFalse(host().view().getJSONArray("skills").toString().contains("BODY_DEVICE_SENTINEL"))
            js("document.getElementById('skills-open').click()")
            until {js("document.getElementById('skills-list').textContent.includes('$name')")=="true"}
            js("[...document.querySelectorAll('.skill-row')].find(e=>e.textContent.includes('$name')).querySelector('button').click()")
            until {js("document.getElementById('skill-content').textContent.includes('BODY_DEVICE_SENTINEL')")=="true"}
            assertEquals("false",js("document.getElementById('skill-content').textContent.includes('REFERENCE_DEVICE_SENTINEL')"))
            js("document.getElementById('skill-resource').value='references/guide.md';document.getElementById('skill-resource').dispatchEvent(new Event('change'))")
            until {js("document.getElementById('skill-content').textContent==='REFERENCE_DEVICE_SENTINEL'")=="true"}
            js("[...document.querySelectorAll('.skill-row')].find(e=>e.textContent.includes('$name')).querySelectorAll('button')[1].click()")
            until {host().view().getJSONArray("skills").objects().single {it.getString("name")==name}.getBoolean("enabled")==false}
            instrumentation.runOnMainSync {activity.finish()};instrumentation.waitForIdleSync();Thread.sleep(250)
            activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay","4").toInt()).toBundle()) as MainActivity
            until {js("window.productReady===true")=="true"}
            assertFalse(host().view().getJSONArray("skills").objects().single {it.getString("name")==name}.getBoolean("enabled"))
            report.put("skillZipImport",true).put("metadataOnly",true).put("explicitResourceRead",true).put("disabledSurvivesRestart",true)
            js("document.getElementById('voice-toggle').click()")
            assertEquals("true",js("document.getElementById('input').hidden && !document.getElementById('voice-panel').hidden"))
            if(CredentialVault(context).status("voice.api")=="未保存" && CredentialVault(context).status("voice.access")=="未保存") {
                js("document.getElementById('voice-hold').dispatchEvent(new PointerEvent('pointerdown',{pointerId:1,button:0,isPrimary:true,bubbles:true}))")
                until {js("document.getElementById('voice-status').textContent.includes('语音不可用')")=="true"}
                report.put("missingVoiceConfigActionable",true)
            }
            val mic=MicrophoneInput(context);var bytes=0
            try {mic.start();bytes=mic.read(ByteArray(6400));assertTrue(bytes>0 && bytes%2==0)}finally {mic.close()}
            report.put("physicalMicrophoneBytes",bytes).put("audioUploaded",false)
            js("document.getElementById('voice-toggle').click()")
            val fake=JSONObject(host().view().toString());val sid=fake.getString("selected")
            fake.put("runs",org.json.JSONArray().put(obj("id" to "presentation-test","sessionId" to sid,"status" to "generating","expanded" to true)))
            fake.put("messages",org.json.JSONArray().put(obj("id" to "m","sessionId" to sid,"runId" to "presentation-test","role" to "assistant","text" to "结构化回答")))
            fake.put("events",org.json.JSONArray().put(obj("runId" to "presentation-test","sequence" to 1,"type" to "thinking_delta","data" to obj("text" to "正确聚合思考"))).put(obj("runId" to "presentation-test","sequence" to 2,"type" to "tool_started","toolCallId" to "c","data" to obj("toolId" to "skills.read","arguments" to obj("name" to "skill-creator")))))
            js("window.receiveNative(${fake})")
            assertEquals("true",js("document.querySelector('.thinking').textContent.includes('正确聚合思考') && document.querySelectorAll('.tool-card').length===1 && !document.getElementById('chat').textContent.includes('thinking_delta')"))
            report.put("webViewStructuredEvents",true)
            js("window.ThorUI.postMessage(JSON.stringify({op:'ready'}))")
            until {js("document.querySelector('.thinking')===null")=="true" || host().view().getJSONArray("events").objects().any {it.optString("type")=="thinking_delta"}}
            assertEquals(messagesBefore,host().view().getJSONArray("messages").length())
            assertEquals(profiles,context.getSharedPreferences("profiles",0).all.toString());assertEquals(encrypted,context.getSharedPreferences("native-credentials",0).all.toString())
            report.put("profilesAndEncryptedCredentialsPreserved",true).put("messagesPreserved",true).put("cloudAsrVerified",false).put("passed",true)
        } finally {
            runCatching {val store=SkillStore(File(context.filesDir,"skills"));val entry=store.catalog().objects().singleOrNull {it.getString("name")==name};if(entry!=null)store.delete(name,entry.getString("hash"))}
            file.delete();File(context.filesDir,"physical-features.json").writeText(report.toString(2))
            instrumentation.runOnMainSync {activity.finish()};instrumentation.waitForIdleSync()
        }
    }
}
