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
class EmulatorDualScreenControlTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val readerDelegate=lazy {SqliteStore(context,"product.db")}
    private val reader get()=readerDelegate.value
    private val terminal=setOf("completed","failed","cancelled","interrupted")
    private val receiverPackage="dev.thor.inputlab"
    private val evidence=obj("schemaVersion" to 1,"evidenceMode" to "manual_platform_api",
        "scope" to "emulator real Native component; synthetic offline receivers; no model execution",
        "cloudCalled" to false,"modelExecutionProven" to false,"systemInjectionProven" to false,
        "steps" to JSONArray(),"cleanupErrors" to JSONArray(),"passed" to false)
    @After fun closeReader(){if(readerDelegate.isInitialized())reader.close()}
    private fun save(){File(context.filesDir,"emulator-dual-screen.json").writeText(evidence.toString(2))}
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
    private fun receiver(method:String,scenario:Boolean,b:Bundle):JSONObject {
        val reply=context.contentResolver.call(Uri.parse("content://dev.thor.inputlab.${if(scenario)"scenario" else "runner"}"),method,null,b) ?: error("Independent Input Lab provider missing")
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
        evidence.getJSONArray("steps").put(obj("runId" to r.id,"toolId" to name,"arguments" to args,"elapsedMs" to (SystemClock.elapsedRealtime()-start),"error" to result.opt("error"),"outcome" to result.opt("outcome"),"data" to details(result)))
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
        File(context.filesDir,"emulator-dual-screen-$role.jpg").writeBytes(bytes)
        evidence.put("${role}Image",obj("bytes" to bytes.size,"sha256" to hash(bytes),"displayId" to display));save()
        return data
    }

    @Test fun realNativeBackgroundRolesControlsAndCrossScreenGuardsAreIndependent() {
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
        val active=mutableListOf<NativeRun>();var activity:MainActivity?=null;var host:ProductHost?=null;var restoreSession:String?=null
        var virtual:VirtualDisplay?=null;var images:ImageReader?=null;var drainThread:HandlerThread?=null
        var labLease:String?=null;var scenarioLease:String?=null;val testRun="dual-screen-${id()}"
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
            ins.runOnMainSync {activity!!.finish()};until("Setup chat Activity not destroyed"){activity!!.isDestroyed}
            receiverStarted=true;launchReceiver("LabActivity",upper.id);launchReceiver("ScenarioActivity",lower)
            waitTarget(upper.id);waitTarget(lower)
            activity=launchChat(lower);ready=EmulatorRuntimeTestSupport.awaitReady(activity!!);host=ready.host
            assertEquals("Chat must actually occupy the lower fixture display",lower,activity!!.display!!.displayId)
            val connection=ProductHost::class.java.getDeclaredField("connection").apply {isAccessible=true}.get(host) as String
            fun newSession():String {
                val previous=host!!.selectedSession();ok(rpc(host!!,connection,"ui",obj("op" to "new")));until("New session did not change"){host!!.selectedSession()!=previous}
                val sid=host!!.selectedSession()
                for((cap,on) in mapOf("perception" to true,"screen_control" to true,"search" to false,"debug" to false))ok(rpc(host!!,connection,"ui",obj("op" to "capability","sessionId" to sid,"capability" to cap,"enabled" to on)))
                return sid
            }
            val sidA=newSession();val sidB=newSession()
            fun begin(sid:String):NativeRun {
                val admitted=ok(rpc(host!!,connection,"begin",obj("sessionId" to sid,"text" to "本地双屏 Native 组件验证，不调用模型。")))
                return NativeRun(admitted.getJSONObject("lease"),connection).also {active.add(it)}
            }
            val b=begin(sidB);val a=begin(sidA)
            evidence.put("runA",a.id).put("runB",b.id).put("phase","native-controls");save()
            assertEquals("upper",authority(host!!,a).getJSONObject("l0").getString("targetScreen"))
            val background=details(ok(tool(host!!,a,"agent.background")))
            assertEquals(true,background.getBoolean("movedToBackground"));assertFalse(background.getBoolean("alreadyBackground"))
            until("Fixed chat task did not actually move behind lower receiver"){
                var hidden=false;ins.runOnMainSync {hidden=activity!!.window.decorView.windowVisibility!=android.view.View.VISIBLE};hidden
            }
            waitTarget(upper.id);waitTarget(lower)
            val already=details(ok(tool(host!!,a,"agent.background")));assertTrue(already.getBoolean("alreadyBackground"));assertFalse(already.getBoolean("submitted"))
            labLease=receiver("prepare",false,Bundle().apply {putInt("version",1);putString("caseId","LAB-05");putString("testRunId",testRun);putString("agentRunId",a.id);putString("senderTraceRef","native-component:${a.id}");putString("evidenceMode","manual_platform_api");putInt("observationSeconds",60)}).getString("lease")
            val scenarioPrepared=receiver("prepare",true,Bundle().apply {putInt("version",1);putString("scene","settings");putLong("seed",7331L);putString("testRunId",testRun);putString("agentRunId",a.id);putString("senderTraceRef","native-component:${a.id}");putString("evidenceMode","manual_platform_api")})
            scenarioLease=scenarioPrepared.getString("lease");evidence.put("lowerAttemptId",scenarioPrepared.getJSONObject("snapshot").getString("attemptId"));save()
            // LAB-05 is semantic-only: ARM acknowledges readiness; RECORDING is
            // entered only by raw dispatch, so waiting for it would require the
            // very input this test must not fabricate before Native execution.
            until("Upper independent receiver did not finish its 500ms ARM setup"){
                val status=receiver("report",false,receiverArgs(testRun,labLease!!)).getJSONObject("snapshot")
                val ready=status.optString("testRunId")==testRun && status.optString("phase") in setOf("ARMED","RECORDING")
                if(ready){evidence.put("upperAttemptId",status.getString("attemptId"));save()};ready
            }
            waitTarget(upper.id);waitTarget(lower)
            val upperFrame=capture(host!!,a,"upper",upper.id)
            val upperTree=tree(host!!,a);val upperNode=node(upperTree,"Native semantic target")
            val oldUpperSnapshot=authority(host!!,a).getJSONObject("l0").getString("snapshotId")
            ok(tool(host!!,a,"perception.select_screen",obj("screen" to "lower")))
            assertEquals("lower",authority(host!!,a).getJSONObject("l0").getString("targetScreen"))
            assertEquals("upper",authority(host!!,b).getJSONObject("l0").getString("targetScreen"))
            val foreignSnapshot=tool(host!!,a,"input.tap",obj("x" to 1,"y" to 1,"coordinateSpace" to "display_logical"),oldUpperSnapshot)
            assertEquals("TARGET_CHANGED",foreignSnapshot.getString("error"))
            val staleRef=tool(host!!,a,"input.activate",obj("nodeRef" to upperNode.getString("nodeRef")))
            assertEquals("TARGET_CHANGED",staleRef.getString("error"))
            val staleFrame=tool(host!!,a,"input.tap",obj("x" to 1,"y" to 1,"coordinateSpace" to "frame_pixels","frameId" to upperFrame.getString("frameId")))
            assertEquals("TARGET_CHANGED",staleFrame.getString("error"))
            capture(host!!,a,"lower",lower)
            val lowerTree=tree(host!!,a);val lowerNode=node(lowerTree,"设备偏好");val bounds=lowerNode.getJSONArray("bounds")
            ok(tool(host!!,a,"input.tap",obj("x" to ((bounds.getInt(0)+bounds.getInt(2))/2.0),"y" to ((bounds.getInt(1)+bounds.getInt(3))/2.0),"coordinateSpace" to "display_logical","expectedPackage" to receiverPackage)))
            until("Lower receiver did not navigate from the actual tap"){receiver("report",true,receiverArgs(testRun,scenarioLease!!)).getJSONObject("snapshot").getString("page")=="preferences"}
            val lowerReceipt=receiver("report",true,receiverArgs(testRun,scenarioLease!!)).getJSONObject("snapshot")
            assertEquals(lower,lowerReceipt.getJSONObject("geometry").getInt("displayId"));assertEquals(1,lowerReceipt.getJSONObject("state").getInt("settingNavigationCount"))
            ok(tool(host!!,a,"perception.select_screen",obj("screen" to "upper")))
            val freshUpper=tree(host!!,a);val currentUpperNode=node(freshUpper,"Native semantic target")
            evidence.put("upperActivationWindowDiagnostic",JSONArray(ThorAccessibilityService.instance!!.windowsOnAllDisplays[upper.id].orEmpty().filter {it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION}.map {w->obj("windowId" to w.id,"focused" to w.isFocused,"active" to w.isActive,"matchesTreeWindow" to (w.id==freshUpper.getInt("windowId")))}));save()
            ok(tool(host!!,a,"input.activate",obj("nodeRef" to currentUpperNode.getString("nodeRef"))))
            assertEquals("upper",authority(host!!,b).getJSONObject("l0").getString("targetScreen"))
            var upperReceipt=JSONObject()
            until("Upper receiver report did not finish",90){upperReceipt=receiver("report",false,receiverArgs(testRun,labLease!!)).getJSONObject("snapshot");!upperReceipt.isNull("report")}
            val upperReport=upperReceipt.getJSONObject("report");evidence.put("upperReceiver",upperReport);save()
            assertEquals("manual_platform_api",upperReport.getJSONObject("metadata").getString("mode"))
            assertFalse(upperReport.getJSONObject("integrity").getBoolean("incomplete"))
            assertEquals(0,upperReport.getJSONObject("integrity").getLong("droppedSamples"))
            assertEquals(1,upperReport.getJSONObject("sceneAfter").getInt("semanticCount"));assertEquals("pass",upperReport.getString("status"))
            assertEquals(upper.id,upperReceipt.getJSONObject("geometry").getInt("actualWindowDisplayId"))
            val sealedLower=receiver("release",true,receiverArgs(testRun,scenarioLease!!)).getJSONObject("snapshot");scenarioLease=null;evidence.put("lowerReceiver",sealedLower);save()
            assertEquals("manual_platform_api",sealedLower.getString("evidenceMode"))
            assertFalse(sealedLower.getBoolean("agentAnswerVerified"));assertFalse(sealedLower.getBoolean("systemInjectionProven"))
            val lowerLog=sealedLower.getJSONObject("evidence");assertFalse(lowerLog.getBoolean("incomplete"));assertEquals(0,lowerLog.getInt("droppedSamples"))
            val lowerPath=lowerLog.getString("relativePath");assertTrue(lowerPath.matches(Regex("scenario-reports/[a-f0-9-]{36}/events\\.jsonl")))
            val lowerRaw=shell(automation,"run-as dev.thor.inputlab cat files/$lowerPath").toByteArray(Charsets.UTF_8)
            assertEquals(lowerLog.getLong("eventPrefixBytes"),lowerRaw.size.toLong());assertEquals(lowerLog.getString("eventPrefixSha256"),hash(lowerRaw))
            val lowerEvents=lowerRaw.toString(Charsets.UTF_8).lineSequence().filter {it.isNotBlank()}.map {JSONObject(it)}.toList()
            val clicks=lowerEvents.filter {it.getString("eventKind")=="click" && it.getJSONObject("data").optString("viewId")=="scenario_preferences"}
            assertEquals(1,clicks.size);assertEquals(lower,clicks.single().getJSONObject("data").getInt("displayId"))
            val lowerTouches=lowerEvents.filter {it.getString("recordOrigin")=="android_dispatch" && it.getString("eventKind")=="touch"}
            assertEquals("Rejected cross-screen inputs must not reach either receiver",1,lowerTouches.count {it.getJSONObject("data").getInt("actionMasked")==0})
            assertEquals(1,lowerTouches.count {it.getJSONObject("data").getInt("actionMasked")==1})
            assertTrue(lowerTouches.all {it.getJSONObject("data").getInt("displayId")==lower})
            val down=lowerTouches.single {it.getJSONObject("data").getInt("actionMasked")==0}.getJSONObject("data")
            val up=lowerTouches.single {it.getJSONObject("data").getInt("actionMasked")==1}.getJSONObject("data")
            assertEquals(down.getLong("downTime"),up.getLong("downTime"));assertTrue(up.getLong("eventTime")>=down.getLong("eventTime"))
            val click=clicks.single();val clickToken=click.getJSONObject("data").opt("dispatchToken")
            if(clickToken!=null && clickToken!==JSONObject.NULL)assertEquals("Synchronous click token must match UP",up.getLong("dispatchToken"),click.getJSONObject("data").getLong("dispatchToken"))
            // Android View may post performClick after dispatchTouchEvent finally
            // clears its token. A missing token is explicitly unverified sender
            // attribution, not evidence that the independently observed click failed.
            val upRow=lowerTouches.single {it.getJSONObject("data").getInt("actionMasked")==1}
            assertTrue("Click must follow the single real UP",click.getLong("sequence")>upRow.getLong("sequence"))
            assertTrue("Click callback must occur in this bounded observed input window",click.getLong("uptimeMs")-upRow.getLong("uptimeMs") in 0..5000)
            evidence.put("lowerClickCallbackDeferred",clickToken==null || clickToken===JSONObject.NULL).put("lowerClickSenderAttribution","time-window-only-not-sender-authentication")
            val attempt=upperReceipt.getString("attemptId");java.util.UUID.fromString(attempt)
            val upperRaw=shell(automation,"run-as dev.thor.inputlab cat files/reports/$attempt/events.jsonl").toByteArray(Charsets.UTF_8)
            val prefix=upperReport.getLong("eventPrefixBytes").toInt();assertTrue(prefix<=upperRaw.size)
            assertEquals(upperReport.getString("eventPrefixSha256"),hash(upperRaw.copyOf(prefix)))
            val upperEvents=upperRaw.toString(Charsets.UTF_8).lineSequence().filter {it.isNotBlank()}.map {JSONObject(it)}.toList()
            assertFalse("No coordinate input was authorized to the upper receiver",upperEvents.any {it.getString("eventKind")=="raw_touch"})
            val semantics=upperEvents.filter {it.getString("recordOrigin")=="semantic_callback" && it.getJSONObject("data").optJSONObject("after")?.optInt("semanticCount")==1}
            assertEquals(1,semantics.size);assertEquals(upper.id,semantics.single().getJSONObject("window").getInt("actualWindowDisplayId"))
            File(context.filesDir,"emulator-dual-screen-upper-events.jsonl").writeBytes(upperRaw.copyOf(prefix));File(context.filesDir,"emulator-dual-screen-lower-events.jsonl").writeBytes(lowerRaw)
            // Release the bounded receiver lease while its sealed prefix is fresh,
            // before the independent own-window escape fixture changes focus.
            receiver("release",false,receiverArgs(testRun,labLease!!));labLease=null
            // Only after the normal controls and sealed receiver evidence pass:
            // reproduce a now-obscured lower target, without input to the chat.
            ok(tool(host!!,a,"perception.select_screen",obj("screen" to "lower")))
            assertEquals("lower",authority(host!!,a).getJSONObject("l0").getString("targetScreen"))
            ins.runOnMainSync {activity!!.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),ActivityOptions.makeBasic().setLaunchDisplayId(lower).toBundle())}
            until("Original chat task was not shown for the blocked-lower escape fixture") {
                var visible=false;ins.runOnMainSync {visible=activity!!.window.decorView.windowVisibility==android.view.View.VISIBLE};visible && ThorAccessibilityService.instance?.target(lower)?.packageName==context.packageName
            }
            val blocked=tool(host!!,a,"perception.capture_screen",obj("maxEdge" to 960,"quality" to 80,"format" to "jpeg"))
            assertEquals("Assistant lower window must remain blocked","PERMISSION_MISSING",blocked.getString("error"))
            assertFalse("Refused assistant pixels must never become an attachment",blocked.optJSONArray("content")?.objects()?.any {it.optString("type")=="attachment"}==true)
            ok(tool(host!!,a,"perception.select_screen",obj("screen" to "upper")))
            assertEquals("upper",authority(host!!,a).getJSONObject("l0").getString("targetScreen"))
            assertEquals(receiverPackage,details(ok(tool(host!!,a,"perception.app_info"))).getString("package"))
            ok(tool(host!!,a,"agent.background"));waitTarget(lower)
            evidence.put("obscuredLowerOwnWindowRefused",true).put("obscuredLowerCanEscapeToUpper",true)
            evidence.put("upperReceiver",upperReport).put("lowerReceiver",sealedLower).put("upperRawSha256",hash(upperRaw.copyOf(prefix))).put("lowerRawSha256",hash(lowerRaw))
                .put("backgroundObserved",true).put("crossScreenOldSnapshotRejected",true).put("crossScreenOldNodeRejected",true).put("crossScreenOldFrameRejected",true).put("otherRunSelectionUnchanged",true).put("passed",true)
        } catch(t:Throwable){evidence.put("failureType",t.javaClass.simpleName).put("failure",t.message);throw t}
        finally {
            fun cleanup(name:String,block:()->Unit){try {block()}catch(t:Throwable){evidence.getJSONArray("cleanupErrors").put(obj("step" to name,"error" to t.javaClass.simpleName,"message" to t.message));evidence.put("passed",false)}}
            cleanup("end-own-runs"){host?.let {h->active.reversed().forEach {ok(rpc(h,it.connection,"end",it.lease))};assertFalse(h.hasActiveRuns());val ours=active.map {it.id}.toSet();assertTrue("Manual components must never claim model completion",rows("runs").filter {it.getString("id") in ours}.all {it.getString("status")=="failed"})}}
            cleanup("release-upper-receiver"){labLease?.let {receiver("release",false,receiverArgs(testRun,it))}}
            cleanup("release-lower-receiver"){scenarioLease?.let {receiver("release",true,receiverArgs(testRun,it))}}
            cleanup("finish-test-chat"){activity?.takeUnless {it.isDestroyed}?.let {a->ins.runOnMainSync {a.finish()};until("Test Activity did not finish"){a.isDestroyed}}}
            cleanup("stop-test-receiver"){if(receiverStarted)shell(automation,"am force-stop dev.thor.inputlab")}
            cleanup("release-virtual-display"){virtual?.release();until("Temporary VirtualDisplay leaked"){displayFacts(context).map {it.id}.toSet()==initialDisplays.map {it.id}.toSet()}}
            cleanup("close-display-surface"){images?.setOnImageAvailableListener(null,null);drainThread?.quitSafely();drainThread?.join(2000);images?.close()}
            cleanup("restore-preferences"){if(preferencesChanged){restore(profiles,profileBefore);restore(vault,vaultBefore);restore(roles,rolesBefore)}}
            cleanup("restore-immersive-lesson"){if(immersiveChanged){automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);try {check(Settings.Secure.putString(context.contentResolver,"immersive_mode_confirmations",immersiveBefore))}finally {automation.dropShellPermissionIdentity()}}}
            cleanup("restore-accessibility"){if(accessibilityChanged){automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);try {Settings.Secure.putString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,a11yBefore);Settings.Secure.putString(context.contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED,a11yEnabledBefore)}finally {automation.dropShellPermissionIdentity()}}}
            cleanup("reload-idle-engine-with-original-state"){if(preferencesChanged){val restored=launchChat(upper.id);try {
                val ready=EmulatorRuntimeTestSupport.reloadIdle(restored)
                if(restoreSession!=null){val c=ProductHost::class.java.getDeclaredField("connection").apply {isAccessible=true}.get(ready.host) as String;ok(rpc(ready.host,c,"ui",obj("op" to "select","sessionId" to restoreSession)))}
            }finally {ins.runOnMainSync {restored.finish()}}}}
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
