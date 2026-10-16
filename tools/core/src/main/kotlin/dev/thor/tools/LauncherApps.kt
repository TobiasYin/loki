package dev.thor.tools

/** Only public, enabled MAIN/LAUNCHER activities enter this projection. */
data class LaunchableApp(val packageName:String,val activityName:String,val label:String)
data class FindAppsQuery(val query:String,val limit:Int) {
    companion object {
        fun parse(args:Map<String,Any?>):FindAppsQuery {
            requireTool(args.keys.all {it in setOf("query","limit")},message="Unknown application search parameter")
            val query=args["query"] as? String ?: fail(Code.INVALID_ARGUMENT,"A nonempty application query is required")
            requireTool(query.length in 1..100 && query.isNotBlank() && query.none(Char::isISOControl),message="Application query must be nonempty and at most 100 characters")
            val number=(args["limit"] ?: 10) as? Number ?: fail(Code.INVALID_ARGUMENT,"Application result limit must be an integer")
            val limit=number.toDouble()
            requireTool(limit.isFinite() && limit==kotlin.math.floor(limit) && limit in 1.0..20.0,message="Application result limit must be 1..20")
            return FindAppsQuery(query.trim(),limit.toInt())
        }
    }
}
data class LaunchAppCommand(val packageName:String) {
    companion object {
        fun parse(args:Map<String,Any?>,ownPackages:Set<String>):LaunchAppCommand {
            requireTool(args.keys==setOf("packageName"),message="Launch accepts only a packageName; no components, URI, extras or display override")
            val name=args["packageName"] as? String ?: fail(Code.INVALID_ARGUMENT,"Application packageName is required")
            requireTool(name.length in 3..255 && name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")),message="Invalid application packageName")
            requireTool(name !in ownPackages,Code.COMMAND_BLOCKED,"The assistant cannot move its own chat onto the upper display")
            return LaunchAppCommand(name)
        }
    }
}
object LauncherApps {
    fun matches(app:LaunchableApp,query:String)=listOf(app.label,app.packageName,app.activityName).any {it.contains(query,ignoreCase=true)}
    fun find(apps:List<LaunchableApp>,query:FindAppsQuery):List<LaunchableApp> = apps.asSequence()
        .filter {matches(it,query.query)}.distinctBy {it.packageName to it.activityName}
        .sortedWith(compareBy<LaunchableApp> {it.label}.thenBy {it.packageName}.thenBy {it.activityName}).take(query.limit).toList()
}
