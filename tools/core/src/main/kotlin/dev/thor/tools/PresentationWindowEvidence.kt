package dev.thor.tools

/** Server-origin facts, never a window title or a caller-selected package. Raw
 * Accessibility UNKNOWN remains UNKNOWN; only this separate proof qualifies it. */
data class PresentationWindowProof(val displayId:Int,val windowId:Int,val token:String,val ownerUid:Int,
    val packageName:String,val flags:String,val frame:TouchableBounds,val rawWindowType:Int=2037) {
    fun matches(window:TargetWindowEvidence,display:Int):Boolean = displayId==display && windowId==window.id &&
        rawWindowType==2037 && frame.left<frame.right && frame.top<frame.bottom && token.matches(Regex("[0-9a-fA-F]{1,16}")) && ownerUid%100000>=10000 &&
        packageName==window.packageName && packageName !in setOf("android","com.android.systemui","com.tobias.loki","dev.thor.tools.diagnostic") &&
        window.type == -1 && window.bounds.let {it.left>=frame.left && it.top>=frame.top && it.right<=frame.right && it.bottom<=frame.bottom} &&
        flags.isNotBlank() && flags.split(' ').none {it in setOf("SECURE","NOT_TOUCHABLE") || it.startsWith("0x",true)}
    /** Time/generation/expiry deliberately excluded: identical refreshes must not invalidate node/frame identity. */
    fun identity()="$displayId:$windowId:$token:$rawWindowType:$ownerUid:$packageName:$flags:$frame"
}

/** Strict AOSP13 dump adapter. Dumps stay inside the shell service; only joined,
 * bounded metadata is returned. A11y WindowInfo.token and WM mClient are the same
 * system_server IWindow binder (not cross-process BinderProxy hash equality). */
