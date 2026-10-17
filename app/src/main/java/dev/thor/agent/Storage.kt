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
class SqliteStore(context: Context, name: String = "conversations.db") : SQLiteOpenHelper(context, name, null, 2), NativeStorePort {
    private val tables = listOf("sessions", "runs", "messages", "events", "attachments")
    // Only small usage receipts for the visible completed runs, never old chat
    // bodies/Pi entries. Leave in-flight runs on disk until a footer is needed.
    private val usageRoundCache=linkedMapOf<String,List<JSONObject>>()
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE runs(id TEXT PRIMARY KEY, sessionId TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, sessionId TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, runId TEXT REFERENCES runs(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY, sessionId TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, runId TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE, sequence INTEGER NOT NULL, payload TEXT NOT NULL, UNIQUE(runId,sequence))")
        db.execSQL("CREATE TABLE attachments(id TEXT PRIMARY KEY, sessionId TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, runId TEXT REFERENCES runs(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        for (table in tables.drop(1)) db.execSQL("CREATE INDEX ${table}_session ON $table(sessionId)")
        createPiSessionTables(db)
    }
    private fun createPiSessionTables(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE pi_sessions(sessionId TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE, header TEXT NOT NULL, leafId TEXT)")
        db.execSQL("CREATE TABLE pi_session_entries(sessionId TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, id TEXT NOT NULL, parentId TEXT, sequence INTEGER NOT NULL, payload TEXT NOT NULL, source TEXT NOT NULL, runId TEXT NOT NULL, PRIMARY KEY(sessionId,id), UNIQUE(sessionId,sequence))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion==1 && newVersion==2) {"不支持的数据库迁移；保留历史并停止升级"}
        // Additive migration: never read, rewrite or reconstruct a legacy turn.
        createPiSessionTables(db)
    }

    /** Opaque upstream Pi entries, loaded for one running conversation only.
     * The shell and chat projection deliberately never include this journal. */
    fun loadPiSession(sessionId:String):JSONObject? {
        require(sessionId.isNotBlank())
        val db=readableDatabase
        db.beginTransactionNonExclusive()
        try {
            val state=db.rawQuery("SELECT header,leafId FROM pi_sessions WHERE sessionId=?",arrayOf(sessionId)).use {c->
                if(!c.moveToFirst())return null
                JSONObject().put("header",JSONObject(c.getString(0))).put("leafId",if(c.isNull(1))JSONObject.NULL else c.getString(1))
            }
            val entries=JSONArray();val provenance=JSONObject()
            db.rawQuery("SELECT id,source FROM pi_session_entries WHERE sessionId=? ORDER BY sequence",arrayOf(sessionId)).use {c->
                while(c.moveToNext()) {
                    val entryId=c.getString(0)
                    entries.put(JSONObject(readPiEntry(sessionId,entryId)))
                    provenance.put(entryId,JSONArray(c.getString(1)))
                }
            }
            state.put("entries",entries).put("provenance",provenance)
            db.setTransactionSuccessful()
            return state
        }finally {db.endTransaction()}
    }

    /** Append Pi's own records and move its selected leaf atomically. Branching,
     * compression and message interpretation remain exclusively upstream Pi's.
     * Repeat delivery is idempotent; existing records can never be rewritten. */
    fun savePiSession(sessionId:String,runId:String,value:JSONObject) {
        require(sessionId.isNotBlank() && runId.isNotBlank())
        val header=value.getJSONObject("header")
        require(header.getString("type")=="session" && header.getString("id").isNotBlank() && header.getInt("version")>0)
        val entries=value.getJSONArray("entries").objects()
        val source=value.optJSONArray("source") ?: JSONArray()
        val sources=(0 until source.length()).map {source.getString(it)}.toSet()
        val capabilities=setOf("screen_perception","web_search","screen_control","adb_debug")
        require(sources.all {it in capabilities})
        val byId=entries.associateBy {it.getString("id")}
        require(byId.size==entries.size)
        val inputRuns=linkedSetOf<String>()
        val inputRunId=if(!value.has("inputRunId") || value.isNull("inputRunId"))null else value.getString("inputRunId").also {require(it.isNotBlank())}
        require(inputRunId==null || entries.any {it.optString("type")=="message" && it.optJSONObject("message")?.optString("role")=="user"}) {"Input commit requires an original Pi user entry"}
        if(inputRunId!=null)inputRuns.add(inputRunId)
        val inputRunIds=if(!value.has("inputRunIds") || value.isNull("inputRunIds"))JSONObject() else value.getJSONObject("inputRunIds")
        for(entryId in inputRunIds.keys()) {
            val entry=byId[entryId]
            require(entry!=null && entry.optString("type")=="message" && entry.optJSONObject("message")?.optString("role")=="user") {"Input commit map must reference a user entry in this batch"}
            inputRuns.add(inputRunIds.getString(entryId).also {require(it.isNotBlank())})
        }
        val sourceMap=if(!value.has("entrySources") || value.isNull("entrySources"))JSONObject() else value.getJSONObject("entrySources")
        val entrySources=mutableMapOf<String,Set<String>>()
        for(entryId in sourceMap.keys()) {
            require(entryId in byId) {"Entry source map must reference this batch"}
            val array=sourceMap.getJSONArray(entryId)
            entrySources[entryId]=(0 until array.length()).map {array.getString(it)}.toSet().also {require(it.all {cap->cap in capabilities})}
        }
        require(value.has("leafId"))
        val leafId=if(value.isNull("leafId"))null else value.getString("leafId").also {require(it.isNotBlank())}
        require(entries.map {it.getString("id")}.toSet().size==entries.size)
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT sessionId FROM runs WHERE id=?",arrayOf(runId)).use {c->require(c.moveToFirst() && c.getString(0)==sessionId) {"Pi Session/Run mismatch"}}
            val existingHeader=db.rawQuery("SELECT header FROM pi_sessions WHERE sessionId=?",arrayOf(sessionId)).use {c->if(c.moveToFirst())JSONObject(c.getString(0)) else null}
            require(existingHeader==null || sameJson(existingHeader,header)) {"Pi Session header cannot change"}
            var sequence=db.rawQuery("SELECT COALESCE(MAX(sequence),0) FROM pi_session_entries WHERE sessionId=?",arrayOf(sessionId)).use {c->check(c.moveToFirst());c.getLong(0)}
            for(entry in entries) {
                val entryId=entry.getString("id");val ownSources=entrySources[entryId] ?: sources;require(entryId.isNotBlank() && entry.getString("type").isNotBlank() && entry.has("parentId"))
                val parent=if(entry.isNull("parentId"))null else entry.getString("parentId").also {require(it.isNotBlank() && it!=entryId)}
                if(parent!=null)db.rawQuery("SELECT 1 FROM pi_session_entries WHERE sessionId=? AND id=?",arrayOf(sessionId,parent)).use {require(it.moveToFirst()) {"Unknown Pi parent entry"}}
                val oldSource=db.rawQuery("SELECT source FROM pi_session_entries WHERE sessionId=? AND id=?",arrayOf(sessionId,entryId)).use {c->if(c.moveToFirst())JSONArray(c.getString(0)) else null}
                if(oldSource!=null) {
                    require(sameJson(JSONObject(readPiEntry(sessionId,entryId)),entry)) {"Pi entries are immutable"}
                    // A retry cannot narrow the provenance recorded earlier.
                    val combined=((0 until oldSource.length()).map {oldSource.getString(it)}+ownSources).toSet()
                    if(combined.size!=oldSource.length())db.update("pi_session_entries",ContentValues().apply {put("source",JSONArray(combined.toList()).toString())},"sessionId=? AND id=?",arrayOf(sessionId,entryId))
                }else {
                    db.insertOrThrow("pi_session_entries",null,ContentValues().apply {
                        put("sessionId",sessionId);put("id",entryId);put("sequence",++sequence);put("payload",entry.toString());put("source",JSONArray(ownSources.toList()).toString());put("runId",runId)
                        if(parent==null)putNull("parentId") else put("parentId",parent)
                    })
                }
            }
            if(leafId!=null)db.rawQuery("SELECT 1 FROM pi_session_entries WHERE sessionId=? AND id=?",arrayOf(sessionId,leafId)).use {require(it.moveToFirst()) {"Unknown Pi leaf entry"}}
            val values=ContentValues().apply {put("sessionId",sessionId);put("header",header.toString());if(leafId==null)putNull("leafId") else put("leafId",leafId)}
            if(existingHeader==null)db.insertOrThrow("pi_sessions",null,values) else {
                // Preserve the original header bytes, including metadata ordering.
                values.remove("header");db.update("pi_sessions",values,"sessionId=?",arrayOf(sessionId))
            }
            for(committedRunId in inputRuns) {
                val inputRun=requireNotNull(readPayload("runs",committedRunId,sessionId)) {"Pi input belongs to another session or missing Run"}
                inputRun.put("piInputCommitted",true)
                check(db.update("runs",ContentValues().apply {put("payload",inputRun.toString())},"id=? AND sessionId=?",arrayOf(committedRunId,sessionId))==1)
            }
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
    }

    /** Recover only a submitted user input that died before its Pi user entry
     * committed. No assistant history, tool events or side effects enter this path. */
    fun loadPendingPiInputs(sessionId:String):JSONArray {
        require(sessionId.isNotBlank())
        val result=JSONArray()
        readableDatabase.rawQuery("SELECT id FROM runs WHERE sessionId=? ORDER BY rowid",arrayOf(sessionId)).use {c->
            while(c.moveToNext())readPendingPiInput(sessionId,c.getString(0))?.let(result::put)
        }
        return result
    }
    private fun readPendingPiInput(sessionId:String,runId:String):JSONObject? {
        // Thor SQLite has no JSON1. Stream selected fields and stop as soon as a
        // status/probe/commit/non-user message excludes the row. Large unrelated
        // assistant content is skipped without materializing a DomainTurn.
        val reader=object:java.io.Reader() {
            var offset=1;var chunk="";var index=0;var ended=false
            override fun read(target:CharArray,start:Int,length:Int):Int {
                if(length==0)return 0
                if(index>=chunk.length) {
                    if(ended)return -1
                    chunk=readableDatabase.rawQuery("SELECT substr(payload,?,65536) FROM runs WHERE id=? AND sessionId=?",arrayOf(offset.toString(),runId,sessionId)).use {c->if(c.moveToFirst())c.getString(0) else ""}
                    offset+=65536;index=0
                    if(chunk.isEmpty()){ended=true;return -1}
                }
                val count=minOf(length,chunk.length-index);chunk.toCharArray(target,start,index,index+count);index+=count;return count
            }
            override fun close(){ended=true;chunk="";index=0}
        }
        return android.util.JsonReader(reader).use {json->
            var eligible=false;var count=0;val content=JSONArray()
            json.beginObject()
            while(json.hasNext())when(json.nextName()) {
                "status" -> {eligible=json.nextString() in setOf("failed","interrupted","cancelled");if(!eligible)return@use null}
                "probe","piInputCommitted" -> if(json.peek()==android.util.JsonToken.BOOLEAN) {if(json.nextBoolean())return@use null} else json.skipValue()
                "turn" -> {
                    if(json.peek()!=android.util.JsonToken.BEGIN_OBJECT){json.skipValue();continue}
                    json.beginObject()
                    while(json.hasNext())if(json.nextName()=="messages") {
                        if(json.peek()!=android.util.JsonToken.BEGIN_ARRAY)return@use null
                        json.beginArray()
                        while(json.hasNext()) {
                            if(json.peek()!=android.util.JsonToken.BEGIN_OBJECT)return@use null
                            var user=false;var items:JSONArray?=null
                            json.beginObject()
                            while(json.hasNext())when(json.nextName()) {
                                "kind" -> {user=json.nextString()=="user";if(!user)return@use null}
                                "content" -> items=readPendingContent(json) ?: return@use null
                                else -> json.skipValue()
                            }
                            json.endObject();if(!user || items==null)return@use null
                            for(i in 0 until items.length())content.put(items.get(i));count++
                        }
                        json.endArray()
                    } else json.skipValue()
                    json.endObject()
                }
                else -> json.skipValue()
            }
            json.endObject()
            if(!eligible || count==0 || content.length()==0)null else obj("runId" to runId,"content" to content,"source" to JSONArray())
        }
    }
    private fun readPendingContent(json:android.util.JsonReader):JSONArray? {
        if(json.peek()!=android.util.JsonToken.BEGIN_ARRAY)return null
        val result=JSONArray();json.beginArray()
        while(json.hasNext()) {
            if(json.peek()!=android.util.JsonToken.BEGIN_OBJECT)return null
            val item=JSONObject();json.beginObject()
            while(json.hasNext()) {
                val name=json.nextName()
                if(name in setOf("type","text","attachmentId")) {
                    if(json.peek()!=android.util.JsonToken.STRING)return null
                    item.put(name,json.nextString())
                }else json.skipValue()
            }
            json.endObject()
            when(item.optString("type")) {
                "text" -> if(!item.has("text"))return null
                "attachment" -> if(item.optString("attachmentId").isBlank())return null
                else -> return null
            }
            result.put(item)
        }
        json.endArray();return result
    }
    private fun readPiEntry(sessionId:String,entryId:String):String {
        val db=readableDatabase
        val args=arrayOf(sessionId,entryId)
        val length=db.rawQuery("SELECT length(payload) FROM pi_session_entries WHERE sessionId=? AND id=?",args).use {c->check(c.moveToFirst());c.getInt(0)}
        val result=StringBuilder(length);var offset=1
        while(offset<=length) {
            db.rawQuery("SELECT substr(payload,?,65536) FROM pi_session_entries WHERE sessionId=? AND id=?",arrayOf(offset.toString(),*args)).use {c->check(c.moveToFirst());result.append(c.getString(0))}
            offset+=65536
        }
        return result.toString()
    }
    private fun sameJson(left:Any?,right:Any?):Boolean = when {
        left is JSONObject && right is JSONObject -> left.keys().asSequence().toSet()==right.keys().asSequence().toSet() && left.keys().asSequence().all {sameJson(left.get(it),right.get(it))}
        left is JSONArray && right is JSONArray -> left.length()==right.length() && (0 until left.length()).all {sameJson(left.get(it),right.get(it))}
        else -> left==right
    }

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
    @Synchronized fun loadChatPage(sessionId:String,runLimit:Int=12,eventLimit:Int=600):JSONObject {
        require(runLimit in 1..4096 && eventLimit in 0..2000)
        val state=sessionShell(sessionId)
        val total=readableDatabase.rawQuery("SELECT COUNT(*) FROM runs WHERE sessionId=?",arrayOf(sessionId)).use {c->check(c.moveToFirst());c.getInt(0)}
        state.put("historyHasMore",total>runLimit).put("historyRunCount",total).put("historyPageLimit",runLimit)
        val runIds="SELECT id FROM runs WHERE sessionId=? ORDER BY rowid DESC LIMIT ?"
        val related="sessionId=? AND (runId IS NULL OR runId IN ($runIds))"
        val args=arrayOf(sessionId,sessionId,runLimit.toString())
        state.put("runs",JSONArray(projectedRows("runs","sessionId=?",arrayOf(sessionId),"ORDER BY rowid DESC LIMIT $runLimit",
            "json_remove(payload,'$.turn','$.contexts','$.runtime')") {row->row.remove("turn");row.remove("contexts");row.remove("runtime");row}.reversed()))
        val visibleRuns=state.getJSONArray("runs").objects()
        usageRoundCache.keys.retainAll(visibleRuns.map {it.getString("id")}.toSet())
        for(run in visibleRuns) {
            val runId=run.getString("id")
            if(run.optString("status") in setOf("completed","failed","cancelled","interrupted")) {
                val usage=usageRoundCache.getOrPut(runId) {
                    val rows=mutableListOf<JSONObject>()
                    // JSON1 is absent on some devices. The stored ledger is
                    // compact JSONObject JSON; filter before allocating bodies.
                    readableDatabase.rawQuery("SELECT payload FROM events WHERE sessionId=? AND runId=? AND payload LIKE ? ORDER BY sequence",
                        arrayOf(sessionId,runId,"%\"type\":\"model_round\"%")).use {cursor->while(cursor.moveToNext()) {
                        val event=JSONObject(cursor.getString(0))
                        if(event.optString("type")=="model_round") {
                            val receipt=JSONObject().put("type","model_round").put("runId",runId)
                            for(key in listOf("sequence","wallTime","elapsedMs"))event.opt(key)?.let {receipt.put(key,it)}
                            val counters=JSONObject();val data=event.optJSONObject("data")
                            for(key in listOf("modelRound","inputTokens","totalInputTokens","cacheReadTokens","cacheWriteTokens","outputTokens"))data?.opt(key)?.let {counters.put(key,it)}
                            rows.add(receipt.put("data",counters))
                        }
                    }}
                    rows
                }
                run.put("usageRounds",JSONArray(usage.map {JSONObject(it.toString())}))
            }else usageRoundCache.remove(runId)
        }
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

    /** Verify a screenshot owner without materializing its transcript or contexts. */
    fun getRunIdentity(runId:String,sessionId:String):JSONObject? {
        require(runId.isNotBlank() && sessionId.isNotBlank())
        val args=arrayOf(runId,sessionId)
        val exists=readableDatabase.rawQuery("SELECT id FROM runs WHERE id=? AND sessionId=?",args).use {it.moveToFirst()}
        if(!exists)return null
        // Thor's Android SQLite omits JSON1. Stream bounded SQL chunks instead
        // of loading a complete (potentially multi-MiB) transcript to read probe.
        val reader=object:java.io.Reader() {
            var offset=1;var chunk="";var index=0;var ended=false
            override fun read(target:CharArray,start:Int,length:Int):Int {
                if(length==0)return 0
                if(index>=chunk.length) {
                    if(ended)return -1
                    chunk=readableDatabase.rawQuery("SELECT substr(payload,?,65536) FROM runs WHERE id=? AND sessionId=?",arrayOf(offset.toString(),*args)).use {c->
                        if(c.moveToFirst())c.getString(0) else ""
                    }
                    offset+=65536;index=0
                    if(chunk.isEmpty()){ended=true;return -1}
                }
                val count=minOf(length,chunk.length-index)
                chunk.toCharArray(target,start,index,index+count);index+=count;return count
            }
            override fun close(){ended=true;chunk="";index=0}
        }
        val probe=android.util.JsonReader(reader).use {json->
            json.beginObject();var value=false
            while(json.hasNext()) {
                if(json.nextName()=="probe") {
                    if(json.peek()==android.util.JsonToken.BOOLEAN)value=json.nextBoolean() else json.skipValue()
                    break
                }
                json.skipValue()
            }
            value
        }
        return obj("id" to runId,"sessionId" to sessionId,"probe" to probe)
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
    @Synchronized fun savePartialSnapshot(state:JSONObject,deletedSessionIds:Set<String> = emptySet()) {
        usageRoundCache.clear()
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

    @Synchronized override fun save(state: JSONObject) {
        usageRoundCache.clear()
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
    @Synchronized fun saveStartupRecovery(state:JSONObject,recoveredRunIds:Set<String>) {
        usageRoundCache.clear()
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
    @Synchronized fun saveRunUpdate(state: JSONObject, run: JSONObject, event: JSONObject?) {
        val rid=run.getString("id");val sid=run.getString("sessionId")
        if(event?.optString("type")=="model_round" || run.optString("status") !in setOf("completed","failed","cancelled","interrupted"))usageRoundCache.remove(rid)
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
