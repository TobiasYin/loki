package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class FileBackendSelectionTest {
    @Test fun sharedAbsolutePathsUseDeviceBackendWithoutRequiringTheModelToKnowAnImplementationFlag() {
        for(path in listOf("/sdcard","/sdcard/Android/data/a.b/files/ErrorLogs/SMAPI-latest.txt","/storage/self/primary/Download","/storage/emulated/0","/storage/ABCD-1234","/mnt/sdcard/log.txt"))
            assertEquals(path,"shizuku",FileBackendSelection.choose(mapOf("path" to path)))
    }
    @Test fun explicitBackendAndLegacyRelativeConfigReadsStayCompatible() {
        assertEquals("synthetic",FileBackendSelection.choose(emptyMap()))
        assertEquals("synthetic",FileBackendSelection.choose(mapOf("path" to "settings.json")))
        assertEquals("synthetic",FileBackendSelection.choose(mapOf("path" to "/sdcardevil/private")))
        assertEquals("synthetic",FileBackendSelection.choose(mapOf("path" to "/sdcard/log.txt","backend" to "synthetic")))
        assertEquals("shizuku",FileBackendSelection.choose(mapOf("path" to ".","backend" to "shizuku")))
        try {FileBackendSelection.choose(mapOf("backend" to "root"));fail("Unknown backend accepted")}catch(e:ToolFailure){assertEquals(Code.INVALID_ARGUMENT,e.code)}
    }
}
