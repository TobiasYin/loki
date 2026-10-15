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
data class Target(val packageName: String?, val validity: String, val revision: String,val touchableBounds:TouchableBounds?=null) {
    fun sameLayout(other:Target)=packageName==other.packageName && validity==other.validity && revision==other.revision && (touchableBounds==null)==(other.touchableBounds==null)
}
data class Snapshot(val id: String, val display: DisplayFact, val displayEpoch: Long, val target: Target, val expires: Long)
class Snapshots(private val clock: Clock, private val currentTarget: (Int) -> Target) {
    private var displays = emptyList<DisplayFact>()
    private var upper: DisplayFact? = null
    private var lower: DisplayFact? = null
    private var epoch = 1L
    private val snapshots = LinkedHashMap<String, Snapshot>()
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
    @Synchronized fun selected(): DisplayFact = upper ?: fail(Code.DISPLAY_GONE, "Calibrate upper/lower displays first")
    @Synchronized fun capture(): Snapshot {
        val display = selected()
        val snap = Snapshot(UUID.randomUUID().toString(), display, epoch, currentTarget(display.id), clock.now() + 10_000)
        if (snapshots.size >= 32) snapshots.remove(snapshots.keys.first())
        snapshots[snap.id] = snap
        return snap
    }
    @Synchronized fun validate(id: String?): Snapshot {
        val snap = snapshots[id] ?: fail(Code.TARGET_CHANGED, "Snapshot missing/invalidated")
        requireTool(clock.now() < snap.expires && snap.displayEpoch == epoch && snap.display == upper,
            Code.TARGET_CHANGED, "Display snapshot expired/changed")
        requireTool(snap.target.sameLayout(currentTarget(snap.display.id)), Code.TARGET_CHANGED, "Upper windows/app/layout changed")
        return snap
    }
    @Synchronized fun validateInputPoints(id:String?,points:List<Point>) {
        val snap=validate(id)
        snap.target.touchableBounds?.let { observed ->
            val current=currentTarget(snap.display.id)
            requireTool(snap.target.sameLayout(current) && current.touchableBounds!=null,Code.TARGET_CHANGED,"Input touch region evidence changed")
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
        requireTool(source.displayEpoch==epoch && source.display==upper,Code.TARGET_CHANGED,"Read display/role changed")
        return source
    }
    @Synchronized fun captureForRead(sourceId: String?): Snapshot {
        validateReadSource(sourceId)
        return capture()
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
