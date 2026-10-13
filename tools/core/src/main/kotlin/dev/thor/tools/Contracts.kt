package dev.thor.tools

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

fun interface Clock { fun now(): Long }
enum class Capability { SCREEN_PERCEPTION, WEB_SEARCH, SCREEN_CONTROL, ADB_DEBUG }
enum class Status { READY, PERMISSION_MISSING, BACKEND_MISSING, UNSUPPORTED, TEMPORARILY_UNAVAILABLE }
data class Availability(val status: Status, val reason: String, val backend: String)
enum class Code { CAPABILITY_DISABLED, STALE_AUTHORIZATION, PERMISSION_MISSING, BACKEND_UNAVAILABLE, TARGET_CHANGED, UNSUPPORTED, TIMEOUT, CANCELLED, LIMIT_EXCEEDED, IO_ERROR, INVALID_ARGUMENT, HASH_CONFLICT, APPROVAL_REQUIRED, SECURE_CONTENT, DISPLAY_GONE, RATE_LIMITED }
class ToolFailure(val code: Code, override val message: String) : RuntimeException(message)
fun fail(code: Code, message: String): Nothing = throw ToolFailure(code, message)
fun requireTool(condition: Boolean, code: Code = Code.INVALID_ARGUMENT, message: String) { if (!condition) fail(code, message) }
enum class Effect { READ, INPUT, WRITE, ARBITRARY_SHELL }
data class Descriptor(val toolId: String, val capability: Capability, val effect: Effect, val targetRequired: Boolean = false, val schemaVersion: Int = 1, val packId: String = "thor.device", val packVersion: String = "0.1.0",
    val timeoutMs: Int = 60000, val cancellable: Boolean = true, val concurrencyKey: String = "device",
    val provenance: String = "builtin", val modelVisibility: Boolean = true)
object ToolPack {
    const val VERSION = "0.1.0"
    val descriptors = listOf(
        Descriptor("perception.app_info", Capability.SCREEN_PERCEPTION, Effect.READ, true),
        Descriptor("perception.window_info", Capability.SCREEN_PERCEPTION, Effect.READ, true),
        Descriptor("perception.ui_tree", Capability.SCREEN_PERCEPTION, Effect.READ, true),
        Descriptor("perception.capture_screen", Capability.SCREEN_PERCEPTION, Effect.READ, true),
        Descriptor("perception.device_info", Capability.SCREEN_PERCEPTION, Effect.READ),
        Descriptor("input.tap", Capability.SCREEN_CONTROL, Effect.INPUT, true),
        Descriptor("input.long_press", Capability.SCREEN_CONTROL, Effect.INPUT, true),
        Descriptor("input.swipe", Capability.SCREEN_CONTROL, Effect.INPUT, true),
        Descriptor("input.press_button", Capability.SCREEN_CONTROL, Effect.INPUT, true),
        Descriptor("input.set_axes", Capability.SCREEN_CONTROL, Effect.INPUT, true),
        Descriptor("input.release_inputs", Capability.SCREEN_CONTROL, Effect.INPUT),
        Descriptor("debug.shell", Capability.ADB_DEBUG, Effect.ARBITRARY_SHELL),
        Descriptor("files.list", Capability.ADB_DEBUG, Effect.READ),
        Descriptor("files.read", Capability.ADB_DEBUG, Effect.READ),
        Descriptor("files.backup", Capability.ADB_DEBUG, Effect.READ),
        Descriptor("files.write", Capability.ADB_DEBUG, Effect.WRITE),
        Descriptor("files.restore", Capability.ADB_DEBUG, Effect.WRITE)
    ).associateBy { it.toolId }
}
data class Envelope(val sessionId: String, val runId: String, val toolCallId: String, val toolId: String,
    val capabilityEpoch: Long, val deadline: Long, val snapshotId: String? = null, val schemaVersion: Int = 1,
    val requestId: String = UUID.randomUUID().toString())
data class ToolResult(val observedAt: Long, val source: String, val availability: Availability,
    val data: Map<String, Any?> = emptyMap(), val error: Code? = null, val message: String? = null,
    val truncated: Boolean = false, val outcome: String = "observed", val snapshotId: String? = null)
class Cancellation {
    private val stopped = AtomicBoolean(false)
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()
    val cancelled: Boolean get() = stopped.get()
    fun register(callback: () -> Unit) { callbacks.add(callback); if (cancelled) callback() }
    fun cancel() { if (stopped.compareAndSet(false, true)) callbacks.forEach { runCatching { it() } } }
    fun check() { if (cancelled) fail(Code.CANCELLED, "Run was stopped; committed effects cannot be undone") }
}
class Checkpoint(private val checkAction: () -> Unit, val cancellation: Cancellation, private val commitAction: ((() -> Unit) -> Unit)? = null) {
    var submitted = false
        private set
    fun check() { cancellation.check(); checkAction() }
    fun submitted() { submitted = true }
    /** Only wrap the brief actual commit, never a whole async/read/shell wait. */
    fun effect(action: () -> Unit) {
        val execute = { check(); action(); submitted() }
        if (commitAction == null) execute() else commitAction.invoke(execute)
    }
}
interface DeviceBackend {
    fun availability(toolId: String): Availability
    fun availability(toolId: String, args: Map<String, Any?>): Availability = availability(toolId)
    fun execute(envelope: Envelope, args: Map<String, Any?>, checkpoint: Checkpoint): ToolResult
    fun release(runId: String): String
}
