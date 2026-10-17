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
    @Test fun ownAppVisualContentChangesPixelIdentityButNotTheInputTarget() {
        val windows=listOf(app(),bar());val revisions=TargetWindowRevisions();var target=UpperTargetResolver.resolve(upper,windows,revisions)
        val snapshots=Snapshots(Clock {1}){target};snapshots.update(listOf(upper,lower));snapshots.calibrate(0,4);val old=snapshots.capture()
        revisions.event(TargetEventKind.CONTENT,10,"example.app",current(windows));target=UpperTargetResolver.resolve(upper,windows,revisions)
        assertFalse(old.target.sameLayout(target));assertTrue(old.target.sameInputTarget(target))
        assertEquals(old,snapshots.validate(old.id));assertEquals(old,snapshots.validateHeld(old.id))
        snapshots.validateInputPoints(old.id,listOf(Point(400.0,300.0)))
    }
    @Test fun applicationViewFeedbackKeepsVisualContentSeparateFromInteractionLayout() {
        // CLICKED, SELECTED, FOCUSED, TEXT_CHANGED, SCROLLED, TEXT_SELECTION_CHANGED, ACCESSIBILITY_FOCUSED.
        for(type in listOf(1,4,8,16,4096,8192,32768)) {
            val windows=listOf(app(),bar());val revisions=TargetWindowRevisions();var target=UpperTargetResolver.resolve(upper,windows,revisions)
            val snapshots=Snapshots(Clock {1}){target};snapshots.update(listOf(upper,lower));snapshots.calibrate(0,4);val original=snapshots.capture()
            val kind=TargetEventKind.fromAndroidType(type)
            assertNotEquals("App feedback is not a window event",TargetEventKind.STRUCTURE,kind)
            revisions.event(kind,10,"example.app",current(windows));target=UpperTargetResolver.resolve(upper,windows,revisions)
            assertEquals("Held input must survive its own app feedback: $type",original,snapshots.validateHeld(original.id))
            if(type==4096)rejected {snapshots.validate(original.id)} else assertEquals(original,snapshots.validate(original.id))
        }
    }
    @Test fun onlyTrueWindowEventsAreStructuralAndCorroboratedSystemNodeFeedbackDoesNotReplaceAppWindow() {
        for(type in listOf(0x20,0x400000)) {
            assertEquals(TargetEventKind.STRUCTURE,TargetEventKind.fromAndroidType(type))
            val revisions=TargetWindowRevisions();val windows=listOf(app(),bar());val before=UpperTargetResolver.resolve(upper,windows,revisions)
            revisions.event(TargetEventKind.fromAndroidType(type),10,"example.app",current(windows))
            assertFalse(before.sameStructure(UpperTargetResolver.resolve(upper,windows,revisions)))
        }
        for(type in listOf(1,4096,8192,16,2048)) {
            val revisions=TargetWindowRevisions();val windows=listOf(app(),bar());val before=UpperTargetResolver.resolve(upper,windows,revisions)
            revisions.event(TargetEventKind.fromAndroidType(type),20,"com.android.systemui",current(windows))
            assertTrue("Only corroborated SystemUI pure content may be ignored",before.sameLayout(UpperTargetResolver.resolve(upper,windows,revisions)))
        }
    }
    @Test fun corroboratedSystemUiSubtreeCanRefreshNetworkTrafficWithoutExpiringAppInput() {
        val windows=listOf(app(),bar());val revisions=TargetWindowRevisions()
        var target=UpperTargetResolver.resolve(upper,windows,revisions)
        val snapshots=Snapshots(Clock {1}) {target}.apply {update(listOf(upper,lower));calibrate(0,4)}
        val original=snapshots.capture()
        repeat(100) {revisions.event(TargetEventKind.fromAndroidType(0x800,0,1),20,"com.android.systemui",current(windows))}
        target=UpperTargetResolver.resolve(upper,windows,revisions)
        assertEquals(0L,revisions.structure(0));assertEquals(original,snapshots.validateObservation(original.id))
        snapshots.validateInputPoints(original.id,listOf(Point(500.0,500.0)))
        target=UpperTargetResolver.resolve(upper,listOf(app(),bar().copy(focused=true)),revisions)
        rejected {snapshots.validateObservation(original.id)}
        target=UpperTargetResolver.resolve(upper,listOf(app(),bar(bounds=TouchableBounds(0,0,1920,120))),revisions)
        rejected {snapshots.validateObservation(original.id)}
        revisions.event(TargetEventKind.STRUCTURE,20,"com.android.systemui",current(windows))
        target=UpperTargetResolver.resolve(upper,windows,revisions)
        rejected {snapshots.validateObservation(original.id)}
    }
    @Test fun boundedEventDiagnosticsDistinguishSystemFeedbackFromRealApplicationLayoutChanges() {
        val revisions=TargetWindowRevisions();val windows=listOf(app(),bar())
        repeat(30){revisions.event(TargetEventKind.INTERACTION_LAYOUT,20,"com.android.systemui",current(windows),0x800)}
        val filtered=revisions.diagnostics()
        assertEquals(16,filtered.size)
        assertTrue(filtered.all {it["systemUiCorroborated"]==true && it["filteredSystemUi"]==true && it["structureChanged"]==false && it["interactionChanged"]==false})
        revisions.event(TargetEventKind.INTERACTION_LAYOUT,10,"example.app",current(windows),0x1000)
        val scrolled=revisions.diagnostics().last()
        assertEquals(true,scrolled["isApplication"]);assertEquals(true,scrolled["interactionChanged"]);assertEquals(false,scrolled["structureChanged"])
        revisions.event(TargetEventKind.STRUCTURE,20,"com.android.systemui",current(windows),0x20)
        val replaced=revisions.diagnostics().last()
        assertEquals(false,replaced["filteredSystemUi"]);assertEquals(true,replaced["structureChanged"])
        assertTrue(revisions.diagnostics().all {row->row.keys.none {it.contains("package",true) || it.contains("windowId",true) || it.contains("text",true)}})
        assertFalse(revisions.diagnostics().toString().contains("com.android.systemui"));assertFalse(revisions.diagnostics().toString().contains("example.app"))
    }
    @Test fun structuralSystemEventsAndSpoofedOrUnknownSystemOwnersAreNeverFiltered() {
        val ordinary=listOf(app(),bar())
        for((kind,rootPackage,eventPackage) in listOf(Triple(TargetEventKind.STRUCTURE,"com.android.systemui","com.android.systemui"),Triple(TargetEventKind.CONTENT,"evil.overlay","com.android.systemui"),Triple(TargetEventKind.CONTENT,null,"com.android.systemui"),Triple(TargetEventKind.CONTENT,"com.android.systemui","evil.overlay"),Triple(TargetEventKind.INTERACTION_LAYOUT,null,"com.android.systemui"),Triple(TargetEventKind.INTERACTION_LAYOUT,"evil.overlay","com.android.systemui"),Triple(TargetEventKind.INTERACTION_LAYOUT,"com.android.systemui","evil.overlay"))) {
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

    @Test fun typedWindowMetadataDoesNotExpireApplicationOrSystemWindowInput() {
        val windows=listOf(app(),bar());val revisions=TargetWindowRevisions();val original=UpperTargetResolver.resolve(upper,windows,revisions)
        for(id in listOf(10,20))for(mask in listOf(4,128,132)) {
            val kind=TargetEventKind.fromAndroidType(0x400000,mask,0)
            assertEquals(TargetEventKind.WINDOW_METADATA,kind)
            revisions.event(kind,id,if(id==10)"example.app" else "com.android.systemui",current(windows))
            assertTrue(original.sameInputTarget(UpperTargetResolver.resolve(upper,windows,revisions)))
        }
        for(mask in listOf(0,1,2,8,16,32,64,256,512,1024,2048,4096,4 or 8,128 or 0x8000))
            assertEquals("Unknown/mixed/real window flags must remain structural: $mask",TargetEventKind.STRUCTURE,TargetEventKind.fromAndroidType(0x400000,mask,0))
    }
    @Test fun scrollAndSubtreeExpireNewCoordinatesWithoutCancellingTheirOwnBoundedInput() {
        for(kind in listOf(TargetEventKind.fromAndroidType(4096),TargetEventKind.fromAndroidType(2048,0,1),TargetEventKind.fromAndroidType(2048,0,1 or 2))) {
            assertEquals(TargetEventKind.INTERACTION_LAYOUT,kind)
            val windows=listOf(app(),bar());val revisions=TargetWindowRevisions();var target=UpperTargetResolver.resolve(upper,windows,revisions)
            val snapshots=Snapshots(Clock {1}){target};snapshots.update(listOf(upper,lower));snapshots.calibrate(0,4);val old=snapshots.capture()
            revisions.event(kind,10,"example.app",current(windows));target=UpperTargetResolver.resolve(upper,windows,revisions)
            assertTrue(old.target.sameStructure(target));assertEquals(old,snapshots.validateHeld(old.id));assertEquals(old,snapshots.validateObservation(old.id))
            rejected {snapshots.validate(old.id)};rejected {snapshots.validateInputPoints(old.id,listOf(Point(500.0,500.0)))}
            assertEquals(0L,revisions.structure(0));assertEquals(1L,revisions.interaction(10))
        }
        for(mask in listOf(0,2,4,8,16,32,64,128))assertEquals(TargetEventKind.CONTENT,TargetEventKind.fromAndroidType(2048,0,mask))
    }
    @Test fun compatibleInputStillRejectsActualWindowIdentityAndRegionEvidenceChanges() {
        val windows=listOf(app(),bar());val revisions=TargetWindowRevisions();var target=UpperTargetResolver.resolve(upper,windows,revisions)
        val snapshots=Snapshots(Clock {1}){target};snapshots.update(listOf(upper,lower));snapshots.calibrate(0,4);val old=snapshots.capture()
        for(changed in listOf(listOf(app(id=11),bar()),listOf(app(pkg="other.app"),bar()),listOf(app().copy(focused=false),bar()),
            listOf(app().copy(layer=8),bar()),listOf(app(bounds=TouchableBounds(1,72,1920,1008)),bar()),
            listOf(app().copy(rootBounds=TouchableBounds(0,80,1920,1008)),bar()),listOf(app().copy(region=listOf(TouchableBounds(0,72,1900,1008))),bar()),
            windows+TargetWindowEvidence(30,4,7,false,true,appBounds,listOf(appBounds),true,"overlay",null))) {
            target=UpperTargetResolver.resolve(upper,changed,revisions)
            rejected {snapshots.validate(old.id)};rejected {snapshots.validateInputPoints(old.id,listOf(Point(500.0,500.0)))}
        }
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
        assertTrue(old.target.sameStructure(target))
        assertEquals(old,snapshots.validate(old.id));snapshots.validateInputPoints(old.id,listOf(Point(500.0,500.0)))
        rejected {snapshots.validateInputPoints(old.id,listOf(Point(0.0,500.0)))}
        val visible=target;target=target.copy(touchableBounds=null)
        rejected {snapshots.validate(old.id)};rejected {snapshots.validateInputPoints(old.id,listOf(Point(500.0,500.0)))}
        target=visible
        now+=Snapshots.OBSERVATION_TTL_MS;rejected {snapshots.validate(old.id)}
    }
}
