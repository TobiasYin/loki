package dev.thor.inputlab

/** Only explicit platform or full-agent attribution is available to the debug runner. */
object RunnerEvidenceMode {
    const val DEFAULT = "agent_e2e"
    fun parse(value: Any? = DEFAULT): String {
        require(value is String && value in setOf(DEFAULT,"manual_platform_api")) {
            "evidenceMode must be agent_e2e or manual_platform_api"
        }
        return value
    }
}

/** Bounded preparation/report lease; it never extends the receiver's input deadline. */
class RunnerWindow private constructor(val observationSeconds: Int) {
    val leaseDurationMs: Long = (observationSeconds.toLong() + 10L) * 1000L

    companion object {
        fun parse(value: Any? = null): RunnerWindow {
            require(value == null || value is Int) { "observationSeconds must be an integer" }
            val seconds = value as? Int ?: 5
            require(seconds in 5..30) { "observationSeconds must be between 5 and 30" }
            return RunnerWindow(seconds)
        }
    }
}
