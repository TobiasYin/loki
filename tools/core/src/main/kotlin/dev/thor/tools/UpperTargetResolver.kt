package dev.thor.tools

/** Observed Android window evidence, excluding titles/text/style from system window identity. */
data class TargetWindowEvidence(val id:Int,val type:Int,val layer:Int,val focused:Boolean,val active:Boolean,
    val bounds:TouchableBounds,val region:List<TouchableBounds>,val rectangular:Boolean,val packageName:String?,val rootBounds:TouchableBounds?) {
    companion object {const val APPLICATION=1;const val SYSTEM=3}
}
enum class TargetEventKind {
    CONTENT, APP_CONTENT, STRUCTURE;
    companion object {
        /** Android public AccessibilityEvent constants; kept platform-free for guard regression tests. */
        fun fromAndroidType(type:Int)=when(type) {
            0x00000800,0x00000010->CONTENT // WINDOW_CONTENT_CHANGED, VIEW_TEXT_CHANGED
            0x00000020,0x00400000->STRUCTURE // WINDOW_STATE_CHANGED, WINDOWS_CHANGED
            else->APP_CONTENT // clicked/scrolled/selected/focused etc. remain app content, never a system exclusion.
        }
    }
}
/** Content freshness belongs to application windows; pure SystemUI bar text is not app content.
 * Structural events and geometry remain independent and cannot be hidden by this filter. */
class TargetWindowRevisions {
    private val content=LinkedHashMap<Int,Long>()
    private val structure=LinkedHashMap<Int,Long>()
    private val displays=LinkedHashMap<Int,Int>()
    @Synchronized fun observe(display:Int,windows:List<TargetWindowEvidence>) {
        windows.forEach {displays[it.id]=display}
        while(displays.size>512){val id=displays.keys.first();displays.remove(id);content.remove(id)}
    }
    @Synchronized fun event(kind:TargetEventKind,windowId:Int,eventPackage:String?,current:Map<Int,List<TargetWindowEvidence>>) {
        current.forEach { (display,windows)->observe(display,windows) }
        val display=displays[windowId] ?: return
        val window=current[display]?.singleOrNull {it.id==windowId}
        if(kind==TargetEventKind.CONTENT && window?.type==TargetWindowEvidence.SYSTEM && window.packageName=="com.android.systemui" && eventPackage=="com.android.systemui")return
        if(kind!=TargetEventKind.STRUCTURE && window?.type==TargetWindowEvidence.APPLICATION)content[windowId]=(content[windowId] ?: 0)+1
        else structure[display]=(structure[display] ?: 0)+1
    }
    @Synchronized fun content(window:Int)=content[window] ?: 0L
    @Synchronized fun structure(display:Int)=structure[display] ?: 0L
}
object UpperTargetResolver {
    private val empty=TouchableBounds(0,0,0,0)
    private fun intersection(a:TouchableBounds,b:TouchableBounds):TouchableBounds {
        val result=TouchableBounds(maxOf(a.left,b.left),maxOf(a.top,b.top),minOf(a.right,b.right),minOf(a.bottom,b.bottom))
        return result.takeIf {it.left<it.right && it.top<it.bottom} ?: empty
    }
    private fun subtract(rect:TouchableBounds,blocker:TouchableBounds):List<TouchableBounds> {
        val overlap=intersection(rect,blocker)
        if(overlap==empty)return listOf(rect)
        return listOf(TouchableBounds(rect.left,rect.top,rect.right,overlap.top),
            TouchableBounds(rect.left,overlap.bottom,rect.right,rect.bottom),
            TouchableBounds(rect.left,overlap.top,overlap.left,overlap.bottom),
            TouchableBounds(overlap.right,overlap.top,rect.right,overlap.bottom)).filter {it.left<it.right && it.top<it.bottom}
    }
    fun resolve(display:DisplayFact,windows:List<TargetWindowEvidence>,revisions:TargetWindowRevisions):Target {
        revisions.observe(display.id,windows)
        val applications=windows.filter {it.type==TargetWindowEvidence.APPLICATION}
        val packages=applications.mapNotNull {it.packageName}.distinct()
        val validity=when {packages.size>1->"ambiguous";packages.isEmpty()->"unknown";else->"valid"}
        val selected=applications.singleOrNull() ?: applications.filter {it.focused}.singleOrNull()
        val region=selected?.region?.singleOrNull()?.takeIf {selected.rectangular}
        val canonical=if(selected!=null)FullScreenGeometry.canonicalRoot(display,windows.size,applications.size,selected.rootBounds,region,selected.rectangular) else null
        // Empty is deliberately not null: unknown/nonrectangular visibility must reject input,
        // rather than falling back to the entire display (including status/navigation bars).
        val initialClip=if(validity=="valid" && selected?.rootBounds!=null && region!=null)
            intersection(intersection(intersection(region,selected.rootBounds),selected.bounds),TouchableBounds(0,0,display.width,display.height)) else empty
        var safePieces=if(initialClip==empty)emptyList() else listOf(initialClip)
        if(selected!=null)for(other in windows.filter {it.id!=selected.id && it.layer>=selected.layer}) {
            for(blocker in other.region.ifEmpty {listOf(other.bounds)})safePieces=safePieces.flatMap {subtract(it,blocker)}
        }
        // Edge-to-edge app roots may include system bars. Exclude higher/equal-layer
        // regions explicitly. A hole or ambiguous occlusion cannot become a full-display fallback.
        val clip=safePieces.singleOrNull() ?: empty
        val layout=windows.joinToString(";") {window->
            val geometry=if(canonical!=null && window.id==selected?.id)"canonical:$canonical" else "bounds:${window.bounds}:region:${window.rectangular}:${window.region}"
            val app=if(window.type==TargetWindowEvidence.APPLICATION)":package:${window.packageName}:root:${window.rootBounds}" else ""
            "${window.id}:${window.type}:${window.layer}:${window.focused}:${window.active}:$geometry$app"
        }+":structure:${revisions.structure(display.id)}"
        val content=applications.joinToString(";") {"${it.id}:${revisions.content(it.id)}"}
        return Target(packages.singleOrNull(),validity,"$layout:app-content:$content",clip,layout)
    }
}
