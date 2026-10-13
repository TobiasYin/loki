package dev.thor.tools

interface AuthorizationPort {
    fun validate(envelope: Envelope): Cancellation
    fun claim(e: Envelope)
    fun commit(envelope: Envelope, effect: () -> Unit)
}

/** Local manual TEST authority. Replace this port with Native's single capability store at integration. */
class ManualTestAuthority(private val clock: Clock) : AuthorizationPort {
    data class Lease(val session: String, val run: String, val epoch: Long, val tools: Set<String>, val deadline: Long, val cancel: Cancellation)
    private var epoch = 1L
    private var lease: Lease? = null
    private val requestIds = mutableSetOf<String>()
    private val callIds = mutableSetOf<String>()
    private val enabled = mutableSetOf(Capability.SCREEN_PERCEPTION, Capability.WEB_SEARCH)
    @Synchronized fun set(capability: Capability, value: Boolean) {
        if (value) enabled.add(capability) else enabled.remove(capability)
        epoch++
        stop()
    }
    @Synchronized fun enabled(capability: Capability) = capability in enabled
    @Synchronized fun begin(backend: DeviceBackend): Lease {
        requireTool(lease == null, Code.BACKEND_UNAVAILABLE, "Only one active device Run")
        val allowed = ToolPack.descriptors.values.filter { it.capability in enabled && backend.availability(it.toolId).status == Status.READY }.map { it.toolId }.toSet()
        requestIds.clear(); callIds.clear()
        return Lease("manual-test-session", java.util.UUID.randomUUID().toString(), epoch, allowed, clock.now() + 60_000, Cancellation()).also { lease = it }
    }
    @Synchronized fun check(e: Envelope): Lease {
        val current = lease ?: fail(Code.CANCELLED, "No active Run")
        requireTool(e.sessionId == current.session && e.runId == current.run, Code.STALE_AUTHORIZATION, "Run/Session binding does not match")
        requireTool(e.capabilityEpoch == epoch && e.capabilityEpoch == current.epoch, Code.STALE_AUTHORIZATION, "Old capability epoch")
        val descriptor = ToolPack.descriptors[e.toolId] ?: fail(Code.UNSUPPORTED, "Unknown tool")
        requireTool(e.schemaVersion == descriptor.schemaVersion, Code.UNSUPPORTED, "Unsupported schema version")
        requireTool(descriptor.capability in enabled, Code.CAPABILITY_DISABLED, "Capability disabled")
        requireTool(e.toolId in current.tools, Code.PERMISSION_MISSING, "Tool absent from signed effective set")
        requireTool(clock.now() < e.deadline && e.deadline <= current.deadline, Code.TIMEOUT, "Run/request deadline expired or exceeded")
        current.cancel.check()
        return current
    }
    @Synchronized override fun claim(e: Envelope) {
        check(e)
        requireTool(e.requestId.isNotBlank() && e.toolCallId.isNotBlank(), message = "Request/call IDs required")
        requireTool(e.requestId !in requestIds && e.toolCallId !in callIds, Code.STALE_AUTHORIZATION, "Replayed request/toolCall ID")
        requireTool(requestIds.size < 32, Code.LIMIT_EXCEEDED, "Run tool budget is 32 calls")
        requestIds.add(e.requestId); callIds.add(e.toolCallId)
    }
    @Synchronized override fun validate(envelope: Envelope): Cancellation = check(envelope).cancel
    @Synchronized override fun commit(envelope: Envelope, effect: () -> Unit) { check(envelope); effect() }
    @Synchronized fun stop() { lease?.cancel?.cancel(); lease = null }
    @Synchronized fun finish(run: String) { if (lease?.run == run) stop() }
}
class Gateway(private val authority: AuthorizationPort, private val backend: DeviceBackend,
    private val snapshots: Snapshots, private val clock: Clock) {
    private val resource = java.util.concurrent.locks.ReentrantLock()
    fun execute(e: Envelope, args: Map<String, Any?>): ToolResult {
        val availability = backend.availability(e.toolId, args)
        var cp: Checkpoint? = null
        try {
            val cancellation = authority.validate(e)
            cp = Checkpoint({
                authority.validate(e)
                val descriptor = ToolPack.descriptors.getValue(e.toolId)
                if (descriptor.targetRequired && !(descriptor.effect == Effect.INPUT && cp?.submitted == true)) snapshots.validate(e.snapshotId)
                val a = backend.availability(e.toolId, args)
                if (a.status != Status.READY) fail(if (a.status == Status.PERMISSION_MISSING) Code.PERMISSION_MISSING else Code.BACKEND_UNAVAILABLE, a.reason)
            }, cancellation) { action -> authority.commit(e) { action() } }
            requireTool(resource.tryLock(), Code.BACKEND_UNAVAILABLE, "Device resource busy")
            try {
                cp.check()
                authority.claim(e)
                val result = backend.execute(e, args, cp)
                cp.check() // Discard late perception output after stop/revoke/target invalidation.
                return result
            } finally { resource.unlock() }
        } catch (t: ToolFailure) {
            val preciseCode = if (t.code == Code.PERMISSION_MISSING && availability.status == Status.UNSUPPORTED) Code.UNSUPPORTED
                else if (t.code == Code.PERMISSION_MISSING && availability.status in setOf(Status.BACKEND_MISSING, Status.TEMPORARILY_UNAVAILABLE)) Code.BACKEND_UNAVAILABLE else t.code
            return ToolResult(clock.now(), availability.backend, availability, error = preciseCode,
                message = if (preciseCode != t.code) availability.reason else t.message,
                outcome = if (cp?.submitted == true) "unknown" else "not_submitted", snapshotId = e.snapshotId)
        } catch (t: Exception) {
            return ToolResult(clock.now(), availability.backend, availability, error = Code.IO_ERROR,
                message = "Backend failure: ${t.javaClass.simpleName}", outcome = if (cp?.submitted == true) "unknown" else "not_submitted")
        }
    }
}
