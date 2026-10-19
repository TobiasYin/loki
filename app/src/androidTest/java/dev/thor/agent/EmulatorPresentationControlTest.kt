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

/** Explicit emulator-only Native RPC component. Independent synthetic offline Presentation UI,
 * real display/window/accessibility/gesture/Engine path; no model or SSE execution.
 * No shell input, forged target, relaxed lower guard, or receiver verdict injection. */
@RunWith(AndroidJUnit4::class)
class EmulatorPresentationControlTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val readerDelegate=lazy {SqliteStore(context,"product.db")}
    private val reader get()=readerDelegate.value
    private val terminal=setOf("completed","failed","cancelled","interrupted")
    private val receiverPackage="dev.thor.inputlab"
    private val evidence=obj("schemaVersion" to 1,"evidenceMode" to "manual_platform_api",
        "scope" to "emulator real Native component with independent WM2037 Presentation; no model execution",
        "cloudCalled" to false,"modelExecutionProven" to false,"systemInjectionProven" to false,
        "steps" to JSONArray(),"cleanupErrors" to JSONArray(),"passed" to false)
    @After fun closeReader(){if(readerDelegate.isInitialized())reader.close()}
    private fun save(){
        // Passive local cache diagnostics: never warms metadata or changes target state.
        ThorAccessibilityService.instance?.let {service->evidence.put("presentationProofTrace",JSONArray(service.presentationDiagnostics().map {JSONObject(it)}))}
        File(context.filesDir,"emulator-presentation.json").writeText(evidence.toString(2))
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
    private fun launchReceiver(activity:String,display:Int) {
        context.startActivity(Intent().setComponent(ComponentName(receiverPackage,"$receiverPackage.$activity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
    }
    private fun launchChat(display:Int)=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()) as MainActivity
    private var diagnosticDebug:dev.thor.tools.diagnostic.DebugConnection?=null
    private fun diagnosePresentation(display:Int,phase:String) {
        val debug=diagnosticDebug ?: return
        val entry=obj("phase" to phase,"displayId" to display,"backend" to debug.availability().status.name)
        val start=SystemClock.elapsedRealtime()
        try {
            val ids=ThorAccessibilityService.instance!!.windowsOnAllDisplays[display].orEmpty().filter {it.type == -1}.map {it.id}.toIntArray()
            entry.put("unknownWindowCount",ids.size)
            @Suppress("UNCHECKED_CAST") val read=dev.thor.tools.diagnostic.DebugConnection::class.java.getDeclaredField("presentationReader").apply {isAccessible=true}.get(debug) as (Int,IntArray)->List<dev.thor.tools.PresentationWindowProof>
            if(ids.isNotEmpty())entry.put("directProductionProofCount",read(display,ids).size)
        }catch(t:Throwable){entry.put("errorType",t.javaClass.simpleName).put("message",t.message?.take(180))}
        entry.put("elapsedMs",SystemClock.elapsedRealtime()-start)
        val list=evidence.optJSONArray("presentationDiagnostics") ?: JSONArray().also {evidence.put("presentationDiagnostics",it)}
        list.put(entry);save() // Counts/errors only; never raw dumps, tokens or proof injection.
    }
    private fun waitTarget(display:Int) {
        var previous:String?=null;var since=SystemClock.elapsedRealtime()
        try {until("Independent receiver target not stable on display $display") {
            ThorAccessibilityService.instance?.preparePresentationEvidence(setOf(display))
            val target=ThorAccessibilityService.instance?.target(display)
            val good=target?.validity=="valid" && target.packageName==receiverPackage && target.touchableBounds?.let {it.left<it.right && it.top<it.bottom}==true
            val signature=if(good)target!!.layoutRevision else null
            val trace=evidence.optJSONArray("targetWaitDiagnostics") ?: JSONArray().also {evidence.put("targetWaitDiagnostics",it)}
            val state=obj("displayId" to display,"validity" to target?.validity,"package" to target?.packageName,"layoutHash" to target?.layoutRevision?.let {hash(it.toByteArray())},"revisionHash" to target?.revision?.let {hash(it.toByteArray())})
            if(trace.length()<24 && (trace.length()==0 || trace.getJSONObject(trace.length()-1).toString()!=state.toString())){trace.put(state);save()}
            if(signature==null || signature!=previous){previous=signature;since=SystemClock.elapsedRealtime()}
            good && SystemClock.elapsedRealtime()-since>=600
        }}catch(t:Throwable){diagnosePresentation(display,"wait-timeout");throw t}
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
        evidence.getJSONArray("steps").put(obj("runId" to r.id,"toolId" to name,"arguments" to args,"elapsedMs" to (SystemClock.elapsedRealtime()-start),"error" to result.opt("error"),"outcome" to result.opt("outcome"),"data" to details(result)))
        save();return result
    }
    /** A factual Presentation proof has an absolute TTL. Changing screens after
     * several guard checks must observe it again; never reuse expired input. */
    private fun selectObservedLower(host:ProductHost,r:NativeRun,display:Int) {
        for(attempt in 0..2) {
            waitTarget(display)
            val selected=tool(host,r,"perception.select_screen",obj("screen" to "lower"))
            if(selected.isNull("error"))return
            assertTrue("Only an unsubmitted observation refusal may be re-observed",selected.optString("error") in setOf("TARGET_CHANGED","PERMISSION_MISSING") && selected.optString("outcome") in setOf("observed","not_submitted"))
            val retries=evidence.optJSONArray("lowerSelectionReobservations") ?: JSONArray().also {evidence.put("lowerSelectionReobservations",it)}
            retries.put(obj("attempt" to attempt,"error" to selected.getString("error")));save()
            diagnosePresentation(display,"selection-refused")
            if(attempt==2)ok(selected)
        }
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
        File(context.filesDir,"emulator-presentation-$role.jpg").writeBytes(bytes)
        evidence.put("${role}Image",obj("bytes" to bytes.size,"sha256" to hash(bytes),"displayId" to display));save()
        return data
    }

    private fun presentation(method:String,test:String,lease:String?=null,secure:Boolean?=null):JSONObject {
        val b=Bundle().apply {putInt("version",1);putString("testRunId",test);if(lease!=null)putString("lease",lease);if(secure!=null)putBoolean("enabled",secure)}
        val result=context.contentResolver.call(Uri.parse("content://dev.thor.inputlab.presentation"),method,null,b) ?: error("Debug Presentation receiver provider missing")
        return obj("lease" to result.getString("lease"),"snapshot" to JSONObject(result.getString("snapshot")!!))
    }
    private fun protectedPresentationVisible(automation:UiAutomation,display:Int):Boolean {
        val dump=shell(automation,"dumpsys window windows")
        val blocks=Regex("(?m)^  Window #\\d+ Window\\{").split(dump).drop(1)
        val ours=blocks.filter {block->
            Regex("(?m)^    mDisplayId=$display ").containsMatchIn(block) &&
                Regex("(?m)^    mOwnerUid=\\d+ showForAllUsers=(?:true|false) package=dev\\.thor\\.inputlab appop=").containsMatchIn(block) &&
                Regex("\\bty=PRESENTATION\\b").containsMatchIn(block.substringAfter("    mAttrs=","").substringBefore("    Requested ",""))
        }
        val block=ours.singleOrNull() ?: return false
        return Regex("(?m)^      fl=[^\\r\\n]*\\bSECURE\\b").containsMatchIn(block.substringAfter("    mAttrs=","").substringBefore("    Requested ",""))
    }
    @Test fun realForeignPresentationScreenshotTreeActionsAndGuards() {
        org.junit.Assume.assumeTrue("Explicit emulator acceptance only",InstrumentationRegistry.getArguments().getString("emulatorAcceptance")=="true")
        assertTrue("Never operate Thor or another physical device",Build.HARDWARE in setOf("ranchu","goldfish"))
        val profiles=context.getSharedPreferences("profiles",0);val vault=context.getSharedPreferences("native-credentials",0);val roles=context.getSharedPreferences("display-roles",0)
        assertFalse("Do not overwrite any existing model credential; use a disposable emulator",vault.contains("model"))
        assertTrue("Do not interrupt existing user work",rows("runs").all {it.optString("status") in terminal})
        val before=historyIds();val profileBefore=profiles.all.toMap();val vaultBefore=vault.all.toMap();val rolesBefore=roles.all.toMap()
        val overlayBefore=Settings.Global.getString(context.contentResolver,"overlay_display_devices")
        val immersiveBefore=Settings.Secure.getString(context.contentResolver,"immersive_mode_confirmations")
        val a11yBefore=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val a11yEnabledBefore=Settings.Secure.getString(context.contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED)
        val automation=ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertFalse("Do not replace an existing Input Lab task",shell(automation,"dumpsys activity activities").contains("$receiverPackage/"))
        val initialDisplays=displayFacts(context);val upper=initialDisplays.single {it.id==0}
        val debugWorkers=mutableSetOf<java.util.concurrent.ExecutorService>()
        val active=mutableListOf<NativeRun>();var activity:MainActivity?=null;var host:ProductHost?=null;var restoreSession:String?=null
        var virtual:VirtualDisplay?=null;var images:ImageReader?=null;var drainThread:HandlerThread?=null
        var presentationLease:String?=null;val testRun="presentation-${id()}"
        evidence.put("receiverTestRunId",testRun)
        var receiverStarted=false;var preferencesChanged=false;var accessibilityChanged=false;var immersiveChanged=false
        try {
            evidence.put("phase","create-public-virtual-display").put("sourceReferences",JSONArray(listOf("https://developer.android.com/reference/android/hardware/display/DisplayManager", "https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/display/OverlayDisplayAdapter.java")));save()
            drainThread=HandlerThread("dual-screen-test-display-drain").apply {start()}
            images=ImageReader.newInstance(960,640,PixelFormat.RGBA_8888,3).apply {setOnImageAvailableListener({reader->runCatching {reader.acquireLatestImage()?.close()}},Handler(drainThread!!.looper))}
            // AOSP android16-release DisplayManager: TRUSTED=1<<10,
            // SUPPORTS_TOUCH=1<<6, OWN_FOCUS=1<<14. These Test/System API flags
            // configure the real fixture display, never the production tool path.
            // DMS requires only ADD_TRUSTED_DISPLAY; drop before launch/reading/input.
            val fixtureFlags=DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or (1 shl 10) or (1 shl 6) or (1 shl 14)
            automation.adoptShellPermissionIdentity("android.permission.ADD_TRUSTED_DISPLAY")
            try {
                virtual=context.getSystemService(DisplayManager::class.java).createVirtualDisplay("Thor component lower ${id()}",960,640,160,images!!.surface,fixtureFlags)
                    ?: error("Trusted touch VirtualDisplay unavailable; no overlay or guard-bypass fallback")
            }finally {automation.dropShellPermissionIdentity()}
            evidence.put("fixtureSetupPermissions",JSONArray(listOf("android.permission.ADD_TRUSTED_DISPLAY"))).put("fixturePermissionDroppedBeforeTools",true)
            val lower=virtual!!.display.displayId;assertNotEquals(upper.id,lower)
            until("Public VirtualDisplay not enumerated"){displayFacts(context).any {it.id==lower}}
            assertEquals(overlayBefore,Settings.Global.getString(context.contentResolver,"overlay_display_devices"))
            evidence.put("upperDisplay",upper.id).put("lowerDisplay",lower).put("virtualDisplayFlags","PUBLIC|OWN_CONTENT_ONLY|PRESENTATION|TRUSTED|SUPPORTS_TOUCH|OWN_FOCUS").put("surfaceConsumerPixelsUsedAsEvidence",false)
            // Explicit fixture setup only. Production selection never changes calibration.
            val facts=displayFacts(context);DisplayRoles(context).save(facts.single {it.id==upper.id},facts.single {it.id==lower});preferencesChanged=true
            check(profiles.edit().putString("provider","deepseek").putString("url",DeepSeekCatalog.BASE_URL).putString("model","deepseek-flash")
                .putString("name","Disposable Native component preset").putInt("protocol",0).putInt("revision",72001)
                .putInt("contextBudget",1048576).putInt("maxOutput",4096).remove("probe").remove("probeRevision").commit())
            CredentialVault(context).put("model","disposable-emulator-component-never-used-for-network")
            val serviceName=ComponentName(context,ThorAccessibilityService::class.java).flattenToString()
            if(a11yEnabledBefore!="1" || a11yBefore.orEmpty().split(':').none {it==serviceName}) {
                accessibilityChanged=true
                automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                try {Settings.Secure.putString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,(a11yBefore.orEmpty().split(':').filter {it.isNotBlank()}+serviceName).joinToString(":"));Settings.Secure.putInt(context.contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED,1)}finally {automation.dropShellPermissionIdentity()}
            }
            // Same reversible fixture initialization used by Android CTS window
            // tests. Suppress only the first-use navigation lesson; no overlay is
            // ignored and Native target clipping remains unchanged.
            immersiveChanged=true
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {check(Settings.Secure.putString(context.contentResolver,"immersive_mode_confirmations","confirmed"))}finally {automation.dropShellPermissionIdentity()}
            evidence.put("immersiveLessonConfirmedForFixture",true)
            until("Existing production Accessibility service did not bind"){ThorAccessibilityService.instance!=null}
            activity=launchChat(upper.id);var ready=EmulatorRuntimeTestSupport.awaitReady(activity!!);restoreSession=ready.host.selectedSession()
            assertFalse(ready.host.hasActiveRuns());ready=EmulatorRuntimeTestSupport.reloadIdle(activity!!);host=ready.host
            val initialDebug=ProductHost::class.java.getDeclaredField("debug").apply {isAccessible=true}.get(host) as dev.thor.tools.diagnostic.DebugConnection
            until("Production Shizuku shell UID2000 service must be running and granted; no injected metadata fallback",15){initialDebug.availability().status==dev.thor.tools.Status.READY}
            debugWorkers.add(dev.thor.tools.diagnostic.DebugConnection::class.java.getDeclaredField("worker").apply {isAccessible=true}.get(initialDebug) as java.util.concurrent.ExecutorService)
            evidence.put("productionShizukuBound",true)
            ins.runOnMainSync {activity!!.finish()};until("Setup chat Activity not destroyed"){activity!!.isDestroyed}
            receiverStarted=true;launchReceiver("LabActivity",upper.id);launchReceiver("PresentationFixtureActivity",lower)
            waitTarget(upper.id);waitTarget(lower)
            activity=launchChat(lower);ready=EmulatorRuntimeTestSupport.awaitReady(activity!!);host=ready.host
            val currentDebug=ProductHost::class.java.getDeclaredField("debug").apply {isAccessible=true}.get(host) as dev.thor.tools.diagnostic.DebugConnection
            debugWorkers.add(dev.thor.tools.diagnostic.DebugConnection::class.java.getDeclaredField("worker").apply {isAccessible=true}.get(currentDebug) as java.util.concurrent.ExecutorService)
            diagnosticDebug=currentDebug
            assertEquals(lower,activity!!.display!!.displayId)
            val connection=ProductHost::class.java.getDeclaredField("connection").apply {isAccessible=true}.get(host) as String
            val previous=host!!.selectedSession();ok(rpc(host!!,connection,"ui",obj("op" to "new")));until("New fixture session did not select"){host!!.selectedSession()!=previous}
            val sid=host!!.selectedSession()
            for((cap,on) in mapOf("perception" to true,"screen_control" to true,"search" to false,"debug" to false))ok(rpc(host!!,connection,"ui",obj("op" to "capability","sessionId" to sid,"capability" to cap,"enabled" to on)))
            val admitted=ok(rpc(host!!,connection,"begin",obj("sessionId" to sid,"text" to "本地 Presentation Native 组件验证，不调用模型。")))
            val run=NativeRun(admitted.getJSONObject("lease"),connection).also {active.add(it)}
            evidence.put("runId",run.id).put("phase","native-presentation");save()
            val moved=details(ok(tool(host!!,run,"agent.background")));assertTrue(moved.getBoolean("movedToBackground"))
            val prepared=presentation("prepare",testRun);presentationLease=prepared.getString("lease")
            evidence.put("receiverAttemptId",prepared.getJSONObject("snapshot").getString("attemptId"));save()
            assertEquals(2037,prepared.getJSONObject("snapshot").getInt("actualWindowType"));assertEquals(lower,prepared.getJSONObject("snapshot").getInt("displayId"))
            waitTarget(lower)
            selectObservedLower(host!!,run,lower)
            val windows=details(ok(tool(host!!,run,"perception.window_info"))).getJSONArray("windows").objects()
            assertEquals("Must exercise the real unmapped Presentation type, not an Activity",-1,windows.single {it.getInt("type")==-1}.getInt("type"))
            evidence.put("realAccessibilityWindowType",-1).put("realWindowManagerType",2037)
            val frame=capture(host!!,run,"lower",lower)
            File(context.filesDir,"emulator-presentation-lower-before.jpg").writeBytes(File(context.filesDir,"emulator-presentation-lower.jpg").readBytes())
            evidence.put("beforeSecureImage",JSONObject(evidence.getJSONObject("lowerImage").toString()));save()
            val firstTree=tree(host!!,run);val semantic=node(firstTree,"Presentation 语义计数")
            ok(tool(host!!,run,"input.activate",obj("nodeRef" to semantic.getString("nodeRef"))))
            until("Independent Presentation did not receive semantic ACTION_CLICK"){presentation("report",testRun,presentationLease).getJSONObject("snapshot").getInt("semanticCount")==1}
            var touch=JSONObject();var lowerSource="";var tapped=false;var coordinateReobservations=0
            for(attempt in 0..2) {
                waitTarget(lower) // Readiness needs layout; coordinate freshness is enforced by the next fresh tree and Native guard.
                val secondTree=tree(host!!,run);touch=node(secondTree,"Presentation 坐标计数");val bounds=touch.getJSONArray("bounds")
                lowerSource=authority(host!!,run).getJSONObject("l0").getString("snapshotId")
                val action=tool(host!!,run,"input.tap",obj("x" to ((bounds.getInt(0)+bounds.getInt(2))/2.0),"y" to ((bounds.getInt(1)+bounds.getInt(3))/2.0),"coordinateSpace" to "display_logical","expectedPackage" to receiverPackage))
                if(action.isNull("error")){tapped=true;break}
                assertEquals("Only a pre-submission stale observation may be retried","TARGET_CHANGED",action.getString("error"))
                assertTrue("Unknown/submitted actions must never be repeated",action.optString("outcome") in setOf("not_submitted","observed"))
                assertEquals("Never repeat a potentially completed coordinate input",0,presentation("report",testRun,presentationLease).getJSONObject("snapshot").getInt("touchCount"))
                coordinateReobservations++ // New tree/coordinates next iteration; the rejected snapshot is never reused.
            }
            assertTrue("Fresh coordinate observation must reach the independent receiver",tapped)
            evidence.put("coordinateTargetReobservations",coordinateReobservations)
            until("Independent Presentation did not receive coordinate tap"){presentation("report",testRun,presentationLease).getJSONObject("snapshot").getInt("touchCount")==1}
            ok(tool(host!!,run,"perception.select_screen",obj("screen" to "upper")))
            val errorBeforeStalePrepare=rpc(host!!,connection,"view",obj()).opt("error") ?: JSONObject.NULL
            assertEquals("TARGET_CHANGED",tool(host!!,run,"input.tap",obj("x" to 1,"y" to 1,"coordinateSpace" to "display_logical"),lowerSource).getString("error"))
            val errorAfterStalePrepare=rpc(host!!,connection,"view",obj()).opt("error") ?: JSONObject.NULL
            assertEquals("Recoverable stale tool.prepare must preserve the existing global page error",errorBeforeStalePrepare,errorAfterStalePrepare)
            evidence.put("stalePreparePreservedGlobalError",true)
            assertEquals("TARGET_CHANGED",tool(host!!,run,"input.activate",obj("nodeRef" to touch.getString("nodeRef"))).getString("error"))
            assertEquals("TARGET_CHANGED",tool(host!!,run,"input.tap",obj("x" to 1,"y" to 1,"coordinateSpace" to "frame_pixels","frameId" to frame.getString("frameId"))).getString("error"))
            selectObservedLower(host!!,run,lower)
            val protected=presentation("secure",testRun,presentationLease,true).getJSONObject("snapshot")
            assertTrue(protected.getBoolean("secure"));assertTrue(protected.getBoolean("actualSecureFlag"))
            until("Actual WindowManager Presentation FLAG_SECURE did not become visible",5){protectedPresentationVisible(automation,lower)}
            var unobservableSince=0L;var unobservableChecks=0
            until("SECURE Presentation admission did not remain unobservable",15) {
                ThorAccessibilityService.instance!!.preparePresentationEvidence(setOf(lower))
                val target=ThorAccessibilityService.instance!!.target(lower)
                val unavailable=target.validity!="valid" || target.packageName!=receiverPackage
                if(!unavailable){unobservableSince=0L;unobservableChecks=0}
                else {if(unobservableSince==0L)unobservableSince=SystemClock.elapsedRealtime();unobservableChecks++}
                unavailable && unobservableChecks>=3 && SystemClock.elapsedRealtime()-unobservableSince>=500
            }
            assertEquals(dev.thor.tools.Status.READY,currentDebug.availability().status)
            assertTrue("Protected WM facts must remain present, not a one-frame flag transition",protectedPresentationVisible(automation,lower))
            val blocked=tool(host!!,run,"perception.capture_screen",obj("maxEdge" to 960,"quality" to 80,"format" to "jpeg"))
            assertFalse("Protected fixture pixels must never be returned",blocked.optJSONArray("content")?.objects()?.any {it.optString("type")=="attachment"}==true)
            assertTrue("Protected unobservable target must fail closed",blocked.optString("error") in setOf("SECURE_CONTENT","PERMISSION_MISSING","TARGET_CHANGED"))
            evidence.put("actualWindowManagerSecureFlagConfirmed",true).put("secureUnobservableStableMs",SystemClock.elapsedRealtime()-unobservableSince)
                .put("secureUnobservableChecks",unobservableChecks).put("secureRefusalCode",blocked.getString("error")).put("securePixelsWithheld",true)
                .put("secureGuardLayer","presentation-classification-or-observation-admission")
                .put("screenshotSecureCallbackProven",false) // Admission rejects before the screenshot API may be invoked.
            presentation("secure",testRun,presentationLease,false)
            waitTarget(lower)
            selectObservedLower(host!!,run,lower)
            capture(host!!,run,"lower",lower)
            evidence.put("afterSecureImage",JSONObject(evidence.getJSONObject("lowerImage").toString())).put("secureABACaptureRestored",true);save()
            val sealed=presentation("release",testRun,presentationLease).getJSONObject("snapshot");presentationLease=null
            assertEquals(1,sealed.getInt("semanticCount"));assertEquals(1,sealed.getInt("touchCount"));assertTrue(sealed.getBoolean("sealed"))
            assertEquals("manual_platform_api",sealed.getString("evidenceMode"));assertFalse(sealed.getBoolean("agentAnswerVerified"));assertFalse(sealed.getBoolean("systemInjectionProven"))
            val log=sealed.getJSONObject("evidence");assertFalse(log.getBoolean("incomplete"));assertEquals(0L,log.getLong("droppedSamples"))
            val path=log.getString("relativePath");assertTrue(path.matches(Regex("presentation-reports/[a-f0-9-]{36}/events\\.jsonl")))
            val raw=shell(automation,"run-as dev.thor.inputlab cat files/$path").toByteArray(Charsets.UTF_8)
            assertEquals(log.getLong("eventPrefixBytes"),raw.size.toLong());assertEquals(log.getString("eventPrefixSha256"),hash(raw))
            val events=raw.toString(Charsets.UTF_8).lineSequence().filter {it.isNotBlank()}.map {JSONObject(it)}.toList()
            val touches=events.filter {it.getString("recordOrigin")=="android_dispatch" && it.getString("eventKind")=="touch"}
            assertEquals(1,touches.count {it.getJSONObject("data").getInt("actionMasked")==0});assertEquals(1,touches.count {it.getJSONObject("data").getInt("actionMasked")==1})
            assertTrue(touches.all {it.getJSONObject("data").getInt("displayId")==lower})
            assertTrue(touches.all {it.getJSONObject("data").isNull("motionDisplayId") && it.getJSONObject("data").getString("motionDisplayIdReason")=="not_exposed_by_public_InputEvent_API"})
            evidence.put("motionEventDisplayIdVerified",false).put("receiverWindowDisplayIdVerified",true)
            val down=touches.single {it.getJSONObject("data").getInt("actionMasked")==0}.getJSONObject("data")
            val up=touches.single {it.getJSONObject("data").getInt("actionMasked")==1}.getJSONObject("data");assertEquals(down.getLong("downTime"),up.getLong("downTime"))
            val clicks=events.filter {it.getString("eventKind")=="click"};assertEquals(2,clicks.size)
            assertEquals(setOf("presentation_semantic","presentation_touch"),clicks.map {it.getJSONObject("data").getString("viewId")}.toSet())
            assertTrue(clicks.all {it.getJSONObject("data").getInt("displayId")==lower})
            File(context.filesDir,"emulator-presentation-receiver-events.jsonl").writeBytes(raw)
            evidence.put("receiver",sealed).put("receiverRawSha256",hash(raw)).put("semanticReceived",true).put("coordinateDownUpReceived",true)
                .put("oldCrossScreenSnapshotRejected",true).put("oldCrossScreenNodeRejected",true).put("oldCrossScreenFrameRejected",true).put("passed",true)
        } catch(t:Throwable){evidence.put("failureType",t.javaClass.simpleName).put("failure",t.message);throw t}
        finally {
            fun cleanup(name:String,block:()->Unit){try {block()}catch(t:Throwable){evidence.getJSONArray("cleanupErrors").put(obj("step" to name,"error" to t.javaClass.simpleName,"message" to t.message));evidence.put("passed",false)}}
            cleanup("end-own-runs"){host?.let {h->active.reversed().forEach {ok(rpc(h,it.connection,"end",it.lease))};assertFalse(h.hasActiveRuns());val ours=active.map {it.id}.toSet();assertTrue("Manual components must never claim model completion",rows("runs").filter {it.getString("id") in ours}.all {it.getString("status")=="failed"})}}
            cleanup("release-presentation-receiver"){presentationLease?.let {evidence.put("receiverFinally",presentation("release",testRun,it).getJSONObject("snapshot"))}}
            cleanup("finish-test-chat"){activity?.takeUnless {it.isDestroyed}?.let {a->ins.runOnMainSync {a.finish()};until("Test Activity did not finish"){a.isDestroyed}}}
            cleanup("stop-test-receiver"){if(receiverStarted)shell(automation,"am force-stop dev.thor.inputlab")}
            cleanup("release-virtual-display"){virtual?.release();until("Temporary VirtualDisplay leaked"){displayFacts(context).map {it.id}.toSet()==initialDisplays.map {it.id}.toSet()}}
            cleanup("close-display-surface"){images?.setOnImageAvailableListener(null,null);drainThread?.quitSafely();drainThread?.join(2000);images?.close()}
            cleanup("restore-preferences"){if(preferencesChanged){restore(profiles,profileBefore);restore(vault,vaultBefore);restore(roles,rolesBefore)}}
            cleanup("restore-immersive-lesson"){if(immersiveChanged){automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);try {check(Settings.Secure.putString(context.contentResolver,"immersive_mode_confirmations",immersiveBefore))}finally {automation.dropShellPermissionIdentity()}}}
            cleanup("reload-idle-engine-with-original-state"){if(preferencesChanged){val restored=launchChat(upper.id);try {
                val ready=EmulatorRuntimeTestSupport.reloadIdle(restored)
                val restoredDebug=ProductHost::class.java.getDeclaredField("debug").apply {isAccessible=true}.get(ready.host) as dev.thor.tools.diagnostic.DebugConnection
                debugWorkers.add(dev.thor.tools.diagnostic.DebugConnection::class.java.getDeclaredField("worker").apply {isAccessible=true}.get(restoredDebug) as java.util.concurrent.ExecutorService)
                if(restoreSession!=null){val c=ProductHost::class.java.getDeclaredField("connection").apply {isAccessible=true}.get(ready.host) as String;ok(rpc(ready.host,c,"ui",obj("op" to "select","sessionId" to restoreSession)))}
            }finally {ins.runOnMainSync {restored.finish()};until("Restored idle Activity did not finish"){restored.isDestroyed}}}}
            cleanup("drain-idle-engine-and-accessibility-setup") {
                until("Idle Service/host must close before restoring accessibility",20){!shell(automation,"dumpsys activity services com.tobias.loki/dev.thor.agent.AgentRuntimeService").contains("ServiceRecord{")}
                until("Shizuku accessibility setup workers must terminate before restoration",20){debugWorkers.all {it.isTerminated}}
                evidence.put("idleHostsClosedBeforeAccessibilityRestore",true)
            }
            cleanup("restore-accessibility-last"){if(accessibilityChanged){automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);try {Settings.Secure.putString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,a11yBefore);Settings.Secure.putString(context.contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED,a11yEnabledBefore)}finally {automation.dropShellPermissionIdentity()}}}
            cleanup("preservation-checks") {
                assertTrue("Original model preferences changed",profileBefore==profiles.all);assertTrue("Original credential preferences changed",vaultBefore==vault.all);assertTrue("Original role preferences changed",rolesBefore==roles.all)
                assertEquals(overlayBefore,Settings.Global.getString(context.contentResolver,"overlay_display_devices"))
                assertEquals("Immersive first-use lesson preference must be restored",immersiveBefore,Settings.Secure.getString(context.contentResolver,"immersive_mode_confirmations"))
                assertEquals(a11yBefore,Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));assertEquals(a11yEnabledBefore,Settings.Secure.getString(context.contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED))
                val after=historyIds();for((table,ids) in before)assertTrue("Existing $table history was removed",after.getValue(table).containsAll(ids))
                if(restoreSession!=null)assertTrue("Original selected session must be restored",read {db->db.rawQuery("SELECT value FROM metadata WHERE name='selected'",null).use {it.moveToFirst() && it.getString(0)==restoreSession}})
                evidence.put("profilesRestored",true).put("credentialsRestored",true).put("rolesRestored",true).put("overlayDisplaySettingUnchanged",true).put("originalHistoryPreserved",true).put("accessibilityRestored",true).put("immersiveLessonSettingRestored",true).put("selectedSessionRestored",true).put("temporaryDisplayReleased",true)
            }
            save()
        }
        assertEquals("Cleanup must restore the disposable emulator",0,evidence.getJSONArray("cleanupErrors").length())
    }
}
