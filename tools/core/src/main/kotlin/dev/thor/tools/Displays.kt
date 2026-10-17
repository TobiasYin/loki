package dev.thor.tools

import java.util.UUID

data class DisplayFact(val id: Int, val identity: String, val width: Int, val height: Int, val rotation: Int, val density: Int)
data class TouchableBounds(val left:Int,val top:Int,val right:Int,val bottom:Int) {
    fun contains(point:Point)=point.x>=left && point.x<right && point.y>=top && point.y<bottom
}
object FullScreenGeometry {
    /** Separate layout from clipping only with complete sole-window evidence.
     * There is no pixel tolerance; every input must still satisfy both clips. */
    fun canonicalRoot(display:DisplayFact,windowCount:Int,applicationCount:Int,root:TouchableBounds?,visible:TouchableBounds?,rectangular:Boolean):TouchableBounds? {
        if(windowCount!=1 || applicationCount!=1 || !rectangular || root==null || visible==null)return null
        if(root!=TouchableBounds(0,0,display.width,display.height))return null
        if(visible.left<root.left || visible.top<root.top || visible.right>root.right || visible.bottom>root.bottom || visible.left>=visible.right || visible.top>=visible.bottom)return null
        return root
    }
}
data class Target(val packageName: String?, val validity: String, val revision: String,val touchableBounds:TouchableBounds?=null,
    // Null preserves legacy/synthetic semantics, including copy(revision=...).
    // Real Accessibility targets provide structure separately from content events.
    val layoutRevision:String?=null,
    // Scroll/subtree changes expire new coordinates without cancelling a hold
    // that caused them or invalidating an unchanged physical window proof.
    val interactionRevision:String?=null) {
    fun sameStructure(other:Target)=packageName==other.packageName && validity==other.validity && (layoutRevision ?: revision)==(other.layoutRevision ?: other.revision)
    /** Content identity remains useful for diagnostics and captured-pixel provenance. */
    fun sameLayout(other:Target)=sameStructure(other) && revision==other.revision && (touchableBounds==null)==(other.touchableBounds==null)
    /** Normal text/caret/render updates do not move an input target. Actual
     * window identity and visibility evidence must remain available. Coordinate
     * inputs separately require every point inside both observed/current clips. */
    fun sameInputTarget(other:Target)=sameStructure(other) && (touchableBounds==null)==(other.touchableBounds==null) &&
        (interactionRevision ?: layoutRevision ?: revision)==(other.interactionRevision ?: other.layoutRevision ?: other.revision)
}
data class Snapshot(val id: String, val display: DisplayFact, val displayEpoch: Long, val target: Target, val expires: Long,val role:String="upper")
class Snapshots(private val clock: Clock, private val currentTarget: (Int) -> Target) {
    companion object {
        /** Resource freshness only, never a model-run timeout. Target/layout,
         * display epoch and authorization are still checked on every use. */
        const val OBSERVATION_TTL_MS=60_000L
    }
    private var displays = emptyList<DisplayFact>()
    private var upper: DisplayFact? = null
    private var lower: DisplayFact? = null
    private var epoch = 1L
    private val snapshots = LinkedHashMap<String, Snapshot>()
    private var lowerGuard:((DisplayFact,Target)->Unit)?=null
    @Synchronized fun installLowerGuard(guard:(DisplayFact,Target)->Unit) {lowerGuard=guard}
    private fun checkRoleTarget(display:DisplayFact,role:String,target:Target) {
        if(role=="lower") {
            requireTool(target.validity=="valid" && !target.packageName.isNullOrBlank() && target.packageName!="dev.thor.agent",
                Code.PERMISSION_MISSING,"Lower target must be a unique non-assistant application")
            requireTool(target.touchableBounds?.let {it.left<it.right && it.top<it.bottom}==true,Code.SECURE_CONTENT,"Lower target has no safe visible region")
            lowerGuard?.invoke(display,target)
        }
    }
    @Synchronized fun update(facts: List<DisplayFact>) {
        if (displays != facts.sortedBy { it.id }) {
            epoch++; snapshots.clear()
            // Calibration survives unchanged descriptors only, never silently accepts new rotation/geometry.
            upper = upper?.takeIf { it in facts }; lower = lower?.takeIf { it in facts }
            displays = facts.sortedBy { it.id }
        }
    }
    @Synchronized fun calibrate(upperId: Int, lowerId: Int) {
        requireTool(upperId != lowerId, message = "Upper/lower must be different displays")
        val chosenUpper = displays.singleOrNull { it.id == upperId } ?: fail(Code.DISPLAY_GONE, "Upper display missing")
        val chosenLower = displays.singleOrNull { it.id == lowerId } ?: fail(Code.DISPLAY_GONE, "Lower display missing")
        upper = chosenUpper; lower = chosenLower
        epoch++; snapshots.clear()
    }
    @Synchronized fun selected(role:String="upper"): DisplayFact = when(role) {
        "upper"->upper;"lower"->lower;else->fail(Code.INVALID_ARGUMENT,"Screen role must be upper or lower")
    } ?: fail(Code.DISPLAY_GONE,"Calibrated $role display unavailable")
    @Synchronized fun capture(role:String="upper"): Snapshot {
        val display = selected(role);val target=currentTarget(display.id)
        checkRoleTarget(display,role,target)
        val snap = Snapshot(UUID.randomUUID().toString(), display, epoch, target, clock.now() + OBSERVATION_TTL_MS,role)
        if (snapshots.size >= 32) snapshots.remove(snapshots.keys.first())
        snapshots[snap.id] = snap
        return snap
    }
    @Synchronized fun validate(id: String?): Snapshot {
        val snap = snapshots[id] ?: fail(Code.TARGET_CHANGED, "Snapshot missing/invalidated")
        requireTool(clock.now() < snap.expires && snap.displayEpoch == epoch && snap.display == selected(snap.role),
            Code.TARGET_CHANGED, "Display snapshot expired/changed")
        val current=currentTarget(snap.display.id);checkRoleTarget(snap.display,snap.role,current)
        requireTool(snap.target.sameInputTarget(current), Code.TARGET_CHANGED, "Selected screen windows/app/layout changed")
        return snap
    }
    /** An already-submitted bounded hold may ignore its own content feedback.
     * New inputs still check exact clipping; node references check their own
     * identity/path/signature independently of visual content revisions. */
    @Synchronized fun validateHeld(id:String?):Snapshot {
        val snap=snapshots[id] ?: fail(Code.TARGET_CHANGED,"Snapshot missing/invalidated")
        requireTool(clock.now()<snap.expires && snap.displayEpoch==epoch && snap.display==selected(snap.role),
            Code.TARGET_CHANGED,"Display snapshot expired/changed")
        val current=currentTarget(snap.display.id);checkRoleTarget(snap.display,snap.role,current)
        requireTool(snap.target.sameStructure(current),Code.TARGET_CHANGED,"Selected screen windows/app/layout changed during held input")
        return snap
    }
    /** Reading pixels/text and fixed non-coordinate navigation may span app text
     * updates. Window identity, focus, package, exact clipping and display remain
     * bound to the original observation. Node references need independent identity
     * verification and frame coordinates retain their separate frame binding. */
    @Synchronized fun validateObservation(id:String?):Snapshot {
        val snap=snapshots[id] ?: fail(Code.TARGET_CHANGED,"Observation missing/invalidated")
        requireTool(clock.now()<snap.expires && snap.displayEpoch==epoch && snap.display==selected(snap.role),
            Code.TARGET_CHANGED,"Display observation expired/changed")
        val current=currentTarget(snap.display.id)
        checkRoleTarget(snap.display,snap.role,current)
        requireTool(snap.target.sameStructure(current) && snap.target.touchableBounds==current.touchableBounds,
            Code.TARGET_CHANGED,"Observed app/windows/geometry changed")
        return snap
    }
    /** Sign a fresh identity at read completion. The caller must retain the
     * original capture identity for any pixels/refs recorded before this point. */
    @Synchronized fun captureReadCompletion(observationId:String?):Snapshot {
        val before=validateObservation(observationId)
        val after=capture(before.role)
        requireTool(before.target.sameStructure(after.target) && before.target.touchableBounds==after.target.touchableBounds,
            Code.TARGET_CHANGED,"Observed app/windows/geometry changed at read completion")
        return after
    }
    @Synchronized fun validateInputPoints(id:String?,points:List<Point>) {
        val snap=validate(id)
        snap.target.touchableBounds?.let { observed ->
            val current=currentTarget(snap.display.id)
            requireTool(snap.target.sameInputTarget(current) && current.touchableBounds!=null,Code.TARGET_CHANGED,"Input touch region evidence changed")
            val currentClip=current.touchableBounds ?: fail(Code.TARGET_CHANGED,"Current touchable region missing")
            requireTool(points.isNotEmpty() && points.all {observed.contains(it) && currentClip.contains(it)},Code.TARGET_CHANGED,"Input leaves observed/current touchable region")
            // Both clips are rectangles: a polyline with every vertex inside their
            // intersection remains inside it throughout each straight segment.
        }
    }
    /** A perception read may observe the current app, but only on the exact
     * display/role authorized by its request. Never use this for input. */
    @Synchronized fun validateReadSource(id: String?): Snapshot {
        val source=snapshots[id] ?: fail(Code.TARGET_CHANGED,"Read source snapshot missing/invalidated")
        requireTool(source.displayEpoch==epoch && source.display==selected(source.role),Code.TARGET_CHANGED,"Read display/role changed")
        return source
    }
    @Synchronized fun captureForRead(sourceId: String?): Snapshot {
        val source=validateReadSource(sourceId)
        return capture(source.role)
    }
    @Synchronized fun selectScreen(sourceId:String?,role:String):Snapshot {
        validateReadSource(sourceId)
        requireTool(role in setOf("upper","lower"),message="Screen role must be upper or lower")
        return capture(role)
    }
    /** Lower is discovered from calibration only; no caller-selected display or
     * companion identity may ever become an input snapshot. */
    @Synchronized fun captureCompanion(sourceId:String?,forbiddenPackages:Set<String>):CompanionObservation {
        requireTool(validateReadSource(sourceId).role=="upper",message="Companion shortcut requires an upper source; select upper or read the selected lower screen normally")
        val observed=captureForRead(sourceId)
        requireTool(observed.target.validity=="valid" && !observed.target.packageName.isNullOrBlank() && observed.target.packageName !in forbiddenPackages,
            Code.PERMISSION_MISSING,"Companion requires an authorized non-assistant upper application")
        requireTool(observed.target.touchableBounds?.let {it.left<it.right && it.top<it.bottom}==true,
            Code.SECURE_CONTENT,"Authorized upper application is not unambiguously visible")
        val companion=lower ?: fail(Code.DISPLAY_GONE,"Calibrated lower display is unavailable")
        requireTool(companion!=observed.display && companion in displays,Code.DISPLAY_GONE,"Distinct calibrated lower display required")
        val target=currentTarget(companion.id)
        requireTool(target.validity=="valid" && target.packageName==observed.target.packageName,Code.PERMISSION_MISSING,"Lower display is not a companion of the current upper application")
        checkRoleTarget(companion,"lower",target)
        return CompanionObservation(UUID.randomUUID().toString(),observed,companion,target,observed.expires).also {validateCompanion(it)}
    }
    @Synchronized fun validateCompanion(observation:CompanionObservation) {
        validateObservation(observation.upper.id)
        requireTool(clock.now()<observation.expires && observation.lowerDisplay==lower && observation.lowerDisplay in displays,
            Code.TARGET_CHANGED,"Companion display/role/geometry or lifetime changed")
        val current=currentTarget(observation.lowerDisplay.id)
        checkRoleTarget(observation.lowerDisplay,"lower",current)
        requireTool(current.validity=="valid" && current.packageName==observation.upper.target.packageName &&
            observation.lowerTarget.sameStructure(current) && observation.lowerTarget.touchableBounds==current.touchableBounds,
            Code.TARGET_CHANGED,"Companion app/window/visible geometry changed")
    }
    @Synchronized fun completeCompanion(observation:CompanionObservation):Snapshot {
        validateCompanion(observation)
        val upperCompletion=captureReadCompletion(observation.upper.id)
        validateCompanion(observation)
        return upperCompletion
    }
    @Synchronized fun validateCompanionResult(upperId:String?,data:Map<String,Any?>) {
        val upperObservation=validateObservation(upperId)
        val imageIdentity=data["companionObservationId"] as? String
        requireTool(upperObservation.role=="upper" && lower!=null && data["readOnly"]==true && data["frameInputUsable"]==false && !data.containsKey("frameId") &&
            data["coordinateSpace"]=="companion_read_only" && data["displayRole"]=="lower-companion-read-only" &&
            data["displayId"]==lower?.id && data["displayId"]!=upperObservation.display.id &&
            data["authorizedInputDisplayId"]==upperObservation.display.id && data["targetSnapshotId"]==upperObservation.id &&
            !imageIdentity.isNullOrBlank() && imageIdentity !in snapshots &&
            (data["companionImageTransform"] as? Map<*,*>)?.get("identity")==imageIdentity,
            Code.TARGET_CHANGED,"Companion result must retain upper input authority and a separate read-only lower image identity")
    }
}
data class Point(val x: Double, val y: Double)
data class FrameTransform(val left: Int, val top: Int, val width: Int, val height: Int, val imageWidth: Int, val imageHeight: Int) {
    fun toDisplay(point: Point): Point {
        requireTool(point.x.isFinite() && point.y.isFinite() && point.x >= 0 && point.x < imageWidth && point.y >= 0 && point.y < imageHeight, message = "Image coordinate outside frame")
        return Point(left + point.x * width / imageWidth, top + point.y * height / imageHeight)
    }
}
data class Gesture(val points: List<Point>, val durationMs: Long) {
    fun validate(display: DisplayFact) {
        requireTool(durationMs in 1..5000, message = "Gesture duration must be 1..5000 ms")
        requireTool(points.size in 1..32, Code.LIMIT_EXCEEDED, "Gesture needs 1..32 points")
        points.forEach { requireTool(it.x.isFinite() && it.y.isFinite() && it.x >= 0 && it.y >= 0 && it.x < display.width && it.y < display.height, message = "Display coordinate out of bounds") }
    }
}
