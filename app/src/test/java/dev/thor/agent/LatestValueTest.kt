package dev.thor.agent

import org.junit.Assert.*
import org.junit.Test

class LatestValueTest {
    @Test fun burstSendsLatestAndNextBurstSchedulesAgain() {
        val tasks=mutableListOf<()->Unit>();val sent=mutableListOf<Int>()
        val latest=LatestValue<Int>({tasks.add(it)},{sent.add(it)})
        repeat(100){latest.offer(it)}
        assertEquals(1,tasks.size);assertTrue(sent.isEmpty())
        tasks.removeAt(0).invoke();assertEquals(listOf(99),sent)
        latest.offer(100);assertEquals(1,tasks.size);tasks.removeAt(0).invoke()
        assertEquals(listOf(99,100),sent)
    }
    @Test fun closedViewCannotReceiveQueuedOrNewState() {
        val tasks=mutableListOf<()->Unit>();val sent=mutableListOf<Int>()
        val latest=LatestValue<Int>({tasks.add(it)},{sent.add(it)})
        latest.offer(1);latest.close();latest.offer(2);tasks.removeAt(0).invoke()
        assertTrue(sent.isEmpty());assertTrue(tasks.isEmpty())
    }
    @Test fun stateProducedDuringDeliveryIsNotLost() {
        val tasks=mutableListOf<()->Unit>();val sent=mutableListOf<Int>()
        lateinit var latest:LatestValue<Int>
        latest=LatestValue({tasks.add(it)},{sent.add(it);if(it==1)latest.offer(2)})
        latest.offer(1);tasks.removeAt(0).invoke();tasks.removeAt(0).invoke()
        assertEquals(listOf(1,2),sent)
    }
}
