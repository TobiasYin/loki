package dev.thor.agent

/** Transporting a call to Native is distinct from dispatching it to Android.
 * Only the Gateway checkpoint's explicit receipt can prove no dispatch. */
internal object ToolSubmission {
    fun fromGateway(outcome: String): String = when (outcome) {
        "not_submitted" -> "not_dispatched"
        "submitted" -> "submitted"
        else -> "unknown"
    }

    /** Fixed local guard diagnostics; arbitrary backend text never becomes a hint. */
    fun targetDiagnostic(message: String?): String? = when (message) {
        "Snapshot missing/invalidated", "Observation missing/invalidated" -> "snapshot_missing"
        "Display snapshot expired/changed", "Display observation expired/changed" -> "snapshot_expired_or_display_changed"
        "Selected screen windows/app/layout changed", "Observed app/windows/geometry changed" -> "input_layout_changed"
        "Frame missing/evicted" -> "frame_missing"
        "Frame must belong to this current snapshot" -> "frame_snapshot_mismatch"
        "Input touch region evidence changed", "Current touchable region missing" -> "input_clip_changed"
        "Input leaves observed/current touchable region" -> "input_outside_clip"
        "Expected app does not match selected screen" -> "expected_package_mismatch"
        else -> null
    }
}
