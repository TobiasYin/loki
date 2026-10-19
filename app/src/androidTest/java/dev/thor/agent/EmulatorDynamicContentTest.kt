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

/** Explicit emulator-only Native RPC component. Synthetic offline receiver pages,
 * real display/window/accessibility/gesture/Engine path; no model or SSE execution.
 * No shell input, forged target, relaxed lower guard, or receiver verdict injection. */
@RunWith(AndroidJUnit4::class)
class EmulatorDynamicContentTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val readerDelegate=lazy {SqliteStore(context,"product.db")}
    private val reader get()=readerDelegate.value
    private val terminal=setOf("completed","failed","cancelled","interrupted")
    private val receiverPackage="dev.thor.inputlab"
    private val evidence=obj("schemaVersion" to 1,"evidenceMode" to "manual_platform_api",
        "scope" to "emulator real Native RPC dynamic-content component; ordinary Activity; no model execution",
        "cloudCalled" to false,"modelExecutionProven" to false,"systemInjectionProven" to false,
        "steps" to JSONArray(),"cleanupErrors" to JSONArray(),"passed" to false)
    @After fun closeReader(){if(readerDelegate.isInitialized())reader.close()}
    private fun save(){File(context.filesDir,"emulator-dynamic-content.json").writeText(evidence.toString(2))}
    // Private diagnostic only: stacks may include exception text; never publish this file.
    private val privateDiagnostics=JSONArray()
    private fun diagnose(stage:String,t:Throwable?=null) {
        privateDiagnostics.put(obj("stage" to stage,"type" to t?.javaClass?.name,"stack" to t?.stackTraceToString()?.take(32768)))
        runCatching {File(context.filesDir,"private-emulator-dynamic-diagnostics.json").writeText(privateDiagnostics.toString(2))}
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
        val reply=context.contentResolver.call(Uri.parse("content://dev.thor.inputlab.dynamic"),method,null,b) ?: error("Dynamic receiver missing")
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
    private fun node(tree:JSONObject,text:String)=tree.getJSONArray("nodes").objects().single {
        (it.optString("text")==text || it.optString("description")==text) && it.optBoolean("visibleToUser") && it.optBoolean("clickable") && it.optBoolean("enabled")
    }
    private fun capture(host:ProductHost,r:NativeRun,role:String,display:Int):JSONObject {
        val result=ok(tool(host,r,"perception.capture_screen",obj("maxEdge" to 960,"quality" to 80,"format" to "jpeg")));val data=details(result)
        assertEquals(role,data.getString("displayRole"));assertEquals(display,data.getInt("displayId"));assertTrue(data.has("frameId"))
        assertTrue("Cross-screen frame test needs an originally input-usable real frame",data.getBoolean("frameInputUsable"))
        val content=result.getJSONArray("content").objects().single {it.getString("type")=="attachment"}
        val attachment=ok(rpc(host,r.connection,"attachment.get",JSONObject(r.lease.toString()).put("id",content.getString("attachmentId"))))
        val bytes=android.util.Base64.decode(attachment.getString("data"),android.util.Base64.DEFAULT);assertTrue(bytes.isNotEmpty())
        File(context.filesDir,"emulator-dynamic-content-$role.jpg").writeBytes(bytes)
        evidence.put("${role}Image",obj("bytes" to bytes.size,"sha256" to hash(bytes),"displayId" to display));save()
        return data
    }

    @Test fun changingPixelsKeepStableTargetsUsableButRealWindowsAndNodeGeometryStillReject() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("dynamicContentAcceptance")=="true")
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
        var lease:String?=null;val testRun="dynamic-${id()}";var setup=false;var primaryFailure:Throwable?=null
        fun params(change:String?=null)=Bundle().apply {putInt("version",1);putString("testRunId",testRun);putString("lease",lease);if(change!=null)putString("change",change)}
        fun report()=receiver("report",params()).getJSONObject("snapshot")
        fun waitTicks(start:Long) {until("100ms ticker did not advance at least ten ticks"){report().getLong("ticks")>=start+10}}
        fun remember(name:String,value:Any?) {evidence.put(name,value);save()}
        try {
            setup=true
            drain=HandlerThread("dynamic-display-drain").apply {start()};images=ImageReader.newInstance(960,640,PixelFormat.RGBA_8888,2)
            images!!.setOnImageAvailableListener({r->r.acquireLatestImage()?.close()},Handler(drain!!.looper))
            automation.adoptShellPermissionIdentity("android.permission.ADD_TRUSTED_DISPLAY")
            try {virtual=context.getSystemService(DisplayManager::class.java).createVirtualDisplay("Dynamic content chat fixture",960,640,160,images!!.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or (1 shl 10) or (1 shl 6) or (1 shl 14))}finally{automation.dropShellPermissionIdentity()}
            val lower=virtual!!.display.displayId;until("Fixture display absent"){displayFacts(context).any {it.id==lower}}
            val facts=displayFacts(context);DisplayRoles(context).save(facts.single {it.id==0},facts.single {it.id==lower})
            check(profiles.edit().putString("provider","deepseek").putString("url",DeepSeekCatalog.BASE_URL).putString("model","deepseek-flash")
                .putString("name","Offline dynamic Native component").putInt("protocol",0).putInt("revision",73001).putInt("contextBudget",1048576).putInt("maxOutput",4096).remove("probe").remove("probeRevision").commit())
            CredentialVault(context).put("model","disposable-dynamic-component-no-network")
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
            launchReceiver("DynamicContentFixtureActivity",0)
            until("Ordinary dynamic Activity absent"){ThorAccessibilityService.instance!!.target(0).packageName==receiverPackage}
            lease=receiver("prepare",params()).getString("lease")
            until("Receiver has zero-size geometry"){report().getJSONArray("buttonBounds").getInt(2)>0}
            assertEquals(android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION,report().getInt("windowType"))
            val h=host!!;val c=connection!!;ok(rpc(h,c,"ui",obj("op" to "new")));val sid=h.selectedSession()
            for((cap,on) in mapOf("perception" to true,"screen_control" to true,"search" to false,"debug" to false))ok(rpc(h,c,"ui",obj("op" to "capability","sessionId" to sid,"capability" to cap,"enabled" to on)))
            run=NativeRun(ok(rpc(h,c,"begin",obj("sessionId" to sid,"text" to "独立动态窗口 Native RPC 组件验证，不调用模型。"))).getJSONObject("lease"),c)
            val r=run!!;remember("nativeRunId",r.id);remember("setupPermissionsDroppedBeforeTools",true)
            fun captureFrame()=capture(h,r,"upper",0)
            fun center()=report().getJSONArray("buttonBounds").let {listOf((it.getDouble(0)+it.getDouble(2))/2,(it.getDouble(1)+it.getDouble(3))/2)}
            fun logicalTap()=center().let {obj("x" to it[0],"y" to it[1],"coordinateSpace" to "display_logical","expectedPackage" to receiverPackage)}
            captureFrame();val image1=evidence.getJSONObject("upperImage").getString("sha256");File(context.filesDir,"emulator-dynamic-content-upper.jpg").copyTo(File(context.filesDir,"emulator-dynamic-before.jpg"),true);val first=report();waitTicks(first.getLong("ticks"))
            val frame=captureFrame();val image2=evidence.getJSONObject("upperImage").getString("sha256");assertNotEquals("Dynamic screenshots must actually differ",image1,image2);File(context.filesDir,"emulator-dynamic-content-upper.jpg").copyTo(File(context.filesDir,"emulator-dynamic-after.jpg"),true);remember("dynamicImageComparison",obj("beforeSha256" to image1,"afterSha256" to image2,"ticksAdvanced" to (report().getLong("ticks")-first.getLong("ticks"))))
            val old=report();val point=center();val transform=frame.getJSONObject("transform")
            waitTicks(old.getLong("ticks"));assertEquals(old.getJSONArray("buttonBounds").toString(),report().getJSONArray("buttonBounds").toString())
            val frameTap=obj("coordinateSpace" to "frame_pixels","frameId" to frame.getString("frameId"),"expectedPackage" to receiverPackage,
                "x" to ((point[0]-transform.getDouble("left"))*transform.getDouble("imageWidth")/transform.getDouble("displayWidth")),
                "y" to ((point[1]-transform.getDouble("top"))*transform.getDouble("imageHeight")/transform.getDouble("displayHeight")))
            ok(tool(h,r,"input.tap",frameTap));until("Old frame tap did not reach receiver"){report().getInt("clickCount")==1}
            remember("dynamicOldFramePositive",obj("ticksAdvanced" to (report().getLong("ticks")-old.getLong("ticks")),"imageHashesDiffer" to true,"geometryUnchanged" to true,"clickCount" to 1))
            captureFrame();val logical=logicalTap();waitTicks(report().getLong("ticks"));ok(tool(h,r,"input.tap",logical));until("Old logical point did not click"){report().getInt("clickCount")==2}
            val stable=node(tree(h,r),"Dynamic target");waitTicks(report().getLong("ticks"));ok(tool(h,r,"input.activate",obj("nodeRef" to stable.getString("nodeRef"))));until("Stable node ref not activated"){report().getInt("clickCount")==3}
            val movable=node(tree(h,r),"Dynamic target");receiver("configure",params("move"));Thread.sleep(350)
            val moved=tool(h,r,"input.activate",obj("nodeRef" to movable.getString("nodeRef")));assertEquals("TARGET_CHANGED",moved.getString("error"));assertEquals(3,report().getInt("clickCount"))
            remember("movedNodeRejected",true)
            val beforeDialog=captureFrame();val counts=report();receiver("configure",params("dialog"));until("Real modal not visible"){report().getBoolean("dialogShowing")};Thread.sleep(350)
            val blocked=tool(h,r,"input.tap",obj("frameId" to beforeDialog.getString("frameId"),"coordinateSpace" to "frame_pixels","x" to beforeDialog.getDouble("width")/2,"y" to beforeDialog.getDouble("height")/2))
            assertEquals("TARGET_CHANGED",blocked.getString("error"));assertEquals("observed",blocked.getString("outcome"));assertEquals(counts.getInt("touchCount"),report().getInt("touchCount"));assertEquals(3,report().getInt("clickCount"))
            remember("realDialogRejectedWithoutDispatch",true);receiver("configure",params("dismiss"));Thread.sleep(350)
            val beforeScroll=captureFrame();val scrollSetup=report()
            remember("scrollFixtureGeometry",obj("viewportHeight" to scrollSetup.getInt("viewportHeight"),"contentHeight" to scrollSetup.getInt("contentHeight"),"scrollRange" to scrollSetup.getInt("scrollRange"),"canScrollDown" to scrollSetup.getBoolean("canScrollDown")))
            assertTrue("Fixture must have actual content beyond its viewport",scrollSetup.getInt("scrollRange")>0)
            assertTrue("Fixture must be able to scroll down before the Native swipe",scrollSetup.getBoolean("canScrollDown"))
            val bounds=scrollSetup.getJSONArray("scrollBounds");val x=(bounds.getDouble(0)+bounds.getDouble(2))/2
            val top=bounds.getDouble(1);val height=bounds.getDouble(3)-top
            ok(tool(h,r,"input.swipe",obj("coordinateSpace" to "display_logical","points" to JSONArray(listOf(listOf(x,top+height*.8),listOf(x,top+height*.3))),"durationMs" to 500)))
            until("Committed swipe did not actually scroll receiver"){report().getInt("scrollY")>0};Thread.sleep(500)
            val postScroll=report();val stale=tool(h,r,"input.tap",obj("coordinateSpace" to "frame_pixels","frameId" to beforeScroll.getString("frameId"),"x" to beforeScroll.getDouble("width")/2,"y" to beforeScroll.getDouble("height")/2))
            assertEquals("TARGET_CHANGED",stale.getString("error"));assertEquals("observed",stale.getString("outcome"));assertEquals(postScroll.getInt("touchCount"),report().getInt("touchCount"))
            remember("committedSwipeSurvivedFeedback",true);remember("oldFrameAfterScrollRejectedWithoutDispatch",true)
            val sealed=receiver("release",params()).getJSONObject("snapshot");lease=null;remember("receiver",sealed)
            val log=sealed.getJSONObject("evidence");assertFalse(log.getBoolean("incomplete"));assertEquals(0,log.getInt("droppedSamples"))
            val path=log.getString("relativePath");assertTrue(path.matches(Regex("dynamic-reports/[a-f0-9-]{36}/events\\.jsonl")))
            val raw=shell(automation,"run-as dev.thor.inputlab cat files/$path").toByteArray();assertEquals(log.getLong("eventPrefixBytes"),raw.size.toLong());assertEquals(log.getString("eventPrefixSha256"),hash(raw))
            File(context.filesDir,"emulator-dynamic-content-events.jsonl").writeBytes(raw)
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
