package dev.thor.inputlab

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.widget.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LabActivity : Activity(), DisplayManager.DisplayListener, InputManager.InputDeviceListener {
    companion object { @Volatile var runnerActivity: java.lang.ref.WeakReference<LabActivity>? = null }
    private var runnerTestRunId: String? = null
    private var runnerAgentRunId: String? = null
    private var runnerTrace: String? = null
    fun runnerPrepare(case: String, testRun: String, agentRun: String, trace: String, observationSeconds: Int = 5, evidenceMode: String = RunnerEvidenceMode.DEFAULT) {
        check(BuildConfig.DEBUG && active?.active() != true && !finishing)
        require(case in caseNames && testRun.length in 1..100 && agentRun.length in 1..100 && trace.length in 1..512)
        val runnerWindow=RunnerWindow.parse(observationSeconds)
        val selectedEvidenceMode=RunnerEvidenceMode.parse(evidenceMode)
        runnerTestRunId = testRun; runnerAgentRunId = agentRun; runnerTrace = trace
        cases.setSelection(caseNames.indexOf(case)); modes.setSelection((0 until modes.count).single { modes.getItemAtPosition(it)==selectedEvidenceMode })
        duration.setText(runnerWindow.observationSeconds.toString()); role.isChecked = true
        scheduleArm()
    }
    fun runnerDescription(): String {
        check(BuildConfig.DEBUG)
        return Json.encode(mapOf("geometry" to windowSnapshot(),"target" to arena.targetWindow().map(),"endTarget" to arena.endWindow().map()))
    }
    fun runnerSnapshot(): String {
        check(BuildConfig.DEBUG)
        return Json.encode(mapOf("attemptId" to attemptId, "testRunId" to testRunId, "phase" to active?.phase?.name,
            "geometry" to windowSnapshot(), "target" to arena.targetWindow().map(), "endTarget" to arena.endWindow().map(), "report" to report, "receivedUptimeMs" to SystemClock.uptimeMillis(), "receivedElapsedRealtimeMs" to SystemClock.elapsedRealtime()))
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var header: TextView
    private lateinit var output: TextView
    private lateinit var controls: LinearLayout
    private lateinit var arena: Arena
    private lateinit var cases: Spinner
    private lateinit var modes: Spinner
    private lateinit var role: CheckBox
    private lateinit var duration: EditText
    private lateinit var semantic: Button
    private lateinit var dm: DisplayManager
    private lateinit var im: InputManager
    private val receiverEpoch = UUID.randomUUID().toString()
    private var layoutEpoch = 0L
    private var deviceRevision = 0L
    private var sequence = 0L
    private var active: Attempt? = null
    private var journal: Journal? = null
    private var attemptDir: File? = null
    private var testRunId = ""
    private var attemptId = ""
    private var mode = "physical_baseline"
    private var initialEnvironment: Map<String,Any?> = emptyMap()
    private var initialSceneState: Map<String,Any?> = emptyMap()
    private var frozenDevices: Map<Int, Map<String,Any?>> = emptyMap()
    private var frozenRanges: Map<Int, List<Range>> = emptyMap()
    private var deviceContinuity: DeviceContinuity? = null
    private var axisProfile = AxisProfile.standard
    private var lifecycleState="created"
    private var report: Map<String,Any?>? = null
    private var finishing = false
    private var currentDispatchSequence: Long? = null
    private var lastInput = 0L
    private var physicalTouch = false
    private var physicalTouchDevice: Int? = null
    private val physicalKeys = mutableSetOf<Pair<Int,Int>>()
    private val observedAxes = mutableMapOf<Pair<Int,Int>,Float>()
    private var selectedExport: File? = null
    private var lastGeometry = ""
    private var lastWindowInsets: Map<String,Any?> = emptyMap()
    private var faultProbe = "none"
    private var lastUiRawRefresh = 0L
    private var armedInstructions = ""
    private fun dp(v: Int)=(v*resources.displayMetrics.density).toInt()
    private val caseNames = listOf("LAB-01","LAB-02","LAB-03","LAB-04-LONG","LAB-04-SWIPE","LAB-04-CANCEL","LAB-05","LAB-06","LAB-07","LAB-08","LAB-08-AXIS","LAB-09","LAB-11","LAB-13","LAB-14")

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(BuildConfig.DEBUG) runnerActivity = java.lang.ref.WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        dm = getSystemService(DisplayManager::class.java)
        im = getSystemService(InputManager::class.java)
        recoverInterrupted()
        val compact=resources.configuration.screenHeightDp<500
        val rowHeight=dp(if(compact) 32 else 48)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(12,8,12,8); setBackgroundColor(Color.rgb(242,245,249)) }
        header = TextView(this).apply { textSize=13f; setTextColor(Color.rgb(22,47,69)) }
        root.addView(header,LinearLayout.LayoutParams(-1,dp(if(compact) 48 else 76)))
        controls = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val selectRow = LinearLayout(this)
        cases = Spinner(this).apply { adapter=ArrayAdapter(this@LabActivity,android.R.layout.simple_spinner_dropdown_item,caseNames); contentDescription="Case selector" }
        modes = Spinner(this).apply { adapter=ArrayAdapter(this@LabActivity,android.R.layout.simple_spinner_dropdown_item,listOf("physical_baseline","manual_platform_api","manual_product_gateway","agent_e2e")); contentDescription="Evidence mode selector" }
        selectRow.addView(cases,LinearLayout.LayoutParams(0,rowHeight,1f)); selectRow.addView(modes,LinearLayout.LayoutParams(0,rowHeight,1f)); controls.addView(selectRow)
        val setup = LinearLayout(this)
        role = CheckBox(this).apply { text="I confirm this window is upper"; textSize=12f; gravity=Gravity.CENTER_VERTICAL }
        duration = EditText(this).apply { setText("5"); inputType=android.text.InputType.TYPE_CLASS_NUMBER; contentDescription="Observation seconds (5 to 60)" }
        setup.addView(role,LinearLayout.LayoutParams(0,rowHeight,1f)); setup.addView(duration,LinearLayout.LayoutParams(dp(70),rowHeight)); controls.addView(setup)
        val actions = LinearLayout(this)
        fun button(text: String, action: () -> Unit) = Button(this).apply { this.text=text; textSize=12f; setOnClickListener { action() } }
        actions.addView(button("Arm") { scheduleArm() },LinearLayout.LayoutParams(0,rowHeight,1f))
        actions.addView(button("Environment") { showEnvironment() },LinearLayout.LayoutParams(0,rowHeight,1f))
        actions.addView(button("Export ZIP") { chooseExport() },LinearLayout.LayoutParams(0,rowHeight,1f))
        actions.addView(button("Fault probe") { chooseFault() },LinearLayout.LayoutParams(0,rowHeight,1f))
        controls.addView(actions); root.addView(controls)
        arena = Arena()
        root.addView(arena,LinearLayout.LayoutParams(-1,0,1f))
        semantic = Button(this).apply { text="Native semantic target (LAB-05)"; contentDescription="Native semantic target"; setOnClickListener {
            val before=active?.state(); active?.semantic()
            emit("scene_change",mapOf("before" to before,"after" to active?.state(),"triggerRawSequence" to currentDispatchSequence,"semanticOrigin" to if(currentDispatchSequence==null) "semantic_only" else "view_click_callback"),"semantic_callback")
            refresh()
        } }
        root.addView(semantic,LinearLayout.LayoutParams(-1,dp(if(compact) 32 else 48)))
        output = TextView(this).apply { textSize=12f; maxLines=6; text="Choose case, release all inputs, Arm. Automatic finish after window + 300 ms silence.\nNo input injection or external control interface. Qualification: Thor physical baseline pending." }
        root.addView(output,LinearLayout.LayoutParams(-1,dp(if(compact) 48 else 96)))
        root.setOnApplyWindowInsetsListener { v,insets ->
            val currentInsets=windowInsetsSnapshot(insets)
            if(lastWindowInsets.isNotEmpty() && currentInsets != lastWindowInsets) {
                active?.takeIf { it.active() }?.let { a ->
                    a.fault("window_insets_changed")
                    emit("window_insets_changed",mapOf("before" to lastWindowInsets,"after" to currentInsets))
                }
            }
            lastWindowInsets=currentInsets
            val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(dp(8)+bars.left,dp(4)+bars.top,dp(8)+bars.right,dp(4)+bars.bottom)
            insets
        }
        setContentView(root)
        arena.viewTreeObserver.addOnGlobalLayoutListener {
            val geometry=geometry()
            if(lastGeometry.isNotEmpty() && geometry != lastGeometry) {
                layoutEpoch++; active?.takeIf { it.active() }?.fault("layout_changed")
                emit("layout_changed",windowSnapshot())
            }
            lastGeometry=geometry; refresh()
        }
        dm.registerDisplayListener(this,handler); im.registerInputDeviceListener(this,handler)
        refresh()
    }
    private fun rootDir() = File(filesDir,"reports").apply { mkdirs() }
    private fun recoverInterrupted() {
        rootDir().listFiles()?.filter { it.isDirectory && File(it,"active.json").exists() }?.forEach { dir ->
            try {
                val previous=File(dir,"active.json").readText()
                atomicFile(File(dir,"interrupted-metadata.json"),previous)
                val previousJson=org.json.JSONObject(previous)
                val previousHeader=previousJson.getJSONObject("metadata")
                val restartKind=if(previousJson.optJSONObject("environment")?.optInt("pid") == android.os.Process.myPid()) "activity_recreated" else "process_restart"
                val interruptedHeader=mapOf("testRunId" to previousHeader.getString("testRunId"),"caseId" to previousHeader.getString("caseId"),"attemptId" to previousHeader.getString("attemptId"),"receiverEpoch" to previousHeader.getString("receiverEpoch"),"scenarioVersion" to previousHeader.optInt("scenarioVersion",1),"phase" to "INTERRUPTED")
                File(dir,"recovery.jsonl").appendText(Json.encode(mapOf("schemaVersion" to 1,"metadata" to interruptedHeader,"sequence" to 1,"eventKind" to restartKind,"receiverEpoch" to receiverEpoch,"recordOrigin" to "receiver_diagnostic","previousAttemptMetadataRef" to "interrupted-metadata.json","receivedUptimeMs" to SystemClock.uptimeMillis()))+"\n")
                val recovered=mapOf("reportSchemaVersion" to 1,"status" to "inconclusive","reasons" to listOf("receiver_interrupted",restartKind,"tail_may_be_missing"),"receiverEpoch" to receiverEpoch,"metadata" to interruptedHeader,"previousAttemptMetadataRef" to "interrupted-metadata.json","eventLogSha256" to File(dir,"events.jsonl").takeIf { it.exists() }?.let { sha256(it) },"receiverQualificationVersion" to "unqualified-physical-baseline-pending")
                atomicFile(File(dir,"report.json"),Json.encode(recovered))
                atomicFile(File(dir,"summary.md"),"# Thor Input Lab interrupted attempt\n\nStatus: inconclusive. Process restart; tail may be missing.\n\nPrevious metadata: $previous\n")
                File(dir,"active.json").delete()
            } catch(e: Exception) { /* Leave marker: next launch retries; budget refuses replacement. */ }
        }
    }
    private fun scheduleArm() {
        if(active?.active()==true || finishing) return
        if(arena.width==0 || arena.height==0) { output.text="Layout unavailable"; return }
        output.text="Arming in 500 ms after release; case and thresholds freeze now."
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken,0)
        arena.requestFocus()
        val caseId=caseNames[cases.selectedItemPosition]
        val seconds=duration.text.toString().toLongOrNull()?.coerceIn(5,120) ?: 5
        val confirmed=role.isChecked
        val selectedMode=modes.selectedItem.toString()
        controls.visibility=View.INVISIBLE // Keeps geometry fixed. Re-arm cannot race.
        handler.postDelayed({
            if(physicalTouch || physicalKeys.isNotEmpty() || !axesNeutral()) { controls.visibility=View.VISIBLE; output.text="Blocked: release not observed. Last input remains unknown; do not synthesize UP/neutral."; return@postDelayed }
            if(windowInsetsSnapshot(window.decorView.rootWindowInsets)["fullscreenReady"] != true) {
                controls.visibility=View.VISIBLE; output.text="Arm blocked: wait for fullscreen system bars and keyboard to hide, then Arm again."; return@postDelayed
            }
            try {
                if(rootDir().listFiles().orEmpty().count { it.isDirectory }>=50 || rootDir().walkTopDown().filter { it.isFile }.sumOf { it.length() } >= 200L*1024*1024) error("Report retention budget full: export and explicitly delete private reports via device settings/adb before new attempt")
                journal?.let { previous -> Thread { previous.close() }.start() }
                testRunId=runnerTestRunId ?: UUID.randomUUID().toString(); attemptId=UUID.randomUUID().toString(); sequence=0; report=null
                attemptDir=File(rootDir(),attemptId)
                journal=Journal(attemptDir!!,maxSamples=if(faultProbe=="budget") 4 else 100_000)
                mode=selectedMode
                val inputDevices=InputDevice.getDeviceIds().associateWith { InputDevice.getDevice(it) }
                frozenDevices=inputDevices.mapValues { Capture.device(it.value) }
                frozenRanges=inputDevices.mapValues { Capture.ranges(it.value) }
                val frozenWindow=windowSnapshot()
                val frozenDisplays=displaySnapshots()
                deviceContinuity=DeviceContinuity(frozenDevices,frozenWindow,frozenDisplays)
                axisProfile=AxisProfile.select(android.os.Build.MANUFACTURER,android.os.Build.MODEL,frozenDevices,frozenRanges)
                initialEnvironment=environment()+mapOf("window" to frozenWindow,"displays" to frozenDisplays,"inputDevices" to frozenDevices.values.toList())
                active=Attempt(caseId,arena.targetWindow(),arena.endWindow(),SystemClock.uptimeMillis(),seconds*1000,requiredAxes=axisProfile.axes,
                    roleConfirmed=confirmed,touchSlopPx=dp(12).toFloat(),requiredAxisDeviceId=axisProfile.deviceId)
                frozenRanges.forEach { (id,ranges) -> active?.ranges(id,ranges) }
                atomicFile(File(attemptDir,"active.json"),Json.encode(mapOf("metadata" to metadata(),"environment" to initialEnvironment,"expectations" to expectations())))
                active!!.arm(); initialSceneState=active!!.state(); lastInput=SystemClock.uptimeMillis(); armedInstructions=instructions(caseId)
                emit("armed",mapOf("metadata" to metadata(),"environment" to initialEnvironment,"expectations" to expectations()))
                if(faultProbe=="layout") { layoutEpoch++; active!!.fault("layout_fault_probe"); emit("fault_probe",mapOf("probe" to "layout")) }
                if(faultProbe=="restart") { output.text="Restart fault armed. Kill process now (adb shell am force-stop dev.thor.inputlab), reopen and export interrupted report." } else output.text=instructions(caseId)
                checkFinish(); refresh()
            } catch(e: Exception) { controls.visibility=View.VISIBLE; output.text="Arm blocked: ${e.message}" }
        },500)
    }
    private fun checkFinish() {
        handler.postDelayed({
            val a=active ?: return@postDelayed
            if(!a.active() || finishing) return@postDelayed
            val now=SystemClock.uptimeMillis()
            if(now >= a.started+a.durationMs && now-lastInput>=300) finishAttempt()
            else if(now > a.started+a.durationMs+5000) { a.fault("quiet_window_not_reached"); finishAttempt() }
            else checkFinish()
        },100)
    }
    private fun finishAttempt() {
        val a=active ?: return
        if(!a.active() || finishing) return
        finishing=true
        if(journal!!.incomplete.get()) a.fault(journal!!.error ?: "log_incomplete")
        val v=a.finish(SystemClock.uptimeMillis())
        emit("finished",mapOf("provisionalVerdict" to mapOf("status" to v.status,"reasons" to v.reasons),"cutoffSequence" to sequence,"state" to a.state()))
        val frozen=linkedMapOf<String,Any?>("reportSchemaVersion" to 1,"metadata" to metadata(),"environmentBefore" to initialEnvironment,"environmentAfter" to environment(),"expectations" to expectations(),"sceneBefore" to initialSceneState,"sceneAfter" to a.state(),"rawEventInterval" to mapOf("firstSequence" to 1,"cutoffSequence" to sequence),"assertions" to mapOf("status" to v.status,"reasons" to v.reasons),"status" to v.status,"receiverQualificationVersion" to "unqualified-physical-baseline-pending","senderResult" to null,"senderTraceRef" to runnerTrace,"agentRunId" to runnerAgentRunId,"association" to "time_window_only_not_sender_authentication","recordOrigin" to "android_dispatch","systemInjectionProven" to false)
        val j=journal!!; val dir=attemptDir!!
        Thread {
            try {
                val prefix=j.snapshot()
                if(j.incomplete.get()) { frozen["status"]="inconclusive"; frozen["assertions"]=mapOf("status" to "inconclusive","reasons" to listOf(j.error ?: "incomplete")) }
                frozen["integrity"]=mapOf("incomplete" to j.incomplete.get(),"droppedSamples" to j.dropped.get(),"error" to j.error,"lateEvents" to "append_to_events_jsonl_after_cutoff_until_next_arm")
                // Only the sealed prefix hash is stable; late records remain separately identifiable.
                frozen["eventPrefixSha256"]=prefix.second
                frozen["eventPrefixBytes"]=prefix.first
                atomicFile(File(dir,"report.json"),Json.encode(frozen))
                atomicFile(File(dir,"summary.md"),summary(frozen))
                check(File(dir,"active.json").delete())
                handler.post { report=frozen; finishing=false; controls.visibility=View.VISIBLE; output.text="${frozen["status"]}: ${frozen["assertions"]}\nReport ${dir.name}\nExport ZIP contains JSON, append-only raw JSONL, summary and hashes."; refresh() }
            } catch(e: Exception) { handler.post { a.fault("report_write_failed"); finishing=false; controls.visibility=View.VISIBLE; output.text="INCONCLUSIVE: report write failed ${e.message}. Active marker retained." } }
        }.start()
    }
    private fun metadata() = mapOf("schemaVersion" to 1,"labVersion" to BuildConfig.VERSION_NAME,"versionCode" to BuildConfig.VERSION_CODE,"buildId" to "input-lab-v3",
        "testRunId" to testRunId,"attemptId" to attemptId,"caseId" to active?.caseId,"receiverEpoch" to receiverEpoch,"scenarioVersion" to 3,"seed" to 1101,
        "mode" to mode,"layoutEpoch" to layoutEpoch,"sceneRevision" to active?.sceneRevision,"deviceRevision" to deviceRevision,"phase" to active?.phase?.name,
        "clockIds" to mapOf("eventTime" to "android_uptime_ms","receivedUptimeMs" to "android_uptime_ms","receivedElapsedRealtimeNanos" to "android_elapsed_realtime_ns","wallTimeMs" to "unix_epoch_ms"))
    private fun expectations() = mapOf("stimulusDescription" to instructions(active?.caseId ?: ""),"inputMode" to if(active?.caseId in setOf("LAB-06","LAB-07","LAB-08","LAB-08-AXIS","LAB-09")) "controller" else "touch_or_explicit_semantic",
        "observationWindowMs" to active?.durationMs,"quietWindowMs" to 300,"cleanupPolicy" to "observe_actual_up_and_neutral_never_synthesize",
        "targetWindowBounds" to active?.target?.map(),"endTargetWindowBounds" to active?.endTarget?.map(),"touchSlopPx" to active?.touchSlopPx,"tapMaxMs" to 500,"longPressMinMs" to 600,"swipeDurationMs" to listOf(100,5000),"expectedKeyCode" to 96,"comboKeyCodes" to listOf(96,97),
        "requiredAxisIds" to axisProfile.axes.toList(),"axisProfile" to axisProfile.map(),"axisExcursionFraction" to 0.5,"axisNeutralFraction" to 0.05,"upperRoleConfirmed" to (active?.roleConfirmed ?: false),
        "deviceChangePolicy" to "unchanged_public_configuration_when_idle_v2")
    private fun summary(r: Map<String,Any?>) = "# Thor Input Lab ${active?.caseId}\n\nStatus: ${r["status"]}\n\nAssertions: ${Json.encode(r["assertions"])}\n\nAttempt: $attemptId; receiver epoch: $receiverEpoch; mode: $mode\n\nQualification: unqualified, Thor physical baseline pending. Android dispatch is not proof of input sender identity or cross-screen injection.\n\nFrozen expectations: ${Json.encode(r["expectations"])}\n\nState: ${Json.encode(r["sceneAfter"])}\n\nIntegrity: ${Json.encode(r["integrity"])}\n\nClock: event/receive uptime milliseconds; receive elapsedRealtime nanoseconds includes deep sleep. Never subtract across clock IDs.\n\nEnvironment: ${Json.encode(r["environmentBefore"])}\n"
    private fun emit(kind: String, body: Map<String,Any?>, origin: String="receiver_diagnostic", samples: Int=1): Long? {
        val a=active ?: return null
        if(journal == null) return null
        val seq=++sequence
        val row=mapOf("schemaVersion" to 1,"testRunId" to testRunId,"caseId" to a.caseId,"attemptId" to attemptId,"receiverEpoch" to receiverEpoch,"sequence" to seq,
            "eventKind" to kind,"recordOrigin" to origin,"receivedUptimeMs" to SystemClock.uptimeMillis(),"receivedElapsedRealtimeNanos" to SystemClock.elapsedRealtimeNanos(),"wallTimeMs" to System.currentTimeMillis(),
            "scenarioVersion" to 3,"sceneRevision" to a.sceneRevision,"layoutEpoch" to layoutEpoch,"deviceRevision" to deviceRevision,"late" to (a.phase==Phase.FINISHED && kind!="finished"),"window" to windowSnapshot(),"data" to body)
        if(!journal!!.append(row,samples)) { a.fault(journal!!.error ?: "log_incomplete"); output.text="INCOMPLETE: ${journal!!.error}, dropped=${journal!!.dropped.get()}" }
        return seq
    }
    private fun receive(raw: Raw, payload: Map<String,Any?>) {
        lastInput=SystemClock.uptimeMillis()
        val seq=emit("raw_${raw.kind}",payload,"android_dispatch",maxOf(1,raw.samples.size*raw.pointerCount))
        currentDispatchSequence=seq
        val a=active ?: return
        if(!a.active()) return
        val before=a.state()
        val currentDevice=Capture.device(InputDevice.getDevice(raw.device))
        if(currentDevice["available"] != true || currentDevice != frozenDevices[raw.device]) {
            a.stale=true; a.fault("input_device_configuration_changed_at_dispatch")
            emit("device_configuration_mismatch",mapOf("deviceId" to raw.device,"frozenDevice" to frozenDevices[raw.device],"device" to currentDevice))
        }
        if(SystemClock.uptimeMillis() > a.started+a.durationMs) a.fault("input_after_observation_deadline")
        a.accept(raw)
        if(SystemClock.uptimeMillis()-lastUiRawRefresh>100) {
            lastUiRawRefresh=SystemClock.uptimeMillis()
            output.text=armedInstructions+"\nRAW #$seq ${raw.kind} action=${raw.action} source=0x${raw.source.toString(16)} d=${raw.device} key=${raw.key} samples=${raw.samples.size} time=${raw.time}ms"
        }
        if(before != a.state()) emit("scene_change",mapOf("triggerRawSequence" to seq,"before" to before,"after" to a.state()))
        if(journal?.incomplete?.get()==true) handler.post { finishAttempt() }
        refresh()
    }
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        physicalTouch = e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL
        physicalTouchDevice = if(physicalTouch) e.deviceId else null
        val (raw,payload)=Capture.motion(e,"touch"); receive(raw,payload)
        return try { super.dispatchTouchEvent(e) } finally { currentDispatchSequence=null }
    }
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val pair=e.deviceId to e.keyCode
        if(e.action==KeyEvent.ACTION_DOWN) physicalKeys += pair else if(e.action==KeyEvent.ACTION_UP) physicalKeys -= pair
        val (raw,payload)=Capture.key(e); receive(raw,payload)
        return try {
            if(active?.active()==true && active?.caseId in setOf("LAB-06","LAB-07","LAB-08","LAB-08-AXIS","LAB-09") && Sources.controller(e.source)) true else super.dispatchKeyEvent(e)
        } finally { currentDispatchSequence=null }
    }
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        val (raw,payload)=Capture.motion(e,"generic")
        if(Sources.has(raw.source,Sources.JOYSTICK)) raw.samples.lastOrNull()?.axes?.forEach { (axis,v) -> observedAxes[raw.device to axis]=v }
        receive(raw,payload)
        return try { if(active?.active()==true && Sources.has(e.source,Sources.JOYSTICK)) true else super.dispatchGenericMotionEvent(e) } finally { currentDispatchSequence=null }
    }
    private fun axesNeutral() = observedAxes.all { (p,v) -> Capture.ranges(InputDevice.getDevice(p.first)).find { it.axis==p.second }?.neutral(v) ?: (v==0f) }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); active?.focus(hasFocus); emit("window_focus",mapOf("hasFocus" to hasFocus)); if(::header.isInitialized) refresh() }
    override fun onPause() { lifecycleState="paused"; super.onPause(); emit("lifecycle",mapOf("state" to "paused")); active?.takeIf { it.active() && it.caseId !in setOf("LAB-01","LAB-09") }?.fault("activity_paused") }
    override fun onResume() {
        lifecycleState="resumed"; super.onResume()
        window.decorView.post {
            window.insetsController?.apply {
                systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        }
        emit("lifecycle",mapOf("state" to "resumed"))
    }
    override fun onDestroy() {
        if(runnerActivity?.get() === this) runnerActivity = null
        lifecycleState="destroyed"
        if(::dm.isInitialized) dm.unregisterDisplayListener(this)
        if(::im.isInitialized) im.unregisterInputDeviceListener(this)
        handler.removeCallbacksAndMessages(null)
        active?.takeIf { it.active() }?.let { emit("lifecycle",mapOf("state" to "destroyed","status" to "interrupted")); it.phase=Phase.INTERRUPTED }
        journal?.let { j -> Thread { j.close() }.start() }
        super.onDestroy()
    }
    override fun onDisplayAdded(id: Int) = displayChanged(id,"added")
    override fun onDisplayRemoved(id: Int) = displayChanged(id,"removed")
    override fun onDisplayChanged(id: Int) = displayChanged(id,"changed")
    private fun displayChanged(id: Int, change: String) { layoutEpoch++; active?.takeIf { it.active() }?.fault("display_changed"); emit("display_changed",mapOf("displayId" to id,"change" to change)); refresh() }
    override fun onInputDeviceAdded(id: Int) = deviceChanged(id,"added")
    override fun onInputDeviceRemoved(id: Int) = deviceChanged(id,"removed")
    override fun onInputDeviceChanged(id: Int) = deviceChanged(id,"changed")
    private fun deviceChanged(id: Int, change: String) {
        deviceRevision++
        val currentDevices=InputDevice.getDeviceIds().associateWith { Capture.device(InputDevice.getDevice(it)) }
        val a=active?.takeIf { it.active() }
        val held=physicalTouchDevice==id || physicalKeys.any { it.first==id } || a?.hasHeldInputs(id)==true ||
            observedAxes.any { (p,v) -> p.first==id && (frozenRanges[id]?.find { it.axis==p.second }?.neutral(v) ?: (v==0f)) != true }
        val decision=if(a==null) null else deviceContinuity?.assess(id,change,currentDevices,windowSnapshot(),displaySnapshots(),held,a.stale || a.faults.isNotEmpty())
            ?: DeviceContinuity.Decision(listOf("input_device_snapshot_unavailable"))
        if(a!=null && decision?.continuous != true) {
            a.stale=true; a.fault("input_device_$change")
            decision?.reasons?.forEach { a.fault(it) }
        }
        emit("device_changed",mapOf("change" to change,"deviceId" to id,"device" to (currentDevices[id] ?: Capture.device(null)),
            "frozenDevice" to frozenDevices[id],"affectedDeviceInputHeld" to held,"continuity" to decision?.map()))
        // An unchanged public configuration only appends evidence. It changes no rendered state.
        // Keep faults visible, including a journal failure raised by emit() after the decision.
        if(a==null || decision?.continuous != true || journal?.incomplete?.get()==true) refresh()
    }
    private fun windowSnapshot(): Map<String,Any?> {
        if(!::arena.isInitialized) return emptyMap()
        val pos=IntArray(2); window.decorView.getLocationOnScreen(pos)
        val insets=window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
        return mapOf("actualWindowDisplayId" to display?.displayId,"rotation" to display?.rotation,"hasWindowFocus" to hasWindowFocus(),"windowScreenX" to pos[0],"windowScreenY" to pos[1],
            "windowWidth" to window.decorView.width,"windowHeight" to window.decorView.height,"arenaWindowBounds" to arena.windowBounds().map(),"layoutHash" to digest(geometry()),"insets" to insets?.let { listOf(it.left,it.top,it.right,it.bottom) },"lifecycle" to lifecycleState,
            "windowMode" to "immersive_transient_system_bars_by_swipe","windowInsets" to windowInsetsSnapshot(window.decorView.rootWindowInsets))
    }
    private fun windowInsetsSnapshot(insets: WindowInsets?): Map<String,Any?> {
        if(insets==null) return mapOf("available" to false,"fullscreenReady" to false)
        val statusVisible=insets.isVisible(WindowInsets.Type.statusBars())
        val navigationVisible=insets.isVisible(WindowInsets.Type.navigationBars())
        val captionVisible=insets.isVisible(WindowInsets.Type.captionBar())
        val imeVisible=insets.isVisible(WindowInsets.Type.ime())
        fun bounds(type: Int) = insets.getInsets(type).let { listOf(it.left,it.top,it.right,it.bottom) }
        return mapOf("available" to true,"statusBarsVisible" to statusVisible,"navigationBarsVisible" to navigationVisible,
            "captionBarVisible" to captionVisible,"imeVisible" to imeVisible,
            "systemBarsInsets" to bounds(WindowInsets.Type.systemBars()),"imeInsets" to bounds(WindowInsets.Type.ime()),
            "fullscreenReady" to (!statusVisible && !navigationVisible && !captionVisible && !imeVisible))
    }
    private fun geometry() = if(::arena.isInitialized) Json.encode(mapOf("arena" to arena.windowBounds().map(),"width" to window.decorView.width,"height" to window.decorView.height,"display" to display?.displayId,"rotation" to display?.rotation)) else ""
    private fun environment() = mapOf("labVersion" to BuildConfig.VERSION_NAME,"applicationId" to packageName,"uid" to android.os.Process.myUid(),"pid" to android.os.Process.myPid(),"androidApi" to android.os.Build.VERSION.SDK_INT,"androidRelease" to android.os.Build.VERSION.RELEASE,
        "manufacturer" to android.os.Build.MANUFACTURER,"model" to android.os.Build.MODEL,"firmwareFingerprint" to android.os.Build.FINGERPRINT,"window" to windowSnapshot(),
        "displays" to displaySnapshots(),
        "inputDevices" to InputDevice.getDeviceIds().map { Capture.device(InputDevice.getDevice(it)) })
    private fun displaySnapshots() = dm.displays.sortedBy { it.displayId }.map { d -> val m=d.mode
        mapOf("displayId" to d.displayId,"name" to d.name,"rotation" to d.rotation,"state" to d.state,
            "physicalWidth" to m.physicalWidth,"physicalHeight" to m.physicalHeight,"refreshRate" to m.refreshRate)
    }
    private fun refresh() {
        if(!::header.isInitialized || !::arena.isInitialized) return
        val a=active
        val headerText="THOR INPUT LAB ${BuildConfig.VERSION_NAME} · display ${display?.displayId} · ${if(hasWindowFocus()) "FOCUSED" else "NO FOCUS"}\n${a?.caseId ?: "PREPARED"} / ${a?.phase ?: "idle"} · rev ${a?.sceneRevision ?: 0} · layout $layoutEpoch · raw ${a?.rawCount ?: 0} · ${attemptId.take(8)}\nIntegrity ${if(journal?.incomplete?.get()==true) "INCOMPLETE" else "no known gap"} · qualification pending"
        if(header.text.toString()!=headerText) header.text=headerText
        arena.invalidate()
    }
    private fun showEnvironment() { AlertDialogBuilder("Environment / focus",Json.encode(environment())) }
    private fun AlertDialogBuilder(title: String, message: String) { android.app.AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Close",null).show() }
    private fun chooseFault() { android.app.AlertDialog.Builder(this).setTitle("Receiver qualification fault (LAB-13)").setItems(arrayOf("none","budget","layout","restart")) { _, index -> faultProbe=listOf("none","budget","layout","restart")[index]; output.text="Fault probe selected: $faultProbe. This cannot produce injection evidence." }.show() }
    private fun chooseExport() {
        if(active?.active()==true || finishing) return
        val dirs=rootDir().listFiles().orEmpty().filter { File(it,"report.json").exists() }.sortedByDescending { it.lastModified() }
        if(dirs.isEmpty()) { output.text="No finished reports"; return }
        android.app.AlertDialog.Builder(this).setTitle("Export local report (descriptor hashed)").setItems(dirs.map { it.name.take(8) }.toTypedArray()) { _, i ->
            selectedExport=dirs[i]
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="application/zip"; putExtra(Intent.EXTRA_TITLE,"thor-input-lab-${dirs[i].name}.zip") },20)
        }.show()
    }
    @Deprecated("Legacy result API avoids extra runtime dependencies")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        val uri=data?.data ?: return; val dir=selectedExport ?: return
        if(requestCode!=20 || resultCode!=RESULT_OK) return
        Thread {
            try {
                journal?.flush()
                ZipOutputStream(contentResolver.openOutputStream(uri) ?: error("Cannot open selected document")).use { zip ->
                    val hashes=linkedMapOf<String,String>()
                    dir.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") && it.name!="active.json" }.forEach { f ->
                        val text=redactDescriptors(f.readText())
                        val bytes=text.toByteArray(Charsets.UTF_8)
                        zip.putNextEntry(ZipEntry(f.name)); zip.write(bytes); zip.closeEntry(); hashes[f.name]=digestBytes(bytes)
                    }
                    zip.putNextEntry(ZipEntry("export-manifest.json")); zip.write(Json.encode(mapOf("reportSchemaVersion" to 1,"descriptorPolicy" to "sha256","exportedWallTimeMs" to System.currentTimeMillis(),"hashes" to hashes,"hashPurpose" to "accidental_corruption_detection_not_signature")).toByteArray()); zip.closeEntry()
                }
                handler.post { output.text="Exported ZIP through user-selected document URI. Raw events preserved; device descriptors hashed." }
            } catch(e: Exception) { handler.post { output.text="Export failed: ${e.message}" } }
        }.start()
    }
    private fun redactDescriptors(text: String): String = Regex("\"descriptor\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"").replace(text) { match ->
        val value=match.value.substringAfter(':').trim()
        "\"descriptorHash\":"+Json.encode(digest(value))
    }
    private fun digest(s: String) = digestBytes(s.toByteArray(Charsets.UTF_8))
    private fun digestBytes(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun instructions(c: String) = when(c) {
        "LAB-01" -> "Confirm upper role, then change focus via another window/notification and return. This checks receiver focus timeline only."
        "LAB-02","LAB-03","LAB-14" -> "Tap START exactly once. Complete DOWN/UP required. Wrong target, no action, semantic-only must fail. LAB-03 uses the same frozen receiver geometry."
        "LAB-04-LONG" -> "Hold START at least 600 ms, then release."
        "LAB-04-SWIPE" -> "Swipe START → END in 100–5000 ms."
        "LAB-04-CANCEL" -> "Deliver a real DOWN/CANCEL touch sequence; UP must not count as cancel."
        "LAB-05" -> "Activate the native semantic button. Semantic evidence is separate from raw touch; self-drawn shapes have no fine UI tree."
        "LAB-06" -> "Press/release controller A (key 96); repeats do not count as new presses. Keyboard source fails."
        "LAB-07" -> "Scan ${axisProfile.name}: axes ${axisProfile.axes} on device ${axisProfile.deviceId ?: "one controller"}, at least 50% advertised range, then actual neutral (5% or flat). Missing axes fail."
        "LAB-08" -> "Hold controller A+B together on same device, release both; any observed axes must return neutral. Sequential taps fail."
        "LAB-08-AXIS" -> "Hold controller A while moving an advertised joystick axis at least 50% range; release A and return axis to actual neutral."
        "LAB-09" -> "Confirm upper role, change focus and receive controller A DOWN/UP. Does not infer where missing input went."
        "LAB-11" -> "Two-step touch task: START then END, each exactly once with real DOWN/UP."
        "LAB-13" -> "Fault qualification: budget/layout probe or kill/reopen process. Fault reports must be inconclusive."
        else -> "Unsupported case"
    }
    inner class Arena : View(this@LabActivity) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val trails=java.util.ArrayDeque<Pair<Float,Float>>()
        init { contentDescription="Input Lab self-drawn arena, START at left, END at right. No fine-grained semantic nodes."; isFocusable=true; isFocusableInTouchMode=true }
        private fun targetLocal() = Bounds(width*0.08f,height*0.25f,width*0.40f,height*0.70f)
        private fun endLocal() = Bounds(width*0.60f,height*0.25f,width*0.92f,height*0.70f)
        fun windowBounds(): Bounds { val p=IntArray(2); getLocationInWindow(p); return Bounds(p[0].toFloat(),p[1].toFloat(),p[0]+width.toFloat(),p[1]+height.toFloat()) }
        private fun toWindow(b: Bounds): Bounds { val p=IntArray(2); getLocationInWindow(p); return Bounds(b.left+p[0],b.top+p[1],b.right+p[0],b.bottom+p[1]) }
        fun targetWindow() = toWindow(targetLocal())
        fun endWindow() = toWindow(endLocal())
        override fun onDraw(c: Canvas) {
            c.drawColor(Color.rgb(19,35,52))
            fun box(b: Bounds,color: Int,label: String) { paint.color=color; c.drawRoundRect(b.left,b.top,b.right,b.bottom,10f,10f,paint); paint.color=Color.WHITE; paint.textSize=dp(16).toFloat(); c.drawText(label,b.left+dp(6),b.top+dp(24),paint) }
            val caseId=active?.caseId ?: caseNames[cases.selectedItemPosition]
            val controller=caseId in setOf("LAB-06","LAB-07","LAB-08","LAB-08-AXIS","LAB-09")
            paint.color=Color.WHITE; paint.textSize=dp(10).toFloat()
            c.drawText("Fixed seed 1101 · raw event receiver",12f,24f,paint)
            if(controller) drawController(c) else {
                box(targetLocal(),Color.rgb(12,123,117),"START"); box(endLocal(),Color.rgb(72,91,173),"END")
                paint.color=Color.YELLOW; trails.forEach { (x,y) -> c.drawCircle(x,y,3f,paint) }
            }
            paint.color=Color.WHITE; paint.textSize=dp(10).toFloat()
            c.drawText("hits ${active?.hits ?: 0} / wrong ${active?.wrong ?: 0} · ${if(active?.stale==true) "STALE / UNKNOWN" else "observed"}",12f,height-44f,paint)
            c.drawText("buttons ${active?.pressed ?: "[]"}",12f,height-24f,paint)
        }
        private fun drawController(c: Canvas) {
            val state=active
            val keys=listOf(96 to "A",97 to "B",99 to "X",100 to "Y",102 to "L1",103 to "R1",19 to "↑",20 to "↓",21 to "←",22 to "→")
            val cell=width/5f
            keys.forEachIndexed { i,(code,label) ->
                val x=i%5*cell+5; val y=40f+i/5*40
                paint.color=if(state?.pressed?.any { it.second==code }==true) Color.rgb(12,180,130) else Color.rgb(65,79,95)
                c.drawRoundRect(x,y,x+cell-10,y+32,5f,5f,paint)
                paint.color=Color.WHITE; paint.textSize=16f; c.drawText(label,x+10,y+23,paint)
            }
            val device=state?.axes?.keys?.firstOrNull()?.first
            fun normalized(axis: Int): Float {
                val value=state?.axes?.get(device to axis) ?: return 0f
                val r=Capture.ranges(device?.let { InputDevice.getDevice(it) }).find { it.axis==axis } ?: return value.coerceIn(-1f,1f)
                return if(r.min<0) (value/maxOf(kotlin.math.abs(r.min),r.max)).coerceIn(-1f,1f) else ((value-r.min)/(r.max-r.min)).coerceIn(0f,1f)
            }
            val radius=minOf(width*0.15f,height*0.13f)
            val cy=height*0.53f
            listOf(0 to 1,11 to 14).forEachIndexed { i,(xAxis,yAxis) ->
                val cx=width*(if(i==0) 0.25f else 0.75f)
                paint.color=Color.rgb(65,79,95); c.drawCircle(cx,cy,radius,paint)
                paint.color=Color.CYAN; c.drawCircle(cx+normalized(xAxis)*radius,cy+normalized(yAxis)*radius,8f,paint)
                paint.color=Color.WHITE; paint.textSize=13f; c.drawText(if(i==0) "LEFT X/Y" else "RIGHT Z/RZ",cx-radius,cy+radius+18,paint)
            }
            listOf((if(axisProfile.axes.contains(23)) 23 else 17) to "LT",(if(axisProfile.axes.contains(22)) 22 else 18) to "RT").forEachIndexed { i,(axis,label) ->
                val x=width*(if(i==0) 0.12f else 0.58f); val y=height*0.79f; val bar=width*0.3f
                paint.color=Color.rgb(65,79,95); c.drawRect(x,y,x+bar,y+16,paint)
                paint.color=Color.YELLOW; c.drawRect(x,y,x+bar*normalized(axis),y+16,paint)
                paint.color=Color.WHITE; paint.textSize=13f; c.drawText("$label ${state?.axes?.get(device to axis) ?: "missing"}",x,y-6,paint)
            }
        }
        override fun performClick(): Boolean {
            super.performClick()
            emit("semantic_arena",mapOf("semanticOrigin" to "semantic_only","triggerRawSequence" to null),"semantic_callback")
            return true // Accessibility action is recorded, never interpreted as a raw target hit.
        }
        @android.annotation.SuppressLint("ClickableViewAccessibility") // Raw trajectory arena has no click semantics.
        override fun onTouchEvent(e: MotionEvent): Boolean {
            for(h in 0..e.historySize) for(p in 0 until e.pointerCount) {
                trails.addLast((if(h<e.historySize) e.getHistoricalX(p,h) else e.getX(p)) to (if(h<e.historySize) e.getHistoricalY(p,h) else e.getY(p)))
                if(trails.size>800) trails.removeFirst() // Visual trail only; raw journal is never sampled.
            }
            invalidate(); return true
        }
    }
}
