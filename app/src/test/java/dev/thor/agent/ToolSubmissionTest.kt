package dev.thor.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSubmissionTest {
    @Test fun onlyExplicitGatewayCheckpointReceiptsDetermineDispatch() {
        assertEquals("not_dispatched", ToolSubmission.fromGateway("not_submitted"))
        assertEquals("submitted", ToolSubmission.fromGateway("submitted"))
        for (outcome in listOf("observed", "unknown", "", "not_dispatched", "NOT_SUBMITTED"))
            assertEquals("unknown", ToolSubmission.fromGateway(outcome))
    }

    @Test fun localGuardDiagnosticsRequireExactKnownMessage() {
        assertEquals("input_layout_changed", ToolSubmission.targetDiagnostic("Selected screen windows/app/layout changed"))
        assertEquals("input_outside_clip", ToolSubmission.targetDiagnostic("Input leaves observed/current touchable region"))
        for (message in listOf(null, "", "Selected screen windows/app/layout changed private.app", "{\"message\":\"Frame missing/evicted\"}"))
            assertEquals(null, ToolSubmission.targetDiagnostic(message))
    }
}
