package dev.thor.tools.diagnostic

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.os.*
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.View
import android.widget.*
import dev.thor.tools.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Paths
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Standalone manual TEST host. No exported tool IPC, Pi, model endpoint or universal command bypass. */
class DiagnosticsActivity : Activity() {
    private val clock = Clock { SystemClock.elapsedRealtime() }
    private val authority = ManualTestAuthority(clock)
    private lateinit var snapshots: Snapshots
    private lateinit var backend: AndroidBackend
    private lateinit var connection: DebugConnection
    private lateinit var gateway: Gateway
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var select: Spinner
    private lateinit var image: ImageView
    private lateinit var executeButton: Button
    private var activeRun: String? = null
    private var pendingSnapshot: String? = null
    private var busy = false
    private var destroyed = false
    private lateinit var displayManager: DisplayManager
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = displayChanged()
        override fun onDisplayRemoved(id: Int) = displayChanged()
        override fun onDisplayChanged(id: Int) = displayChanged()
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        snapshots = Snapshots(clock) { id -> ThorAccessibilityService.instance?.target(id) ?: Target(null, "permission_missing", "no-accessibility") }
        snapshots.update(displayFacts(this))
        connection = DebugConnection(this) { stopRun("Shizuku binder disconnected") }
        val root = File(filesDir, "synthetic-config").apply { mkdirs() }
        File(root, "sample.json").let { if (!it.exists()) it.writeText("{\"volume\":50,\"unknownField\":\"preserved\"}\n") }
        val local = LocalFiles(root.toPath())
        val vault = EncryptedVault(File(filesDir, "encrypted-backups").toPath(), backupKey(), Clock { System.currentTimeMillis() })
        val tx = ConfigTransactions(local, vault) { bytes ->
            val content = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            JSONObject(content) // JSON object only; original content is written verbatim, not reserialized.
        }
        backend = AndroidBackend(this, clock, snapshots, connection, local, tx)
        gateway = Gateway(authority, backend, snapshots, clock)
        ThorAccessibilityService.invalidated = { stopRun("Accessibility disconnected") }
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(listener, Handler(mainLooper))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 20) }
        val scroll = ScrollView(this).apply { addView(body) }; setContentView(scroll)
        fun text(value: String) { body.addView(TextView(this).apply { this.text = value; setPadding(0, 6, 0, 6) }) }
        fun button(label: String, action: () -> Unit): Button = Button(this).apply { this.text = label; setOnClickListener { action() }; body.addView(this) }
        text("Thor ToolPack 0.1 · local TEST authority · no Pi\nAndroid API backend; Thor delivery UNVERIFIED. Confirm displays after every restart/geometry change.")
        text("ADB DEBUG RISK: arbitrary Shell can capture screens, inject input, access files and networks. Other switches cannot sandbox it. Shell is not Root; effects cannot be rolled back by stopping. Descendants may survive cancellation.")
        Capability.entries.forEach { cap -> body.addView(Switch(this).apply {
            this.text = cap.name.lowercase() + if (cap == Capability.WEB_SEARCH) " (unavailable; outside this module)" else ""
            isChecked = authority.enabled(cap)
            isEnabled = cap != Capability.WEB_SEARCH
            setOnCheckedChangeListener { _, value ->
                if (cap == Capability.ADB_DEBUG && value) {
                    isChecked = false
                    AlertDialog.Builder(this@DiagnosticsActivity).setTitle("Enable debug for this manual session?")
                        .setMessage("Arbitrary Shell crosses all capability boundaries. Structured backup protection applies only to synthetic config tools, not to shell writes. Each shell command still requires exact approval.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Enable") { _, _ ->
                            setOnCheckedChangeListener(null); isChecked = true; authority.set(cap, true)
                            setOnCheckedChangeListener { _, next -> authority.set(cap, next); pendingSnapshot = null }
                        }.show()
                } else { authority.set(cap, value); pendingSnapshot = null }
            }
        }) }
        button("Android Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("Request Shizuku grant / bind shell UID UserService") {
            try { connection.requestAndBind(); show("If a permission prompt appeared, grant it, then tap Bind again.\n${connection.availability()}") }
            catch (t: Exception) { show("${t.javaClass.simpleName}: ${Budgets.safeText(t.message.orEmpty())}") }
        }
        button("Calibrate upper / lower displays") { calibrate() }
        button("Observe minimal upper context / renew snapshot") { observe() }
        button("Show tool availability & actual backends") { show(ToolPack.descriptors.keys.joinToString("\n") { "$it: ${backend.availability(it)}" }) }
        button("STOP Run / release owned inputs") { stopRun("Manual stop") }
        select = Spinner(this).apply { adapter = ArrayAdapter(this@DiagnosticsActivity, android.R.layout.simple_spinner_dropdown_item, ToolPack.descriptors.keys.toList()) }; body.addView(select)
        input = EditText(this).apply { hint = "Tool input JSON object"; minLines = 3; setText("{}") }; body.addView(input)
        select.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { input.setText(example(select.selectedItem.toString())) }
        }
        executeButton = button("Execute selected tool (review exact side effects)") { prepare() }
        output = TextView(this).apply { setTextIsSelectable(true); textSize = 12f }; body.addView(output)
        image = ImageView(this).apply { adjustViewBounds = true }; body.addView(image)
        show("No tool executed. Synthetic sample path: ${File(root, "sample.json")}\nRead it to obtain expectedHash; backup before write. ${connection.availability()}")
    }
    private fun show(value: String) { if (!destroyed) runOnUiThread { if (!destroyed) output.text = Budgets.safeText(value, 96 * 1024) } }
    private fun example(tool: String): String = when (tool) {
        "input.tap", "input.long_press" -> "{\"x\":100,\"y\":100,\"coordinateSpace\":\"display_logical\"}"
        "input.swipe" -> "{\"points\":[[100,100],[200,200]],\"durationMs\":500,\"coordinateSpace\":\"display_logical\"}"
        "debug.shell" -> "{\"command\":\"id\",\"timeoutMs\":15000,\"maxBytes\":65536}"
        "files.list" -> "{\"path\":\".\",\"backend\":\"synthetic\"}"
        "files.read" -> "{\"path\":\"sample.json\",\"backend\":\"synthetic\"}"
        "files.backup" -> "{\"path\":\"sample.json\",\"expectedHash\":\"COPY_READ_HASH\"}"
        "files.write" -> "{\"path\":\"sample.json\",\"expectedHash\":\"COPY_READ_HASH\",\"backupId\":\"COPY_BACKUP_ID\",\"content\":\"{\\\"volume\\\":51,\\\"unknownField\\\":\\\"preserved\\\"}\\n\"}"
        "files.restore" -> "{\"backupId\":\"COPY_ORIGINAL_BACKUP_ID\",\"expectedCurrentHash\":\"COPY_WRITTEN_HASH\"}"
        else -> "{}"
    }
    private fun displayChanged() { snapshots.update(displayFacts(this)); pendingSnapshot = null; stopRun("Display changed; reconfirm calibration") }
    private fun calibrate() {
        if (busy) { show("Stop active Run before calibration"); return }
        val facts = displayFacts(this); snapshots.update(facts)
        if (facts.size < 2) { show("DISPLAY_GONE: need two distinct displays; single-display emulator is not Thor validation"); return }
        val labels = facts.map { "id=${it.id} ${it.identity} ${it.width}×${it.height} rotation=${it.rotation}" }.toTypedArray()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val upper = Spinner(this).apply { adapter = ArrayAdapter(this@DiagnosticsActivity, android.R.layout.simple_spinner_dropdown_item, labels) }
        val lower = Spinner(this).apply { adapter = ArrayAdapter(this@DiagnosticsActivity, android.R.layout.simple_spinner_dropdown_item, labels); setSelection(1) }
        layout.addView(TextView(this).apply { text = "Select physical UPPER display" }); layout.addView(upper)
        layout.addView(TextView(this).apply { text = "Select physical LOWER display" }); layout.addView(lower)
        AlertDialog.Builder(this).setTitle("Explicit role calibration").setView(layout).setNegativeButton("Cancel", null).setPositiveButton("Confirm") { _, _ ->
            try { snapshots.calibrate(facts[upper.selectedItemPosition].id, facts[lower.selectedItemPosition].id); pendingSnapshot = null; show("Roles confirmed; observe target before frame-based input.") } catch (t: ToolFailure) { show("${t.code}: ${t.message}") }
        }.show()
    }
    private fun observe() {
        if (!authority.enabled(Capability.SCREEN_PERCEPTION)) { show("{\"deviceClass\":\"AYN Thor (product target; device unverified)\",\"screen_perception\":false}"); return }
        try {
            snapshots.update(displayFacts(this))
            val a = backend.availability("perception.window_info")
            requireTool(a.status == Status.READY, Code.PERMISSION_MISSING, a.reason)
            val s = snapshots.capture(); pendingSnapshot = s.id
            val label = s.target.packageName?.let { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString().take(128) }.getOrNull() }
            val l0 = json(mapOf("deviceClass" to "AYN Thor", "deviceVerified" to false, "targetDisplay" to "upper", "label" to label, "package" to s.target.packageName,
                "observedAt" to java.time.Instant.now().toString(), "validity" to s.target.validity,
                "capabilities" to Capability.entries.associate { cap ->
                    val ready = ToolPack.descriptors.values.any { it.capability == cap && backend.availability(it.toolId).status == Status.READY }
                    cap.name.lowercase() to if (!authority.enabled(cap)) "disabled" else if (ready) "at_least_one_tool_ready" else "backend_missing"
                })) as JSONObject
            requireTool(l0.toString().toByteArray().size <= 1024, Code.LIMIT_EXCEEDED, "L0 exceeds 1KiB")
            show("Minimal L0 (no screenshot/version/UI tree)\n${l0.toString(2)}\nNative-only snapshotId=${s.id}, TTL=${Snapshots.OBSERVATION_TTL_MS/1000}s")
        } catch (t: ToolFailure) { show("${t.code}: ${t.message}") }
    }
    private fun prepare() {
        if (busy) return
        try {
            val tool = select.selectedItem.toString()
            val raw = input.text.toString(); requireTool(raw.toByteArray().size <= Budgets.CONFIG, Code.LIMIT_EXCEEDED, "Input exceeds 1MiB")
            val args = JSONObject(raw).args()
            val available = backend.availability(tool)
            requireTool(available.status == Status.READY, when(available.status) { Status.UNSUPPORTED -> Code.UNSUPPORTED; Status.PERMISSION_MISSING -> Code.PERMISSION_MISSING; else -> Code.BACKEND_UNAVAILABLE }, available.reason)
            val cap = ToolPack.descriptors.getValue(tool).capability
            requireTool(authority.enabled(cap), Code.CAPABILITY_DISABLED, "Enable $cap in this manual test session")
            val review = when (tool) {
                "debug.thor_brightness" -> ThorBrightnessCommand.parse(args).let {if(it.needsApproval)it.summary else null}
                "debug.shell" -> {
                    val command = args.string("command")
                    val policy=ShellCommandPolicy.classify(command)
                    requireTool(policy.tier!=ShellCommandPolicy.Tier.BLACK,Code.COMMAND_BLOCKED,policy.reason)
                    if(policy.tier==ShellCommandPolicy.Tier.GRAY)"Gray-list command:\n$command\n\n${policy.reason}" else null
                }
                "files.write" -> {
                    val p = backend.files.path(args.string("path")).toString()
                    val old = backend.files.read(p); val new = args.string("content").toByteArray()
                    requireTool(hash(old) == args.string("expectedHash"), Code.HASH_CONFLICT, "Read hash changed before approval")
                    backend.approval = Approval(p, hash(old), hash(new))
                    "Exact synthetic configuration replacement:\n$p\nold=${hash(old)}\nnew=${hash(new)}\n\nBEFORE:\n${old.toString(Charsets.UTF_8)}\nAFTER:\n${new.toString(Charsets.UTF_8)}"
                }
                "files.restore" -> {
                    // Vault metadata is private; this preview resolves only the requested validated backup ID.
                    val desired = previewBackup(args.string("backupId"))
                    val old = backend.files.read(desired.path)
                    requireTool(hash(old) == args.string("expectedCurrentHash"), Code.HASH_CONFLICT, "Restore hash changed before approval")
                    backend.approval = Approval(desired.path, hash(old), desired.oldHash)
                    "Exact restore (current version will be backed up first):\n${desired.path}\nold=${hash(old)}\nnew=${desired.oldHash}\n\nBEFORE:\n${old.toString(Charsets.UTF_8)}\nAFTER:\n${desired.bytes.toString(Charsets.UTF_8)}"
                }
                else -> null
            }
            if (review != null) {
                requireTool(review.toByteArray().size <= Budgets.TEXT, Code.LIMIT_EXCEEDED, "Review exceeds 64KiB manual display budget; reduce config size before approval")
                AlertDialog.Builder(this).setTitle("Approve exact operation").setMessage(Budgets.safeText(review, Budgets.CONFIG))
                    .setNegativeButton("Cancel") { _, _ -> backend.approval = null; backend.shellApproval = null }
                    .setOnCancelListener { backend.approval = null; backend.shellApproval = null }
                    .setPositiveButton("Approve & execute") { _, _ ->
                        if (tool == "debug.shell") backend.shellApproval = hash(args.string("command").toByteArray())
                        execute(tool, args, raw)
                    }.show()
            } else execute(tool, args, raw)
        } catch (t: ToolFailure) { show("${t.code}: ${t.message}") }
          catch (t: Exception) { show("INVALID_ARGUMENT: ${t.javaClass.simpleName}") }
    }
    private fun execute(tool: String, args: Map<String, Any?>, raw: String) {
        busy = true; executeButton.isEnabled = false; image.setImageDrawable(null)
        worker.submit {
            var lease: ManualTestAuthority.Lease? = null
            try {
                snapshots.update(displayFacts(this))
                lease = authority.begin(backend); activeRun = lease.run
                val descriptor = ToolPack.descriptors.getValue(tool)
                val snapshot = if (descriptor.targetRequired) {
                    if (args["coordinateSpace"] == "frame_pixels") pendingSnapshot ?: fail(Code.TARGET_CHANGED, "Capture a frame first")
                    else snapshots.capture().id.also { pendingSnapshot = it }
                } else null
                val envelope = Envelope(lease.session, lease.run, java.util.UUID.randomUUID().toString(), tool, lease.epoch,
                    minOf(lease.deadline, clock.now() + 60_000), snapshot)
                val result = gateway.execute(envelope, args)
                if(tool=="perception.capture_screen" && result.error==null)pendingSnapshot=result.snapshotId
                show("TEST authority / $tool\nINPUT:\n${Budgets.safeText(raw)}\nRESULT:\n${result.toJson()}")
                if (result.error == null) (result.data["attachmentRef"] as? String)?.let { path -> runOnUiThread { if (!destroyed) image.setImageBitmap(BitmapFactory.decodeFile(path)) } }
            } catch (t: ToolFailure) { show("${t.code}: ${t.message}") }
              catch (t: Exception) { show("IO_ERROR: ${t.javaClass.simpleName}") }
            finally {
                lease?.let { authority.finish(it.run); backend.release(it.run) }; activeRun = null
                backend.approval = null; backend.shellApproval = null
                runOnUiThread { busy = false; if (!destroyed) executeButton.isEnabled = true }
            }
        }
    }
    private fun stopRun(reason: String) {
        authority.stop()
        val release = if (::backend.isInitialized) activeRun?.let { backend.release(it) } ?: "No owned inputs" else "No backend"
        if (::output.isInitialized) show("Stopped: $reason\n$release\nSubmitted gesture/Shell effects may already have happened.")
    }
    private fun backupKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("thor-toolpack-backups", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("thor-toolpack-backups", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun previewBackup(id: String) = EncryptedVault(Paths.get(filesDir.absolutePath, "encrypted-backups"), backupKey(), clock).load(id)
    override fun onStop() { stopRun("Host no longer interactive"); super.onStop() }
    override fun onDestroy() {
        destroyed = true; authority.stop(); connection.close(); worker.shutdownNow(); displayManager.unregisterDisplayListener(listener)
        ThorAccessibilityService.invalidated = null; super.onDestroy()
    }
}
