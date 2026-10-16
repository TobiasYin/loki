package dev.thor.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite, disposable database only; never opens product.db or settings. */
@RunWith(AndroidJUnit4::class)
class StartupRecoveryStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture():JSONObject=obj("selected" to "session-a","sessions" to JSONArray().put(obj("id" to "session-a","title" to "旧标题")),
        "runs" to JSONArray().put(obj("id" to "recover","sessionId" to "session-a","status" to "generating",
            "turn" to obj("complete" to false,"messages" to JSONArray().put("fixture-context-".repeat(2000))),"contexts" to JSONArray().put(obj("retained" to true))))
            .put(obj("id" to "terminal","sessionId" to "session-a","status" to "completed","turn" to obj("complete" to true))),
        "messages" to JSONArray().put(obj("id" to "message","sessionId" to "session-a","runId" to "recover","role" to "assistant","text" to "完整历史保持原字节")),
        "events" to JSONArray().put(obj("id" to "event","sessionId" to "session-a","runId" to "recover","sequence" to 1,"type" to "thinking_delta","data" to obj("text" to "不可重写的事件"))),
        "attachments" to JSONArray().put(obj("id" to "attachment","sessionId" to "session-a","runId" to "recover","data" to "synthetic-retained-image-data")))
    private fun planned(state:JSONObject)=JSONObject(state.toString()).apply {
        getJSONArray("sessions").getJSONObject(0).put("title","恢复后的标题")
        getJSONArray("sessions").put(obj("id" to "session-b","title" to "新会话"));put("selected","session-b")
        getJSONArray("runs").getJSONObject(0).put("status","interrupted").put("stopReason","进程恢复：不重放任何副作用")
    }
    private fun <T> isolated(block:(SqliteStore)->T):T {
        val name="startup-recovery-${java.util.UUID.randomUUID()}.db";val store=SqliteStore(context,name)
        try {return block(store)}finally {store.close();check(context.deleteDatabase(name))}
    }
    private fun bytes(store:SqliteStore):Map<String,String> = buildMap {
        for(table in listOf("sessions","runs","messages","events","attachments"))store.readableDatabase.rawQuery("SELECT id,payload FROM $table",null).use {c->while(c.moveToNext())put("$table:${c.getString(0)}",c.getString(1))}
        store.readableDatabase.rawQuery("SELECT name,value FROM metadata",null).use {c->while(c.moveToNext())put("metadata:${c.getString(0)}",c.getString(1))}
    }
    private fun protectHistory(store:SqliteStore) {
        for(table in listOf("messages","events","attachments"))for(action in listOf("INSERT","UPDATE","DELETE"))
            store.writableDatabase.execSQL("CREATE TRIGGER deny_${table}_${action} BEFORE $action ON $table BEGIN SELECT RAISE(ABORT,'old child write forbidden'); END")
        for(action in listOf("UPDATE","DELETE"))store.writableDatabase.execSQL("CREATE TRIGGER deny_terminal_$action BEFORE $action ON runs WHEN OLD.id='terminal' BEGIN SELECT RAISE(ABORT,'terminal write forbidden'); END")
        store.writableDatabase.execSQL("CREATE TRIGGER deny_new_run BEFORE INSERT ON runs BEGIN SELECT RAISE(ABORT,'new recovered Run forbidden'); END")
    }
    private fun rejected(block:()->Unit) {var failed=false;try {block()}catch(_:Exception){failed=true};assertTrue("Invalid startup transaction must fail",failed)}

    @Test fun recoversOnlyExplicitRunsAndSessionMetadataWithoutTouchingHistoryOrTerminalRuns() = isolated {store->
        val state=fixture();store.save(state);val before=bytes(store);protectHistory(store)
        store.saveStartupRecovery(planned(state),setOf("recover"));val after=bytes(store)
        for((key,value) in before)if(key.startsWith("messages:") || key.startsWith("events:") || key.startsWith("attachments:") || key=="runs:terminal")assertEquals(key,value,after[key])
        assertEquals("session-b",after["metadata:selected"]);assertTrue(after.containsKey("sessions:session-b"))
        assertEquals("恢复后的标题",JSONObject(after.getValue("sessions:session-a")).getString("title"))
        val recovered=JSONObject(after.getValue("runs:recover"));assertEquals("interrupted",recovered.getString("status"))
        val original=JSONObject(before.getValue("runs:recover"));assertEquals(original.getJSONObject("turn").toString(),recovered.getJSONObject("turn").toString());assertEquals(original.getJSONArray("contexts").toString(),recovered.getJSONArray("contexts").toString())
    }
    @Test fun metadataFailureRollsBackSessionCreationTitleAndRecoveredRunTogether() = isolated {store->
        val state=fixture();store.save(state);val before=bytes(store);protectHistory(store)
        store.writableDatabase.execSQL("CREATE TRIGGER fail_selection BEFORE INSERT ON metadata WHEN NEW.name='selected' BEGIN SELECT RAISE(ABORT,'fixture selection failure'); END")
        rejected {store.saveStartupRecovery(planned(state),setOf("recover"))};assertEquals(before,bytes(store))
    }
    @Test fun unknownAndTerminalRecoveryIdentifiersAreRejectedWithoutAnyPartialWrite() = isolated {store->
        val state=fixture();store.save(state);val before=bytes(store);protectHistory(store)
        rejected {store.saveStartupRecovery(planned(state),setOf("not-in-state"))};assertEquals(before,bytes(store))
        val unknown=planned(state).apply {getJSONArray("runs").put(obj("id" to "unknown","sessionId" to "session-a","status" to "interrupted"))}
        rejected {store.saveStartupRecovery(unknown,setOf("unknown"))};assertEquals(before,bytes(store))
        val terminal=planned(state).apply {getJSONArray("runs").getJSONObject(1).put("status","interrupted")}
        rejected {store.saveStartupRecovery(terminal,setOf("terminal"))};assertEquals(before,bytes(store))
        store.saveStartupRecovery(planned(state),emptySet());assertEquals(before["runs:recover"],bytes(store)["runs:recover"])
    }
}