object PresentationWindowEvidence {
    data class A11y(val id:Int,val display:Int,val token:String,val rawType:Int,val bounds:TouchableBounds,val signature:String)
    private fun one(text:String,pattern:String):MatchResult = Regex(pattern).findAll(text).toList().singleOrNull()
        ?: fail(Code.UNSUPPORTED,"Missing or ambiguous Presentation system metadata")
    private fun rect(m:MatchResult,start:Int=1)=TouchableBounds(m.groupValues[start].toInt(),m.groupValues[start+1].toInt(),m.groupValues[start+2].toInt(),m.groupValues[start+3].toInt())
    private const val RECT="Rect\\((-?\\d+), (-?\\d+) - (-?\\d+), (-?\\d+)\\)"
    private fun positive(r:TouchableBounds)=r.left<r.right && r.top<r.bottom
    fun a11y(dump:String,display:Int,ids:Set<Int>):List<A11y> {
        requireTool(dump.length<=2*1024*1024 && dump.startsWith("ACCESSIBILITY MANAGER"),Code.UNSUPPORTED,"Unrecognized Accessibility metadata")
        val lines=dump.lineSequence().toList();val result=mutableListOf<A11y>()
        for(i in 0 until lines.lastIndex) {
            val line=lines[i].trim()
            if(!line.startsWith("A11yWindow[AccessibilityWindowInfo["))continue
            val id=one(line,", id=(\\d+),").groupValues[1].toInt()
            if(id !in ids)continue
            val d=one(line,", displayId=(\\d+),").groupValues[1].toInt()
            requireTool(d==display,Code.TARGET_CHANGED,"Presentation moved to another display")
            requireTool(one(line,", type=([^,]+),").groupValues[1]=="<UNKNOWN:-1>",Code.UNSUPPORTED,"Only exact unknown Presentation candidates qualify")
            val raw=lines[i+1].trim()
            requireTool(raw.startsWith("WindowInfo[WindowInfo["),Code.UNSUPPORTED,"Unpaired Accessibility window metadata")
            requireTool(one(raw,", displayId=(\\d+),").groupValues[1].toInt()==display,Code.TARGET_CHANGED,"Presentation display metadata differs")
            val type=one(raw,", type=(\\d+),").groupValues[1].toInt()
            requireTool(type==2037,Code.UNSUPPORTED,"Unknown window is not a public Presentation")
            val token=one(raw,", token=android\\.os\\.BinderProxy@([0-9a-fA-F]{1,16}),").groupValues[1]
            val bounds=rect(one(line,", bounds=$RECT,"));requireTool(positive(bounds),Code.TARGET_CHANGED,"Presentation has empty bounds")
            requireTool(rect(one(raw,", bounds=$RECT,"))==bounds,Code.TARGET_CHANGED,"Presentation bounds metadata differs")
            // No title/content in the identity; structure/region/focus must agree before/after WM read.
            fun structural(s:String)=s.substringAfter(", displayId=", "").substringBefore(", accessibility anchor=")
            result.add(A11y(id,d,token,type,bounds,structural(line)+"|"+structural(raw)))
        }
        requireTool(result.size==ids.size && result.map {it.id}.toSet()==ids && result.map {it.token}.distinct().size==result.size,Code.TARGET_CHANGED,"Presentation Accessibility identity is missing/ambiguous")
        return result.sortedBy {it.id}
    }
    fun join(before:String,windows:String,after:String,display:Int,ids:Set<Int>):List<PresentationWindowProof> {
        requireTool(display>=0 && ids.size in 1..8 && ids.all {it>=0},Code.INVALID_ARGUMENT,"Bounded Presentation window ids required")
        val first=a11y(before,display,ids);val last=a11y(after,display,ids)
        requireTool(first==last,Code.TARGET_CHANGED,"Presentation changed during metadata inspection")
        requireTool(windows.length<=2*1024*1024 && windows.startsWith("WINDOW MANAGER WINDOWS"),Code.UNSUPPORTED,"Unrecognized WindowManager metadata")
        val blocks=Regex("(?m)^  Window #\\d+ Window\\{").split(windows).drop(1)
        return first.map {a->
            val matches=blocks.filter {Regex("(?m)^    mDisplayId=.* mClient=android\\.os\\.BinderProxy@${a.token}(?:\\s|$)").containsMatchIn(it)}
            val block=matches.singleOrNull() ?: fail(Code.TARGET_CHANGED,"Presentation WindowManager identity is missing/ambiguous")
            requireTool(one(block,"(?m)^    mDisplayId=(\\d+) ").groupValues[1].toInt()==display,Code.TARGET_CHANGED,"Presentation WM display differs")
            val owner=one(block,"(?m)^    mOwnerUid=(\\d+) showForAllUsers=(?:true|false) package=([A-Za-z][A-Za-z0-9_.]+) appop=[^\\r\\n]*$")
            val uid=owner.groupValues[1].toInt();val pkg=owner.groupValues[2]
            requireTool(uid%100000>=10000 && pkg !in setOf("android","com.android.systemui","com.tobias.loki","dev.thor.tools.diagnostic"),Code.PERMISSION_MISSING,"System/assistant Presentation is not eligible")
            val attrs=block.substringAfter("    mAttrs=","").substringBefore("    Requested ","")
            requireTool(attrs.isNotEmpty() && one(attrs,"\\bty=([A-Z_]+)\\b").groupValues[1]=="PRESENTATION",Code.UNSUPPORTED,"Only public Presentation layout type qualifies")
            requireTool(one(block,"mToken=WindowToken\\{[^\\r\\n]* type=(\\d+) ").groupValues[1].toInt()==2037,Code.UNSUPPORTED,"Presentation WindowToken type differs")
            val flags=one(attrs,"(?m)^      fl=([A-Z_ ]+)\\r?$").groupValues[1].trim().split(Regex(" +"))
            requireTool(flags.isNotEmpty() && flags.none {it in setOf("SECURE","NOT_TOUCHABLE")},Code.SECURE_CONTENT,"Secure/non-touchable Presentation is not eligible")
            requireTool(one(block,"(?m)^    mViewVisibility=(0x[0-9a-fA-F]+) mHaveFrame=(true|false)").let {it.groupValues[1]=="0x0" && it.groupValues[2]=="true"} &&
                one(block,"(?m)^    isVisible=(true|false)\\r?$").groupValues[1]=="true" &&
                one(block,"(?m) isOnScreen=(true|false)\\r?$").groupValues[1]=="true" &&
                one(block,"(?m)^    mHasSurface=(true|false) isReadyForDisplay\\(\\)=(true|false)").let {it.groupValues[1]=="true" && it.groupValues[2]=="true"},
                Code.TARGET_CHANGED,"Presentation is not visibly drawn")
            val frame=rect(one(block,"(?m)^    Frames: [^\\r\\n]* frame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]"))
            requireTool(positive(frame) && a.bounds.let {it.left>=frame.left && it.top>=frame.top && it.right<=frame.right && it.bottom<=frame.bottom},Code.TARGET_CHANGED,"Presentation geometry differs")
            PresentationWindowProof(display,a.id,a.token,uid,pkg,flags.sorted().joinToString(" "),frame)
        }
    }
}

