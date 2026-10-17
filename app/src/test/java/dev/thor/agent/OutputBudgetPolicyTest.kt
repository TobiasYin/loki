package dev.thor.agent

import org.junit.Assert.*
import org.junit.Test

class OutputBudgetPolicyTest {
    @Test fun lunaAutomaticUsesProfileRatherThanLegacy2048() {
        val value=OutputBudgetPolicy.resolve("opencode-go",1050000,128000,"",2048)
        assertEquals(128000,value.tokens);assertEquals(128000,value.limit);assertTrue(value.automatic)
    }
    @Test fun missingAndWhitespaceOfficialTextAreAutomatic() {
        for(text in listOf(null,"","  "))assertEquals(128000,OutputBudgetPolicy.resolve("opencode-go",1050000,128000,text,4096).tokens)
    }
    @Test fun explicitLegacyTextRemainsUserCeiling() {
        val value=OutputBudgetPolicy.resolve("opencode-go",1050000,128000,"2048",2048)
        assertEquals(2048,value.tokens);assertFalse(value.automatic)
    }
    @Test fun clearingAnExplicitCeilingRestoresAutomaticModelCapacity() {
        assertEquals(8192,OutputBudgetPolicy.resolve("opencode-go",1050000,128000,"8192").tokens)
        assertEquals(128000,OutputBudgetPolicy.resolve("opencode-go",1050000,128000,"").tokens)
    }
    @Test fun eachOfficialProviderFollowsItsModel() {
        assertEquals(393216,OutputBudgetPolicy.resolve("deepseek",1048576,393216).tokens)
        assertEquals(64000,OutputBudgetPolicy.resolve("opencode-go",1000000,64000).tokens)
    }
    @Test fun customBlankRetainsDefaultAndLegacyNumericBudget() {
        assertEquals(2048,OutputBudgetPolicy.resolve("custom",32768).tokens)
        assertEquals(4096,OutputBudgetPolicy.resolve("custom",32768,legacyCustomOutput=4096).tokens)
        assertFalse(OutputBudgetPolicy.resolve("custom",32768).automatic)
    }
    @Test fun customExplicitBudgetCanExceedOld16384Limit() {
        assertEquals(128000,OutputBudgetPolicy.resolve("custom",1048576,explicitText="128000",rejectExcess=true).tokens)
    }
    @Test fun switchingToSmallerModelClampsCeilingWithoutRewritingIt() {
        val text="128000"
        assertEquals(64000,OutputBudgetPolicy.resolve("opencode-go",1000000,64000,text).tokens)
        assertEquals(128000,OutputBudgetPolicy.resolve("opencode-go",1050000,128000,text).tokens)
    }
    @Test fun settingsRejectCeilingAboveSelectedModel() {
        assertTrue(runCatching {OutputBudgetPolicy.resolve("opencode-go",1050000,128000,"128001",rejectExcess=true)}.isFailure)
    }
    @Test fun outputCannotConsumeEntireContext() {
        assertEquals(4095,OutputBudgetPolicy.resolve("opencode-go",4096,4096).tokens)
        assertTrue(runCatching {OutputBudgetPolicy.resolve("custom",4096,explicitText="4096",rejectExcess=true)}.isFailure)
    }
    @Test fun malformedAndTooSmallExplicitValuesAreRejected() {
        for(text in listOf("0","15","-1","1.5","1e4","NaN","+2048","999999999999999999999"))
            assertTrue(text,runCatching {OutputBudgetPolicy.resolve("opencode-go",1050000,128000,text)}.isFailure)
    }
    @Test fun validIntegerMinimumAndWhitespaceAreAccepted() {
        assertEquals(16,OutputBudgetPolicy.resolve("opencode-go",1050000,128000," 16 ").tokens)
    }
    @Test fun invalidModelCapacityAndContextAreRejected() {
        for(limit in listOf(null,0,15,1050001))assertTrue(runCatching {OutputBudgetPolicy.resolve("opencode-go",1050000,limit)}.isFailure)
        for(context in listOf(0,4095,4000001))assertTrue(runCatching {OutputBudgetPolicy.resolve("custom",context)}.isFailure)
    }
}
