package dev.thor.agent

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class EventIndexIsolationTest {
    @Test fun publishedIndexListsRemainStableWhenWriterAppendsOrRecoversState() {
        val index=EventIndex();val events=JSONArray().put(obj("sessionId" to "s","runId" to "r","sequence" to 1))
        val sessionSnapshot=index.sessionEvents(events,"s");val runSnapshot=index.runEvents(events,"r")
        events.put(obj("sessionId" to "s","runId" to "r","sequence" to 2))
        assertEquals(2,index.runEvents(events,"r").size)
        assertEquals("Readers must not retain the mutable internal index",1,sessionSnapshot.size)
        assertEquals("Run snapshots must also be isolated",1,runSnapshot.size)
        val recovered=JSONArray().put(obj("sessionId" to "s","runId" to "r","sequence" to 9))
        assertEquals(9,index.sessionEvents(recovered,"s").single().getInt("sequence"))
        assertEquals(1,sessionSnapshot.single().getInt("sequence"))
    }
    @Test fun clearReleasesSourceAndIndexedRowsWithoutChangingPublishedSnapshots() {
        val index=EventIndex();val events=JSONArray()
            .put(obj("sessionId" to "s","runId" to "r","sequence" to 1))
            .put(obj("sessionId" to "other","runId" to "other-run","sequence" to 2))
        val before=events.toString();val published=index.sessionEvents(events,"s")
        index.clear();index.clear()
        fun field(name:String):Any?=EventIndex::class.java.getDeclaredField(name).apply {isAccessible=true}.get(index)
        assertNull("A closed host must not retain the full ledger array",field("source"))
        assertEquals(0,field("length"));assertTrue((field("bySession") as Map<*,*>).isEmpty());assertTrue((field("byRun") as Map<*,*>).isEmpty())
        assertEquals(before,events.toString());assertEquals(1,published.single().getInt("sequence"))
        assertEquals(1,index.sessionEventCount(events,"s"));assertEquals(1,index.runEventCount(events,"other-run"))
        assertEquals(2,index.sessionEvents(events,"other").single().getInt("sequence"))
    }

    @Test fun clearThenNewLedgerCannotCarryPriorSessionOrRunReferences() {
        val index=EventIndex();val old=JSONArray().put(obj("sessionId" to "old","runId" to "old-run"))
        index.sessionEvents(old,"old");index.clear()
        val next=JSONArray().put(obj("sessionId" to "new","runId" to "new-run"))
        assertTrue(index.sessionEvents(next,"old").isEmpty());assertEquals(0,index.runEventCount(next,"old-run"))
        assertEquals(1,index.sessionEventCount(next,"new"));assertEquals(1,index.runEvents(next,"new-run").size)
    }

}
