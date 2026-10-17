package dev.thor.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite and random disposable DBs only. Never opens product.db or settings. */
@RunWith(AndroidJUnit4::class)
class OnDemandSessionStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val tables=listOf("sessions","runs","messages","events","attachments")
    private fun fixture()=obj("selected" to "a",
        "sessions" to JSONArray().put(obj("id" to "a","title" to "selected")).put(obj("id" to "b","title" to "on disk")),
        "runs" to JSONArray().put(obj("id" to "ra","sessionId" to "a","status" to "completed","turn" to obj("messages" to JSONArray().put("context a"))))
            .put(obj("id" to "rb","sessionId" to "b","status" to "generating","turn" to obj("messages" to JSONArray().put("context b")))),
        "messages" to JSONArray().put(obj("id" to "ma","sessionId" to "a","runId" to "ra","text" to "message a"))
            .put(obj("id" to "mb","sessionId" to "b","runId" to "rb","text" to "message b")),
        "events" to JSONArray().put(obj("id" to "ea","sessionId" to "a","runId" to "ra","sequence" to 1,"type" to "text_delta","data" to obj("text" to "event a")))
            .put(obj("id" to "eb","sessionId" to "b","runId" to "rb","sequence" to 1,"type" to "thinking_delta","data" to obj("text" to "event b"))),
        "attachments" to JSONArray().put(obj("id" to "aa","sessionId" to "a","runId" to "ra","data" to "pixels a"))
            .put(obj("id" to "ab","sessionId" to "b","runId" to "rb","data" to "pixels b")))
    private fun isolated(block:(SqliteStore)->Unit) {
        val name="on-demand-session-${id()}.db"
        try {SqliteStore(context,name).use(block)}finally {assertTrue(context.deleteDatabase(name))}
    }
    private fun bytes(store:SqliteStore):Map<String,String> = buildMap {
        for(table in tables)store.readableDatabase.rawQuery("SELECT id,payload FROM $table ORDER BY rowid",null).use {c->while(c.moveToNext())put("$table:${c.getString(0)}",c.getString(1))}
        store.readableDatabase.rawQuery("SELECT name,value FROM metadata",null).use {c->while(c.moveToNext())put("metadata:${c.getString(0)}",c.getString(1))}
    }
    private fun rejected(block:()->Unit){var failed=false;try{block()}catch(_:Exception){failed=true};assertTrue("Invalid partial write must fail",failed)}

    @Test fun startupShellContainsOnlySessionMetadataAndSelectedIdentity()=isolated {store->
        val original=fixture();store.save(original);val before=bytes(store)
        val shell=store.loadShell();assertEquals("a",shell.getString("selected"));assertEquals(original.getJSONArray("sessions").toString(),shell.getJSONArray("sessions").toString())
        for(table in tables.drop(1))assertEquals("$table must stay on disk",0,shell.getJSONArray(table).length())
        assertEquals(before,bytes(store));assertFalse(shell.toString().contains("message a"));assertFalse(shell.toString().contains("pixels b"))
    }
    @Test fun oneSessionLoadPreservesCompleteContextButNeverIncludesAnotherSession()=isolated {store->
        val original=fixture();store.save(original)
        for(sid in listOf("a","b")) {
            val loaded=store.loadSession(sid);assertEquals(sid,loaded.getString("selected"));assertEquals(sid,loaded.getJSONArray("sessions").getJSONObject(0).getString("id"))
            for(table in tables.drop(1))assertEquals(JSONArray(original.getJSONArray(table).objects().filter {it.getString("sessionId")==sid}).toString(),loaded.getJSONArray(table).toString())
        }
        rejected {store.loadSession("missing")}
    }
    @Test fun shellAndSmallSessionLoadNeverMaterializeOversizedUnrelatedPayloads()=isolated {store->
        val original=fixture();val runs=original.getJSONArray("runs")
        runs.getJSONObject(1).put("status","completed").put("turn",obj("messages" to JSONArray().put("z".repeat(3*1024*1024))))
        original.getJSONArray("events").getJSONObject(1).put("data",obj("text" to "e".repeat(3*1024*1024)))
        original.getJSONArray("attachments").getJSONObject(1).put("data","a".repeat(3*1024*1024))
        store.save(original)
        val shell=store.loadShell();assertEquals(2,shell.getJSONArray("sessions").length());assertEquals(0,shell.getJSONArray("events").length())
        assertEquals("message a",store.loadSession("a").getJSONArray("messages").getJSONObject(0).getString("text"))
        assertTrue("Terminal oversized Pi turn must be filtered before Java CursorWindow",store.loadRecoveryRuns().isEmpty())
        store.savePartialSnapshot(shell)
        for(table in listOf("runs","events","attachments"))store.readableDatabase.rawQuery("SELECT length(payload) FROM $table WHERE sessionId='b'",null).use {c->assertTrue(c.moveToFirst());assertTrue(c.getLong(0)>3L*1024*1024)}
    }
    @Test fun recoveryReadsOnlyNonterminalRunsAcrossSessions()=isolated {store->
        val original=fixture();val rows=original.getJSONArray("runs")
        for(status in listOf("failed","cancelled","interrupted"))rows.put(obj("id" to status,"sessionId" to "a","status" to status))
        rows.put(obj("id" to "unknown-state","sessionId" to "a"));store.save(original)
        assertEquals(setOf("rb","unknown-state"),store.loadRecoveryRuns().map {it.getString("id")}.toSet())
        val shell=store.loadShell();val recovered=store.loadRecoveryRuns();recovered.forEach {it.put("status","interrupted").put("stopReason","do not replay")}
        shell.put("runs",JSONArray(recovered));store.saveStartupRecovery(shell,recovered.map {it.getString("id")}.toSet())
        assertTrue(store.loadRecoveryRuns().isEmpty());assertEquals(original.getJSONArray("messages").toString(),store.load().getJSONArray("messages").toString())
    }
    @Test fun partialSaveNeverDeletesRowsMissingFromMemoryOrRewritesUnchangedHistory()=isolated {store->
        store.save(fixture());val before=bytes(store)
        for(table in tables)store.writableDatabase.execSQL("CREATE TRIGGER no_unchanged_$table BEFORE UPDATE ON $table WHEN OLD.payload=NEW.payload BEGIN SELECT RAISE(ABORT,'unchanged rewrite'); END")
        val partial=store.loadShell();partial.getJSONArray("sessions").getJSONObject(0).put("title","changed title")
        store.savePartialSnapshot(partial)
        val loaded=store.loadSession("a");loaded.getJSONArray("messages").getJSONObject(0).put("text","new message a");store.savePartialSnapshot(loaded)
        val after=bytes(store)
        for((key,value) in before)if(key !in setOf("sessions:a","messages:ma"))assertEquals(key,value,after[key])
        assertEquals("changed title",JSONObject(after.getValue("sessions:a")).getString("title"));assertEquals("new message a",JSONObject(after.getValue("messages:ma")).getString("text"))
        store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use {assertEquals(0,it.count)}
    }
    @Test fun partialSaveCreatesNewRowsWithoutEvictingUnloadedSessions()=isolated {store->
        store.save(fixture());val before=bytes(store)
        val partial=obj("selected" to "new","sessions" to JSONArray().put(obj("id" to "new","title" to "new task")),
            "runs" to JSONArray().put(obj("id" to "new-run","sessionId" to "new","status" to "generating")),
            "messages" to JSONArray().put(obj("id" to "new-message","sessionId" to "new","runId" to "new-run","text" to "new prompt")),
            "events" to JSONArray().put(obj("id" to "new-event","sessionId" to "new","runId" to "new-run","sequence" to 1)),"attachments" to JSONArray())
        store.savePartialSnapshot(partial);val after=bytes(store)
        for((key,value) in before)if(key!="metadata:selected")assertEquals(key,value,after[key])
        assertEquals("new",after["metadata:selected"]);assertEquals(3,store.loadShell().getJSONArray("sessions").length())
    }
    @Test fun explicitSessionDeletionCascadesOnlyThatHistory()=isolated {store->
        store.save(fixture());val before=bytes(store)
        store.savePartialSnapshot(store.loadSession("a"),setOf("b"));val after=bytes(store)
        assertEquals("a",after["metadata:selected"])
        for((key,value) in before)if(key.endsWith(":a") || key.endsWith(":ra") || key.endsWith(":ma") || key.endsWith(":ea") || key.endsWith(":aa"))assertEquals(value,after[key])
        for(table in tables)store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use {assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))}
        rejected {store.loadSession("b")}
    }
    @Test fun failedFinalMetadataWriteRollsBackExplicitDeletionAndEveryPartialUpdate()=isolated {store->
        store.save(fixture());val before=bytes(store)
        val partial=store.loadSession("a");partial.getJSONArray("messages").getJSONObject(0).put("text","must roll back")
        store.writableDatabase.execSQL("CREATE TRIGGER block_partial_selection BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT,'selection failure'); END")
        rejected {store.savePartialSnapshot(partial,setOf("b"))};assertEquals(before,bytes(store))
    }
    @Test fun crossSessionReparentingAndMismatchedRunsAreRejectedAtomically()=isolated {store->
        store.save(fixture());val before=bytes(store)
        val partial=store.loadSession("a");partial.getJSONArray("messages").put(obj("id" to "mb","sessionId" to "a","runId" to "ra","text" to "steal row"))
        rejected {store.savePartialSnapshot(partial)};assertEquals(before,bytes(store))
        partial.getJSONArray("messages").remove(1);partial.getJSONArray("attachments").put(obj("id" to "new-image","sessionId" to "a","runId" to "rb"))
        rejected {store.savePartialSnapshot(partial)};assertEquals(before,bytes(store))
        rejected {store.savePartialSnapshot(store.loadShell(),setOf("a"))};assertEquals(before,bytes(store))
    }
    @Test fun boundedChatPageStripsHeavyFieldsBeforeCursorAndReportsOlderHistory()=isolated {store->
        val original=fixture();val runs=original.getJSONArray("runs");val messages=original.getJSONArray("messages");val events=original.getJSONArray("events")
        repeat(14) {i->
            val rid="recent-$i";runs.put(obj("id" to rid,"sessionId" to "a","status" to "completed","turn" to obj("messages" to JSONArray().put("t".repeat(260000))),"contexts" to JSONArray().put("private context"),"runtime" to obj("private" to true)))
            messages.put(obj("id" to "recent-message-$i","sessionId" to "a","runId" to rid,"role" to "assistant","text" to "answer-$i","attachments" to JSONArray().put(obj("id" to "nested","data" to "nested pixels","path" to "private path"))))
            repeat(3){n->events.put(obj("id" to "recent-event-$i-$n","sessionId" to "a","runId" to rid,"sequence" to n+1,"data" to obj("text" to "fragment $i $n")))}
        }
        runs.getJSONObject(runs.length()-1).getJSONObject("turn").put("oversized","z".repeat(3*1024*1024))
        original.getJSONArray("attachments").put(obj("id" to "recent-image","sessionId" to "a","runId" to "recent-13","size" to 42,"data" to "p".repeat(3*1024*1024),"path" to "private path"))
        store.save(original)
        val page=store.loadChatPage("a",3,5)
        assertTrue(page.getBoolean("historyHasMore"));assertEquals(15,page.getInt("historyRunCount"));assertEquals(3,page.getInt("historyPageLimit"))
        assertEquals(listOf("recent-11","recent-12","recent-13"),page.getJSONArray("runs").objects().map {it.getString("id")})
        for(row in page.getJSONArray("runs").objects()){assertFalse(row.has("turn"));assertFalse(row.has("contexts"));assertFalse(row.has("runtime"))}
        assertEquals(5,page.getJSONArray("events").length());assertEquals(3,page.getJSONArray("messages").length())
        assertFalse(page.toString().contains("nested pixels"));assertFalse(page.toString().contains("private path"));assertFalse(page.toString().contains("private context"))
        assertEquals(42,page.getJSONArray("attachments").objects().single {it.getString("id")=="recent-image"}.getInt("size"))
        val all=store.loadChatPage("b");assertFalse(all.getBoolean("historyHasMore"));assertEquals(1,all.getInt("historyRunCount"))
    }
    @Test fun voiceContextReadsOnlyRecentValidMessagesAndMinimalRunSource()=isolated {store->
        val original=fixture();val run=original.getJSONArray("runs").getJSONObject(0)
        run.getJSONObject("turn").put("source",JSONArray().put("screen_perception")).put("oversized","z".repeat(3*1024*1024))
        run.put("contexts",JSONArray().put("private context"));run.put("runtime",obj("private" to true))
        original.getJSONArray("messages").put(obj("id" to "old-voice","sessionId" to "a","runId" to "ra","role" to "user","text" to "older prompt"))
            .put(obj("id" to "new-voice","sessionId" to "a","runId" to "ra","role" to "assistant","status" to "complete","text" to "new reply","thinking" to "private reasoning","attachments" to JSONArray().put("pixels")))
            .put(obj("id" to "streaming-voice","sessionId" to "a","runId" to "ra","role" to "assistant","status" to "streaming","text" to "partial"))
        store.save(original);val voice=store.loadRecentVoiceContext("a",2)
        assertEquals(listOf("older prompt","new reply"),voice.getJSONArray("messages").objects().map {it.getString("text")})
        val source=voice.getJSONArray("runs").getJSONObject(0);assertEquals("[\"screen_perception\"]",source.getJSONObject("turn").getJSONArray("source").toString())
        assertFalse(source.has("contexts"));assertFalse(source.has("runtime"));assertEquals(1,source.getJSONObject("turn").length())
        assertFalse(voice.toString().contains("private reasoning"));assertEquals(0,voice.getJSONArray("events").length());assertEquals(0,voice.getJSONArray("attachments").length())
    }
    @Test fun attachmentLookupIsSessionScopedAndQuotaReadsScalarSizes()=isolated {store->
        val original=fixture();original.getJSONArray("attachments").getJSONObject(0).put("size",23)
        original.getJSONArray("attachments").getJSONObject(1).put("size",29).put("data","p".repeat(3*1024*1024))
        store.save(original)
        assertEquals(52L,store.attachmentBytes());assertNull(store.getAttachment("aa","b"));assertNull(store.getAttachment("missing","a"))
        assertEquals("pixels a",store.getAttachment("aa","a")!!.getString("data"));assertEquals(3*1024*1024,store.getAttachment("ab","b")!!.getString("data").length)
        assertEquals("context a",store.getRun("ra")!!.getJSONObject("turn").getJSONArray("messages").getString(0));assertNull(store.getRun("missing"))
    }

    @Test fun runtimeSessionKeepsCompletePiTurnsAndOnlyLegacyRecoveryEventsWithoutImages()=isolated {store->
        val original=fixture();val runs=original.getJSONArray("runs")
        runs.getJSONObject(0).getJSONObject("turn").put("complete",true)
        runs.getJSONObject(0).put("contexts",JSONArray().put("duplicate private observation")).put("runtime",obj("old runtime" to true))
        runs.put(obj("id" to "legacy","sessionId" to "a","status" to "failed","turn" to obj("complete" to false,"messages" to JSONArray().put(obj("kind" to "user","content" to JSONArray().put("original question"))))))
            .put(obj("id" to "partial-pi","sessionId" to "a","status" to "failed","turn" to obj("complete" to false,"messages" to JSONArray().put(obj("kind" to "user")).put(obj("kind" to "assistant","text" to "already retained")))))
            .put(obj("id" to "probe","sessionId" to "a","probe" to true,"turn" to obj("messages" to JSONArray().put(obj("kind" to "user")))))
        val events=original.getJSONArray("events")
        for(rid in listOf("legacy","partial-pi","probe"))events.put(obj("id" to "event-$rid","sessionId" to "a","runId" to rid,"sequence" to 1,"type" to "text_delta","data" to obj("text" to "recoverable answer")))
        original.getJSONArray("attachments").getJSONObject(0).put("data","p".repeat(3*1024*1024))
        store.save(original)
        val runtime=store.loadRuntimeSession("a")
        assertEquals(4,runtime.getJSONArray("runs").length());assertEquals(0,runtime.getJSONArray("messages").length());assertEquals(0,runtime.getJSONArray("attachments").length())
        for(run in runtime.getJSONArray("runs").objects()){assertFalse(run.has("contexts"));assertFalse(run.has("runtime"))}
        assertEquals(listOf("legacy"),runtime.getJSONArray("events").objects().map {it.getString("runId")})
        assertEquals(runs.getJSONObject(0).getJSONObject("turn").toString(),runtime.getJSONArray("runs").getJSONObject(0).getJSONObject("turn").toString())
        assertEquals("already retained",runtime.getJSONArray("runs").objects().single {it.getString("id")=="partial-pi"}.getJSONObject("turn").getJSONArray("messages").getJSONObject(1).getString("text"))
    }

    @Test fun explicitAndRecoveryRunReadsSupportOversizedPiTurnWithoutGlobalHistoryLoad()=isolated {store->
        val original=fixture();original.getJSONArray("runs").getJSONObject(1).getJSONObject("turn").put("complete",true).put("large","z".repeat(3*1024*1024))
        original.getJSONArray("runs").getJSONObject(1).put("contexts",JSONArray().put("duplicate context")).put("runtime",obj("old runtime" to true))
        store.save(original)
        assertEquals(3*1024*1024,store.getRun("rb")!!.getJSONObject("turn").getString("large").length)
        val recovery=store.loadRecoveryRuns();assertEquals(1,recovery.size);assertEquals("rb",recovery.single().getString("id"))
        assertEquals(3*1024*1024,recovery.single().getJSONObject("turn").getString("large").length)
        assertEquals(3*1024*1024,store.loadSession("b").getJSONArray("runs").getJSONObject(0).getJSONObject("turn").getString("large").length)
        val runtime=store.loadRuntimeSession("b");val row=runtime.getJSONArray("runs").getJSONObject(0)
        assertEquals(3*1024*1024,row.getJSONObject("turn").getString("large").length);assertFalse(row.has("contexts"));assertFalse(row.has("runtime"))
        assertEquals(0,runtime.getJSONArray("messages").length());assertEquals(0,runtime.getJSONArray("events").length());assertEquals(0,runtime.getJSONArray("attachments").length())
    }

    @Test fun screenshotOwnerLookupProjectsOnlyIdentityAndRespectsSessionAndProbe()=isolated {store->
        val state=fixture();state.getJSONArray("runs").getJSONObject(0).put("privateLargeField","x".repeat(3*1024*1024)).put("probe",true)
        store.save(state)
        val owner=store.getRunIdentity("ra","a")!!
        assertEquals(setOf("id","sessionId","probe"),owner.keys().asSequence().toSet())
        assertEquals("ra",owner.getString("id"));assertTrue(owner.getBoolean("probe"))
        assertFalse(store.getRunIdentity("rb","b")!!.getBoolean("probe"))
        assertNull(store.getRunIdentity("ra","b"));assertNull(store.getRunIdentity("missing","a"))
    }

}
