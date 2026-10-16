package dev.thor.tools

object ScreenSelection {
    fun parse(args:Map<String,Any?>):String {
        requireTool(args.keys==setOf("screen") && args["screen"] in setOf("upper","lower"),message="Select screen accepts only screen=upper or lower")
        return args["screen"] as String
    }
    fun verifyResult(source:Snapshot,selected:Snapshot,data:Map<String,Any?>) {
        requireTool(selected.displayEpoch==source.displayEpoch && selected.id!=source.id &&
            data["selectedScreen"]==selected.role && data["displayRole"]==selected.role && data["displayId"]==selected.display.id &&
            data["targetSnapshotId"]==selected.id && data["readOnlySelection"]==true && data["calibrationChanged"]==false,
            Code.TARGET_CHANGED,"Screen selection must issue a new calibrated per-run role observation without recalibration")
    }
}