/** Absolute freshness, independent of stable window identity. No cache hit extends
 * expiry; stale async work cannot repopulate a new display/service generation. */
enum class PresentationCacheReason {
    STRUCTURE_EVENT, SERVICE_CONNECTED, DISPLAY_CONFIGURATION, SERVICE_INTERRUPT,
    SERVICE_UNBOUND, SERVICE_DESTROYED, READER_CHANGED, EXPLICIT,
    KEY_CHANGED, EXPIRED, STORED, REJECTED_GENERATION, REJECTED_PROOF,
    PREFLIGHT_BEGIN, SCHEDULE_NO_READER, SCHEDULE_NO_CANDIDATE, SCHEDULE_TOO_MANY_CANDIDATES,
    SCHEDULE_FRESH_HIT, SCHEDULE_REFRESH_REQUIRED, SCHEDULE_QUEUED, SCHEDULE_JOINED,
    WORKER_BEGIN, READER_COMPLETE, READER_FAILED, READER_REMOTE_FAILED,
    RESULT_READER_REJECTED, RESULT_KEY_REJECTED, RESULT_IDS_REJECTED,
    WAIT_COMPLETE, WAIT_TIMEOUT, WAIT_CANCELLED, WAIT_INCOMPLETE, MAIN_NO_WAIT
}
data class PresentationCacheDiagnostic(val at:Long,val displayId:Int?,val generation:Long,
    val reason:PresentationCacheReason,val oldKeyHash:String?,val newKeyHash:String?,val ageMs:Long?,
    val eventType:Int?,val windowId:Int?,val reportedDisplayId:Int?,
    val remainingMs:Long?=null,val durationMs:Long?=null,val candidateCount:Int?=null,
    val expectedGeneration:Long?=null,val errorCode:Code?=null)

/** Resolve only a corroborated event display. Unknown/contradictory origins must
 * invalidate every cached display. A removed window can still carry its public
 * framework display id; no package/title/root text is used as identity. */
object PresentationEventScope {
    fun display(reportedDisplay:Int,windowId:Int,current:Map<Int,Set<Int>>):Int? {
        val mapped=current.filterValues {windowId>=0 && windowId in it}.keys
        if(mapped.size>1)return null
        val reported=reportedDisplay.takeIf {it>=0 && it in current}
        if(reportedDisplay>=0 && reported==null)return null
        if(reported!=null && mapped.isNotEmpty() && reported!=mapped.single())return null
        return reported ?: mapped.singleOrNull()
    }
}
/** A preflight scheduling threshold, not an extension of proof lifetime and
 * not a guarantee that an arbitrarily long tool will complete before expiry. */
