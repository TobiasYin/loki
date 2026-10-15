package dev.thor.inputlab

/** Public configuration continuity only: this does not certify InputReader routing or qualification. */
class DeviceContinuity(
    private val devices: Map<Int, Map<String, Any?>>,
    private val window: Map<String, Any?>,
    private val displays: List<Map<String, Any?>>
) {
    data class Decision(val reasons: List<String>) {
        val continuous get() = reasons.isEmpty()
        fun map() = mapOf("policy" to "unchanged_public_configuration_when_idle_v2",
            "disposition" to if(continuous) "diagnostic_only" else "inconclusive",
            "reasons" to reasons, "routingProven" to false)
    }

    fun assess(id: Int, change: String, current: Map<Int, Map<String, Any?>>,
        currentWindow: Map<String, Any?>, currentDisplays: List<Map<String, Any?>>,
        inputHeld: Boolean, priorUncertainty: Boolean): Decision {
        val reasons = linkedSetOf<String>()
        if(change != "changed") reasons += "input_device_$change"
        if(devices[id]?.get("available") != true || current[id]?.get("available") != true)
            reasons += "input_device_snapshot_unavailable"
        if(current.keys != devices.keys) reasons += "input_device_set_changed"
        if(devices.any { (key, value) -> value["available"] != true || current[key] != value })
            reasons += "input_device_configuration_changed"
        if(window["hasWindowFocus"] != true || window["lifecycle"] != "resumed" ||
            currentWindow != window || currentDisplays != displays) reasons += "input_device_environment_changed"
        if(inputHeld) reasons += "input_device_changed_while_input_held"
        if(priorUncertainty) reasons += "input_device_prior_uncertainty"
        return Decision(reasons.toList())
    }
}

data class AxisProfile(val name: String, val axes: Set<Int>, val deviceId: Int? = null) {
    fun map() = mapOf("name" to name, "requiredAxisIds" to axes.toList(), "deviceId" to deviceId,
        "selection" to "prepare_metadata_only_frozen_before_input")
    companion object {
        val standard = AxisProfile("standard_xy_zrz_ltrigger_rtrigger", linkedSetOf(0,1,11,14,17,18))
        fun select(manufacturer: String, model: String, devices: Map<Int, Map<String, Any?>>, ranges: Map<Int, List<Range>>): AxisProfile {
            val thorAxes = linkedSetOf(0,1,11,14,23,22)
            if(manufacturer != "AYN" || model != "AYN Thor") return standard
            val candidates = devices.filter { (id, d) ->
                d["available"] == true && d["name"] == "Odin Controller" && d["vendorId"] == 8224 &&
                    d["productId"] == 273 && d["isVirtual"] == false && d["isEnabled"] == true &&
                    Sources.has(d["sources"] as? Int ?: 0, Sources.JOYSTICK) &&
                    thorAxes.all { axis -> ranges[id].orEmpty().any { it.axis == axis && Sources.has(it.source, Sources.JOYSTICK) && it.max > it.min } }
            }
            return if(candidates.size == 1) AxisProfile("ayn_thor_odin_xy_zrz_brake_gas", thorAxes, candidates.keys.single()) else standard
        }
    }
}
