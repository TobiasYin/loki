package dev.thor.integration

import dev.thor.bridge.Authority
import dev.thor.bridge.Binding
import dev.thor.bridge.Capability
import dev.thor.bridge.MonotonicClock
import dev.thor.tools.*
import org.junit.Test
import org.junit.Assert.*

class DeviceAuthorityTest {
    private var now = 1000L
    private val native = Authority(MonotonicClock { now }).apply { createSession("a"); createSession("b") }
    private val port = DeviceAuthority(native)
    private fun begin(): Binding = native.beginRun("a", native.connect(), emptyMap()).also { port.bind(it); port.publish(it, setOf("perception.device_info")) }
    private fun e(b: Binding, call: String = "c") = Envelope(b.sessionId, b.runId, call, "perception.device_info", b.capabilityEpoch, now + 5000, requestId = call)
    private fun rejected(action: () -> Unit) { try { action(); fail("Expected rejection") } catch(_: ToolFailure) { } }
    @Test fun oneToolTimeoutDoesNotRevokeItsRunOrOtherTools() {
        val b=begin();val first=e(b,"first");val next=e(b,"next")
        val ca=port.validate(first);val cb=port.validate(next)
        port.cancelTool(b.runId,first.requestId)
        assertTrue(ca.cancelled);assertFalse(cb.cancelled);native.validate(b)
        rejected {port.commit(first){fail("Cancelled call must not commit")}}
        var committed=false;port.commit(next){committed=true};assertTrue(committed)
        native.stopRun(b.runId);assertTrue(cb.cancelled)
    }
    @Test fun concurrentRunsKeepPublicationCancellationAndReplayLedgersSeparate() {
        val a=begin();val b=native.beginRun("b",a.connectionId,emptyMap());port.bind(b);port.publish(b,setOf("perception.device_info"))
        val ca=port.validate(e(a));val cb=port.validate(e(b));port.claim(e(a));port.claim(e(b))
        rejected {port.validate(e(a).copy(sessionId="b"))}
        native.stopRun(a.runId);port.stop(a.runId);assertTrue(ca.cancelled);assertFalse(cb.cancelled)
        var effects=0;port.commit(e(b,"next")){effects++};assertEquals(1,effects)
        rejected {port.claim(e(b))};rejected {port.validate(e(a))}
    }
    @Test fun realGatewayUsesNativeCapabilitySourceAndRejectsOffOn() {
        val b = begin(); val cancel = port.validate(e(b)); var effects = 0
        native.setEnabled("a", Capability.screen_perception, false)
        native.setEnabled("a", Capability.screen_perception, true)
        assertTrue(cancel.cancelled); rejected { port.commit(e(b)) { effects++ } }; assertEquals(0, effects)
        native.stopRun(); val fresh = begin(); port.commit(e(fresh)) { effects++ }; assertEquals(1, effects)
    }
    @Test fun crossSessionForgedRunSchemaAndDeadlineFail() {
        val b = begin(); val good = e(b)
        rejected { port.validate(good.copy(sessionId = "b")) }; rejected { port.validate(good.copy(runId = "forged")) }
        rejected { port.validate(good.copy(schemaVersion = 2)) }; rejected { port.validate(good.copy(deadline = now)) }
        rejected { port.validate(good.copy(deadline = now + 200000)) }
    }
    @Test fun unpublishedToolAndReplayNeverCommit() {
        val b = begin(); port.claim(e(b)); rejected { port.claim(e(b)) }
        rejected { port.validate(e(b).copy(toolId = "perception.ui_tree")) }
        native.stopRun(); rejected { port.commit(e(b)) { fail("Must not execute") } }
    }
    @Test fun lateDeviceReadIsDiscardedByRealGateway() {
        val b = begin(); val backend = object : DeviceBackend {
            override fun availability(toolId: String) = Availability(Status.READY, "test", "actual-port-test")
            override fun release(runId: String) = "none"
            override fun execute(envelope: Envelope, args: Map<String,Any?>, checkpoint: Checkpoint): ToolResult {
                native.setEnabled("a", Capability.screen_perception, false)
                return ToolResult(now, "test", availability(envelope.toolId), mapOf("secret" to "must-drop"))
            }
        }
        val gateway = Gateway(port, backend, Snapshots(Clock { now }) { Target(null,"unknown","0") }, Clock { now })
        val result = gateway.execute(e(b), emptyMap()); assertNotNull(result.error); assertTrue(result.data.isEmpty())
    }
    @Test fun nativeDisconnectCancelsAndCannotRevive() {
        val b = begin(); val cancellation = port.validate(e(b)); native.disconnect(b.connectionId)
        assertTrue(cancellation.cancelled); rejected { port.validate(e(b)) }
    }
    @Test fun queuedCommitRevocationRaceOneHundredTimesHasZeroEffects() {
        var effects=0
        repeat(100) { index ->
            native.stopRun();val b=begin();val envelope=e(b,"race-$index")
            val cancellation=port.validate(envelope);port.claim(envelope)
            val admitted=java.util.concurrent.CountDownLatch(1);val released=java.util.concurrent.CountDownLatch(1)
            var rejected=false
            val worker=Thread {
                val cp=Checkpoint({port.validate(envelope)},cancellation) {action ->port.commit(envelope,action)}
                admitted.countDown();released.await()
                try {cp.effect {effects++}}catch(_:ToolFailure){rejected=true}
            }.apply {start()}
            assertTrue(admitted.await(1,java.util.concurrent.TimeUnit.SECONDS))
            native.setEnabled("a",Capability.screen_perception,false);native.setEnabled("a",Capability.screen_perception,true)
            released.countDown();worker.join(1000);assertFalse(worker.isAlive);assertTrue(rejected)
        }
        assertEquals(0,effects)
    }
    @Test fun publicationCannotSwitchBindingOrAddUnknownTool() {
        val b = begin(); try { port.publish(b.copy(runId="fake"), setOf("debug.shell")); fail("forgery") } catch(_: RuntimeException) { }
        rejected { port.validate(e(b).copy(toolId="unknown")) }
    }
    @Test fun eightySequentialGatewayReadsReleaseCancellationResourcesButKeepReplayProtection() {
        val b=begin();var reads=0
        val backend=object:DeviceBackend {
            override fun availability(toolId:String)=Availability(Status.READY,"test","read-fixture")
            override fun release(runId:String)="none"
            override fun execute(envelope:Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult {
                checkpoint.check();reads++
                return ToolResult(now,"read-fixture",availability(envelope.toolId),mapOf("read" to reads))
            }
        }
        val gateway=Gateway(port,backend,Snapshots(Clock {now}) {Target(null,"unknown","0")},Clock {now})
        repeat(80) {index->
            val request=e(b,"read-$index");val cancellation=port.validate(request)
            val result=gateway.execute(request,emptyMap());assertNull("read $index: ${result.error}",result.error)
            assertEquals(index+1,result.data["read"]);port.finishTool(b.runId,request.requestId)
            assertTrue(cancellation.cancelled)
            val replay=gateway.execute(request,emptyMap());assertEquals(Code.STALE_AUTHORIZATION,replay.error)
        }
        assertEquals(80,reads)
        rejected {port.claim(e(b,"new-request").copy(toolCallId="read-0"))}
        native.validate(b)
    }
    @Test fun finishedCheckpointCannotSubmitLateEffectsAndFinishingOneCallKeepsOtherCallsLive() {
        val b=begin();val first=e(b,"first");val second=e(b,"second")
        val firstCancellation=port.validate(first);val secondCancellation=port.validate(second)
        port.claim(first);var effects=0
        val checkpoint=Checkpoint({port.validate(first)},firstCancellation) {action->port.commit(first,action)}
        port.finishTool(b.runId,first.requestId)
        rejected {checkpoint.effect {effects++}}
        assertEquals(0,effects);assertFalse(secondCancellation.cancelled)
        port.commit(second){effects++};assertEquals(1,effects)
        native.stopRun(b.runId);assertTrue(secondCancellation.cancelled)
    }
    @Test fun targetSelectionIsRecheckedAtCommitAndOtherRunsRemainIndependent() {
        val first=begin();val second=native.beginRun("b",first.connectionId,emptyMap()).also {port.bind(it);port.publish(it,setOf("perception.device_info"))}
        val selected=java.util.concurrent.ConcurrentHashMap<String,String>().apply {put(first.runId,"upper");put(second.runId,"upper")}
        port.targetScopeValidator={request->requireTool(request.snapshotId==selected[request.runId],Code.TARGET_CHANGED,"Screen selection changed")}
        val a=e(first,"prepared-a").copy(snapshotId="upper")
        val b=e(second,"prepared-b").copy(snapshotId="upper")
        val cancellation=port.validate(a);port.claim(a)
        val checkpoint=Checkpoint({port.validate(a)},cancellation) {effect->port.commit(a,effect)}
        selected[first.runId]="lower"
        var effects=0
        assertEquals(Code.TARGET_CHANGED,assertThrows(ToolFailure::class.java){checkpoint.effect {effects++}}.code)
        assertEquals(0,effects)
        port.commit(b){effects++};assertEquals(1,effects)
        port.commit(e(first,"fresh-lower").copy(snapshotId="lower")){effects++};assertEquals(2,effects)
    }

    @Test fun targetSelectionWriteWaitsForValidatedCommitThenRejectsTheOldRole() {
        val binding=begin()
        val selected=java.util.concurrent.atomic.AtomicReference("upper")
        val validated=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val pause=java.util.concurrent.atomic.AtomicBoolean(true)
        val failures=java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val order=java.util.concurrent.ConcurrentLinkedQueue<String>()
        port.targetScopeValidator={request->
            requireTool(request.snapshotId==selected.get(),Code.TARGET_CHANGED,"Screen selection changed")
            if(pause.getAndSet(false)) {
                validated.countDown()
                check(release.await(5,java.util.concurrent.TimeUnit.SECONDS)) {"Commit was not released"}
            }
        }
        val old=e(binding,"old-upper").copy(snapshotId="upper")
        val commit=Thread({try {
            port.commit(old) {assertEquals("upper",selected.get());order.add("effect")}
        }catch(t:Throwable){failures.add(t)}},"validated-device-commit")
        val selection=Thread({try {
            // Mirrors ProductHost's selection publication under the authority
            // lock, not just a volatile write to the current Run's role.
            native.atomic {selected.set("lower");order.add("selection")}
        }catch(t:Throwable){failures.add(t)}},"publish-screen-selection")
        try {
            commit.start()
            assertTrue(validated.await(5,java.util.concurrent.TimeUnit.SECONDS))
            selection.start()
            // Prove actual monitor contention while validation is paused. This
            // does not infer exclusion from an arbitrary sleep or fast timeout.
            val threads=java.lang.management.ManagementFactory.getThreadMXBean()
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            var blocked=false
            while(System.nanoTime()<deadline && !blocked && selection.isAlive) {
                val info=threads.getThreadInfo(selection.id)
                blocked=info?.threadState==Thread.State.BLOCKED && info.lockOwnerId==commit.id
                if(!blocked)Thread.yield()
            }
            assertTrue("Selection must contend on the live commit's authority monitor",blocked)
            assertEquals("upper",selected.get());assertTrue(order.isEmpty())
        } finally {
            release.countDown();commit.join(5000)
            if(selection.state!=Thread.State.NEW)selection.join(5000)
        }
        assertFalse(commit.isAlive);assertFalse(selection.isAlive)
        assertTrue("Worker failures: $failures",failures.isEmpty())
        assertEquals(listOf("effect","selection"),order.toList())
        assertEquals("lower",selected.get())
        assertEquals(Code.TARGET_CHANGED,assertThrows(ToolFailure::class.java){
            port.commit(old){fail("Old-role effect must not execute after selection")}
        }.code)
        port.commit(e(binding,"fresh-lower").copy(snapshotId="lower")){order.add("lower-effect")}
        assertEquals(listOf("effect","selection","lower-effect"),order.toList())
    }

}
