package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScreenshotHistoryPolicyTest {
    private fun capture()=JSONObject().put("id","image").put("attachmentId","image").put("sessionId","chat")
        .put("runId","run").put("mimeType","image/jpeg").put("source",JSONArray(listOf("screen_perception")))
        .put("data","historical-image").put("observedAt",100L).put("expiresAt",600_100L)
    private fun owner()=JSONObject().put("id","run").put("sessionId","chat").put("probe",false)

    @Test fun newCapturesRetainHistoryButConfigurationProbeKeepsItsShortLifetime() {
        assertEquals(9_007_199_254_740_991L,ScreenshotHistoryPolicy.captureExpiry(100L,false))
        assertEquals(600_100L,ScreenshotHistoryPolicy.captureExpiry(100L,true))
    }
    @Test fun oldOwnedScreenshotIsNormalizedOnlyInReturnedCopy() {
        val saved=capture();val before=saved.toString()
        val returned=ScreenshotHistoryPolicy.replay(saved,"chat") {owner()}!!
        assertNotSame(saved,returned);assertEquals(before,saved.toString())
        assertEquals(ScreenshotHistoryPolicy.HISTORY_EXPIRES_AT,returned.getLong("expiresAt"))
        for(key in listOf("id","attachmentId","sessionId","runId","mimeType","data","observedAt"))assertEquals(saved.get(key),returned.get(key))
        assertFalse(returned.has("snapshotId"));assertFalse(returned.has("frameId"));assertFalse(returned.has("nodeRef"))
    }
    @Test fun repeatedReadsDoNotMutateStoredRowOrDependOnWallClock() {
        val saved=capture()
        repeat(3) {assertEquals(ScreenshotHistoryPolicy.HISTORY_EXPIRES_AT,ScreenshotHistoryPolicy.replay(saved,"chat") {owner()}!!.getLong("expiresAt"))}
        assertEquals(600_100L,saved.getLong("expiresAt"))
    }
    @Test fun userUploadsAndNonCaptureSourcesNeverReadOwnerOrExtendExpiry() {
        for(attachment in listOf(capture().put("source",JSONArray()),capture().put("source",JSONArray(listOf("screen_perception","web_search"))),capture().put("mimeType","image/png"),capture().put("source",JSONArray(listOf("web_search"))),capture().put("runId",JSONObject.NULL))) {
            assertSame(attachment,ScreenshotHistoryPolicy.replay(attachment,"chat") {error("Not a screenshot")})
            assertEquals(600_100L,attachment.getLong("expiresAt"))
        }
    }
    @Test fun configurationProbeMissingOwnerAndMismatchedOwnerDoNotExtendExpiry() {
        for(run in listOf(null,owner().put("probe",true),owner().put("sessionId","other-chat"),owner().put("id","other-run"))) {
            val attachment=capture()
            assertSame(attachment,ScreenshotHistoryPolicy.replay(attachment,"chat") {run})
            assertEquals(600_100L,attachment.getLong("expiresAt"))
        }
    }
    @Test fun differentSessionAndBlankSessionDoNotNormalizeOrLookUpRun() {
        val attachment=capture()
        for(session in listOf("other-chat",""))assertSame(attachment,ScreenshotHistoryPolicy.replay(attachment,session) {error("Wrong session")})
        assertNull(ScreenshotHistoryPolicy.replay(null,"chat") {error("No attachment")})
    }
}
