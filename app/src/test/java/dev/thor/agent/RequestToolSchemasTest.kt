package dev.thor.agent

import dev.thor.tools.*
import org.junit.Assert.*
import org.junit.Test

class RequestToolSchemasTest {
    @Test fun issuedDeclarationsStayValidWhenCurrentExecutionAvailabilityChanges() {
        val schemas=RequestToolSchemas()
        val original=setOf("input.tap","perception.capture_screen","agent.sleep")
        val id=schemas.issue(7,12,original)
        val executable=setOf("perception.capture_screen","agent.sleep")
        schemas.issue(7,12,executable)
        schemas.requireMatch(id,7,12,original.toList())
        // Declaring the old schema is not an execution grant.
        val rejected=assertThrows(ToolFailure::class.java) {
            ToolPublication.requirePublished("input.tap",executable,mapOf("input.tap" to Code.TARGET_CHANGED))
        }
        assertEquals(Code.TARGET_CHANGED,rejected.code)
    }
    @Test fun onlyFixedTargetChangedEnabledInputCanRemainInDeclarations() {
        val ready=setOf("perception.capture_screen")
        val rejections=mapOf("input.tap" to Code.TARGET_CHANGED,"perception.ui_tree" to Code.TARGET_CHANGED,
            "input.key_press" to Code.PERMISSION_MISSING,"input.scroll" to Code.SECURE_CONTENT,"input.drag" to Code.DISPLAY_GONE)
        assertEquals(setOf("perception.capture_screen","input.tap"),RequestToolSchemas.declarations(ready,rejections,setOf("input.tap","input.key_press","input.scroll","input.drag")))
        assertEquals(ready,RequestToolSchemas.declarations(ready,rejections,emptySet()))
    }
    @Test fun schemasRequireExactDeclarationsProfileEpochAndRunOwnership() {
        val schemas=RequestToolSchemas();val id=schemas.issue(7,12,setOf("input.tap","agent.sleep"))
        for(declared in listOf(listOf("input.tap"),listOf("input.tap","agent.sleep","debug.shell"),listOf("input.tap","agent.sleep","agent.sleep"))) {
            assertThrows(IllegalArgumentException::class.java) {schemas.requireMatch(id,7,12,declared)}
        }
        assertThrows(IllegalArgumentException::class.java) {schemas.requireMatch(id,8,12,listOf("input.tap","agent.sleep"))}
        assertThrows(IllegalArgumentException::class.java) {schemas.requireMatch(id,7,13,listOf("input.tap","agent.sleep"))}
        assertThrows(IllegalStateException::class.java) {RequestToolSchemas().requireMatch(id,7,12,listOf("input.tap","agent.sleep"))}
        schemas.requireMatch(id,7,12,listOf("agent.sleep","input.tap"))
    }
    @Test fun repeatedSchemasReuseIdsAndStorageIsBoundedWhileCurrentAndPreviousStayPinned() {
        val schemas=RequestToolSchemas(4)
        val first=schemas.issue(7,12,setOf("first"));schemas.requireMatch(first,7,12,listOf("first"))
        val second=schemas.issue(7,12,setOf("second"));schemas.requireMatch(second,7,12,listOf("second"))
        assertEquals(second,schemas.issue(7,12,setOf("second")))
        var transient=""
        repeat(100) {index->transient=schemas.issue(7,12,setOf("transient-$index"));assertTrue(schemas.size()<=4)}
        schemas.requireMatch(first,7,12,listOf("first"));schemas.requireMatch(second,7,12,listOf("second"))
        assertEquals(4,schemas.size())
        assertThrows(IllegalStateException::class.java) {schemas.requireMatch("missing",7,12,emptyList())}
        assertNotEquals(first,transient)
    }
}
