package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class AccessibilitySetupTest {
    @Test fun savedEnabledOrAuthorizedRecoveryIsTemporaryUntilTheActualServiceConnects() {
        for(recovery in listOf(false,true)) {
            val pending=AccessibilitySetup.availability(true,false,recovery,"fixture")
            assertEquals(Status.TEMPORARILY_UNAVAILABLE,pending.status)
            assertTrue(pending.reason.contains(if(recovery)"自动恢复" else "关闭后重新开启"))
        }
        assertEquals(Status.TEMPORARILY_UNAVAILABLE,AccessibilitySetup.availability(false,false,true,"fixture").status)
        assertEquals(Status.PERMISSION_MISSING,AccessibilitySetup.availability(false,false,false,"fixture").status)
        assertEquals(Status.READY,AccessibilitySetup.availability(false,true,false,"fixture").status)
    }
    @Test fun preservesOtherServicesAndDoesNotDuplicateOwnService() {
        val existing="one.app/.Reader:two.app/two.app.Reader"
        val merged=AccessibilitySetup.mergedServices(existing,"com.tobias.loki")
        assertEquals(existing+":com.tobias.loki/dev.thor.tools.diagnostic.ThorAccessibilityService",merged)
        assertEquals(merged,AccessibilitySetup.mergedServices(merged,"com.tobias.loki"))
    }
    @Test fun emptyAndNullSettingsOnlyAddOwnFixedComponent() {
        for(value in listOf(null,"","null"))assertEquals("com.tobias.loki/dev.thor.tools.diagnostic.ThorAccessibilityService",AccessibilitySetup.mergedServices(value,"com.tobias.loki"))
    }
    @Test fun malformedOrOversizedSettingsCannotBecomeCommandsOrOtherComponents() {
        for(value in listOf("other.app/.Reader;echo bad","other.app/.Reader\nother", "x".repeat(16385)))assertThrows(ToolFailure::class.java) {AccessibilitySetup.mergedServices(value,"com.tobias.loki")}
        for(pkg in listOf("other/app", "dev.thor.agent;bad", "dev.thor.agent\n"))assertThrows(ToolFailure::class.java) {AccessibilitySetup.mergedServices("",pkg)}
    }
}
