package dev.thor.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated random DBs only. SQL triggers fail if an unrelated session is rewritten. */
@RunWith(AndroidJUnit4::class)
class ScopedSessionStorageTest {
    @Test fun screenshotAppendPreservesHistoryAndRejectsDuplicateOrCrossSessionImages() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="image-append-${id()}.db"
        try {SqliteStore(context,name).use {store->
            val state=obj("selected" to "s","sessions" to JSONArray().put(obj("id" to "s")).put(obj("id" to "other")),
                "runs" to JSONArray().put(obj("id" to "r","sessionId" to "s")),
                "messages" to JSONArray().put(obj("id" to "m","sessionId" to "s","runId" to "r","text" to "original")),
                "events" to JSONArray().put(obj("id" to "e","sessionId" to "s","runId" to "r","sequence" to 1)),
                "attachments" to JSONArray().put(obj("id" to "old-image","sessionId" to "s","runId" to "r","data" to "old pixels")))
            store.save(state)
            for(table in listOf("sessions","runs","messages","events","metadata"))
                for(action in listOf("INSERT","UPDATE","DELETE"))store.writableDatabase.execSQL("CREATE TRIGGER block_${table}_$action BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'unrelated history touched'); END")
            for(action in listOf("UPDATE","DELETE"))store.writableDatabase.execSQL("CREATE TRIGGER block_images_$action BEFORE $action ON attachments BEGIN SELECT RAISE(ABORT,'old image touched'); END")
            val image=obj("id" to "new-image","sessionId" to "s","runId" to "r","data" to "new pixels")
            store.saveAttachment(image)
            val after=store.load()
            for(table in listOf("sessions","runs","messages","events"))assertEquals(state.getJSONArray(table).toString(),after.getJSONArray(table).toString())
            assertEquals("s",after.getString("selected"));assertEquals(2,after.getJSONArray("attachments").length())
            assertEquals("old pixels",after.getJSONArray("attachments").getJSONObject(0).getString("data"))
            fun rejected(row:org.json.JSONObject) {val before=store.load().toString();var failed=false;try {store.saveAttachment(row)}catch(_:Exception){failed=true};assertTrue(failed);assertEquals(before,store.load().toString())}
            rejected(image)
            rejected(obj("id" to "cross-session","sessionId" to "other","runId" to "r"))
            store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use {assertEquals(0,it.count)}
        }}finally {assertTrue(context.deleteDatabase(name))}
    }
    @Test fun capabilityAndStreamWritesTouchOnlyTheirOwnSessionAndPreserveHistory() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="scoped-session-${id()}.db"
        try {SqliteStore(context,name).use {store->
            val state=obj("selected" to "s","sessions" to JSONArray().put(obj("id" to "s","title" to "own")).put(obj("id" to "other","title" to "untouched")),
                "runs" to JSONArray().put(obj("id" to "r","sessionId" to "s")),"messages" to JSONArray().put(obj("id" to "m","sessionId" to "s","runId" to "r","text" to "history")),
                "events" to JSONArray().put(obj("id" to "e","sessionId" to "s","runId" to "r","sequence" to 1,"text" to "old evidence")),"attachments" to JSONArray())
            store.save(state)
            store.writableDatabase.execSQL("CREATE TRIGGER block_unrelated_session BEFORE UPDATE ON sessions WHEN OLD.id='other' BEGIN SELECT RAISE(ABORT,'unrelated session rewritten'); END")
            state.getJSONArray("sessions").getJSONObject(0).put("capabilityEpoch",2)
            state.getJSONArray("sessions").getJSONObject(1).put("title","must not persist this unrelated in-memory edit")
            store.saveSessionUpdate(state,"s")
            val event=obj("id" to "e2","sessionId" to "s","runId" to "r","sequence" to 2,"text" to "new evidence")
            state.getJSONArray("events").put(event);store.saveRunUpdate(state,state.getJSONArray("runs").getJSONObject(0),event)
            val after=store.load()
            assertEquals("untouched",after.getJSONArray("sessions").objects().single {it.getString("id")=="other"}.getString("title"))
            assertEquals(2,after.getJSONArray("sessions").objects().single {it.getString("id")=="s"}.getInt("capabilityEpoch"))
            assertEquals(2,after.getJSONArray("events").length());assertEquals("history",after.getJSONArray("messages").getJSONObject(0).getString("text"))
            store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use {assertEquals(0,it.count)}
        }}finally {assertTrue(context.deleteDatabase(name))}
    }
}
