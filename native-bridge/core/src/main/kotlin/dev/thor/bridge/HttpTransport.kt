package dev.thor.bridge

import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InterruptedIOException
import java.util.concurrent.*

/** Byte transport only: no model payload/SSE parser, cookies, cache, automatic redirect or retry. */
class HttpTransport(private val authority: Authority, private val policy: NetworkPolicy, val limits: Limits = Limits()): AutoCloseable {
    private companion object { const val MAX_ACTIVE_STREAMS = 8 }
    // Each accepted stream owns a worker until EOF (including consumer backpressure).
    // Match admission capacity so one long generation cannot queue another accepted run.
    private val workers = Executors.newFixedThreadPool(MAX_ACTIVE_STREAMS)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val active = ConcurrentHashMap<String, Stream>()
    private val seen = mutableSetOf<String>()
    private var seenRun: String? = null
    @Volatile private var closed = false
    private val subscription = authority.onChange { active.values.forEach { s -> try { authority.validateNetwork(s.start.envelope, false) } catch (e: BridgeFailure) { s.fail(e.code) } } }
    private inner class Stream(val start: HttpStart, val sink: EventSink, val client: OkHttpClient) {
        private val lock = authority as java.lang.Object
        var permits = 0
        var terminal = false
        var call: Call? = null
        var deadline: ScheduledFuture<*>? = null
        fun emit(event: HttpEvent) = synchronized(lock) {
            if (!terminal) authority.atomic { authority.validateNetwork(start.envelope, false); sink.emit(event) }
        }
        fun finish() = synchronized(lock) {
            if (!terminal) {
                authority.atomic { authority.validateNetwork(start.envelope, false); terminal = true; try { sink.emit(HttpEvent.End(start.envelope.requestId)) } finally { cleanup() } }
            }
        }
        fun fail(code: Code) = synchronized(lock) {
            if (!terminal) { terminal = true; call?.cancel(); lock.notifyAll(); try { sink.emit(HttpEvent.Error(start.envelope.requestId, code)) } finally { cleanup() } }
        }
        private fun cleanup() { deadline?.cancel(false); active.remove(start.envelope.requestId, this); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        fun credit(bytes: Int) = synchronized(lock) {
            if (!terminal) {
                requireBridge(bytes > 0 && bytes <= limits.windowBytes && permits + bytes <= limits.windowBytes, Code.LIMIT_EXCEEDED)
                permits += bytes; lock.notifyAll()
            }
        }
        fun allowance(): Int = synchronized(lock) {
            while (permits == 0 && !terminal) lock.wait()
            if (terminal) throw BridgeFailure(Code.CANCELLED)
            minOf(permits, limits.chunkBytes)
        }
        fun consume(count: Int) = synchronized(lock) { permits -= count }
        fun attachCall(value: Call) = synchronized(lock) { call = value; if (terminal) value.cancel() }
    }
    @Synchronized fun start(start: HttpStart, sink: EventSink) {
        requireBridge(!closed, Code.CANCELLED)
        authority.validateNetwork(start.envelope, true)
        requireBridge(start.envelope.requestId.matches(Regex("[a-zA-Z0-9_-]{1,100}")), Code.INVALID_REQUEST)
        if (seenRun != start.envelope.binding.runId) { seen.clear(); seenRun = start.envelope.binding.runId }
        requireBridge(start.envelope.requestId !in seen, Code.DUPLICATE_CALL)
        requireBridge(active.size < MAX_ACTIVE_STREAMS && seen.size < 512, Code.LIMIT_EXCEEDED)
        requireBridge((start.body?.size ?: 0) <= limits.requestBytes && start.method.matches(Regex("[A-Z]{1,12}")), Code.LIMIT_EXCEEDED)
        requireBridge(start.method !in setOf("GET", "HEAD") || start.body == null, Code.INVALID_REQUEST)
        val p = policy.prepare(start)
        val model=start.envelope.purpose==Purpose.model
        val client = OkHttpClient.Builder().dns(p.dns).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(limits.connectMs, TimeUnit.MILLISECONDS).readTimeout(if(model)0 else limits.idleMs, TimeUnit.MILLISECONDS)
            .writeTimeout(limits.idleMs, TimeUnit.MILLISECONDS).callTimeout(if(model)0 else limits.totalMs, TimeUnit.MILLISECONDS).build()
        val stream = Stream(start.copy(body = start.body?.copyOf(), headers = start.headers.toMap()), sink, client)
        seen.add(start.envelope.requestId); active[start.envelope.requestId] = stream
        val duration = minOf(if(model)Long.MAX_VALUE else limits.totalMs, start.envelope.deadlineMs - authority.clock.nowMs(), authority.remainingLeaseMs(start.envelope.binding))
        // JS uses its largest safe integer for an unbounded foreground deadline.
        if(duration<9_000_000_000_000_000L)stream.deadline = timer.schedule({ stream.fail(Code.TIMEOUT) }, duration, TimeUnit.MILLISECONDS)
        workers.execute {
            try {
                var url = p.url
                var headers = p.headers
                var redirects = 0
                while (true) {
                    val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                        .method(start.method, stream.start.body?.toRequestBody() ?: if (start.method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null).build()
                    val call = client.newCall(request)
                    authority.atomic { authority.validateNetwork(start.envelope, false); stream.attachCall(call) }
                    call.execute().use { response ->
                        val location = response.header("Location")
                        if (response.code in setOf(301, 302, 303, 307, 308) && location != null && p.profile.credentialRef == null && start.method in setOf("GET", "HEAD")) {
                            requireBridge(++redirects <= 5, Code.LIMIT_EXCEEDED)
                            val next = url.resolve(location) ?: throw BridgeFailure(Code.PERMISSION_MISSING)
                            // Do not forward caller headers across origins, even without stored credentials.
                            if(next.host != url.host || next.port != url.port || next.scheme != url.scheme) headers = emptyMap()
                            url = policy.validateUrl(p.profile, next.toString(), start.method, start.envelope.purpose)
                        } else {
                            requireBridge(response.headers.size <= 64 && (0 until response.headers.size).sumOf { response.headers.name(it).length + response.headers.value(it).length } <= 16384, Code.LIMIT_EXCEEDED)
                            val headers = response.headers.toMultimap().mapKeys { it.key.lowercase() }.filterKeys { it.lowercase() !in setOf("set-cookie", "authorization", "proxy-authenticate") }
                                .mapValues { (_, values) -> values.map { policy.vault.redact(it) } }
                            stream.emit(HttpEvent.Head(start.envelope.requestId, response.code, policy.vault.redact(response.message), headers, policy.vault.redact(response.request.url.toString())))
                            val source = response.body?.byteStream()
                            var count = 0L
                            var sequence = 0L
                            if (source != null && start.method != "HEAD") {
                                // Reading one lookahead byte detects EOF without demanding an extra consumer credit.
                                var lookahead = source.read()
                                while (lookahead != -1) {
                                    val buffer = ByteArray(stream.allowance())
                                    buffer[0] = lookahead.toByte()
                                    val n = if (buffer.size == 1) 0 else source.read(buffer, 1, buffer.size - 1).coerceAtLeast(0)
                                    val size = n + 1
                                    count += size
                                    requireBridge(count <= limits.responseBytes, Code.LIMIT_EXCEEDED)
                                    stream.consume(size)
                                    stream.emit(HttpEvent.Chunk(start.envelope.requestId, sequence++, buffer.copyOf(size)))
                                    lookahead = source.read()
                                }
                            }
                            stream.finish()
                            return@execute
                        }
                    }
                }
            } catch (e: BridgeFailure) { stream.fail(e.code) }
            catch (e: InterruptedIOException) { stream.fail(Code.TIMEOUT) }
            catch (_: Exception) { stream.fail(Code.IO_ERROR) }
        }
    }
    fun credit(requestId: String, binding: Binding, bytes: Int) {
        val s = active[requestId] ?: return
        requireBridge(s.start.envelope.binding == binding, Code.STALE_AUTHORIZATION)
        authority.validateNetwork(s.start.envelope, false)
        try { s.credit(bytes) } catch (e: BridgeFailure) { s.fail(e.code); throw e }
    }
    fun abort(requestId: String, binding: Binding) {
        val s = active[requestId] ?: return
        requireBridge(s.start.envelope.binding == binding, Code.STALE_AUTHORIZATION)
        s.fail(Code.CANCELLED)
    }
    @Synchronized override fun close() { closed = true; subscription.close(); active.values.toList().forEach { it.fail(Code.CANCELLED) }; workers.shutdownNow(); timer.shutdownNow() }
}
