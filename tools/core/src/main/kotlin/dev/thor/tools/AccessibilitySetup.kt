package dev.thor.tools

/** Only prepares this application's fixed service; never accepts a caller-selected component. */
object AccessibilitySetup {
    /** Read-only health classification: a saved setting is not a live service. */
    fun availability(enabled:Boolean,connected:Boolean,recoveryPending:Boolean,backend:String):Availability = when {
        connected -> Availability(Status.READY,"本应用无障碍已连接",backend)
        recoveryPending -> Availability(Status.TEMPORARILY_UNAVAILABLE,"本应用无障碍正在通过已授权的 Shizuku 自动恢复，等待系统连接；无需重复授权",backend)
        enabled -> Availability(Status.TEMPORARILY_UNAVAILABLE,"本应用无障碍已开启，正在等待系统连接或恢复；无需重复授权",backend)
        else -> Availability(Status.PERMISSION_MISSING,"本应用无障碍尚未开启，请在设置中授权",backend)
    }
    fun mergedServices(current: String?, applicationPackage: String): String {
        requireTool(applicationPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")),Code.INVALID_ARGUMENT,"Invalid application package")
        val original=current.orEmpty().trim().takeUnless {it=="null"}.orEmpty()
        requireTool(original.length<=16384,Code.LIMIT_EXCEEDED,"Accessibility settings budget exceeded")
        val entries=original.split(':').filter {it.isNotBlank()}
        requireTool(entries.all {it.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))},Code.INVALID_ARGUMENT,"Invalid existing service component")
        return (entries+"$applicationPackage/dev.thor.tools.diagnostic.ThorAccessibilityService").distinct().joinToString(":")
    }
}
