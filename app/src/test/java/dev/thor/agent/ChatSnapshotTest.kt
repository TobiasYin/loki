package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatSnapshotTest {
    private fun state():JSONObject {
        val s=obj("selected" to "b","sessions" to JSONArray(),"runs" to JSONArray(),"messages" to JSONArray(),"events" to JSONArray(),"attachments" to JSONArray())
        for(id in listOf("a","b","c")) {
            s.getJSONArray("sessions").put(obj("id" to id,"title" to id))
            s.getJSONArray("runs").put(obj("id" to "run-$id","sessionId" to id,"status" to "completed","turn" to obj("text" to "private-turn-$id"),"contexts" to JSONArray().put("private-context-$id")))
            s.getJSONArray("messages").put(obj("id" to "message-$id","sessionId" to id,"runId" to "run-$id","text" to "message-$id"))
            s.getJSONArray("events").put(obj("id" to "event-$id","sessionId" to id,"runId" to "run-$id","data" to obj("text" to "event-$id")))
            s.getJSONArray("attachments").put(obj("id" to "image-$id","sessionId" to id,"runId" to "run-$id","hash" to "hash-$id","data" to "private-image-$id","path" to "/private/$id"))
        }
        return s
    }
    @Test fun selectedChatAndActiveRunRemainVisibleWithoutUnrelatedBodies() {
        val source=state();val snapshot=chatSnapshot(source,"run-a")
        assertEquals(3,snapshot.getJSONArray("sessions").length());assertEquals(2,snapshot.getJSONArray("runs").length())
        assertTrue(snapshot.toString().contains("message-b"));assertTrue(snapshot.toString().contains("event-a"));assertFalse(snapshot.toString().contains("message-c"))
        assertFalse(snapshot.toString().contains("private-image"));assertFalse(snapshot.toString().contains("private-turn"));assertFalse(snapshot.toString().contains("private-context"))
        assertTrue(snapshot.toString().contains("hash-a"))
    }
    @Test fun snapshotIsIsolatedAndDoesNotDeleteOrRewriteDurableHistory() {
        val source=state();val before=source.toString();val snapshot=chatSnapshot(source,null)
        assertEquals(before,source.toString());assertEquals(1,snapshot.getJSONArray("messages").length())
        source.getJSONArray("messages").getJSONObject(1).put("text","changed after publish")
        assertEquals("message-b",snapshot.getJSONArray("messages").getJSONObject(0).getString("text"))
        snapshot.getJSONArray("sessions").getJSONObject(0).put("title","UI mutation")
        assertEquals("a",source.getJSONArray("sessions").getJSONObject(0).getString("title"))
    }
}
