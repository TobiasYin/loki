package dev.thor.tools

/** Accessibility actions target a currently visible window, not the global
 * keyboard-focus owner. Snapshot role/epoch/structure and exact node identity
 * remain independently checked by the caller before actual submission. */
object UiActionWindow {
    private fun intersects(a:TouchableBounds,b:TouchableBounds)=maxOf(a.left,b.left)<minOf(a.right,b.right) && maxOf(a.top,b.top)<minOf(a.bottom,b.bottom)
    fun select(display:DisplayFact,observed:Target,windowId:Int,packageName:String?,windows:List<TargetWindowEvidence>):Int {
        requireTool(observed.validity=="valid" && !packageName.isNullOrBlank() && observed.packageName==packageName,
            Code.TARGET_CHANGED,"Semantic action requires the observed unambiguous application")
        val clip=observed.touchableBounds
        requireTool(clip!=null && clip.left>=0 && clip.top>=0 && clip.right<=display.width && clip.bottom<=display.height && clip.left<clip.right && clip.top<clip.bottom,
            Code.TARGET_CHANGED,"Semantic action requires the exact visible input region")
        requireTool(windows.map {it.id}.distinct().size==windows.size,Code.TARGET_CHANGED,"Semantic window evidence is ambiguous")
        val screen=TouchableBounds(0,0,display.width,display.height)
        val applications=windows.filter {it.type==TargetWindowEvidence.APPLICATION && it.region.any {r->intersects(r,screen)}}
        requireTool(applications.size==1,Code.TARGET_CHANGED,"Semantic action requires exactly one visible application window on the selected screen")
        val app=applications.single()
        requireTool(app.id==windowId && app.packageName==packageName && app.rootBounds!=null && app.rectangular && app.region.size==1,
            Code.TARGET_CHANGED,"Semantic window identity/package/visible region changed")
        // Reuse the production target resolver's occlusion, root/window bounds,
        // screen clipping and sole safe rectangle rules. Focus is part of the
        // observed structure, but is not itself required to be true.
        val current=UpperTargetResolver.resolve(display,windows,TargetWindowRevisions())
        requireTool(current.validity=="valid" && current.packageName==packageName && current.touchableBounds==clip,
            Code.TARGET_CHANGED,"Semantic window is no longer the same unoccluded observed target")
        return app.id
    }
}
