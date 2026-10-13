package dev.thor.tools

import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Arbitrary shell is an explicit debug capability, never a touch or gamepad backend. */
class BoundedShell(private val shell: String, private val clock: Clock) {
    data class Output(val stdout: String, val stderr: String, val exitCode: Int?, val durationMs: Long,
        val truncated: Boolean, val error: Code?, val terminationConfirmed: Boolean,
        val descendantsMayRemain: Boolean = true)
    fun execute(command: String, timeoutMs: Int, maxBytes: Int, cancellation: Cancellation, check: () -> Unit): Output {
        requireTool(command.isNotBlank() && command.toByteArray().size <= 8192 && !command.contains('\u0000'), message = "Shell command must be 1..8192 UTF-8 bytes")
        requireTool(timeoutMs in 1..60_000 && maxBytes in 1..Budgets.TEXT, Code.LIMIT_EXCEEDED, "Shell time/output budget exceeded")
        check(); cancellation.check()
        val process = start(command, cancellation, check)
        return collect(process, timeoutMs, maxBytes, cancellation, check)
    }
    /** Short submission primitive; production callers wrap this in the Native authority commit. */
    fun start(command: String, cancellation: Cancellation, check: () -> Unit): Process {
        requireTool(command.isNotBlank() && command.toByteArray().size <= 8192 && !command.contains('\u0000'), message = "Invalid command")
        check(); cancellation.check()
        val process = ProcessBuilder(shell, "-c", command).start()
        process.outputStream.close()
        cancellation.register { process.destroy(); if (process.isAlive) process.destroyForcibly() }
        return process
    }
    /** Waiting/draining never holds Native's authorization lock. */
    fun collect(process: Process, timeoutMs: Int, maxBytes: Int, cancellation: Cancellation, check: () -> Unit): Output {
        requireTool(timeoutMs in 1..60_000 && maxBytes in 1..Budgets.TEXT, Code.LIMIT_EXCEEDED, "Shell budget exceeded")
        val start = clock.now()
        val used = AtomicLong(0); val truncated = AtomicBoolean(false); val stoppedDraining = AtomicBoolean(false)
        val drain = Executors.newFixedThreadPool(2)
        fun collect(input: java.io.InputStream): java.util.concurrent.Future<String> = drain.submit<String> {
            val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            try {
                while (!stoppedDraining.get()) {
                    val available = input.available()
                    if (available == 0) {
                        if (!process.isAlive || cancellation.cancelled) break
                        Thread.sleep(5); continue
                    }
                    val n = input.read(buffer, 0, minOf(buffer.size, available)); if (n < 0) break
                    val reserved = used.getAndAdd(n.toLong())
                    val accepted = maxOf(0L, minOf(n.toLong(), maxBytes - reserved)).toInt()
                    if (accepted < n) truncated.set(true)
                    if (accepted > 0) out.write(buffer, 0, accepted)
                }
            } catch (_: java.io.IOException) { /* pipe closed during cancellation */ }
            Budgets.safeText(out.toString("UTF-8"), maxBytes)
        }
        val stdout = collect(process.inputStream); val stderr = collect(process.errorStream)
        var error: Code? = null
        try {
            while (!process.waitFor(25, TimeUnit.MILLISECONDS)) {
                if (cancellation.cancelled) { error = Code.CANCELLED; break }
                if (clock.now() - start >= timeoutMs) { error = Code.TIMEOUT; break }
                try { check() } catch (t: ToolFailure) { error = t.code; break }
            }
            if (cancellation.cancelled) error = Code.CANCELLED
            if (error != null) {
                process.destroy()
                if (!process.waitFor(100, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
            // Descendants may retain pipes after the direct shell exits; do not wait forever.
            fun output(future: java.util.concurrent.Future<String>): String = try { future.get(250, TimeUnit.MILLISECONDS) } catch (_: Exception) { truncated.set(true); stoppedDraining.set(true); "" }
            val out = output(stdout); val err = output(stderr)
            return Output(out, err, if (process.isAlive) null else process.exitValue(), clock.now() - start,
                truncated.get(), error, terminationConfirmed = false)
        } finally {
            stoppedDraining.set(true)
            process.inputStream.close(); process.errorStream.close()
            if (process.isAlive) process.destroyForcibly()
            drain.shutdownNow()
        }
    }
}
