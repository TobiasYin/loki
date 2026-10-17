package dev.thor.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable SQLite fixtures only. Never opens the product database or keys. */
@RunWith(AndroidJUnit4::class)
class StreamEventStorageTest {
    @Test fun foldsDeltasLosslesslyAndKeepsLatestReplyIndependentOfEvenAnEmptyEventPage() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="stream-rows-${id()}.db"
        try {SqliteStore(context,name).use {store->
            val run=obj("id" to "r","sessionId" to "s","status" to "generating")
            val message=obj("id" to "m","sessionId" to "s","runId" to "r","role" to "assistant","text" to "")
            val state=obj("selected" to "s","sessions" to JSONArray().put(obj("id" to "s")),"runs" to JSONArray().put(run),
                "messages" to JSONArray().put(message),"events" to JSONArray(),"attachments" to JSONArray())
            store.save(state);var sequence=0
            fun append(type:String,block:String?=null,text:String="") {
                val data=obj("text" to text);if(block!=null)data.put("blockId",block)
                val event=obj("id" to "e${++sequence}","sessionId" to "s","runId" to "r","sequence" to sequence,"type" to type,"data" to data)
                state.getJSONArray("events").put(event);if(type=="text_delta")message.put("text",message.getString("text")+text)
                store.saveRunUpdate(state,run,event)
            }
            repeat(1000){append("text_delta","1:0","哈哈")}
            append("tool_started");append("tool_result")
            append("text_delta","2:0","LATEST");append("text_delta","2:0","_REPLY")
            repeat(700){append("state")}
            val stored=store.load().getJSONArray("events").objects()
            assertEquals(704,stored.size);assertEquals(1000,stored.first().getInt("fragmentCount"));assertEquals(1000,stored.first().getInt("lastSequence"))
            assertEquals("哈哈".repeat(1000),stored.first().getJSONObject("data").getString("text"))
            for(limit in listOf(0,2,600)) {
                val page=store.loadChatPage("s",1,limit);assertEquals(limit,page.getJSONArray("events").length())
                val segments=page.getJSONArray("runs").getJSONObject(0).getJSONArray("textSegments").objects()
                assertEquals(2,segments.size);assertEquals("LATEST_REPLY",segments.last().getJSONObject("data").getString("text"))
                assertEquals(1003,segments.last().getInt("sequence"));assertEquals(1004,segments.last().getInt("lastSequence"))
            }
            // Snapshot writes must not insert all raw deltas back into the DB.
            store.savePartialSnapshot(state);assertEquals(704,store.load().getJSONArray("events").length())
            store.save(state);assertEquals(704,store.load().getJSONArray("events").length())
            val before=store.load().toString();message.put("text","must rollback")
            try {store.saveRunUpdate(state,run,obj("id" to "duplicate","sessionId" to "s","runId" to "r","sequence" to 1002,"type" to "text_delta","data" to obj("blockId" to "2:0","text" to "bad")));fail("Duplicate sequence accepted")}
            catch(_:android.database.sqlite.SQLiteConstraintException) {}
            assertEquals(before,store.load().toString())
        }
            SqliteStore(context,name).use {store->assertEquals("LATEST_REPLY",store.loadChatPage("s",1,0).getJSONArray("runs").getJSONObject(0).getJSONArray("textSegments").objects().last().getJSONObject("data").getString("text"))}
        }finally {assertTrue(context.deleteDatabase(name))}
    }

    @Test fun oldUnaggregatedRowsProjectCorrectlyWithoutRewritingOrLoadingForeignHistory() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="stream-legacy-${id()}.db"
        try {SqliteStore(context,name).use {store->
            val state=obj("selected" to "s","sessions" to JSONArray().put(obj("id" to "s")).put(obj("id" to "other")),
                "runs" to JSONArray().put(obj("id" to "r","sessionId" to "s","status" to "completed")).put(obj("id" to "foreign","sessionId" to "other","status" to "completed")),
                "messages" to JSONArray().put(obj("id" to "m","sessionId" to "s","runId" to "r","role" to "assistant","text" to "oldLATEST_REPLY")),"events" to JSONArray(),"attachments" to JSONArray())
            store.save(state)
            fun raw(seq:Int,block:String,text:String,run:String="r",sid:String="s") {
                val event=obj("id" to "$run-$seq","sessionId" to sid,"runId" to run,"sequence" to seq,"type" to "text_delta","data" to obj("blockId" to block,"text" to text))
                store.writableDatabase.execSQL("INSERT INTO events(id,sessionId,runId,sequence,payload) VALUES(?,?,?,?,?)",arrayOf(event.getString("id"),sid,run,seq,event.toString()))
            }
            raw(1,"1:0","old");raw(701,"2:0","LATEST");raw(702,"2:0","_REPLY");raw(1,"2:0","FOREIGN_PRIVATE_TEXT","foreign","other")
            val before=store.load().toString();val page=store.loadChatPage("s",1,0)
            assertFalse(page.toString().contains("FOREIGN_PRIVATE_TEXT"))
            val segments=page.getJSONArray("runs").getJSONObject(0).getJSONArray("textSegments").objects()
            assertEquals(listOf("old","LATEST_REPLY"),segments.map {it.getJSONObject("data").getString("text")})
            repeat(20){assertEquals(page.toString(),store.loadChatPage("s",1,0).toString())}
            assertEquals(before,store.load().toString())
        }}finally {assertTrue(context.deleteDatabase(name))}
    }

    @Test fun legacyFragmentLimitMarksTheRetainedTailInsteadOfPretendingTheReplyIsComplete() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val name="stream-limit-${id()}.db"
        try {SqliteStore(context,name).use {store->
            store.save(obj("selected" to "s","sessions" to JSONArray().put(obj("id" to "s")),
                "runs" to JSONArray().put(obj("id" to "r","sessionId" to "s","status" to "completed")),
                "messages" to JSONArray().put(obj("id" to "m","sessionId" to "s","runId" to "r","role" to "assistant","text" to "x".repeat(2050))),"events" to JSONArray(),"attachments" to JSONArray()))
            val db=store.writableDatabase;db.beginTransaction()
            try {
                repeat(2050){index->val sequence=index+1;val event=obj("id" to "e$sequence","sessionId" to "s","runId" to "r","sequence" to sequence,"type" to "text_delta","data" to obj("blockId" to "1:0","text" to "x"))
                    db.execSQL("INSERT INTO events(id,sessionId,runId,sequence,payload) VALUES(?,?,?,?,?)",arrayOf("e$sequence","s","r",sequence,event.toString()))}
                db.setTransactionSuccessful()
            }finally {db.endTransaction()}
            val segments=store.loadChatPage("s",1,0).getJSONArray("runs").getJSONObject(0).getJSONArray("textSegments")
            assertEquals(1,segments.length());val row=segments.getJSONObject(0)
            assertEquals(3,row.getInt("sequence"));assertEquals(2050,row.getInt("lastSequence"))
            assertEquals(2048,row.getJSONObject("data").getString("text").length);assertTrue(row.getJSONObject("data").getBoolean("textTruncated"))
            db.rawQuery("SELECT COUNT(*) FROM events",null).use {c->assertTrue(c.moveToFirst());assertEquals(2050,c.getInt(0))}
        }}finally {assertTrue(context.deleteDatabase(name))}
    }
}
