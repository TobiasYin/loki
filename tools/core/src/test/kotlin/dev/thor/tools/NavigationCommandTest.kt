package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class NavigationCommandTest {
    @Test fun onlyBackWithoutCallerRoutingOrArbitraryKeysIsAccepted() {
        assertEquals("back",NavigationCommand.parse(mapOf("action" to "back")).action)
        for(args in listOf(emptyMap(),mapOf("action" to "home"),mapOf("action" to "BACK"),mapOf("action" to 4),
            mapOf("action" to "back","keyCode" to 4),mapOf("action" to "back","displayId" to 0),
            mapOf("action" to "back","command" to "input keyevent 4"),mapOf("action" to "back","holdMs" to 500))) {
            try {NavigationCommand.parse(args);fail("Unsupported navigation accepted")}catch(e:ToolFailure){assertEquals(Code.INVALID_ARGUMENT,e.code)}
        }
        val d=ToolPack.descriptors.getValue("input.navigate")
        assertEquals(Capability.SCREEN_CONTROL,d.capability);assertEquals(Effect.INPUT,d.effect);assertTrue(d.targetRequired)
    }
    @Test fun cancelledBeforeSubmissionDoesNotEmitAnyEvent() {
        val events=mutableListOf<String>();val cancel=Cancellation().apply {cancel()}
        try {NavigationPair.dispatch(cancel::check,{events+="submitted"},{events+="down";true},{events+="up";true});fail("Cancelled input accepted")}
        catch(e:ToolFailure){assertEquals(Code.CANCELLED,e.code)}
        assertTrue(events.isEmpty())
    }
    @Test fun stopAfterDownStillReleasesAndPreservesCancellation() {
        val events=mutableListOf<String>();val cancel=Cancellation()
        try {NavigationPair.dispatch(cancel::check,{events+="submitted"},{events+="down";cancel.cancel();true},{events+="up";true});fail("Stop was ignored")}
        catch(e:ToolFailure){assertEquals(Code.CANCELLED,e.code)}
        assertEquals(listOf("submitted","down","up"),events)
    }
    @Test fun failedOrThrowingDownStillAttemptsUpAndDoesNotReportSuccess() {
        for(throws in listOf(false,true)) {
            val events=mutableListOf<String>()
            try {NavigationPair.dispatch({},{events+="submitted"},{events+="down";if(throws)throw ToolFailure(Code.IO_ERROR,"Binder failed");false},{events+="up";true});fail("Failed down accepted")}
            catch(e:ToolFailure){assertEquals(Code.IO_ERROR,e.code)}
            assertEquals(listOf("submitted","down","up"),events)
        }
    }
    @Test fun missingUpAcknowledgmentIsAnErrorAndNormalPairHasNoHold() {
        val events=mutableListOf<String>()
        NavigationPair.dispatch({events+="check"},{events+="submitted"},{events+="down";true},{events+="up";true})
        assertEquals(listOf("check","submitted","down","check","up"),events)
        try {NavigationPair.dispatch({},{},{true},{false});fail("Missing UP accepted")}
        catch(e:ToolFailure){assertEquals(Code.IO_ERROR,e.code)}
    }
}
