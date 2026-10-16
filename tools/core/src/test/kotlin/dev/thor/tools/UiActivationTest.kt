package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class UiActivationTest {
    private val guard=UiActivationGuard("run","session","tree-snapshot",7,"game.app","root/0/2","node-own-signature",100)
    private val display=DisplayFact(0,"upper",1920,1080,1,320)
    @Test fun stableTargetAcceptsDespiteUnrelatedContentChanges() {
        guard.check("run","session","tree-snapshot",7,"game.app","root/0/2",true,"node-own-signature",99)
        UiActivationGuard.requireClickable(true,true,true,false,true,TouchableBounds(100,200,400,300),display,null)
    }
    @Test fun unrelatedContentDoesNotRelaxCoordinateInputOrChangedStructure() {
        var current=Target("game.app","valid","content-1",TouchableBounds(0,0,1920,1080),"same-window-layout")
        val snapshots=Snapshots(Clock {0}) {current}
        snapshots.update(listOf(display,display.copy(id=4,identity="lower")));snapshots.calibrate(0,4)
        val observed=snapshots.capture();current=current.copy(revision="ram-updated")
        snapshots.validateObservation(observed.id)
        rejects(Code.TARGET_CHANGED) {snapshots.validate(observed.id)}
        current=current.copy(layoutRevision="different-window")
        rejects(Code.TARGET_CHANGED) {snapshots.validateObservation(observed.id)}
    }
    @Test fun refsCannotCrossOwnerSnapshotWindowPackagePathIdentityOrNodeContent() {
        val base=listOf<Any?>("run","session","tree-snapshot",7,"game.app","root/0/2",true,"node-own-signature",99L)
        val changes=listOf(0 to "other-run",1 to "other-session",2 to "old-tree",3 to 8,4 to "other.app",5 to "root/0/3",6 to false,7 to "changed-label-or-bounds",8 to 100L)
        for((index,value) in changes) {
            val a=base.toMutableList().apply {this[index]=value}
            rejects(Code.TARGET_CHANGED) {guard.check(a[0] as String,a[1] as String,a[2] as String,a[3] as Int,a[4] as String,a[5] as String,a[6] as Boolean,a[7] as String,a[8] as Long)}
        }
    }
    @Test fun passwordDisabledOffscreenAndNonClickableNodesAreRejected() {
        for(flags in listOf(listOf(false,true,true,false,true),listOf(true,false,true,false,true),listOf(true,true,false,false,true),listOf(true,true,true,true,true),listOf(true,true,true,false,false)))
            rejects(Code.INVALID_ARGUMENT) {UiActivationGuard.requireClickable(flags[0],flags[1],flags[2],flags[3],flags[4],TouchableBounds(1,1,100,100),display,null)}
        for(b in listOf(TouchableBounds(-1,1,100,100),TouchableBounds(1,1,1921,100),TouchableBounds(1,1070,100,1100),TouchableBounds(10,10,10,20)))
            rejects(Code.TARGET_CHANGED) {UiActivationGuard.requireClickable(true,true,true,false,true,b,display,null)}
        rejects(Code.TARGET_CHANGED) {UiActivationGuard.requireClickable(true,true,true,false,true,TouchableBounds(1,1,100,100),display,TouchableBounds(0,50,1920,1080))}
    }
    @Test fun schemaRejectsCoordinateFallbackArbitraryActionsAndOtherFields() {
        assertEquals("ref",UiActivationGuard.parse(mapOf("nodeRef" to "ref")))
        for(args in listOf(emptyMap(),mapOf("nodeRef" to ""),mapOf("nodeRef" to "ref","x" to 20),mapOf("nodeRef" to "ref","action" to "set_text"),mapOf("nodeRef" to "ref","runId" to "other")))
            rejects(Code.INVALID_ARGUMENT) {UiActivationGuard.parse(args)}
    }
    private fun rejects(code:Code,block:()->Unit) {try {block();fail("Unsafe activation accepted")}catch(e:ToolFailure){assertEquals(code,e.code)}}
}
