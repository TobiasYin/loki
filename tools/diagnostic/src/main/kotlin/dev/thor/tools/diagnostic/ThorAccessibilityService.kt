package dev.thor.tools.diagnostic

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import dev.thor.tools.Target
import dev.thor.tools.TouchableBounds
import dev.thor.tools.TargetWindowEvidence
import dev.thor.tools.TargetWindowRevisions
import dev.thor.tools.TargetEventKind
import dev.thor.tools.UpperTargetResolver
import dev.thor.tools.*
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class ThorAccessibilityService : AccessibilityService() {
    private val revisions=TargetWindowRevisions()
    private val proofClock=Clock {SystemClock.elapsedRealtime()}
    private val proofs=PresentationProofCache(proofClock)
    private val proofWorker=Executors.newSingleThreadExecutor()
    private val jobs=LinkedHashMap<Int,Future<*>>()
    private val jobLock=Any()
    @Volatile private var closed=false
    @Volatile private var displayIdentity=""
    override fun onServiceConnected() { instance=this;proofs.invalidate(reason=PresentationCacheReason.SERVICE_CONNECTED) }
    private fun windowKey(display:Int,windows:List<AccessibilityWindowInfo>):String {
        val fact=displayFacts(this).singleOrNull {it.id==display} ?: return "missing:$display"
        return "$fact:"+windows.joinToString(";") {w->
            val r=Rect();w.getBoundsInScreen(r);val region=Region();w.getRegionInScreen(region)
            "${w.id}:${w.type}:${w.layer}:${w.isFocused}:${w.isActive}:$r:$region"
        }
    }
    private fun cached(window:AccessibilityWindowInfo):PresentationWindowProof? {
        if(window.type != -1)return null
        val windows=windowsOnAllDisplays[window.displayId].orEmpty()
        return proofs.get(window.displayId,windowKey(window.displayId,windows))?.singleOrNull {it.windowId==window.id}
    }
    private fun scheduleProof(display:Int):Future<*>? {
        proofs.note(display,PresentationCacheReason.PREFLIGHT_BEGIN)
        if(closed || presentationReader==null) {proofs.note(display,PresentationCacheReason.SCHEDULE_NO_READER);return null}
        val windows=windowsOnAllDisplays[display].orEmpty()
        val ids=windows.filter {it.type == -1}.map {it.id}.toSet()
        if(ids.isEmpty() || ids.size>8) {
            proofs.note(display,if(ids.isEmpty())PresentationCacheReason.SCHEDULE_NO_CANDIDATE else PresentationCacheReason.SCHEDULE_TOO_MANY_CANDIDATES,candidateCount=ids.size)
            return null
        }
        val key=windowKey(display,windows)
        // A merely unexpired hit can cross its hard deadline during a tool.
        // Preflight may acquire a complete new proof while leaving the old one
        // available at its original expiry for concurrent cached-only checks.
        val remaining=proofs.remainingLifetimeMs(display,key)
        if(!PresentationRefreshPolicy.needsFreshProof(remaining)) {
            proofs.note(display,PresentationCacheReason.SCHEDULE_FRESH_HIT,key,remainingMs=remaining,candidateCount=ids.size)
            return null
        }
        proofs.note(display,PresentationCacheReason.SCHEDULE_REFRESH_REQUIRED,key,remainingMs=remaining,candidateCount=ids.size)
        return synchronized(jobLock) {
            jobs[display]?.takeIf {!it.isDone}?.also {
                proofs.note(display,PresentationCacheReason.SCHEDULE_JOINED,key,remainingMs=remaining,candidateCount=ids.size)
            } ?: run {
                val generation=proofs.generation(display);val reader=presentationReader
                proofs.note(display,PresentationCacheReason.SCHEDULE_QUEUED,key,remainingMs=remaining,candidateCount=ids.size,expectedGeneration=generation)
                proofWorker.submit {
                    val started=proofClock.now() // full new metadata read begins here, never a cache touch
                    proofs.note(display,PresentationCacheReason.WORKER_BEGIN,key,candidateCount=ids.size,expectedGeneration=generation)
                    try {
                        val found=reader?.read?.invoke(display,ids.toIntArray()) ?: run {
                            proofs.note(display,PresentationCacheReason.RESULT_READER_REJECTED,key,expectedGeneration=generation)
                            return@submit
                        }
                        proofs.note(display,PresentationCacheReason.READER_COMPLETE,key,durationMs=proofClock.now()-started,candidateCount=found.size,expectedGeneration=generation)
                        when {
                            closed || presentationReader!==reader->proofs.note(display,PresentationCacheReason.RESULT_READER_REJECTED,key,expectedGeneration=generation)
                            key!=windowKey(display,windowsOnAllDisplays[display].orEmpty())->proofs.note(display,PresentationCacheReason.RESULT_KEY_REJECTED,key,expectedGeneration=generation)
                            found.map {it.windowId}.toSet()!=ids->proofs.note(display,PresentationCacheReason.RESULT_IDS_REJECTED,key,candidateCount=found.size,expectedGeneration=generation)
                            else->proofs.put(display,key,found,started,generation)
                        }
                    }catch(t:Throwable) {
                        proofs.note(display,PresentationCacheReason.READER_FAILED,key,durationMs=proofClock.now()-started,
                            expectedGeneration=generation,errorCode=(t as? ToolFailure)?.code)
                    }
                }.also {jobs[display]=it}
            }
        }
    }
    /** Explicit read-only entry, invoked before Native commit/resource locks.
     * target()/main/checkpoint never wait for shell IPC. No arbitrary displays. */
    fun preparePresentationEvidence(displayIds:Set<Int>,cancellation:Cancellation?=null,maxWaitMs:Long=1500) {
        requireTool(displayIds.size<=2 && displayIds.all {it>=0},Code.INVALID_ARGUMENT,"Only calibrated screen ids may be prepared")
        requireTool(maxWaitMs in 0..1500,Code.INVALID_ARGUMENT,"Invalid presentation wait budget")
        cancellation?.check()
        val identity=displayFacts(this).sortedBy {it.id}.joinToString(";")
        if(identity!=displayIdentity){displayIdentity=identity;proofs.invalidate(reason=PresentationCacheReason.DISPLAY_CONFIGURATION)}
        val work=displayIds.mapNotNull {display->scheduleProof(display)?.let {display to it}}
        if(Looper.myLooper()==Looper.getMainLooper()) {
            work.forEach {(display,_)->proofs.note(display,PresentationCacheReason.MAIN_NO_WAIT)}
            return
        }
        val waitStarted=proofClock.now();val deadline=waitStarted+maxWaitMs
        try {
            for((_,job) in work)while(!job.isDone && proofClock.now()<deadline) {
                cancellation?.check()
                try {job.get(minOf(50,(deadline-proofClock.now()).coerceAtLeast(1)),TimeUnit.MILLISECONDS)}
                catch(_:java.util.concurrent.TimeoutException){} catch(_:Exception){break}
            }
            cancellation?.check()
        }catch(t:Exception) {
            work.forEach {(display,_)->proofs.note(display,PresentationCacheReason.WAIT_CANCELLED,durationMs=proofClock.now()-waitStarted,errorCode=(t as? ToolFailure)?.code)}
            throw t
        }finally {
            work.forEach {(display,job)->proofs.note(display,when {job.isDone->PresentationCacheReason.WAIT_COMPLETE;proofClock.now()>=deadline->PresentationCacheReason.WAIT_TIMEOUT;else->PresentationCacheReason.WAIT_INCOMPLETE},durationMs=proofClock.now()-waitStarted)}
        }
    }
    fun isApplicationWindow(window:AccessibilityWindowInfo)=windowEvidence(window).isApplicationOn(window.displayId)
    fun windowEvidence(window:AccessibilityWindowInfo,readSystemRoot:Boolean=false,readAppRoot:Boolean=true):TargetWindowEvidence {
        val rect=Rect();window.getBoundsInScreen(rect)
        val region=Region();window.getRegionInScreen(region)
        val pieces=mutableListOf<TouchableBounds>();val iterator=RegionIterator(region);val piece=Rect()
        while(iterator.next(piece))pieces.add(TouchableBounds(piece.left,piece.top,piece.right,piece.bottom))
        var rootBounds:TouchableBounds?=null;var pkg:String?=null
        val proof=cached(window)
        if(!readAppRoot && proof!=null)pkg=proof.packageName
        if((window.type==AccessibilityWindowInfo.TYPE_APPLICATION || proof!=null) && readAppRoot || readSystemRoot)window.root?.let {node->
            try {
                if(node.refresh()) {
                    val root=Rect();node.getBoundsInScreen(root);rootBounds=TouchableBounds(root.left,root.top,root.right,root.bottom)
                    pkg=node.packageName?.toString()
                }
            }finally { @Suppress("DEPRECATION") node.recycle() }
        }
        return TargetWindowEvidence(window.id,window.type,window.layer,window.isFocused,window.isActive,
            TouchableBounds(rect.left,rect.top,rect.right,rect.bottom),pieces,region.isRect,pkg,rootBounds,proof?.takeIf {pkg==it.packageName})
    }
    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(event==null)return
        val kind=TargetEventKind.fromAndroidType(event.eventType,event.windowChanges,event.contentChangeTypes)
        val all=windowsOnAllDisplays
        if(kind==TargetEventKind.STRUCTURE) {
            val windows=linkedMapOf<Int,Set<Int>>()
            for(index in 0 until all.size())windows[all.keyAt(index)]=all.valueAt(index).map {it.id}.toSet()
            // Public AccessibilityRecord display id plus framework window mapping.
            // Never let an unrelated display revoke an in-flight observation;
            // unresolvable/conflicting origins still invalidate every proof.
            val reported=if(android.os.Build.VERSION.SDK_INT>=33)event.displayId else -1
            proofs.invalidate(PresentationEventScope.display(reported,event.windowId,windows),
                PresentationCacheReason.STRUCTURE_EVENT,event.eventType,event.windowId,reported)
        }
        val current=linkedMapOf<Int,List<TargetWindowEvidence>>()
        for(index in 0 until all.size()) {
            val display=all.keyAt(index)
            // Only an exact system window is eligible for the content-only exclusion.
            // Never trust the event's package string without corroborating its root.
            current[display]=all.valueAt(index).map {window->windowEvidence(window,kind==TargetEventKind.CONTENT && window.id==event.windowId && window.type==AccessibilityWindowInfo.TYPE_SYSTEM,readAppRoot=false)}
        }
        revisions.event(kind,event.windowId,event.packageName?.toString(),current)
    }
    fun target(display:Int):Target {
        val fact=displayFacts(this).singleOrNull {it.id==display} ?: return Target(null,"unknown","display-missing")
        // Cache-only: explicit read-only preflight is the sole metadata I/O entry.
        return UpperTargetResolver.resolve(fact,windowsOnAllDisplays[display].orEmpty().map {windowEvidence(it)},revisions)
    }
    /** Optional ASR scene hint, never input authority. Only the unique verified
     * application window is eligible; no tree traversal, screenshot or shell I/O. */
    fun foregroundWindowTitle(display:Int,packageName:String):String? {
        val before=target(display)
        if(before.validity!="valid" || before.packageName!=packageName)return null
        val window=windowsOnAllDisplays[display].orEmpty().filter {window->
            val evidence=windowEvidence(window)
            evidence.isApplicationOn(display) && evidence.packageName==packageName
        }.singleOrNull() ?: return null
        val title=window.title?.take(256)?.toString() ?: return null
        val after=target(display)
        return title.takeIf {before.sameStructure(after) && before.touchableBounds==after.touchableBounds}
    }
    /** Bounded local diagnostic facts only: no window title, text, package, token or dumps. */
    fun presentationDiagnostics():List<Map<String,Any?>> = proofs.diagnostics().map {item->mapOf(
        "atElapsedMs" to item.at,"displayId" to item.displayId,"generation" to item.generation,"reason" to item.reason.name,
        "oldKeyHash" to item.oldKeyHash,"newKeyHash" to item.newKeyHash,"ageMs" to item.ageMs,
        "eventType" to item.eventType,"windowId" to item.windowId,"reportedDisplayId" to item.reportedDisplayId,
        "remainingMs" to item.remainingMs,"durationMs" to item.durationMs,"candidateCount" to item.candidateCount,
        "expectedGeneration" to item.expectedGeneration,"errorCode" to item.errorCode?.name)}
    override fun onInterrupt() { proofs.invalidate(reason=PresentationCacheReason.SERVICE_INTERRUPT);invalidated?.invoke() }
    override fun onUnbind(intent:android.content.Intent?):Boolean { proofs.invalidate(reason=PresentationCacheReason.SERVICE_UNBOUND);instance=null;invalidated?.invoke();return super.onUnbind(intent) }
    override fun onDestroy() { closed=true;proofs.invalidate(reason=PresentationCacheReason.SERVICE_DESTROYED);proofWorker.shutdownNow();instance=null;invalidated?.invoke();super.onDestroy() }
    companion object {
        private data class PresentationReader(val owner:Any,val read:(Int,IntArray)->List<PresentationWindowProof>)
        @Volatile private var presentationReader:PresentationReader?=null
        @Synchronized fun installPresentationReader(owner:Any,reader:(Int,IntArray)->List<PresentationWindowProof>) {
            presentationReader=PresentationReader(owner,reader);invalidatePresentationEvidence()
        }
        @Synchronized fun removePresentationReader(owner:Any) {
            if(presentationReader?.owner===owner){presentationReader=null;invalidatePresentationEvidence()}
        }
        /** Bound-reader rejection code only. Raw response messages never enter the trace. */
        fun recordPresentationReaderFailure(display:Int,code:Code?) {
            instance?.proofs?.note(display,PresentationCacheReason.READER_REMOTE_FAILED,errorCode=code)
        }
        fun invalidatePresentationEvidence(){instance?.proofs?.invalidate(reason=PresentationCacheReason.READER_CHANGED)}

        @Volatile var instance:ThorAccessibilityService?=null
        @Volatile var invalidated:(()->Unit)?=null
    }
}
