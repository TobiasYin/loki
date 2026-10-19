package dev.thor.agent

import org.junit.Assert.*
import org.junit.Test

class SearchServicesTest {
    private fun service(name:String)=SearchServices.Service(name,SearchServices.endpoint(name),"test-key")
    @Test fun selectionRespectsSingleServiceExplicitChoiceAndDisabled() {
        val tavily=service("Tavily");val deepseek=service("DeepSeek")
        assertEquals(tavily,SearchServices.choose("自动选择",listOf(tavily)))
        assertEquals(deepseek,SearchServices.choose("自动选择",listOf(tavily,deepseek)))
        assertEquals(tavily,SearchServices.choose("Tavily",listOf(tavily,deepseek)))
        assertNull(SearchServices.choose("Brave",listOf(tavily)))
        assertNull(SearchServices.choose("关闭",listOf(tavily)))
        assertNull(SearchServices.choose("自动选择",emptyList()))
    }
    @Test fun onlyOfficialDeepSeekDestinationCanShareKey() {
        assertTrue(SearchServices.isOfficialDeepSeek("https://api.deepseek.com"))
        assertTrue(SearchServices.isOfficialDeepSeek("https://api.deepseek.com/v1"))
        for(url in listOf("https://api.deepseek.com.evil.test","http://api.deepseek.com","https://api.deepseek.com@evil.test","https://opencode.ai/zen/go/v1","https://api.deepseek.com/other"))assertFalse(url,SearchServices.isOfficialDeepSeek(url))
    }
}
