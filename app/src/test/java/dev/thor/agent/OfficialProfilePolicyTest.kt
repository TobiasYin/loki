package dev.thor.agent

import org.junit.Assert.*
import org.junit.Test

class OfficialProfilePolicyTest {
    @Test fun onlyKnownOfficialCatalogAndMatchingServiceCanSkipAProbe() {
        assertTrue(OfficialProfilePolicy.metadataKnown("opencode-go","https://opencode.ai/zen/go/v1",true))
        assertTrue(OfficialProfilePolicy.metadataKnown("opencode-go","https://opencode.ai/inference/go/anthropic/v1/",true))
        assertTrue(OfficialProfilePolicy.metadataKnown("deepseek","https://api.deepseek.com",true))
        assertTrue(OfficialProfilePolicy.metadataKnown("deepseek","https://api.deepseek.com/v1",true))
        assertFalse(OfficialProfilePolicy.metadataKnown("custom","https://api.deepseek.com",true))
        assertFalse(OfficialProfilePolicy.metadataKnown("opencode-go","https://opencode.ai/zen/go/v1",false))
    }
    @Test fun nonofficialOrMalformedUrlsCannotInheritOfficialCapabilities() {
        for(url in listOf("http://api.deepseek.com","https://api.deepseek.com.evil.invalid","https://api.deepseek.com:8443","https://user@api.deepseek.com","https://api.deepseek.com?token=not-a-key","https://api.deepseek.com#x","https://api.deepseek.com/custom","not a URL"))
            assertFalse(url,OfficialProfilePolicy.metadataKnown("deepseek",url,true))
        assertFalse(OfficialProfilePolicy.metadataKnown("opencode-go","https://api.deepseek.com",true))
        assertFalse(OfficialProfilePolicy.metadataKnown("deepseek","https://opencode.ai/zen/go/v1",true))
    }
    @Test fun switchingGoProtocolsDoesNotDuplicateAnthropicV1PathOrAlterCustomEndpoints() {
        assertEquals("https://opencode.ai/zen/go",OfficialProfilePolicy.protocolBase("opencode-go","anthropic-messages","https://opencode.ai/zen/go/v1/"))
        assertEquals("https://opencode.ai/zen/go/v1",OfficialProfilePolicy.protocolBase("opencode-go","openai-responses","https://opencode.ai/zen/go/v1"))
        assertEquals("https://opencode.ai/zen/go/v1",OfficialProfilePolicy.protocolBase("opencode-go","openai-completions","https://opencode.ai/zen/go/v1"))
        assertEquals("https://custom.invalid/v1",OfficialProfilePolicy.protocolBase("custom","anthropic-messages","https://custom.invalid/v1"))
        assertEquals("https://api.deepseek.com",OfficialProfilePolicy.protocolBase("deepseek","openai-responses","https://api.deepseek.com"))
    }
}
