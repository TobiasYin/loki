package dev.thor.bridge

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class GatewayTest {
    private class Lab(executor: ToolExecutor = ToolExecutor { c, p -> c.step {}; p["x"].toString() }, timeout: Long = 2000, resultBytes: Int = 65536): AutoCloseable {
        val authority = Authority().apply { createSession("a"); createSession("b"); setEnabled("a", Capability.screen_control, true) }
        val connection = authority.connect()
        val binding = authority.beginRun("a", connection, emptyMap())
        @Volatile var snapshot = "s1"
        @Volatile var ready = true
        val gateway = ToolGateway(authority, listOf(ToolDescriptor("mock.tap", 1, setOf(Capability.screen_control), mapOf("x" to ParamRule(ParamType.INTEGER, min = 0, max = 100)), setOf("display:1"), "input", timeout, resultBytes, { ready }, executor)), { mapOf("display:1" to snapshot) })
        init { gateway.effectiveTools(binding) }
        fun call(id: String = "c", b: Binding = binding) = ToolCall(id, b, id, "mock.tap", 1, authority.clock.nowMs() + 5000, mapOf("x" to 50), "display:1", "s1")
        fun result(call: ToolCall) = gateway.submit(call).get(3, TimeUnit.SECONDS)
        override fun close() { gateway.close() }
    }
    @Test fun knownCredentialValuesCannotEnterToolParametersAndResultsAreRedacted() {
        val authority = Authority(); authority.createSession("s"); val b = authority.beginRun("s", authority.connect(), emptyMap())
        val vault = CredentialVault(mapOf("key" to "secret-123"))
        val descriptor = ToolDescriptor("mock.echo", 1, emptySet(), mapOf("message" to ParamRule(ParamType.STRING)), setOf("fixture"), executor = ToolExecutor { _, _ -> "secret-123" })
        ToolGateway(authority, listOf(descriptor), { mapOf("fixture" to "snapshot") }, vault::redact).use { gateway ->
            gateway.effectiveTools(b)
            val c = ToolCall("r", b, "c", "mock.echo", 1, authority.clock.nowMs() + 1000, mapOf("message" to "secret-123"), "fixture", "snapshot")
            assertEquals(Code.PERMISSION_MISSING, gateway.submit(c).get().code)
            assertEquals("[REDACTED]", gateway.submit(c.copy(params = mapOf("message" to "public"))).get().text)
        }
    }
    @Test fun pendingUiDeliveryHasIndependentPerRequestAndGlobalBudgets() {
        val budget = DeliveryBudget(4, 6); val a = budget.reserve("a", 4); val b = budget.reserve("b", 2)
        assertEquals(Code.LIMIT_EXCEEDED, (runCatching { budget.reserve("a", 1) }.exceptionOrNull() as BridgeFailure).code)
        assertEquals(Code.LIMIT_EXCEEDED, (runCatching { budget.reserve("c", 1) }.exceptionOrNull() as BridgeFailure).code)
        a.close(); a.close(); budget.reserve("b", 2).close(); b.close(); budget.reserve("a", 4).close()
    }
    @Test fun trustedOriginMainFrameAndConnectionAreMandatory() {
        assertTrue(BridgePeerPolicy.accepts(BridgePeerPolicy.ORIGIN, true, true, BridgePeerPolicy.PAGE))
        for (origin in listOf("http://appassets.androidplatform.net", "https://evil.example", "https://appassets.androidplatform.net.evil", "null", "https://appassets.androidplatform.net:443")) assertFalse(BridgePeerPolicy.accepts(origin, true, true, BridgePeerPolicy.PAGE))
        assertFalse(BridgePeerPolicy.accepts(BridgePeerPolicy.ORIGIN, false, true, BridgePeerPolicy.PAGE)); assertFalse(BridgePeerPolicy.accepts(BridgePeerPolicy.ORIGIN, true, false, BridgePeerPolicy.PAGE)); assertFalse(BridgePeerPolicy.accepts(BridgePeerPolicy.ORIGIN, true, true, "https://evil.example"))
        Lab().use { l -> assertEquals(Code.UNTRUSTED_BRIDGE, l.result(l.call(b = l.binding.copy(connectionId = "forged"))).code) }
    }
    @Test fun defaultsAndSessionIsolation() {
        val a = Authority(); a.createSession("one"); a.createSession("two"); val c = a.connect(); val one = a.beginRun("one", c, emptyMap())
        assertEquals(setOf(Capability.screen_perception, Capability.web_search), a.enabled(one)); a.setEnabled("two", Capability.web_search, false); assertTrue(Capability.web_search in a.enabled(one))
        val two=a.beginRun("two",c,emptyMap());a.validate(one);a.validate(two)
        assertEquals(Code.LIMIT_EXCEEDED,(runCatching {a.beginRun("one",c,emptyMap())}.exceptionOrNull() as BridgeFailure).code)
        a.stopRun(one.runId);assertEquals(Code.STALE_AUTHORIZATION,(runCatching {a.validate(one)}.exceptionOrNull() as BridgeFailure).code);a.validate(two)
    }
    @Test fun concurrentNetworkLeasesAndRevocationStaySessionScoped() {
        val a=Authority();a.createSession("a");a.createSession("b");val c=a.connect()
        val first=a.beginRun("a",c,mapOf(Purpose.model to setOf("first")))
        val second=a.beginRun("b",c,mapOf(Purpose.model to setOf("second")))
        a.validateNetwork(NetworkEnvelope(1,"a",first,Purpose.model,"first",a.clock.nowMs()+1000),true)
        a.validateNetwork(NetworkEnvelope(1,"b",second,Purpose.model,"second",a.clock.nowMs()+1000),true)
        assertEquals(Code.PERMISSION_MISSING,(runCatching {a.validateNetwork(NetworkEnvelope(1,"cross",first,Purpose.model,"second",a.clock.nowMs()+1000),true)}.exceptionOrNull() as BridgeFailure).code)
        a.setEnabled("a",Capability.web_search,false);a.validate(second)
        a.stopRun(first.runId);a.validate(second);a.disconnect(c)
        assertEquals(Code.UNTRUSTED_BRIDGE,(runCatching {a.validate(second)}.exceptionOrNull() as BridgeFailure).code)
    }
    @Test fun forgedSessionLeaseUnknownToolAndSchemaFailClosed() = Lab().use { l ->
        assertEquals(Code.STALE_AUTHORIZATION, l.result(l.call(b = l.binding.copy(sessionId = "b"))).code)
        assertEquals(Code.STALE_AUTHORIZATION, l.result(l.call(b = l.binding.copy(lease = "fake"))).code)
        assertEquals(Code.UNSUPPORTED, l.result(l.call().copy(toolId = "unknown.tool")).code)
        assertEquals(Code.UNSUPPORTED, l.result(l.call().copy(schemaVersion = 2)).code)
    }
    @Test fun parameterAndResourceValidation() = Lab().use { l ->
        for (params in listOf(emptyMap(), mapOf("x" to 101), mapOf("x" to "50"), mapOf("x" to 50, "shell" to "rm"), mapOf("x" to 1.5))) assertEquals(Code.INVALID_REQUEST, l.result(l.call().copy(params = params)).code)
        assertEquals(Code.PERMISSION_MISSING, l.result(l.call().copy(resource = "display:2")).code); assertEquals(Code.TARGET_CHANGED, l.result(l.call().copy(snapshotId = "old")).code)
        l.ready = false; assertTrue(l.gateway.effectiveTools(l.binding).isEmpty()); assertEquals(Code.BACKEND_UNAVAILABLE, l.result(l.call()).code)
    }
    @Test fun disableReenableNeverResurrectsOldCallsAndDuplicateDoesNotReplay() = Lab().use { l ->
        assertNull(l.result(l.call()).code); assertEquals(Code.DUPLICATE_CALL, l.result(l.call()).code)
        l.authority.setEnabled("a", Capability.screen_control, false); l.authority.setEnabled("a", Capability.screen_control, true)
        assertEquals(Code.STALE_AUTHORIZATION, l.result(l.call("old")).code)
        val fresh = l.authority.currentBinding()!!; assertTrue(fresh.capabilityEpoch > l.binding.capabilityEpoch); l.gateway.effectiveTools(fresh); assertNull(l.result(l.call("new", fresh)).code)
    }
    @Test fun queuedCallIsRecheckedBeforeAnyEffect() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val effects = AtomicInteger()
        Lab(ToolExecutor { c, _ -> entered.countDown(); release.await(2, TimeUnit.SECONDS); c.step { effects.incrementAndGet() }; "done" }).use { l ->
            val first = l.gateway.submit(l.call("first")); assertTrue(entered.await(2, TimeUnit.SECONDS)); val queued = l.gateway.submit(l.call("queued"))
            l.authority.setEnabled("a", Capability.screen_control, false); l.authority.setEnabled("a", Capability.screen_control, true); release.countDown()
            assertEquals(Code.STALE_AUTHORIZATION, first.get().code); assertEquals(Code.STALE_AUTHORIZATION, queued.get().code); assertEquals(0, effects.get())
        }
    }
    @Test fun runningSequenceStopsAfterRevocationAndReportsSubmittedEffects() {
        val firstStep = CountDownLatch(1); val release = CountDownLatch(1); val effects = AtomicInteger()
        Lab(ToolExecutor { c, _ -> c.step { effects.incrementAndGet() }; firstStep.countDown(); release.await(2, TimeUnit.SECONDS); c.step { effects.incrementAndGet() }; "done" }).use { l ->
            val f = l.gateway.submit(l.call()); assertTrue(firstStep.await(2, TimeUnit.SECONDS)); l.authority.setEnabled("a", Capability.screen_control, false)
            val result = f.get(); release.countDown(); assertEquals(Code.STALE_AUTHORIZATION, result.code); assertEquals("submitted", result.outcome); assertEquals(1, effects.get())
        }
    }
    @Test fun targetChangeAndBackendLossBetweenAdmissionAndCommitBlockEffects() {
        for (change in listOf<(Lab) -> Unit>({ it.snapshot = "s2" }, { it.ready = false })) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val effects = AtomicInteger()
            Lab(ToolExecutor { c, _ -> entered.countDown(); release.await(2, TimeUnit.SECONDS); c.step { effects.incrementAndGet() }; "done" }).use { l ->
                val f = l.gateway.submit(l.call()); assertTrue(entered.await(2, TimeUnit.SECONDS)); change(l); release.countDown(); assertNotNull(f.get().code); assertEquals(0, effects.get())
            }
        }
    }
    @Test fun deadlinesAndStoppedLeaseBlockWork() = Lab().use { l ->
        assertEquals(Code.TIMEOUT, l.result(l.call().copy(deadlineMs = l.authority.clock.nowMs())).code)
        l.authority.stopRun(); assertEquals(Code.STALE_AUTHORIZATION, l.result(l.call()).code)
        val newer = l.authority.beginRun("a", l.connection, emptyMap()); assertNotEquals(l.binding.lease, newer.lease); assertEquals(Code.STALE_AUTHORIZATION, l.result(l.call()).code)
    }
    @Test fun outputHasUtf8BudgetAndAsyncTimeout() {
        Lab(ToolExecutor { c, _ -> c.check(); "中文🙂" }, resultBytes = 4).use { l -> val r = l.result(l.call()); assertTrue(r.truncated); assertEquals("中", r.text); assertEquals("observed", r.outcome) }
        val block = CountDownLatch(1)
        Lab(ToolExecutor { c, _ -> block.await(1, TimeUnit.SECONDS); c.step {}; "late" }, timeout = 80).use { l -> val r = l.result(l.call()); block.countDown(); assertEquals(Code.TIMEOUT, r.code); assertEquals("observed", r.outcome) }
    }
    @Test fun boundedQueueRefusesExcessWork() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Lab(ToolExecutor { _, _ -> entered.countDown(); release.await(2, TimeUnit.SECONDS); "done" }).use { l ->
            val first = l.gateway.submit(l.call("first")); assertTrue(entered.await(2, TimeUnit.SECONDS))
            val queue = (0..31).map { l.gateway.submit(l.call("queued$it")) }
            assertEquals(Code.LIMIT_EXCEEDED, l.result(l.call("overflow")).code); l.authority.stopRun(); release.countDown(); first.get(); queue.forEach { it.get() }
        }
    }

    @Test fun toolOmittedFromPublishedSnapshotCannotAppearJustBecauseBackendRecovers() = Lab().use { l ->
        l.ready = false; assertTrue(l.gateway.effectiveTools(l.binding).isEmpty()); l.ready = true
        assertEquals(Code.PERMISSION_MISSING, l.result(l.call()).code)
        assertEquals(setOf("mock.tap"), l.gateway.effectiveTools(l.binding)); assertNull(l.result(l.call()).code)
    }
    @Test fun executorFailureAfterCommitReportsUnknownAndRedactsOutput() {
        Lab(ToolExecutor { c, _ -> c.step { throw IllegalStateException("secret data") }; "unreachable" }).use { l ->
            val r = l.result(l.call()); assertEquals(Code.IO_ERROR, r.code); assertEquals("unknown", r.outcome); assertEquals("IO_ERROR", r.text)
        }
    }
    @Test fun expiredLeaseAndRendererDisconnectCannotResumeOldRun() {
        var now = 0L; val a = Authority(MonotonicClock { now }); a.createSession("a"); val c = a.connect(); val b = a.beginRun("a", c, emptyMap(), 10)
        now = 10; assertEquals(Code.TIMEOUT, (runCatching { a.validate(b) }.exceptionOrNull() as BridgeFailure).code)
        a.disconnect(c); assertEquals(Code.UNTRUSTED_BRIDGE, (runCatching { a.validate(b) }.exceptionOrNull() as BridgeFailure).code)
        val c2 = a.connect(); val b2 = a.beginRun("a", c2, emptyMap()); assertNotEquals(b.lease, b2.lease)
    }
}
