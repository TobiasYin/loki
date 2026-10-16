package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class LauncherAppsTest {
    private val lime=LaunchableApp("io.github.lime3ds.android","org.citra.citra_emu.ui.MainActivity","Lime3DS")
    @Test fun purposeQueryMatchesLauncherClassWhenVisibleProductAndPackageNamesDiffer() {
        val unrelated=LaunchableApp("com.example.bank","com.example.bank.MainActivity","Private unrelated app")
        val result=LauncherApps.find(listOf(lime,unrelated),FindAppsQuery.parse(mapOf("query" to "Citra")))
        assertEquals(listOf(lime),result)
        assertEquals(listOf(lime),LauncherApps.find(listOf(lime,unrelated),FindAppsQuery("lime3ds",20)))
        assertTrue(LauncherApps.find(listOf(lime,unrelated),FindAppsQuery("*",20)).isEmpty())
    }
    @Test fun resultsAreDeduplicatedAndLimitedWithoutReturningUnrelatedInventory() {
        val apps=(0..29).map {LaunchableApp("com.example.game$it","com.example.Main$it","Game $it")}
        val result=LauncherApps.find(apps+apps,FindAppsQuery.parse(mapOf("query" to "game","limit" to 20)))
        assertEquals(20,result.size);assertEquals(20,result.map {it.packageName to it.activityName}.toSet().size)
        assertTrue(result.all {it.label.contains("Game")})
    }
    @Test fun queryMustBeExplicitNonblankBoundedAndCannotExpandIntoPackageEnumeration() {
        val invalid=listOf(mapOf<String,Any?>(),mapOf("query" to ""),mapOf("query" to "  "),mapOf("query" to "a".repeat(101)),mapOf("query" to "a\n"),
            mapOf("query" to "game","limit" to 0),mapOf("query" to "game","limit" to 21),mapOf("query" to "game","limit" to 2.5),mapOf("query" to "game","limit" to Double.NaN),
            mapOf("query" to "game","all" to true),mapOf("query" to "game","packageManagerFlags" to 8192))
        for(args in invalid)try {FindAppsQuery.parse(args);fail("Invalid app query accepted: $args")}catch(e:ToolFailure){assertEquals(Code.INVALID_ARGUMENT,e.code)}
    }
    @Test fun launchOnlyAcceptsOneCanonicalPackageAndRejectsOwnAssistant() {
        assertEquals(lime.packageName,LaunchAppCommand.parse(mapOf("packageName" to lime.packageName),setOf("dev.thor.agent")).packageName)
        for(name in listOf("dev.thor.agent","dev.thor.agent.test"))try {LaunchAppCommand.parse(mapOf("packageName" to name),setOf("dev.thor.agent","dev.thor.agent.test"));fail("Own assistant launch")}
            catch(e:ToolFailure){assertEquals(Code.COMMAND_BLOCKED,e.code)}
        for(args in listOf(mapOf("packageName" to "com.example;reboot"),mapOf("packageName" to "https://example.org"),mapOf("packageName" to "com.example/.Hidden"),
            mapOf("packageName" to "com.example","component" to ".Hidden"),mapOf("packageName" to "com.example","displayId" to 4),mapOf("packageName" to "com.example","extras" to emptyMap<String,Any>()),
            mapOf("packageName" to "com.example","clearTask" to true)))try {LaunchAppCommand.parse(args,emptySet());fail("Unbounded launcher parameters accepted")}
            catch(e:ToolFailure){assertEquals(Code.INVALID_ARGUMENT,e.code)}
    }
}
