package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class InputTargetPolicyTest {
    @Test fun everyScreenInputRejectsOwnAssistantOnEitherRoleButKeepsExternalAppsAvailable() {
        val own=setOf("com.tobias.loki","fixture.assistant")
        for(tool in ToolPack.descriptors.values.filter {it.effect==Effect.INPUT && it.targetRequired}) {
            for(pkg in own)assertEquals(tool.toolId,Code.COMMAND_BLOCKED,assertThrows(ToolFailure::class.java) {InputTargetPolicy.check(tool.toolId,Target(pkg,"valid","same-window"),own)}.code)
            InputTargetPolicy.check(tool.toolId,Target("game.app","valid","same-window"),own)
        }
    }
    @Test fun fixedBackgroundHasNoArbitraryTargetAndReadDoesNotGrantApproval() {
        assertFalse(ToolPack.descriptors.getValue("agent.background").targetRequired)
        InputTargetPolicy.check("agent.background",Target("com.tobias.loki","valid","own"),setOf("com.tobias.loki"))
        InputTargetPolicy.check("perception.window_info",Target("com.tobias.loki","valid","own"),setOf("com.tobias.loki"))
    }
}
