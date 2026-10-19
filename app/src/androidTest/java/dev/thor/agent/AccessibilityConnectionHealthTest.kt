package dev.thor.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.thor.tools.diagnostic.ThorAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityConnectionHealthTest {
    @Test fun retainedServiceWithoutFrameworkConnectionIsInvalidated() {
        val previous=ThorAccessibilityService.instance
        val callback=ThorAccessibilityService.invalidated
        var invalidations=0
        try {
            // A retained Java service object has no live framework connection.
            // This was previously sufficient for both UI and tools to report READY.
            ThorAccessibilityService.instance=ThorAccessibilityService()
            ThorAccessibilityService.invalidated={invalidations++}
            assertFalse(ThorAccessibilityService.checkConnection())
            assertNull(ThorAccessibilityService.instance)
            assertEquals(1,invalidations)
            assertFalse(ThorAccessibilityService.checkConnection())
            assertEquals(1,invalidations)
        } finally {
            ThorAccessibilityService.instance=previous
            ThorAccessibilityService.invalidated=callback
        }
    }
}
