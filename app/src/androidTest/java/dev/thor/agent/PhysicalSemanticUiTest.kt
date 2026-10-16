package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.*
import dev.thor.tools.diagnostic.AndroidBackend
import dev.thor.tools.diagnostic.ThorAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Connected-device component test, deliberately NOT a model/product-authority run.
 * Uses the production Gateway/AndroidBackend with ManualTestAuthority. Runner only
 * prepares/reads/seals its independent local scene; no test helper dispatches input. */
@RunWith(AndroidJUnit4::class)
class PhysicalSemanticUiTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val reader by lazy {SqliteStore(context,"product.db")}
    private val uri=Uri.parse("content://dev.thor.inputlab.scenario")
    private fun until(seconds:Int,message:String,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+seconds*1000L
        while(SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(100)}
        fail(message)
    }
    private fun <T> retryRead(block:()->T):T {
        val end=SystemClock.elapsedRealtime()+40000
        while(true)try{return block()}catch(t:android.database.sqlite.SQLiteDatabaseLockedException){if(SystemClock.elapsedRealtime()>=end)throw t;Thread.sleep(100)}
    }
    private fun runs()=retryRead {reader.readableDatabase.rawQuery("SELECT payload FROM runs",null).use {c->buildList {while(c.moveToNext())add(JSONObject(c.getString(0)))}}}
    private fun historyIds()=retryRead {reader.readableDatabase.let {db->listOf("sessions","runs","messages","events","attachments").associateWith {table->
        db.rawQuery("SELECT id FROM $table",null).use {c->buildSet {while(c.moveToNext())add(c.getString(0))}}
    }}}
    private fun receiver(method:String,args:Bundle):JSONObject {
        val response=context.contentResolver.call(uri,method,null,args) ?: error("Scenario receiver missing")
        return obj("lease" to response.getString("lease"),"snapshot" to JSONObject(response.getString("snapshot")!!))
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun raw(snapshot:JSONObject,automation:UiAutomation):ByteArray {
        val attempt=snapshot.getString("attemptId");require(java.util.UUID.fromString(attempt).toString()==attempt)
        val path="scenario-reports/$attempt/events.jsonl"
        assertEquals(path,snapshot.getJSONObject("evidence").getString("relativePath"))
        // Fixed UUID-only path, no arbitrary command or user-controlled shell arguments.
        val fd=automation.executeShellCommand("run-as dev.thor.inputlab cat files/$path")
        val worker=Executors.newSingleThreadExecutor()
        val result=worker.submit<ByteArray> {
            ParcelFileDescriptor.AutoCloseInputStream(fd).use {input->
                val bytes=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=input.read(buffer);if(n<0)break;check(bytes.size()+n<=8*1024*1024){"Receiver log exceeds its byte budget"};bytes.write(buffer,0,n)}
                bytes.toByteArray()
            }
        }
        return try {result.get(5,TimeUnit.SECONDS)}finally {runCatching {fd.close()};result.cancel(true);worker.shutdownNow()}
    }
    private fun rows(bytes:ByteArray)=bytes.toString(Charsets.UTF_8).lineSequence().filter {it.isNotBlank()}.map {JSONObject(it)}.toList()
    private fun touchCount(snapshot:JSONObject,automation:UiAutomation)=rows(raw(snapshot,automation)).count {it.optString("eventKind")=="touch" && it.optString("recordOrigin")=="android_dispatch"}
    private fun collect(snapshot:JSONObject,automation:UiAutomation,evidence:JSONObject,testRun:String,noAgent:String) {
        File(context.filesDir,"physical-semantic-ui-report.json").writeText(snapshot.toString(2))
        val bytes=raw(snapshot,automation);File(context.filesDir,"physical-semantic-ui-events.jsonl").writeBytes(bytes)
        val proof=snapshot.getJSONObject("evidence");val prefix=proof.getLong("eventPrefixBytes")
        assertTrue(prefix in 1..bytes.size.toLong());assertEquals("Sealed evidence must include the entire file",bytes.size.toLong(),prefix)
        assertEquals(proof.getString("eventPrefixSha256"),sha(bytes))
        val events=rows(bytes);assertEquals(proof.getLong("eventCount"),events.size.toLong())
        events.forEachIndexed {i,row->
            assertEquals(i+1L,row.getLong("sequence"))
            for(key in listOf("attemptId","testRunId","agentRunId","evidenceMode"))assertEquals(snapshot.getString(key),row.getString(key))
        }
        assertTrue(proof.getBoolean("sealed"));assertFalse(proof.getBoolean("incomplete"));assertEquals(0L,proof.getLong("droppedSamples"))
        assertEquals("manual_platform_api",snapshot.getString("evidenceMode"));assertEquals(noAgent,snapshot.getString("agentRunId"));assertEquals(testRun,snapshot.getString("testRunId"))
        assertFalse(snapshot.getBoolean("agentAnswerVerified"));assertFalse(snapshot.getBoolean("systemInjectionProven"))
        assertTrue(events.any {it.optString("eventKind")=="scroll" && it.getJSONObject("data").optInt("scrollY")>0})
        assertEquals("This component case never opens an article",0,snapshot.getJSONObject("state").getInt("articleOpenCount"))
        assertEquals("Rejected taps must produce no receiver touch dispatch",0,events.count {it.optString("eventKind")=="touch"})
        assertEquals("Semantic scrolling must not be relabelled as raw input",0,events.count {it.optString("recordOrigin")=="android_dispatch"})
        evidence.put("receiverState",snapshot.getJSONObject("state")).put("eventCount",events.size).put("eventPrefixBytes",prefix)
            .put("eventPrefixSha256",sha(bytes)).put("rawTouchDispatchCount",0).put("rawAndroidDispatchCount",0).put("independentRawHashVerified",true)
    }

    @Test fun offscreenSemanticSearchRealScrollStaleRefsAndUpperGuards() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit connected component acceptance required",args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val upper=args.getString("labDisplay")!!.toInt();val lower=args.getString("chatDisplay")!!.toInt();assertNotEquals(upper,lower)
        val evidence=obj("schemaVersion" to 1,"evidenceMode" to "manual_platform_api","authority" to "ManualTestAuthority: component control; no cloud/model/product-authority run",
            "upperDisplay" to upper,"chatDisplay" to lower,"maxCalls" to 32,"modelInvoked" to false,"passed" to false,"steps" to JSONArray())
        fun save(){File(context.filesDir,"physical-semantic-ui.json").writeText(evidence.toString(2))}
        val profiles=context.getSharedPreferences("profiles",0);val profileBefore=profiles.all.toMap()
        val credentials=context.getSharedPreferences("native-credentials",0);val credentialsBefore=credentials.all.toMap()
        var before:Map<String,Set<String>>?=null
        var activity:MainActivity?=null;var backend:AndroidBackend?=null
        var authority:ManualTestAuthority?=null;var grant:ManualTestAuthority.Lease?=null;var receiverArgs:Bundle?=null
        var failure:Throwable?=null;var sealed=false
        val testRun="component-semantic-${id()}";val noAgent="no-agent:$testRun"
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        try {
            save()
            assertTrue("Do not start over a pre-existing user task",runs().all {it.optString("status") in setOf("completed","failed","cancelled","interrupted")})
            before=historyIds()
            val screen=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(lower).toBundle()) as MainActivity
            activity=screen
            val host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(screen) as ProductHost
            assertFalse("Do not interrupt existing user work",host.hasActiveRuns())
            val device=ProductHost::class.java.getDeclaredField("backend").apply {isAccessible=true}.get(host) as AndroidBackend;backend=device
            val snapshots=ProductHost::class.java.getDeclaredField("snapshots").apply {isAccessible=true}.get(host) as Snapshots
            until(40,"Accessibility setup did not become ready"){ThorAccessibilityService.instance!=null && host.accessibilitySetupStatus().contains("已通过 Shizuku")}
            until(20,"Normal startup must identify upper display without manual calibration"){runCatching {snapshots.selected().id==upper}.getOrDefault(false)}
            context.startActivity(Intent().setComponent(ComponentName("dev.thor.inputlab","dev.thor.inputlab.ScenarioActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),ActivityOptions.makeBasic().setLaunchDisplayId(upper).toBundle())
            until(10,"Scenario activity did not become the upper app"){ThorAccessibilityService.instance!!.target(upper).let {it.packageName=="dev.thor.inputlab" && it.validity=="valid"}}
            Thread.sleep(500)
            val seed=java.util.UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
            val setup=receiver("prepare",Bundle().apply {
                putInt("version",1);putString("scene","news");putLong("seed",seed);putString("testRunId",testRun);putString("agentRunId",noAgent)
                putString("senderTraceRef","component-gateway/ManualTestAuthority:semantic-ui; no cloud/model/product-authority run");putString("evidenceMode","manual_platform_api")
            })
            val params=Bundle().apply {putInt("version",1);putString("lease",setup.getString("lease"));putString("testRunId",testRun)};receiverArgs=params
            val initial=setup.getJSONObject("snapshot");assertEquals(upper,initial.getJSONObject("geometry").getInt("displayId"))
            assertEquals(0,initial.getJSONObject("state").getInt("listScrollY"))
            val title=initial.getJSONObject("expected").getString("newsTargetTitle")
            evidence.put("testRunId",testRun).put("agentRunId",noAgent).put("seed",seed).put("targetTitle",title)
            Thread.sleep(600)
            val clock=Clock {SystemClock.elapsedRealtime()}
            val auth=ManualTestAuthority(clock).apply {set(Capability.SCREEN_CONTROL,true)};authority=auth
            val lease=auth.begin(device);grant=lease
            val gateway=Gateway(auth,device,snapshots,clock);var calls=0
            fun call(name:String,tool:String,snapshot:String?,values:Map<String,Any?>):ToolResult {
                assertTrue("Component authority budget must stay below 32",calls<32);calls++
                val result=gateway.execute(Envelope(lease.session,lease.run,id(),tool,lease.epoch,lease.deadline,snapshot),values)
                evidence.getJSONArray("steps").put(obj("name" to name,"tool" to tool,"snapshotId" to result.snapshotId,"error" to result.error?.name,
                    "message" to result.message,"outcome" to result.outcome,"data" to JSONObject(result.data)))
                evidence.put("calls",calls);save();return result
            }
            fun tree(name:String):ToolResult {
                val result=call(name,"perception.ui_tree",snapshots.capture().id,mapOf("query" to title,"visibility" to "all","maxDepth" to 20,"maxNodes" to 64,"maxChars" to 6000,"maxVisited" to 256))
                assertNull("Tree query failed: ${result.message}",result.error);assertNotNull(result.snapshotId);return result
            }
            fun target(tree:ToolResult):JSONObject {
                val nodes=JSONObject(tree.data).getJSONArray("nodes").objects()
                return nodes.single {it.optString("text")==title && it.optBoolean("matchesQuery")}
            }
            fun container(tree:ToolResult):String {
                val nodes=JSONObject(tree.data).getJSONArray("nodes").objects();val item=target(tree)
                val ref=item.getString("scrollAncestorRef");assertTrue(ref.isNotBlank())
                val parent=nodes.single {it.getString("nodeRef")==ref}
                assertTrue(parent.getBoolean("scrollable"));assertTrue(parent.getBoolean("visibleToUser"));assertTrue(parent.getBoolean("enabled"))
                assertTrue(parent.getString("id").endsWith("/scenario_news_scroll"));return ref
            }
            fun report()=receiver("report",params).getJSONObject("snapshot")
            fun scrollAndObserve(tree:ToolResult,name:String) {
                val oldY=report().getJSONObject("state").getInt("listScrollY")
                val moved=call(name,"input.scroll",tree.snapshotId,mapOf("nodeRef" to container(tree),"direction" to "forward","expectedPackage" to "dev.thor.inputlab"))
                assertNull("Real semantic scroll failed: ${moved.message}",moved.error);assertEquals("submitted",moved.outcome)
                assertTrue(moved.data["nodeRefsInvalidated"]==true)
                until(4,"Independent receiver did not observe actual viewport scroll"){report().getJSONObject("state").getInt("listScrollY")>oldY}
                // ScrollView performs a bounded smooth scroll. Wait for settled content before new ref guards.
                var lastY=-1;var stable=0
                until(4,"Receiver scroll position did not settle"){val y=report().getJSONObject("state").getInt("listScrollY");stable=if(y==lastY)stable+1 else 0;lastY=y;stable>=3}
            }
            var current=tree("initial-offscreen-keyword-query")
            assertFalse("The specified target must start outside the viewport",target(current).getBoolean("visibleToUser"))
            val oldRef=container(current);val oldTreeId=current.data["treeId"]
            assertTrue("Initial query should report actual exposed offscreen nodes",(JSONObject(current.data).getJSONObject("coverage").getInt("offscreenExposedNodes"))>0)
            assertEquals(0,report().getJSONObject("state").getInt("listScrollY"))
            scrollAndObserve(current,"first-real-scroll")
            current=tree("fresh-tree-after-scroll")
            assertNotEquals(oldTreeId,current.data["treeId"]);assertNotEquals(oldRef,container(current))
            val beforeStaleY=report().getJSONObject("state").getInt("listScrollY")
            val stale=call("old-ref-against-current-snapshot","input.scroll",current.snapshotId,mapOf("nodeRef" to oldRef,"direction" to "forward","expectedPackage" to "dev.thor.inputlab"))
            assertEquals(Code.TARGET_CHANGED,stale.error);assertEquals("not_submitted",stale.outcome)
            assertTrue("Old-ref rejection must specifically validate node identity",stale.message.orEmpty().contains("Node ref"))
            assertEquals(beforeStaleY,report().getJSONObject("state").getInt("listScrollY"))
            var scrolls=1
            while(!target(current).getBoolean("visibleToUser")) {
                assertTrue("Target should become visible within bounded local fixture scrolling",scrolls<10)
                scrollAndObserve(current,"continue-real-scroll-$scrolls");scrolls++;current=tree("refresh-after-scroll-$scrolls")
            }
            val visibleState=report().getJSONObject("state");assertTrue(visibleState.getInt("listScrollY")>0);assertEquals(0,visibleState.getInt("articleOpenCount"))
            evidence.put("targetActuallyVisible",true).put("scrollCount",scrolls).put("visibleReceiverState",visibleState)
            // Ordinary app window must retain system-bar clipping. Do not hide bars or touch them.
            until(4,"Ordinary upper window must exclude the system-bar origin"){ThorAccessibilityService.instance!!.target(upper).touchableBounds?.contains(Point(0.0,0.0))==false}
            current=tree("fresh-ordinary-window-clip-observation")
            val clip=snapshots.validate(current.snapshotId).target.touchableBounds ?: error("Missing ordinary touchable bounds")
            assertFalse(clip.contains(Point(0.0,0.0)))
            val beforeClip=touchCount(report(),automation)
            val clipped=call("system-bar-origin-rejected","input.tap",current.snapshotId,mapOf("x" to 0.0,"y" to 0.0,"coordinateSpace" to "display_logical","expectedPackage" to "dev.thor.inputlab"))
            assertEquals(Code.TARGET_CHANGED,clipped.error);assertEquals("not_submitted",clipped.outcome)
            assertTrue("Rejection must specifically come from the observed/current clip guard",clipped.message.orEmpty().contains("observed/current touchable region"))
            assertEquals(beforeClip,touchCount(report(),automation));evidence.put("ordinaryTouchableBounds",clip.toString()).put("systemBarRejectedWithoutDispatch",true)
            val oldSnapshot=current.snapshotId
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(upper).toBundle())
            until(10,"Settings did not replace the upper application"){ThorAccessibilityService.instance!!.target(upper).let {it.validity=="valid" && it.packageName?.startsWith("com.android.settings")==true}}
            val switched=call("previous-app-snapshot-rejected","input.tap",oldSnapshot,mapOf("x" to 500,"y" to 500,"coordinateSpace" to "display_logical","expectedPackage" to "dev.thor.inputlab"))
            assertEquals(Code.TARGET_CHANGED,switched.error);assertEquals("not_submitted",switched.outcome)
            assertEquals(beforeClip,touchCount(report(),automation));evidence.put("changedAppRejectedWithoutDispatch",true)
            val final=receiver("release",params).getJSONObject("snapshot");sealed=true
            collect(final,automation,evidence,testRun,noAgent)
            evidence.put("passed",true)
        } catch(t:Throwable){failure=t;evidence.put("failure","${t.javaClass.simpleName}: ${t.message.orEmpty().take(2500)}")}
        finally {
            if(!sealed)receiverArgs?.let {params->runCatching {val final=receiver("release",params).getJSONObject("snapshot");collect(final,automation,evidence,testRun,noAgent)}.onFailure {evidence.put("collectionError","${it.javaClass.simpleName}: ${it.message}");if(failure==null)failure=it}}
            authority?.stop();grant?.let {backend?.release(it.run)}
            runCatching {
                before?.let {previous->val after=historyIds();val preserved=previous.all {(table,ids)->after.getValue(table).containsAll(ids)};evidence.put("historyPreserved",preserved);assertTrue("Existing user history must remain intact",preserved)}
                val profileUnchanged=profileBefore==profiles.all;val credentialsUnchanged=credentialsBefore==credentials.all
                evidence.put("profileConfigurationUnchanged",profileUnchanged).put("encryptedCredentialsUnchanged",credentialsUnchanged)
                assertTrue(profileUnchanged);assertTrue(credentialsUnchanged)
            }.onFailure {if(failure==null)failure=it;evidence.put("preservationError",it.javaClass.simpleName+": "+it.message)}
            activity?.let {screen->instrumentation.runOnMainSync {screen.finish()};instrumentation.waitForIdleSync()}
            runCatching {reader.close()};if(failure!=null)evidence.put("passed",false);save()
        }
        failure?.let {throw it}
    }
}
