package dev.thor.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated random temporary databases only. Never opens product.db or credentials. */
@RunWith(AndroidJUnit4::class)
class SessionUpdateStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture()=obj("selected" to "s", "sessions" to JSONArray().put(obj("id" to "s","title" to "original")).put(obj("id" to "other","title" to "other")),
        "runs" to JSONArray().put(obj("id" to "r","sessionId" to "s")).put(obj("id" to "other-r","sessionId" to "other")),
        "messages" to JSONArray().put(obj("id" to "m","sessionId" to "s","runId" to "r","text" to "history")).put(obj("id" to "other-m","sessionId" to "other","runId" to "other-r","text" to "other history")),
        "events" to JSONArray().put(obj("id" to "e","sessionId" to "s","runId" to "r","sequence" to 1,"text" to "durable evidence")).put(obj("id" to "other-e","sessionId" to "other","runId" to "other-r","sequence" to 1)),
        "attachments" to JSONArray().put(obj("id" to "a","sessionId" to "s","runId" to "r","data" to "unchanged image" )).put(obj("id" to "pending-a","sessionId" to "s","runId" to null,"data" to "pending picker image")))
    private fun isolated(block:(SqliteStore)->Unit) {
        val name="session-update-review-${id()}.db"
        try {SqliteStore(context,name).use(block)} finally {assertTrue(context.deleteDatabase(name))}
    }
    private fun assertNoOrphans(store:SqliteStore) {store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use {assertEquals(0,it.count)}}
    @Test fun sessionRenameSelectionAndInsertionPreserveEveryExistingChildAndRowId()=isolated {store->
        val state=fixture();store.save(state)
        val children=listOf("runs","messages","events","attachments")
        val rowIds=children.associateWith {table->store.readableDatabase.rawQuery("SELECT rowid,id FROM $table ORDER BY rowid",null).use {c->buildList {while(c.moveToNext())add(c.getLong(0) to c.getString(1))}}}
        // No event/attachment/message fields supplied: session-only saving must
        // preserve these without requiring or replacing the history arrays.
        val update=obj("selected" to "new","sessions" to JSONArray().put(obj("id" to "s","title" to "renamed")).put(obj("id" to "new","title" to "new title")))
        repeat(25){store.saveSessionUpdate(update)}
        val after=store.load();assertEquals("new",after.getString("selected"));assertEquals(3,after.getJSONArray("sessions").length())
        assertEquals("renamed",after.getJSONArray("sessions").objects().single {it.getString("id")=="s"}.getString("title"))
        for(table in children) {
            assertEquals(state.getJSONArray(table).toString(),after.getJSONArray(table).toString())
            val actual=store.readableDatabase.rawQuery("SELECT rowid,id FROM $table ORDER BY rowid",null).use {c->buildList {while(c.moveToNext())add(c.getLong(0) to c.getString(1))}}
            assertEquals(rowIds[table],actual)
        }
        assertNoOrphans(store)
    }
    @Test fun malformedLaterSessionRollsBackEarlierUpdatesInsertionsAndSelection()=isolated {store->
        store.save(fixture());val before=store.load().toString()
        val update=obj("selected" to "new","sessions" to JSONArray().put(obj("id" to "s","title" to "must rollback")).put(obj("id" to "new","title" to "must rollback insert")).put(obj("title" to "missing required id")))
        try {store.saveSessionUpdate(update);fail("Invalid later session must abort the transaction")}
        catch(_:org.json.JSONException) {}
        assertEquals(before,store.load().toString());assertNoOrphans(store)
    }
    @Test fun metadataFailureRollsBackAllParentUpdatesWithoutCascadingChildren()=isolated {store->
        store.save(fixture());val before=store.load().toString()
        store.writableDatabase.execSQL("CREATE TRIGGER reject_test_selection BEFORE INSERT ON metadata WHEN NEW.name='selected' AND NEW.value='reject-test' BEGIN SELECT RAISE(ABORT, 'forced metadata failure'); END")
        val update=obj("selected" to "reject-test","sessions" to JSONArray().put(obj("id" to "s","title" to "must rollback")).put(obj("id" to "new","title" to "must rollback insert")))
        try {store.saveSessionUpdate(update);fail("Metadata write failure must abort the entire transaction")}
        catch(_:android.database.sqlite.SQLiteConstraintException) {}
        assertEquals(before,store.load().toString());assertNoOrphans(store)
    }
}
