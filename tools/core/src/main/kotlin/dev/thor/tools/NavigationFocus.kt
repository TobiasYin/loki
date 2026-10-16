package dev.thor.tools

/** Explicit-display navigation follows that display's focused app AND window.
 * Global FocusedDisplayId belongs to physical, unrouted controller input. */
object NavigationFocus {
    private val packageName=Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val row=Regex("^\\s*displayId=(\\d+), name='([^']+)'(?:,.*)?$")
    fun verify(dump:String,displayId:Int,expectedPackage:String?) {
        requireTool(displayId>=0 && expectedPackage!=null && packageName.matches(expectedPackage),message="Navigation requires a resolved display and package")
        val lines=dump.lineSequence().toList()
        val start=lines.indexOfFirst {it.trim()=="Input Dispatcher State:"}
        requireTool(start>=0,Code.TARGET_CHANGED,"Current input dispatcher state is absent")
        val active=lines.drop(start+1).takeWhile {it.trim()!="Input Dispatcher State at time of last ANR:"}
        fun focused(section:String):String {
            val headers=active.withIndex().filter {it.value.trim()=="$section:"}
            requireTool(headers.size==1,Code.TARGET_CHANGED,"Navigation focus section is absent or ambiguous")
            val entries=active.drop(headers.single().index+1).takeWhile {it.isBlank() || it.startsWith("    ")}
            val matches=entries.filter {it.isNotBlank()}.map {line->
                row.matchEntire(line) ?: fail(Code.TARGET_CHANGED,"Unrecognized focused-display record")
            }.filter {it.groupValues[1].toIntOrNull()==displayId}
            requireTool(matches.size==1,Code.TARGET_CHANGED,"Selected display has no unique focused $section record")
            return matches.single().groupValues[2]
        }
        val application=focused("FocusedApplications")
        val apps=Regex("\\bu\\d+ ([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+)/[A-Za-z0-9_.$]+").findAll(application).toList()
        requireTool(application.startsWith("ActivityRecord{") && apps.size==1 && apps.single().groupValues[1]==expectedPackage,
            Code.TARGET_CHANGED,"Focused selected-screen application is not the authorized navigation target")
        val window=focused("FocusedWindows")
        val name=Regex("^[0-9a-fA-F]+ ([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+)(?:/[A-Za-z0-9_.$]+)?$").matchEntire(window)
        requireTool(name!=null && name.groupValues[1]==expectedPackage,Code.TARGET_CHANGED,"Focused selected-screen window is not the authorized navigation target")
    }
}
