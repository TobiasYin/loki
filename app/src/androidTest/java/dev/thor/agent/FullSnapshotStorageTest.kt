package dev.thor.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite, random disposable DBs only. Never opens product.db or credentials. */
@RunWith(AndroidJUnit4::class)
class FullSnapshotStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val tables=listOf("sessions","runs","messages","events","attachments")
    private fun fixture()=obj("selected" to "s",
        "sessions" to JSONArray().put(obj("id" to "s","title" to "original")).put(obj("id" to "other","title" to "untouched")),
        "runs" to JSONArray().put(obj("id" to "r","sessionId" to "s","status" to "generating")).put(obj("id" to "other-r","sessionId" to "other")),
        "messages" to JSONArray().put(obj("id" to "m","sessionId" to "s","runId" to "r","text" to "original body")).put(obj("id" to "other-m","sessionId" to "other","runId" to "other-r")),
        "events" to JSONArray().put(obj("id" to "e","sessionId" to "s","runId" to "r","sequence" to 1,"text" to "original evidence")).put(obj("id" to "other-e","sessionId" to "other","runId" to "other-r","sequence" to 1)),
        "attachments" to JSONArray().put(obj("id" to "a","sessionId" to "s","runId" to "r","data" to "original pixels")).put(obj("id" to "pending-a","sessionId" to "s","runId" to null)))
    private fun isolated(block:(String,SqliteStore)->Unit) {
        val name="full-snapshot-memory-${id()}.db"
        try {SqliteStore(context,name).use {block(name,it)}} finally {assertTrue(context.deleteDatabase(name))}
    }
    private fun rowIds(store:SqliteStore)=tables.associateWith {table->
        store.readableDatabase.rawQuery("SELECT id,rowid FROM $table",null).use {c->buildMap<String,Long> {while(c.moveToNext())put(c.getString(0),c.getLong(1))}}
    }
    private fun assertExactRowsWithoutReadingBodies(store:SqliteStore,state:JSONObject) {
        for(table in tables) {
            val rows=state.getJSONArray(table)
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use {assertTrue(it.moveToFirst());assertEquals(rows.length(),it.getInt(0))}
            for(row in rows.objects())store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE id=? AND payload=?",arrayOf(row.getString("id"),row.toString())).use {
                assertTrue(it.moveToFirst());assertEquals("Full unchanged or updated $table payload must remain exact",1,it.getInt(0))
            }
        }
        store.readableDatabase.rawQuery("SELECT value FROM metadata WHERE name='selected'",null).use {assertTrue(it.moveToFirst());assertEquals(state.getString("selected"),it.getString(0))}
        store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use {assertEquals(0,it.count)}
    }
    @Test fun fullSaveReopenDoesNotMaterializeOversizedOldBodiesOrRewriteUnchangedRows()=isolated {name,store->
        val state=fixture();val events=state.getJSONArray("events")
        // A single old row exceeds the ordinary CursorWindow capacity. The old
        // SELECT id,payload path fails deterministically, without a flaky heap-
        // exhaustion assertion. Body comparison below returns scalar counts only.
        events.put(obj("id" to "large","sessionId" to "s","runId" to "r","sequence" to 2,"text" to "z".repeat(3*1024*1024)))
        repeat(12){i->events.put(obj("id" to "history-$i","sessionId" to "s","runId" to "r","sequence" to i+3,"text" to "h".repeat(128*1024)))}
        store.save(state);val before=rowIds(store)
        for(table in tables)store.writableDatabase.execSQL("CREATE TRIGGER reject_unchanged_$table BEFORE UPDATE ON $table WHEN OLD.payload=NEW.payload BEGIN SELECT RAISE(ABORT,'unchanged history rewritten'); END")
        store.close() // No in-memory store cache may be required for correctness.
        SqliteStore(context,name).use {reopened->
            repeat(3){reopened.save(state)}
            state.getJSONArray("runs").getJSONObject(0).put("status","interrupted")
            state.getJSONArray("messages").getJSONObject(0).put("text","retained terminal body")
            events.put(obj("id" to "terminal","sessionId" to "s","runId" to "r","sequence" to 15,"text" to "terminal event"))
            reopened.save(state)
            assertExactRowsWithoutReadingBodies(reopened,state)
            val after=rowIds(reopened)
            for(table in tables)for((id,rowId) in before.getValue(table))assertEquals("Existing $table row identity must survive",rowId,after.getValue(table)[id])
        }
    }
    @Test fun lateForeignKeyFailureRollsBackDeletesUpdatesInsertsAndKeepsSelection()=isolated {_,store->
        val state=fixture();store.save(state);val before=store.load().toString();val ids=rowIds(store)
        state.getJSONArray("messages").remove(0)
        state.getJSONArray("sessions").getJSONObject(0).put("title","must roll back")
        state.getJSONArray("runs").put(obj("id" to "new-r","sessionId" to "s"))
        state.getJSONArray("attachments").put(obj("id" to "bad","sessionId" to "absent","runId" to "r"))
        state.put("selected","other")
        try {store.save(state);fail("Late foreign-key failure must abort the whole snapshot")}
        catch(_:android.database.sqlite.SQLiteConstraintException) {}
        assertEquals(before,store.load().toString());assertEquals(ids,rowIds(store))
    }
    @Test fun lastMetadataFailureRollsBackTheEntireSnapshotIncludingDeletions()=isolated {_,store->
        val state=fixture();store.save(state);val before=store.load().toString();val ids=rowIds(store)
        store.writableDatabase.execSQL("CREATE TRIGGER reject_selection BEFORE INSERT ON metadata WHEN NEW.value='reject' BEGIN SELECT RAISE(ABORT,'forced metadata failure'); END")
        state.getJSONArray("messages").remove(0)
        state.getJSONArray("runs").getJSONObject(0).put("status","interrupted")
        state.getJSONArray("events").put(obj("id" to "new-e","sessionId" to "s","runId" to "r","sequence" to 2))
        state.put("selected","reject")
        try {store.save(state);fail("Selection failure must also restore all earlier snapshot changes")}
        catch(_:android.database.sqlite.SQLiteConstraintException) {}
        assertEquals(before,store.load().toString());assertEquals(ids,rowIds(store))
    }
    @Test fun parentCascadeDoesNotSilentlyLoseWantedChildrenMovedToASurvivingSession()=isolated {_,store->
        val state=fixture();store.save(state)
        state.getJSONArray("sessions").remove(0);state.put("selected","other")
        for(table in tables.drop(1))for(row in state.getJSONArray(table).objects())if(row.getString("sessionId")=="s")row.put("sessionId","other")
        store.save(state)
        assertExactRowsWithoutReadingBodies(store,state)
    }
}
