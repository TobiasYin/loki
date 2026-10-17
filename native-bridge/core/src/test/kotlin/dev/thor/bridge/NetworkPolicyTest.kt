package dev.thor.bridge

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Config identity, rather than provider/hostname, owns every Native credential. */
class NetworkPolicyTest {
    private val credentialA = "fixture-account-a-secret"
    private val credentialB = "fixture-account-b-secret"
    private fun profile(ref: String, origin: String = "https://api.example.test", path: String = "/v1/chat/completions", go: Boolean = false) =
        NetworkProfile(ref, origin, setOf(path), setOf("POST"), setOf(Purpose.model, Purpose.configuration_test),
            "$ref:key", mapOf("Authorization" to "Bearer {{credential}}"), if (go) "x-opencode-session" else null)
    private fun request(authority: Authority, binding: Binding, profile: NetworkProfile, id: String = "request") = HttpStart(
        NetworkEnvelope(1, id, binding, Purpose.model, profile.ref, authority.clock.nowMs() + 10_000),
        profile.origin!! + profile.paths.single(), credentialRef = profile.credentialRef)
    private class Runs {
        val authority = Authority().apply { createSession("chat-a"); createSession("chat-b") }
        val connection = authority.connect()
        fun begin(session: String, profile: NetworkProfile) = authority.beginRun(session, connection, mapOf(Purpose.model to setOf(profile.ref)))
    }

    @Test fun sameHostAndPathKeepEachSavedConfigurationCredentialIndependent() {
        val first = profile("config-a:completions")
        val second = profile("config-b:completions")
        val policy = NetworkPolicy(listOf(first, second), CredentialVault(mapOf(first.credentialRef!! to credentialA, second.credentialRef!! to credentialB)))
        val runs = Runs()
        val a = runs.begin("chat-a", first); val b = runs.begin("chat-b", second)
        val requestA = request(runs.authority, a, first)
        val requestB = request(runs.authority, b, second)
        runs.authority.validateNetwork(requestA.envelope, true)
        runs.authority.validateNetwork(requestB.envelope, true)
        assertEquals("Bearer $credentialA", policy.prepare(requestA).headers["authorization"])
        assertEquals("Bearer $credentialB", policy.prepare(requestB).headers["authorization"])
        assertEquals("Bearer $credentialA", policy.prepare(requestA).headers["authorization"])
    }

    @Test fun knowingAnotherProfileAndCredentialReferenceDoesNotGrantItsRunAuthority() {
        val first = profile("config-a:completions"); val second = profile("config-b:completions")
        val runs = Runs(); val a = runs.begin("chat-a", first); val b = runs.begin("chat-b", second)
        val cross = request(runs.authority, a, second)
        assertEquals(Code.PERMISSION_MISSING, assertThrows(BridgeFailure::class.java) { runs.authority.validateNetwork(cross.envelope, true) }.code)
        assertEquals(Code.PERMISSION_MISSING, assertThrows(BridgeFailure::class.java) { runs.authority.validateNetwork(cross.envelope, false) }.code)
        runs.authority.validateNetwork(request(runs.authority, b, second).envelope, true)
    }

    @Test fun changingOnlyCredentialReferenceCannotBorrowAnotherSavedKey() {
        val first = profile("config-a:completions"); val second = profile("config-b:completions")
        val policy = NetworkPolicy(listOf(first, second), CredentialVault(mapOf(first.credentialRef!! to credentialA, second.credentialRef!! to credentialB)))
        val runs = Runs(); val a = runs.begin("chat-a", first)
        val original = request(runs.authority, a, first)
        for (foreignRef in listOf(second.credentialRef, null, "unknown:key")) {
            assertEquals(Code.PERMISSION_MISSING, assertThrows(BridgeFailure::class.java) { policy.prepare(original.copy(credentialRef = foreignRef)) }.code)
        }
    }

