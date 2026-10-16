package dev.thor.bridge

/** Sole authorization owner. Host calls mutation APIs; none are exposed to JS. */
class Authority(val clock: MonotonicClock = SYSTEM_CLOCK) {
    private data class Session(val enabled: MutableSet<Capability>, var epoch: Long = 1)
    private data class Run(val session: String, val id: String, val lease: String, val connection: String, val expires: Long, val profiles: Map<Purpose, Set<String>>, var alive: Boolean = true)
    private val sessions = mutableMapOf<String, Session>()
    private val connections = mutableSetOf<String>()
    private val runs = linkedMapOf<String, Run>()
    private val listeners = mutableListOf<() -> Unit>()
    @Synchronized fun connect(): String = nonce().also { connections.add(it) }
    @Synchronized fun createSession(id: String) { require(id !in sessions); sessions[id] = Session(mutableSetOf(Capability.screen_perception, Capability.web_search)) }
    @Synchronized fun restoreSession(id: String, enabled: Set<Capability>, epoch: Long) {
        require(id !in sessions && epoch >= 1); sessions[id] = Session(enabled.toMutableSet(), epoch)
    }
    @Synchronized fun sessionEpoch(id: String): Long = sessions.getValue(id).epoch
    @Synchronized fun sessionEnabled(id: String): Set<Capability> = sessions.getValue(id).enabled.toSet()
    @Synchronized fun beginRun(sessionId: String, connectionId: String, profiles: Map<Purpose, Set<String>>, durationMs: Long = 180_000): Binding {
        requireBridge(connectionId in connections, Code.UNTRUSTED_BRIDGE)
        requireBridge(runs.values.none { it.session == sessionId && it.alive && clock.nowMs() < it.expires }, Code.LIMIT_EXCEEDED)
        runs.entries.removeAll { !it.value.alive || clock.nowMs() >= it.value.expires }
        requireBridge(runs.size < 8, Code.LIMIT_EXCEEDED)
        // Zero is a host-selected foreground run without a wall-clock expiry.
        // Stop, disconnect and capability epochs still revoke it immediately.
        require(durationMs == 0L || durationMs in 1..600_000)
        val s = sessions[sessionId] ?: throw BridgeFailure(Code.STALE_AUTHORIZATION)
        val r = Run(sessionId, nonce(), nonce(), connectionId, if(durationMs==0L)Long.MAX_VALUE else clock.nowMs() + durationMs, profiles.mapValues { it.value.toSet() })
        runs[r.id] = r
        changed()
        return Binding(sessionId, r.id, r.lease, connectionId, s.epoch)
    }
    @Synchronized fun currentBinding(): Binding? = runs.values.lastOrNull { it.alive && clock.nowMs() < it.expires }?.let { Binding(it.session, it.id, it.lease, it.connection, sessions.getValue(it.session).epoch) }
    @Synchronized fun setEnabled(sessionId: String, capability: Capability, enabled: Boolean) {
        val s = sessions.getValue(sessionId)
        if ((capability in s.enabled) != enabled) {
            if (enabled) s.enabled.add(capability) else s.enabled.remove(capability)
            s.epoch++
            changed()
        }
    }
    @Synchronized fun invalidateResources(sessionId: String) { sessions.getValue(sessionId).epoch++; changed() }
    @Synchronized fun disconnect(connectionId: String) { connections.remove(connectionId); runs.values.filter { it.connection == connectionId }.forEach { it.alive = false }; changed() }
    @Synchronized fun stopRun(runId: String? = null) { runs.values.filter { runId == null || it.id == runId }.forEach { it.alive = false }; changed() }
    /** Capture exactly the existing work in one chat before a queued UI stop.
     * A later Run in that chat must never inherit an earlier stop request. */
    @Synchronized fun stopSessionRuns(sessionId:String,runId:String?=null):List<String> {
        val affected=runs.values.filter {it.session==sessionId && (runId==null || it.id==runId) && it.alive}.map {it.id}
        affected.forEach {runs.getValue(it).alive=false}
        if(affected.isNotEmpty())changed()
        return affected
    }
    @Synchronized fun onChange(listener: () -> Unit): AutoCloseable { listeners.add(listener); return AutoCloseable { synchronized(this) { listeners.remove(listener) } } }
    private fun changed() { listeners.toList().forEach { listener -> try { listener() } catch (_: Exception) { /* One observer cannot prevent the others from revoking. */ } } }
    @Synchronized fun validate(binding: Binding, strictEpoch: Boolean = true) {
        requireBridge(binding.connectionId in connections, Code.UNTRUSTED_BRIDGE)
        val r = runs[binding.runId] ?: throw BridgeFailure(Code.STALE_AUTHORIZATION)
        requireBridge(r.alive && r.session == binding.sessionId && r.id == binding.runId && r.lease == binding.lease && r.connection == binding.connectionId, Code.STALE_AUTHORIZATION)
        requireBridge(clock.nowMs() < r.expires, Code.TIMEOUT)
        if (strictEpoch) requireBridge(sessions.getValue(r.session).epoch == binding.capabilityEpoch, Code.STALE_AUTHORIZATION)
    }
    @Synchronized fun remainingLeaseMs(binding: Binding): Long { validate(binding, false); return runs.getValue(binding.runId).expires - clock.nowMs() }
    @Synchronized fun enabled(binding: Binding): Set<Capability> { validate(binding); return sessions.getValue(binding.sessionId).enabled.toSet() }
    @Synchronized fun validateNetwork(e: NetworkEnvelope, starting: Boolean) {
        requireBridge(e.bridgeVersion == 1, Code.UNSUPPORTED)
        // Model transport continues across a search toggle; new requests still need the latest epoch.
        validate(e.binding, starting || e.purpose != Purpose.model)
        requireBridge(clock.nowMs() < e.deadlineMs, Code.TIMEOUT)
        requireBridge(e.profileRef in runs.getValue(e.binding.runId).profiles[e.purpose].orEmpty(), Code.PERMISSION_MISSING)
        if (e.purpose == Purpose.web_search || e.purpose == Purpose.web_fetch) requireBridge(Capability.web_search in sessions.getValue(e.binding.sessionId).enabled, Code.CAPABILITY_DISABLED)
    }
    /** The authorization lock stays held across a short commit; long executors use one checkpoint per step. */
    @Synchronized fun <T> atomic(block: () -> T): T = block()
}
