package dev.thor.inputlab

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ScenarioSessionTest {
    private fun session(dir:File,model:ScenarioModel=ScenarioModel("game",91),maxEvents:Long=20000,maxBytes:Long=8L*1024*1024)=ScenarioSession(model,"test-run","real-agent-run","trace-only","agent_e2e",dir,maxEvents,maxBytes,{100},{200})
    @Test fun manualSelectionCannotImpersonateRealAgentRunAndUsesIndependentJournal() {
        val dir=Files.createTempDirectory("scenario-manual").toFile()
        try {
            val a=ScenarioSession.manual(ScenarioModel("settings",91),dir,{100},{200})
            val b=ScenarioSession.manual(ScenarioModel("settings",91),dir,{100},{200})
            a.seal();b.seal();val report=a.snapshot()
            assertTrue(a.isManual);assertEquals("",a.agentRunId);assertNotEquals(a.attemptId,b.attemptId);assertNotEquals(a.testRunId,b.testRunId)
            assertTrue(report.contains("\"evidenceMode\":\"manual_synthetic\""));assertTrue(report.contains("\"agentRunId\":\"\""));assertFalse(report.contains("agent_e2e"))
            assertTrue(File(dir,"scenario-reports/${a.attemptId}/events.jsonl").isFile);assertTrue(File(dir,"scenario-reports/${b.attemptId}/events.jsonl").isFile)
        } finally {dir.deleteRecursively()}
    }
    @Test fun sealedEvidenceHashAndPrefixNeverChangeAfterLateInput() {
        val dir=Files.createTempDirectory("scenario-sealed").toFile()
        try {
            val s=session(dir);s.record("touch",mapOf("actionMasked" to 0),"android_dispatch");s.seal()
            val events=File(dir,"scenario-reports/${s.attemptId}/events.jsonl");val bytes=events.readBytes();val report=s.snapshot()
            assertEquals(3,events.readLines().size);assertTrue(report.contains("\"eventPrefixSha256\":\"${sha256(events)}\""));assertTrue(report.contains("\"eventPrefixBytes\":${bytes.size},"))
            s.record("touch",mapOf("late" to true),"android_dispatch");assertArrayEquals(bytes,events.readBytes());assertEquals(report,s.snapshot())
            assertTrue(report.contains("\"agentAnswerVerified\":false"));assertTrue(report.contains("\"systemInjectionProven\":false"))
        } finally {dir.deleteRecursively()}
    }
    @Test fun quotaPreservesPriorEvidenceAndPreventsFalseCompletion() {
        val dir=Files.createTempDirectory("scenario-quota").toFile()
        try {
            val m=ScenarioModel("game",92);val s=session(dir,m,maxEvents=1)
            m.gameTargets.forEach {m.pickGame(it)};assertTrue(m.completed());s.record("game_pick",emptyMap());s.seal()
            val report=s.snapshot();assertTrue(report.contains("\"incomplete\":true"));assertTrue(report.contains("\"completed\":false"));assertTrue(report.contains("\"droppedSamples\":2"))
            assertEquals(1,File(dir,"scenario-reports/${s.attemptId}/events.jsonl").readLines().size)
        } finally {dir.deleteRecursively()}
    }
    @Test fun exactByteBudgetDoesNotAppendPartialOrOversizedRows() {
        val dir=Files.createTempDirectory("scenario-bytes").toFile()
        try {
            val s=session(dir,maxBytes=1);s.seal();val report=s.snapshot()
            assertEquals(0L,File(dir,"scenario-reports/${s.attemptId}/events.jsonl").length());assertTrue(report.contains("\"incomplete\":true"));assertTrue(report.contains("\"eventCount\":0"))
        } finally {dir.deleteRecursively()}
    }
    @Test fun interruptedAttemptCannotClaimCompletionAndSavedSettingsPersistSeparately() {
        val dir=Files.createTempDirectory("scenario-interrupted").toFile()
        try {
            val m=ScenarioModel("settings",93);val s=session(dir,m)
            m.openSettings();m.chooseMode(m.desiredMode);m.toggle(m.desiredEnabled);m.slider(m.desiredVolume);m.qualifyDrag();assertTrue(m.saveSettings());s.persistSettings()
            assertEquals(Json.encode(m.settingsValues),File(dir,"scenario-reports/${s.attemptId}/saved-settings.json").readText())
            s.seal("activity_destroyed");val report=s.snapshot();assertTrue(report.contains("\"completed\":false"));assertTrue(report.contains("\"interruptedReason\":\"activity_destroyed\""))
        } finally {dir.deleteRecursively()}
    }
}
