package dev.thor.agent

import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.*
import dev.thor.tools.diagnostic.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.KeyGenerator

/** Read-only native component. Never starts an Activity, opens a game, sends
 * input, configures layouts or calls a model. Existing same-app companion required. */
@RunWith(AndroidJUnit4::class)
class PhysicalCompanionCaptureTest {
    @Test fun currentSameAppCompanionIsReadOnlyAndKeepsUpperInputNamespace() {
        val ins=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        val context=ins.targetContext;assertEquals("AYN Thor",android.os.Build.MODEL)
        val expected=args.getString("companionPackage") ?: error("Explicit expected current upper package required")
        assertTrue(expected.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")))
        // Do not run alongside an existing model task. This reader never writes
        // profile, credentials, session or history records.
        assertTrue(File(context.getDatabasePath("product.db").path).isFile)
        SqliteStore(context,"product.db").use {store->
            val end=SystemClock.elapsedRealtime()+40000
            while(true)try {
                store.readableDatabase.rawQuery("SELECT payload FROM runs",null).use {cursor->while(cursor.moveToNext()) {
                    assertTrue("Existing user run must finish first",org.json.JSONObject(cursor.getString(0)).optString("status") in setOf("completed","failed","cancelled","interrupted"))
                }};break
            }catch(e:android.database.sqlite.SQLiteDatabaseLockedException){if(SystemClock.elapsedRealtime()>=end)throw e;Thread.sleep(100)}
        }
        ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val clock=Clock {SystemClock.elapsedRealtime()}
        val debug=DebugConnection(context) {}
        val temporary=File(context.cacheDir,"companion-component-${java.util.UUID.randomUUID()}").apply {mkdirs()}
        val report=obj("schemaVersion" to 1,"scope" to "manual native component; existing same-app dual-screen scene; no model or game operation",
            "harnessInputSteps" to org.json.JSONArray(),"passed" to false,"cloudCalled" to false)
        try {
            val end=SystemClock.elapsedRealtime()+40000
            while(ThorAccessibilityService.instance==null && SystemClock.elapsedRealtime()<end)Thread.sleep(100)
            assertNotNull("Existing authorized Accessibility service required",ThorAccessibilityService.instance)
            val facts=displayFacts(context);val roles=DisplayRoles(context).resolve(facts) ?: error("Normal saved/automatic display roles unavailable")
            val snapshots=Snapshots(clock) {ThorAccessibilityService.instance!!.target(it)}.apply {update(facts);calibrate(roles.first.id,roles.second.id)}
            val source=snapshots.capture();assertEquals(expected,source.target.packageName)
            val local=LocalFiles(File(temporary,"config").toPath())
            val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey()
            val tx=ConfigTransactions(local,EncryptedVault(File(temporary,"backups").toPath(),key,clock)) {}
            val backend=AndroidBackend(context,clock,snapshots,debug,local,tx)
            val authority=ManualTestAuthority(clock).apply {set(Capability.WEB_SEARCH,false)}
            val lease=authority.begin(backend);val gateway=Gateway(authority,backend,snapshots,clock)
            fun envelope(tool:String,snapshot:String)=Envelope(lease.session,lease.run,java.util.UUID.randomUUID().toString(),tool,lease.epoch,lease.deadline,snapshot)
            val result=gateway.execute(envelope("perception.capture_companion",source.id),mapOf("maxEdge" to 1280,"quality" to 80,"format" to "jpeg"))
            report.put("error",result.error?.name).put("message",result.message).put("backend",result.source)
            assertNull("Native capture failed: ${result.message}",result.error)
            assertEquals(true,result.data["readOnly"]);assertEquals(false,result.data["frameInputUsable"]);assertFalse(result.data.containsKey("frameId"))
            assertEquals(roles.second.id,result.data["displayId"]);assertEquals(roles.first.id,result.data["authorizedInputDisplayId"])
            assertEquals(roles.first,snapshots.validateObservation(result.snapshotId).display)
            val imageIdentity=result.data["companionObservationId"] as String
            try {snapshots.validateReadSource(imageIdentity);fail("Companion image became an input snapshot")}
            catch(e:ToolFailure){assertEquals(Code.TARGET_CHANGED,e.code)}
            val denied=gateway.execute(envelope("input.tap",result.snapshotId!!),mapOf("x" to 1,"y" to 1,"coordinateSpace" to "display_logical"))
            assertEquals(Code.CAPABILITY_DISABLED,denied.error)
            val bytes=File(result.data["attachmentRef"] as String).readBytes();assertTrue(bytes.size in 1..Budgets.IMAGE)
            File(context.filesDir,"physical-companion.jpg").writeBytes(bytes)
            report.put("upperDisplay",roles.first.id).put("companionDisplay",roles.second.id).put("sameApp",expected)
                .put("imageSha256",hash(bytes)).put("imageBytes",bytes.size).put("width",result.data["width"]).put("height",result.data["height"])
                .put("readOnly",true).put("frameIdAbsent",true).put("companionIdentityNotAnInputSnapshot",true).put("inputCapabilityDenied",true).put("passed",true)
            authority.finish(lease.run)
        } finally {
            debug.close();temporary.deleteRecursively()
            File(context.filesDir,"physical-companion.json").writeText(report.toString(2))
        }
    }
}
