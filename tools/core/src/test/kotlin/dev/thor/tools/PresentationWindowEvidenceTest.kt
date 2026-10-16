package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class PresentationWindowEvidenceTest {
    private val full=TouchableBounds(0,0,1240,1080)
    private val display=DisplayFact(4,"calibrated-lower",1240,1080,1,369)
    private val a11y="""ACCESSIBILITY MANAGER (dumpsys accessibility)
Display[4] :
A11yWindow[AccessibilityWindowInfo[title=null, displayId=4, id=5034, taskId=-1, type=<UNKNOWN:-1>, layer=0, region=SkRegion((0,0,1240,1080)), bounds=Rect(0, 0 - 1240, 1080), focused=true, active=false]]
WindowInfo[WindowInfo[title=null, displayId=4, taskId=-1, type=2037, layer=0, token=android.os.BinderProxy@48a7ae6, region=SkRegion((0,0,1240,1080)), bounds=Rect(0, 0 - 1240, 1080), parent=null, focused=true, children=null, accessibility anchor=0]]
"""
    private val wm="""WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #25 Window{44c9827 u0 receiver.app}:
    mDisplayId=4 mSession=Session{bcbb65 12328:u0a10123} mClient=android.os.BinderProxy@48a7ae6
    mOwnerUid=10123 showForAllUsers=false package=receiver.app appop=NONE
    mAttrs={(0,0)(fillxfill) ty=PRESENTATION fmt=TRANSLUCENT
      fl=LAYOUT_IN_SCREEN FULLSCREEN SPLIT_TOUCH HARDWARE_ACCELERATED
      pfl=USE_BLAST
      fitSides=}
    Requested w=1240 h=1080 mLayoutSeq=28080
    mToken=WindowToken{17bf47d type=2037 android.os.BinderProxy@20851c5}
    mViewVisibility=0x0 mHaveFrame=true mObscured=false
    mHasSurface=true isReadyForDisplay()=true mWindowRemovalAllowed=false
    Frames: parent=[0,0][1240,1080] display=[0,0][1240,1080] frame=[0,0][1240,1080] last=[0,0][1240,1080]
    mForceSeamlesslyRotate=false seamlesslyRotate: pending=null    isOnScreen=true
    isVisible=true
"""
    private fun proof(before:String=a11y,windows:String=wm,after:String=before)=PresentationWindowEvidence.join(before,windows,after,4,setOf(5034)).single()
    private fun candidate(p:PresentationWindowProof?=proof())=TargetWindowEvidence(5034,-1,0,false,false,full,listOf(full),true,"receiver.app",full,p)
    private fun denied(block:()->Unit){try {block();fail("Unsafe Presentation evidence accepted")}catch(_:ToolFailure){}}
    @Test fun exactServerTokenJoinQualifiesWithoutChangingRawUnknownType() {
        val p=proof();assertEquals(10123,p.ownerUid);assertEquals(2037,p.rawWindowType);assertEquals("48a7ae6",p.token)
        val window=candidate(p);assertEquals(-1,window.type);assertTrue(window.isApplicationOn(4));assertFalse(window.isApplicationOn(0))
        assertEquals("valid",UpperTargetResolver.resolve(display,listOf(window),TargetWindowRevisions()).validity)
        assertEquals(5034,CompanionWindows.verify(display,"receiver.app",listOf(window),setOf("dev.thor.agent")).windowId)
        val target=UpperTargetResolver.resolve(display,listOf(window),TargetWindowRevisions())
        assertEquals(5034,UiActionWindow.select(display,target,5034,"receiver.app",listOf(window)))
    }
    @Test fun unknownWithReadableRootAloneCannotBecomeApplication() {
        assertFalse(candidate(null).isApplicationOn(4));assertEquals("unknown",UpperTargetResolver.resolve(display,listOf(candidate(null)),TargetWindowRevisions()).validity)
        denied {CompanionWindows.verify(display,"receiver.app",listOf(candidate(null)),emptySet())}
    }
    @Test fun rawTypesPrivatePresentationsAndSystemOverlaysAreRejected() {
        for(type in listOf(2030,2038,2032,2005))denied {proof(a11y.replace("type=2037","type=$type"))}
        denied {proof(windows=wm.replace("ty=PRESENTATION","ty=PRIVATE_PRESENTATION"))}
        denied {proof(windows=wm.replace("type=2037","type=2030"))}
    }
    @Test fun tokenDisplayOwnerAndDuplicateMetadataCannotBeSubstituted() {
        denied {proof(windows=wm.replace("@48a7ae6","@deadbeef"))}
        denied {proof(windows=wm.replace("mDisplayId=4","mDisplayId=0"))}
        denied {proof(a11y.replace("displayId=4","displayId=0"))}
        denied {proof(windows=wm+wm.substringAfter("(dumpsys window windows)\n"))}
        denied {proof(a11y+a11y.substringAfter("(dumpsys accessibility)\n"))}
        denied {proof(windows=wm.replace("    mOwnerUid=10123", "    mOwnerUid=10123 showForAllUsers=false package=spoof.app appop=NONE\n    mOwnerUid=10123"))}
    }
    @Test fun changedAccessibilityStructureDuringReadIsRejected() {
        denied {proof(after=a11y.replace("focused=true","focused=false"))}
        denied {proof(after=a11y.replace("@48a7ae6","@deadbeef"))}
        denied {proof(after=a11y.replace("id=5034","id=5035"))}
        denied {proof(after=a11y.replace("1240, 1080","1200, 1080"))}
    }
    @Test fun missingSecureOrTouchableVisibilityAndGeometryFactsFailClosed() {
        for(flags in listOf("SECURE FULLSCREEN","NOT_TOUCHABLE FULLSCREEN","0x00002000",""))denied {proof(windows=wm.replace("LAYOUT_IN_SCREEN FULLSCREEN SPLIT_TOUCH HARDWARE_ACCELERATED",flags))}
        for(change in listOf("isVisible=true" to "isVisible=false","isOnScreen=true" to "isOnScreen=false","mViewVisibility=0x0" to "mViewVisibility=0x8",
            "mHaveFrame=true" to "mHaveFrame=false","mHasSurface=true" to "mHasSurface=false","isReadyForDisplay()=true" to "isReadyForDisplay()=false"))
            denied {proof(windows=wm.replace(change.first,change.second))}
        denied {proof(windows=wm.replace("frame=[0,0][1240,1080]","frame=[0,0][1000,1080]"))}
        denied {proof(windows=wm.replace("    isVisible=true\n",""))}
    }
    @Test fun namesTitlesAndOtherAppsDoNotAuthorizeAnUnknownWindow() {
        denied {proof(windows=wm.replace("package=receiver.app","package=com.android.systemui"))}
        denied {proof(windows=wm.replace("mOwnerUid=10123","mOwnerUid=1000"))}
        denied {proof(windows=wm.replace("package=receiver.app","package=dev.thor.agent"))}
        assertFalse(candidate().copy(packageName="spoof.app").isApplicationOn(4))
        // App-controlled titles have no place in the joined proof/stable identity.
        assertEquals(proof(),proof(a11y.replace("title=null","title=Any app-controlled title")))
        assertEquals(proof(),proof(windows=wm.replace("u0 receiver.app}","u0 Arbitrary title}")))
    }
    @Test fun exactRootClipAndOverlaysStillApplyToQualifiedPresentations() {
        val p=candidate();val overlay=TargetWindowEvidence(77,3,2,false,false,TouchableBounds(100,100,200,200),listOf(TouchableBounds(100,100,200,200)),true,null,null)
        denied {CompanionWindows.verify(display,"receiver.app",listOf(p,overlay),emptySet())}
        val unclipped=UpperTargetResolver.resolve(display,listOf(p),TargetWindowRevisions())
        denied {UiActionWindow.select(display,unclipped,5034,"receiver.app",listOf(p,overlay))}
        assertFalse(p.copy(bounds=TouchableBounds(-1,0,1240,1080)).isApplicationOn(4))
        val wrongRole=display.copy(id=0)
        assertEquals("unknown",UpperTargetResolver.resolve(wrongRole,listOf(p),TargetWindowRevisions()).validity)
    }
    @Test fun rootPackageRequiresRealPackageOwnerAndNeverAssistantOrSystemUid() {
        val p=proof()
        PresentationOwnerPolicy.verify(p,10190,"assistant.host",10123,false)
        // Another package sharing the same real UID is the same security principal;
        // no unsupported blanket shared-UID restriction is invented.
        PresentationOwnerPolicy.verify(p.copy(packageName="shared.receiver"),10190,"assistant.host",10123,false)
        denied {PresentationOwnerPolicy.verify(p,10190,"assistant.host",10124,false)}
        denied {PresentationOwnerPolicy.verify(p,10190,"assistant.host",10123,true)}
        denied {PresentationOwnerPolicy.verify(p,10123,"assistant.host",10123,false)}
        denied {PresentationOwnerPolicy.verify(p.copy(packageName="assistant.host"),10190,"assistant.host",10123,false)}
        denied {PresentationOwnerPolicy.verify(p.copy(ownerUid=110123),10190,"assistant.host",110123,false)}
        denied {PresentationOwnerPolicy.verify(p.copy(ownerUid=1000),10190,"assistant.host",1000,false)}
    }
    @Test fun stableProofRefreshKeepsLayoutButRealIdentityChangesInvalidateIt() {
        val initial=UpperTargetResolver.resolve(display,listOf(candidate()),TargetWindowRevisions())
        val identical=UpperTargetResolver.resolve(display,listOf(candidate(proof())),TargetWindowRevisions())
        assertTrue(initial.sameStructure(identical))
        for(changed in listOf(proof().copy(token="deadbeef"),proof().copy(ownerUid=10124),proof().copy(flags=proof().flags+" NOT_FOCUSABLE"))) {
            assertFalse(initial.sameStructure(UpperTargetResolver.resolve(display,listOf(candidate(changed)),TargetWindowRevisions())))
        }
    }
    @Test fun absoluteCacheTtlAndGenerationRejectExpiredOrLateProofsWithoutTouchRenewal() {
        var now=1000L;val cache=PresentationProofCache(Clock {now});val generation=cache.generation();val p=proof()
        assertTrue(cache.put(4,"geometry",listOf(p),now,generation))
        now=5999;assertNotNull(cache.get(4,"geometry"));now=6000;assertNull(cache.get(4,"geometry"))
        assertFalse(cache.put(4,"geometry",listOf(p),1000,generation))
        assertTrue(cache.put(4,"geometry",listOf(p),now,generation));cache.invalidate()
        assertNull(cache.get(4,"geometry"));assertFalse(cache.put(4,"geometry",listOf(p),now,generation))
        assertFalse(cache.put(0,"geometry",listOf(p),now,cache.generation()))
        assertTrue(cache.put(4,"geometry",listOf(p),now,cache.generation()));assertNull(cache.get(4,"changed-display-or-window"))
    }
    @Test fun presentationContentIsStillAppContentAndStructureRemainsStrict() {
        val revisions=TargetWindowRevisions();val window=candidate();val original=UpperTargetResolver.resolve(display,listOf(window),revisions)
        revisions.event(TargetEventKind.CONTENT,5034,"receiver.app",mapOf(4 to listOf(window)))
        val changed=UpperTargetResolver.resolve(display,listOf(window),revisions)
        assertTrue(original.sameStructure(changed));assertFalse(original.sameLayout(changed))
        revisions.event(TargetEventKind.STRUCTURE,5034,"receiver.app",mapOf(4 to listOf(window)))
        assertFalse(changed.sameStructure(UpperTargetResolver.resolve(display,listOf(window),revisions)))
    }
    @Test fun preflightRunsBeforeAnyClaimOrCommitAndCancellationPreventsExecution() {
        val clock=Clock {0};val authority=ManualTestAuthority(clock);var prepared=false;var executed=false
        val backend=object:DeviceBackend {
            override fun availability(toolId:String)=Availability(Status.READY,"fixture","fixture")
            override fun prepareObservation(envelope:Envelope,args:Map<String,Any?>,cancellation:Cancellation) {
                assertFalse(Thread.holdsLock(authority));prepared=true;authority.stop();cancellation.check()
            }
            override fun execute(envelope:Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult {executed=true;error("Must not run")}
            override fun release(runId:String)="fixture"
        }
        val lease=authority.begin(backend)
        val result=Gateway(authority,backend,Snapshots(clock){Target(null,"unknown","fixture")},clock).execute(
            Envelope(lease.session,lease.run,"call","perception.device_info",lease.epoch,lease.deadline),emptyMap())
        assertTrue(prepared);assertFalse(executed);assertEquals(Code.CANCELLED,result.error);assertEquals("not_submitted",result.outcome)
    }
}
