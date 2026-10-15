package dev.thor.agent

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Independent temporary database; never opens or resets product.db. */
@RunWith(AndroidJUnit4::class)
class PhysicalStorageRegressionTest {
    @Test fun bulkComparisonPreservesUpdatesReopenAndTransactionRollback() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="storage-regression-${id()}.db"
        val state=obj("selected" to "s","sessions" to JSONArray().put(obj("id" to "s")),"runs" to JSONArray().put(obj("id" to "r","sessionId" to "s")),"messages" to JSONArray(),"events" to JSONArray(),"attachments" to JSONArray())
        val evidence=obj("historyRows" to 1000,"streamUpdates" to 20,"privateTemporaryDatabaseOnly" to true)
        try {
            SqliteStore(context,name).use {store ->
                repeat(1000){i->state.getJSONArray("events").put(obj("id" to "e$i","sessionId" to "s","runId" to "r","sequence" to i+1,"text" to "history-$i"))}
                store.save(state)
                state.getJSONArray("messages").put(obj("id" to "m","sessionId" to "s","runId" to "r","text" to "first"));store.save(state)
                val start=android.os.SystemClock.elapsedRealtime()
                repeat(20){i->state.getJSONArray("messages").getJSONObject(0).put("text","delta-$i");store.save(state)}
                evidence.put("streamPersistenceMs",android.os.SystemClock.elapsedRealtime()-start)
                state.getJSONArray("messages").getJSONObject(0).put("text","updated");store.save(state)
                val committed=SqliteStore(context,name).use {it.load()}
                assertEquals(1000,committed.getJSONArray("events").length());assertEquals("updated",committed.getJSONArray("messages").getJSONObject(0).getString("text"))
                state.getJSONArray("messages").getJSONObject(0).put("text","must rollback")
                state.getJSONArray("attachments").put(obj("id" to "bad","sessionId" to "missing-parent","runId" to "r"))
                try {store.save(state);fail("Foreign-key failure must roll back")}catch(_:android.database.sqlite.SQLiteConstraintException) {}
                assertEquals(committed.toString(),SqliteStore(context,name).use {it.load().toString()})
                state.getJSONArray("attachments").remove(0);state.getJSONArray("events").remove(0);store.save(state)
                val final=SqliteStore(context,name).use {it.load()};assertEquals(999,final.getJSONArray("events").length());assertEquals("must rollback",final.getJSONArray("messages").getJSONObject(0).getString("text"))
                evidence.put("passed",true).put("atomicRollbackVerified",true)
            }
        } finally {evidence.put("temporaryDatabaseDeleted",context.deleteDatabase(name));java.io.File(context.filesDir,"physical-storage-regression.json").writeText(evidence.toString(2))}
    }
}
