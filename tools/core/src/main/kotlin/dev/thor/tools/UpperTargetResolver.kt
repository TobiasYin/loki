package dev.thor.tools

/** Observed Android window evidence, excluding titles/text/style from system window identity. */
data class TargetWindowEvidence(val id:Int,val type:Int,val layer:Int,val focused:Boolean,val active:Boolean,
    val bounds:TouchableBounds,val region:List<TouchableBounds>,val rectangular:Boolean,val packageName:String?,val rootBounds:TouchableBounds?,
    val presentation:PresentationWindowProof?=null) {
    fun isApplicationOn(display:Int)=type==APPLICATION || presentation?.matches(this,display)==true
    companion object {const val APPLICATION=1;const val SYSTEM=3}
}
enum class TargetEventKind {
    CONTENT, APP_CONTENT, WINDOW_METADATA, INTERACTION_LAYOUT, STRUCTURE;
    companion object {
        /** Android public AccessibilityEvent constants; kept platform-free for guard regression tests. */
        fun fromAndroidType(type:Int,windowChanges:Int=0,contentChangeTypes:Int=0)=when(type) {
            // A real scroll or subtree replacement may move/recycle controls even
            // when the application window rectangle remains unchanged.
            0x00001000->INTERACTION_LAYOUT // VIEW_SCROLLED
            0x00000800->if(contentChangeTypes and 1 != 0)INTERACTION_LAYOUT else CONTENT
            0x00000010->CONTENT // VIEW_TEXT_CHANGED
            0x00400000->if(windowChanges!=0 && windowChanges and (4 or 128).inv()==0)WINDOW_METADATA else STRUCTURE
            0x00000020->STRUCTURE // WINDOW_STATE_CHANGED
            else->APP_CONTENT // clicked/selected/focused/caret feedback
        }
    }
}
/** Content freshness belongs to application windows; pure SystemUI bar text is not app content.
 * Structural events and geometry remain independent and cannot be hidden by this filter. */
class TargetWindowRevisions {
    private val content=LinkedHashMap<Int,Long>()
    private val structure=LinkedHashMap<Int,Long>()
    private val interaction=LinkedHashMap<Int,Long>()
    private val displays=LinkedHashMap<Int,Int>()
    private var eventSequence=0L
    private val eventDiagnostics=java.util.ArrayDeque<Map<String,Any>>()
    @Synchronized fun diagnostics():List<Map<String,Any>> = eventDiagnostics.toList()
    @Synchronized fun observe(display:Int,windows:List<TargetWindowEvidence>) {
        windows.forEach {displays[it.id]=display}
        while(displays.size>512){val id=displays.keys.first();displays.remove(id);content.remove(id);interaction.remove(id)}
    }
    @Synchronized fun event(kind:TargetEventKind,windowId:Int,eventPackage:String?,current:Map<Int,List<TargetWindowEvidence>>,eventType:Int=0) {
        current.forEach { (display,windows)->observe(display,windows) }
        val display=displays[windowId]
        val window=current[display]?.singleOrNull {it.id==windowId}
        val corroborated=window?.type==TargetWindowEvidence.SYSTEM && window.packageName=="com.android.systemui" && eventPackage=="com.android.systemui"
        val application=display!=null && window?.isApplicationOn(display)==true
        val beforeStructure=structure[display] ?: 0L
        val beforeInteraction=interaction[windowId] ?: 0L
        fun record(filtered:Boolean) {
            if(eventDiagnostics.size>=16)eventDiagnostics.removeFirst()
            eventDiagnostics.addLast(mapOf("sequence" to ++eventSequence,"kind" to kind.name,"eventType" to eventType,
                "scopeResolved" to (display!=null),"systemUiCorroborated" to corroborated,"isApplication" to application,
                "filteredSystemUi" to filtered,"structureChanged" to ((structure[display] ?: 0L)!=beforeStructure),
                "interactionChanged" to ((interaction[windowId] ?: 0L)!=beforeInteraction)))
        }
        if(display==null){record(false);return}
        // Internal SystemUI node content/focus/scroll/subtree feedback is outside
        // the application's input region. Only a unique exact SYSTEM window,
        // corroborated root package and matching event package qualify. Real
        // window events and live window/focus/geometry evidence remain strict.
        if(kind!=TargetEventKind.STRUCTURE && corroborated){record(true);return}
        if(kind==TargetEventKind.INTERACTION_LAYOUT && window?.isApplicationOn(display)==true)
            interaction[windowId]=(interaction[windowId] ?: 0)+1
        // Public title/accessibility-focus-only window flags do not imply a
        // window replacement, including on SystemUI/non-application windows.
        // Live evidence still carries actual window/focus/geometry/owner facts.
        if(kind==TargetEventKind.WINDOW_METADATA || (kind!=TargetEventKind.STRUCTURE && window?.isApplicationOn(display)==true))content[windowId]=(content[windowId] ?: 0)+1
        else structure[display]=(structure[display] ?: 0)+1
        record(false)
    }
    @Synchronized fun content(window:Int)=content[window] ?: 0L
    @Synchronized fun structure(display:Int)=structure[display] ?: 0L
    @Synchronized fun interaction(window:Int)=interaction[window] ?: 0L
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
        val applications=windows.filter {it.isApplicationOn(display.id)}
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
            val app=if(window.isApplicationOn(display.id))":package:${window.packageName}:root:${window.rootBounds}:presentation:${window.presentation?.identity()}" else ""
            "${window.id}:${window.type}:${window.layer}:${window.focused}:${window.active}:$geometry$app"
        }+":structure:${revisions.structure(display.id)}"
        val content=applications.joinToString(";") {"${it.id}:${revisions.content(it.id)}"}
        val interaction=applications.joinToString(";") {"${it.id}:${revisions.interaction(it.id)}"}
        return Target(packages.singleOrNull(),validity,"$layout:app-content:$content",clip,layout,interaction)
    }
}
