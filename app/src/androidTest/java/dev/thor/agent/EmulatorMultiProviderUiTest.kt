package dev.thor.agent

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Settings acceptance. Provision provider records separately. Only active
 * provider selection changes, and it is restored before completing the test.
 * Never reads CredentialVault, fills an API key, saves an editor draft, changes
 * a conversation, refreshes model catalogs, or calls a cloud service.
 * Run with providerUiMode=empty on a fresh installation, or =configured after
 * provisioning at least two records. The other test is explicitly skipped. */
@RunWith(AndroidJUnit4::class)
class EmulatorMultiProviderUiTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun ui(action:()->Unit)=inst.runOnMainSync(action)
    private fun guard(mode:String) {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("providerUiMode")==mode)
        val emulator=android.os.Build.HARDWARE in setOf("ranchu","goldfish")
        assertTrue("Physical UI checks require an explicit providerUiAllowPhysical opt-in",emulator || mode=="configured" && args.getString("providerUiAllowPhysical")=="true")
    }
    private fun views(root:View):List<View> = listOf(root)+(if(root is ViewGroup)(0 until root.childCount).flatMap {views(root.getChildAt(it))} else emptyList())
    private fun showProviders(activity:MainActivity) {
        ui {MainActivity::class.java.getDeclaredMethod("showSettings",String::class.java).apply {isAccessible=true}.invoke(activity,"model")}
        inst.waitForIdleSync()
    }
    private fun back(activity:MainActivity) {
        ui {views(activity.window.decorView).filterIsInstance<Button>().single {it.text.toString()=="‹ 返回"}.performClick()}
        inst.waitForIdleSync()
    }
    private fun js(web:WebView,code:String):String {
        val latch=CountDownLatch(1);var result=""
        ui {web.evaluateJavascript(code){result=it;latch.countDown()}}
        assertTrue("Chat WebView did not answer",latch.await(5,TimeUnit.SECONDS));return result
    }
    private fun recordStatus(activity:MainActivity,id:String):String {
        var status=""
        ui {
            val edit=activity.window.decorView.findViewWithTag<Button>("provider-edit-$id")
            assertNotNull("Provider row must expose an edit action",edit)
            val row=edit.parent.parent as View
            status=views(row).filterIsInstance<TextView>().map {it.text.toString()}.single {it.contains("API Key：")}.substringAfter("API Key：").trim()
        }
        return status
    }
    private fun verifyBlankEditor(activity:MainActivity,status:String?=null) {
        ui {
            val key=activity.window.decorView.findViewWithTag<EditText>("provider-api-key")
            assertNotNull("Each provider editor has an independent key field",key)
            assertEquals("Saved credentials must never populate the editor",0,key.text.length)
            assertFalse("API key draft is excluded from view-state persistence",key.isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,key.importantForAutofill)
            val label=views(activity.window.decorView).filterIsInstance<TextView>().map {it.text.toString()}.single {it.startsWith("API Key：")}
            assertTrue("Key status must belong to the selected record",label.startsWith("API Key：${status ?: "未保存"}；"))
        }
    }
    private fun report(name:String,value:JSONObject) {
        File(context.filesDir,name).writeText(value.put("cloudCalled",false).put("keysReadOrWrittenByTest",false).put("draftSaved",false)
            .put("deviceKind",if(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))"emulator" else "physical").toString(2))
    }
    private fun until(check:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while(System.nanoTime()<end){if(check())return;Thread.sleep(40)}
        fail("Provider UI activation did not settle")
    }
    private fun activate(activity:MainActivity,ready:EmulatorRuntimeTestSupport.Ready,id:String) {
        if(ProviderStore(context).defaultId()!=id) {
            ui {
                val button=activity.window.decorView.findViewWithTag<Button>("provider-activate-$id")
                assertNotNull("Other saved providers expose a use-service action",button);assertTrue(button.performClick())
            }
        }
        until {
            if(ProviderStore(context).defaultId()!=id)return@until false
            var settled=false
            ui {settled=activity.window.decorView.findViewWithTag<Button>("provider-activate-$id")==null}
            settled && js(ready.web,"document.querySelector('#model-select').dataset.value.startsWith(${JSONObject.quote("$id::")})")=="true"
        }
        val current=EmulatorRuntimeTestSupport.awaitReady(activity)
        assertSame("Switching service must not replace the chat WebView",ready.web,current.web)
        assertSame("Switching service must not replace the engine host",ready.host,current.host)
        assertSame("Switching service must not replace the execution WebView",ready.executionWeb,current.executionWeb)
        val choices=current.host.view().getJSONArray("modelChoices").objects()
        assertTrue("Configured active service has model choices",choices.isNotEmpty())
        assertTrue("Only the newly active service appears in composer",choices.all {it.getString("providerConfigId")==id})
    }
    @Test fun freshUnconfiguredInstallStartsChatAndAllowsAddingAProviderWithoutSaving() {
        guard("empty")
        val store=ProviderStore(context)
        assertTrue("Use a fresh empty provider store for this case",store.list().isEmpty())
        val before=context.getSharedPreferences("provider-configs",0).getString("document",null)
        val activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val ready=EmulatorRuntimeTestSupport.awaitReady(activity)
            assertFalse("Read-only settings check must not interrupt existing work",ready.host.hasActiveRuns())
            val snapshot=ready.host.view()
            assertEquals(0,snapshot.getJSONArray("modelChoices").length())
            assertFalse(snapshot.getJSONObject("modelSelection").getBoolean("configured"))
            until {js(ready.web,"document.querySelector('#model-select').getAttribute('aria-disabled')==='true' && !document.querySelector('#settings').disabled && document.querySelector('#notice').hidden")=="true"}
            showProviders(activity)
            ui {assertNotNull(activity.window.decorView.findViewWithTag<Button>("provider-add"))}
            ui {MainActivity::class.java.getDeclaredMethod("showProviderEditor",JSONObject::class.java,String::class.java).apply {isAccessible=true}.invoke(activity,null,"deepseek")}
            inst.waitForIdleSync();verifyBlankEditor(activity)
            back(activity)
            assertEquals("Returning from an unsaved provider must not create it",before,context.getSharedPreferences("provider-configs",0).getString("document",null))
            report("emulator-multi-provider-empty-ui.json",JSONObject().put("passed",true).put("freshChatReady",true).put("emptyModelChoices",true).put("unsavedEditorReturnedToList",true))
        } finally {ui {activity.finish()};inst.waitForIdleSync()}
    }
    @Test fun existingProviderEditorsKeepIndependentKeyStatusAcrossRepeatedNavigation() {
        guard("configured")
        val store=ProviderStore(context);val configs=store.list()
        val enabled=configs.filter {it.getBoolean("enabled")}
        assertTrue("Provision at least two enabled provider records before this test",enabled.size>=2)
        val originalActive=requireNotNull(store.defaultId())
        val before=context.getSharedPreferences("provider-configs",0).getString("document",null)
        val activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var originalReady:EmulatorRuntimeTestSupport.Ready?=null
        var activeTouched=false
        try {
            val ready=EmulatorRuntimeTestSupport.awaitReady(activity)
            originalReady=ready
            assertFalse("Read-only settings check must not interrupt existing work",ready.host.hasActiveRuns())
            val choices=ready.host.view().getJSONArray("modelChoices").objects()
            assertEquals("Provider/model choices must be unique",choices.size,choices.map {it.getString("choiceId")}.distinct().size)
            assertTrue("Only the active service contributes composer models",choices.all {it.getString("providerConfigId")==store.defaultId()})
            choices.forEach {choice->
                val config=configs.single {it.getString("id")==choice.getString("providerConfigId")}
                assertTrue("Disabled records are excluded from the composer",config.getBoolean("enabled"))
                assertEquals(config.getString("name"),choice.getString("providerName"))
            }
            assertEquals("true",js(ready.web,"[...document.querySelectorAll('#model-select [role=option]')].every(n=>n.dataset.value.includes('::'))"))
            showProviders(activity)
            val ids=enabled.take(2).map {it.getString("id")}
            val statuses=ids.associateWith {recordStatus(activity,it)}
            assertTrue("Both test providers need saved usable credentials",statuses.values.all {it=="已加密保存"})
            for(id in listOf(ids[0],ids[1],ids[0])) {
                ui {activity.window.decorView.findViewWithTag<Button>("provider-edit-$id").performClick()}
                inst.waitForIdleSync();verifyBlankEditor(activity,statuses.getValue(id));back(activity)
                for(other in ids)assertEquals("Opening another record must preserve this record's key status",statuses.getValue(other),recordStatus(activity,other))
            }
            activeTouched=true
            for(id in listOf(ids[1],ids[0],originalActive)) {
                activate(activity,ready,id)
                for(other in ids)assertEquals("Switching the active service preserves each saved key status",statuses.getValue(other),recordStatus(activity,other))
            }
            assertEquals("Editor navigation must not change provider metadata",before,context.getSharedPreferences("provider-configs",0).getString("document",null))
            report("emulator-multi-provider-existing-ui.json",JSONObject().put("passed",true).put("providerCount",configs.size).put("checkedEditorCount",2).put("navigationCount",3).put("providerChoicesUnique",true).put("emptySecretFields",true).put("statusPreserved",true).put("metadataUnchanged",true).put("activeProviderSwitchAndRestore",true).put("engineAndWebViewsPreserved",true).put("onlyActiveModelsListed",true))
        } finally {
            try {if(activeTouched && store.defaultId()!=originalActive)originalReady?.host?.activateProvider(originalActive)?.get(10,TimeUnit.SECONDS)}
            finally {ui {activity.finish()};inst.waitForIdleSync()}
        }
    }
}