    @Test fun goAndDeepSeekKeepOriginCredentialAndSessionRoutingSeparate() {
        val go = profile("go-account:responses", "https://opencode.ai", "/zen/go/v1/responses", go = true)
        val deepseek = profile("deepseek-account:completions", "https://api.deepseek.com", "/chat/completions")
        val policy = NetworkPolicy(listOf(go, deepseek), CredentialVault(mapOf(go.credentialRef!! to credentialA, deepseek.credentialRef!! to credentialB)))
        val runs = Runs(); val a = runs.begin("chat-a", go); val b = runs.begin("chat-b", deepseek)
        val goRequest = request(runs.authority, a, go); val deepseekRequest = request(runs.authority, b, deepseek)
        val preparedGo = policy.prepare(goRequest)
        val preparedDeepseek = policy.prepare(deepseekRequest)
        assertEquals("Bearer $credentialA", preparedGo.headers["authorization"])
        assertEquals("chat-a", preparedGo.headers["x-opencode-session"])
        assertEquals("Bearer $credentialB", preparedDeepseek.headers["authorization"])
        assertFalse(preparedDeepseek.headers.containsKey("x-opencode-session"))
        assertEquals(Code.PERMISSION_MISSING, assertThrows(BridgeFailure::class.java) { policy.prepare(goRequest.copy(url = deepseekRequest.url)) }.code)
        assertEquals(Code.PERMISSION_MISSING, assertThrows(BridgeFailure::class.java) { policy.prepare(deepseekRequest.copy(url = goRequest.url)) }.code)
    }

    @Test fun endingOneProviderRunDoesNotRevokeAnotherAndNextRunCanSwitchInSameChat() {
        val first = profile("config-a:completions"); val second = profile("config-b:completions")
        val runs = Runs(); val a = runs.begin("chat-a", first); val b = runs.begin("chat-b", second)
        runs.authority.stopRun(a.runId)
        val switched = runs.begin("chat-a", second)
        assertEquals(a.capabilityEpoch, switched.capabilityEpoch)
        runs.authority.validateNetwork(request(runs.authority, b, second).envelope, true)
        runs.authority.validateNetwork(request(runs.authority, switched, second).envelope, true)
        assertEquals(Code.STALE_AUTHORIZATION, assertThrows(BridgeFailure::class.java) { runs.authority.validateNetwork(request(runs.authority, a, first).envelope, true) }.code)
        assertEquals(Code.PERMISSION_MISSING, assertThrows(BridgeFailure::class.java) { runs.authority.validateNetwork(request(runs.authority, switched, first).envelope, true) }.code)
    }

    @Test fun parallelTransportUsesTheCredentialBoundToEachRunEvenOnTheSameEndpoint() {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse().setBody("ok")) }
            val origin = server.url("/").toString().removeSuffix("/")
            val first = profile("account-a", origin); val second = profile("account-b", origin)
            val runs = Runs(); val a = runs.begin("chat-a", first); val b = runs.begin("chat-b", second)
            val policy = NetworkPolicy(listOf(first, second), CredentialVault(mapOf(first.credentialRef!! to credentialA, second.credentialRef!! to credentialB)))
            HttpTransport(runs.authority, policy).use { transport ->
                val terminals = CountDownLatch(2)
                val events = CopyOnWriteArrayList<HttpEvent>()
                fun sink(binding: Binding) = EventSink { event ->
                    events.add(event)
                    if (event is HttpEvent.Head) transport.credit(event.requestId, binding, 128)
                    if (event is HttpEvent.End || event is HttpEvent.Error) terminals.countDown()
                }
                val cross = request(runs.authority, a, second, "cross")
                assertEquals(Code.PERMISSION_MISSING, assertThrows(BridgeFailure::class.java) { transport.start(cross, sink(a)) }.code)
                transport.start(request(runs.authority, a, first, "account-a-request").copy(headers = mapOf("X-Test-Account" to "a")), sink(a))
                transport.start(request(runs.authority, b, second, "account-b-request").copy(headers = mapOf("X-Test-Account" to "b")), sink(b))
                assertTrue("both provider streams must complete", terminals.await(4, TimeUnit.SECONDS))
                assertEquals(2, events.count { it is HttpEvent.End })
                assertFalse(events.any { it is HttpEvent.Error })
                val requests = (0 until 2).map { server.takeRequest(1, TimeUnit.SECONDS)!! }.associateBy { it.getHeader("X-Test-Account") }
                assertEquals("Bearer $credentialA", requests.getValue("a").getHeader("Authorization"))
                assertEquals("Bearer $credentialB", requests.getValue("b").getHeader("Authorization"))
                assertEquals(2, server.requestCount)
            }
        }
    }
}
