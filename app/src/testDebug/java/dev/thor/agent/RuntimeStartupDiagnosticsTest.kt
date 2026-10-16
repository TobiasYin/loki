package dev.thor.agent

import org.junit.Assert.*
import org.junit.Test

class RuntimeStartupDiagnosticsTest {
    @Test fun recorderRetainsOnlyFourRecentAttemptsAndCannotResurrectAnEvictedAttempt() {
        val old=RuntimeStartupDiagnostics.begin();repeat(5){RuntimeStartupDiagnostics.begin()}
        RuntimeStartupDiagnostics.mark(old,RuntimeStartupPhase.EXEC_READY)
        val attempts=RuntimeStartupDiagnostics.snapshot().getJSONArray("attempts");assertEquals(4,attempts.length())
        for(i in 0 until attempts.length())assertNotEquals(old,attempts.getJSONObject(i).getLong("attempt"))
    }
    @Test fun payloadDiagnosticContainsOnlyExactByteCountsAndFixedPhaseNames() {
        val id=RuntimeStartupDiagnostics.begin();val privateText="SYNTHETIC_PRIVATE_VALUE_中文😀"
        RuntimeStartupDiagnostics.payload(id,RuntimeStartupPhase.CHAT_STATE_POST,privateText)
        RuntimeStartupDiagnostics.payload(id,RuntimeStartupPhase.CHAT_STATE_POST,"x")
        val snapshot=RuntimeStartupDiagnostics.snapshot();assertFalse(snapshot.toString().contains(privateText));assertFalse(snapshot.toString().contains("SYNTHETIC_PRIVATE_VALUE"))
        val attempts=snapshot.getJSONArray("attempts");val state=attempts.getJSONObject(attempts.length()-1).getJSONObject("phases").getJSONObject("CHAT_STATE_POST")
        assertEquals(2,state.getInt("count"));assertEquals(privateText.toByteArray().size+1L,state.getLong("totalValue"));assertEquals(privateText.toByteArray().size.toLong(),state.getLong("maxValue"))
    }
}
