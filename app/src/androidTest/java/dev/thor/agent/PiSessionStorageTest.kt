package dev.thor.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Upstream records are opaque storage values. These tests never open product.db. */
@RunWith(AndroidJUnit4::class)
class PiSessionStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture()=obj("selected" to "a","sessions" to JSONArray().put(obj("id" to "a")).put(obj("id" to "b")),
        "runs" to JSONArray().put(obj("id" to "ra","sessionId" to "a","status" to "completed","turn" to obj("legacy" to "unchanged")))
            .put(obj("id" to "rb","sessionId" to "b","status" to "completed")),
        "messages" to JSONArray(),"events" to JSONArray(),"attachments" to JSONArray())
    private fun header(sid:String="a")=obj("type" to "session","version" to 3,"id" to sid,"timestamp" to "2026-10-17T01:00:00Z","cwd" to "/thor","unknownMetadata" to obj("preserved" to true))
    private fun entry(id:String,parent:String?=null,type:String="message",message:JSONObject=obj("role" to "user","content" to "hello"))=obj(
        "type" to type,"id" to id,"parentId" to (parent ?: JSONObject.NULL),"timestamp" to "2026-10-17T01:00:00Z","message" to message)
    private fun batch(entries:List<JSONObject>,leaf:String?=entries.lastOrNull()?.getString("id"),source:List<String> = emptyList(),sid:String="a")=obj(
        "header" to header(sid),"entries" to JSONArray(entries),"leafId" to (leaf ?: JSONObject.NULL),"source" to JSONArray(source))
    private fun isolated(block:(SqliteStore)->Unit) {
        val name="pi-session-storage-${id()}.db"
        try {SqliteStore(context,name).use {it.save(fixture());block(it)}}finally {assertTrue(context.deleteDatabase(name))}
    }
    private fun rejected(block:()->Unit){var failed=false;try{block()}catch(_:Exception){failed=true};assertTrue("Invalid journal mutation must fail",failed)}
    private fun legacyBytes(store:SqliteStore)=buildMap<String,String> {
        for(table in listOf("sessions","runs","messages","events","attachments"))store.readableDatabase.rawQuery("SELECT id,payload FROM $table ORDER BY rowid",null).use {c->while(c.moveToNext())put("$table:${c.getString(0)}",c.getString(1))}
    }
    @Test fun appendRestoresExactOpaqueMessagesUsageSignaturesAndMetadata()=isolated {store->
        val before=legacyBytes(store)
        val one=entry("one",message=obj("role" to "assistant","content" to JSONArray().put(obj("type" to "thinking","thinking" to "preserve","thinkingSignature" to "opaque-signature")),
            "usage" to obj("input" to 120,"output" to 8,"cacheRead" to 100),"providerMetadata" to obj("future" to true)))
        val two=entry("two","one",message=obj("role" to "toolResult","toolCallId" to "done","content" to JSONArray().put(obj("type" to "text","text" to "confirmed result"))))
        assertNull(store.loadPiSession("a"))
        store.savePiSession("a","ra",batch(listOf(one),source=listOf("screen_perception")))
        store.savePiSession("a","ra",batch(listOf(two),source=listOf("web_search")))
        val restored=store.loadPiSession("a")!!
        assertEquals(header().toString(),restored.getJSONObject("header").toString())
        assertEquals(JSONArray(listOf(one,two)).toString(),restored.getJSONArray("entries").toString());assertEquals("two",restored.getString("leafId"))
        assertEquals("[\"screen_perception\"]",restored.getJSONObject("provenance").getJSONArray("one").toString())
        assertEquals("[\"web_search\"]",restored.getJSONObject("provenance").getJSONArray("two").toString())
        assertEquals(before,legacyBytes(store));assertNull(store.loadPiSession("b"))
        assertFalse(store.loadShell().toString().contains("opaque-signature"));assertFalse(store.loadChatPage("a").toString().contains("opaque-signature"))
    }
    @Test fun branchesCompactionAndSelectedLeafAreStoredWithoutApplyingCustomAlgorithms()=isolated {store->
        val root=entry("root");val left=entry("left","root");val right=entry("right","root")
        val compression=entry("compression","right","compaction").put("summary","Original summary").put("firstKeptEntryId","right").put("tokensBefore",40000).put("details",obj("custom" to "preserved"))
        store.savePiSession("a","ra",batch(listOf(root,left,right,compression),source=listOf("screen_perception","web_search")))
        store.savePiSession("a","ra",batch(emptyList(),leaf="left"))
        val restored=store.loadPiSession("a")!!
        assertEquals("left",restored.getString("leafId"));assertEquals(4,restored.getJSONArray("entries").length())
        assertEquals(compression.toString(),restored.getJSONArray("entries").getJSONObject(3).toString())
        store.savePiSession("a","ra",batch(emptyList(),leaf=null));assertTrue(store.loadPiSession("a")!!.isNull("leafId"))
    }
    @Test fun duplicateDeliveryIsIdempotentButCannotRewriteEntriesHeaderOrNarrowSources()=isolated {store->
        val first=entry("one");val value=batch(listOf(first),source=listOf("screen_perception"));store.savePiSession("a","ra",value)
        store.savePiSession("a","ra",batch(listOf(first)))
        assertEquals(1,store.loadPiSession("a")!!.getJSONArray("entries").length())
        assertEquals("[\"screen_perception\"]",store.loadPiSession("a")!!.getJSONObject("provenance").getJSONArray("one").toString())
        val before=store.loadPiSession("a").toString()
        rejected {store.savePiSession("a","ra",batch(listOf(entry("one").put("message",obj("role" to "user","content" to "modified")))))}
        rejected {store.savePiSession("a","ra",batch(emptyList(),leaf="one").put("header",header().put("cwd","changed")))}
        assertEquals(before,store.loadPiSession("a").toString())
    }
    @Test fun invalidParentLeafScopeOrSourceRollsBackEntireBatch()=isolated {store->
        val one=entry("one");store.savePiSession("a","ra",batch(listOf(one)));val before=store.loadPiSession("a").toString()
        rejected {store.savePiSession("a","ra",batch(listOf(entry("two","one"),entry("three","missing"))))}
        rejected {store.savePiSession("a","ra",batch(listOf(entry("two","one")),leaf="missing"))}
        rejected {store.savePiSession("a","rb",batch(listOf(entry("two","one"))))}
        rejected {store.savePiSession("a","ra",batch(listOf(entry("two","one")),source=listOf("made_up_capability")))}
        rejected {store.savePiSession("a","ra",batch(listOf(entry("same","one"),entry("same","one"))))}
        assertEquals(before,store.loadPiSession("a").toString());assertNull(store.loadPiSession("b"))
        store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use {assertEquals(0,it.count)}
    }
    @Test fun failedLeafCommitRollsBackNewEntriesAndProvenanceTogether()=isolated {store->
        store.savePiSession("a","ra",batch(listOf(entry("one")),source=listOf("screen_perception")))
        val before=store.loadPiSession("a").toString()
        store.writableDatabase.execSQL("CREATE TRIGGER block_pi_leaf BEFORE UPDATE ON pi_sessions BEGIN SELECT RAISE(ABORT,'leaf write failure'); END")
        rejected {store.savePiSession("a","ra",batch(listOf(entry("one"),entry("two","one")),source=listOf("web_search")))}
        assertEquals(before,store.loadPiSession("a").toString())
    }
    @Test fun identicalEntryIdsRemainScopedToTheirOwnSession()=isolated {store->
        store.savePiSession("a","ra",batch(listOf(entry("same"))))
        store.savePiSession("b","rb",batch(listOf(entry("same",message=obj("role" to "user","content" to "other session"))),sid="b"))
        assertEquals("hello",store.loadPiSession("a")!!.getJSONArray("entries").getJSONObject(0).getJSONObject("message").getString("content"))
        assertEquals("other session",store.loadPiSession("b")!!.getJSONArray("entries").getJSONObject(0).getJSONObject("message").getString("content"))
    }
    @Test fun oversizedEntriesUseChunkedReadsAndNeverEnterStartupOrChatState()=isolated {store->
        val large=entry("large",message=obj("role" to "user","content" to "x".repeat(3*1024*1024)))
        store.savePiSession("b","rb",batch(listOf(large),sid="b"))
        assertEquals(3*1024*1024,store.loadPiSession("b")!!.getJSONArray("entries").getJSONObject(0).getJSONObject("message").getString("content").length)
        assertNull(store.loadPiSession("a"));assertTrue(store.loadShell().toString().length<1024);assertTrue(store.loadChatPage("b").toString().length<2048)
        store.savePiSession("b","rb",batch(listOf(large),sid="b"));assertEquals(1,store.loadPiSession("b")!!.getJSONArray("entries").length())
    }
    @Test fun sessionDeletionCascadesItsPiJournalButNeverOtherSessions()=isolated {store->
        store.savePiSession("a","ra",batch(listOf(entry("a-entry"))))
        store.savePiSession("b","rb",batch(listOf(entry("b-entry")),sid="b"));val before=store.loadPiSession("a").toString()
        store.savePartialSnapshot(store.loadSession("a"),setOf("b"))
        assertNull(store.loadPiSession("b"));assertEquals(before,store.loadPiSession("a").toString())
        for(table in listOf("pi_sessions","pi_session_entries"))store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use {assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))}
    }
    private fun inputRun(store:SqliteStore,runId:String,status:String="interrupted",sessionId:String="a",extra:JSONObject?=null):JSONObject {
        val content=JSONArray().put(obj("type" to "text","text" to "user input $runId")).put(obj("type" to "attachment","attachmentId" to "image-$runId"))
        val turn=obj("complete" to false,"messages" to JSONArray().put(obj("kind" to "user","content" to content)))
        val row=obj("id" to runId,"sessionId" to sessionId,"status" to status,"turn" to turn)
        extra?.let {values->values.keys().forEach {row.put(it,values.get(it))}}
        store.saveRunUpdate(store.loadShell(),row,null);return row
    }
    @Test fun pendingInputRecoveryReturnsOnlyTerminalUncommittedUserContent()=isolated {store->
        inputRun(store,"failed-input","failed");inputRun(store,"cancelled-input","cancelled");inputRun(store,"interrupted-input")
        inputRun(store,"completed-input","completed");inputRun(store,"running-input","generating")
        inputRun(store,"probe-input",extra=obj("probe" to true));inputRun(store,"committed-input",extra=obj("piInputCommitted" to true))
        inputRun(store,"other-session",sessionId="b")
        val mixed=inputRun(store,"mixed-history","failed")
        mixed.getJSONObject("turn").getJSONArray("messages").put(obj("kind" to "assistant","text" to "x".repeat(3*1024*1024),"calls" to JSONArray()))
        store.saveRunUpdate(store.loadShell(),mixed,null)
        val pending=store.loadPendingPiInputs("a").objects()
        assertEquals(listOf("failed-input","cancelled-input","interrupted-input"),pending.map {it.getString("runId")})
        for(row in pending) {
            assertEquals(setOf("runId","content","source"),row.keys().asSequence().toSet());assertEquals(0,row.getJSONArray("source").length())
            assertEquals(2,row.getJSONArray("content").length());assertEquals("user input ${row.getString("runId")}",row.getJSONArray("content").getJSONObject(0).getString("text"))
            assertEquals("image-${row.getString("runId")}",row.getJSONArray("content").getJSONObject(1).getString("attachmentId"))
        }
        assertFalse(pending.toString().contains("mixed-history"));assertEquals(1,store.loadPendingPiInputs("b").length())
    }
    @Test fun piUserEntryAndItsExplicitInputCommitMarkerAreAtomicAndIdempotent()=isolated {store->
        inputRun(store,"prior-input");assertEquals(1,store.loadPendingPiInputs("a").length())
        val user=entry("actual-user",message=obj("role" to "user","content" to "user input prior-input"))
        // A user entry from compatibility import alone must never mark the new Run.
        store.savePiSession("a","ra",batch(listOf(user)));assertFalse(store.getRun("prior-input")!!.optBoolean("piInputCommitted"))
        assertEquals(1,store.loadPendingPiInputs("a").length())
        val snapshot=store.loadPiSession("a")!!.getJSONArray("entries").toString()
        store.savePiSession("a","ra",batch(listOf(user)).put("inputRunId","prior-input"))
        assertTrue(store.getRun("prior-input")!!.getBoolean("piInputCommitted"));assertEquals(0,store.loadPendingPiInputs("a").length())
        assertEquals(snapshot,store.loadPiSession("a")!!.getJSONArray("entries").toString())
        store.savePiSession("a","ra",batch(listOf(user)).put("inputRunId","prior-input"));assertEquals(snapshot,store.loadPiSession("a")!!.getJSONArray("entries").toString())
        assertFalse(store.getRun("ra")!!.optBoolean("piInputCommitted"))
    }
    @Test fun failedInputMarkerWriteRollsBackThePiEntryAndLeafTogether()=isolated {store->
        inputRun(store,"pending-input")
        store.savePiSession("a","ra",batch(listOf(entry("root"))))
        val before=store.loadPiSession("a").toString()
        store.writableDatabase.execSQL("CREATE TRIGGER block_pi_input_commit BEFORE UPDATE ON runs WHEN OLD.id='pending-input' BEGIN SELECT RAISE(ABORT,'input commit failure'); END")
        rejected {store.savePiSession("a","ra",batch(listOf(entry("new-user","root"))).put("inputRunId","pending-input"))}
        assertEquals(before,store.loadPiSession("a").toString());assertFalse(store.getRun("pending-input")!!.optBoolean("piInputCommitted"));assertEquals(1,store.loadPendingPiInputs("a").length())
    }
    @Test fun inputCommitRejectsNonUserEntriesAndCrossSessionOwnership()=isolated {store->
        inputRun(store,"pending-input");inputRun(store,"other-input",sessionId="b")
        val assistant=entry("not-user",message=obj("role" to "assistant","content" to JSONArray()))
        rejected {store.savePiSession("a","ra",batch(listOf(assistant)).put("inputRunId","pending-input"))}
        rejected {store.savePiSession("a","ra",batch(listOf(entry("user"))).put("inputRunId","other-input"))}
        rejected {store.savePiSession("a","ra",batch(listOf(entry("user"))).put("inputRunId","missing-input"))}
        assertNull(store.loadPiSession("a"));assertFalse(store.getRun("pending-input")!!.optBoolean("piInputCommitted"));assertFalse(store.getRun("other-input")!!.optBoolean("piInputCommitted"))
    }
    @Test fun batchCommitsEveryOriginalInputAndItsOwnEntrySourcesTogether()=isolated {store->
        inputRun(store,"input-one");inputRun(store,"input-two")
        val one=entry("user-one");val two=entry("user-two","user-one")
        val value=batch(listOf(one,two),source=listOf("screen_perception","web_search"))
            .put("inputRunIds",obj("user-one" to "input-one","user-two" to "input-two"))
            .put("entrySources",obj("user-one" to JSONArray(),"user-two" to JSONArray().put("web_search")))
        store.savePiSession("a","ra",value)
        assertTrue(store.getRun("input-one")!!.getBoolean("piInputCommitted"));assertTrue(store.getRun("input-two")!!.getBoolean("piInputCommitted"))
        assertEquals(0,store.loadPendingPiInputs("a").length())
        val restored=store.loadPiSession("a")!!;assertEquals("user-two",restored.getString("leafId"));assertEquals(2,restored.getJSONArray("entries").length())
        assertEquals("[]",restored.getJSONObject("provenance").getJSONArray("user-one").toString())
        assertEquals("[\"web_search\"]",restored.getJSONObject("provenance").getJSONArray("user-two").toString())
        value.getJSONObject("entrySources").put("user-two",JSONArray());store.savePiSession("a","ra",value)
        assertEquals("[\"web_search\"]",store.loadPiSession("a")!!.getJSONObject("provenance").getJSONArray("user-two").toString())
    }
    @Test fun secondInputCommitFailureRollsBackTheEntireInitialMigrationBatch()=isolated {store->
        inputRun(store,"input-one");inputRun(store,"input-two")
        store.writableDatabase.execSQL("CREATE TRIGGER block_second_pi_ack BEFORE UPDATE ON runs WHEN OLD.id='input-two' BEGIN SELECT RAISE(ABORT,'second input commit failure'); END")
        val value=batch(listOf(entry("user-one"),entry("user-two","user-one")))
            .put("inputRunIds",obj("user-one" to "input-one","user-two" to "input-two"))
            .put("entrySources",obj("user-one" to JSONArray(),"user-two" to JSONArray().put("web_search")))
        rejected {store.savePiSession("a","ra",value)}
        assertNull(store.loadPiSession("a"));assertFalse(store.getRun("input-one")!!.optBoolean("piInputCommitted"));assertFalse(store.getRun("input-two")!!.optBoolean("piInputCommitted"))
        assertEquals(2,store.loadPendingPiInputs("a").length())
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM pi_session_entries",null).use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
    }
    @Test fun batchMapsRejectMissingNonUserCrossSessionAndUnknownSourcesAtomically()=isolated {store->
        inputRun(store,"input-one");inputRun(store,"other-input",sessionId="b")
        val one=entry("user-one");val assistant=entry("assistant","user-one",message=obj("role" to "assistant","content" to JSONArray()))
        for(map in listOf(obj("missing-entry" to "input-one"),obj("assistant" to "input-one"),obj("user-one" to "other-input"))) {
            rejected {store.savePiSession("a","ra",batch(listOf(one,assistant)).put("inputRunIds",map))}
            assertNull(store.loadPiSession("a"));assertFalse(store.getRun("input-one")!!.optBoolean("piInputCommitted"))
        }
        for(map in listOf(obj("missing-entry" to JSONArray()),obj("user-one" to JSONArray().put("unknown-source")))) {
            rejected {store.savePiSession("a","ra",batch(listOf(one)).put("entrySources",map))}
            assertNull(store.loadPiSession("a"))
        }
        assertFalse(store.getRun("other-input")!!.optBoolean("piInputCommitted"))
    }
    @Test fun v1UpgradeAddsJournalTablesWithoutChangingAnyLegacyPayload() {
        val name="pi-upgrade-${id()}.db"
        try {
            val before=SqliteStore(context,name).use {store->
                store.save(fixture());val saved=legacyBytes(store)
                store.writableDatabase.execSQL("DROP TABLE pi_session_entries");store.writableDatabase.execSQL("DROP TABLE pi_sessions");store.writableDatabase.version=1;saved
            }
            SqliteStore(context,name).use {store->
                assertEquals(before,legacyBytes(store));assertEquals(2,store.readableDatabase.version);assertNull(store.loadPiSession("a"))
                store.savePiSession("a","ra",batch(listOf(entry("imported"))));assertEquals(before,legacyBytes(store))
            }
        }finally {assertTrue(context.deleteDatabase(name))}
    }
}
