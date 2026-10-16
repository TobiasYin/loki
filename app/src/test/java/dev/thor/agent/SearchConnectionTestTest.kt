package dev.thor.agent

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SearchConnectionTestTest {
    @Test fun draftSearchUsesActualKeyAndParsesBraveResults() {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("{\"web\":{\"results\":[{\"title\":\"Android\"}]}}"));server.start()
            val result=SearchConnectionTest.run(server.url("/res/v1/web/search").toString(),"draft-search-secret")
            assertTrue(result.message,result.success)
            val request=server.takeRequest();assertEquals("draft-search-secret",request.getHeader("X-Subscription-Token"));assertEquals("/res/v1/web/search?q=Android&count=1",request.path)
            assertFalse(result.message.contains("draft-search-secret"))
        }
    }
    @Test fun redirectsAndWrongResponseCannotPass() {
        MockWebServer().use {server->
            server.start();val url=server.url("/res/v1/web/search").toString()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","http://invalid.test/key"))
            assertFalse(SearchConnectionTest.run(url,"secret").success)
            server.enqueue(MockResponse().setBody("{\"error\":\"secret server error\"}"))
            val wrong=SearchConnectionTest.run(url,"secret");assertFalse(wrong.success);assertFalse(wrong.message.contains("secret"))
        }
    }
}
