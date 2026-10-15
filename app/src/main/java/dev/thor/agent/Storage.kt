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
    override fun save(state: JSONObject) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Read each table once inside the transaction. A per-row SELECT here
            // makes every streamed fragment perform thousands of old-history queries.
            val previousRows=tables.associateWith {table ->
                val rows=mutableMapOf<String,String>()
                db.rawQuery("SELECT id,payload FROM $table",null).use {c->while(c.moveToNext())rows[c.getString(0)]=c.getString(1)}
                rows
            }
            // Delete children first, insert parents first; compare committed payloads.
            for (table in tables.asReversed()) {
                val wanted = state.getJSONArray(table).objects().map { it.getString("id") }.toSet()
                previousRows.getValue(table).keys.filter { it !in wanted }.forEach { db.delete(table, "id=?", arrayOf(it));previousRows.getValue(table).remove(it) }
            }
            for (table in tables) for (row in state.getJSONArray(table).objects()) {
                val payload = row.toString(); val id = row.getString("id")
                val previous = previousRows.getValue(table)[id]
                if (previous == payload) continue
                val values = ContentValues().apply {
                    put("id", id); put("payload", payload)
                    if(table != "sessions") put("sessionId", row.getString("sessionId"))
                    if(table != "sessions" && table != "runs") { if(row.isNull("runId")) putNull("runId") else put("runId", row.getString("runId")) }
                    if(table == "events") put("sequence", row.getInt("sequence"))
                }
                if(previous == null) db.insertOrThrow(table, null, values) else db.update(table, values, "id=?", arrayOf(id))
                previousRows.getValue(table)[id]=payload
            }
            val selected = ContentValues().apply { put("name", "selected"); put("value", state.optString("selected")) }
            db.insertWithOnConflict("metadata", null, selected, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
