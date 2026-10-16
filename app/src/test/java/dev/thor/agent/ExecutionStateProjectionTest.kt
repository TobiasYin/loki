package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExecutionStateProjectionTest {
    private fun session(id:String,epoch:Long)=JSONObject().put("id",id).put("capabilityEpoch",epoch)
    private fun run(id:String,sid:String,reason:String="")=JSONObject().put("id",id).put("sessionId",sid).put("status","executing_tools").put("stopReason",reason)
    private fun fields(value:JSONObject)=value.keys().asSequence().toSet()

    @Test fun everySessionEpochAndConcurrentRunStopNoticeAreRetained() {
        val input=JSONObject().put("selected","s1").put("sessions",JSONArray().put(session("s1",1)).put(session("s2",5)).put(session("s3",9)))
            .put("activeRuns",JSONArray().put(run("r1","s1")).put(run("r2","s2","用户停止")).put(run("r3","s3")))
        val projected=ExecutionStateProjection.project(input)
        assertEquals(setOf("sessions","activeRuns"),fields(projected));assertEquals(3,projected.getJSONArray("sessions").length())
        assertEquals(listOf(1L,5L,9L),(0..2).map {projected.getJSONArray("sessions").getJSONObject(it).getLong("capabilityEpoch")})
        val runs=projected.getJSONArray("activeRuns");assertEquals(3,runs.length());assertEquals("s2",runs.getJSONObject(1).getString("sessionId"));assertEquals("用户停止",runs.getJSONObject(1).getString("stopReason"))
        assertEquals("",runs.getJSONObject(0).getString("stopReason"));assertEquals("r3",runs.getJSONObject(2).getString("id"))
    }
    @Test fun onlyMissingActiveRunsFallsBackToLegacyRuns() {
        val input=JSONObject().put("sessions",JSONArray().put(session("s",3))).put("runs",JSONArray().put(run("historical","s","stop")))
        assertEquals("historical",ExecutionStateProjection.project(input).getJSONArray("activeRuns").getJSONObject(0).getString("id"))
        input.put("activeRuns",JSONArray());assertEquals(0,ExecutionStateProjection.project(input).getJSONArray("activeRuns").length())
        input.put("activeRuns",JSONObject.NULL);assertEquals(0,ExecutionStateProjection.project(input).getJSONArray("activeRuns").length())
    }
    @Test fun messagesEventsAttachmentsTurnAndCredentialFieldsNeverReachExecutionPush() {
        val privateValue="SYNTHETIC_PRIVATE_BODY"
        val s=session("s",8).put("messages",privateValue).put("credential",privateValue).put("title",privateValue)
        val r=run("r","s").put("turn",JSONObject().put("text",privateValue)).put("contexts",JSONArray().put(privateValue)).put("events",privateValue)
        val input=JSONObject().put("sessions",JSONArray().put(s)).put("activeRuns",JSONArray().put(r))
            .put("messages",privateValue).put("events",privateValue).put("attachments",privateValue).put("credentials",privateValue).put("modelProfile",privateValue)
        val result=ExecutionStateProjection.project(input)
        assertFalse(result.toString().contains(privateValue));assertEquals(setOf("id","capabilityEpoch"),fields(result.getJSONArray("sessions").getJSONObject(0)))
        assertEquals(setOf("id","sessionId","status","stopReason"),fields(result.getJSONArray("activeRuns").getJSONObject(0)))
    }
    @Test fun projectionDoesNotMutateOrShareWritableSessionAndRunRows() {
        val input=JSONObject().put("sessions",JSONArray().put(session("s",7))).put("activeRuns",JSONArray().put(run("r","s")))
            .put("messages",JSONArray().put(JSONObject().put("text","retain original")))
        val original=input.toString();val projected=ExecutionStateProjection.project(input);assertEquals(original,input.toString())
        projected.getJSONArray("sessions").getJSONObject(0).put("capabilityEpoch",100)
        projected.getJSONArray("activeRuns").getJSONObject(0).put("stopReason","changed")
        assertEquals(original,input.toString())
    }
    @Test fun outputAndHistoryTraversalAreIndependentOfHugeHistoryPayload() {
        val input=JSONObject().put("sessions",JSONArray().put(session("s",1))).put("activeRuns",JSONArray().put(run("r","s")))
        val expected=ExecutionStateProjection.project(input).toString()
        val neverSerialize=object:JSONObject() {override fun toString():String=error("History must not be serialized for execution state")}
            .put("text","中文历史".repeat(500000))
        input.put("messages",JSONArray().put(neverSerialize)).put("events",neverSerialize).put("attachments",neverSerialize)
        input.getJSONArray("activeRuns").getJSONObject(0).put("turn",neverSerialize)
        val result=ExecutionStateProjection.project(input).toString()
        assertEquals(expected,result);assertTrue(result.toByteArray(Charsets.UTF_8).size<256)
    }
}
