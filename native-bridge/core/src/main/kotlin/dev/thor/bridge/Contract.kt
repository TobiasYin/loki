package dev.thor.bridge

import java.util.UUID
import java.util.concurrent.TimeUnit

fun interface MonotonicClock { fun nowMs(): Long }
val SYSTEM_CLOCK = MonotonicClock { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }
internal fun nonce(): String = UUID.randomUUID().toString()
enum class Code { CAPABILITY_DISABLED, STALE_AUTHORIZATION, PERMISSION_MISSING, BACKEND_UNAVAILABLE, TARGET_CHANGED, UNSUPPORTED, TIMEOUT, CANCELLED, LIMIT_EXCEEDED, IO_ERROR, INVALID_REQUEST, UNTRUSTED_BRIDGE, DUPLICATE_CALL }
class BridgeFailure(val code: Code) : RuntimeException(code.name)
internal fun requireBridge(value: Boolean, code: Code) { if (!value) throw BridgeFailure(code) }
enum class Capability { screen_perception, web_search, screen_control, adb_debug }
enum class Purpose { model, web_search, web_fetch, configuration_test }
data class Binding(val sessionId: String, val runId: String, val lease: String, val connectionId: String, val capabilityEpoch: Long)
data class NetworkEnvelope(val bridgeVersion: Int = 1, val requestId: String, val binding: Binding, val purpose: Purpose, val profileRef: String, val deadlineMs: Long)
data class HttpStart(val envelope: NetworkEnvelope, val url: String, val method: String = "POST", val headers: Map<String, String> = emptyMap(), val body: ByteArray? = null, val credentialRef: String? = null)
data class Limits(val chunkBytes: Int = 32 * 1024, val windowBytes: Int = 256 * 1024, val requestBytes: Int = 16 * 1024 * 1024, val responseBytes: Long = 32L * 1024 * 1024, val connectMs: Long = 15_000, val idleMs: Long = 60_000, val totalMs: Long = 180_000) {
    init { require(chunkBytes in 1..32768 && windowBytes in chunkBytes..262144 && requestBytes in 0..16777216 && responseBytes in 1..33554432 && connectMs in 1..15000 && idleMs in 1..60000 && totalMs in 1..180000) }
}
sealed interface HttpEvent {
    val requestId: String
    data class Head(override val requestId: String, val status: Int, val statusText: String, val headers: Map<String, List<String>>, val url: String): HttpEvent
    data class Chunk(override val requestId: String, val sequence: Long, val bytes: ByteArray): HttpEvent
    data class End(override val requestId: String): HttpEvent
    data class Error(override val requestId: String, val code: Code): HttpEvent
}
fun interface EventSink { fun emit(event: HttpEvent) }
