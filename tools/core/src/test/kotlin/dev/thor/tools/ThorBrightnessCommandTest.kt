package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class ThorBrightnessCommandTest {
    @Test fun readsAndBoundedSetsAreWhiteWhileOnlySetsMutate() {
        val read=ThorBrightnessCommand.parse(mapOf("action" to "read","screen" to "lower"))
        assertFalse(read.mutates);assertFalse(read.needsApproval)
        val command=ThorBrightnessCommand.parse(mapOf("action" to "set","screen" to "lower","value" to 80))
        assertTrue(command.mutates);assertFalse(command.needsApproval)
    }
    @Test fun unreviewedActionsTargetsValuesAndBinderParametersAreRejected() {
        for(args in listOf(mapOf("action" to "read","screen" to "lower","value" to 1),mapOf("action" to "set","screen" to "lower"),mapOf("action" to "set","screen" to "4","value" to 80),mapOf("action" to "set","screen" to "upper","value" to 256),mapOf("action" to "set","screen" to "upper","value" to -1),mapOf("action" to "set","screen" to "lower","value" to 0.5),mapOf("action" to "read","screen" to "upper","transaction" to 16386))) {
            try {ThorBrightnessCommand.parse(args);fail("Unknown operation must be rejected")}catch(e:ToolFailure){assertEquals(Code.INVALID_ARGUMENT,e.code)}
        }
    }
}
