package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class TextInputCommandTest {
    private fun command(text:String,mode:String="insert")=TextInputCommand.parse(mapOf("nodeRef" to "current-field","text" to text,"mode" to mode))
    @Test fun unicodeInsertionPreservesChineseEmojiAndNewlines() {
        val value="中文🙂\n第二行"
        assertEquals("before${value}after",command(value).updatedText("beforeafter",6,6))
        assertEquals("prefix$value",command(value).updatedText("prefix",-1,-1))
        assertEquals("insert",TextInputCommand.parse(mapOf("nodeRef" to "ref","text" to "x")).mode)
    }
    @Test fun forwardAndReversedSelectionsReplaceTheSameRange() {
        val edit=command("你好🙂")
        assertEquals("a你好🙂d",edit.updatedText("abcd",1,3))
        assertEquals("a你好🙂d",edit.updatedText("abcd",3,1))
        assertEquals("abcd",command("").updatedText("abcd",2,2))
        assertEquals("ad",command("").updatedText("abcd",1,3))
    }
    @Test fun replaceDoesNotDependOnSelectionAndEmptyReplaceClears() {
        assertEquals("new🙂",command("new🙂","replace").updatedText("old",-2,200))
        assertEquals("",command("","replace").updatedText("delete me",-1,-1))
        assertEquals("",command("","replace").updatedText("x".repeat(70000),0,0))
    }
    @Test fun selectionMustBeBothUnavailableOrWithinCurrentText() {
        for((start,end) in listOf(-1 to 0,0 to -1,-2 to -2,0 to 5,5 to 0))
            rejects(Code.TARGET_CHANGED) {command("x").updatedText("abcd",start,end)}
    }
    @Test fun selectionCannotSplitSurrogatePairsButCanReplaceWholeEmoji() {
        rejects(Code.TARGET_CHANGED) {command("x").updatedText("a🙂b",2,2)}
        rejects(Code.TARGET_CHANGED) {command("x").updatedText("a🙂b",1,2)}
        assertEquals("axb",command("x").updatedText("a🙂b",1,3))
    }
    @Test fun budgetCountsUnicodeCodePointsAndBoundsResultingUtf16Length() {
        assertEquals(8192,command("🙂".repeat(4096)).text.length)
        rejects(Code.LIMIT_EXCEEDED) {command("🙂".repeat(4097))}
        rejects(Code.LIMIT_EXCEEDED) {command("x").updatedText("a".repeat(65536),-1,-1)}
        assertEquals(65536,command("x").updatedText("a".repeat(65536),0,1).length)
        rejects(Code.LIMIT_EXCEEDED) {command("x").updatedText("a".repeat(65537),0,1)}
    }
    @Test fun malformedUnicodeAndSchemaOverridesAreRejected() {
        for(text in listOf("\uD800","\uDC00","\uD800a","\uD800\uD800"))rejects(Code.INVALID_ARGUMENT) {command(text)}
        for(args in listOf(emptyMap(),mapOf("nodeRef" to "ref"),mapOf("text" to "x"),
            mapOf("nodeRef" to "ref","text" to 9),mapOf("nodeRef" to "ref","text" to "x","mode" to null),
            mapOf("nodeRef" to "ref","text" to "x","mode" to "append"),
            mapOf("nodeRef" to "ref","text" to "x","displayId" to 4),
            mapOf("nodeRef" to "ref","text" to "x","submit" to true),
            mapOf("nodeRef" to "bad\nref","text" to "x")))rejects(Code.INVALID_ARGUMENT) {TextInputCommand.parse(args)}
    }
    @Test fun onlyVisibleEnabledEditableNonPasswordSetTextNodesAreEligible() {
        val bounds=TouchableBounds(10,20,300,100);val display=DisplayFact(0,"upper",1920,1080,1,320)
        UiTextInputGuard.requireEditable(true,true,true,false,true,bounds,display,null)
        for(flags in listOf(listOf(false,true,true,false,true),listOf(true,false,true,false,true),
            listOf(true,true,false,false,true),listOf(true,true,true,true,true),listOf(true,true,true,false,false)))
            rejects(Code.INVALID_ARGUMENT) {UiTextInputGuard.requireEditable(flags[0],flags[1],flags[2],flags[3],flags[4],bounds,display,null)}
        rejects(Code.TARGET_CHANGED) {UiTextInputGuard.requireEditable(true,true,true,false,true,bounds,display,TouchableBounds(20,20,300,100))}
        rejects(Code.TARGET_CHANGED) {UiTextInputGuard.requireEditable(true,true,true,false,true,TouchableBounds(1,1,2000,100),display,null)}
    }
    @Test fun textUsesTheSameOwnerWindowAndNodeIdentityAsActivation() {
        val guard=UiActivationGuard("run","session","snapshot",7,"editor.app","root/1","unchanged-editable",100)
        guard.check("run","session","snapshot",7,"editor.app","root/1",true,"unchanged-editable",99)
        rejects(Code.TARGET_CHANGED) {guard.check("another-run","session","snapshot",7,"editor.app","root/1",true,"unchanged-editable",99)}
        rejects(Code.TARGET_CHANGED) {guard.check("run","session","snapshot",7,"editor.app","root/1",false,"unchanged-editable",99)}
        rejects(Code.TARGET_CHANGED) {guard.check("run","session","snapshot",7,"editor.app","root/1",true,"changed-field",99)}
    }
    @Test fun descriptorRequiresSelectedScreenControlAndFreshTarget() {
        val descriptor=ToolPack.descriptors.getValue("input.type_text")
        assertEquals(Capability.SCREEN_CONTROL,descriptor.capability);assertEquals(Effect.INPUT,descriptor.effect)
        assertTrue(descriptor.targetRequired);assertEquals(15000,descriptor.timeoutMs)
    }
    private fun rejects(code:Code,block:()->Unit) {try {block();fail("Unsafe text edit accepted")}catch(e:ToolFailure){assertEquals(code,e.code)}}
}
