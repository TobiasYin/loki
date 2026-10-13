package dev.thor.bridge

/** Bounds data waiting for the UI thread independently of renderer-supplied credits. */
class DeliveryBudget(private val perRequestBytes: Int = 262144, private val totalBytes: Int = 524288) {
    private val pending = mutableMapOf<String, Int>()
    private var total = 0
    init { require(perRequestBytes > 0 && totalBytes >= perRequestBytes) }
    @Synchronized fun reserve(requestId: String, bytes: Int): AutoCloseable {
        requireBridge(bytes > 0 && bytes <= perRequestBytes && (pending[requestId] ?: 0).toLong() + bytes <= perRequestBytes && total.toLong() + bytes <= totalBytes, Code.LIMIT_EXCEEDED)
        pending[requestId] = (pending[requestId] ?: 0) + bytes; total += bytes
        var released = false
        return AutoCloseable {
            synchronized(this) {
                if (!released) {
                    released = true; total -= bytes
                    val left = pending.getValue(requestId) - bytes
                    if (left == 0) pending.remove(requestId) else pending[requestId] = left
                }
            }
        }
    }
}
