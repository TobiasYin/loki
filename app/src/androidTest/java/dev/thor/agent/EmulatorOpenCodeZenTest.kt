package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic intercepted catalog responses. Preserves providers, credentials and conversations. */
@RunWith(AndroidJUnit4::class)
class EmulatorOpenCodeZenTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun ui(action:()->Unit)=inst.runOnMainSync(action)
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList())
    private fun until(check:()->Boolean) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(check())return;Thread.sleep(80)};fail("Zen UI did not settle")}
    private fun js(activity:MainActivity,code:String):String {
        val latch=java.util.concurrent.CountDownLatch(1);var result=""
        ui {activity.findViewById<android.webkit.WebView>(android.R.id.custom).evaluateJavascript(code){result=it;latch.countDown()}}
        assertTrue(latch.await(5,TimeUnit.SECONDS));return result
    }
    private fun restore(prefs:SharedPreferences,before:Map<String,*>) {
        val edit=prefs.edit().clear()
        before.forEach {(k,v)->when(v){is String->edit.putString(k,v);is Int->edit.putInt(k,v);is Boolean->edit.putBoolean(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v);is Set<*>->edit.putStringSet(k,v.filterIsInstance<String>().toSet())}}
        check(edit.commit())
    }
    private fun snapshot(activity:MainActivity) {
        val latch=java.util.concurrent.CountDownLatch(1);var bitmap:android.graphics.Bitmap?=null;var result=-1
        ui {
            bitmap=android.graphics.Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,android.graphics.Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(activity.window,bitmap!!,{code->result=code;latch.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))
        }
        assertTrue(latch.await(5,TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
        try {File(context.filesDir,"emulator-opencode-zen.png").outputStream().use {bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally {bitmap?.recycle()}
    }
    @Test fun keyAutomaticallyLoadsFreeModelsAndSelectionPersistsWithInputLimits() {
        assertTrue("Dedicated emulator only",android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val providerPrefs=context.getSharedPreferences("provider-configs",0);val providerBefore=HashMap(providerPrefs.all)
        val catalogPrefs=context.getSharedPreferences("opencode-zen-model-catalog",0);val catalogBefore=HashMap(catalogPrefs.all)
        val profilePrefs=context.getSharedPreferences("profiles",0);val profileBefore=HashMap(profilePrefs.all)
        val keysBefore=HashMap(context.getSharedPreferences("native-credentials",0).all)
        val report=JSONObject().put("cloudCalled",false).put("display",4)
        var launched:MainActivity?=null;var fixtureId:String?=null
        try {
            val activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(4).toBundle()) as MainActivity
            launched=activity
            val ready=EmulatorRuntimeTestSupport.awaitReady(activity);assertFalse(ready.host.hasActiveRuns())
            ui {assertEquals(4,activity.display!!.displayId)}
            val lazyCatalog=MainActivity::class.java.getDeclaredField("zenCatalog\$delegate").apply {isAccessible=true}.get(activity) as Lazy<*>
            val catalog=lazyCatalog.value as OpenCodeZenCatalog
            val calls=AtomicInteger();var failRefresh=false;var empty=false
            fun model(id:String,price:Int=0)=JSONObject().put("id",id).put("name",id).put("reasoning",false).put("tool_call",true)
                .put("cost",JSONObject().put("input",price).put("output",price)).put("limit",JSONObject().put("context",32768).put("input",24576).put("output",8192))
                .put("modalities",JSONObject().put("input",JSONArray(listOf("text"))).put("output",JSONArray(listOf("text"))))
            val fixtureClient=OkHttpClient.Builder().addInterceptor {chain->
                val request=chain.request();val availability=request.url.toString()==OpenCodeZenCatalog.SOURCE
                assertTrue(availability || request.url.toString()==ModelCatalog.SOURCE)
                if(availability) {assertEquals("Bearer zen-synthetic-key",request.header("Authorization"));calls.incrementAndGet()}
                else assertNull("Key must not reach Models.dev",request.header("Authorization"))
                val data=if(availability)JSONObject().put("object","list").put("data",JSONArray((if(empty)listOf("paid-free") else listOf("alpha-free","beta-free","paid-free")).map {JSONObject().put("id",it)}))
                else JSONObject().put("opencode",JSONObject().put("npm","@ai-sdk/openai-compatible").put("models",JSONObject().put("alpha-free",model("alpha-free")).put("beta-free",model("beta-free")).put("paid-free",model("paid-free",1))))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(failRefresh)401 else 200).message("Fixture").body(data.toString().toResponseBody()).build()
            }.build()
            OpenCodeZenCatalog::class.java.getDeclaredField("client").apply {isAccessible=true}.set(catalog,fixtureClient)
            fun editor(saved:JSONObject?=null) {ui {MainActivity::class.java.getDeclaredMethod("showProviderEditor",JSONObject::class.java,String::class.java).apply {isAccessible=true}.invoke(activity,saved,OpenCodeZenCatalog.KIND)};inst.waitForIdleSync()}
            fun status():String {var text="";ui {text=activity.window.decorView.findViewWithTag<TextView>("model-catalog-status").text.toString()};return text}
            editor()
            assertEquals(0,calls.get())
            ui {activity.window.decorView.findViewWithTag<EditText>("provider-api-key").setText("zen-synthetic-key")}
            until {status().contains("输入上限 24576")}
            assertEquals(1,calls.get())
            ui {
                val picker=activity.window.decorView.findViewWithTag<Spinner>("model-picker")
                assertEquals(2,picker.count);assertEquals("alpha-free",picker.adapter.getItem(0));assertEquals("beta-free",picker.adapter.getItem(1));picker.setSelection(1)
            }
            inst.waitForIdleSync()
            assertTrue(status().contains("上下文 32768"));assertTrue(status().contains("输出容量 8192"))
            // Save through the actual settings action and use only the disposable record.
            val idsBefore=ProviderStore(context).list().map {it.getString("id")}.toSet()
            ui {views(activity.window.decorView).filterIsInstance<Button>().single {it.text.toString()=="保存"}.performClick()}
            until {ProviderStore(context).list().any {it.getString("id") !in idsBefore}}
            val store=ProviderStore(context);val saved=store.list().single {it.getString("id") !in idsBefore};fixtureId=saved.getString("id")
            assertEquals("beta-free",saved.getString("model"));assertEquals(2,saved.getInt("protocol"));assertEquals("zen-synthetic-key",store.credential(fixtureId!!))
            assertFalse(providerPrefs.all.toString().contains("zen-synthetic-key"))
            store.setDefault(fixtureId!!);EmulatorRuntimeTestSupport.reloadIdle(activity)
            val selection=EmulatorRuntimeTestSupport.awaitReady(activity).host.view().getJSONObject("modelSelection")
            assertEquals(OpenCodeZenCatalog.KIND,selection.getString("provider"));assertEquals("beta-free",selection.getString("modelId"))
            assertEquals(24576,selection.getInt("maxInput"));assertEquals(32768,selection.getInt("contextWindow"));assertEquals(8192,selection.getInt("maxOutput"))
            assertTrue(selection.getBoolean("providerMetadataKnown"));assertFalse(selection.getBoolean("probeRequired"));assertFalse(selection.toString().contains("zen-synthetic-key"))
            until {js(activity,"document.querySelector('#model-context').textContent.includes('输入')")=="true"}
            assertEquals("true",js(activity,"document.querySelector('#model-context').textContent.includes('25K')"))
            report.put("chatShowsInputLimit",true)
            editor(store.get(fixtureId!!));until {calls.get()>=2 && status().contains("输入上限 24576")}
            ui {assertEquals(0,activity.window.decorView.findViewWithTag<EditText>("provider-api-key").text.length);assertEquals(1,activity.window.decorView.findViewWithTag<Spinner>("model-picker").selectedItemPosition)}
            snapshot(activity)
            failRefresh=true
            ui {views(activity.window.decorView).filterIsInstance<Button>().single {it.text.toString()=="刷新 Zen 免费模型"}.performClick()}
            until {status().contains("暂时无法刷新")}
            assertEquals(2,catalog.models().length())
            failRefresh=false;empty=true
            ui {views(activity.window.decorView).filterIsInstance<Button>().single {it.text.toString()=="刷新 Zen 免费模型"}.performClick()}
            until {status().contains("当前没有可用")}
            ui {assertFalse(activity.window.decorView.findViewWithTag<Spinner>("model-picker").isEnabled)}
            val emptyView=EmulatorRuntimeTestSupport.awaitReady(activity).host.view()
            assertEquals(0,emptyView.getJSONArray("modelChoices").length());assertFalse(emptyView.getJSONObject("modelSelection").getBoolean("configured"))
            report.put("passed",true).put("autoFetch",true).put("paidModelsExcluded",true).put("freeSelectionPersisted",true).put("inputLimitPropagated",true).put("failureRetainsCache",true).put("emptyFreeListHandled",true)
        } finally {
            fixtureId?.let {CredentialVault(context).remove(ProviderStore.credentialRef(it))}
            restore(providerPrefs,providerBefore);restore(catalogPrefs,catalogBefore);restore(profilePrefs,profileBefore)
            launched?.let {try {EmulatorRuntimeTestSupport.reloadIdle(it)}finally {ui {it.finish()}}}
            assertEquals("Existing encrypted credentials must be preserved",keysBefore,context.getSharedPreferences("native-credentials",0).all)
            report.put("existingCredentialsPreserved",true)
            File(context.filesDir,"emulator-opencode-zen.json").writeText(report.toString(2))
        }
    }
}
