package dev.thor.inputlab

import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Separate journal/schema. Does not change original Input Lab receiver qualification. */
class ScenarioSession(val model:ScenarioModel,val testRunId:String,val agentRunId:String,val senderTraceRef:String,val evidenceMode:String,files:File,private val maxEvents:Long=20000,private val maxBytes:Long=8L*1024*1024,private val uptime:()->Long={SystemClock.uptimeMillis()},private val elapsed:()->Long={SystemClock.elapsedRealtime()}) {
    companion object {
        const val MANUAL_MODE="manual_synthetic"
        fun manual(model:ScenarioModel,files:File,uptime:()->Long={SystemClock.uptimeMillis()},elapsed:()->Long={SystemClock.elapsedRealtime()}) =
            ScenarioSession(model,"manual-${UUID.randomUUID()}","","manual-scene-selector",MANUAL_MODE,files,uptime=uptime,elapsed=elapsed)
    }
    val isManual get()=evidenceMode==MANUAL_MODE
    val attemptId=UUID.randomUUID().toString()
    private val directory=File(files,"scenario-reports/$attemptId").apply {check(mkdirs())}
    private val events=File(directory,"events.jsonl")
    private val stream=FileOutputStream(events)
    private var sequence=0L
    private var incomplete=false
    private var dropped=0L
    var sealed=false;private set
    var interruptedReason:String?=null;private set
    var geometry:Map<String,Any?> = emptyMap()
    init {record("prepared",mapOf("scene" to model.scene,"seed" to model.seed))}
    @Synchronized fun record(kind:String,data:Map<String,Any?>,origin:String="receiver_ui") {
        if(sealed)return
        if(incomplete || sequence>=maxEvents){incomplete=true;dropped++;return}
        val row=mapOf("sequence" to (sequence+1),"eventKind" to kind,"recordOrigin" to origin,"attemptId" to attemptId,"testRunId" to testRunId,"agentRunId" to agentRunId,"evidenceMode" to evidenceMode,"uptimeMs" to uptime(),"elapsedRealtimeMs" to elapsed(),"page" to model.page,"data" to data)
        val bytes=(Json.encode(row)+"\n").toByteArray(Charsets.UTF_8)
        if(events.length()+bytes.size>maxBytes){incomplete=true;dropped++;return}
        try {stream.write(bytes);sequence++}catch(_:Exception){incomplete=true;dropped++}
    }
    @Synchronized fun seal(reason:String?=null) {
        if(sealed)return
        interruptedReason=reason;record("sealed",mapOf("reason" to reason,"uiFlowCompleted" to model.completed()))
        try {stream.fd.sync();stream.close()}catch(_:Exception){incomplete=true}
        sealed=true
    }
    @Synchronized fun persistSettings() {
        val values=model.settingsValues ?: error("No saved values")
        val target=File(directory,"saved-settings.json");atomicFile(target,Json.encode(values))
        record("settings_persisted",mapOf("relativePath" to "scenario-reports/$attemptId/saved-settings.json","sha256" to sha256(target),"values" to values))
    }
    @Synchronized fun snapshot():String {
        if(!sealed)try {stream.fd.sync()}catch(_:Exception){incomplete=true}
        val report=linkedMapOf<String,Any?>("schemaVersion" to 1,"attemptId" to attemptId,"testRunId" to testRunId,"agentRunId" to agentRunId,"senderTraceRef" to senderTraceRef,"evidenceMode" to evidenceMode,"scene" to model.scene,"seed" to model.seed,"page" to model.page,"taskPrompt" to model.taskPrompt,"state" to model.state(),"expected" to model.expected(),"completed" to (model.completed() && !incomplete && interruptedReason==null),"interruptedReason" to interruptedReason,"geometry" to geometry,"agentAnswerVerified" to false,"receiverQualificationVersion" to "scenario-ui-observation-v1","systemInjectionProven" to false,"evidence" to mapOf("eventPrefixBytes" to events.length(),"eventPrefixSha256" to sha256(events),"eventCount" to sequence,"incomplete" to incomplete,"droppedSamples" to dropped,"sealed" to sealed,"relativePath" to "scenario-reports/$attemptId/events.jsonl"))
        val text=Json.encode(report);atomicFile(File(directory,"report.json"),text);return text
    }
}
object ScenarioSessions { @Volatile var current:ScenarioSession?=null }
