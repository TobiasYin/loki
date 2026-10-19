package dev.thor.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccessibilityRecoveryHistoryTest {
    @Test fun maintenanceHistoryDoesNotAlterRuntimeSequenceOrPersistPrivateFields() {
        val run=JSONObject("""{"id":"r","runtime":{"sequence":7},"status":"generating"}""")
        val event=JSONObject("""{"id":1,"phase":"recovering","wallTime":10,"private":"secret"}""")
        assertTrue(AccessibilityRecoveryHistory.append(run,event))
        assertFalse(AccessibilityRecoveryHistory.append(run,event))
        assertFalse(run.getJSONArray("serviceRecoveryEvents").getJSONObject(0).has("private"))
        assertEquals(7,run.getJSONObject("runtime").getInt("sequence"))
        assertEquals("generating",run.getString("status"))
        assertFalse(AccessibilityRecoveryHistory.append(run,JSONObject("""{"id":2,"phase":"unknown"}""")))
    }
    @Test fun boundedHistoryRetainsLatestRecoveryOutcome() {
        val run=JSONObject()
        for(i in 1..100)AccessibilityRecoveryHistory.append(run,JSONObject().put("id",i).put("phase",if(i==100)"connected" else "recovering"))
        val events=run.getJSONArray("serviceRecoveryEvents")
        assertEquals(64,events.length());assertEquals(37,events.getJSONObject(0).getInt("id"))
        assertEquals("connected",events.getJSONObject(63).getString("phase"))
    }
}
