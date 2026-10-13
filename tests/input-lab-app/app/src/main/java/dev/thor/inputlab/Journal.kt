package dev.thor.inputlab

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val buf = ByteArray(8192); while(true) { val n=input.read(buf); if(n<0) break; md.update(buf,0,n) } }
    return md.digest().joinToString("") { "%02x".format(it) }
}
fun atomicFile(file: File, text: String) {
    val tmp = File(file.parentFile, file.name+".tmp")
    FileOutputStream(tmp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    check(tmp.renameTo(file)) { "Cannot commit ${file.name}" }
}

/** Single append writer. Admission is bounded; failures are sticky and prevent pass. */
class Journal(val dir: File, val maxSamples: Long = 100_000, val maxBytes: Long = 32L*1024*1024, queueCapacity: Int = 256, private val writeRecord: ((FileOutputStream, ByteArray) -> Unit)? = null) {
    val incomplete = AtomicBoolean(false)
    val dropped = AtomicLong(0)
    private val pool = ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(queueCapacity))
    private val stream: FileOutputStream
    private var reservedSamples = 0L
    private var reservedBytes = 0L
    private var sealed = false
    var error: String? = null
        private set
    init { check(dir.mkdirs() || dir.isDirectory); stream = FileOutputStream(File(dir,"events.jsonl"),true) }
    @Synchronized fun append(record: Map<String, Any?>, samples: Int = 1): Boolean {
        if(sealed) return false
        if(incomplete.get()) { dropped.addAndGet(samples.toLong()); return false }
        val bytes = (Json.encode(record)+"\n").toByteArray(Charsets.UTF_8)
        if(reservedSamples+samples > maxSamples || reservedBytes+bytes.size > maxBytes) {
            incomplete.set(true); dropped.addAndGet(samples.toLong()); error="budget_exceeded"; return false
        }
        reservedSamples += samples; reservedBytes += bytes.size
        return try {
            pool.execute { try { if(writeRecord != null) writeRecord.invoke(stream, bytes) else { stream.write(bytes); stream.fd.sync() } } catch(e: Exception) { incomplete.set(true); dropped.addAndGet(samples.toLong()); error="disk_write_failed:${e.javaClass.simpleName}" } }
            true
        } catch(e: java.util.concurrent.RejectedExecutionException) {
            incomplete.set(true); dropped.addAndGet(samples.toLong()); error="queue_overflow"; false
        }
    }
    /** Control flush is serialized behind admitted records, even if the data queue is full. Call off UI thread. */
    fun flush() {
        val latch = java.util.concurrent.CountDownLatch(1)
        val task = Runnable { try { stream.fd.sync() } catch(e: Exception) { incomplete.set(true); error="flush_failed" }; latch.countDown() }
        while(true) { try { pool.execute(task); break } catch(e: java.util.concurrent.RejectedExecutionException) { if(pool.isShutdown) error("Writer closed"); Thread.sleep(5) } }
        check(latch.await(30,TimeUnit.SECONDS)) { "Journal flush timed out" }
    }
    fun snapshot(): Pair<Long,String> {
        val done = java.util.concurrent.CompletableFuture<Pair<Long,String>>()
        val task = Runnable { try { stream.fd.sync(); done.complete(File(dir,"events.jsonl").length() to sha256(File(dir,"events.jsonl"))) } catch(e: Exception) { incomplete.set(true); done.completeExceptionally(e) } }
        while(true) { try { pool.execute(task); break } catch(e: java.util.concurrent.RejectedExecutionException) { if(pool.isShutdown) error("Writer closed"); Thread.sleep(5) } }
        return done.get(30,TimeUnit.SECONDS)
    }
    fun close() {
        synchronized(this) { sealed=true }
        pool.shutdown()
        if(!pool.awaitTermination(30,TimeUnit.SECONDS)) { incomplete.set(true); error="writer_timeout"; pool.shutdownNow() }
        stream.close()
    }
}
