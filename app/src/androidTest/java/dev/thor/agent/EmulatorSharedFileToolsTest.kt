package dev.thor.agent

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.*
import dev.thor.tools.diagnostic.AndroidBackend
import dev.thor.tools.diagnostic.DebugConnection
import dev.thor.tools.diagnostic.InputSchemas
import dev.thor.tools.diagnostic.json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.KeyGenerator

/** Real shell-UID UserService on a disposable emulator; no product DB, model,
 * user credentials, game operations or physical-device access. */
@RunWith(AndroidJUnit4::class)
class EmulatorSharedFileToolsTest {
    @Test fun actualPublishedToolsFindStatReadAndTailLogsWithoutAProvisionedDiagnosticRoot() {
        org.junit.Assume.assumeTrue("Emulator only",Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        val clock=Clock {SystemClock.elapsedRealtime()}
        val debug=DebugConnection(context) {}
        val fixture=File(context.getExternalFilesDir(null),"shared-read-${id()}").apply {mkdirs()}
        val temporary=File(context.cacheDir,"shared-read-${id()}").apply {mkdirs()}
        try {
            val end=SystemClock.elapsedRealtime()+20000
            while(debug.availability().status!=Status.READY && SystemClock.elapsedRealtime()<end) {runCatching {debug.requestAndBind()};Thread.sleep(50)}
            assertEquals("Authorized emulator UserService required: ${debug.availability()}",Status.READY,debug.availability().status)
            val schemas=InputSchemas(context)
            schemas.validate("files.read",mapOf("path" to "/sdcard/test","tail" to true))
            try {schemas.validate("files.read",mapOf("path" to "/sdcard/test","tail" to "true"));fail("String boolean accepted")}catch(e:ToolFailure){assertEquals(Code.INVALID_ARGUMENT,e.code)}
            val local=LocalFiles(File(temporary,"config").toPath())
            File(local.root.toFile(),"settings.json").writeText("{\"testOnly\":true}")
            val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey()
            val tx=ConfigTransactions(local,EncryptedVault(File(temporary,"backups").toPath(),key,clock)) {}
            val snapshots=Snapshots(clock) {fail(Code.UNSUPPORTED,"No screen requested by file tools")}
            val backend=AndroidBackend(context,clock,snapshots,debug,local,tx)
            val authority=ManualTestAuthority(clock).apply {set(Capability.SCREEN_PERCEPTION,false);set(Capability.WEB_SEARCH,false);set(Capability.ADB_DEBUG,true)}
            val lease=authority.begin(backend)
            val gateway=Gateway(authority,backend,snapshots,clock)
            fun call(tool:String,args:Map<String,Any?>):ToolResult {
                schemas.validate(tool,args)
                return gateway.execute(Envelope(lease.session,lease.run,id(),tool,lease.epoch,lease.deadline),args)
            }
            val logs=File(fixture,"ErrorLogs").apply {mkdirs()};val log=File(logs,"SMAPI-latest.txt")
            val content="[INFO] SMAPI fixture\n"+"中文😀 progress\n".repeat(12000)+"[ERROR] FIXTURE_MOD_FAILURE\n"
            log.writeText(content);val before=hash(log.readBytes())
            repeat(5){File(logs,"older-$it.log").writeText("older fixture $it")}
            val alias=fixture.absolutePath.replace("/storage/emulated/0","/sdcard")
            val found=call("files.find",mapOf("path" to alias,"name" to "smapi","maxDepth" to 3))
            assertNull(found.message,found.error)
            assertTrue(found.data.toString().contains("SMAPI-latest.txt"))
            val path="$alias/ErrorLogs/SMAPI-latest.txt"
            val metadata=call("files.stat",mapOf("path" to path));assertNull(metadata.message,metadata.error);assertEquals(log.length(),(metadata.data["size"] as Number).toLong())
            val tail=call("files.read",mapOf("path" to path,"tail" to true,"maxBytes" to 4096))
            assertNull(tail.message,tail.error);assertEquals(2000,(tail.data["uid"] as Number).toInt())
            assertTrue((tail.data["content"] as String).endsWith("[ERROR] FIXTURE_MOD_FAILURE\n"));assertEquals("utf-8",tail.data["encoding"].toString().lowercase())
            assertTrue((tail.data["offset"] as Number).toLong()>65536);assertTrue(tail.truncated)
            val second=call("files.read",mapOf("path" to path,"offset" to 70000,"maxBytes" to 1024))
            assertNull(second.message,second.error);assertEquals("utf-8",second.data["encoding"].toString().lowercase());assertNotNull(second.data["nextOffset"])
            val page1=call("files.list",mapOf("path" to "$alias/ErrorLogs","limit" to 2))
            assertNull(page1.message,page1.error);assertEquals(2,(page1.data["entries"] as List<*>).size)
            val page2=call("files.list",mapOf("path" to "$alias/ErrorLogs","limit" to 2,"offset" to (page1.data["nextOffset"] as Number).toInt()))
            assertNull(page2.message,page2.error);assertNotEquals(page1.data["entries"],page2.data["entries"])
            val roots=call("files.list",mapOf("path" to "/storage"));assertNull(roots.message,roots.error);assertTrue(roots.data.toString().contains("/storage/emulated/0"))
            assertEquals(Code.IO_ERROR,call("files.stat",mapOf("path" to "$alias/missing.log")).error)
            assertEquals(Code.PERMISSION_MISSING,call("files.read",mapOf("path" to "/data/local/tmp/unrelated","backend" to "shizuku")).error)
            assertEquals(Code.INVALID_ARGUMENT,call("files.read",mapOf("path" to path,"tail" to true,"offset" to 1)).error)
            val legacy=call("files.read",mapOf("path" to "settings.json"));assertNull(legacy.message,legacy.error);assertEquals("{\"testOnly\":true}",legacy.data["content"])
            val marker="SHARED_LOG_FIXTURE_${id()}";Log.i("ThorSharedFileTest",marker);Thread.sleep(100)
            val appLogs=call("debug.app_logs",mapOf("packageName" to context.packageName,"maxLines" to 2000))
            assertNull(appLogs.message,appLogs.error);assertEquals(true,appLogs.data["processRunning"])
            assertTrue("Logcat must include this test's own marker",(appLogs.data["content"] as String).contains(marker))
            val absent=call("debug.app_logs",mapOf("packageName" to "dev.thor.nonexistent.fixture"));assertNull(absent.message,absent.error)
            assertEquals("PROCESS_NOT_RUNNING",absent.data["reason"])
            val escaped=File(logs,"escaped.txt").apply {writeText("/\u2000\u2028\u0080\"\n".repeat(12000))}
            val budgeted=call("files.read",mapOf("path" to escaped.absolutePath,"tail" to true,"maxBytes" to 65536))
            assertNull(budgeted.message,budgeted.error)
            assertTrue("Android JSON escaping must stay under the runtime's text budget",(json(budgeted.data) as org.json.JSONObject).toString().toByteArray().size<65536)
            assertEquals(before,hash(log.readBytes()))
            authority.finish(lease.run)
        }finally {debug.close();fixture.deleteRecursively();temporary.deleteRecursively()}
    }
}
