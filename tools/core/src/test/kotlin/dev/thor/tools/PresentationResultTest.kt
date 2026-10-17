package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class PresentationResultTest {
    @Test fun displayEvidenceIsNotPartOfModelResultDataAndSurvivesReceiptCopies() {
        val data=mapOf<String,Any?>("submitted" to true)
        val visual=mapOf("visual" to mapOf("frameId" to "historical-frame","points" to listOf(mapOf("x" to 120,"y" to 50))))
        val result=ToolResult(100,"native",Availability(Status.READY,"","native"),data,presentation=visual)
        assertEquals(data,result.data)
        assertFalse(result.data.containsKey("visual"))
        assertEquals(visual,result.copy(snapshotId="completed-read",outcome="unknown").presentation)
    }

    @Test fun resultsWithoutVisualEvidenceStayCompatible() {
        assertTrue(ToolResult(100,"native",Availability(Status.READY,"","native")).presentation.isEmpty())
    }
}
