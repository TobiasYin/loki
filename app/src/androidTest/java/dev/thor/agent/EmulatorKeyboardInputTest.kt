package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.Budgets
import dev.thor.tools.hash
import dev.thor.tools.diagnostic.DisplayRoles
import dev.thor.tools.diagnostic.ThorAccessibilityService
import dev.thor.tools.diagnostic.displayFacts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit emulator-only Native RPC keyboard component. Synthetic offline receiver,
 * real display/window/accessibility/gesture/Engine path; no model or SSE execution.
 * No shell input, forged target, relaxed lower guard, or receiver verdict injection. */
@RunWith(AndroidJUnit4::class)
class EmulatorKeyboardInputTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val readerDelegate=lazy {SqliteStore(context,"product.db")}
    private val reader get()=readerDelegate.value
    private val terminal=setOf("completed","failed","cancelled","interrupted")
    private val receiverPackage="dev.thor.inputlab"
    private val evidence=obj("schemaVersion" to 1,"evidenceMode" to "manual_platform_api",
        "scope" to "emulator real Native RPC keyboard-input component; ordinary Activity; no model execution",
        "cloudCalled" to false,"modelExecutionProven" to false,"systemInjectionProven" to false,
        "steps" to JSONArray(),"cleanupErrors" to JSONArray(),"textPassed" to false,"keysPassed" to false,"passed" to false)
    @After fun closeReader(){if(readerDelegate.isInitialized())reader.close()}
    private fun save(){File(context.filesDir,"emulator-keyboard-input.json").writeText(evidence.toString(2))}
    // Private diagnostic only: stacks may include exception text; never publish this file.
    private val privateDiagnostics=JSONArray()
    private fun diagnose(stage:String,t:Throwable?=null) {
        privateDiagnostics.put(obj("stage" to stage,"type" to t?.javaClass?.name,"stack" to t?.stackTraceToString()?.take(32768)))
        runCatching {File(context.filesDir,"private-emulator-keyboard-diagnostics.json").writeText(privateDiagnostics.toString(2))}
    }
    private fun diagnoseView(host:ProductHost) {
        try {
            // Keep the single-writer contract; discard the returned view/history entirely.
            val serial=ProductHost::class.java.getDeclaredField("serial").apply {isAccessible=true}.get(host) as java.util.concurrent.ExecutorService
            serial.submit {host.view();Unit}.get(15,TimeUnit.SECONDS)
            diagnose("selection-view-recheck-succeeded")
        }catch(t:Throwable){diagnose("selection-view-recheck-failed",t)}
    }
    private fun until(reason:String,seconds:Int=40,check:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+seconds*1000L
        while(SystemClock.elapsedRealtime()<end){if(check())return;Thread.sleep(60)}
        fail(reason)
    }
    private fun <T> read(block:(SQLiteDatabase)->T):T {
        val end=SystemClock.elapsedRealtime()+40000
        while(true)try{return block(reader.readableDatabase)}catch(e:android.database.sqlite.SQLiteDatabaseLockedException){if(SystemClock.elapsedRealtime()>=end)throw e;Thread.sleep(100)}
    }
    private fun rows(table:String,run:String?=null):List<JSONObject> {
        require(table in setOf("sessions","runs","events"))
        return read {db->db.rawQuery("SELECT payload FROM $table${if(run==null)"" else " WHERE runId=?"} ORDER BY rowid",run?.let {arrayOf(it)}).use {c->buildList {while(c.moveToNext())add(JSONObject(c.getString(0)))}}}
    }
    private fun historyIds():Map<String,Set<String>> =read {db->listOf("sessions","runs","messages","events","attachments").associateWith {table->
        db.rawQuery("SELECT id FROM $table",null).use {c->buildSet {while(c.moveToNext())add(c.getString(0))}}}}
    private fun restore(prefs:SharedPreferences,before:Map<String,*>) {
        val e=prefs.edit().clear();before.forEach {(k,v)->when(v) {
            is String->e.putString(k,v);is Boolean->e.putBoolean(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v)
            is Float->e.putFloat(k,v);is Set<*>->e.putStringSet(k,v.filterIsInstance<String>().toSet())
        }};check(e.commit())
    }
    private fun shell(automation:UiAutomation,command:String):String =ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
        val read=Budgets.read(it,Budgets.CONFIG);assertFalse("Diagnostic output exceeded budget",read.truncated);read.bytes.toString(Charsets.UTF_8)
    }
    private fun receiver(method:String,b:Bundle):JSONObject {
        val reply=context.contentResolver.call(Uri.parse("content://dev.thor.inputlab.keyboard"),method,null,b) ?: error("Keyboard receiver missing")
        return obj("lease" to reply.getString("lease"),"snapshot" to JSONObject(reply.getString("snapshot")!!))
    }
    private fun receiverArgs(test:String,lease:String)=Bundle().apply {putInt("version",1);putString("testRunId",test);putString("lease",lease)}
    private fun launchReceiver(activity:String,display:Int) {
        context.startActivity(Intent().setComponent(ComponentName(receiverPackage,"$receiverPackage.$activity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
    }
    private fun launchChat(display:Int)=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()) as MainActivity
    private fun waitTarget(display:Int) {
        var previous:String?=null;var since=SystemClock.elapsedRealtime()
        until("Independent receiver target not stable on display $display") {
            val target=ThorAccessibilityService.instance?.target(display)
            val good=target?.validity=="valid" && target.packageName==receiverPackage && target.touchableBounds?.let {it.left<it.right && it.top<it.bottom}==true
            val signature=if(good)target!!.layoutRevision else null
            if(signature==null || signature!=previous){previous=signature;since=SystemClock.elapsedRealtime()}
            good && SystemClock.elapsedRealtime()-since>=600
        }
    }
    private class NativeRun(val lease:JSONObject,val connection:String) {val id:String get()=lease.getString("runId");val sid:String get()=lease.getString("sessionId")}
    private fun rpc(host:ProductHost,connection:String,op:String,data:JSONObject):JSONObject =host.rpc(connection,obj("op" to op,"data" to data)).get(20,TimeUnit.SECONDS)
    private fun ok(value:JSONObject):JSONObject {assertTrue("Native RPC rejected: ${value.optString("error")}",value.isNull("error"));return value}
    private fun authority(host:ProductHost,r:NativeRun)=ok(rpc(host,r.connection,"authority",JSONObject(r.lease.toString()).put("refresh",true)))
    private fun details(result:JSONObject):JSONObject =result.optJSONArray("content")?.objects()?.lastOrNull {it.optString("type")=="text"}?.optString("text")?.let {JSONObject(it)} ?: JSONObject()
    private fun tool(host:ProductHost,r:NativeRun,name:String,args:JSONObject=JSONObject(),snapshotOverride:String?=null):JSONObject {
        val state=authority(host,r);val call=id();val request=id()
        val contract=JSONObject(context.assets.open("toolpack-v1.json").bufferedReader().use {it.readText()}).getJSONArray("tools").objects().single {it.getString("toolId")==name}
        val envelope=JSONObject(r.lease.toString()).put("bridgeVersion","0.1").put("schemaVersion","1").put("toolCallId",call).put("toolId",name)
            .put("requestId",request).put("deadline",System.currentTimeMillis()+15000).put("arguments",args)
            .put("targetSnapshotId",snapshotOverride ?: state.getJSONObject("l0").getString("snapshotId")).put("resources",contract.getJSONArray("resources"))
        val event=obj("sessionId" to r.sid,"runId" to r.id,"eventId" to id(),"sequence" to (rows("events",r.id).size+1),"wallTime" to System.currentTimeMillis(),"elapsedMs" to 0,
            "type" to "tool_intent","toolCallId" to call,"data" to obj("toolId" to name,"schemaVersion" to "1","capabilityEpoch" to r.lease.getLong("capabilityEpoch"),"requestId" to request,"outcome" to "unknown"))
        ok(rpc(host,r.connection,"persist",JSONObject(r.lease.toString()).put("kind","event").put("value",event)))
        val start=SystemClock.elapsedRealtime();val prepared=rpc(host,r.connection,"tool.prepare",envelope)
        val result=if(!prepared.isNull("error"))prepared else {assertFalse("Component tools cannot request approval",prepared.optBoolean("awaiting"));rpc(host,r.connection,"tool.execute",envelope)}
        evidence.getJSONArray("steps").put(obj("authorityAvailability" to state.optJSONObject("availability")?.optJSONObject(name),"runId" to r.id,"toolId" to name,"arguments" to args,"elapsedMs" to (SystemClock.elapsedRealtime()-start),"error" to result.opt("error"),"outcome" to result.opt("outcome"),"data" to details(result)))
        save();return result
    }
    private fun tree(host:ProductHost,r:NativeRun)=details(ok(tool(host,r,"perception.ui_tree",obj("visibility" to "visible","maxNodes" to 128,"maxDepth" to 10))))
    @Test fun unicodeTextSelectionClearingAndKeyboardGuardsUseRealNativeRpc() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("keyboardInputAcceptance")=="true")
        assertTrue("Explicit isolated emulator only",Build.HARDWARE in setOf("ranchu","goldfish"))
        val profiles=context.getSharedPreferences("profiles",0);val vault=context.getSharedPreferences("native-credentials",0);val roles=context.getSharedPreferences("display-roles",0)
        assertFalse("Never replace a real credential",vault.contains("model"))
        assertTrue("Never interrupt user work",rows("runs").all {it.optString("status") in terminal})
        val history=historyIds();val profileBefore=profiles.all.toMap();val vaultBefore=vault.all.toMap();val rolesBefore=roles.all.toMap()
        val a11yBefore=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val enabledBefore=Settings.Secure.getString(context.contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED)
        val immersiveBefore=Settings.Secure.getString(context.contentResolver,"immersive_mode_confirmations")
        val automation=ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertFalse("Do not replace another receiver task",shell(automation,"dumpsys activity activities").contains("$receiverPackage/"))
        var virtual:VirtualDisplay?=null;var images:ImageReader?=null;var drain:HandlerThread?=null
        var activity:MainActivity?=null;var host:ProductHost?=null;var run:NativeRun?=null;var connection:String?=null;var selected:String?=null
        var lease:String?=null;val testRun="keyboard-${id()}";var setup=false;var primaryFailure:Throwable?=null
        fun params(change:String?=null)=Bundle().apply {putInt("version",1);putString("testRunId",testRun);putString("lease",lease);if(change!=null)putString("change",change)}
        fun report()=receiver("report",params()).getJSONObject("snapshot")
        fun remember(name:String,value:Any?) {evidence.put(name,value);save()}
        try {
            setup=true
            drain=HandlerThread("keyboard-display-drain").apply {start()};images=ImageReader.newInstance(960,640,PixelFormat.RGBA_8888,2)
            images!!.setOnImageAvailableListener({r->r.acquireLatestImage()?.close()},Handler(drain!!.looper))
            automation.adoptShellPermissionIdentity("android.permission.ADD_TRUSTED_DISPLAY")
            try {virtual=context.getSystemService(DisplayManager::class.java).createVirtualDisplay("Keyboard input chat fixture",960,640,160,images!!.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or (1 shl 10) or (1 shl 6) or (1 shl 14))}finally{automation.dropShellPermissionIdentity()}
            val lower=virtual!!.display.displayId;until("Fixture display absent"){displayFacts(context).any {it.id==lower}}
            val facts=displayFacts(context);DisplayRoles(context).save(facts.single {it.id==0},facts.single {it.id==lower})
            check(profiles.edit().putString("provider","deepseek").putString("url",DeepSeekCatalog.BASE_URL).putString("model","deepseek-flash")
                .putString("name","Offline keyboard Native component").putInt("protocol",0).putInt("revision",73001).putInt("contextBudget",1048576).putInt("maxOutput",4096).remove("probe").remove("probeRevision").commit())
            CredentialVault(context).put("model","disposable-keyboard-component-no-network")
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                val component=ComponentName(context,ThorAccessibilityService::class.java).flattenToString()
                Settings.Secure.putString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,(a11yBefore.orEmpty().split(':').filter {it.isNotBlank()}+component).distinct().joinToString(":"))
                Settings.Secure.putInt(context.contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED,1)
                Settings.Secure.putString(context.contentResolver,"immersive_mode_confirmations","confirmed")
            }finally {automation.dropShellPermissionIdentity()}
            until("Accessibility not connected"){ThorAccessibilityService.instance!=null}
            activity=launchChat(lower);var ready=EmulatorRuntimeTestSupport.awaitReady(activity!!);selected=ready.host.selectedSession();ready=EmulatorRuntimeTestSupport.reloadIdle(activity!!);host=ready.host
            connection=ProductHost::class.java.getDeclaredField("connection").apply {isAccessible=true}.get(host) as String
            launchReceiver("KeyboardFixtureActivity",0)
            until("Ordinary keyboard Activity absent"){ThorAccessibilityService.instance!!.target(0).packageName==receiverPackage}
            lease=receiver("prepare",params()).getString("lease")
            assertEquals(android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION,report().getInt("windowType"))
            val h=host!!;val c=connection!!;ok(rpc(h,c,"ui",obj("op" to "new")));val sid=h.selectedSession()
            for((cap,on) in mapOf("perception" to true,"screen_control" to true,"search" to false,"debug" to false))ok(rpc(h,c,"ui",obj("op" to "capability","sessionId" to sid,"capability" to cap,"enabled" to on)))
            run=NativeRun(ok(rpc(h,c,"begin",obj("sessionId" to sid,"text" to "独立键盘输入 Native RPC 组件验证，不调用模型。"))).getJSONObject("lease"),c)
            val r=run!!;remember("nativeRunId",r.id);remember("setupPermissionsDroppedBeforeTools",true)
            fun freshNode(kind:String):JSONObject {
                val nodes=tree(h,run!!).getJSONArray("nodes").objects()
                return if(kind=="password")nodes.single {it.optBoolean("password")}
                else nodes.single {it.optString("id").endsWith(":id/keyboard_$kind")}
            }
            fun type(node:JSONObject,text:String,mode:String="insert")=tool(h,run!!,"input.type_text",obj("nodeRef" to node.getString("nodeRef"),"text" to text,"mode" to mode))
            fun unchanged(before:JSONObject) {
                val now=report()
                for(key in listOf("normalText","passwordLength","disabledText","readOnlyText","textChangeCount","editorActionCount","submissionCount","keyEventCount"))assertEquals("Rejected operation changed $key",before.get(key),now.get(key))
            }
            ok(type(freshNode("normal"),"中🙂文","replace"))
            until("Unicode replacement did not arrive in actual EditText"){report().getString("normalText")=="中🙂文"}
            assertEquals(0,report().getInt("editorActionCount"));assertEquals(0,report().getInt("submissionCount"))
            remember("unicodeReplacementAndNoAutoSubmit",true)
            receiver("configure",params("select_emoji"));Thread.sleep(250)
            val selectedEmoji=freshNode("normal").also {val s=it.getJSONObject("textSelection");assertEquals(1,s.getInt("start"));assertEquals(3,s.getInt("end"))}
            ok(type(selectedEmoji,"测试"))
            until("Selection insert did not replace emoji selection"){report().getString("normalText")=="中测试文"}
            remember("selectionInsert",true)
            ok(type(freshNode("normal"),"","replace"));until("Empty replacement did not clear field"){report().getString("normalText").isEmpty()}
            remember("emptyReplacementClears",true)
            for(kind in listOf("password","disabled","readonly")) {
                val node=freshNode(kind);val before=report();val refused=type(node,"SHOULD_NOT_ARRIVE","replace")
                assertTrue("Unsafe editor must reject",refused.optString("error") in setOf("INVALID_ARGUMENT","SECURE_CONTENT","PERMISSION_MISSING"));unchanged(before)
            }
            remember("unsafeEditorsRejectedWithoutWrite",true)
            val movable=freshNode("normal");val beforeMove=report();receiver("configure",params("move"));Thread.sleep(350)
            val moved=type(movable,"SHOULD_NOT_ARRIVE");assertEquals("TARGET_CHANGED",moved.getString("error"));unchanged(beforeMove)
            remember("movedEditorRejectedWithoutWrite",true)
            // No private clock/ref override: exercise the real resource TTL.
            val expiring=freshNode("normal");val beforeExpiry=report();val started=SystemClock.elapsedRealtime()
            Thread.sleep(dev.thor.tools.Snapshots.OBSERVATION_TTL_MS+150)
            val expired=type(expiring,"SHOULD_NOT_ARRIVE");assertEquals("TARGET_CHANGED",expired.getString("error"));unchanged(beforeExpiry)
            remember("realExpiredRefRejectedWithoutWrite",obj("elapsedMs" to (SystemClock.elapsedRealtime()-started),"passed" to true))
            val previous=freshNode("normal");val beforeCrossRun=report();val firstRun=run!!
            ok(rpc(h,c,"end",firstRun.lease));assertFalse(h.hasActiveRuns())
            run=NativeRun(ok(rpc(h,c,"begin",obj("sessionId" to sid,"text" to "同会话第二轮独立键盘引用隔离验证，不调用模型。"))).getJSONObject("lease"),c)
            remember("secondNativeRunId",run!!.id)
            val cross=type(previous,"SHOULD_NOT_ARRIVE");assertEquals("TARGET_CHANGED",cross.getString("error"));unchanged(beforeCrossRun)
            remember("crossRunRefRejectedWithoutWrite",true);remember("textPassed",true)
            // Native key injection is never replaced with UiAutomation/shell input.
            ok(type(freshNode("normal"),"keyboard sample","replace"))
            receiver("configure",params("focus_normal"));Thread.sleep(350)
            tree(h,run!!)
            val keyAvailability=authority(h,run!!).getJSONObject("availability").getJSONObject("input.key_press")
            val beforeKeys=report();val keyResult=tool(h,run!!,"input.key_press",obj("key" to "ENTER"))
            assertEquals("Real Shizuku keyboard backend is required for key acceptance", "ready", keyAvailability.getString("status"))
            ok(keyResult)
            until("Native key submission had no independent receiver key callback"){report().getInt("keyEventCount")>=beforeKeys.getInt("keyEventCount")+2}
            remember("focusAfterEnter",obj("normalFocused" to report().getBoolean("normalFocused"),"passwordFocused" to report().getBoolean("passwordFocused"),"readOnlyFocused" to report().getBoolean("readOnlyFocused")))
            // ENTER can move focus. Use the same observation/activation path as
            // an Agent, rather than letting fixture setup refocus on its behalf.
            val normalForKeyboard=freshNode("normal")
            ok(tool(h,run!!,"input.activate",obj("nodeRef" to normalForKeyboard.getString("nodeRef"))))
            until("Official activation did not focus the real normal editor"){report().getBoolean("normalFocused")}
            assertTrue("Agent ui_tree must expose the focused editor",freshNode("normal").getBoolean("focused"))
            remember("keyboardFieldRefocusedWithOfficialTool",true)
            ok(tool(h,run!!,"input.key_press",obj("key" to "A","modifiers" to JSONArray(listOf("CTRL")))))
            until("CTRL+A did not select actual EditText contents"){val x=report();x.getInt("selectionStart")==0 && x.getInt("selectionEnd")==x.getString("normalText").length}
            val selectedAll=freshNode("normal").getJSONObject("textSelection")
            assertEquals(0,selectedAll.getInt("start"));assertEquals(report().getString("normalText").length,selectedAll.getInt("end"))
            remember("selectionReadableInAgentUiTree",true)
            ok(tool(h,run!!,"input.key_press",obj("key" to "BACKSPACE")))
            until("BACKSPACE did not clear actual selected receiver text"){report().getString("normalText").isEmpty()}
            remember("keyboardKeyBackend",obj("status" to "submitted_and_receiver_observed","keyEventDelta" to (report().getInt("keyEventCount")-beforeKeys.getInt("keyEventCount")),"ctrlASelectionObserved" to true,"backspaceClearObserved" to true))
            remember("keysPassed",true)
            val sealed=receiver("release",params()).getJSONObject("snapshot");lease=null;remember("receiver",sealed)
            val log=sealed.getJSONObject("evidence");assertFalse(log.getBoolean("incomplete"));assertEquals(0,log.getInt("droppedSamples"))
            val path=log.getString("relativePath");assertTrue(path.matches(Regex("keyboard-reports/[a-f0-9-]{36}/events\\.jsonl")))
            val raw=shell(automation,"run-as dev.thor.inputlab cat files/$path").toByteArray();assertEquals(log.getLong("eventPrefixBytes"),raw.size.toLong());assertEquals(log.getString("eventPrefixSha256"),hash(raw))
            File(context.filesDir,"emulator-keyboard-input-events.jsonl").writeBytes(raw)
            remember("passed",true)
        }catch(t:Throwable){primaryFailure=t;diagnose("primary-failure",t);evidence.put("passed",false).put("failureType",t.javaClass.simpleName);throw t}
        finally {
            var firstCleanupFailure:Throwable?=null
            fun cleanup(label:String,block:()->Unit){try{block()}catch(t:Throwable){
                diagnose("cleanup-$label",t)
                evidence.getJSONArray("cleanupErrors").put(obj("stage" to label,"error" to t.javaClass.simpleName));evidence.put("passed",false)
                if(primaryFailure!=null)primaryFailure!!.addSuppressed(t)
                else if(firstCleanupFailure==null)firstCleanupFailure=t else firstCleanupFailure!!.addSuppressed(t)
            }}
            cleanup("run"){if(host!=null && run!=null){ok(rpc(host!!,run!!.connection,"end",run!!.lease));assertFalse(host!!.hasActiveRuns())}}
            cleanup("receiver"){if(lease!=null)receiver("release",params())}
            cleanup("selection"){if(host!=null && connection!=null && selected!=null) {
                try {ok(rpc(host!!,connection!!,"ui",obj("op" to "select","sessionId" to selected)))}
                catch(t:Throwable){diagnoseView(host!!);throw t}
            }}
            cleanup("activity"){activity?.let {a->ins.runOnMainSync {a.finish()}}}
            cleanup("idle"){until("Runtime did not idle before restore"){!shell(automation,"dumpsys activity services com.tobias.loki/dev.thor.agent.AgentRuntimeService").contains("ServiceRecord{")}}
            cleanup("preferences"){if(setup){restore(profiles,profileBefore);restore(vault,vaultBefore);restore(roles,rolesBefore)}}
            cleanup("display"){virtual?.release();images?.setOnImageAvailableListener(null,null);drain?.quitSafely();drain?.join(2000);images?.close()}
            cleanup("accessibility"){if(setup){automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);try {Settings.Secure.putString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,a11yBefore);Settings.Secure.putString(context.contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED,enabledBefore);Settings.Secure.putString(context.contentResolver,"immersive_mode_confirmations",immersiveBefore)}finally {automation.dropShellPermissionIdentity()}}}
            cleanup("history"){val after=historyIds();history.forEach {(table,ids)->assertTrue(after.getValue(table).containsAll(ids))}}
            cleanup("save-report"){evidence.put("profilesRestored",profiles.all==profileBefore).put("credentialsRestored",vault.all==vaultBefore).put("rolesRestored",roles.all==rolesBefore);save()}
            if(primaryFailure==null)firstCleanupFailure?.let {throw it}
        }
    }
}
