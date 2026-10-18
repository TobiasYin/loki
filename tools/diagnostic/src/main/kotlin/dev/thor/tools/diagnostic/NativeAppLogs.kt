package dev.thor.tools.diagnostic

import dev.thor.tools.*
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Runs as the existing authorized UserService UID. No shell, writes, flags, or app launch. */
object NativeAppLogs {
    private const val TIMEOUT_MS = 10_000L
    private const val SCAN_BYTES = 8L * 1024 * 1024

    fun read(args: Map<String, Any?>, check: () -> Unit): Map<String, Any?> {
        val request = AppLogRequest.parse(args)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)
        fun checked() {
            check()
            requireTool(System.nanoTime() < deadline, Code.TIMEOUT, "Application log read timed out")
        }
        checked()
        val processes = StringBuilder()
        val ps = drain(request.processArgv(), minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)), AtomicLong(512 * 1024), ::checked) { processes.append(it) }
        requireTool(!ps, Code.LIMIT_EXCEEDED, "Application process list exceeds the read budget")
        val pids = request.matchingPids(processes.toString())
        requireTool(pids.size <= 16, Code.LIMIT_EXCEEDED, "Application has too many processes for one log read")
        val remaining = AtomicLong(SCAN_BYTES)
        val tail = AppLogTail(request.maxLines, request.maxBytes)
        var limited = false
        // Each command selects one verified application PID before reading the log buffer.
        // Android's -t applies a global tail before --pid on some versions, so do not use it.
        for (pid in pids) {
            checked()
            if (remaining.get() <= 0) { limited = true; break }
            limited = drain(request.logArgv(pid), deadline, remaining, ::checked, tail::append) || limited
            tail.finish()
        }
        tail.finish(); checked()
        val content = tail.content()
        return mapOf(
            "packageName" to request.packageName, "pids" to pids, "content" to content,
            "ordering" to "per_process_pid_order",
            "count" to tail.count(), "truncated" to (tail.truncated || limited),
            "available" to true, "logsEmpty" to content.isEmpty(), "processRunning" to pids.isNotEmpty(),
            "reason" to when {
                pids.isEmpty() -> "PROCESS_NOT_RUNNING"
                limited -> "READ_BUDGET_REACHED"
                content.isEmpty() -> "LOGS_EMPTY"
                else -> "LOGS_AVAILABLE"
            }
        )
    }

    /** Always drain stderr independently; never expose another command's unbounded output. */
    private fun drain(argv: List<String>, deadline: Long, remaining: AtomicLong, check: () -> Unit, append: (String) -> Unit): Boolean {
        check()
        val process = try { ProcessBuilder(argv).start() } catch (_: java.io.IOException) {
            fail(Code.IO_ERROR, "Cannot start the application log reader")
        }
        val exhausted = AtomicBoolean(false)
        val readers = Executors.newFixedThreadPool(2) { task -> Thread(task, "thor-app-log-reader").apply { isDaemon = true } }
        try {
            process.outputStream.close()
            val stdout = readers.submit<Unit> {
                val bounded = object : FilterInputStream(process.inputStream) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val left = remaining.get()
                        if (left <= 0) { exhausted.set(true); return -1 }
                        val n = super.read(buffer, offset, minOf(length.toLong(), left).toInt())
                        if (n > 0) remaining.addAndGet(-n.toLong())
                        return n
                    }
                }
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE).onUnmappableCharacter(CodingErrorAction.IGNORE)
                InputStreamReader(bounded, decoder).use { input ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        append(String(buffer, 0, n))
                    }
                }
            }
            val stderr = readers.submit<Unit> {
                process.errorStream.use { input ->
                    val buffer = ByteArray(1024)
                    // The fixed command has no descendants; even a pathological error stream
                    // is bounded by the same wall-clock deadline and consumes no retained memory.
                    while (input.read(buffer) >= 0) { }
                }
            }
            while (true) {
                check()
                requireTool(System.nanoTime() < deadline, Code.TIMEOUT, "Application log read timed out")
                if (exhausted.get()) {
                    stop(process)
                    break
                }
                if (process.waitFor(25, TimeUnit.MILLISECONDS)) break
            }
            fun await(future: java.util.concurrent.Future<*>) {
                val left = deadline - System.nanoTime()
                requireTool(left > 0, Code.TIMEOUT, "Application log read timed out")
                try { future.get(minOf(left, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS) }
                catch (_: java.util.concurrent.TimeoutException) { fail(Code.TIMEOUT, "Application log reader did not close") }
                catch (_: java.util.concurrent.ExecutionException) {
                    if (!exhausted.get()) fail(Code.IO_ERROR, "Application log reader could not read its output")
                }
            }
            await(stdout); await(stderr); check()
            requireTool(exhausted.get() || !process.isAlive && process.exitValue() == 0, Code.IO_ERROR, "Application log reader failed")
            return exhausted.get()
        } finally {
            if (process.isAlive) stop(process)
            runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            readers.shutdownNow()
        }
    }

    private fun stop(process: Process) {
        runCatching { process.destroy() }
        val stopped = runCatching { process.waitFor(100, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!stopped) runCatching { process.destroyForcibly() }
    }
}
