package dev.thor.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillStoreTest {
    private fun skill(name:String="game-guide",body:String="SECRET_BODY_SENTINEL")="---\nname: $name\ndescription: >-\n  游戏攻略。\n  需要通关时使用。\n---\n$body"
    private fun store()=SkillStore(Files.createTempDirectory("thor-skills").toFile())
    private fun rejected(block:()->Unit) {try {block();fail("Expected rejection")}catch(_:IllegalArgumentException) {}}
    @Test fun metadataDoesNotLoadBodiesAndReopenPreservesResources() {
        val root=Files.createTempDirectory("thor-skills").toFile();val store=SkillStore(root)
        val entry=store.save(mapOf("SKILL.md" to skill().toByteArray(),"references/攻略.md" to "打法".toByteArray()))
        assertFalse(store.catalog().toString().contains("SECRET_BODY_SENTINEL"))
        assertTrue(store.catalog().getJSONObject(0).getString("description").contains("需要通关"))
        assertEquals("打法",SkillStore(root).read("game-guide","references/攻略.md").getString("content"))
        assertTrue(store.read("game-guide").getString("content").contains("SECRET_BODY_SENTINEL"))
        store.setEnabled("game-guide",false,entry.getString("hash"));assertEquals(0,store.catalog(true).length())
        rejected {store.read("game-guide",enabledOnly=true)}
    }
    @Test fun updatesAreAtomicCompareVersionsAndKeepResources() {
        val store=store();val first=store.save(mapOf("SKILL.md" to skill().toByteArray(),"references/a.md" to "old".toByteArray()))
        rejected {store.save(mapOf("SKILL.md" to skill(body="new").toByteArray()))}
        assertTrue(store.read("game-guide").getString("content").contains("SECRET_BODY_SENTINEL"))
        val files=store.textPackage(skill(body="new"),JSONObject().put("references/b.md","new resource"),first.getString("hash"))
        val updated=store.save(files,first.getString("hash"));assertNotEquals(first.getString("hash"),updated.getString("hash"))
        assertEquals("old",store.read("game-guide","references/a.md").getString("content"))
        rejected {store.delete("game-guide",first.getString("hash"))}
        store.delete("game-guide",updated.getString("hash"));assertEquals(0,store.catalog().length())
    }
    @Test fun packageHashIncludesUnambiguousFileBoundaries() {
        val first=store().save(mapOf("SKILL.md" to (skill()+"references/a.md\u0000extra").toByteArray()))
        val second=store().save(mapOf("SKILL.md" to skill().toByteArray(),"references/a.md" to "extra".toByteArray()))
        assertNotEquals(first.getString("hash"),second.getString("hash"))
    }
    @Test fun builtinCannotBeOverwrittenOrDeletedButCanBeDisabled() {
        val store=store();store.installBuiltin(skill());val entry=store.catalog().getJSONObject(0)
        rejected {store.delete("game-guide",entry.getString("hash"))}
        rejected {store.save(mapOf("SKILL.md" to skill(body="change").toByteArray()),entry.getString("hash"))}
        store.setEnabled("game-guide",false);assertEquals(0,store.catalog(true).length())
    }
    @Test fun builtinUpgradeUpdatesReferencesAndPreservesDisabledChoice() {
        val store=store();store.installBuiltin(mapOf("SKILL.md" to skill().toByteArray(),"references/device.md" to "old".toByteArray()))
        val hash=store.catalog().getJSONObject(0).getString("hash");store.setEnabled("game-guide",false)
        store.installBuiltin(mapOf("SKILL.md" to skill().toByteArray(),"references/device.md" to "new".toByteArray()))
        assertEquals(0,store.catalog(true).length());assertNotEquals(hash,store.catalog().getJSONObject(0).getString("hash"))
        assertEquals("new",store.read("game-guide","references/device.md").getString("content"))
        assertFalse(store.catalog().toString().contains("SECRET_BODY_SENTINEL"))
    }
    private fun zip(vararg entries:Pair<String,ByteArray>):ByteArray=ByteArrayOutputStream().also {out->ZipOutputStream(out).use {z->entries.forEach {(path,bytes)->z.putNextEntry(ZipEntry(path));z.write(bytes);z.closeEntry()}}}.toByteArray()
    @Test fun zipRejectsTraversalMultipleSkillsAndOversizeWithoutWriting() {
        for(path in listOf("../SKILL.md","/SKILL.md","x/../../SKILL.md","x\\SKILL.md","x//SKILL.md"))rejected {SkillStore.unpack(zip(path to skill().toByteArray()).inputStream(),true)}
        rejected {SkillStore.unpack(zip("a/SKILL.md" to skill().toByteArray(),"b/SKILL.md" to skill("other").toByteArray()).inputStream(),true)}
        rejected {SkillStore.unpack(zip("SKILL.md" to ByteArray(65537)).inputStream(),true)}
        val files=SkillStore.unpack(zip("repo/game-guide/SKILL.md" to skill().toByteArray(),"repo/game-guide/references/a.txt" to "hello".toByteArray()).inputStream(),true)
        assertEquals(setOf("SKILL.md","references/a.txt"),files.keys)
    }
    @Test fun yamlRejectsDuplicateKeysTagsAndBadNames() {
        for(content in listOf(skill("../bad"),skill("bad--name"),skill().replace("name: game-guide","name: game-guide\nname: another"),skill().replace("description: >-\n  游戏攻略。\n  需要通关时使用。","description: !!java.lang.String evil"))) {
            try {SkillStore.manifest(content);fail("Invalid YAML accepted")}catch(_:Exception) {}
        }
    }
}
