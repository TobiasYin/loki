package dev.thor.tools.diagnostic

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import dev.thor.tools.Target
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
        val packages = apps.mapNotNull { window -> window.root?.let { node ->
            try { node.packageName?.toString() } finally { @Suppress("DEPRECATION") node.recycle() }
        } }.distinct()
        val revision = windows.joinToString(";") { window ->
            val bounds = Rect(); window.getBoundsInScreen(bounds)
            "${window.id}:${window.type}:${window.layer}:${window.isFocused}:$bounds"
        } + ":${revisions[display] ?: 0}"
        val validity = when { packages.size > 1 -> "ambiguous"; packages.isEmpty() -> "unknown"; else -> "valid" }
        return Target(packages.singleOrNull(), validity, revision)
    }
    override fun onInterrupt() { invalidated?.invoke() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; invalidated?.invoke(); return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; invalidated?.invoke(); super.onDestroy() }
    companion object {
        @Volatile var instance: ThorAccessibilityService? = null
        @Volatile var invalidated: (() -> Unit)? = null
    }
}
