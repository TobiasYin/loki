package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class UpperTargetResolverTest {
    private val upper=DisplayFact(0,"upper",1920,1080,0,240)
    private val lower=DisplayFact(4,"lower",1240,1080,0,240)
    private val screen=TouchableBounds(0,0,1920,1080)
    private val appBounds=TouchableBounds(0,72,1920,1008)
    private fun app(id:Int=10,pkg:String="example.app",bounds:TouchableBounds=appBounds,focus:Boolean=true)=TargetWindowEvidence(id,1,1,focus,true,bounds,listOf(bounds),true,pkg,bounds)
    private fun bar(id:Int=20,bounds:TouchableBounds=TouchableBounds(0,0,1920,72),pkg:String?="com.android.systemui")=TargetWindowEvidence(id,3,2,false,false,bounds,listOf(bounds),true,pkg,bounds)
    private fun current(windows:List<TargetWindowEvidence>)=mapOf(0 to windows)
    private fun rejected(action:()->Unit){assertEquals(Code.TARGET_CHANGED,assertThrows(ToolFailure::class.java,action).code)}
    @Test fun ordinaryApplicationKeepsClipWhileClockAndBatteryContentUpdatesDoNotInvalidateInput() {
        val windows=listOf(app(),bar(),bar(21,TouchableBounds(0,1008,1920,1080)))
        val revisions=TargetWindowRevisions();var observed=UpperTargetResolver.resolve(upper,windows,revisions)
        assertEquals("valid",observed.validity);assertEquals(appBounds,observed.touchableBounds)
        val snapshots=Snapshots(Clock {1}){observed};snapshots.update(listOf(upper,lower));snapshots.calibrate(0,4);val old=snapshots.capture()
        repeat(100){revisions.event(TargetEventKind.CONTENT,20,"com.android.systemui",current(windows))}
        observed=UpperTargetResolver.resolve(upper,windows,revisions)
        assertEquals(old,snapshots.validate(old.id));snapshots.validateInputPoints(old.id,listOf(Point(400.0,300.0)))
        rejected {snapshots.validateInputPoints(old.id,listOf(Point(400.0,30.0)))}
        rejected {snapshots.validateInputPoints(old.id,listOf(Point(400.0,1050.0)))}
    }
    @Test fun edgeToEdgeRootNeverAuthorizesStatusNavigationBarsOrAnOverlayHole() {
        val revisions=TargetWindowRevisions()
        val windows=listOf(app(bounds=screen),bar(),bar(21,TouchableBounds(0,1008,1920,1080)))
        assertEquals(appBounds,UpperTargetResolver.resolve(upper,windows,revisions).touchableBounds)
        val overlay=TargetWindowEvidence(30,4,7,false,false,TouchableBounds(400,300,600,500),listOf(TouchableBounds(400,300,600,500)),true,"overlay",null)
        val target=UpperTargetResolver.resolve(upper,windows+overlay,revisions)
        assertNotNull(target.touchableBounds);assertFalse(target.touchableBounds!!.contains(Point(500.0,400.0)))
        assertFalse("Nonrectangular safe region must not fall back to display",target.touchableBounds!!.contains(Point(800.0,600.0)))
    }
    @Test fun ownAppContentStillInvalidatesNewInputButHeldStructureIsIndependent() {
        val windows=listOf(app(),bar());val revisions=TargetWindowRevisions();var target=UpperTargetResolver.resolve(upper,windows,revisions)
        val snapshots=Snapshots(Clock {1}){target};snapshots.update(listOf(upper,lower));snapshots.calibrate(0,4);val old=snapshots.capture()
        revisions.event(TargetEventKind.CONTENT,10,"example.app",current(windows));target=UpperTargetResolver.resolve(upper,windows,revisions)
        rejected {snapshots.validate(old.id)};assertEquals(old,snapshots.validateHeld(old.id))
    }
    @Test fun applicationViewFeedbackExpiresNewObservationsWithoutInventingWindowStructureChanges() {
        // CLICKED, SELECTED, FOCUSED, TEXT_CHANGED, SCROLLED, TEXT_SELECTION_CHANGED, ACCESSIBILITY_FOCUSED.
        for(type in listOf(1,4,8,16,4096,8192,32768)) {
            val windows=listOf(app(),bar());val revisions=TargetWindowRevisions();var target=UpperTargetResolver.resolve(upper,windows,revisions)
            val snapshots=Snapshots(Clock {1}){target};snapshots.update(listOf(upper,lower));snapshots.calibrate(0,4);val original=snapshots.capture()
            val kind=TargetEventKind.fromAndroidType(type)
            assertNotEquals("App feedback is not a window event",TargetEventKind.STRUCTURE,kind)
            revisions.event(kind,10,"example.app",current(windows));target=UpperTargetResolver.resolve(upper,windows,revisions)
            assertEquals("Held input must survive its own app feedback: $type",original,snapshots.validateHeld(original.id))
            rejected {snapshots.validate(original.id)}
        }
    }
    @Test fun onlyTrueWindowEventsAreStructuralAndSystemInteractionsCannotUseContentExclusion() {
        for(type in listOf(0x20,0x400000)) {
            assertEquals(TargetEventKind.STRUCTURE,TargetEventKind.fromAndroidType(type))
            val revisions=TargetWindowRevisions();val windows=listOf(app(),bar());val before=UpperTargetResolver.resolve(upper,windows,revisions)
            revisions.event(TargetEventKind.fromAndroidType(type),10,"example.app",current(windows))
            assertFalse(before.sameStructure(UpperTargetResolver.resolve(upper,windows,revisions)))
        }
        for(type in listOf(1,4096,8192)) {
            val revisions=TargetWindowRevisions();val windows=listOf(app(),bar());val before=UpperTargetResolver.resolve(upper,windows,revisions)
            revisions.event(TargetEventKind.fromAndroidType(type),20,"com.android.systemui",current(windows))
            assertFalse("System interactions stay conservative: $type",before.sameStructure(UpperTargetResolver.resolve(upper,windows,revisions)))
        }
        for(type in listOf(16,2048)) {
            val revisions=TargetWindowRevisions();val windows=listOf(app(),bar());val before=UpperTargetResolver.resolve(upper,windows,revisions)
            revisions.event(TargetEventKind.fromAndroidType(type),20,"com.android.systemui",current(windows))
            assertTrue("Only corroborated SystemUI pure content may be ignored",before.sameLayout(UpperTargetResolver.resolve(upper,windows,revisions)))
        }
    }
    @Test fun structuralSystemEventsAndSpoofedOrUnknownSystemOwnersAreNeverFiltered() {
        val ordinary=listOf(app(),bar())
        for((kind,rootPackage,eventPackage) in listOf(Triple(TargetEventKind.STRUCTURE,"com.android.systemui","com.android.systemui"),Triple(TargetEventKind.CONTENT,"evil.overlay","com.android.systemui"),Triple(TargetEventKind.CONTENT,null,"com.android.systemui"),Triple(TargetEventKind.CONTENT,"com.android.systemui","evil.overlay"))) {
            val revisions=TargetWindowRevisions();val windows=listOf(app(),bar(pkg=rootPackage));val before=UpperTargetResolver.resolve(upper,windows,revisions)
            revisions.event(kind,20,eventPackage,current(windows));val after=UpperTargetResolver.resolve(upper,windows,revisions)
            assertFalse(before.sameStructure(after));assertFalse(before.sameLayout(after))
        }
        val revisions=TargetWindowRevisions();val before=UpperTargetResolver.resolve(upper,ordinary,revisions)
        revisions.event(TargetEventKind.STRUCTURE,20,"com.android.systemui",current(ordinary))
        assertFalse(before.sameLayout(UpperTargetResolver.resolve(upper,ordinary,revisions)))
    }
    @Test fun overlaysPackagesRootGeometryFocusLayerAndRegionChangesRemainStrict() {
        val windows=listOf(app(),bar());val revisions=TargetWindowRevisions();val before=UpperTargetResolver.resolve(upper,windows,revisions)
        val overlay=TargetWindowEvidence(30,4,5,true,true,TouchableBounds(200,200,700,700),listOf(TouchableBounds(200,200,700,700)),true,"overlay",null)
        for(changed in listOf(windows+overlay,listOf(app(pkg="other"),bar()),listOf(app(bounds=TouchableBounds(1,72,1920,1008)),bar()),listOf(app().copy(focused=false),bar()),listOf(app().copy(layer=8),bar()),listOf(app().copy(region=listOf(TouchableBounds(0,72,1900,1008))),bar()),listOf(app().copy(rootBounds=TouchableBounds(0,80,1920,1008)),bar()),listOf(app(),bar().copy(bounds=TouchableBounds(0,0,1920,96))))) {
            assertFalse("Changed window evidence must not match",before.sameLayout(UpperTargetResolver.resolve(upper,changed,revisions)))
        }
        // SystemUI accessibility overlays are not status-bar content-only exclusions.
        val overlayRevisions=TargetWindowRevisions();val withOverlay=windows+overlay.copy(packageName="com.android.systemui")
        val initial=UpperTargetResolver.resolve(upper,withOverlay,overlayRevisions)
        overlayRevisions.event(TargetEventKind.CONTENT,30,"com.android.systemui",current(withOverlay))
        assertFalse(initial.sameStructure(UpperTargetResolver.resolve(upper,withOverlay,overlayRevisions)))
    }
    @Test fun regionIsIntersectedWithRootWindowAndDisplayAndUnsafeShapesCannotTouchAnywhere() {
        val revisions=TargetWindowRevisions()
        val clipped=app(bounds=TouchableBounds(-10,50,2000,1050)).copy(rootBounds=TouchableBounds(0,70,1920,1000),region=listOf(TouchableBounds(0,80,1910,1060)))
        assertEquals(TouchableBounds(0,80,1910,1000),UpperTargetResolver.resolve(upper,listOf(clipped,bar()),revisions).touchableBounds)
        for(window in listOf(app().copy(rectangular=false,region=listOf(TouchableBounds(0,72,500,1008),TouchableBounds(1000,72,1920,1008))),app().copy(rootBounds=null),app().copy(region=emptyList()))) {
            val target=UpperTargetResolver.resolve(upper,listOf(window,bar()),revisions)
            assertNotNull(target.touchableBounds);assertFalse(target.touchableBounds!!.contains(Point(400.0,300.0)))
        }
    }
    @Test fun lowerScreenChangesDoNotInvalidateUpperAndRemovedWindowEventsRetainDisplayOwnership() {
        val revisions=TargetWindowRevisions();val upperWindows=listOf(app(),bar());val lowerWindows=listOf(app(40,"chat"))
        revisions.observe(4,lowerWindows);val before=UpperTargetResolver.resolve(upper,upperWindows,revisions)
        revisions.event(TargetEventKind.CONTENT,40,"chat",mapOf(0 to upperWindows,4 to lowerWindows))
        assertTrue(before.sameLayout(UpperTargetResolver.resolve(upper,upperWindows,revisions)))
        revisions.event(TargetEventKind.STRUCTURE,20,"com.android.systemui",current(listOf(app())))
        assertFalse(before.sameStructure(UpperTargetResolver.resolve(upper,upperWindows,revisions)))
    }
    @Test fun soleFullscreenCanonicalClippingKeepsExistingIntersectionGuardAndExpiry() {
        val revisions=TargetWindowRevisions();var window=app(bounds=screen);var target=UpperTargetResolver.resolve(upper,listOf(window),revisions)
        var now=1L;val snapshots=Snapshots(Clock {now}){target};snapshots.update(listOf(upper,lower));snapshots.calibrate(0,4);val old=snapshots.capture()
        window=window.copy(bounds=TouchableBounds(1,0,1920,1079),region=listOf(TouchableBounds(1,0,1920,1079)))
        target=UpperTargetResolver.resolve(upper,listOf(window),revisions)
        assertEquals(old,snapshots.validate(old.id));snapshots.validateInputPoints(old.id,listOf(Point(500.0,500.0)))
        rejected {snapshots.validateInputPoints(old.id,listOf(Point(0.0,500.0)))}
        now+=Snapshots.OBSERVATION_TTL_MS;rejected {snapshots.validate(old.id)}
    }
}
