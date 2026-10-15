package dev.thor.tools

/** Only prepares this application's fixed service; never accepts a caller-selected component. */
object AccessibilitySetup {
    fun mergedServices(current: String?, applicationPackage: String): String {
        requireTool(applicationPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")),Code.INVALID_ARGUMENT,"Invalid application package")
        val original=current.orEmpty().trim().takeUnless {it=="null"}.orEmpty()
        requireTool(original.length<=16384,Code.LIMIT_EXCEEDED,"Accessibility settings budget exceeded")
        val entries=original.split(':').filter {it.isNotBlank()}
        requireTool(entries.all {it.matches(Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))},Code.INVALID_ARGUMENT,"Invalid existing service component")
        return (entries+"$applicationPackage/dev.thor.tools.diagnostic.ThorAccessibilityService").distinct().joinToString(":")
    }
}
