package dev.thor.inputlab

import org.junit.Assert.*
import org.junit.Test

class RunnerEvidenceModeTest {
    @Test fun omittedModePreservesAgentAttribution() {
        assertEquals("agent_e2e",RunnerEvidenceMode.parse())
    }
    @Test fun explicitPlatformModeRetainsItsAttributionWithoutCoercion() {
        assertEquals("manual_platform_api",RunnerEvidenceMode.parse("manual_platform_api"))
        assertEquals("agent_e2e",RunnerEvidenceMode.parse("agent_e2e"))
    }
    @Test fun unsupportedOrAmbiguousModeCannotClaimPhysicalOrProductEvidence() {
        for(value in listOf("physical_baseline","manual_product_gateway","","AGENT_E2E"," agent_e2e","manual_platform_api ")) {
            assertThrows("mode=$value",IllegalArgumentException::class.java) { RunnerEvidenceMode.parse(value) }
        }
    }
    @Test fun presentNullAndWrongBundleTypesCannotSilentlyBecomeAgentEvidence() {
        for(value in listOf(null,1,true,listOf("manual_platform_api"),StringBuilder("manual_platform_api"))) {
            assertThrows("value=$value",IllegalArgumentException::class.java) { RunnerEvidenceMode.parse(value) }
        }
    }
}
