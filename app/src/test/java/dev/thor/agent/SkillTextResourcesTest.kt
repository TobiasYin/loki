package dev.thor.agent

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SkillTextResourcesTest {
    @Test fun typedFilesRetainBothReferencesAndRejectAmbiguousInputs() {
        val files=JSONArray().put(obj("path" to "references/a.md","content" to "A")).put(obj("path" to "references/b.md","content" to "B"))
        val result=skillTextResources(obj("files" to files))!!
        assertEquals("A",result.getString("references/a.md"));assertEquals("B",result.getString("references/b.md"))
        assertThrows(IllegalArgumentException::class.java){skillTextResources(obj("files" to files,"resources" to result))}
        assertThrows(IllegalArgumentException::class.java){skillTextResources(obj("files" to JSONArray().put(files.getJSONObject(0)).put(files.getJSONObject(0))))}
        assertEquals(result.toString(),skillTextResources(obj("resources" to result)).toString())
    }
}
