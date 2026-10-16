package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class AgentBackgroundTest {
    private fun rejected(code:Code,block:()->Unit) {
        try {block();fail("Expected $code")}catch(t:ToolFailure){assertEquals(code,t.code)}
    }
    @Test fun fixedAssistantActionRejectsCallerRoutingAndNeedsScreenControlWithoutTarget() {
        AgentBackground.parse(emptyMap())
        for(key in listOf("packageName","taskId","displayId","command","action","screen"))
            rejected(Code.INVALID_ARGUMENT){AgentBackground.parse(mapOf(key to "arbitrary"))}
        val d=ToolPack.descriptors.getValue("agent.background")
        assertEquals(Capability.SCREEN_CONTROL,d.capability);assertEquals(Effect.INPUT,d.effect)
        assertFalse(d.targetRequired);assertTrue(d.cancellable)
    }
    @Test fun actualReceiptMustAgreeWithSubmittedEffect() {
        val hidden=mapOf("alreadyBackground" to true,"movedToBackground" to false)
        val moved=mapOf("alreadyBackground" to false,"movedToBackground" to true)
        AgentBackground.verifyReceipt(hidden,false);AgentBackground.verifyReceipt(moved,true)
        rejected(Code.IO_ERROR){AgentBackground.verifyReceipt(hidden,true)}
        rejected(Code.IO_ERROR){AgentBackground.verifyReceipt(moved,false)}
        for(data in listOf(emptyMap(),mapOf("alreadyBackground" to "true","movedToBackground" to false),
            mapOf("alreadyBackground" to false,"movedToBackground" to false),
            mapOf("alreadyBackground" to true,"movedToBackground" to true)))
            rejected(Code.IO_ERROR){AgentBackground.verifyReceipt(data,false)}
    }
    @Test fun queuedExpiryIsRecheckedInsideNativeCommitBeforeAnyMovement() {
        var expired=false;var moves=0;var commitEntries=0
        val parent=Checkpoint({},Cancellation()) {action->commitEntries++;expired=true;action()}
        val queued=parent.checking {requireTool(!expired,Code.TIMEOUT,"Queue expired")}
        queued.check()
        rejected(Code.TIMEOUT){queued.effect {moves++}}
        assertEquals(1,commitEntries);assertEquals(0,moves)
        assertFalse(parent.submitted);assertFalse(queued.submitted)
    }
    @Test fun delegatedCommitRetainsAuthorityAndPropagatesPartialSubmissionImmediately() {
        var insideCommit=false;var commits=0
        val parent=Checkpoint({},Cancellation()) {action->insideCommit=true;commits++;try {action()}finally {insideCommit=false}}
        val queued=parent.checking {}
        rejected(Code.IO_ERROR){queued.effect {
            assertTrue(insideCommit);queued.submitted();assertTrue(parent.submitted)
            throw ToolFailure(Code.IO_ERROR,"System rejected after attempt")
        }}
        assertEquals(1,commits);assertTrue(queued.submitted);assertTrue(parent.submitted);assertFalse(insideCommit)
    }
    @Test fun cancelledQueuedWorkCannotMoveAndHiddenReceiptDoesNotFabricateSubmission() {
        val cancel=Cancellation();val parent=Checkpoint({},cancel);val queued=parent.checking {}
        AgentBackground.verifyReceipt(mapOf("alreadyBackground" to true,"movedToBackground" to false),queued.submitted)
        assertFalse(parent.submitted)
        var moves=0;cancel.cancel()
        rejected(Code.CANCELLED){queued.effect {moves++}}
        assertEquals(0,moves);assertFalse(parent.submitted)
    }
}
