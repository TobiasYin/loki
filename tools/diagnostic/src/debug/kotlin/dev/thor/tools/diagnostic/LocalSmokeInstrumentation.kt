package dev.thor.tools.diagnostic

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import dev.thor.tools.*
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKey

/** Debug-only on-device smoke runner, no injection permissions and no Input Lab dependencies. */
class LocalSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = Bundle(); var passed = 0
        try {
            val activity = startActivitySync(Intent(targetContext, DiagnosticsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            runOnMainSync {
                fun switches(view: View): List<Switch> = when (view) {
                    is Switch -> listOf(view)
                    is ViewGroup -> (0 until view.childCount).flatMap { switches(view.getChildAt(it)) }
                    else -> emptyList()
                }
                val toggles = switches(activity.window.decorView)
                check(toggles.size == 4)
                check(toggles.first { it.text.startsWith("screen_control") }.isChecked.not())
                check(toggles.first { it.text.startsWith("adb_debug") }.isChecked.not())
                check(toggles.first { it.text.startsWith("screen_perception") }.isChecked)
            }
            val schemas = InputSchemas(targetContext)
            try { schemas.validate("perception.capture_screen", mapOf("region" to "invalid")); error("Malformed crop must be rejected") }
            catch (failure: ToolFailure) { check(failure.code == Code.INVALID_ARGUMENT) }
            passed++ // Activity defaults and malformed input refusal.
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("thor-toolpack-backups", null) as SecretKey
            val clock = Clock { SystemClock.elapsedRealtime() }
            val root = File(targetContext.filesDir, "smoke-config").toPath()
            val files = LocalFiles(root); val file = files.root.resolve("sample.json")
            val old = "{\"volume\":50,\"unknown\":\"preserved\"}\r\n".toByteArray()
            val next = "{\"volume\":51,\"unknown\":\"preserved\"}\r\n".toByteArray()
            java.nio.file.Files.write(file, old)
            val vault = EncryptedVault(File(targetContext.filesDir, "smoke-backups-${SystemClock.elapsedRealtime()}").toPath(), key, clock)
            val tx = ConfigTransactions(files, vault) { JSONObject(it.toString(Charsets.UTF_8)) }
            val cp = Checkpoint({}, Cancellation())
            val original = tx.backup(file.toString(), hash(old), cp)
            check(vault.load(original.id).bytes.contentEquals(old)); passed++
            val write = tx.write(file.toString(), hash(old), next, original.id, Approval(file.toString(), hash(old), hash(next)), cp)
            check(write.atomic && write.verified && files.read(file.toString()).contentEquals(next)); passed++
            val restore = tx.restore(original.id, hash(next), Approval(file.toString(), hash(next), hash(old)), cp)
            check(files.read(file.toString()).contentEquals(old) && vault.load(restore.backupId).bytes.contentEquals(next)); passed++
            val snapshots = Snapshots(clock) { Target(null, "unknown", "smoke") }
            snapshots.update(displayFacts(targetContext))
            val connection = DebugConnection(targetContext) {}
            try {
                val backend = AndroidBackend(targetContext, clock, snapshots, connection, files, tx)
                check(backend.availability("input.set_axes").status == Status.UNSUPPORTED)
                check(backend.availability("input.press_button").status == Status.UNSUPPORTED)
                check(backend.availability("input.tap").status == Status.PERMISSION_MISSING)
                passed++
                val authority = ManualTestAuthority(clock)
                val l = authority.begin(backend)
                val e = Envelope(l.session, l.run, "smoke-device", "perception.device_info", l.epoch, l.deadline)
                val result = Gateway(authority, backend, snapshots, clock).execute(e, emptyMap())
                check(result.error == null && result.data.containsKey("system")); passed++
                val disabled = Gateway(authority, backend, snapshots, clock).execute(e.copy(toolId = "debug.shell", toolCallId = "blocked"), mapOf("command" to "id"))
                check(disabled.error == Code.CAPABILITY_DISABLED); passed++
                authority.finish(l.run)
            } finally { connection.close() }
            runOnMainSync { activity.finish() }
            report.putInt("passed", passed)
            report.putString("stream", "\nPASS: $passed Android smoke checks (UI defaults, Keystore backup, atomic write/readback, restore recovery point, availability, SDK device_info, disabled Shell). No Thor/Shizuku/input delivery claim.\n")
            finish(Activity.RESULT_OK, report)
        } catch (t: Throwable) {
            report.putInt("passed", passed)
            report.putString("stream", "\nFAIL after $passed checks: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(4000)}\n")
            finish(Activity.RESULT_CANCELED, report)
        }
    }
}
