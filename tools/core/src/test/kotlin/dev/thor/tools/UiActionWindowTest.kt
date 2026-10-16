package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class UiActionWindowTest {
    private val display=DisplayFact(7,"selected-screen",800,600,0,160)
    private val full=TouchableBounds(0,0,800,600)
    private fun app(id:Int=21,pkg:String="receiver.app",focused:Boolean=false,region:List<TouchableBounds> = listOf(full))=
        TargetWindowEvidence(id,TargetWindowEvidence.APPLICATION,2,focused,false,full,region,true,pkg,full)
    private fun observed(windows:List<TargetWindowEvidence>)=UpperTargetResolver.resolve(display,windows,TargetWindowRevisions())
    private fun rejected(block:()->Unit){try {block();fail("Unsafe semantic window accepted")}catch(e:ToolFailure){assertEquals(Code.TARGET_CHANGED,e.code)}}
    @Test fun soleVisibleBoundApplicationDoesNotNeedGlobalKeyboardFocus() {
        for(focused in listOf(false,true)) {
            val windows=listOf(app(focused=focused));assertEquals(21,UiActionWindow.select(display,observed(windows),21,"receiver.app",windows))
        }
    }
    @Test fun windowPackageValidityAndDuplicateIdentityCannotBeSubstituted() {
        val windows=listOf(app());val target=observed(windows)
        rejected {UiActionWindow.select(display,target,22,"receiver.app",windows)}
        rejected {UiActionWindow.select(display,target,21,"different.app",windows)}
        rejected {UiActionWindow.select(display,target.copy(validity="ambiguous"),21,"receiver.app",windows)}
        rejected {UiActionWindow.select(display,target,21,"receiver.app",windows+windows)}
        rejected {UiActionWindow.select(display,target,21,"receiver.app",listOf(app(pkg="different.app")))}
    }
    @Test fun focusedCandidateCannotHideASecondVisibleApplicationWindow() {
        val first=app(focused=true);val target=observed(listOf(first))
        for(other in listOf(app(id=22),app(id=22,pkg="different.app"),app(id=22,region=listOf(TouchableBounds(700,0,800,100)))))
            rejected {UiActionWindow.select(display,target,21,"receiver.app",listOf(first,other))}
    }
    @Test fun hiddenOffscreenNonRectangularOrRootlessWindowsAreNotTargets() {
        val target=observed(listOf(app()))
        for(bad in listOf(app(region=emptyList()),app(region=listOf(TouchableBounds(900,0,1000,100))),app().copy(rectangular=false),
            app().copy(rootBounds=null),app().copy(type=TargetWindowEvidence.SYSTEM),app(region=listOf(TouchableBounds(0,0,100,100),TouchableBounds(200,0,300,100)))))
            rejected {UiActionWindow.select(display,target,21,"receiver.app",listOf(bad))}
    }
    @Test fun fullObservedClipMustStillMatchProductionOcclusionAndBoundsRules() {
        val windows=listOf(app());val target=observed(windows)
        val overlay=TargetWindowEvidence(90,TargetWindowEvidence.SYSTEM,3,false,false,TouchableBounds(200,200,300,300),listOf(TouchableBounds(200,200,300,300)),true,null,null)
        rejected {UiActionWindow.select(display,target,21,"receiver.app",windows+overlay)}
        rejected {UiActionWindow.select(display,target,21,"receiver.app",listOf(app().copy(rootBounds=TouchableBounds(0,20,800,600))))}
        rejected {UiActionWindow.select(display,target.copy(touchableBounds=TouchableBounds(0,0,801,600)),21,"receiver.app",windows)}
        rejected {UiActionWindow.select(display,target.copy(touchableBounds=TouchableBounds(0,0,0,0)),21,"receiver.app",windows)}
        val bar=overlay.copy(bounds=TouchableBounds(0,0,800,20),region=listOf(TouchableBounds(0,0,800,20)))
        val clippedApp=app(region=listOf(TouchableBounds(0,20,800,600)))
        val actual=listOf(clippedApp,bar);val clipped=observed(actual)
        assertEquals(TouchableBounds(0,20,800,600),clipped.touchableBounds)
        assertEquals(21,UiActionWindow.select(display,clipped,21,"receiver.app",actual))
        rejected {UiActionWindow.select(display,target,21,"receiver.app",actual)}
    }
    @Test fun allowingUnfocusedActionsDoesNotPermitFocusStructureToChangeAfterObservation() {
        var windows=listOf(app());val revisions=TargetWindowRevisions()
        val lower=display.copy(id=8,identity="other-screen")
        val snapshots=Snapshots(Clock {0}) {UpperTargetResolver.resolve(display,windows,revisions)}.apply {update(listOf(display,lower));calibrate(7,8)}
        val source=snapshots.capture();UiActionWindow.select(display,source.target,21,"receiver.app",windows)
        windows=listOf(app(focused=true))
        rejected {snapshots.validateObservation(source.id)}
    }
}
