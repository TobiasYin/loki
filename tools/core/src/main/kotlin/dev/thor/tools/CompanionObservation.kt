package dev.thor.tools

/** A read-only companion identity. It is NEVER inserted in the upper input snapshot registry. */
data class CompanionObservation(val id:String,val upper:Snapshot,val lowerDisplay:DisplayFact,val lowerTarget:Target,val expires:Long)
data class CompanionWindowState(val displayId:Int,val windowId:Int,val packageName:String,val clip:TouchableBounds,val windows:List<TargetWindowEvidence>)
object CompanionWindows {
    private fun intersect(a:TouchableBounds,b:TouchableBounds)=TouchableBounds(maxOf(a.left,b.left),maxOf(a.top,b.top),minOf(a.right,b.right),minOf(a.bottom,b.bottom))
    private fun valid(r:TouchableBounds)=r.left<r.right && r.top<r.bottom
    fun verify(display:DisplayFact,upperPackage:String?,windows:List<TargetWindowEvidence>,forbidden:Set<String>):CompanionWindowState {
        requireTool(!upperPackage.isNullOrBlank() && upperPackage !in forbidden,Code.PERMISSION_MISSING,"Companion capture cannot observe the assistant or an unknown app")
        val screen=TouchableBounds(0,0,display.width,display.height)
        fun visible(w:TargetWindowEvidence)=w.region.any {valid(intersect(it,screen))}
        val apps=windows.filter {it.isApplicationOn(display.id) && visible(it)}
        requireTool(apps.size==1,Code.TARGET_CHANGED,"Companion requires exactly one visible application window")
        val app=apps.single()
        requireTool(app.packageName==upperPackage && app.packageName !in forbidden,Code.PERMISSION_MISSING,"Companion window does not belong to the authorized upper app")
        val region=app.region.singleOrNull()
        requireTool(app.rectangular && region!=null && app.rootBounds!=null,Code.TARGET_CHANGED,"Companion application has no unambiguous rectangular visible region")
        val clip=intersect(intersect(intersect(region!!,app.rootBounds!!),app.bounds),screen)
        requireTool(valid(clip),Code.TARGET_CHANGED,"Companion application is not visibly capturable")
        // Only app pixels are encoded. Any overlapping system/IME/other overlay
        // rejects capture instead of exposing a sensitive system surface.
        requireTool(windows.filter {it.id!=app.id}.none {other->
            (if(other.isApplicationOn(display.id))other.region else other.region.ifEmpty {listOf(other.bounds)}).any {valid(intersect(it,clip))}
        },Code.SECURE_CONTENT,"Companion application is occluded by another window; no screenshot fallback")
        return CompanionWindowState(display.id,app.id,upperPackage!!,clip,windows.toList())
    }
}
