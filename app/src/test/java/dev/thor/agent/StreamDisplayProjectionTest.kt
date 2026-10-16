package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StreamDisplayProjectionTest {
    private fun event(sequence:Int,type:String="thinking_delta",text:String="chunk")=obj("eventId" to "e$sequence","sessionId" to "s","runId" to "r","sequence" to sequence,"type" to type,"data" to if(type.endsWith("_delta"))obj("text" to text) else JSONObject())
    private fun message()=obj("runId" to "r","role" to "assistant","text" to "","thinking" to "")
    @Test fun cumulativeLongStreamKeepsFullLedgerAndContextWhileDisplayTailIsBoundedAndOrdinaryTerminalStillCommits() {
        val ledger=JSONArray();val m=message();val run=obj("id" to "r","sessionId" to "s","status" to "generating")
        val full=StringBuilder();var sequence=0
        for(type in listOf("thinking_delta","text_delta"))repeat(40) {index->
            val text="[$index]"+"中文😀".repeat(600);full.append(text)
            StreamDisplayProjection.append(ledger,listOf(m),run,event(++sequence,type,text),"s","r",sequence,"stream update")
        }
        val ledgerText=ledger.objects().joinToString(""){it.getJSONObject("data").getString("text")}
        assertEquals(full.toString(),ledgerText);assertTrue(ledgerText.length>65536*2)
        for(field in listOf("text","thinking")) {
            assertTrue(m.getString(field).length<=65536);assertTrue(m.getBoolean("${field}Truncated"));assertTrue(m.getLong("${field}OmittedUtf16Units")>0)
            assertFalse(m.getString(field).first().isLowSurrogate())
        }
        // The display helper neither owns nor truncates the durable DomainTurn.
        val context=full.toString();run.put("turn",obj("messages" to JSONArray().put(obj("kind" to "assistant","text" to context)),"complete" to true))
        val completed=obj("sessionId" to "s","runId" to "r","state" to "completed")
        StreamDisplayProjection.append(ledger,listOf(m),run,event(++sequence,"state"),"s","r",sequence,"completed",completed)
        assertEquals("completed",run.getString("status"));assertEquals("completed",run.getJSONObject("runtime").getString("state"));assertFalse(run.getBoolean("expanded"))
        assertEquals(context,run.getJSONObject("turn").getJSONArray("messages").getJSONObject(0).getString("text"));assertEquals(81,ledger.length())
    }
    @Test fun aSingleLegalDeltaLargerThanDisplayWindowIsDurableWithoutRejection() {
        val ledger=JSONArray();val m=message();val run=JSONObject();val text="x".repeat(65535)+"😀"+"tail".repeat(20000)
        StreamDisplayProjection.append(ledger,listOf(m),run,event(1,"thinking_delta",text),"s","r",1,"update")
        assertEquals(text,ledger.getJSONObject(0).getJSONObject("data").getString("text"));assertEquals(text.takeLast(65536),m.getString("thinking"));assertTrue(m.getBoolean("thinkingTruncated"))
    }
    @Test fun displayTailPreservesSurrogateBoundaryAndAccumulatesOmittedCount() {
        val first=StreamTextWindow.append("","abc😀tail",maxUnits=5)
        assertEquals("tail",first.text);assertEquals(5L,first.omittedUtf16Units)
        val second=StreamTextWindow.append(first.text,"!",first.omittedUtf16Units,maxUnits=5)
        assertEquals("tail!",second.text);assertEquals(5L,second.omittedUtf16Units)
        assertEquals(9_007_199_254_740_991L,StreamTextWindow.append("12345","6",Long.MAX_VALUE,maxUnits=5).omittedUtf16Units)
    }
    @Test fun malformedDeltaOrCommitCannotAppendAnEventOrMutatePriorMessageRunOrTurn() {
        for(kind in listOf("text","commit","message")) {
            val ledger=JSONArray();val m=message().put("text","prior");val run=obj("id" to "r","status" to "generating","turn" to obj("text" to "prior context"))
            val beforeMessage=m.toString();val beforeRun=run.toString()
            val e=event(1,"text_delta","late");if(kind=="text")e.getJSONObject("data").put("text",JSONObject())
            val commit=if(kind=="commit")obj("sessionId" to "wrong","runId" to "r","state" to "completed") else null
            try {StreamDisplayProjection.append(ledger,if(kind=="message")emptyList() else listOf(m),run,e,"s","r",1,"update",commit);fail("Invalid input accepted")}catch(_:Exception){}
            assertEquals(0,ledger.length());assertEquals(beforeMessage,m.toString());assertEquals(beforeRun,run.toString())
        }
    }
}
