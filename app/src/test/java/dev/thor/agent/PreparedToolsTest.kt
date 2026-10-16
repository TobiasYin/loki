package dev.thor.agent

import dev.thor.tools.Code
import dev.thor.tools.ToolFailure
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PreparedToolsTest {
    private class Read(val value:Int)
    private fun rejected(code:Code,action:()->Unit) {
        try {action();fail("Expected $code")}catch(t:ToolFailure){assertEquals(code,t.code)}
    }
    @Test fun sameRunCanCompleteEightyReadsWhileOnlyInFlightPayloadsOccupySlots() {
        val tools=PreparedTools<Read>();val observed=mutableListOf<Int>()
        repeat(80) {i->
            val read=Read(i);tools.prepare("r","request-$i","call-$i",read)
            val claimed=tools.claim("request-$i"){it===read};observed.add(claimed.value)
            assertEquals(1,tools.values.size);tools.finish("request-$i",claimed)
            assertTrue(tools.values.isEmpty())
        }
        assertEquals((0 until 80).toList(),observed)
    }
    @Test fun completedAndFailedCallsNeverAllowRequestOrCallIdReplay() {
        val tools=PreparedTools<Read>()
        for(i in 0..1) {
            val read=Read(i);tools.prepare("r","request-$i","call-$i",read)
            val future=java.util.concurrent.CompletableFuture<Unit>()
            future.whenComplete {_,_->tools.finish("request-$i",read)}
            tools.claim("request-$i"){true}
            if(i==0)future.complete(Unit) else future.completeExceptionally(IllegalStateException("synthetic read failure"))
            rejected(Code.INVALID_ARGUMENT){tools.claim("request-$i"){true}}
            rejected(Code.INVALID_ARGUMENT){tools.prepare("r","request-$i","different-$i",Read(9))}
            rejected(Code.INVALID_ARGUMENT){tools.prepare("r","different-$i","call-$i",Read(9))}
        }
    }
    @Test fun overlappingExecuteRequestsClaimOnlyOneExecutionAndRetainPayloadUntilFinish() {
        val tools=PreparedTools<Read>();val read=Read(1);tools.prepare("r","request","call",read)
        val start=CountDownLatch(1);val winners=AtomicInteger();val losers=AtomicInteger();val workers=Executors.newFixedThreadPool(8)
        try {
            val jobs=(0 until 32).map {workers.submit {start.await();try {tools.claim("request"){true};winners.incrementAndGet()}catch(t:ToolFailure){assertEquals(Code.INVALID_ARGUMENT,t.code);losers.incrementAndGet()}}}
            start.countDown();jobs.forEach {it.get(2,TimeUnit.SECONDS)}
            assertEquals(1,winners.get());assertEquals(31,losers.get());assertSame(read,tools["request"])
            assertEquals(1,tools.values.size);tools.finish("request",read);assertNull(tools["request"])
        }finally {workers.shutdownNow()}
    }
    @Test fun inFlightAndTombstoneBudgetsRemainBoundedAndSeparateByRun() {
        val tools=PreparedTools<Read>(2,4)
        val a=Read(1);tools.prepare("r","one","one",a);tools.prepare("r","two","two",Read(2))
        rejected(Code.LIMIT_EXCEEDED){tools.prepare("r","three","three",Read(3))}
        tools.claim("one"){true};tools.finish("one",a)
        tools.prepare("r","three","three",Read(3));val third=tools.claim("three"){true};tools.finish("three",third)
        tools.prepare("r","four","four",Read(4));val fourth=tools.claim("four"){true};tools.finish("four",fourth)
        rejected(Code.LIMIT_EXCEEDED){tools.prepare("r","five","five",Read(5))}
        tools.prepare("other","other-request","one",Read(1))
        tools.removeRun("r");assertEquals(1,tools.values.size)
        tools.prepare("r","one","one",Read(1)) // Old lease rejection belongs to Native authority.
    }
    @Test fun mismatchedArgumentsDoNotClaimAndWrongCompletionCannotReleaseAnotherPayload() {
        val tools=PreparedTools<Read>();val read=Read(7);tools.prepare("r","request","call",read)
        rejected(Code.INVALID_ARGUMENT){tools.claim("request"){it.value==8}}
        assertSame(read,tools.claim("request"){it.value==7})
        tools.finish("request",Read(7));assertSame(read,tools["request"])
        tools.finish("request",read);assertTrue(tools.values.isEmpty())
    }
}
