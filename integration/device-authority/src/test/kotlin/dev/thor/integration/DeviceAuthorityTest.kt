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
}
