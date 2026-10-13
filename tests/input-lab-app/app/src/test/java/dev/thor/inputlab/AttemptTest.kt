package dev.thor.inputlab

import org.junit.Assert.*
import org.junit.Test

class AttemptTest {
    private val left=Bounds(0f,0f,100f,100f)
    private val right=Bounds(200f,0f,300f,100f)
    private fun attempt(c: String="LAB-02")=Attempt(c,left,right,0,roleConfirmed=true).also { it.arm() }
    private fun touch(a: Int,t: Long,x: Float=50f,down: Long=10,device: Int=1)=Raw("touch",a,t,down,device=device,samples=listOf(Sample(t,x,50f)))
    private fun tap(a: Attempt,x: Float=50f,t: Long=10) { a.accept(touch(0,t,x,t)); a.accept(touch(1,t+80,x,t)) }
    private fun key(a: Int,k: Int=96,source: Int=Sources.GAMEPAD,repeat: Int=0,device: Int=1)=Raw("key",a,20,10,source,device,k,repeat)
    @Test fun singleTapPassAndRawCountsOnce() { val a=attempt(); tap(a); assertEquals("pass",a.finish(5000).status); assertEquals(2,a.rawCount); assertEquals(1,a.hits); assertEquals(1L,a.sceneRevision) }
    @Test fun noActionFails() { assertEquals("fail",attempt().finish(5000).status) }
    @Test fun semanticClickCannotProveRawTap() { val a=attempt(); a.semantic(); assertEquals("fail",a.finish(5000).status); assertEquals(0,a.rawCount) }
    @Test fun wrongTargetFails() { val a=attempt(); tap(a,250f); assertEquals("fail",a.finish(5000).status) }
    @Test fun duplicateTapFails() { val a=attempt(); tap(a); tap(a,t=200); assertEquals("fail",a.finish(5000).status) }
    @Test fun upWithoutDownFails() { val a=attempt(); a.accept(touch(1,100)); assertEquals("fail",a.finish(5000).status) }
    @Test fun differentDeviceUpFails() { val a=attempt(); a.accept(touch(0,10)); a.accept(touch(1,100,device=2)); assertEquals("fail",a.finish(5000).status) }
    @Test fun differentDownTimeFails() { val a=attempt(); a.accept(touch(0,10)); a.accept(touch(1,100,down=11)); assertEquals("fail",a.finish(5000).status) }
    @Test fun longAndSwipeHaveIndependentEvidence() {
        val a=attempt("LAB-04-LONG"); a.accept(touch(0,10)); a.accept(touch(1,700)); assertEquals("pass",a.finish(5000).status)
        val b=attempt("LAB-04-LONG"); tap(b); assertEquals("fail",b.finish(5000).status)
        val c=attempt("LAB-04-SWIPE"); c.accept(touch(0,10)); c.accept(touch(2,200,150f)); c.accept(touch(1,400,250f)); assertEquals("pass",c.finish(5000).status)
    }
    @Test fun cancelNotTap() { val a=attempt(); a.accept(touch(0,10)); a.accept(touch(3,100)); assertEquals("fail",a.finish(5000).status) }
    @Test fun cancelCaseNeedsDownCancel() { val a=attempt("LAB-04-CANCEL"); a.accept(touch(0,10)); a.accept(touch(3,100)); assertEquals("pass",a.finish(5000).status) }
    @Test fun keyboardDoesNotBecomeController() { val a=attempt("LAB-06"); a.accept(key(0,source=0x101)); a.accept(key(1,source=0x101)); assertEquals("fail",a.finish(5000).status) }
    @Test fun compositeSourceIsAcceptedAndRepeatDoesNotDuplicate() { val a=attempt("LAB-06"); a.accept(key(0,source=Sources.GAMEPAD or 0x100)); a.accept(key(0,repeat=1)); a.accept(key(1)); assertEquals("pass",a.finish(5000).status) }
    @Test fun wrongButtonFails() { val a=attempt("LAB-06"); a.accept(key(0,97)); a.accept(key(1,97)); assertEquals("fail",a.finish(5000).status) }
    @Test fun focusDoesNotManufactureUp() { val a=attempt("LAB-06"); a.accept(key(0)); a.focus(false); assertEquals(setOf(1 to 96),a.pressed); assertEquals("inconclusive",a.finish(5000).status) }
    @Test fun actualUpAfterFocusCanProveRelease() { val a=attempt("LAB-09"); a.accept(key(0)); a.focus(false); a.focus(true); a.accept(key(1)); assertEquals("pass",a.finish(5000).status); assertTrue(a.stale) }
    @Test fun heldButtonWithoutFocusLossFails() { val a=attempt("LAB-06"); a.accept(key(0)); assertEquals("fail",a.finish(5000).status) }
    @Test fun comboRequiresSameDeviceOverlap() {
        val a=attempt("LAB-08"); a.accept(key(0)); a.accept(key(0,97)); a.accept(key(1)); a.accept(key(1,97)); assertEquals("pass",a.finish(5000).status)
        val b=attempt("LAB-08"); b.accept(key(0)); b.accept(key(1)); b.accept(key(0,97)); b.accept(key(1,97)); assertEquals("fail",b.finish(5000).status)
        val c=attempt("LAB-08"); c.accept(key(0)); c.accept(key(0,97,device=2)); c.accept(key(1)); c.accept(key(1,97,device=2)); assertEquals("fail",c.finish(5000).status)
    }
    private fun axisEvent(values: List<Map<Int,Float>>,device: Int=1,source: Int=Sources.JOYSTICK)=Raw("generic",2,100,10,source,device,samples=values.mapIndexed { i,v -> Sample(90L+i,0f,0f,v) })
    private fun axisAttempt()=attempt("LAB-07").also { a -> a.ranges(1,a.requiredAxes.map { Range(it,Sources.JOYSTICK,if(it==17||it==18) 0f else -1f,1f,0.02f) }) }
    @Test fun batchedAxesPreservedExcursionThenNeutral() { val a=axisAttempt(); a.accept(axisEvent(listOf(a.requiredAxes.associateWith { 1f },a.requiredAxes.associateWith { 0f }))); assertEquals(2,a.sampleCount); assertEquals("pass",a.finish(5000).status) }
    @Test fun missingAxesFail() { val a=axisAttempt(); a.accept(axisEvent(listOf(mapOf(0 to 1f),mapOf(0 to 0f)))); assertEquals("fail",a.finish(5000).status) }
    @Test fun keyboardAxesFail() { val a=axisAttempt(); a.accept(axisEvent(listOf(a.requiredAxes.associateWith { 1f },a.requiredAxes.associateWith { 0f }),source=0x101)); assertEquals("fail",a.finish(5000).status) }
    @Test fun lossOfFocusDoesNotZeroAxes() { val a=axisAttempt(); a.accept(axisEvent(listOf(a.requiredAxes.associateWith { 1f }))); a.focus(false); assertEquals(1f,a.axes[1 to 0]); assertNotEquals("pass",a.finish(5000).status) }
    @Test fun historyCannotHideLastNonNeutral() { val a=axisAttempt(); a.accept(axisEvent(listOf(a.requiredAxes.associateWith { 1f },a.requiredAxes.associateWith { 0f },a.requiredAxes.associateWith { 1f }))); assertEquals("fail",a.finish(5000).status) }
    @Test fun integrityFaultOverridesSuccessfulInput() { val a=attempt(); tap(a); a.fault("queue_overflow"); assertEquals("inconclusive",a.finish(5000).status) }
    @Test fun shortWindowCannotPass() { val a=attempt(); tap(a); assertEquals("inconclusive",a.finish(100).status) }
    @Test fun lateEventsDoNotChangeFrozenResult() { val a=attempt(); tap(a); assertEquals("pass",a.finish(5000).status); tap(a); assertEquals(2,a.rawCount); assertEquals(1,a.hits) }
    @Test fun twoStepTaskAndWrongOrder() { val a=attempt("LAB-11"); tap(a); tap(a,250f,200); assertEquals("pass",a.finish(5000).status); val b=attempt("LAB-11"); tap(b,250f); tap(b); assertEquals("fail",b.finish(5000).status) }
    @Test fun fixtureOriginIsNeverAndroidEvidence() { val row=Json.encode(mapOf("recordOrigin" to "fixture_replay","verdict" to attempt().finish(5000).status)); assertTrue(row.contains("fixture_replay")); assertFalse(row.contains("android_dispatch")) }
    @Test fun jsonEscapesControlCharacters() { assertEquals("{\"a\":\"\\n\\\"\\\\\",\"b\":null}",Json.encode(linkedMapOf("a" to "\n\"\\","b" to Float.NaN))) }
    @Test fun endpointJumpWithoutMoveCannotProveSwipe() { val a=attempt("LAB-04-SWIPE"); a.accept(touch(0,10)); a.accept(touch(1,400,250f)); assertEquals("fail",a.finish(5000).status) }
    @Test fun moveAwayAndBackCannotProveTap() { val a=attempt(); a.accept(touch(0,10)); a.accept(touch(2,40,150f)); a.accept(touch(1,100)); assertEquals("fail",a.finish(5000).status) }
    @Test fun longHoldCannotProveTap() { val a=attempt(); a.accept(touch(0,10)); a.accept(touch(1,1000)); assertEquals("fail",a.finish(5000).status) }
    @Test fun extraControllerTapCannotProveSingleButtonCase() { val a=attempt("LAB-06"); repeat(2) { a.accept(key(0)); a.accept(key(1)) }; assertEquals("fail",a.finish(5000).status) }
    @Test fun rawTouchCanActivateSemanticSceneButIsNotSemanticByItself() { val a=attempt("LAB-05"); tap(a,250f); assertEquals("fail",a.finish(5000).status); val b=attempt("LAB-05"); tap(b,250f); b.semantic(); assertEquals("pass",b.finish(5000).status) }

