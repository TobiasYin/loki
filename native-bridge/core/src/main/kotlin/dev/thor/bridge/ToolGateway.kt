package dev.thor.bridge

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

enum class ParamType { STRING, INTEGER, BOOLEAN }
data class ParamRule(val type: ParamType, val required: Boolean = true, val maxLength: Int = 256, val min: Long = 0, val max: Long = 1000)
data class ToolDescriptor(val toolId: String, val schemaVersion: Int, val requiredCapabilities: Set<Capability>, val parameters: Map<String, ParamRule>, val resources: Set<String>, val effectClass: String = "read", val timeoutMs: Long = 5000, val maxResultBytes: Int = 65536, val prerequisite: () -> Boolean = { true }, val executor: ToolExecutor) {
    init { require(toolId.matches(Regex("[a-z][a-z0-9_.]{1,80}")) && schemaVersion == 1 && resources.isNotEmpty() && effectClass in setOf("read", "network", "input", "write", "arbitrary-shell") && timeoutMs in 1..180000 && maxResultBytes in 1..65536) }
}
data class ToolCall(val requestId: String, val binding: Binding, val toolCallId: String, val toolId: String, val schemaVersion: Int, val deadlineMs: Long, val params: Map<String, Any>, val resource: String, val snapshotId: String)
data class ToolResult(val requestId: String, val code: Code?, val text: String, val observedAt: Long, val source: String, val truncated: Boolean = false, val outcome: String = "observed")
fun interface ToolExecutor { fun execute(context: ExecutionContext, params: Map<String, Any>): String }
class ExecutionContext internal constructor(private val checkpoint: (Boolean, () -> Unit) -> Unit) {
    /** Every read/side effect segment goes here, including the final commit. No raw device executor is bridged. */
    fun step(action: () -> Unit) = checkpoint(true, action)
    fun check() = checkpoint(false) {}
}
/** Bounded serial queue. Closing/re-enabling a capability never revives an old admitted call. */
class ToolGateway(private val authority: Authority, descriptors: List<ToolDescriptor>, private val resourceSnapshots: () -> Map<String, String>, private val sanitize: (String) -> String = { it }): AutoCloseable {
    private val tools = descriptors.map { it.copy(requiredCapabilities = it.requiredCapabilities.toSet(), parameters = it.parameters.toMap(), resources = it.resources.toSet()) }.associateBy { it.toolId }
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32))
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val seen = mutableSetOf<String>()
    private val seenRequests = mutableSetOf<String>()
    private var publishedRun: String? = null
    private var publishedEpoch: Long = 0
    private var publishedTools: Set<String> = emptySet()
    private var runId: String? = null
    private var closed = false
    init { require(tools.size == descriptors.size) }
    /** Native publishes this set to Runtime before model requests; JS cannot register its own tool names. */
    fun effectiveTools(binding: Binding): Set<String> = authority.atomic {
        val set = computeTools(binding)
        publishedRun = binding.runId; publishedEpoch = binding.capabilityEpoch; publishedTools = set
        set
    }
    private fun computeTools(binding: Binding): Set<String> = authority.atomic {
        val enabled = authority.enabled(binding)
        tools.values.filter { enabled.containsAll(it.requiredCapabilities) && it.prerequisite() && it.resources.any { r -> r in resourceSnapshots() } }.map { it.toolId }.toSet()
    }
    private fun validate(call: ToolCall): ToolDescriptor {
        authority.validate(call.binding)
        requireBridge(authority.clock.nowMs() < call.deadlineMs, Code.TIMEOUT)
        val d = tools[call.toolId] ?: throw BridgeFailure(Code.UNSUPPORTED)
        requireBridge(d.schemaVersion == call.schemaVersion, Code.UNSUPPORTED)
        requireBridge(authority.enabled(call.binding).containsAll(d.requiredCapabilities), Code.CAPABILITY_DISABLED)
        requireBridge(d.prerequisite(), Code.BACKEND_UNAVAILABLE)
        requireBridge(publishedRun == call.binding.runId && publishedEpoch == call.binding.capabilityEpoch && call.toolId in publishedTools && call.toolId in computeTools(call.binding), Code.PERMISSION_MISSING)
        requireBridge(call.resource in d.resources, Code.PERMISSION_MISSING)
        requireBridge(resourceSnapshots()[call.resource] == call.snapshotId, Code.TARGET_CHANGED)
        requireBridge(call.params.keys.all { it in d.parameters } && call.params.size <= 32, Code.INVALID_REQUEST)
        d.parameters.forEach { (name, rule) ->
            val value = call.params[name]
            requireBridge(value != null || !rule.required, Code.INVALID_REQUEST)
            if (value is String) requireBridge(sanitize(value) == value, Code.PERMISSION_MISSING)
            if (value != null) requireBridge(when (rule.type) {
                ParamType.STRING -> value is String && value.length <= rule.maxLength
                ParamType.INTEGER -> (value is Int || value is Long) && (value as Number).toLong() in rule.min..rule.max
                ParamType.BOOLEAN -> value is Boolean
            }, Code.INVALID_REQUEST)
        }
        return d
    }
    fun submit(raw: ToolCall): CompletableFuture<ToolResult> {
        val call = raw.copy(params = raw.params.toMap())
        val result = CompletableFuture<ToolResult>()
        var committed = false
        val cancelled = AtomicBoolean(false)
        lateinit var descriptor: ToolDescriptor
        fun failure(code: Code, unknown: Boolean = false) = authority.atomic {
            cancelled.set(true)
            result.complete(ToolResult(call.requestId, code, code.name, authority.clock.nowMs(), call.toolId, outcome = if (committed && unknown) "unknown" else if (committed) "submitted" else "observed"))
        }
        try {
            authority.atomic {
                requireBridge(!closed, Code.CANCELLED)
                descriptor = validate(call)
                requireBridge(call.toolCallId.matches(Regex("[a-zA-Z0-9_-]{1,100}")) && call.requestId.matches(Regex("[a-zA-Z0-9_-]{1,100}")), Code.INVALID_REQUEST)
                if (runId != call.binding.runId) { seen.clear(); seenRequests.clear(); runId = call.binding.runId }
                requireBridge(call.toolCallId !in seen && call.requestId !in seenRequests, Code.DUPLICATE_CALL)
                requireBridge(seen.size < 512, Code.LIMIT_EXCEEDED)
                seen.add(call.toolCallId); seenRequests.add(call.requestId)
            }
            val expires = minOf(call.deadlineMs, authority.clock.nowMs() + descriptor.timeoutMs, authority.clock.nowMs() + authority.remainingLeaseMs(call.binding))
            val timeout = timer.schedule({ failure(Code.TIMEOUT) }, maxOf(0, expires - authority.clock.nowMs()), TimeUnit.MILLISECONDS)
            val subscription = authority.onChange { try { authority.atomic { validate(call) } } catch (e: BridgeFailure) { failure(e.code) } }
            result.whenComplete { _, _ -> timeout.cancel(false); subscription.close() }
            result.whenComplete { _, _ -> if (result.isCancelled) cancelled.set(true) }
            worker.execute {
                try {
                    val context = ExecutionContext { isAction, action ->
                        authority.atomic {
                            requireBridge(!cancelled.get() && !closed, Code.CANCELLED)
                            validate(call)
                            requireBridge(authority.clock.nowMs() < expires, Code.TIMEOUT)
                            // Callbacks must be short, bounded platform commits, never a whole blocking sequence.
                            if (isAction && descriptor.effectClass != "read") committed = true
                            action()
                        }
                    }
                    context.check()
                    val text = descriptor.executor.execute(context, call.params)
                    authority.atomic {
                        context.check()
                        val bytes = sanitize(text).toByteArray(Charsets.UTF_8)
                        val truncated = bytes.size > descriptor.maxResultBytes
                        var end = minOf(bytes.size, descriptor.maxResultBytes)
                        // Do not split a UTF-8 codepoint at the result boundary.
                        if (truncated) while (end > 0 && bytes[end].toInt() and 0xc0 == 0x80) end--
                        result.complete(ToolResult(call.requestId, null, bytes.copyOf(end).toString(Charsets.UTF_8), authority.clock.nowMs(), call.toolId, truncated, if (committed) "submitted" else "observed"))
                    }
                } catch (e: BridgeFailure) { failure(e.code) } catch (_: Exception) { failure(Code.IO_ERROR, true) }
            }
        } catch (_: RejectedExecutionException) { failure(Code.LIMIT_EXCEEDED) }
        catch (e: BridgeFailure) { failure(e.code) }
        return result
    }
    override fun close() { authority.atomic { closed = true; authority.stopRun() }; worker.shutdownNow(); timer.shutdownNow() }
}
