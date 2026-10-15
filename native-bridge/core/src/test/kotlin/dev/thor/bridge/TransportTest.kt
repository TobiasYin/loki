package dev.thor.bridge

import okhttp3.Dns
import okhttp3.mockwebserver.*
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.*

class TransportTest {
    @Test fun nativeSessionRoutingOverridesCallerHeaderAndIsProfileScoped() {
        val binding=Binding("native-session","run","lease","connection",1)
        val profile=NetworkProfile("go","https://opencode.ai",setOf("/zen/go/v1/responses"),setOf("POST"),setOf(Purpose.model),nativeSessionHeader="x-opencode-session")
        val start=HttpStart(NetworkEnvelope(1,"request",binding,Purpose.model,"go",1),"https://opencode.ai/zen/go/v1/responses",headers=mapOf("X-OpenCode-Session" to "caller-forged-session"))
        val policy=NetworkPolicy(listOf(profile),CredentialVault(emptyMap()))
        assertEquals("native-session",policy.prepare(start).headers["x-opencode-session"])
        assertEquals("another-native-session",policy.prepare(start.copy(envelope=start.envelope.copy(binding=binding.copy(sessionId="another-native-session")))).headers["x-opencode-session"])
        assertThrows(BridgeFailure::class.java) {policy.prepare(start.copy(envelope=start.envelope.copy(binding=binding.copy(sessionId="bad\r\nheader"))))}
        val ordinary=NetworkPolicy(listOf(profile.copy(nativeSessionHeader=null)),CredentialVault(emptyMap()))
        assertFalse(ordinary.prepare(start.copy(headers=emptyMap())).headers.containsKey("x-opencode-session"))
        assertThrows(IllegalArgumentException::class.java) {profile.copy(nativeSessionHeader="authorization")}
    }
    private class Lab(val limits: Limits = Limits(chunkBytes = 3), val credential: Boolean = false, tls: Boolean = false): AutoCloseable {
        val server = MockWebServer().apply {
            if (tls) {
                val certificate = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
                val certificates = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
                useHttps(certificates.sslSocketFactory(), false)
            }
            start()
        }
        val authority = Authority().apply { createSession("a") }
        val connection = authority.connect()
        val binding = authority.beginRun("a", connection, mapOf(Purpose.model to setOf("p"), Purpose.web_search to setOf("p")))
        val vault = CredentialVault(mapOf("key" to "secret-123"))
        val policy = NetworkPolicy(listOf(NetworkProfile("p", server.url("/").toString(), setOf("/test", "/next"), setOf("GET", "POST", "HEAD"), setOf(Purpose.model, Purpose.web_search), if (credential) "key" else null, if (credential) mapOf("X-Token" to "Token {{credential}}") else emptyMap())), vault)
        val transport = HttpTransport(authority, policy, limits)
        fun request(id: String = "r", purpose: Purpose = Purpose.model, body: ByteArray? = null, method: String = "GET") = HttpStart(NetworkEnvelope(1, id, binding, purpose, "p", authority.clock.nowMs() + limits.totalMs), server.url("/test").toString(), method, body = body, credentialRef = if (credential) "key" else null)
        override fun close() { transport.close(); server.shutdown() }
    }
    private class Events {
        val all = CopyOnWriteArrayList<HttpEvent>()
        val terminal = CountDownLatch(1)
        val head = CountDownLatch(1)
        fun sink(onEvent: (HttpEvent) -> Unit = {}) = EventSink { e -> all.add(e); if (e is HttpEvent.Head) head.countDown(); if (e is HttpEvent.End || e is HttpEvent.Error) terminal.countDown(); onEvent(e) }
        fun await() { assertTrue("terminal missing", terminal.await(4, TimeUnit.SECONDS)) }
        fun bytes() = all.filterIsInstance<HttpEvent.Chunk>().fold(ByteArray(0)) { acc, e -> acc + e.bytes }
    }
    @Test fun selfSignedTlsIsRejectedWithoutLeakingExceptionDetails() = Lab(tls = true).use { l ->
        l.server.enqueue(MockResponse().setBody("untrusted")); val e = Events(); l.transport.start(l.request(), e.sink()); e.await()
        assertEquals(Code.IO_ERROR, (e.all.last() as HttpEvent.Error).code); assertTrue(e.all.none { it is HttpEvent.Head || it is HttpEvent.Chunk })
    }
    @Test fun utf8RemainsBytesWithMonotonicChunks() = Lab().use { l ->
        val expected = "中文🙂\nnot a protocol".toByteArray()
        l.server.enqueue(MockResponse().setChunkedBody(Buffer().write(expected), 1))
        val e = Events()
        l.transport.start(l.request(), e.sink { event -> if (event is HttpEvent.Head) l.transport.credit("r", l.binding, 256) })
        e.await(); assertArrayEquals(expected, e.bytes())
        val chunks = e.all.filterIsInstance<HttpEvent.Chunk>(); assertEquals(chunks.indices.map(Int::toLong), chunks.map { it.sequence }); assertTrue(chunks.all { it.bytes.size <= 3 }); assertTrue(e.all.last() is HttpEvent.End)
    }
    @Test fun errorStatusStillDeliversHeadersAndBody() {
        for (status in listOf(400, 429, 500)) Lab().use { l ->
            l.server.enqueue(MockResponse().setResponseCode(status).addHeader("X-Repeat", "one").addHeader("X-Repeat", "two").setBody("error-$status"))
            val e = Events(); l.transport.start(l.request(), e.sink { if (it is HttpEvent.Head) l.transport.credit("r", l.binding, 256) }); e.await()
            assertEquals(status, e.all.filterIsInstance<HttpEvent.Head>().single().status); assertEquals("error-$status", e.bytes().toString(Charsets.UTF_8)); assertTrue(e.all.last() is HttpEvent.End)
        }
    }
    @Test fun creditControlsDeliveryAndAbortHasOneTerminal() = Lab().use { l ->
        l.server.enqueue(MockResponse().setBody("abcdefghij"))
        val e = Events(); val chunks = CountDownLatch(1); l.transport.start(l.request(), e.sink { if (it is HttpEvent.Chunk) chunks.countDown() }); assertTrue(e.head.await(2, TimeUnit.SECONDS))
        assertTrue(e.all.none { it is HttpEvent.Chunk }); l.transport.credit("r", l.binding, 2); assertTrue(chunks.await(2, TimeUnit.SECONDS)); assertEquals(2, e.bytes().size)
        l.transport.abort("r", l.binding); l.transport.abort("r", l.binding); e.await(); val count = e.all.size
        Thread.sleep(80); assertEquals(count, e.all.size); assertEquals(Code.CANCELLED, (e.all.last() as HttpEvent.Error).code)
        assertEquals(1, e.all.count { it is HttpEvent.End || it is HttpEvent.Error })
    }
    @Test fun stalledConsumerTimesOut() = Lab(Limits(chunkBytes = 3, totalMs = 200)).use { l ->
        l.server.enqueue(MockResponse().setBody("abc")); val e = Events(); l.transport.start(l.request(), e.sink()); e.await(); assertEquals(Code.TIMEOUT, (e.all.last() as HttpEvent.Error).code)
    }
    @Test fun responseLimitFailsExplicitly() = Lab(Limits(chunkBytes = 3, responseBytes = 4)).use { l ->
        l.server.enqueue(MockResponse().setBody("too big")); val e = Events(); l.transport.start(l.request(), e.sink { if (it is HttpEvent.Head) l.transport.credit("r", l.binding, 100) }); e.await(); assertEquals(Code.LIMIT_EXCEEDED, (e.all.last() as HttpEvent.Error).code); assertTrue(e.bytes().size <= 4)
    }
    @Test fun requestLimitAndDuplicateIdAreRejected() = Lab(Limits(chunkBytes = 3, requestBytes = 2)).use { l ->
        val fail = runCatching { l.transport.start(l.request(body = ByteArray(3), method = "POST"), Events().sink()) }.exceptionOrNull() as BridgeFailure
        assertEquals(Code.LIMIT_EXCEEDED, fail.code); assertEquals(0, l.server.requestCount)
        l.server.enqueue(MockResponse().setBody("")); val e = Events(); l.transport.start(l.request(), e.sink()); e.await()
        assertEquals(Code.DUPLICATE_CALL, (runCatching { l.transport.start(l.request(), e.sink()) }.exceptionOrNull() as BridgeFailure).code)
    }
    @Test fun gzipIsGenericAndHeadersMatchDecodedBody() = Lab().use { l ->
        val compressed = Buffer(); GzipSink(compressed).buffer().use { it.writeUtf8("中文 gzip") }
        l.server.enqueue(MockResponse().setBody(compressed).setHeader("Content-Encoding", "gzip"))
        val e = Events(); l.transport.start(l.request(), e.sink { if (it is HttpEvent.Head) l.transport.credit("r", l.binding, 100) }); e.await()
        assertEquals("中文 gzip", e.bytes().toString(Charsets.UTF_8)); assertTrue(e.all.filterIsInstance<HttpEvent.Head>().single().headers.keys.none { it.equals("Content-Encoding", true) || it.equals("Content-Length", true) })
    }
    @Test fun credentialsAreBoundRedactedAndNeverFollowRedirects() = Lab(credential = true).use { l ->
        MockWebServer().use { target -> target.start(); l.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/steal")).setHeader("X-Echo", "secret-123").setBody("redirect"))
            val e = Events(); l.transport.start(l.request(), e.sink { if (it is HttpEvent.Head) l.transport.credit("r", l.binding, 100) }); e.await()
            assertEquals("Token secret-123", l.server.takeRequest().getHeader("X-Token")); assertEquals(0, target.requestCount); assertEquals(302, e.all.filterIsInstance<HttpEvent.Head>().single().status)
            assertFalse(e.all.filterIsInstance<HttpEvent.Head>().single().headers.toString().contains("secret-123"))
        }
    }
    @Test fun redirectsRevalidatePathAndDoNotReplayPost() = Lab().use { l ->
        l.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/forbidden")); val e = Events(); l.transport.start(l.request(), e.sink()); e.await(); assertEquals(Code.PERMISSION_MISSING, (e.all.last() as HttpEvent.Error).code); assertEquals(1, l.server.requestCount)
        l.server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/next")); val p = Events(); l.transport.start(l.request("post", body = byteArrayOf(1), method = "POST"), p.sink()); p.await(); assertEquals(307, p.all.filterIsInstance<HttpEvent.Head>().single().status); assertEquals(2, l.server.requestCount)
    }
    @Test fun searchRevocationDoesNotCancelModelAndOldEpochCannotRestart() = Lab().use { l ->
        l.server.enqueue(MockResponse().setBody("model")); l.server.enqueue(MockResponse().setBody("search"))
        val model = Events(); val search = Events(); l.transport.start(l.request("m"), model.sink()); assertTrue(model.head.await(2, TimeUnit.SECONDS)); l.transport.start(l.request("s", Purpose.web_search), search.sink())
        assertTrue(model.head.await(2, TimeUnit.SECONDS)); assertTrue(search.head.await(2, TimeUnit.SECONDS))
        l.authority.setEnabled("a", Capability.web_search, false); search.await(); assertEquals(Code.STALE_AUTHORIZATION, (search.all.last() as HttpEvent.Error).code)
        l.transport.credit("m", l.binding, 100); model.await(); assertEquals("model", model.bytes().toString(Charsets.UTF_8)); assertTrue(model.all.last() is HttpEvent.End)
        assertEquals(Code.STALE_AUTHORIZATION, (runCatching { l.transport.start(l.request("new"), Events().sink()) }.exceptionOrNull() as BridgeFailure).code)
    }
    @Test fun disconnectCancelsAndForgedBindingCannotAbortAnotherRun() = Lab().use { l ->
        l.server.enqueue(MockResponse().setBody("abc")); val e = Events(); l.transport.start(l.request(), e.sink()); assertTrue(e.head.await(2, TimeUnit.SECONDS))
        assertEquals(Code.STALE_AUTHORIZATION, (runCatching { l.transport.abort("r", l.binding.copy(sessionId = "other")) }.exceptionOrNull() as BridgeFailure).code)
        l.authority.disconnect(l.connection); e.await(); assertEquals(Code.UNTRUSTED_BRIDGE, (e.all.last() as HttpEvent.Error).code)
    }
    @Test fun headResponseFinishesWithoutCredit() = Lab().use { l ->
        l.server.enqueue(MockResponse().setBody("")); val e = Events(); l.transport.start(l.request(method = "HEAD"), e.sink()); e.await(); assertTrue(e.all.last() is HttpEvent.End); assertEquals(0, e.bytes().size)
    }
    @Test fun abruptDisconnectIsTransportError() = Lab().use { l ->
        l.server.enqueue(MockResponse().setBody("abcdefghijklmnop").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)); val e = Events(); l.transport.start(l.request(), e.sink { if (it is HttpEvent.Head) l.transport.credit("r", l.binding, 100) }); e.await(); assertTrue(e.all.last() is HttpEvent.Error)
    }
    @Test fun endpointMethodCredentialAndHeaderScopeAreEnforced() = Lab(credential = true).use { l ->
        assertEquals("Token secret-123", l.policy.prepare(l.request().copy(headers = mapOf("x-token" to "Token {{credential}}"))).headers["x-token"])
        for (request in listOf(l.request().copy(url = l.server.url("/other").toString()), l.request().copy(method = "DELETE"), l.request().copy(credentialRef = "wrong"), l.request().copy(headers = mapOf("Host" to "evil")), l.request().copy(headers = mapOf("X-Token" to "bypass")), l.request().copy(url = l.server.url("/test?key=secret-123").toString()))) {
            assertEquals(Code.PERMISSION_MISSING, (runCatching { l.policy.prepare(request) }.exceptionOrNull() as BridgeFailure).code)
        }
    }
    @Test fun networkAllowsSystemDnsAndReachableLocalOrProxyDestinations() {
        val profile = NetworkProfile("crawl", null, emptySet(), setOf("GET"), setOf(Purpose.web_fetch))
        val answers=listOf("8.8.8.8","127.0.0.1","10.1.2.3","169.254.169.254","100.64.0.1","198.18.0.1","::1","fc00::1").map(InetAddress::getByName)
        val dns = object : Dns { override fun lookup(hostname: String) = answers }
        val policy = NetworkPolicy(listOf(profile), CredentialVault(emptyMap()), dns)
        val binding = Binding("a", "b", "c", "d", 1)
        val start = HttpStart(NetworkEnvelope(1, "id", binding, Purpose.web_fetch, "crawl", 1), "https://public.example/page", "GET")
        assertEquals(answers,policy.prepare(start).dns.lookup("public.example"))
        for(url in listOf("http://127.0.0.1/page","http://localhost/page","http://192.168.1.1/page","https://198.18.0.1/page","http://[::1]/page"))assertEquals(url,policy.prepare(start.copy(url=url)).url.toString())
    }
    @Test fun webFetchFollowsReachableHttpRedirectAcrossOriginsWithoutCredentials() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start();second.start()
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location",second.url("/lan-page")))
            second.enqueue(MockResponse().setBody("local page"))
            val authority=Authority().apply {createSession("local-web")}
            val binding=authority.beginRun("local-web",authority.connect(),mapOf(Purpose.web_fetch to setOf("crawl")))
            val policy=NetworkPolicy(listOf(NetworkProfile("crawl",null,emptySet(),setOf("GET"),setOf(Purpose.web_fetch))),CredentialVault(emptyMap()))
            HttpTransport(authority,policy).use { transport ->
                val start=HttpStart(NetworkEnvelope(1,"local-web-request",binding,Purpose.web_fetch,"crawl",authority.clock.nowMs()+5000),first.url("/redirect").toString(),"GET")
                val e=Events();transport.start(start,e.sink {if(it is HttpEvent.Head)transport.credit(start.envelope.requestId,binding,256)})
                e.await();assertEquals("local page",e.bytes().toString(Charsets.UTF_8));assertTrue(e.all.last() is HttpEvent.End)
                assertEquals(1,first.requestCount);assertEquals(1,second.requestCount)
                assertNull(second.takeRequest().getHeader("Authorization"))
            }
        } }
    }
}
