package dev.thor.agent

import org.junit.Assert.*
import org.junit.Test

class LatestValueTest {
    @Test fun burstSendsLatestAndNextBurstSchedulesAgain() {
        val tasks=mutableListOf<()->Unit>();val sent=mutableListOf<Int>()
        val latest=LatestValue<Int>({tasks.add(it)},{value,done->sent.add(value);done()})
        repeat(100){latest.offer(it)}
        assertEquals(1,tasks.size);assertTrue(sent.isEmpty())
        tasks.removeAt(0).invoke();assertEquals(listOf(99),sent)
        latest.offer(100);assertEquals(1,tasks.size);tasks.removeAt(0).invoke()
        assertEquals(listOf(99,100),sent)
    }
    @Test fun closedViewCannotReceiveQueuedOrNewState() {
        val tasks=mutableListOf<()->Unit>();val sent=mutableListOf<Int>()
        val latest=LatestValue<Int>({tasks.add(it)},{value,done->sent.add(value);done()})
        latest.offer(1);latest.close();latest.offer(2);tasks.removeAt(0).invoke()
        assertTrue(sent.isEmpty());assertTrue(tasks.isEmpty())
    }
    @Test fun stateProducedDuringDeliveryIsNotLost() {
        val tasks=mutableListOf<()->Unit>();val sent=mutableListOf<Int>()
        lateinit var latest:LatestValue<Int>
        latest=LatestValue({tasks.add(it)},{value,done->sent.add(value);if(value==1)latest.offer(2);done()})
        latest.offer(1);tasks.removeAt(0).invoke();tasks.removeAt(0).invoke()
        assertEquals(listOf(1,2),sent)
    }
    @Test fun slowRendererHasOnlyOneInFlightAndOneLatestPendingSnapshot() {
        val tasks=mutableListOf<()->Unit>();val sent=mutableListOf<Int>();val acks=mutableListOf<()->Unit>()
        val latest=LatestValue<Int>({tasks.add(it)},{value,done->sent.add(value);acks.add(done)})
        latest.offer(0);tasks.removeAt(0).invoke()
        repeat(10000){latest.offer(it+1)}
        assertEquals(listOf(0),sent);assertTrue(tasks.isEmpty())
        acks.removeAt(0).invoke();assertEquals(1,tasks.size);tasks.removeAt(0).invoke()
        assertEquals(listOf(0,10000),sent)
        latest.close();acks.removeAt(0).invoke();assertTrue(tasks.isEmpty())
    }

}
