package dev.thor.integration

import dev.thor.bridge.Authority
import dev.thor.bridge.Binding
import dev.thor.bridge.BridgeFailure
import dev.thor.tools.*

/** Device executor mirror; all decisions and short commits belong to Native Authority. */
class DeviceAuthority(private val native: Authority, private val refreshResources: () -> Unit = {}) : AuthorizationPort, AutoCloseable {
    @Volatile var targetScopeValidator:(Envelope)->Unit={}
    private class Scope(val binding:Binding) {
        var allowed=emptySet<String>()
        val cancellation=Cancellation()
        val toolCancellations=mutableMapOf<String,Cancellation>()
        val requests=mutableSetOf<String>()
        val calls=mutableSetOf<String>()
        val finishedRequests=mutableSetOf<String>()
        fun cancel() {cancellation.cancel();toolCancellations.values.toList().forEach {it.cancel()}}
    }
    private val scopes=mutableMapOf<String,Scope>()
    private val observer=native.onChange {
        scopes.values.forEach { scope -> try {native.validate(scope.binding)} catch(_:BridgeFailure) {scope.cancel()} }
    }
    fun bind(b:Binding)=native.atomic {
        native.validate(b);scopes.remove(b.runId)?.cancel();scopes[b.runId]=Scope(b)
    }
    fun publish(b:Binding,tools:Set<String>)=native.atomic {
        native.validate(b);val scope=scopes[b.runId] ?: fail(Code.STALE_AUTHORIZATION,"No device lease")
        requireTool(b==scope.binding,Code.STALE_AUTHORIZATION,"Binding changed");scope.allowed=tools.toSet()
    }
    override fun validate(envelope: Envelope): Cancellation = native.atomic {
        refreshResources()
        val scope=scopes[envelope.runId] ?: fail(Code.STALE_AUTHORIZATION,"No device lease")
        val b=scope.binding
        try { native.validate(b) } catch (e: BridgeFailure) { fail(runCatching { Code.valueOf(e.code.name) }.getOrDefault(Code.STALE_AUTHORIZATION), "Native lease invalid") }
        requireTool(envelope.sessionId == b.sessionId && envelope.runId == b.runId && envelope.capabilityEpoch == b.capabilityEpoch,
            Code.STALE_AUTHORIZATION, "Session/Run/epoch mismatch")
        val t = ToolPack.descriptors[envelope.toolId] ?: fail(Code.UNSUPPORTED, "Unknown tool")
        requireTool(envelope.schemaVersion == t.schemaVersion, Code.UNSUPPORTED, "Schema mismatch")
        val cap = dev.thor.bridge.Capability.valueOf(t.capability.name.lowercase())
        requireTool(cap in native.enabled(b), Code.CAPABILITY_DISABLED, "Capability disabled")
        requireTool(envelope.toolId in scope.allowed, Code.PERMISSION_MISSING, "Tool not published")
        targetScopeValidator(envelope)
        val now = native.clock.nowMs()
        requireTool(envelope.deadline > now && envelope.deadline <= now + native.remainingLeaseMs(b), Code.TIMEOUT, "Invalid deadline")
        scope.cancellation.check()
        requireTool(envelope.requestId.length in 1..100 && envelope.toolCallId.length in 1..512,Code.INVALID_ARGUMENT,"Invalid IDs")
        requireTool(envelope.requestId !in scope.finishedRequests,Code.STALE_AUTHORIZATION,"Tool invocation already finished")
        requireTool(scope.finishedRequests.size<4096,Code.LIMIT_EXCEEDED,"Run tool-call budget exhausted")
        val child=scope.toolCancellations.getOrPut(envelope.requestId) {
            requireTool(scope.toolCancellations.size<64,Code.LIMIT_EXCEEDED,"Tool cancellation budget exceeded")
            Cancellation()
        }
        child.check();child
    }
    /** A tool timeout cancels only that invocation; user Stop/revocation still cancels the Run. */
    fun cancelTool(runId:String,requestId:String)=native.atomic {
        scopes[runId]?.toolCancellations?.get(requestId)?.cancel();Unit
    }
    override fun claim(e: Envelope) = native.atomic {
        validate(e)
        val scope=scopes.getValue(e.runId);val requests=scope.requests;val calls=scope.calls
        requireTool(e.requestId.length in 1..100 && e.toolCallId.length in 1..512, Code.INVALID_ARGUMENT, "Invalid IDs")
        requireTool(e.requestId !in requests && e.toolCallId !in calls, Code.STALE_AUTHORIZATION, "Duplicate call")
        requireTool(requests.size < 4096, Code.LIMIT_EXCEEDED, "Run tool-call budget exhausted")
        requests.add(e.requestId); calls.add(e.toolCallId); Unit
    }
    /** Release per-invocation cancellation resources without forgetting replay IDs.
     * Finishing also cancels a captured checkpoint, preventing detached late effects. */
    fun finishTool(runId:String,requestId:String)=native.atomic {
        scopes[runId]?.let {scope->scope.toolCancellations.remove(requestId)?.let {child->
            scope.finishedRequests.add(requestId);child.cancel()
        }};Unit
    }
    override fun commit(envelope: Envelope, effect: () -> Unit) = native.atomic { validate(envelope); effect() }
    fun stop(runId:String?=null) = native.atomic { val ids=scopes.keys.filter {runId==null || it==runId};ids.forEach {scopes.remove(it)?.cancel()};Unit }
    override fun close() { stop(); observer.close() }
}
