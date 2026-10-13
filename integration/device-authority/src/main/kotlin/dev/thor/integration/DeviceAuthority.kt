package dev.thor.integration

import dev.thor.bridge.Authority
import dev.thor.bridge.Binding
import dev.thor.bridge.BridgeFailure
import dev.thor.tools.*

/** Device executor mirror; all decisions and short commits belong to Native Authority. */
class DeviceAuthority(private val native: Authority, private val refreshResources: () -> Unit = {}) : AuthorizationPort, AutoCloseable {
    private var binding: Binding? = null
    private var allowed = emptySet<String>()
    private var cancellation = Cancellation()
    private val requests = mutableSetOf<String>()
    private val calls = mutableSetOf<String>()
    private val observer = native.onChange {
        binding?.let { b -> try { native.validate(b) } catch (_: BridgeFailure) { cancellation.cancel() } }
    }
    fun bind(b: Binding) = native.atomic {
        native.validate(b); cancellation.cancel(); cancellation = Cancellation()
        binding = b; allowed = emptySet(); requests.clear(); calls.clear()
    }
    fun publish(b: Binding, tools: Set<String>) = native.atomic {
        native.validate(b); requireTool(b == binding, Code.STALE_AUTHORIZATION, "Binding changed")
        allowed = tools.toSet()
    }
    override fun validate(envelope: Envelope): Cancellation = native.atomic {
        refreshResources()
        val b = binding ?: fail(Code.STALE_AUTHORIZATION, "No device lease")
        try { native.validate(b) } catch (e: BridgeFailure) { fail(runCatching { Code.valueOf(e.code.name) }.getOrDefault(Code.STALE_AUTHORIZATION), "Native lease invalid") }
        requireTool(envelope.sessionId == b.sessionId && envelope.runId == b.runId && envelope.capabilityEpoch == b.capabilityEpoch,
            Code.STALE_AUTHORIZATION, "Session/Run/epoch mismatch")
        val t = ToolPack.descriptors[envelope.toolId] ?: fail(Code.UNSUPPORTED, "Unknown tool")
        requireTool(envelope.schemaVersion == t.schemaVersion, Code.UNSUPPORTED, "Schema mismatch")
        val cap = dev.thor.bridge.Capability.valueOf(t.capability.name.lowercase())
        requireTool(cap in native.enabled(b), Code.CAPABILITY_DISABLED, "Capability disabled")
        requireTool(envelope.toolId in allowed, Code.PERMISSION_MISSING, "Tool not published")
        val now = native.clock.nowMs()
        requireTool(envelope.deadline > now && envelope.deadline <= now + native.remainingLeaseMs(b), Code.TIMEOUT, "Invalid deadline")
        cancellation.check(); cancellation
    }
    override fun claim(e: Envelope) = native.atomic {
        validate(e)
        requireTool(e.requestId.length in 1..100 && e.toolCallId.length in 1..512, Code.INVALID_ARGUMENT, "Invalid IDs")
        requireTool(e.requestId !in requests && e.toolCallId !in calls, Code.STALE_AUTHORIZATION, "Duplicate call")
        requireTool(requests.size < 32, Code.LIMIT_EXCEEDED, "Call budget exceeded")
        requests.add(e.requestId); calls.add(e.toolCallId); Unit
    }
    override fun commit(envelope: Envelope, effect: () -> Unit) = native.atomic { validate(envelope); effect() }
    fun stop() = native.atomic { cancellation.cancel(); allowed = emptySet(); binding = null }
    override fun close() { stop(); observer.close() }
}
