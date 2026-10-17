package dev.thor.agent

import dev.thor.tools.Code
import dev.thor.tools.ToolFailure
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class AgentSleepTest {
    private fun now()=System.nanoTime()/1_000_000
    private fun cancelled(result:java.util.concurrent.CompletableFuture<AgentSleep.Receipt>) {
        try {result.get(1,TimeUnit.SECONDS);fail("Cancelled timer completed successfully")}
        catch(error:ExecutionException){assertEquals(Code.CANCELLED,(error.cause as ToolFailure).code)}
    }
    @Test fun strictDurationBoundsAndTypes() {
        assertEquals(1L,AgentSleep.duration(JSONObject("{\"durationMs\":1}")))
        assertEquals(60000L,AgentSleep.duration(JSONObject("{\"durationMs\":60000}")))
        for(raw in listOf("{}","{\"durationMs\":0}","{\"durationMs\":60001}","{\"durationMs\":-1}","{\"durationMs\":1.5}","{\"durationMs\":\"100\"}","{\"durationMs\":true}","{\"durationMs\":1,\"command\":\"extra\"}")) {
            try {AgentSleep.duration(JSONObject(raw));fail(raw)}catch(error:ToolFailure){assertEquals(Code.INVALID_ARGUMENT,error.code)}
        }
    }
    @Test fun elapsedReceiptIsObservedAfterWaitingAndPayloadIsReleased() {
        AgentSleep(::now).use {timer->
            val result=timer.start("a","wait",20,now()+1000).get(1,TimeUnit.SECONDS)
            assertEquals(20L,result.durationMs);assertTrue(result.elapsedMs>=20);assertEquals(0,timer.size)
        }
    }
    @Test fun cancellingOneRunDoesNotBlockOrCancelAnotherRun() {
        AgentSleep(::now).use {timer->
            val a=timer.start("a","long",60000,now()+65000)
            val b=timer.start("b","short",10,now()+1000)
            timer.cancel("a");cancelled(a)
            assertEquals(10L,b.get(1,TimeUnit.SECONDS).durationMs);assertEquals(0,timer.size)
        }
    }
    @Test fun cancelUsesBothRunAndRequestIdentity() {
        AgentSleep(::now).use {timer->
            val result=timer.start("a","wait",60000,now()+65000)
            timer.cancel("other","wait");timer.cancel("a","other");assertFalse(result.isDone)
            timer.cancel("a","wait");cancelled(result);assertEquals(0,timer.size)
        }
    }
    @Test fun expiredDeadlineIsRejectedBeforeScheduling() {
        AgentSleep(::now).use {timer->
            try {timer.start("a","wait",1000,now()+100);fail("Deadline accepted")}
            catch(error:ToolFailure){assertEquals(Code.TIMEOUT,error.code)}
            assertEquals(0,timer.size)
        }
    }
    @Test fun closeCancelsLongWaitsAndRejectsNewWork() {
        val timer=AgentSleep(::now);val result=timer.start("a","wait",60000,now()+65000)
        timer.close();cancelled(result);assertEquals(0,timer.size)
        try {timer.start("b","next",1,now()+100);fail("Closed owner accepted work")}
        catch(error:ToolFailure){assertEquals(Code.CANCELLED,error.code)}
    }
}
