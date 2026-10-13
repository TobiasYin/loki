package dev.thor.inputlab

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class JournalTest {
    @Test fun appendOrderingAndHash() {
        val dir=Files.createTempDirectory("lab-journal").toFile()
        try {
            val j=Journal(dir)
            repeat(40) { assertTrue(j.append(mapOf("sequence" to it,"recordOrigin" to "fixture_replay"))) }
            j.flush(); j.close()
            val lines=java.io.File(dir,"events.jsonl").readLines()
            assertEquals(40,lines.size)
            lines.forEachIndexed { i,s -> assertTrue(s.contains("\"sequence\":$i,")) }
            assertEquals(64,sha256(java.io.File(dir,"events.jsonl")).length)
        } finally { dir.deleteRecursively() }
    }
    @Test fun budgetDoesNotOverwriteEvidence() {
        val dir=Files.createTempDirectory("lab-budget").toFile()
        try {
            val j=Journal(dir,maxSamples=2)
            assertTrue(j.append(mapOf("sequence" to 1),2)); assertFalse(j.append(mapOf("sequence" to 2)))
            j.flush(); j.close()
            assertTrue(j.incomplete.get()); assertEquals(1,j.dropped.get()); assertEquals(1,java.io.File(dir,"events.jsonl").readLines().size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun byteLimitIsExplicit() {
        val dir=Files.createTempDirectory("lab-bytes").toFile()
        try { val j=Journal(dir,maxBytes=8); assertFalse(j.append(mapOf("long" to "payload"))); j.close(); assertTrue(j.incomplete.get()) } finally { dir.deleteRecursively() }
    }
    @Test fun atomicReportContainsOnlyCommittedContents() {
        val dir=Files.createTempDirectory("lab-report").toFile()
        try { val f=java.io.File(dir,"report.json"); atomicFile(f,"old"); atomicFile(f,"new"); assertEquals("new",f.readText()); assertFalse(java.io.File(dir,"report.json.tmp").exists()) } finally { dir.deleteRecursively() }
    }
    @Test fun queueOverflowIsDeterministicAndExplicit() {
        val dir=Files.createTempDirectory("lab-queue").toFile()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        try {
            val j=Journal(dir,queueCapacity=1,writeRecord={ stream,bytes -> entered.countDown(); release.await(); stream.write(bytes); stream.fd.sync() })
            assertTrue(j.append(mapOf("sequence" to 1)))
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(j.append(mapOf("sequence" to 2)))
            assertFalse(j.append(mapOf("sequence" to 3)))
            release.countDown(); j.flush(); j.close()
            assertTrue(j.incomplete.get()); assertEquals("queue_overflow",j.error); assertEquals(1,j.dropped.get())
            assertEquals(2,java.io.File(dir,"events.jsonl").readLines().size)
        } finally { release.countDown(); dir.deleteRecursively() }
    }
    @Test fun diskFailureIsStickyAndBlocksPassEvidence() {
        val dir=Files.createTempDirectory("lab-disk").toFile()
        try {
            val j=Journal(dir,writeRecord={ _,_ -> throw java.io.IOException("fixture disk full") })
            j.append(mapOf("sequence" to 1)); j.flush()
            assertTrue(j.incomplete.get()); assertFalse(j.append(mapOf("sequence" to 2))); j.close()
            assertEquals(2,j.dropped.get()); assertEquals(0,java.io.File(dir,"events.jsonl").length())
        } finally { dir.deleteRecursively() }
    }
    @Test fun prefixSnapshotRemainsVerifiableAfterLateAppend() {
        val dir=Files.createTempDirectory("lab-prefix").toFile()
        try {
            val j=Journal(dir); j.append(mapOf("sequence" to 1)); val prefix=j.snapshot()
            j.append(mapOf("sequence" to 2,"late" to true)); j.close()
            val bytes=java.io.File(dir,"events.jsonl").readBytes().copyOf(prefix.first.toInt())
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(prefix.second,hash)
        } finally { dir.deleteRecursively() }
    }

}
