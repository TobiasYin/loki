package dev.thor.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryStore : NativeStorePort {
    var persisted = JSONObject()
    override fun load() = JSONObject(persisted.toString())
    override fun save(state: JSONObject) { persisted = JSONObject(state.toString()) }
}
class CoordinatorTest {
    @Test fun defaultsAreSessionScopedAndSurviveRecreation() {
        val db=MemoryStore();val c=Coordinator(db);val a=c.state.getString("selected")
        assertEquals(listOf(true,true,false,false), capabilities.map { c.session(a).getJSONObject("capabilities").getBoolean(it) })
        c.capability(a,"debug",true);val b=c.newSession();assertFalse(c.session(b).getJSONObject("capabilities").getBoolean("debug"))
        val restored=Coordinator(db);assertTrue(restored.session(a).getJSONObject("capabilities").getBoolean("debug"));assertEquals(b,restored.state.getString("selected"))
    }
    @Test fun successPersistsSeparateOrderedToolPairsAndCollapses() {
        val c=Coordinator(MemoryStore());val rid=c.start(c.state.getString("selected"),"hello","success")
        SimulatedRuntime().plan("hello","success").forEach { c.step(rid,it) }
        val r=c.runs().single();assertEquals("completed",r.getString("status"));assertFalse(r.getBoolean("expanded"))
        val starts=c.events().filter { it.getString("type")=="tool_start" };assertEquals(2,starts.size)
        starts.forEach { start -> assertEquals(1,c.events().count { it.optString("toolCallId")==start.getString("toolCallId") && it.getString("type")=="tool_result" }) }
        assertEquals((1..c.events().size).toList(),c.events().map { it.getInt("sequence") })
        assertEquals(2,c.state.getJSONArray("messages").length())
    }
    @Test fun switchingViewDoesNotStopOrRebindRunAndSecondRunRejected() {
        val c=Coordinator(MemoryStore());val a=c.state.getString("selected");val rid=c.start(a,"A","success");val b=c.newSession();c.select(b)
        assertEquals(a,c.active()!!.getString("sessionId"))
        assertThrows(IllegalArgumentException::class.java) { c.start(b,"B","success") }
        c.step(rid,Step("delta","still A"));assertEquals(a,c.state.getJSONArray("messages").getJSONObject(1).getString("sessionId"))
    }
    @Test fun revokeAndReenableCannotReviveLeaseOrAcceptLateToken() {
        val c=Coordinator(MemoryStore());val sid=c.state.getString("selected");val rid=c.start(sid,"test","success")
        c.step(rid,Step("start",tool="read_ui"));c.capability(sid,"perception",false);c.capability(sid,"perception",true)
        assertNull(c.active());assertFalse(c.step(rid,Step("delta","late")))
        assertEquals("cancelled",c.runs().single().getString("status"));assertTrue(c.events().any { it.optString("outcome")=="unknown" })
        assertEquals("",c.state.getJSONArray("messages").getJSONObject(1).getString("text"))
    }
    @Test fun processRecoveryInterruptsAndDoesNotReplayIntent() {
        val db=MemoryStore();val c=Coordinator(db);val rid=c.start(c.state.getString("selected"),"test","success");c.step(rid,Step("start",tool="app_info"))
        val restored=Coordinator(db);assertNull(restored.active());assertEquals("interrupted",restored.runs().single().getString("status"))
        assertEquals(1,restored.events().count { it.getString("type")=="tool_start" });assertTrue(restored.events().any { it.optString("outcome")=="unknown" })
        val n=restored.events().size;Coordinator(db);assertEquals(n,Coordinator(db).events().size)
    }
    @Test fun waitRequiresExactApprovalAndDisabledCapabilityFails() {
        val c=Coordinator(MemoryStore());val sid=c.state.getString("selected");c.capability(sid,"screen_control",true)
        val rid=c.start(sid,"change","waiting");c.step(rid,Step("wait","specific change","write_config","screen_control"))
        assertFalse(c.step(rid,Step("start",tool="write_config",capability="screen_control")))
        assertEquals("awaiting_user",c.active()!!.getString("status"));c.approve(rid);c.step(rid,Step("start",tool="write_config",capability="screen_control"));c.cancel(rid)
        assertThrows(IllegalArgumentException::class.java) { c.approve(rid) }
        val denied=c.start(sid,"deny","success");c.capability(sid,"perception",false);assertFalse(c.step(denied,Step("start",tool="app_info")))
    }
    @Test fun failureAndUnknownAreTerminalAndKeepAuditableSummary() {
        for(scenario in listOf("failure","unknown")) {
            val c=Coordinator(MemoryStore());val sid=c.state.getString("selected");c.capability(sid,"screen_control",true)
            val rid=c.start(sid,"test",scenario)
            SimulatedRuntime().plan("test",scenario).forEach { step -> if(c.active()?.getString("status")=="awaiting_user")c.approve(rid);c.step(rid,step) }
            assertEquals("failed",c.runs().single().getString("status"));assertTrue(c.runs().single().getString("stopReason").isNotBlank())
            assertTrue(c.events().any { it.optString("outcome")==if(scenario=="unknown")"unknown" else "failed" })
        }
    }
    @Test fun archiveDeleteAndRestorePreserveOtherSessions() {
        val c=Coordinator(MemoryStore());val a=c.state.getString("selected");val b=c.newSession();c.rename(a,"renamed");c.archive(a);c.archive(a,false);assertEquals("renamed",c.session(a).getString("title"))
        val rid=c.start(a,"test","success");assertThrows(IllegalArgumentException::class.java) {c.archive(a)};c.cancel(rid);c.delete(a)
        assertEquals(b,c.sessions().single().getString("id"));assertEquals(0,c.runs().size);assertEquals(0,c.events().size);assertEquals(0,c.state.getJSONArray("messages").length())
    }
    @Test fun historyAndLongAnswerAreBoundedWithoutLosingMessages() {
        val c=Coordinator(MemoryStore());c.seedHistory();assertEquals(50,c.sessions().size);assertEquals(200,c.state.getJSONArray("messages").length())
        val rid=c.start(c.state.getString("selected"),"long","long");SimulatedRuntime().plan("long","long").forEach {c.step(rid,it)}
        assertTrue(c.state.getJSONArray("messages").getJSONObject(201).getString("text").length>3000);assertEquals("completed",c.runs().single().getString("status"))
    }
    @Test fun manualDisclosureDoesNotChangeHistoryAndNewRunOnlyCollapsesItself() {
        val c=Coordinator(MemoryStore());val sid=c.state.getString("selected");val first=c.start(sid,"a","success");c.cancel(first);c.expanded(first,true)
        val second=c.start(sid,"b","success");c.cancel(second);assertTrue(c.runs()[0].getBoolean("expanded"));assertFalse(c.runs()[1].getBoolean("expanded"));assertEquals(4,c.state.getJSONArray("messages").length())
    }
    @Test fun oversizedInputAndOutputRejectWithoutFalseCompletion() {
        val c=Coordinator(MemoryStore());val sid=c.state.getString("selected");assertThrows(IllegalArgumentException::class.java){c.start(sid,"x".repeat(8001),"long")}
        val rid=c.start(sid,"test","long");c.step(rid,Step("delta","x".repeat(65537)));assertEquals("failed",c.runs().single().getString("status"))
    }
    @Test fun queuedToolAfterEpochChangeFailsEvenIfAnotherCapabilityWasEnabled() {
        val c=Coordinator(MemoryStore());val sid=c.state.getString("selected");val rid=c.start(sid,"test","success");c.capability(sid,"debug",true)
        c.step(rid,Step("start",tool="app_info"));assertEquals("failed",c.runs().single().getString("status"));assertFalse(c.events().any {it.getString("type")=="tool_start"})
    }
    @Test fun bridgePolicyRejectsForeignOriginsSubframesNavigationAndOversizedPayload() {
        assertTrue(BridgePolicy.accepts(BridgePolicy.ORIGIN,true,BridgePolicy.PAGE,10))
        assertFalse(BridgePolicy.accepts("https://evil.invalid",true,BridgePolicy.PAGE,10))
        assertFalse(BridgePolicy.accepts(BridgePolicy.ORIGIN,false,BridgePolicy.PAGE,10))
        assertFalse(BridgePolicy.accepts(BridgePolicy.ORIGIN,true,"https://evil.invalid",10))
        assertFalse(BridgePolicy.accepts(BridgePolicy.ORIGIN,true,BridgePolicy.PAGE,16385))
    }
    @Test fun failedPersistenceRollsBackDomainMutation() {
        val delegate=MemoryStore(); var fail=false
        val store=object : NativeStorePort {
            override fun load()=delegate.load()
            override fun save(state:JSONObject) { if(fail)throw IllegalStateException("disk full");delegate.save(state) }
        }
        val c=Coordinator(store);val sid=c.state.getString("selected");fail=true
        assertThrows(PersistenceFailure::class.java){c.start(sid,"test","success")}
        assertNull(c.active());assertEquals(0,c.state.getJSONArray("messages").length())
    }
    @Test fun timeoutUsesMonotonicTimeAndForegroundExitInterrupts() {
        var mono=100L;val c=Coordinator(MemoryStore(),clock={1000L},monotonic={mono});val rid=c.start(c.state.getString("selected"),"test","long")
        mono+=600001;c.step(rid,Step("delta","late"));assertEquals("failed",c.runs().single().getString("status"))
        val next=c.start(c.state.getString("selected"),"new","long");c.interrupt(next,"background");assertEquals("interrupted",c.runs().last().getString("status"))
    }

    @Test fun deviceExecutesOnceAndAttachmentFailureDoesNotCommitSuccess() {
        var calls=0
        val device=object:DevicePort { override fun availability(capability:String)="demo";override fun execute(tool:String):String { calls++;return "result" } }
        val files=object:AttachmentPort { override fun createText(sessionId:String,runId:String?,text:String):JSONObject { throw IllegalStateException("disk full") } }
        val c=Coordinator(MemoryStore(),device,attachments=files);val rid=c.start(c.state.getString("selected"),"test","success")
        c.step(rid,Step("start",tool="app_info"));assertThrows(IllegalStateException::class.java){c.step(rid,Step("result",tool="app_info"))}
        c.cancel(rid,"attachment failed");assertEquals(1,calls);assertFalse(c.events().any{it.optString("outcome")=="completed"});assertTrue(c.events().any{it.optString("outcome")=="unknown"})
    }

}
