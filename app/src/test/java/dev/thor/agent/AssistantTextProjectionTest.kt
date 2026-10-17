package dev.thor.agent

import org.junit.Assert.*
import org.junit.Test

class AssistantTextProjectionTest {
    private fun text(seq:Int,block:String,value:String)=obj("type" to "text_delta","runId" to "r","sessionId" to "s","sequence" to seq,"data" to obj("text" to value,"blockId" to block))
    @Test fun reconstructsRealMessageBlocksRegardlessOfTheNonTextEventPage() {
        val rows=listOf(text(1,"1:0","first"),text(2,"1:0"," message"),text(701,"2:0","latest"),text(702,"2:0"," reply"))
        val before=rows.map {it.toString()};val projection=AssistantTextProjection();rows.asReversed().forEach {projection.appendDescending(it)}
        val result=projection.snapshot()!!
        assertEquals(listOf("first message","latest reply"),result.map {it.getJSONObject("data").getString("text")})
        assertEquals(listOf(1,701),result.map {it.getInt("sequence")});assertEquals(listOf(2,702),result.map {it.getInt("lastSequence")})
        assertEquals(before,rows.map {it.toString()});assertEquals(result.map {it.toString()},projection.snapshot()!!.map {it.toString()})
    }
    @Test fun aggregateRowsKeepSourceRangesAndRepeatedCharactersAreNeverDeduplicated() {
        val projection=AssistantTextProjection()
        projection.appendDescending(text(5,"2:0","哈哈").put("lastSequence",30))
        projection.appendDescending(text(1,"1:0","哈哈"))
        val result=projection.snapshot()!!;assertEquals(2,result.size);assertEquals(30,result.last().getInt("lastSequence"))
        assertEquals("哈哈哈哈",result.joinToString(""){it.getJSONObject("data").getString("text")})
    }
    @Test fun totalBudgetKeepsTheLatestSuffixAndNeverStartsWithHalfAnEmoji() {
        val projection=AssistantTextProjection(7)
        assertTrue(projection.appendDescending(text(3,"2:0","latest")))
        assertTrue(projection.appendDescending(text(2,"1:0","😀a")))
        assertFalse(projection.appendDescending(text(1,"1:0","old")))
        val result=projection.snapshot()!!;assertEquals(listOf("a","latest"),result.map {it.getJSONObject("data").getString("text")})
        assertTrue(result.first().getJSONObject("data").getBoolean("textTruncated"));assertEquals(2L,result.first().getJSONObject("data").getLong("omittedUtf16Units"))
        assertTrue(result.sumOf {it.getJSONObject("data").getString("text").length}<=7)
    }
    @Test fun missingLegacyBoundaryRetainsSavedMessageFallbackInsteadOfInventingSegments() {
        val projection=AssistantTextProjection();val old=text(1,"","legacy")
        assertFalse(projection.appendDescending(old));assertNull(projection.snapshot())
        assertTrue(AssistantTextProjection().snapshot()!!.isEmpty())
    }
    @Test fun sameBlockSeparatedByOtherEventsKeepsItsOriginalPositionsAndDistinctKeys() {
        val projection=AssistantTextProjection();projection.appendDescending(text(3,"1:0","after"));projection.appendDescending(text(1,"1:0","before"))
        val rows=projection.snapshot()!!
        assertEquals(listOf(1,3),rows.map {it.getInt("sequence")});assertEquals(listOf("before","after"),rows.map {it.getJSONObject("data").getString("text")})
        assertEquals(2,rows.map {it.getString("presentationSegment")}.toSet().size)
    }
    @Test fun rowLimitMarksAnIncompleteMessageButNotADifferentOlderReply() {
        val projection=AssistantTextProjection();projection.appendDescending(text(5,"2:0","kept"));projection.omitOlder(text(4,"2:0","omitted"))
        assertTrue(projection.snapshot()!!.single().getJSONObject("data").getBoolean("textTruncated"))
        val complete=AssistantTextProjection();complete.appendDescending(text(5,"2:0","kept"));complete.omitOlder(text(4,"1:0","old reply"))
        assertFalse(complete.snapshot()!!.single().getJSONObject("data").optBoolean("textTruncated"))
    }
}
