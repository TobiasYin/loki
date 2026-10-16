package dev.thor.agent

import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Assert.*
import org.junit.rules.TestName
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shared emulator-only preservation. No precondition failure grants cleanup authority. */
abstract class EmulatorBackgroundTestState {
    protected val ins get()=InstrumentationRegistry.getInstrumentation()
    protected val context get()=ins.targetContext
    @get:Rule val testName=TestName()
    private var originalStateCaptured=false
    private var selectedBefore:String?=null
    private var a11yBefore:String?=null
    private var a11yEnabledBefore:String?=null
    private var profileBefore:Map<String,* > = emptyMap<String,Any?>()
    private var vaultBefore:Map<String,* > = emptyMap<String,Any?>()
    private var rolesBefore:Map<String,* > = emptyMap<String,Any?>()
    private var historyBefore=emptyMap<String,Set<String>>()
    private val setupWorkers=mutableSetOf<java.util.concurrent.ExecutorService>()
    private fun snapshotHistory()=SqliteStore(context,"product.db").use {store->listOf("sessions","runs","messages","events","attachments").associateWith {table->
        store.readableDatabase.rawQuery("SELECT id FROM $table",null).use {c->buildSet {while(c.moveToNext())add(c.getString(0))}}}}
    private fun selected()=SqliteStore(context,"product.db").use {store->store.readableDatabase.rawQuery("SELECT value FROM metadata WHERE name='selected'",null).use {c->if(c.moveToFirst())c.getString(0) else null}}
    @Before fun preserveOriginalEmulatorState() {
        assertTrue("Emulator only",Build.HARDWARE in setOf("ranchu","goldfish"))
        ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertFalse("Never interrupt existing work",runsSafelyActive())
        selectedBefore=selected();historyBefore=snapshotHistory()
        profileBefore=context.getSharedPreferences("profiles",0).all.toMap();vaultBefore=context.getSharedPreferences("native-credentials",0).all.toMap();rolesBefore=context.getSharedPreferences("display-roles",0).all.toMap()
        a11yBefore=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        a11yEnabledBefore=Settings.Secure.getString(context.contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED)
        originalStateCaptured=true
    }
    @After fun restoreOriginalEmulatorState() {
        if(!originalStateCaptured)return // A failed precondition grants no cleanup authority.
        val report=obj("test" to testName.methodName,"passed" to false,"scope" to "preservation only; lifecycle duration is reported by each case")
        try {
            assertFalse("Do not reconfigure or stop a nonterminal Run",runsSafelyActive())
            if(selectedBefore!=null) {
                val activity=launch()
                try {
                    val ready=EmulatorRuntimeTestSupport.awaitReady(activity)
                    val c=ProductHost::class.java.getDeclaredField("connection").apply {isAccessible=true}.get(ready.host) as String
                    val result=ready.host.rpc(c,obj("op" to "ui","data" to obj("op" to "select","sessionId" to selectedBefore))).get(20,TimeUnit.SECONDS)
                    assertTrue("Original session selection rejected",result.isNull("error"))
                }finally {ins.runOnMainSync {activity.finish()}}
            }
            val automation=ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            until("Idle runtime must close before restoring accessibility") {
                val text=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys activity services dev.thor.agent/.AgentRuntimeService")).use {it.readBytes().toString(Charsets.UTF_8)}
                !text.contains("ServiceRecord{") && setupWorkers.all {it.isTerminated}
            }
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {Settings.Secure.putString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,a11yBefore);Settings.Secure.putString(context.contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED,a11yEnabledBefore)}finally {automation.dropShellPermissionIdentity()}
            assertEquals(a11yBefore,Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));assertEquals(a11yEnabledBefore,Settings.Secure.getString(context.contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED))
            assertEquals(selectedBefore,selected())
            assertEquals(profileBefore,context.getSharedPreferences("profiles",0).all);assertEquals(vaultBefore,context.getSharedPreferences("native-credentials",0).all);assertEquals(rolesBefore,context.getSharedPreferences("display-roles",0).all)
            val after=snapshotHistory();historyBefore.forEach {(table,ids)->assertTrue("Existing $table history removed",after.getValue(table).containsAll(ids))}
            report.put("profilesRestored",true).put("credentialsRestored",true).put("rolesUnchanged",true).put("selectedSessionRestored",true).put("accessibilityRestoredLast",true).put("originalHistoryPreserved",true).put("idleHostsAndSetupWorkersClosed",true).put("passed",true)
        }catch(t:Throwable){report.put("failure",t.message);throw t}
        finally {
            val file=File(context.filesDir,"background-preservation.json")
            val all=if(file.exists())JSONArray(file.readText()) else JSONArray();all.put(report);file.writeText(all.toString(2))
        }
    }
    protected fun until(reason:String,check:()->Boolean) {val end=SystemClock.elapsedRealtime()+60000;while(SystemClock.elapsedRealtime()<end){if(try {check()}catch(_:android.database.sqlite.SQLiteDatabaseLockedException){false})return;Thread.sleep(80)};fail(reason)}
    protected fun js(web:WebView,source:String):String {val done=CountDownLatch(1);var value="";ins.runOnMainSync {web.evaluateJavascript(source){value=it;done.countDown()}};assertTrue(done.await(10,TimeUnit.SECONDS));return value}
    protected fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.get(a)
    protected fun launch():MainActivity {
        val a=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        until("Service engine attach"){a.findViewById<WebView>(android.R.id.custom)!=null}
        val web=a.findViewById<WebView>(android.R.id.custom)
        until("Native document ready"){js(web,"window.productReady===true && !document.getElementById('new').disabled")=="true"}
        EmulatorRuntimeTestSupport.awaitReady(a)
        val host=field(a,"host") as ProductHost
        val debug=ProductHost::class.java.getDeclaredField("debug").apply {isAccessible=true}.get(host) as dev.thor.tools.diagnostic.DebugConnection
        setupWorkers.add(dev.thor.tools.diagnostic.DebugConnection::class.java.getDeclaredField("worker").apply {isAccessible=true}.get(debug) as java.util.concurrent.ExecutorService)
        return a
    }
    protected fun restore(prefs:android.content.SharedPreferences,values:Map<String,*>) {
        val edit=prefs.edit().clear();for((key,value) in values)when(value) {
            is String->edit.putString(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value)
            is Boolean->edit.putBoolean(key,value);is Float->edit.putFloat(key,value)
            is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
        };check(edit.commit())
    }
    protected fun runsSafelyActive():Boolean=runCatching {SqliteStore(context,"product.db").use {store->store.readableDatabase.rawQuery("SELECT payload FROM runs",null).use {c->var active=false;while(c.moveToNext())if(JSONObject(c.getString(0)).optString("status") !in setOf("completed","failed","cancelled","interrupted"))active=true;active}}}.getOrDefault(true)
}
