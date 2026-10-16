package dev.thor.agent

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ChatEventProjectionTest {
    private fun event(sequence:Int,type:String="text_delta",text:String="甲")=obj("sessionId" to "s","runId" to "r","sequence" to sequence,"type" to type,"data" to obj("text" to text,"blockId" to "one"))
    @Test fun largeLedgerIsFoldedOnceAndOnlyNewFragmentsAreVisited() {
        val events=JSONArray();repeat(20000){events.put(event(it+1))}
        val index=EventIndex();val projection=ChatEventProjection()
        val first=projection.project(events,"s",index);assertEquals(1,first.size);assertEquals(20000,first.single().getJSONObject("data").getString("text").length)
        assertEquals(20000L,projection.foldedEvents)
        repeat(50){projection.project(events,"s",index)};assertEquals(20000L,projection.foldedEvents)
        events.put(event(20001,text="😀"));val latest=projection.project(events,"s",index)
        assertEquals(20001L,projection.foldedEvents);assertTrue(latest.single().getJSONObject("data").getString("text").endsWith("😀"))
        assertEquals("Durable source events must remain individual fragments","甲",events.getJSONObject(0).getJSONObject("data").getString("text"))
    }
    @Test fun appendProjectionMatchesFullProjectionAndNeverMutatesAlreadyPublishedSnapshot() {
        val events=JSONArray().put(event(1,"thinking_delta","想"))
        val state=obj("selected" to "s","sessions" to JSONArray(),"messages" to JSONArray(),"runs" to JSONArray(),"attachments" to JSONArray(),"events" to events)
        val index=EventIndex();val cache=ChatEventProjection()
        val previous=chatSnapshot(state,null,cache.project(events,"s",index)).toString()
        events.put(event(2,"thinking_delta","明白"))
        events.put(obj("sessionId" to "s","runId" to "r","sequence" to 3,"type" to "tool_call_update","toolCallId" to "c","data" to obj("arguments" to obj("x" to 1))))
        events.put(obj("sessionId" to "s","runId" to "r","sequence" to 4,"type" to "tool_call_update","toolCallId" to "c","data" to obj("arguments" to obj("x" to 2))))
        events.put(event(5))
        assertEquals(chatSnapshot(state,null,index.sessionEvents(events,"s")).toString(),chatSnapshot(state,null,cache.project(events,"s",index)).toString())
        assertFalse(previous.contains("明白"));assertEquals("想",events.getJSONObject(0).getJSONObject("data").getString("text"))
    }
    @Test fun switchingConversationsAndReplacingRecoveredArraysNeverLeaksAnotherSession() {
        val index=EventIndex();val cache=ChatEventProjection();val events=JSONArray().put(event(1))
        events.put(event(2,text="乙").put("sessionId","other"))
        assertEquals("甲",cache.project(events,"s",index).single().getJSONObject("data").getString("text"))
        assertEquals("乙",cache.project(events,"other",index).single().getJSONObject("data").getString("text"))
        val recovered=JSONArray().put(event(8,text="恢复"))
        assertEquals("恢复",cache.project(recovered,"s",index).single().getJSONObject("data").getString("text"))
    }
}
