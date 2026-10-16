package dev.thor.tools.diagnostic

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import dev.thor.tools.Target
import dev.thor.tools.TouchableBounds
import dev.thor.tools.FullScreenGeometry
import android.graphics.Rect
import java.util.concurrent.ConcurrentHashMap

class ThorAccessibilityService : AccessibilityService() {
    private val revisions = ConcurrentHashMap<Int, Long>()
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) {
            val display = all.keyAt(i)
            if (all.valueAt(i).any { it.id == event.windowId }) revisions.merge(display, 1L, Long::plus)
        }
    }
    fun target(display: Int): Target {
        val windows = windowsOnAllDisplays[display].orEmpty()
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        var soleRoot:TouchableBounds?=null
        val rootBoundsByWindow=mutableMapOf<Int,TouchableBounds>()
        val packages = apps.mapNotNull { window -> window.root?.let { node ->
            try {
                if(!node.refresh())return@let null
                val rootBounds=Rect();node.getBoundsInScreen(rootBounds)
                val root=TouchableBounds(rootBounds.left,rootBounds.top,rootBounds.right,rootBounds.bottom)
                rootBoundsByWindow[window.id]=root
                if(apps.size==1)soleRoot=root
                node.packageName?.toString()
            } finally { @Suppress("DEPRECATION") node.recycle() }
        } }.distinct()
        val soleRegion=if(windows.size==1 && apps.size==1)android.graphics.Region().also {apps.single().getRegionInScreen(it)} else null
        val visible=soleRegion?.bounds?.let {TouchableBounds(it.left,it.top,it.right,it.bottom)}
        val canonical=displayFacts(this).singleOrNull {it.id==display}?.let {FullScreenGeometry.canonicalRoot(it,windows.size,apps.size,soleRoot,visible,soleRegion?.isRect==true)}
        // Structural changes terminate a held controller action. Text/content
        // callbacks caused by that action must not be mistaken for a new layout.
        val layoutRevision = windows.joinToString(";") { window ->
            val bounds = Rect(); window.getBoundsInScreen(bounds)
            val geometry=canonical?.let {"root-layout:$it"} ?: "window:$bounds"
            "${window.id}:${window.type}:${window.layer}:${window.isFocused}:${window.isActive}:$geometry:root:${rootBoundsByWindow[window.id]}"
        }
        val revision="$layoutRevision:${revisions[display] ?: 0}"
        val validity = when { packages.size > 1 -> "ambiguous"; packages.isEmpty() -> "unknown"; else -> "valid" }
        return Target(packages.singleOrNull(), validity, revision,if(canonical!=null)visible else null,layoutRevision)
    }
    override fun onInterrupt() { invalidated?.invoke() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; invalidated?.invoke(); return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; invalidated?.invoke(); super.onDestroy() }
    companion object {
        @Volatile var instance: ThorAccessibilityService? = null
        @Volatile var invalidated: (() -> Unit)? = null
    }
}
