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
    @Test fun incrementalRunCommitsPreserveHistoryAndRollbackDuplicateEvents() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="storage-streaming-${id()}.db"
        val current=obj("id" to "r","sessionId" to "s","status" to "generating")
        val message=obj("id" to "m","sessionId" to "s","runId" to "r","text" to "initial")
        val state=obj("selected" to "s","sessions" to JSONArray().put(obj("id" to "s")).put(obj("id" to "other")),
            "runs" to JSONArray().put(obj("id" to "history","sessionId" to "s")).put(current).put(obj("id" to "other-run","sessionId" to "other")),
            "messages" to JSONArray().put(message).put(obj("id" to "other-message","sessionId" to "other","runId" to "other-run","text" to "untouched")),
            "events" to JSONArray(),"attachments" to JSONArray())
        val evidence=obj("historyRows" to 10000,"streamUpdates" to 200,"privateTemporaryDatabaseOnly" to true)
        try {
            SqliteStore(context,name).use {store ->
                repeat(10000){i->state.getJSONArray("events").put(obj("id" to "old-$i","sessionId" to "s","runId" to "history","sequence" to i+1,"text" to "history-$i"))}
                store.save(state)
                var start=android.os.SystemClock.elapsedRealtime()
                repeat(20){i->message.put("text","baseline-$i");store.save(state)}
                evidence.put("fullSnapshotUpdates",20).put("fullSnapshotMs",android.os.SystemClock.elapsedRealtime()-start)
                start=android.os.SystemClock.elapsedRealtime()
                repeat(200){i->
                    message.put("text","delta-$i")
                    val event=obj("id" to "new-$i","sessionId" to "s","runId" to "r","sequence" to i+1,"text" to "delta-$i")
                    state.getJSONArray("events").put(event);store.saveRunUpdate(state,current,event)
                }
                evidence.put("incrementalMs",android.os.SystemClock.elapsedRealtime()-start)
                val committed=SqliteStore(context,name).use {it.load()}
                assertEquals(10200,committed.getJSONArray("events").length())
                assertEquals("delta-199",committed.getJSONArray("messages").getJSONObject(0).getString("text"))
                assertEquals("untouched",committed.getJSONArray("messages").getJSONObject(1).getString("text"))
                repeat(10000){i->assertEquals("history-$i",committed.getJSONArray("events").getJSONObject(i).getString("text"))}
                state.put("selected","other");message.put("text","must rollback");current.put("status","must rollback")
                try {store.saveRunUpdate(state,current,state.getJSONArray("events").getJSONObject(10000));fail("Duplicate event must reject and roll back the whole commit")}
                catch(_:android.database.sqlite.SQLiteConstraintException) {}
                assertEquals(committed.toString(),SqliteStore(context,name).use {it.load().toString()})
                // Full UI saves after streaming must retain all committed events and other sessions.
                store.save(committed)
                assertEquals(committed.toString(),SqliteStore(context,name).use {it.load().toString()})
                evidence.put("passed",true).put("historyAndOtherSessionPreserved",true).put("duplicateEventAtomicRollbackVerified",true).put("fullSaveAfterStreamingVerified",true)
            }
        } finally {evidence.put("temporaryDatabaseDeleted",context.deleteDatabase(name));java.io.File(context.filesDir,"physical-storage-streaming-regression.json").writeText(evidence.toString(2))}
    }
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
