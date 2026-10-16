package dev.thor.tools.diagnostic

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import dev.thor.tools.Target
import dev.thor.tools.TouchableBounds
import dev.thor.tools.TargetWindowEvidence
import dev.thor.tools.TargetWindowRevisions
import dev.thor.tools.TargetEventKind
import dev.thor.tools.UpperTargetResolver

class ThorAccessibilityService : AccessibilityService() {
    private val revisions=TargetWindowRevisions()
    override fun onServiceConnected() { instance=this }
    private fun evidence(window:AccessibilityWindowInfo,readSystemRoot:Boolean=false,readAppRoot:Boolean=true):TargetWindowEvidence {
        val rect=Rect();window.getBoundsInScreen(rect)
        val region=Region();window.getRegionInScreen(region)
        val pieces=mutableListOf<TouchableBounds>();val iterator=RegionIterator(region);val piece=Rect()
        while(iterator.next(piece))pieces.add(TouchableBounds(piece.left,piece.top,piece.right,piece.bottom))
        var rootBounds:TouchableBounds?=null;var pkg:String?=null
        if(window.type==AccessibilityWindowInfo.TYPE_APPLICATION && readAppRoot || readSystemRoot)window.root?.let {node->
            try {
                if(node.refresh()) {
                    val root=Rect();node.getBoundsInScreen(root);rootBounds=TouchableBounds(root.left,root.top,root.right,root.bottom)
                    pkg=node.packageName?.toString()
                }
            }finally { @Suppress("DEPRECATION") node.recycle() }
        }
        return TargetWindowEvidence(window.id,window.type,window.layer,window.isFocused,window.isActive,
            TouchableBounds(rect.left,rect.top,rect.right,rect.bottom),pieces,region.isRect,pkg,rootBounds)
    }
    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(event==null)return
        val kind=TargetEventKind.fromAndroidType(event.eventType)
        val all=windowsOnAllDisplays
        val current=linkedMapOf<Int,List<TargetWindowEvidence>>()
        for(index in 0 until all.size()) {
            val display=all.keyAt(index)
            // Only an exact system window is eligible for the content-only exclusion.
            // Never trust the event's package string without corroborating its root.
            current[display]=all.valueAt(index).map {window->evidence(window,kind==TargetEventKind.CONTENT && window.id==event.windowId && window.type==AccessibilityWindowInfo.TYPE_SYSTEM,readAppRoot=false)}
        }
        revisions.event(kind,event.windowId,event.packageName?.toString(),current)
    }
    fun target(display:Int):Target {
        val fact=displayFacts(this).singleOrNull {it.id==display} ?: return Target(null,"unknown","display-missing")
        return UpperTargetResolver.resolve(fact,windowsOnAllDisplays[display].orEmpty().map {evidence(it)},revisions)
    }
    override fun onInterrupt() { invalidated?.invoke() }
    override fun onUnbind(intent:android.content.Intent?):Boolean { instance=null;invalidated?.invoke();return super.onUnbind(intent) }
    override fun onDestroy() { instance=null;invalidated?.invoke();super.onDestroy() }
    companion object {
        @Volatile var instance:ThorAccessibilityService?=null
        @Volatile var invalidated:(()->Unit)?=null
    }
}
