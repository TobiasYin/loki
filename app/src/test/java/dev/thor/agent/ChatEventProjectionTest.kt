package dev.thor.agent

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ChatEventProjectionTest {
    private fun event(sequence:Int,type:String="text_delta",text:String="甲")=obj("sessionId" to "s","runId" to "r","sequence" to sequence,"type" to type,"data" to obj("text" to text,"blockId" to "one"))
    @Test fun thoughtsKeepTheirRealPositionAroundAnOperationAndSecondProjectionPreservesBoundaries() {
        val events=JSONArray().put(event(1,"thinking_delta","先看"))
            .put(event(2,"thinking_delta","屏幕"))
            .put(event(3,"tool_call_update").put("toolCallId","call"))
            .put(event(4,"tool_started").put("toolCallId","call"))
            .put(event(5,"tool_result").put("toolCallId","call"))
            .put(event(6,"thinking_delta","再决定"))
            .put(event(7,"text_delta","已完成"))
        val index=EventIndex();val cache=ChatEventProjection();val rows=cache.project(events,"s",index)
        assertEquals(listOf("thinking_delta","tool_call_update","tool_started","tool_result","thinking_delta","text_delta"),rows.map {it.getString("type")})
        assertEquals(listOf("先看屏幕","再决定"),rows.filter {it.getString("type")=="thinking_delta"}.map {it.getJSONObject("data").getString("text")})
        val again=PresentationEventFold();rows.forEach(again::append)
        assertEquals(JSONArray(rows).toString(),JSONArray(again.snapshot()).toString())
        events.put(event(8,"tool_call_update","latest arguments").put("toolCallId","call"))
        val update=cache.project(events,"s",index).single {it.getString("type")=="tool_call_update"}
        assertEquals(3,update.getInt("sequence"));assertEquals(8,update.getInt("lastSequence"))
        assertEquals("latest arguments",update.getJSONObject("data").getString("text"))
    }
    @Test fun aggregateTextBudgetStaysBoundedAcrossManyAlternatingThoughtAndToolSegments() {
        val fold=PresentationEventFold()
        repeat(20){i->
            fold.append(event(i*2+1,"thinking_delta","😀".repeat(4000)))
            fold.append(event(i*2+2,"tool_result").put("toolCallId","call-$i"))
        }
        val rows=fold.snapshot();val thoughts=rows.filter {it.getString("type")=="thinking_delta"}
        assertEquals(20,thoughts.size)
        assertTrue(thoughts.sumOf {it.getJSONObject("data").getString("text").length}<=65536)
        assertTrue(thoughts.first().getJSONObject("data").getBoolean("textTruncated"))
        assertTrue(thoughts.all {it.getJSONObject("data").getString("text").firstOrNull()?.isLowSurrogate()!=true})
        assertEquals(20,rows.count {it.getString("type")=="tool_result"})
    }
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
    @Test fun longMultiBlockThinkingIsBoundedPerRunWithoutChangingDurableFragments() {
        val events=JSONArray();val original=StringBuilder()
        repeat(120) {i->val text="block-$i:"+"思考😀".repeat(300);original.append(text);events.put(event(i+1,"thinking_delta",text).also {it.getJSONObject("data").put("blockId","$i:0")})}
        val before=events.toString();val cache=ChatEventProjection();val index=EventIndex()
        val projected=cache.project(events,"s",index)
        assertEquals(1,projected.size);val data=projected.single().getJSONObject("data")
        assertTrue(data.getString("text").length<=65536);assertTrue(data.getBoolean("textTruncated"));assertTrue(data.getLong("omittedUtf16Units")>0)
        assertTrue(original.toString().endsWith(data.getString("text")));assertFalse(data.getString("text").first().isLowSurrogate())
        assertEquals(before,events.toString());assertEquals(120L,cache.foldedEvents)
        repeat(10){cache.project(events,"s",index)};assertEquals(120L,cache.foldedEvents)
        events.put(event(121,"thinking_delta","最新尾部").also {it.getJSONObject("data").put("blockId","new")})
        val fresh=cache.project(events,"s",index).single().getJSONObject("data")
        assertTrue(fresh.getString("text").endsWith("最新尾部"));assertTrue(fresh.getString("text").length<=65536)
    }

    @Test fun clearReleasesEveryLedgerAndTextWindowReferenceAndCanRebuildSameSource() {
        val index=EventIndex();val cache=ChatEventProjection()
        val events=JSONArray().put(event(1,"thinking_delta","think")).put(event(2,text="answer"))
            .put(event(3,"tool_call_update").put("toolCallId","call")).put(event(4,"tool_start"))
        val before=events.toString();val published=cache.project(events,"s",index);val expected=JSONArray(published).toString()
        assertEquals(4L,cache.foldedEvents)
        cache.clear();cache.clear()
        fun field(name:String):Any?=ChatEventProjection::class.java.getDeclaredField(name).apply {isAccessible=true}.get(cache)
        assertNull(field("source"));assertNull(field("selected"));assertEquals(0,field("consumed"));assertEquals(0L,cache.foldedEvents)
        assertTrue((field("fold") as PresentationEventFold).snapshot().isEmpty())
        assertEquals(before,events.toString());assertEquals(expected,JSONArray(published).toString())
        assertEquals(expected,JSONArray(cache.project(events,"s",index)).toString());assertEquals(4L,cache.foldedEvents)
        events.put(event(5,text="more"));assertTrue(cache.project(events,"s",index).any {it.getJSONObject("data").optString("text")=="more"})
        assertEquals("Published results stay readable after clearing the owner cache",expected,JSONArray(published).toString())
    }

    @Test fun clearedProjectionAndIndexCannotReuseOldToolOrTextBlocksForAnotherHost() {
        val index=EventIndex();val cache=ChatEventProjection()
        val old=JSONArray().put(event(1,text="old secret")).put(event(2,"tool_call_update").put("toolCallId","call"))
        cache.project(old,"s",index);cache.clear();index.clear()
        val next=JSONArray().put(event(1,text="new")).put(event(2,"tool_call_update","new tool").put("toolCallId","call"))
        val result=cache.project(next,"s",index)
        assertEquals(2,result.size);assertEquals("new",result.first().getJSONObject("data").getString("text"))
        assertEquals("new tool",result.last().getJSONObject("data").getString("text"));assertFalse(JSONArray(result).toString().contains("old secret"));assertEquals(2L,cache.foldedEvents)
    }

}
