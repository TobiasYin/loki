package dev.thor.agent

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class NativeStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun testSQLiteRecoveryAndCascadingDeletion() {
        context.deleteDatabase("storage-test.db")
        var store=SqliteStore(context,"storage-test.db");val c=Coordinator(store);val a=c.state.getString("selected");val rid=c.start(a,"persist","success")
        c.step(rid,Step("start",tool="app_info"));store.close()
        store=SqliteStore(context,"storage-test.db");val resumed=Coordinator(store)
        assertEquals("interrupted",resumed.runs().single().getString("status"));assertTrue(resumed.events().any {it.optString("outcome")=="unknown"})
        resumed.newSession();resumed.delete(a);assertEquals(0,store.load().getJSONArray("events").length());assertEquals(0,store.load().getJSONArray("messages").length())
        store.close();context.deleteDatabase("storage-test.db")
    }
    @Test fun testCredentialCiphertextAndNativeOnlyRead() {
        val vault=CredentialVault(context);val ref="test-secret";val secret="test-only-key-123"
        vault.put(ref,secret);assertEquals(secret,vault.readNative(ref));assertEquals("已加密保存",vault.status(ref))
        val raw=context.getSharedPreferences("native-credentials",0).getString(ref,null)!!
        assertFalse(raw.contains(secret));assertTrue(JSONObject(raw).has("cipher"));vault.remove(ref);assertNull(vault.readNative(ref))
    }
    @Test fun testAttachmentPublicationHashAndOrphanCleanup() {
        val files=AttachmentFiles(context,"attachments-test");val ref=files.createText("test-session",null,"模拟附件")
        assertEquals(64,ref.getString("hash").length);assertEquals("text/plain",ref.getString("mime"));files.cleanup(emptyList())
        assertFalse(java.io.File(context.filesDir,"attachments-test/${ref.getString("path")}").exists())
    }
    @Test fun testForeignKeyRejectsOrphanAndTransactionRollsBack() {
        context.deleteDatabase("fk-test.db");val store=SqliteStore(context,"fk-test.db");val c=Coordinator(store);val before=store.load().toString()
        val invalid=c.snapshot();invalid.getJSONArray("messages").put(obj("id" to "orphan", "sessionId" to "missing", "runId" to null))
        try {store.save(invalid);fail("orphan was accepted")}catch(_:android.database.sqlite.SQLiteConstraintException){}
        assertEquals(before,store.load().toString());store.close();context.deleteDatabase("fk-test.db")
    }
}
