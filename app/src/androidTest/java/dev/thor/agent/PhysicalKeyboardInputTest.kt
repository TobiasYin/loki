package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.Budgets
import dev.thor.tools.hash
import dev.thor.tools.diagnostic.ThorAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit connected Thor Native RPC acceptance. Keeps the saved profile/credential/roles,
 * starts ordinary DEBUG receiver only, and performs no cloud/model request.
 * Inputs use published product tools; independent Android callbacks prove receipt. */
@RunWith(AndroidJUnit4::class)
class PhysicalKeyboardInputTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val readerDelegate=lazy {SqliteStore(context,"product.db")}
    private val reader get()=readerDelegate.value
    private val terminal=setOf("completed","failed","cancelled","interrupted")
    private val receiverPackage="dev.thor.inputlab"
    private val evidence=obj("schemaVersion" to 1,"evidenceMode" to "manual_platform_api",
        "scope" to "physical Thor Native RPC keyboard/upper-foreground component; ordinary Activity; no model execution",
        "cloudCalled" to false,"modelExecutionProven" to false,"systemInjectionProven" to false,
        "steps" to JSONArray(),"cleanupErrors" to JSONArray(),"textPassed" to false,"keysPassed" to false,"passed" to false)
    @After fun closeReader(){if(readerDelegate.isInitialized())reader.close()}
    private fun save(){File(context.filesDir,"physical-keyboard-input.json").writeText(evidence.toString(2))}
    // Private diagnostic only: stacks may include exception text; never publish this file.
    private val privateDiagnostics=JSONArray()
    private fun diagnose(stage:String,t:Throwable?=null) {
        privateDiagnostics.put(obj("stage" to stage,"type" to t?.javaClass?.name,"stack" to t?.stackTraceToString()?.take(32768)))
        runCatching {File(context.filesDir,"private-physical-keyboard-diagnostics.json").writeText(privateDiagnostics.toString(2))}
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
        require(table in setOf("sessions","runs","messages","events"))
        return read {db->db.rawQuery("SELECT payload FROM $table${if(run==null)"" else " WHERE runId=?"} ORDER BY rowid",run?.let {arrayOf(it)}).use {c->buildList {while(c.moveToNext())add(JSONObject(c.getString(0)))}}}
    }
    private fun historyIds():Map<String,Set<String>> =read {db->listOf("sessions","runs","messages","events","attachments").associateWith {table->
        db.rawQuery("SELECT id FROM $table",null).use {c->buildSet {while(c.moveToNext())add(c.getString(0))}}}}
    private fun shell(automation:UiAutomation,command:String):String =ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
        val read=Budgets.read(it,Budgets.CONFIG);assertFalse("Diagnostic output exceeded budget",read.truncated);read.bytes.toString(Charsets.UTF_8)
    }
    private fun receiver(method:String,b:Bundle):JSONObject {
        val reply=context.contentResolver.call(Uri.parse("content://dev.thor.inputlab.keyboard"),method,null,b) ?: error("Keyboard receiver missing")
        return obj("lease" to reply.getString("lease"),"snapshot" to JSONObject(reply.getString("snapshot")!!))
    }
    private fun lowerReceiver(method:String,b:Bundle):JSONObject {
        val reply=context.contentResolver.call(Uri.parse("content://dev.thor.inputlab.dynamic"),method,null,b) ?: error("Lower receiver missing")
        return obj("lease" to reply.getString("lease"),"snapshot" to JSONObject(reply.getString("snapshot")!!))
    }
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
    private fun prefDigests():Map<String,String> =listOf("profiles","voice","native-credentials","display-roles").associateWith {
        hash(context.getSharedPreferences(it,0).all.toSortedMap().toString().toByteArray())
    }
    private fun assertUpperContext(state:JSONObject,upper:Int):JSONObject {
        val l0=state.getJSONObject("l0");val observed=l0.getJSONObject("upperAppObservation");val app=l0.getJSONObject("upperApp")
        assertEquals("observed",observed.getString("status"));assertEquals(upper,observed.getInt("displayId"))
        assertEquals(receiverPackage,app.getString("packageName"));assertEquals("available",app.getString("metadataStatus"))
        val info=context.packageManager.getPackageInfo(receiverPackage,0)
        assertEquals(context.packageManager.getApplicationLabel(info.applicationInfo!!).toString(),app.getString("label"))
        assertEquals(info.versionName,app.getString("versionName"));assertEquals(info.longVersionCode.toString(),app.getString("versionCode"))
        assertFalse("Read-only metadata must not grant input identity",app.has("snapshotId") || app.has("nodeRef") || app.has("windowId"))
        return JSONObject(l0.toString())
    }
    @Test fun savedDeviceUnicodeKeyboardAndUpperForegroundUseRealNativeRpc() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit connected device acceptance required",args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",Build.MODEL)
        val upper=args.getString("labDisplay")!!.toInt();val lower=args.getString("chatDisplay")!!.toInt();assertNotEquals(upper,lower)
        assertTrue("Never interrupt user work",rows("runs").all {it.optString("status") in terminal})
        val beforeHistory=historyIds();val beforePrefs=prefDigests()
        val automation=ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertFalse("Do not replace an existing receiver task",shell(automation,"dumpsys activity activities").contains("$receiverPackage/"))
        var activity:MainActivity?=null;var host:ProductHost?=null;var run:NativeRun?=null;var connection:String?=null;var selected:String?=null
        var lease:String?=null;var lowerLease:String?=null;var primaryFailure:Throwable?=null
        val testRun="physical-keyboard-${id()}"
        fun params(change:String?=null)=Bundle().apply {putInt("version",1);putString("testRunId",testRun);putString("lease",lease);if(change!=null)putString("change",change)}
        fun lowerParams()=Bundle().apply {putInt("version",1);putString("testRunId",testRun);putString("lease",lowerLease)}
        fun report()=receiver("report",params()).getJSONObject("snapshot")
        fun remember(name:String,value:Any?) {evidence.put(name,value);save()}
        try {
            activity=launchChat(lower)
            val ready=EmulatorRuntimeTestSupport.awaitReady(activity!!);host=ready.host;selected=host!!.selectedSession()
            assertFalse("No user run may start during setup",host!!.hasActiveRuns())
            until("Saved Shizuku/accessibility unavailable") {ThorAccessibilityService.instance!=null && host!!.accessibilitySetupStatus().contains("已通过 Shizuku")}
            val snapshots=ProductHost::class.java.getDeclaredField("snapshots").apply {isAccessible=true}.get(host) as dev.thor.tools.Snapshots
            assertEquals("Normal startup must discover upper screen",upper,snapshots.selected("upper").id)
            assertEquals("Normal startup must discover lower screen",lower,snapshots.selected("lower").id)
            connection=ProductHost::class.java.getDeclaredField("connection").apply {isAccessible=true}.get(host) as String
            launchReceiver("KeyboardFixtureActivity",upper);waitTarget(upper)
            lease=receiver("prepare",params()).getString("lease")
            assertEquals(upper,report().getInt("displayId"));assertEquals(android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION,report().getInt("windowType"))
            val h=host!!;val c=connection!!
            ok(rpc(h,c,"ui",obj("op" to "new")));val sid=h.selectedSession();remember("testSessionId",sid)
            for((cap,on) in mapOf("perception" to true,"screen_control" to true,"search" to false,"debug" to false))
                ok(rpc(h,c,"ui",obj("op" to "capability","sessionId" to sid,"capability" to cap,"enabled" to on)))
            run=NativeRun(ok(rpc(h,c,"begin",obj("sessionId" to sid,"text" to "真机键盘和默认上屏应用上下文 Native RPC 验证，不调用模型。"))).getJSONObject("lease"),c)
            val r=run!!;remember("nativeRunId",r.id);remember("upperDisplay",upper);remember("lowerDisplay",lower)
            remember("initialAuthorityL0",assertUpperContext(authority(h,r),upper))
            fun normal():JSONObject=tree(h,r).getJSONArray("nodes").objects().single {it.optString("id").endsWith(":id/keyboard_normal")}
            fun type(text:String,mode:String="replace")=tool(h,r,"input.type_text",obj("nodeRef" to normal().getString("nodeRef"),"text" to text,"mode" to mode))
            ok(type("中🙂文"));until("Unicode replacement not observed"){report().getString("normalText")=="中🙂文"}
            assertEquals(0,report().getInt("editorActionCount"));assertEquals(0,report().getInt("submissionCount"));remember("unicodeReplacementAndNoAutoSubmit",true)
            receiver("configure",params("select_emoji"));Thread.sleep(250)
            val emoji=normal().getJSONObject("textSelection");assertEquals(1,emoji.getInt("start"));assertEquals(3,emoji.getInt("end"))
            ok(type("测试","insert"));until("Selection insertion not observed"){report().getString("normalText")=="中测试文"};remember("selectionInsert",true)
            ok(type(""));until("Empty replacement did not clear field"){report().getString("normalText").isEmpty()};remember("emptyReplacementClears",true);remember("textPassed",true)
            ok(type("keyboard sample"))
            // Use the same fresh observation and public activation an Agent uses.
            ok(tool(h,r,"input.activate",obj("nodeRef" to normal().getString("nodeRef"))))
            until("Initial official activation did not focus editor"){report().getBoolean("normalFocused")}
            assertTrue(normal().getBoolean("focused"))
            assertEquals("ready",authority(h,r).getJSONObject("availability").getJSONObject("input.key_press").getString("status"))
            val beforeKeys=report();ok(tool(h,r,"input.key_press",obj("key" to "ENTER")))
            until("ENTER had no actual receiver callbacks"){report().getInt("keyEventCount")>=beforeKeys.getInt("keyEventCount")+2}
            remember("focusAfterEnter",obj("normalFocused" to report().getBoolean("normalFocused"),"passwordFocused" to report().getBoolean("passwordFocused"),"readOnlyFocused" to report().getBoolean("readOnlyFocused")))
            ok(tool(h,r,"input.activate",obj("nodeRef" to normal().getString("nodeRef"))))
            until("Official reactivation did not focus editor"){report().getBoolean("normalFocused")};assertTrue(normal().getBoolean("focused"));remember("keyboardFieldRefocusedWithOfficialTool",true)
            ok(tool(h,r,"input.key_press",obj("key" to "A","modifiers" to JSONArray(listOf("CTRL")))))
            until("CTRL+A did not select actual text"){val x=report();x.getInt("selectionStart")==0 && x.getInt("selectionEnd")==x.getString("normalText").length}
            val all=normal().getJSONObject("textSelection");assertEquals(0,all.getInt("start"));assertEquals(report().getString("normalText").length,all.getInt("end"));remember("selectionReadableInAgentUiTree",true)
            ok(tool(h,r,"input.key_press",obj("key" to "BACKSPACE")))
            until("BACKSPACE did not clear actual selected receiver text"){report().getString("normalText").isEmpty()}
            remember("keyboardKeyBackend",obj("status" to "submitted_and_receiver_observed","keyEventDelta" to (report().getInt("keyEventCount")-beforeKeys.getInt("keyEventCount")),"ctrlASelectionObserved" to true,"backspaceClearObserved" to true));remember("keysPassed",true);remember("nativeReceiverBeforeBackground",report())
            // Keep both observed screens on ordinary, independent DEBUG Activities.
            // This setup does not input into a real app or bypass a lower guard.
            ok(tool(h,r,"agent.background"))
            launchReceiver("DynamicContentFixtureActivity",lower);waitTarget(lower)
            val lowerPrepared=lowerReceiver("prepare",lowerParams());lowerLease=lowerPrepared.getString("lease")
            assertEquals(lower,lowerPrepared.getJSONObject("snapshot").getInt("displayId"))
            remember("lowerFixture",obj("displayId" to lower,"ordinaryActivity" to true,"inputPerformed" to false))
            ok(tool(h,r,"perception.select_screen",obj("screen" to "lower")))
            val lowerAuthority=authority(h,r);assertEquals("lower",lowerAuthority.getJSONObject("l0").getString("targetScreen"))
            remember("lowerSelectedAuthorityL0",assertUpperContext(lowerAuthority,upper));remember("upperContextIndependentOfInputScreen",true)
            ok(tool(h,r,"perception.select_screen",obj("screen" to "upper")))
            assertEquals("upper",authority(h,r).getJSONObject("l0").getString("targetScreen"))
            if(args.getString("physicalUpperContextCloud")=="true") {
                // End only this manual Native run. Runtime owns the following real
                // model run and its end; never replay/end it twice in cleanup.
                ok(rpc(h,c,"end",r.lease));run=null;assertFalse(h.hasActiveRuns())
                val request="只根据本轮系统默认提供的上屏应用基础信息回答，不调用任何工具，不推测，也不要介绍操作步骤。请仅按以下四个字段各输出一行：应用名称：实际名称；包名：实际包名；版本名称：实际版本名称；版本代码：实际版本代码。"
                val begun=h.prepareForRunner(request).get(20,TimeUnit.SECONDS)
                val cloudId=begun.getJSONObject("lease").getString("runId")
                val cloud=obj("runId" to cloudId,"requested" to true,"cloudCalled" to true,"passed" to false)
                evidence.put("cloudUpperContext",cloud).put("cloudCalled",true);save()
                val execution=EmulatorRuntimeTestSupport.awaitExecutionReady(activity!!)
                val delivered=java.util.concurrent.CountDownLatch(1)
                ins.runOnMainSync {execution.evaluateJavascript("window.runPreparedNative(JSON.parse(${JSONObject.quote(begun.toString())}));true") {delivered.countDown()}}
                assertTrue("Runtime execution dispatch missing",delivered.await(5,TimeUnit.SECONDS))
                // User requires no model wall-clock cap. Network/provider failures
                // still become a terminal run through the ordinary Runtime path.
                var completed:JSONObject
                while(true) {
                    completed=read {db->db.rawQuery("SELECT payload FROM runs WHERE id=?",arrayOf(cloudId)).use {cursor->check(cursor.moveToFirst());JSONObject(cursor.getString(0))}}
                    cloud.put("status",completed.optString("status"));save()
                    if(completed.optString("status") in terminal && !h.hasActiveRuns())break
                    Thread.sleep(500)
                }
                val events=rows("events",cloudId)
                val toolCount=events.count {it.optString("type")=="tool_intent"}
                val answer=rows("messages",cloudId).lastOrNull {it.optString("role")=="assistant"}?.optString("text").orEmpty().replace("*","").replace("`","")
                val info=context.packageManager.getPackageInfo(receiverPackage,0)
                fun field(label:String)=Regex("(?m)^\\s*"+Regex.escape(label)+"[：:]\\s*(.*?)\\s*$").find(answer)?.groupValues?.get(1)?.trim()
                val values=obj("label" to (field("应用名称")==context.packageManager.getApplicationLabel(info.applicationInfo!!).toString()),
                    "packageName" to (field("包名")==receiverPackage),"versionName" to (field("版本名称")==info.versionName),
                    "versionCode" to (field("版本代码")==info.longVersionCode.toString()))
                cloud.put("toolCallCount",toolCount).put("matchesPackageManager",values).put("terminal",true)
                val good=completed.optString("status")=="completed" && toolCount==0 && values.keys().asSequence().all {values.getBoolean(it)}
                cloud.put("passed",good);save()
                assertTrue("Real model must answer injected upper app fields without any tool call",good)
                remember("cloudCalled",true);remember("modelExecutionProven",true)
            }
            val sealed=receiver("release",params()).getJSONObject("snapshot");lease=null;remember("receiver",sealed)
            val log=sealed.getJSONObject("evidence");assertFalse(log.getBoolean("incomplete"));assertEquals(0,log.getInt("droppedSamples"))
            val path=log.getString("relativePath");assertTrue(path.matches(Regex("keyboard-reports/[a-f0-9-]{36}/events\\.jsonl")))
            val raw=shell(automation,"run-as dev.thor.inputlab cat files/$path").toByteArray();assertEquals(log.getLong("eventPrefixBytes"),raw.size.toLong());assertEquals(log.getString("eventPrefixSha256"),hash(raw))
            File(context.filesDir,"physical-keyboard-input-events.jsonl").writeBytes(raw);remember("upperContextPassed",true);remember("passed",true)
        }catch(t:Throwable){primaryFailure=t;diagnose("primary-failure",t);evidence.put("passed",false).put("failureType",t.javaClass.simpleName);throw t}
        finally {
            var cleanupFailure:Throwable?=null
            fun cleanup(label:String,block:()->Unit){try{block()}catch(t:Throwable){diagnose("cleanup-$label",t);evidence.getJSONArray("cleanupErrors").put(obj("stage" to label,"error" to t.javaClass.simpleName));evidence.put("passed",false);if(primaryFailure!=null)primaryFailure!!.addSuppressed(t) else if(cleanupFailure==null)cleanupFailure=t else cleanupFailure!!.addSuppressed(t)}}
            cleanup("run"){if(host!=null && run!=null){ok(rpc(host!!,run!!.connection,"end",run!!.lease));assertFalse(host!!.hasActiveRuns())}}
            cleanup("receiver"){if(lease!=null){
                val sealed=receiver("release",params()).getJSONObject("snapshot");lease=null;evidence.put("receiver",sealed)
                val log=sealed.getJSONObject("evidence");assertFalse(log.getBoolean("incomplete"));assertEquals(0,log.getInt("droppedSamples"))
                val path=log.getString("relativePath");assertTrue(path.matches(Regex("keyboard-reports/[a-f0-9-]{36}/events\\.jsonl")))
                val raw=shell(automation,"run-as dev.thor.inputlab cat files/$path").toByteArray();assertEquals(log.getLong("eventPrefixBytes"),raw.size.toLong());assertEquals(log.getString("eventPrefixSha256"),hash(raw))
                File(context.filesDir,"physical-keyboard-input-events.jsonl").writeBytes(raw)
            }}
            cleanup("lower-receiver"){if(lowerLease!=null){
                val lowerSealed=lowerReceiver("release",lowerParams()).getJSONObject("snapshot");lowerLease=null
                evidence.put("lowerReceiver",obj("displayId" to lowerSealed.getInt("displayId"),"sealed" to lowerSealed.getBoolean("sealed"),
                    "touchCount" to lowerSealed.getInt("touchCount"),"clickCount" to lowerSealed.getInt("clickCount")))
                assertEquals("No lower fixture input was requested",0,lowerSealed.getInt("touchCount"));assertEquals(0,lowerSealed.getInt("clickCount"))
            }}
            cleanup("selection"){if(host!=null && connection!=null && selected!=null)try {ok(rpc(host!!,connection!!,"ui",obj("op" to "select","sessionId" to selected)))}catch(t:Throwable){diagnoseView(host!!);throw t}}
            cleanup("activity"){activity?.let {a->ins.runOnMainSync {a.finish()}}}
            cleanup("idle"){until("Runtime did not idle before final checks"){!shell(automation,"dumpsys activity services com.tobias.loki/dev.thor.agent.AgentRuntimeService").contains("ServiceRecord{")}}
            cleanup("history"){val after=historyIds();beforeHistory.forEach {(table,ids)->assertTrue("Original $table IDs must remain",after.getValue(table).containsAll(ids))}}
            cleanup("preferences"){assertEquals("Original configuration must remain unchanged",beforePrefs,prefDigests())}
            cleanup("save-report"){evidence.put("configurationPreserved",beforePrefs==prefDigests());save()}
            if(primaryFailure==null)cleanupFailure?.let {throw it}
        }
    }
}
