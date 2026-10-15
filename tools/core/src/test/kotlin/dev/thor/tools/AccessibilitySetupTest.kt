package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class AccessibilitySetupTest {
    @Test fun preservesOtherServicesAndDoesNotDuplicateOwnService() {
        val existing="one.app/.Reader:two.app/two.app.Reader"
        val merged=AccessibilitySetup.mergedServices(existing,"dev.thor.agent")
        assertEquals(existing+":dev.thor.agent/dev.thor.tools.diagnostic.ThorAccessibilityService",merged)
        assertEquals(merged,AccessibilitySetup.mergedServices(merged,"dev.thor.agent"))
    }
    @Test fun emptyAndNullSettingsOnlyAddOwnFixedComponent() {
        for(value in listOf(null,"","null"))assertEquals("dev.thor.agent/dev.thor.tools.diagnostic.ThorAccessibilityService",AccessibilitySetup.mergedServices(value,"dev.thor.agent"))
    }
    @Test fun malformedOrOversizedSettingsCannotBecomeCommandsOrOtherComponents() {
        for(value in listOf("other.app/.Reader;echo bad","other.app/.Reader\nother", "x".repeat(16385)))assertThrows(ToolFailure::class.java) {AccessibilitySetup.mergedServices(value,"dev.thor.agent")}
        for(pkg in listOf("other/app", "dev.thor.agent;bad", "dev.thor.agent\n"))assertThrows(ToolFailure::class.java) {AccessibilitySetup.mergedServices("",pkg)}
    }
}
