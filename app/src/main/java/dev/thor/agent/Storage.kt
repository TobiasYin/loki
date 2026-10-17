package dev.thor.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned private DB. Changed rows and selection commit together; failed upgrade never wipes history. */
class SqliteStore(context: Context, name: String = "conversations.db") : SQLiteOpenHelper(context, name, null, 1), NativeStorePort {
    private val tables = listOf("sessions", "runs", "messages", "events", "attachments")
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE runs(id TEXT PRIMARY KEY, sessionId TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, sessionId TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, runId TEXT REFERENCES runs(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY, sessionId TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, runId TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE, sequence INTEGER NOT NULL, payload TEXT NOT NULL, UNIQUE(runId,sequence))")
        db.execSQL("CREATE TABLE attachments(id TEXT PRIMARY KEY, sessionId TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, runId TEXT REFERENCES runs(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        for (table in tables.drop(1)) db.execSQL("CREATE INDEX ${table}_session ON $table(sessionId)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("不支持的数据库迁移；保留历史并停止升级") }
    override fun load(): JSONObject {
        val state = JSONObject()
        for (table in tables) {
            val rows = JSONArray()
            readableDatabase.rawQuery("SELECT payload FROM $table ORDER BY rowid", null).use { c -> while(c.moveToNext()) rows.put(JSONObject(c.getString(0))) }
            state.put(table, rows)
        }
        readableDatabase.rawQuery("SELECT value FROM metadata WHERE name='selected'", null).use { if(it.moveToFirst()) state.put("selected", it.getString(0)) }
        return state
    }
    /** Startup shell: only session metadata and selection enter the Java heap.
     * All conversation bodies remain on disk until explicitly requested. */
    fun loadShell(): JSONObject {
        val db=readableDatabase
        val state=JSONObject()
        for(table in tables)state.put(table,JSONArray())
        db.rawQuery("SELECT payload FROM sessions ORDER BY rowid",null).use {c->while(c.moveToNext())state.getJSONArray("sessions").put(JSONObject(c.getString(0)))}
        db.rawQuery("SELECT value FROM metadata WHERE name='selected'",null).use {c->if(c.moveToFirst())state.put("selected",c.getString(0))}
        return state
    }

    /** Load one session for an active task or explicit history inspection.
     * Callers own and may discard the returned objects; the store retains none. */
    fun loadSession(sessionId:String): JSONObject {
        require(sessionId.isNotBlank())
        val db=readableDatabase
        val state=JSONObject().put("selected",sessionId)
        db.beginTransactionNonExclusive()
        try {
            val sessions=JSONArray()
            db.rawQuery("SELECT payload FROM sessions WHERE id=?",arrayOf(sessionId)).use {c->if(c.moveToFirst())sessions.put(JSONObject(c.getString(0)))}
            require(sessions.length()==1) {"Unknown session"}
            state.put("sessions",sessions)
            for(table in tables.drop(1)) {
                state.put(table,JSONArray(completeRows(table,"sessionId=?",arrayOf(sessionId),"ORDER BY rowid")))
            }
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
        return state
    }

    /** Runtime history is hydrated only while this session is executing.
     * Complete Pi turns already contain their history; do not load their
     * duplicate streamed event ledger or any image bodies alongside them. */
    fun loadRuntimeSession(sessionId:String):JSONObject {
        val state=sessionShell(sessionId)
        val runs=projectedRows("runs","sessionId=?",arrayOf(sessionId),"ORDER BY rowid",
            "json_remove(payload,'$.contexts','$.runtime')") {row->row.remove("contexts");row.remove("runtime");row}
        state.put("runs",JSONArray(runs))
        // Pi turns are the authoritative task history. Chat messages duplicate
        // their prose and thinking and belong only in the bounded UI projection.
        val events=JSONArray()
        for(run in runs) {
            if(run.optBoolean("probe"))continue
            val turn=run.optJSONObject("turn") ?: continue
            val messages=turn.optJSONArray("messages")
            val needsRecovery=!turn.optBoolean("complete") && messages!=null && (0 until messages.length()).all {messages.getJSONObject(it).optString("kind")=="user"}
            if(needsRecovery)completeRows("events","sessionId=? AND runId=?",arrayOf(sessionId,run.getString("id")),"ORDER BY sequence,rowid").forEach(events::put)
        }
        state.put("events",events)
        return state
    }

    /** Filter statuses inside SQLite, so completed Pi turns are never parsed or
     * retained by startup merely to discover interrupted tasks. */
    fun loadRecoveryRuns(): List<JSONObject> {
        val rows=mutableListOf<JSONObject>()
        try {
            readableDatabase.rawQuery("SELECT id FROM runs WHERE COALESCE(json_extract(payload,'$.status'),'') NOT IN ('completed','failed','cancelled','interrupted') ORDER BY rowid",null)
                .use {c->while(c.moveToNext())readPayload("runs",c.getString(0))?.let(rows::add)}
        }catch(error:android.database.sqlite.SQLiteException) {
            if(!missingJsonFunction(error))throw error
            readableDatabase.rawQuery("SELECT id FROM runs ORDER BY rowid",null).use {c->while(c.moveToNext()) {
                val row=requireNotNull(readPayload("runs",c.getString(0)))
                if(row.optString("status") !in setOf("completed","failed","cancelled","interrupted"))rows.add(row)
            }}
        }
        return rows
    }

    /** A bounded, temporary UI window. Heavy Pi turns and image bodies are
     * removed inside SQLite before any CursorWindow/Java object is allocated. */
    fun loadChatPage(sessionId:String,runLimit:Int=12,eventLimit:Int=600):JSONObject {
        require(runLimit in 1..4096 && eventLimit in 0..2000)
        val state=sessionShell(sessionId)
        val total=readableDatabase.rawQuery("SELECT COUNT(*) FROM runs WHERE sessionId=?",arrayOf(sessionId)).use {c->check(c.moveToFirst());c.getInt(0)}
        state.put("historyHasMore",total>runLimit).put("historyRunCount",total).put("historyPageLimit",runLimit)
        val runIds="SELECT id FROM runs WHERE sessionId=? ORDER BY rowid DESC LIMIT ?"
        val related="sessionId=? AND (runId IS NULL OR runId IN ($runIds))"
        val args=arrayOf(sessionId,sessionId,runLimit.toString())
        state.put("runs",JSONArray(projectedRows("runs","sessionId=?",arrayOf(sessionId),"ORDER BY rowid DESC LIMIT $runLimit",
            "json_remove(payload,'$.turn','$.contexts','$.runtime')") {row->row.remove("turn");row.remove("contexts");row.remove("runtime");row}.reversed()))
        state.put("messages",JSONArray(projectedRows("messages",related,args,"ORDER BY rowid DESC LIMIT ${runLimit*4+32}",
            messageProjectionSql(),::stripMessageData).reversed()))
        state.put("events",JSONArray(projectedRows("events",related,args,"ORDER BY rowid DESC LIMIT $eventLimit","payload") {it}.reversed()))
        state.put("attachments",JSONArray(projectedRows("attachments",related,args,"ORDER BY rowid DESC LIMIT 256",
            "json_remove(payload,'$.data','$.path')") {row->row.remove("data");row.remove("path");row}.reversed()))
        return state
    }

    /** Only recent conversation text plus the source tags needed to honour
     * screen-perception revocation. Never load full Pi turns for ASR hints. */
    fun loadRecentVoiceContext(sessionId:String,limit:Int=6):JSONObject {
        require(limit in 1..24)
        val state=sessionShell(sessionId)
        val messages=projectedRows("messages","sessionId=?",arrayOf(sessionId),"ORDER BY rowid DESC LIMIT ${limit*4}",
            "json_remove(payload,'$.thinking','$.attachments','$.data','$.path')") {row->
                row.remove("thinking");row.remove("attachments");row.remove("data");row.remove("path");row
            }.filter {it.optString("role") in setOf("user","assistant") && it.optString("status")!="streaming" && it.optString("text").isNotBlank()}.take(limit)
        state.put("messages",JSONArray(messages.reversed()))
        val runs=JSONArray()
        for(runId in messages.map {it.optString("runId")}.filter {it.isNotBlank() && it!="null"}.distinct()) {
            val rows=projectedRows("runs","id=? AND sessionId=?",arrayOf(runId,sessionId),"LIMIT 1",
                "json_set(json_remove(payload,'$.turn','$.contexts','$.runtime'),'$.turn',json_object('source',json_extract(payload,'$.turn.source')))") {row->
                    val source=row.optJSONObject("turn")?.optJSONArray("source")
                    row.remove("contexts");row.remove("runtime");row.put("turn",JSONObject().apply {if(source!=null)put("source",source)});row
                }
            rows.firstOrNull()?.let(runs::put)
        }
        state.put("runs",runs)
        return state
    }

    /** Explicit history-row lookup for a local expansion or retry update. */
    fun getRun(runId:String):JSONObject? {
        require(runId.isNotBlank())
        return readPayload("runs",runId)
    }

    /** Explicit, session-scoped image/content lookup. Chunked reads also work
     * for a single attachment larger than Android's ordinary CursorWindow. */
    fun getAttachment(attachmentId:String,sessionId:String):JSONObject? {
        require(attachmentId.isNotBlank() && sessionId.isNotBlank())
        return readPayload("attachments",attachmentId,sessionId)
    }
    private fun readPayload(table:String,rowId:String,sessionId:String?=null):JSONObject? {
        require(table in tables)
        val db=readableDatabase
        val where=if(sessionId==null)"id=?" else "id=? AND sessionId=?"
        val args=if(sessionId==null)arrayOf(rowId) else arrayOf(rowId,sessionId)
        val length=db.rawQuery("SELECT length(payload) FROM $table WHERE $where",args).use {c->if(c.moveToFirst())c.getInt(0) else return null}
        val text=StringBuilder(length)
        var offset=1
        while(offset<=length) {
            db.rawQuery("SELECT substr(payload,?,65536) FROM $table WHERE $where",arrayOf(offset.toString(),*args)).use {c->
                check(c.moveToFirst()) {"Stored row disappeared during read"};text.append(c.getString(0))
            }
            offset+=65536
        }
        return JSONObject(text.toString())
    }

    /** Global quota needs scalar sizes, never all encoded image payloads. */
    fun attachmentBytes():Long {
        try {
            readableDatabase.rawQuery("SELECT COALESCE(SUM(CAST(json_extract(payload,'$.size') AS INTEGER)),0) FROM attachments",null).use {c->check(c.moveToFirst());return c.getLong(0)}
        }catch(error:android.database.sqlite.SQLiteException) {
            if(!missingJsonFunction(error))throw error
        }
        var total=0L
        // Compatibility path for builds without SQLite JSON functions: inspect
        // one row at a time, retaining only its numeric size between rows.
        readableDatabase.rawQuery("SELECT id,sessionId FROM attachments ORDER BY rowid",null).use {c->while(c.moveToNext()) {
            total+=getAttachment(c.getString(0),c.getString(1))?.optLong("size",0) ?: 0
        }}
        return total
    }
    private fun sessionShell(sessionId:String):JSONObject {
        require(sessionId.isNotBlank())
        val state=JSONObject().put("selected",sessionId)
        for(table in tables)state.put(table,JSONArray())
        readableDatabase.rawQuery("SELECT payload FROM sessions WHERE id=?",arrayOf(sessionId)).use {c->
            require(c.moveToFirst()) {"Unknown session"};state.getJSONArray("sessions").put(JSONObject(c.getString(0)))
        }
        return state
    }
    private fun missingJsonFunction(error:android.database.sqlite.SQLiteException)=error.message.orEmpty().contains("no such function",ignoreCase=true)
    private fun messageProjectionSql():String {
        // Native messages currently allow at most two images; trim a wider
        // compatibility window before Java allocation as well as after parsing.
        val paths=mutableListOf("'$.data'","'$.path'")
        repeat(16){i->paths.add("'$.attachments[$i].data'");paths.add("'$.attachments[$i].path'")}
        return "json_remove(payload,${paths.joinToString(",")})"
    }
    private fun stripMessageData(row:JSONObject):JSONObject {
        row.remove("data");row.remove("path")
        row.optJSONArray("attachments")?.let {attachments->for(i in 0 until attachments.length())attachments.optJSONObject(i)?.let {it.remove("data");it.remove("path")}}
        return row
    }
    private fun completeRows(table:String,where:String,args:Array<String>,tail:String):List<JSONObject> {
        return try {readableDatabase.rawQuery("SELECT payload FROM $table WHERE $where $tail",args).use {c->buildList {while(c.moveToNext())add(JSONObject(c.getString(0)))}}}
        catch(_:android.database.sqlite.SQLiteBlobTooBigException) {
            readableDatabase.rawQuery("SELECT id FROM $table WHERE $where $tail",args).use {c->buildList {while(c.moveToNext())readPayload(table,c.getString(0))?.let(::add)}}
        }
    }
    private fun projectedRows(table:String,where:String,args:Array<String>,tail:String,expression:String,trim:(JSONObject)->JSONObject):List<JSONObject> {
        fun read(sql:String)=readableDatabase.rawQuery(sql,args).use {c->buildList {while(c.moveToNext())add(trim(JSONObject(c.getString(0))))}}
        return try {read("SELECT $expression FROM $table WHERE $where $tail")}
        catch(error:android.database.sqlite.SQLiteException) {
            if(error !is android.database.sqlite.SQLiteBlobTooBigException && (expression=="payload" || !missingJsonFunction(error)))throw error
            readableDatabase.rawQuery("SELECT id FROM $table WHERE $where $tail",args).use {c->buildList {
                while(c.moveToNext())readPayload(table,c.getString(0))?.let {add(trim(it))}
            }}
        }
    }

    /** Persist explicitly resident rows without treating missing disk history
     * as deletion. Destructive deletion requires an explicit session ID set.
     * Parent updates stay in place, preserving child rows and durable row IDs. */
    fun savePartialSnapshot(state:JSONObject,deletedSessionIds:Set<String> = emptySet()) {
        val sessions=state.getJSONArray("sessions").objects()
        val sessionIds=sessions.map {it.getString("id")}.toSet()
        val selected=state.getString("selected")
        require(selected in sessionIds && selected !in deletedSessionIds)
        require(sessionIds.intersect(deletedSessionIds).isEmpty()) {"Deleted sessions cannot also be saved"}
        val db=writableDatabase
        fun rowValues(table:String,row:JSONObject)=ContentValues().apply {
            put("id",row.getString("id"));put("payload",row.toString())
            if(table!="sessions")put("sessionId",row.getString("sessionId"))
            if(table!="sessions" && table!="runs") {if(row.isNull("runId"))putNull("runId") else put("runId",row.getString("runId"))}
            if(table=="events")put("sequence",row.getInt("sequence"))
        }
        db.beginTransaction()
        try {
            for(sid in deletedSessionIds)db.delete("sessions","id=?",arrayOf(sid))
            for(table in tables)for(row in state.getJSONArray(table).objects()) {
                if(table!="sessions") {
                    val sid=row.getString("sessionId");require(sid in sessionIds && sid !in deletedSessionIds)
                    db.rawQuery("SELECT sessionId FROM $table WHERE id=?",arrayOf(row.getString("id"))).use {c->
                        require(!c.moveToFirst() || c.getString(0)==sid) {"Existing row belongs to another session"}
                    }
                    if(table!="runs" && !row.isNull("runId"))db.rawQuery("SELECT sessionId FROM runs WHERE id=?",arrayOf(row.getString("runId"))).use {c->
                        require(c.moveToFirst() && c.getString(0)==sid) {"Child Run/Session mismatch"}
                    }
                }
                val values=rowValues(table,row);val rowId=row.getString("id");val payload=values.getAsString("payload")
                val changed=db.update(table,values,"id=? AND payload<>?",arrayOf(rowId,payload))
                if(changed==0)db.rawQuery("SELECT 1 FROM $table WHERE id=?",arrayOf(rowId)).use {c->if(!c.moveToFirst())db.insertOrThrow(table,null,values)}
            }
            db.insertWithOnConflict("metadata",null,ContentValues().apply {put("name","selected");put("value",selected)},SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
    }

    override fun save(state: JSONObject) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Keep identities only. Reading all committed payloads here duplicates
            // the entire in-memory history, including images and durable Pi turns.
            // Compare each bound new payload inside SQLite instead of materializing
            // old bodies through CursorWindow into a second Java history map.
            fun existingIds(table:String):MutableSet<String> {
                val ids=mutableSetOf<String>()
                db.rawQuery("SELECT id FROM $table",null).use {c->while(c.moveToNext())ids.add(c.getString(0))}
                return ids
            }
            // Delete children first, insert parents first; unchanged rows do not
            // execute UPDATE triggers or replace parents/cascade their history.
            for (table in tables.asReversed()) {
                val wanted = state.getJSONArray(table).objects().map { it.getString("id") }.toSet()
                existingIds(table).filter { it !in wanted }.forEach { db.delete(table, "id=?", arrayOf(it)) }
            }
            for (table in tables) {
                // Parent deletion may have cascaded rows that remain in the desired
                // snapshot under a different parent. Inspect current IDs after all
                // deletions so those rows are inserted rather than silently lost.
                val existing=existingIds(table)
                for (row in state.getJSONArray(table).objects()) {
                    val payload = row.toString(); val id = row.getString("id")
                    val values = ContentValues().apply {
                        put("id", id); put("payload", payload)
                        if(table != "sessions") put("sessionId", row.getString("sessionId"))
                        if(table != "sessions" && table != "runs") { if(row.isNull("runId")) putNull("runId") else put("runId", row.getString("runId")) }
                        if(table == "events") put("sequence", row.getInt("sequence"))
                    }
                    if(id in existing) db.update(table, values, "id=? AND payload<>?", arrayOf(id,payload))
                    else {db.insertOrThrow(table, null, values);existing.add(id)}
                }
            }
            val selected = ContentValues().apply { put("name", "selected"); put("value", state.optString("selected")) }
            db.insertWithOnConflict("metadata", null, selected, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Startup modifies session metadata and explicitly recovered nonterminal
     * Runs only. Never reread/compare every old payload or rewrite/delete child
     * history merely to open the app. */
    fun saveStartupRecovery(state:JSONObject,recoveredRunIds:Set<String>) {
        val sessions=state.getJSONArray("sessions").objects()
        val recovered=state.getJSONArray("runs").objects().filter {it.getString("id") in recoveredRunIds}
        require(recovered.map {it.getString("id")}.toSet()==recoveredRunIds && recovered.size==recoveredRunIds.size)
        require(recovered.all {it.optString("status")=="interrupted"})
        require(sessions.any {it.getString("id")==state.optString("selected")})
        val db=writableDatabase;db.beginTransaction()
        try {
            for(row in sessions) {
                val values=ContentValues().apply {put("id",row.getString("id"));put("payload",row.toString())}
                if(db.update("sessions",values,"id=?",arrayOf(row.getString("id")))==0)db.insertOrThrow("sessions",null,values)
            }
            for(row in recovered) {
                val id=row.getString("id")
                db.rawQuery("SELECT sessionId,payload FROM runs WHERE id=?",arrayOf(id)).use {c->
                    require(c.moveToFirst() && c.getString(0)==row.getString("sessionId")) {"Unknown recovered Run"}
                    require(JSONObject(c.getString(1)).optString("status") !in setOf("completed","failed","cancelled","interrupted")) {"Terminal Run cannot be recovered again"}
                }
                val values=ContentValues().apply {put("sessionId",row.getString("sessionId"));put("payload",row.toString())}
                check(db.update("runs",values,"id=?",arrayOf(id))==1)
            }
            db.insertWithOnConflict("metadata",null,ContentValues().apply {put("name","selected");put("value",state.getString("selected"))},SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
    }

    /** Changing a title/selection must not reread the durable event ledger. */
    fun saveSessionUpdate(state:JSONObject,changedSessionId:String?=null) {
        val db=writableDatabase;db.beginTransaction()
        try {
            for(row in state.getJSONArray("sessions").objects().filter {changedSessionId==null || it.getString("id")==changedSessionId}) {
                val values=ContentValues().apply {put("id",row.getString("id"));put("payload",row.toString())}
                if(db.update("sessions",values,"id=?",arrayOf(row.getString("id")))==0)db.insertOrThrow("sessions",null,values)
            }
            db.insertWithOnConflict("metadata",null,ContentValues().apply {put("name","selected");put("value",state.optString("selected"))},SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }

    /** A captured image appends one row; never scans or rewrites old image bodies,
     * messages or evidence. A duplicate identity must fail rather than replace. */
    fun saveAttachment(row:JSONObject) {
        val db=writableDatabase
        val sid=row.getString("sessionId")
        val rid=if(row.isNull("runId"))null else row.getString("runId")
        val values=ContentValues().apply {
            put("id",row.getString("id"));put("sessionId",sid);put("payload",row.toString())
            if(rid==null)putNull("runId") else put("runId",rid)
        }
        db.beginTransaction()
        try {
            if(rid!=null)db.rawQuery("SELECT sessionId FROM runs WHERE id=?",arrayOf(rid)).use {c->
                require(c.moveToFirst() && c.getString(0)==sid){"Attachment Run/Session mismatch"}
            }
            db.insertOrThrow("attachments",null,values)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }

    /** Current-run streaming transaction. Full snapshots still handle deletion and UI changes.
     * Updating parents in place preserves their existing children. Events only append;
     * a duplicate ID/sequence rolls back the run, message and selection together.
     */
    fun saveRunUpdate(state: JSONObject, run: JSONObject, event: JSONObject?) {
        val rid=run.getString("id");val sid=run.getString("sessionId")
        require(event==null || event.getString("runId")==rid && event.getString("sessionId")==sid)
        val db=writableDatabase
        fun values(table:String,row:JSONObject)=ContentValues().apply {
            put("id",row.getString("id"));put("payload",row.toString())
            if(table!="sessions")put("sessionId",row.getString("sessionId"))
            if(table=="messages" || table=="events") {if(row.isNull("runId"))putNull("runId") else put("runId",row.getString("runId"))}
            if(table=="events")put("sequence",row.getInt("sequence"))
        }
        fun upsert(table:String,row:JSONObject) {
            val v=values(table,row)
            if(db.update(table,v,"id=?",arrayOf(row.getString("id")))==0)db.insertOrThrow(table,null,v)
        }
        db.beginTransaction()
        try {
            upsert("sessions",state.getJSONArray("sessions").objects().single {it.getString("id")==sid})
            upsert("runs",run)
            state.getJSONArray("messages").objects().filter {it.optString("runId")==rid}.forEach {upsert("messages",it)}
            if(event!=null)db.insertOrThrow("events",null,values("events",event))
            db.insertWithOnConflict("metadata",null,ContentValues().apply {put("name","selected");put("value",state.optString("selected"))},SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
}

/** Secrets only enter through Native widgets. The web bridge has no key read/write API. */
class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("native-credentials", Context.MODE_PRIVATE)
    private val alias = "thor-agent-credentials-v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun put(ref: String, secret: String) {
        require(secret.length in 1..8192 && '\r' !in secret && '\n' !in secret)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(ref.toByteArray()) }
        val encoded = obj("iv" to Base64.encodeToString(cipher.iv, Base64.NO_WRAP), "cipher" to Base64.encodeToString(cipher.doFinal(secret.toByteArray()), Base64.NO_WRAP))
        check(prefs.edit().putString(ref, encoded.toString()).commit()) { "凭据保存失败" }
    }
    // Native transport may use this in a future adapter; never return it through the bridge.
    fun readNative(ref: String): String? {
        val saved = prefs.getString(ref, null) ?: return null; val data = JSONObject(saved)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(data.getString("iv"), Base64.NO_WRAP))); updateAAD(ref.toByteArray()) }
        return cipher.doFinal(Base64.decode(data.getString("cipher"), Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    fun status(ref: String): String = if(!prefs.contains(ref)) "未保存" else try { readNative(ref); "已加密保存" } catch(_: Exception) { "无法解密，请重新配置；会话仍保留" }
    fun remove(ref: String) { check(prefs.edit().remove(ref).commit()) }
}

class AttachmentFiles(context: Context, name: String = "attachments") : AttachmentPort {
    private val directory = File(context.filesDir, name).apply { mkdirs() }
    override fun createText(sessionId: String, runId: String?, text: String): JSONObject {
        val bytes = text.toByteArray(); require(bytes.size <= 65536) { "附件超出 64 KiB" }
        val aid = id(); val temp = File(directory, "$aid.tmp"); val final = File(directory, "$aid.txt")
        temp.outputStream().use { it.write(bytes); it.flush() }
        check(temp.renameTo(final)) { "附件发布失败" }
        return obj("id" to aid, "sessionId" to sessionId, "runId" to runId, "mime" to "text/plain", "path" to final.name, "size" to bytes.size,
            "hash" to MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, "sourcePermission" to "demo", "expiresAt" to System.currentTimeMillis()+86_400_000)
    }
    fun cleanup(references: List<JSONObject>) {
        val names = references.filter { it.optLong("expiresAt", Long.MAX_VALUE) > System.currentTimeMillis() }.map { it.getString("path") }.toSet()
        directory.listFiles()?.filter { it.name.endsWith(".tmp") || it.name !in names }?.forEach { it.delete() }
    }
}
