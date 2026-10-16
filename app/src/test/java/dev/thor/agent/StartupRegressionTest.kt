package dev.thor.agent

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class StartupRegressionTest {
    @Test fun titlesPreserveMeaningfulFirstLineInsteadOfFixedTenCharacters() {
        assertEquals("帮我查看上屏应用并截图然后点击",promptTitle("  帮我查看上屏应用并截图然后点击  "))
        assertEquals("123456789😀后面的文字",promptTitle("123456789😀后面的文字"))
        assertEquals("新会话",promptTitle(" \n "))
        assertEquals("查看",promptTitle("查看\n上屏"))
        assertEquals("第一行 完整标题",promptTitle("\n\t 第一行  完整标题\n第二行代码或正文"))
        val title=promptTitle("😀".repeat(600));assertEquals(512,title.codePointCount(0,title.length));assertTrue(title.endsWith("😀"))
    }
    @Test fun indexHandlesAppendDeleteAndStateRecoveryWithoutStaleRows() {
        val index=EventIndex();val events=JSONArray()
        repeat(20000) {events.put(obj("sessionId" to "old","runId" to "r-old"))}
        assertEquals(20000,index.sessionEvents(events,"old").size)
        val row=obj("sessionId" to "new","runId" to "r-new");events.put(row)
        assertSame(row,index.runEvents(events,"r-new").single())
        assertEquals(1,index.sessionEvents(events,"new").size)
        val afterDelete=JSONArray().put(row)
        assertEquals(0,index.sessionEvents(afterDelete,"old").size)
        val recovered=JSONArray().put(obj("sessionId" to "recovered","runId" to "r-new"))
        assertEquals("recovered",index.runEvents(recovered,"r-new").single().getString("sessionId"))
    }
}
