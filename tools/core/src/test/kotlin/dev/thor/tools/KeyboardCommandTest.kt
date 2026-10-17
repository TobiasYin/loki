package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class KeyboardCommandTest {
    private fun command(key:String="A",modifiers:List<String> = emptyList())=KeyboardCommand.parse(mapOf("key" to key,"modifiers" to modifiers))
    @Test fun mapsOnlyFixedKeyboardCodesIncludingTextHomeRatherThanSystemHome() {
        for((index,key) in ('A'..'Z').withIndex())assertEquals(29+index,command(key.toString()).keyCode)
        for((index,key) in ('0'..'9').withIndex())assertEquals(7+index,command(key.toString()).keyCode)
        val special=mapOf("ENTER" to 66,"TAB" to 61,"BACKSPACE" to 67,"DELETE" to 112,"ESCAPE" to 111,"SPACE" to 62,
            "UP" to 19,"DOWN" to 20,"LEFT" to 21,"RIGHT" to 22,"HOME" to 122,"END" to 123,"PAGE_UP" to 92,"PAGE_DOWN" to 93)
        for((key,code) in special)assertEquals(code,command(key).keyCode)
        assertEquals(50,KeyboardCommand.keyCodes.size)
        assertEquals(emptyList<KeyboardModifier>(),KeyboardCommand.parse(mapOf("key" to "TAB")).modifiers)
    }
    @Test fun rejectsSystemKeysShellNumericCodesHoldsRepeatAndCallerRouting() {
        for(key in listOf("POWER","VOLUME_UP","VOLUME_DOWN","BACK","BUTTON_A","KEYCODE_HOME","29","a","A; reboot","🙂"))
            rejects(Code.INVALID_ARGUMENT) {command(key)}
        for(args in listOf(emptyMap(),mapOf("key" to 29),mapOf("key" to "A","keyCode" to 29),
            mapOf("key" to "A","displayId" to 0),mapOf("key" to "A","repeat" to 2),mapOf("key" to "A","durationMs" to 1000),
            mapOf("key" to "A","command" to "input keyevent 29")))rejects(Code.INVALID_ARGUMENT) {KeyboardCommand.parse(args)}
    }
    @Test fun modifiersAreDistinctTypedAndAllowlisted() {
        assertEquals(listOf(KeyboardModifier.CTRL,KeyboardModifier.SHIFT),command(modifiers=listOf("CTRL","SHIFT")).modifiers)
        for(raw in listOf(null,"CTRL",listOf("CTRL","CTRL"),listOf("ctrl"),listOf("CAPS_LOCK"),listOf(113),listOf("CTRL","ALT","SHIFT","META","CTRL")))
            rejects(Code.INVALID_ARGUMENT) {KeyboardCommand.parse(mapOf("key" to "A","modifiers" to raw))}
    }
    @Test fun orderedChordHasOnePairPerKeyAndClearsModifierMetaState() {
        val events=mutableListOf<Triple<Int,Boolean,Int>>();var submissions=0
        KeyboardPair.dispatch(command(modifiers=listOf("CTRL","SHIFT")),{},{submissions++}) {code,down,meta->events+=Triple(code,down,meta);true}
        assertEquals(1,submissions)
        assertEquals(listOf(Triple(113,true,0x1000),Triple(59,true,0x1001),Triple(29,true,0x1001),
            Triple(29,false,0x1001),Triple(59,false,0x1000),Triple(113,false,0)),events)
    }
    @Test fun cancelledBeforeSubmissionDoesNotEmitEvents() {
        val cancellation=Cancellation().apply {cancel()};var events=0
        rejects(Code.CANCELLED) {KeyboardPair.dispatch(command(),cancellation::check,{events++}) {_,_,_->events++;true}}
        assertEquals(0,events)
    }
    @Test fun cancellationAfterFirstModifierReleasesOnlyThatAttemptedKey() {
        val cancellation=Cancellation();val events=mutableListOf<Pair<Int,Boolean>>()
        rejects(Code.CANCELLED) {KeyboardPair.dispatch(command(modifiers=listOf("CTRL","SHIFT")),cancellation::check,{}) {code,down,_->
            events+=code to down;if(down)cancellation.cancel();true}}
        assertEquals(listOf(113 to true,113 to false),events)
    }
    @Test fun cancellationAfterPrimaryDownReleasesPrimaryAndAllModifiers() {
        val cancellation=Cancellation();val events=mutableListOf<Pair<Int,Boolean>>()
        rejects(Code.CANCELLED) {KeyboardPair.dispatch(command(modifiers=listOf("ALT","META")),cancellation::check,{}) {code,down,_->
            events+=code to down;if(code==29 && down)cancellation.cancel();true}}
        assertEquals(listOf(57 to true,117 to true,29 to true,29 to false,117 to false,57 to false),events)
    }
    @Test fun rejectedAndThrowingDownStillReceiveUpWithoutUnattemptedKeys() {
        for(throws in listOf(false,true)) {
            val events=mutableListOf<Pair<Int,Boolean>>()
            rejects(Code.IO_ERROR) {KeyboardPair.dispatch(command(modifiers=listOf("CTRL","SHIFT")),{},{}) {code,down,_->
                events+=code to down;if(down){if(throws)throw ToolFailure(Code.IO_ERROR,"Binder down failed");false}else true}}
            assertEquals(listOf(113 to true,113 to false),events)
        }
    }
    @Test fun cleanupFailureStillReleasesOtherModifiersAndDoesNotClaimSuccess() {
        val events=mutableListOf<Pair<Int,Boolean>>()
        rejects(Code.IO_ERROR) {KeyboardPair.dispatch(command(modifiers=listOf("CTRL","SHIFT")),{},{}) {code,down,_->
            events+=code to down;down || code!=29}}
        assertEquals(listOf(113 to true,59 to true,29 to true,29 to false,59 to false,113 to false),events)
    }
    @Test fun failedCleanupIsAttachedWithoutReplacingOriginalCancellation() {
        val cancellation=Cancellation();val events=mutableListOf<Pair<Int,Boolean>>()
        try {KeyboardPair.dispatch(command(modifiers=listOf("CTRL")),cancellation::check,{}) {code,down,_->
            events+=code to down;if(code==29 && down)cancellation.cancel();down};fail("Cancellation was lost")}
        catch(e:ToolFailure) {assertEquals(Code.CANCELLED,e.code);assertEquals(2,e.suppressed.size);assertTrue(e.suppressed.all {it is ToolFailure && it.code==Code.IO_ERROR})}
        assertEquals(listOf(113 to true,29 to true,29 to false,113 to false),events)
    }
    @Test fun descriptorRequiresSelectedScreenControlAndFreshTarget() {
        val descriptor=ToolPack.descriptors.getValue("input.key_press")
        assertEquals(Capability.SCREEN_CONTROL,descriptor.capability);assertEquals(Effect.INPUT,descriptor.effect)
        assertTrue(descriptor.targetRequired);assertEquals(15000,descriptor.timeoutMs)
    }
    private fun rejects(code:Code,block:()->Unit) {try {block();fail("Unsafe keyboard input accepted")}catch(e:ToolFailure){assertEquals(code,e.code)}}
}