    @Test fun buttonAxisDoesNotSubstituteForTwoButtonOverlap() {
        val a=attempt("LAB-08"); a.ranges(1,listOf(Range(0,Sources.JOYSTICK,-1f,1f,0f)))
        a.accept(key(0)); a.accept(axisEvent(listOf(mapOf(0 to 1f),mapOf(0 to 0f)))); a.accept(key(1))
        a.accept(key(0,97)); a.accept(key(1,97)); assertEquals("fail",a.finish(5000).status)
        val b=attempt("LAB-08-AXIS"); b.ranges(1,listOf(Range(0,Sources.JOYSTICK,-1f,1f,0f)))
        b.accept(key(0)); b.accept(axisEvent(listOf(mapOf(0 to 1f),mapOf(0 to 0f)))); b.accept(key(1)); assertEquals("pass",b.finish(5000).status)
    }

    @Test fun unadvertisedZeroAxesDoNotBlockValidButtonAxisCombo() {
        val a=attempt("LAB-08-AXIS"); a.ranges(1,listOf(Range(0,Sources.JOYSTICK,-1f,1f,0f)))
        a.accept(key(0)); a.accept(axisEvent(listOf(mapOf(0 to 1f,17 to 0f),mapOf(0 to 0f,17 to 0f)))); a.accept(key(1))
        assertEquals("pass",a.finish(5000).status); assertFalse(a.axes.containsKey(1 to 17))
    }

    @Test fun smallOverlapCannotBorrowAnExcursionAfterButtonRelease() {
        val a=attempt("LAB-08-AXIS"); a.ranges(1,listOf(Range(0,Sources.JOYSTICK,-1f,1f,0f)))
        a.accept(key(0)); a.accept(axisEvent(listOf(mapOf(0 to 0.2f)))); a.accept(key(1))
        a.accept(axisEvent(listOf(mapOf(0 to 1f),mapOf(0 to 0f)))); assertEquals("fail",a.finish(5000).status)
    }

}