object PresentationRefreshPolicy {
    const val PREFLIGHT_RESERVE_MS=2000L
    fun needsFreshProof(remainingMs:Long)=remainingMs<PREFLIGHT_RESERVE_MS
}
class PresentationProofCache(private val clock:Clock,private val ttlMs:Long=5000) {
    private data class Entry(val key:String,val started:Long,val proofs:List<PresentationWindowProof>)
    private val entries=LinkedHashMap<Int,Entry>()
    private var sequence=0L
    private var allGeneration=0L
    private val displayGenerations=LinkedHashMap<Int,Long>()
    private val trace=ArrayDeque<PresentationCacheDiagnostic>()
    @Synchronized fun generation()=allGeneration
    @Synchronized fun generation(display:Int)=displayGenerations[display] ?: allGeneration
    private fun keyHash(key:String?)=key?.let {
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
        val hex="0123456789abcdef"
        buildString(digest.size*2) {for(byte in digest) {val value=byte.toInt() and 255;append(hex[value ushr 4]);append(hex[value and 15])}}
    }
    private fun record(display:Int?,reason:PresentationCacheReason,old:Entry?=null,newKey:String?=null,
        eventType:Int?=null,windowId:Int?=null,reportedDisplay:Int?=null,
        remainingMs:Long?=null,durationMs:Long?=null,candidateCount:Int?=null,expectedGeneration:Long?=null,errorCode:Code?=null) {
        if(trace.size==64)trace.removeFirst()
        trace.addLast(PresentationCacheDiagnostic(clock.now(),display,display?.let {generation(it)} ?: allGeneration,
            reason,keyHash(old?.key),keyHash(newKey),old?.let {clock.now()-it.started},eventType,windowId,reportedDisplay,
            remainingMs,durationMs,candidateCount,expectedGeneration,errorCode))
    }
    /** Diagnostic-only side channel: fixed enums/numbers and a hashed internal
     * key. It cannot alter a proof, its generation or original deadline. */
    @Synchronized fun note(display:Int,reason:PresentationCacheReason,key:String?=null,remainingMs:Long?=null,
        durationMs:Long?=null,candidateCount:Int?=null,expectedGeneration:Long?=null,errorCode:Code?=null) {
        record(display,reason,newKey=key,remainingMs=remainingMs,durationMs=durationMs,
            candidateCount=candidateCount,expectedGeneration=expectedGeneration,errorCode=errorCode)
    }
    @Synchronized fun diagnostics():List<PresentationCacheDiagnostic> = trace.toList()
    @Synchronized fun invalidate(display:Int?=null,reason:PresentationCacheReason=PresentationCacheReason.EXPLICIT,
        eventType:Int?=null,windowId:Int?=null,reportedDisplay:Int?=null) {
        sequence++
        if(display==null) {
            allGeneration=sequence;displayGenerations.clear();entries.clear()
            record(null,reason,eventType=eventType,windowId=windowId,reportedDisplay=reportedDisplay)
        } else {
            val old=entries.remove(display);displayGenerations[display]=sequence
            // Only two calibrated roles are prepared; bound stale event-only ids too.
            if(displayGenerations.size>64) {
                allGeneration=sequence;displayGenerations.clear();entries.clear()
                record(null,reason,eventType=eventType,windowId=windowId,reportedDisplay=reportedDisplay)
            } else record(display,reason,old,eventType=eventType,windowId=windowId,reportedDisplay=reportedDisplay)
        }
    }
    /** Passive preflight hint only. Never renew, remove or replace an entry;
     * target()/commit use get() and the original absolute deadline instead. */
    @Synchronized fun remainingLifetimeMs(display:Int,key:String):Long {
        val entry=entries[display] ?: return 0
        val age=clock.now()-entry.started
        return if(entry.key==key && age in 0 until ttlMs)ttlMs-age else 0
    }
    @Synchronized fun get(display:Int,key:String):List<PresentationWindowProof>? {
        val entry=entries[display] ?: return null
        val expired=clock.now()-entry.started !in 0 until ttlMs
        if(entry.key!=key || expired) {
            entries.remove(display);record(display,if(expired)PresentationCacheReason.EXPIRED else PresentationCacheReason.KEY_CHANGED,entry,key)
            return null
        }
        return entry.proofs
    }
    @Synchronized fun put(display:Int,key:String,proofs:List<PresentationWindowProof>,started:Long,expectedGeneration:Long):Boolean {
        if(generation(display)!=expectedGeneration) {record(display,PresentationCacheReason.REJECTED_GENERATION,newKey=key);return false}
        if(clock.now()-started !in 0 until ttlMs || proofs.size !in 1..8 || proofs.any {it.displayId!=display} ||
            proofs.map {it.windowId}.distinct().size!=proofs.size) {record(display,PresentationCacheReason.REJECTED_PROOF,newKey=key);return false}
        if(entries.size>=2 && display !in entries)entries.remove(entries.keys.first())
        val old=entries.put(display,Entry(key,started,proofs.toList()));record(display,PresentationCacheReason.STORED,old,key)
        return true
    }
}

object PresentationOwnerPolicy {
    fun verify(proof:PresentationWindowProof,callerUid:Int,hostPackage:String,actualPackageUid:Int,systemApp:Boolean) {
        requireTool(proof.ownerUid/100000==callerUid/100000 && proof.ownerUid%100000>=10000 && proof.ownerUid!=callerUid &&
            proof.packageName !in setOf(hostPackage,"com.tobias.loki","dev.thor.tools.diagnostic","android","com.android.systemui") && !systemApp,
            Code.PERMISSION_MISSING,"Presentation user/system/assistant identity is not eligible")
        requireTool(actualPackageUid==proof.ownerUid,Code.PERMISSION_MISSING,"Presentation package owner UID differs")
    }
}
