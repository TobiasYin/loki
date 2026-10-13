package dev.thor.tools

import java.util.UUID

data class DisplayFact(val id: Int, val identity: String, val width: Int, val height: Int, val rotation: Int, val density: Int)
data class Target(val packageName: String?, val validity: String, val revision: String)
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
        requireTool(snap.target == currentTarget(snap.display.id), Code.TARGET_CHANGED, "Upper windows/app/layout changed")
        return snap
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
