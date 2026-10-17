package dev.thor.agent

import android.app.ActivityOptions
import android.app.UiAutomation
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Destructive maintenance, deliberately excluded unless the caller supplies
 * -e deleteAllTestConversations true. Uses the real product deletion path and
 * never clears app data, Provider credentials, settings or Skill files. */
@RunWith(AndroidJUnit4::class)
class CleanupTestConversationsTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun field(host:ProductHost,name:String):Any=requireNotNull(ProductHost::class.java.getDeclaredField(name).apply {isAccessible=true}.get(host))
    private fun rpc(host:ProductHost,connection:String,op:String,data:JSONObject=obj()):JSONObject {
        val value=host.rpc(connection,obj("op" to op,"data" to data)).get(30,TimeUnit.SECONDS)
        assertTrue("Cleanup RPC must succeed",value.isNull("error"));return value
    }
    private fun assertIdle(host:ProductHost,connection:String) {
        val diagnostic=rpc(host,connection,"runtime.diagnostics.native")
        assertEquals("Cleanup must never interrupt an active task",0,diagnostic.getInt("activeBindingCount"))
        assertFalse(diagnostic.getBoolean("storageFailed"))
    }
    private fun protectedFiles():Map<String,String> =buildMap {
        val preferences=File(context.applicationInfo.dataDir,"shared_prefs")
        preferences.listFiles()?.filter {it.isFile && it.extension=="xml"}?.sortedBy {it.name}?.forEach {file->
            put("preferences/${file.name}",SkillStore.digest(file.readBytes()))
        }
        val skills=File(context.filesDir,"skills")
        if(skills.exists())skills.walkTopDown().filter {it.isFile}.forEach {file->put("skills/${file.relativeTo(skills).invariantSeparatorsPath}",SkillStore.digest(file.readBytes()))}
    }
    private fun rowCounts():JSONObject=SqliteStore(context,"product.db").use {store->
        JSONObject().apply {
            for(table in listOf("sessions","runs","messages","events","attachments","pi_sessions","pi_session_entries")) {
                val count=store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use {cursor->check(cursor.moveToFirst());cursor.getLong(0)}
                put(table,count)
            }
        }
    }
    @Test fun deleteAllExplicitlyAuthorizedTestConversationsAndPreserveConfiguration() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit deleteAllTestConversations=true is required",args.getString("deleteAllTestConversations")=="true")
        val reportFile=File(context.filesDir,"cleanup-test-conversations.json")
        reportFile.writeText(obj("schemaVersion" to 1,"explicitlyAuthorized" to true,"passed" to false).toString(2))
        ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay","0").toInt()).toBundle()) as MainActivity
        val ready=EmulatorRuntimeTestSupport.awaitReady(activity)
        val host=ready.host;val connection=field(host,"connection") as String
        assertIdle(host,connection)
        val preservedFiles=protectedFiles();val before=rowCounts()
        val originalIds=rpc(host,connection,"view").getJSONArray("sessions").objects().map {it.getString("id")}
        // A fresh empty selected chat keeps every deletion out of old history
        // projections and lets the production path retain one usable blank chat.
        rpc(host,connection,"ui",obj("op" to "new"))
        for(sid in originalIds) {
            assertIdle(host,connection)
            rpc(host,connection,"ui",obj("op" to "delete","sessionId" to sid))
        }
        assertIdle(host,connection)
        val after=rowCounts()
        assertEquals(1L,after.getLong("sessions"))
        for(table in listOf("runs","messages","events","attachments","pi_sessions","pi_session_entries"))assertEquals("All old conversation rows must cascade",0L,after.getLong(table))
        SqliteStore(context,"product.db").use {store->store.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use {assertEquals(0,it.count)}}
        assertEquals("Provider keys, settings and Skills must be preserved",preservedFiles,protectedFiles())
        // Only derived screen-image cache files are removed after all owners
        // have been deleted; downloaded apps and other user files are untouched.
        val frames=File(context.cacheDir,"tool-frames");var removedFrames=0
        frames.listFiles()?.filter {it.isFile && it.extension=="jpg" && it.canonicalFile.parentFile==frames.canonicalFile}?.forEach {file->
            assertTrue("An obsolete captured frame could not be removed",file.delete());removedFrames++
        }
        reportFile.writeText(obj("schemaVersion" to 1,"explicitlyAuthorized" to true,
            "deletedOriginalSessions" to originalIds.size,"before" to before,"after" to after,"protectedConfigurationPreserved" to true,
            "removedDerivedFrameFiles" to removedFrames,"passed" to true).toString(2))
    }
}
