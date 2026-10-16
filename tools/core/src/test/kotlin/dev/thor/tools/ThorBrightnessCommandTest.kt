package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class ThorBrightnessCommandTest {
    @Test fun readsAndBoundedSetsAreWhiteWhileOnlySetsMutate() {
        val read=ThorBrightnessCommand.parse(mapOf("action" to "read","screen" to "lower"))
        assertFalse(read.mutates);assertFalse(read.needsApproval)
        val command=ThorBrightnessCommand.parse(mapOf("action" to "set","screen" to "lower","value" to 80))
        assertTrue(command.mutates);assertFalse(command.needsApproval)
        assertTrue(command.summary.contains("80%"))
        for(screen in listOf("upper","lower"))for(value in listOf(0,1,42,99,100))
            assertEquals(value,ThorBrightnessCommand.parse(mapOf("action" to "set","screen" to screen,"value" to value)).value)
    }
    @Test fun unreviewedActionsTargetsValuesAndBinderParametersAreRejected() {
        val invalidNumbers=listOf<Any?>(-1,101,128,255,256,0.5,42.1,99.999,Double.NaN,Double.POSITIVE_INFINITY,Long.MAX_VALUE,"42",null)
        val invalid=invalidNumbers.map {mapOf("action" to "set","screen" to "upper","value" to it)}+
            listOf(mapOf("action" to "read","screen" to "lower","value" to 1),mapOf("action" to "set","screen" to "lower"),mapOf("action" to "set","screen" to "4","value" to 80),mapOf("action" to "read","screen" to "upper","transaction" to 16386))
        for(args in invalid) {
            try {ThorBrightnessCommand.parse(args);fail("Unknown operation must be rejected")}catch(e:ToolFailure){assertEquals(Code.INVALID_ARGUMENT,e.code)}
        }
    }
    @Test fun allPercentagesRoundTripAcrossFirmwareQuantization() {
        assertEquals(0,ThorBrightnessPercent.toFirmware(0));assertEquals(255,ThorBrightnessPercent.toFirmware(100))
        assertEquals(107,ThorBrightnessPercent.toFirmware(42));assertEquals(94,ThorBrightnessPercent.toFirmware(37))
        var previous=-1
        for(percent in 0..100) {
            val raw=ThorBrightnessPercent.toFirmware(percent)
            assertTrue(raw>previous);previous=raw
            assertEquals(percent,ThorBrightnessPercent.fromFirmware(raw))
            assertTrue(ThorBrightnessPercent.verified(percent,raw))
        }
        // Saving and restoring a rounded percentage changes any original raw value by <=1.
        for(raw in 0..255)assertTrue(kotlin.math.abs(raw-ThorBrightnessPercent.toFirmware(ThorBrightnessPercent.fromFirmware(raw)))<=1)
    }
    @Test fun strictFirmwareVerificationDoesNotWidenToOnePercent() {
        assertTrue(ThorBrightnessPercent.verified(42,106));assertTrue(ThorBrightnessPercent.verified(42,108))
        // A one-percentage-point difference must not excuse a +2 raw hardware mismatch.
        assertEquals(43,ThorBrightnessPercent.fromFirmware(109));assertFalse(ThorBrightnessPercent.verified(42,109))
        for(value in listOf(-1,101))try {ThorBrightnessPercent.toFirmware(value);fail("Invalid percent")}catch(e:ToolFailure){assertEquals(Code.INVALID_ARGUMENT,e.code)}
        for(raw in listOf(-1,256))try {ThorBrightnessPercent.fromFirmware(raw);fail("Invalid firmware result")}catch(e:ToolFailure){assertEquals(Code.IO_ERROR,e.code)}
    }
}
