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
}
