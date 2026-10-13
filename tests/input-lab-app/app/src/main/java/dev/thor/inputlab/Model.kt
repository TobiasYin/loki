package dev.thor.inputlab

import kotlin.math.abs

/** Offline fixtures call this same reducer, but never enter the Android evidence journal. */
object Json {
    fun encode(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.map { when(it) { '"' -> "\\\""; '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; else -> if(it.code < 32) "\\u%04x".format(it.code) else it.toString() } }.joinToString("") + "\""
        is Boolean, is Number -> if(v is Number && !v.toDouble().isFinite()) "null" else v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { encode(it.key.toString()) + ":" + encode(it.value) }
        is Iterable<*> -> v.joinToString(",", "[", "]") { encode(it) }
        else -> error("Unsupported JSON value ${v.javaClass}")
    }
}

data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    fun map() = mapOf("left" to left, "top" to top, "right" to right, "bottom" to bottom)
}
data class Range(val axis: Int, val source: Int, val min: Float, val max: Float, val flat: Float) {
    fun neutral(v: Float) = abs(v - if(min < 0) 0f else min) <= maxOf(flat, (max-min)*0.05f)
    fun excursion(v: Float) = abs(v - if(min < 0) 0f else min) >= (max-min)*0.5f
}
data class Sample(val time: Long, val x: Float, val y: Float, val axes: Map<Int, Float> = emptyMap())
data class Raw(
    val kind: String, val action: Int, val time: Long, val downTime: Long = time,
    val source: Int = 0x1002, val device: Int = 1, val key: Int = 0, val repeat: Int = 0,
    val samples: List<Sample> = emptyList(), val pointerCount: Int = 1
)
object Sources {
    const val GAMEPAD = 0x401
    const val JOYSTICK = 0x1000010
    const val TOUCHSCREEN = 0x1002
    fun has(source: Int, mask: Int) = source and mask == mask
    fun controller(source: Int) = has(source, GAMEPAD) || has(source, JOYSTICK)
}
enum class Phase { PREPARED, ARMED, RECORDING, FINISHED, INTERRUPTED }
data class Verdict(val status: String, val reasons: List<String>)

class Attempt(
    val caseId: String, val target: Bounds, val endTarget: Bounds, val started: Long,
    val durationMs: Long = 5000, val expectedKey: Int = 96,
    val requiredAxes: Set<Int> = setOf(0, 1, 11, 14, 17, 18), val roleConfirmed: Boolean = false, val touchSlopPx: Float = 24f
) {
    var phase = Phase.PREPARED
    var sceneRevision = 0L
    var rawCount = 0
    var sampleCount = 0
    var hits = 0
    var wrong = 0
    var semanticCount = 0
    var focusChanges = 0
    var stale = false
    val faults = linkedSetOf<String>()
    val pressed = linkedSetOf<Pair<Int, Int>>()
    val axes = linkedMapOf<Pair<Int, Int>, Float>()
    private val downKeys = mutableSetOf<Pair<Int, Int>>()
    private val pairedKeys = mutableSetOf<Int>()
    private val keyDownTimes = mutableMapOf<Pair<Int, Int>, Long>()
    private val pairCounts = mutableMapOf<Int, Int>()
    private val excursedAxes = mutableSetOf<Pair<Int, Int>>()
    private val neutralAxes = mutableSetOf<Pair<Int, Int>>()
    private val deviceRanges = mutableMapOf<Int, List<Range>>()
    private var touchDown: Raw? = null
    private var touchSequenceValid = true
    private var tapPairs = 0
    private var maxTouchDistance = 0f
    private var moves = 0
    private var shortTaps = 0
    private var longPresses = 0
    private var swipes = 0
    private var cancels = 0
    private var overlap = false
    private var buttonAxisOverlap = false
    private var step = 0
    fun arm() { check(phase == Phase.PREPARED); phase = Phase.ARMED }
    fun ranges(device: Int, ranges: List<Range>) { deviceRanges[device] = ranges }
    fun fault(reason: String) { faults += reason }
    fun focus(value: Boolean) {
        if(phase == Phase.FINISHED) return
        focusChanges++
        if(!value) stale = true // Never manufacture release or neutral samples.
    }
    fun semantic() { if(active()) { semanticCount++; sceneRevision++ } }
    fun active() = phase == Phase.ARMED || phase == Phase.RECORDING
    fun accept(e: Raw) {
        if(!active()) return
        phase = Phase.RECORDING
        rawCount++; sampleCount += maxOf(1, e.samples.size)
        if(e.kind == "touch" && caseId !in setOf("LAB-01","LAB-05")) touch(e)
        if(e.kind == "key") key(e)
        if(e.kind == "generic" && Sources.has(e.source, Sources.JOYSTICK) && e.action == 2) {
            e.samples.forEach { s ->
                s.axes.forEach axisLoop@ { (axis, value) ->
                    val pair = e.device to axis
                    val range = deviceRanges[e.device]?.find { it.axis == axis && Sources.has(e.source, it.source) } ?: return@axisLoop
                    if(axes[pair] != value) sceneRevision++
                    axes[pair] = value
                    if(range.max > range.min) {
                        if(value < range.min-range.flat || value > range.max+range.flat) wrong++
                        if(range.excursion(value)) { excursedAxes += pair; neutralAxes -= pair }
                        if(range.neutral(value) && pair in excursedAxes) neutralAxes += pair
                        if(range.excursion(value) && pressed.any { it.first == e.device && it.second == 96 }) buttonAxisOverlap = true
                    }
                }
            }
        }
    }
    private fun touch(e: Raw) {
        if(!Sources.has(e.source, Sources.TOUCHSCREEN)) { wrong++; return }
        if(e.pointerCount != 1 || e.action == 5 || e.action == 6) touchSequenceValid = false
        val s = e.samples.lastOrNull() ?: return
        when(e.action) {
            0 -> { if(touchDown != null) touchSequenceValid = false; touchDown = e; maxTouchDistance = 0f; moves = 0 }
            2 -> {
                val d=touchDown
                val first=d?.samples?.lastOrNull()
                if(d==null || first==null || d.device!=e.device || d.downTime!=e.downTime) { wrong++; touchSequenceValid=false }
                else {
                    moves++
                    e.samples.forEach { sample -> maxTouchDistance=maxOf(maxTouchDistance,kotlin.math.hypot(sample.x-first.x,sample.y-first.y)) }
                }
            }
            3 -> { if(touchDown != null) cancels++ else wrong++; touchDown = null; touchSequenceValid = false }
            1 -> {
                val d = touchDown
                val first = d?.samples?.lastOrNull()
                if(d == null || first == null || d.device != e.device || d.downTime != e.downTime || e.time < d.time) {
                    wrong++; touchSequenceValid = false
                } else {
                    val expected = if(caseId == "LAB-11" && step == 1) endTarget else target
                    maxTouchDistance=maxOf(maxTouchDistance,kotlin.math.hypot(s.x-first.x,s.y-first.y))
                    if(expected.contains(first.x, first.y) && expected.contains(s.x, s.y) && maxTouchDistance<=touchSlopPx) {
                        tapPairs++; hits++; sceneRevision++
                        if(e.time-d.time in 0..500) shortTaps++
                        if(e.time-d.time >= 600) longPresses++
                        if(caseId == "LAB-11") step++
                    } else if(target.contains(first.x, first.y) && endTarget.contains(s.x, s.y) && e.time-d.time in 100..5000 && moves>0) {
                        swipes++; sceneRevision++
                    } else wrong++
                }
                touchDown = null
            }
        }
    }
    private fun key(e: Raw) {
        if(!Sources.controller(e.source)) { wrong++; return }
        val pair = e.device to e.key
        if(e.action == 0) {
            if(e.repeat == 0) {
                if(pair in pressed) wrong++ else { pressed += pair; downKeys += pair; keyDownTimes[pair] = e.downTime; sceneRevision++ }
                if(pressed.count { it.first == e.device } >= 2) overlap = true
            } else if(pair !in pressed) wrong++
        } else if(e.action == 1) {
            if(pair !in downKeys || pair !in pressed || keyDownTimes[pair] != e.downTime || e.time < e.downTime) wrong++ else { pairedKeys += e.key; pairCounts[e.key] = (pairCounts[e.key] ?: 0) + 1; sceneRevision++ }
            pressed -= pair
        } else wrong++
        if(caseId in setOf("LAB-06", "LAB-09") && e.key != expectedKey) wrong++
        if(caseId == "LAB-08" && e.key !in setOf(96,97)) wrong++
        if(caseId == "LAB-08-AXIS" && e.key != 96) wrong++
    }
    fun finish(now: Long): Verdict {
        check(active())
        phase = Phase.FINISHED
        if(now-started < durationMs) faults += "observation_window_short"
        if(faults.isNotEmpty()) return Verdict("inconclusive", faults.toList())
        if(touchDown != null || pressed.isNotEmpty()) return Verdict(if(stale) "inconclusive" else "fail", listOf("release_not_observed"))
        val axisPass = neutralAxes.map { it.first }.distinct().any { device -> requiredAxes.all { device to it in neutralAxes } }
        val axisDevices=deviceRanges.filterValues { ranges -> requiredAxes.all { axis -> ranges.any { it.axis==axis && Sources.has(it.source,Sources.JOYSTICK) } } }.keys
        if(caseId == "LAB-07" && !axisPass) {
            val absent=requiredAxes.filter { axis -> deviceRanges.values.none { ranges -> ranges.any { it.axis==axis && Sources.has(it.source,Sources.JOYSTICK) } } }
            val unresolved=requiredAxes.filter { axis -> neutralAxes.none { it.second==axis } }
            return Verdict(if(stale && excursedAxes.any { it !in neutralAxes }) "inconclusive" else "fail",listOf("axis_profile_unsatisfied","missingAxisIds=$absent","excursion_or_neutral_not_observed=$unresolved","sameDeviceProfileCandidates=$axisDevices"))
        }
        val ok = when(caseId) {
            "LAB-01" -> roleConfirmed && focusChanges > 0
            "LAB-02", "LAB-03", "LAB-14" -> shortTaps == 1 && tapPairs == 1 && hits == 1 && touchSequenceValid
            "LAB-04-LONG" -> longPresses == 1 && tapPairs == 1 && touchSequenceValid
            "LAB-04-SWIPE" -> swipes == 1 && tapPairs == 0 && touchSequenceValid
            "LAB-04-CANCEL" -> cancels == 1 && tapPairs == 0 && swipes == 0
            "LAB-05" -> semanticCount > 0
            "LAB-06" -> pairCounts[expectedKey] == 1
            "LAB-07" -> axisPass
            "LAB-08" -> overlap && pairedKeys.containsAll(setOf(96, 97)) && axes.all { (p,v) -> deviceRanges[p.first]?.find { it.axis == p.second }?.neutral(v) == true }
            "LAB-08-AXIS" -> buttonAxisOverlap && expectedKey in pairedKeys && neutralAxes.isNotEmpty() && axes.all { (p,v) -> deviceRanges[p.first]?.find { it.axis == p.second }?.neutral(v) == true }
            "LAB-09" -> focusChanges > 0 && pairCounts[expectedKey] == 1 && roleConfirmed
            "LAB-11" -> step == 2 && shortTaps == 2 && touchSequenceValid
            "LAB-13" -> return Verdict("inconclusive", listOf("qualification_fault_probe_not_input_success"))
            else -> return Verdict("blocked", listOf("unsupported_case"))
        }
        return if(ok && wrong == 0) Verdict("pass", listOf("receiver_assertions_satisfied")) else Verdict("fail", listOf("expected_evidence_missing_or_wrong", "wrong=$wrong raw=$rawCount hits=$hits semantic=$semanticCount"))
    }
    fun state() = mapOf("hits" to hits, "wrong" to wrong, "semanticCount" to semanticCount, "sceneRevision" to sceneRevision,
        "pressed" to pressed.map { listOf(it.first,it.second) }, "axes" to axes.map { (p,v) -> mapOf("deviceId" to p.first,"axis" to p.second,"value" to v) },
        "staleOrUnknown" to stale, "rawCount" to rawCount, "sampleCount" to sampleCount)
}
