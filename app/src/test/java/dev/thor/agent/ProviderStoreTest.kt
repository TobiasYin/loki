package dev.thor.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderStoreTest {
    private class Memory: ProviderStore.Backend {
        var value:String?=null
        var original:JSONObject?=null
        var writes=0
        var failNextWrite=false
        var failAfterWrite=false
        var failNextCredentialWrite=false
        var failNextCredentialRemove=false
        val keys=mutableMapOf<String,String>()
        override fun read()=value
        override fun write(value:String){
            writes++
            if(failNextWrite){failNextWrite=false;if(failAfterWrite)this.value=value;throw IllegalStateException("fixture metadata write failure")}
            this.value=value
        }
        override fun legacy()=original
        override fun readCredential(ref:String)=keys[ref]
        override fun writeCredential(ref:String,value:String){keys[ref]=value;if(failNextCredentialWrite){failNextCredentialWrite=false;throw IllegalStateException("fixture credential write failure")}}
        override fun removeCredential(ref:String){keys.remove(ref);if(failNextCredentialRemove){failNextCredentialRemove=false;throw IllegalStateException("fixture credential removal failure")}}
        override fun credentialStatus(ref:String)=if(keys.containsKey(ref))"已加密保存" else "未保存"
    }
    private fun draft(id:String="account-a",kind:String="custom",url:String="https://example.test/v1")=JSONObject().put("id",id).put("kind",kind)
        .put("url",if(kind==OpenCodeZenCatalog.KIND)OpenCodeZenCatalog.BASE_URL else if(kind=="deepseek")DeepSeekCatalog.BASE_URL else if(kind=="opencode-go")"https://opencode.ai/zen/go/v1" else url)
        .put("name","账户 $id").put("model","fixture-model").put("protocol",0).put("contextBudget",32768).put("maxOutput",2048)
    private fun rejected(block:()->Unit){assertThrows(IllegalArgumentException::class.java){block()}}
    @Test fun initialEmptyStoreHasNoInventedProfile(){val memory=Memory();val store=ProviderStore(memory);assertTrue(store.list().isEmpty());assertNull(store.defaultId());assertEquals(1,memory.writes)}
    @Test fun legacyMigrationCopiesCredentialAndPreservesOriginals(){
        val memory=Memory().apply {original=draft("ignored","opencode-go").put("revision",7).put("probe","passed").put("probeRevision",7).put("probeImages",true);keys["model"]="legacy-fixture";keys["search"]="search-fixture";keys["voice"]="voice-fixture"}
        val original=memory.original.toString();val store=ProviderStore(memory)
        assertEquals("legacy",store.defaultId());val row=store.get("legacy")!!;assertEquals(7,row.getInt("revision"));assertTrue(row.getBoolean("legacyMigration"));assertTrue(row.getBoolean("legacyProbePassed"));assertTrue(row.getBoolean("legacyProbeImages"))
        assertEquals("legacy-fixture",store.credential("legacy"));assertEquals("legacy-fixture",memory.keys["model"]);assertEquals("search-fixture",memory.keys["search"]);assertEquals("voice-fixture",memory.keys["voice"]);assertEquals(original,memory.original.toString())
        ProviderStore(memory);assertEquals(1,memory.writes)
    }
    @Test fun multipleAccountsOfSameKindKeepIndependentKeys(){val memory=Memory();val store=ProviderStore(memory);store.save(draft("a","deepseek"),"key-a");store.save(draft("b","deepseek"),"key-b");assertEquals("key-a",store.credential("a"));assertEquals("key-b",store.credential("b"));assertEquals(2,store.list().size)}
    @Test fun zenAccountsPersistIndependentEncryptedKeysAndPinnedEndpoint() {
        val memory=Memory();val store=ProviderStore(memory)
        store.save(draft("zen",OpenCodeZenCatalog.KIND).put("model","big-pickle").put("protocol",2),"zen-fixture")
        store.save(draft("go","opencode-go"),"go-fixture")
        val restored=ProviderStore(memory);assertEquals(OpenCodeZenCatalog.KIND,restored.get("zen")!!.getString("kind"))
        assertEquals("zen-fixture",restored.credential("zen"));assertEquals("go-fixture",restored.credential("go"))
        assertFalse(memory.value!!.contains("zen-fixture"))
    }
    @Test fun blankSaveKeepsOnlyCurrentCredential(){val memory=Memory();val store=ProviderStore(memory);store.save(draft("a"),"key-a");store.save(draft("b"),"key-b");store.save(draft("a").put("name","new"),"");assertEquals("key-a",store.credential("a"));assertEquals("key-b",store.credential("b"));assertEquals(2,store.get("a")!!.getInt("revision"))}
    @Test fun explicitRemovalAndDeleteOnlyAffectTheirRecord(){val memory=Memory();val store=ProviderStore(memory);store.save(draft("a"),"key-a");store.save(draft("b"),"key-b");store.save(draft("a"),removeKey=true);assertNull(store.credential("a"));assertEquals("key-b",store.credential("b"));store.delete("a");assertEquals("b",store.defaultId());assertEquals("key-b",store.credential("b"))}
    @Test fun providerKindCannotSilentlyReuseAnExistingCredential(){val memory=Memory();val store=ProviderStore(memory);store.save(draft("a"),"key-a");rejected {store.save(draft("a","deepseek"))};assertEquals("custom",store.get("a")!!.getString("kind"));assertEquals("key-a",store.credential("a"))}
    @Test fun endpointChangesRequireNewCredentialOrExplicitRemoval(){val memory=Memory();val store=ProviderStore(memory);store.save(draft("a"),"key-a");rejected {store.save(draft("a",url="https://other.test/v1"))};assertEquals("key-a",store.credential("a"));store.save(draft("a",url="https://other.test/v1"),"replacement");assertEquals("replacement",store.credential("a"));store.save(draft("a",url="https://third.test/v1"),removeKey=true);assertNull(store.credential("a"))}
    @Test fun selectionAndEnabledStateSurviveReopen(){val memory=Memory();val store=ProviderStore(memory);store.save(draft("a"),"key-a");store.save(draft("b"),"key-b");store.setDefault("b");store.setEnabled("a",false);val reopened=ProviderStore(memory);assertEquals("b",reopened.defaultId());assertFalse(reopened.get("a")!!.getBoolean("enabled"));assertEquals("key-a",reopened.credential("a"));assertEquals("key-b",reopened.credential("b"))}
    @Test fun disablingDefaultUsesNextEnabledRecordWithoutDeletingAny(){val store=ProviderStore(Memory());store.save(draft("a"));store.save(draft("b"));store.setEnabled("a",false);assertEquals("b",store.defaultId());rejected {store.setDefault("a")};store.setEnabled("b",false);assertNull(store.defaultId());assertEquals(2,store.list().size);store.setEnabled("a",true);assertEquals("a",store.defaultId())}
    @Test fun metadataNeverContainsCredentialsOrUnexpectedInputFields(){val memory=Memory();val store=ProviderStore(memory);store.save(draft().put("apiKey","sentinel-secret").put("credential","sentinel-secret"),"real-fixture-key");assertFalse(memory.value!!.contains("secret"));assertFalse(store.list().toString().contains("real-fixture-key"));assertFalse(store.list().toString().contains("apiKey"))}
    @Test fun returnedMetadataCannotMutateSavedRecords(){val store=ProviderStore(Memory());store.save(draft());store.list()[0].put("model","changed");store.get("account-a")!!.put("url","bad");assertEquals("fixture-model",store.get("account-a")!!.getString("model"))}
    @Test fun eachRevisionChangesIndependently(){val store=ProviderStore(Memory());store.save(draft("a"));store.save(draft("b"));store.save(draft("a").put("name","new"));assertEquals(2,store.get("a")!!.getInt("revision"));assertEquals(1,store.get("b")!!.getInt("revision"))}
    @Test fun modificationDropsLegacyProbeAuthorization(){val memory=Memory().apply {original=draft().put("revision",1).put("probeRevision",1).put("probe","passed").put("probeImages",true)};val store=ProviderStore(memory);assertTrue(store.get("legacy")!!.getBoolean("legacyProbePassed"));store.save(store.get("legacy")!!.put("name","edited"));assertFalse(store.get("legacy")!!.has("legacyProbePassed"));assertFalse(store.get("legacy")!!.has("legacyMigration"))}
    @Test fun maximumRecordCountAllowsEditingAndDeleting(){val store=ProviderStore(Memory());repeat(16){store.save(draft("account-$it"))};rejected {store.save(draft("extra"))};store.save(draft("account-0").put("name","updated"));store.delete("account-1");store.save(draft("replacement"));assertEquals(16,store.list().size)}
    @Test fun generatedIdsAreStableAndSafe(){val store=ProviderStore(Memory());val d=draft().apply {remove("id")};val result=store.save(d);val id=result.getString("id");assertTrue(id.matches(Regex("[a-zA-Z0-9-]+")));assertEquals(id,store.list()[0].getString("id"));assertEquals("provider.$id.model",ProviderStore.credentialRef(id))}
    @Test fun invalidIdsCannotAliasOldVaultReferences(){for(id in listOf("../model","x.model","x/y","","-bad","x".repeat(65))){if(id.isEmpty())continue;rejected {ProviderStore.credentialRef(id)}}}
    @Test fun malformedOrSecretBearingUrlsDoNotSaveOrChangeCredentials(){for(url in listOf("file:///test","https://user:pass@example.test","https://example.test?key=1","https://example.test/#frag")){val m=Memory();val s=ProviderStore(m);rejected{s.save(draft(url=url),"fixture-key")};assertTrue(s.list().isEmpty());assertTrue(m.keys.isEmpty())}}
    @Test fun officialDestinationsArePinned(){for(kind in listOf("opencode-go","deepseek",OpenCodeZenCatalog.KIND)){rejected {ProviderStore(Memory()).save(draft(kind=kind).put("url","https://other.test"),"fixture-key")}}}
    @Test fun invalidKeysAndBudgetsAreRejectedBeforeCredentialMutation(){val m=Memory();val s=ProviderStore(m);for(d in listOf(draft().put("maxOutput",32768),draft().put("contextBudget",100),draft().put("maxOutputText","-1"))){rejected{s.save(d,"fixture-key")}};rejected{s.save(draft(),"a\nb")};assertTrue(m.keys.isEmpty());assertTrue(s.list().isEmpty())}
    @Test fun corruptStoredDocumentDoesNotOverwriteExistingStore(){val m=Memory().apply {value="{}"};assertThrows(Exception::class.java){ProviderStore(m)};assertEquals("{}",m.value);assertEquals(0,m.writes)}
    @Test fun failedMetadataWriteRestoresOldEndpointAndKey() {
        for(afterWrite in listOf(false,true)) {
            val memory=Memory();val store=ProviderStore(memory)
            store.save(draft("a"),"key-a");store.save(draft("b"),"key-b");val oldDoc=memory.value
            memory.failNextWrite=true;memory.failAfterWrite=afterWrite
            assertThrows(IllegalStateException::class.java){store.save(draft("a",url="https://new.test/v1"),"new-key")}
            assertEquals(oldDoc,memory.value);assertEquals("https://example.test/v1",store.get("a")!!.getString("url"));assertEquals("key-a",store.credential("a"));assertEquals("key-b",store.credential("b"))
            assertEquals("key-a",ProviderStore(memory).credential("a"))
        }
    }
    @Test fun failedNewRecordSaveRemovesItsOrphanCredential() {
        for(afterWrite in listOf(false,true)) {
            val memory=Memory();val store=ProviderStore(memory);store.save(draft("b"),"key-b");val oldDoc=memory.value
            memory.failNextWrite=true;memory.failAfterWrite=afterWrite
            assertThrows(IllegalStateException::class.java){store.save(draft("a"),"new-key")}
            assertEquals(oldDoc,memory.value);assertNull(store.get("a"));assertFalse(memory.keys.containsKey(ProviderStore.credentialRef("a")));assertEquals("key-b",store.credential("b"));assertEquals("b",store.defaultId())
        }
    }
    @Test fun failedMetadataWriteAfterKeyRemovalRestoresThatKey() {
        val memory=Memory();val store=ProviderStore(memory);store.save(draft("a"),"key-a");store.save(draft("b"),"key-b");val oldDoc=memory.value
        memory.failNextWrite=true
        assertThrows(IllegalStateException::class.java){store.save(draft("a"),removeKey=true)}
        assertEquals(oldDoc,memory.value);assertEquals("key-a",store.credential("a"));assertEquals("key-b",store.credential("b"))
    }
    @Test fun credentialWriteFailureAfterMutationRollsBackWithoutChangingMetadata() {
        val memory=Memory();val store=ProviderStore(memory);store.save(draft("a"),"key-a");store.save(draft("b"),"key-b");val oldDoc=memory.value
        memory.failNextCredentialWrite=true
        assertThrows(IllegalStateException::class.java){store.save(draft("a",url="https://new.test/v1"),"new-key")}
        assertEquals(oldDoc,memory.value);assertEquals("key-a",store.credential("a"));assertEquals("key-b",store.credential("b"))
    }
    @Test fun credentialRemoveFailureAfterMutationRestoresOriginalKey() {
        val memory=Memory();val store=ProviderStore(memory);store.save(draft("a"),"key-a");val oldDoc=memory.value
        memory.failNextCredentialRemove=true
        assertThrows(IllegalStateException::class.java){store.save(draft("a"),removeKey=true)}
        assertEquals(oldDoc,memory.value);assertEquals("key-a",store.credential("a"))
    }
    @Test fun failedBlankKeyEditLeavesEveryCredentialUntouched() {
        val memory=Memory();val store=ProviderStore(memory);store.save(draft("a"),"key-a");store.save(draft("b"),"key-b");val oldDoc=memory.value;val oldKeys=memory.keys.toMap()
        memory.failNextWrite=true;memory.failAfterWrite=true
        assertThrows(IllegalStateException::class.java){store.save(draft("a").put("name","renamed"),"")}
        assertEquals(oldDoc,memory.value);assertEquals(oldKeys,memory.keys)
    }
    @Test fun migrationUsesImageProbeResultInsteadOfDeclaredImageSupport() {
        for(images in listOf(false,true)) {
            val memory=Memory().apply {original=draft().put("revision",3).put("probeRevision",3).put("probe","passed").put("supportsImages",!images).put("probeImages",images)}
            val legacy=ProviderStore(memory).get("legacy")!!
            assertTrue(legacy.getBoolean("legacyProbePassed"));assertEquals(images,legacy.getBoolean("legacyProbeImages"));assertEquals(!images,legacy.getBoolean("supportsImages"))
        }
    }
    @Test fun missingStaleOrFailedProbeCannotMigrateImageAuthorization() {
        for(record in listOf(
            draft().put("revision",3).put("probeRevision",2).put("probe","passed").put("probeImages",true),
            draft().put("revision",3).put("probeRevision",3).put("probe","failed").put("probeImages",true),
            draft().put("revision",3).put("supportsImages",true),
            draft().put("revision",3).put("probeRevision",3).put("probe","passed")
        )) {
            val memory=Memory().apply {original=record};val legacy=ProviderStore(memory).get("legacy")!!
            assertFalse(legacy.getBoolean("legacyProbeImages"))
        }
    }

}
