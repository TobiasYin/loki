package dev.thor.agent

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeepSeekCatalogTest {
    @Test fun officialSnapshotDoesNotReuseGoIdsOrCapabilities() {
        assertEquals("https://api.deepseek.com",DeepSeekCatalog.BASE_URL)
        val models=DeepSeekCatalog.bundled().objects()
        assertEquals(listOf("deepseek-flash","deepseek-v4-pro"),models.map {it.getString("id")})
        for(m in models) {
            assertEquals("openai-responses",m.getString("protocol"));assertEquals(1048576,m.getInt("contextWindow"));assertEquals(393216,m.getInt("maxOutput"))
            assertEquals(listOf("off","low","high","max"),(0 until m.getJSONArray("efforts").length()).map {m.getJSONArray("efforts").getString(it)})
            assertEquals("high",m.getString("defaultEffort"));assertEquals("none",m.getJSONObject("thinkingLevelMap").getString("off"))
            assertFalse(m.getJSONObject("compat").getBoolean("supportsDeveloperRole"))
            assertTrue(m.getBoolean("providerMetadataKnown"));assertTrue(m.getBoolean("supportsTools"))
        }
        assertEquals("[\"text\",\"image\"]",models[0].getJSONArray("input").toString());assertEquals("[\"text\"]",models[1].getJSONArray("input").toString())
    }
    @Test fun discoveryUsesServerLimitsEffortsAndOmitsUnknownUnprofiledIds() {
        val row=JSONObject().put("id","deepseek-flash").put("name","Current Flash").put("context_window",524288).put("max_output_tokens",65536)
            .put("input_modalities",JSONArray(listOf("text"))).put("effort",JSONObject().put("supported_levels",JSONArray(listOf("low","high"))).put("default_level","low"))
        val raw=JSONObject().put("object","list").put("data",JSONArray().put(row).put(JSONObject().put("id","future-without-metadata")))
        val models=DeepSeekCatalog.normalize(raw)
        assertEquals(1,models.length());val m=models.getJSONObject(0)
        assertEquals(524288,m.getInt("contextWindow"));assertEquals(65536,m.getInt("maxOutput"));assertEquals("low",m.getString("defaultEffort"))
        assertEquals("[\"off\",\"low\",\"high\"]",m.getJSONArray("efforts").toString());assertFalse(m.getJSONObject("thinkingLevelMap").has("max"))
        assertEquals("[\"text\"]",m.getJSONArray("input").toString())
        val future=JSONObject(row.toString()).put("id","future-with-rich-metadata")
        val unknown=DeepSeekCatalog.normalize(JSONObject().put("object","list").put("data",JSONArray().put(future))).getJSONObject(0)
        assertFalse("Rich metadata alone cannot assert tool support",unknown.getBoolean("supportsTools"))
        val legacy=DeepSeekCatalog.normalize(JSONObject("""{"object":"list","data":[{"id":"deepseek-v4-pro"}]}"""))
        assertEquals("[\"text\"]",legacy.getJSONObject(0).getJSONArray("input").toString())
    }
    @Test fun corruptMetadataAndCrossProviderCredentialReuseAreRejected() {
        for(raw in listOf("{}","""{"object":"list","data":[]}""","""{"object":"list","data":[{"id":"deepseek-flash","context_window":1024}]}""","""{"object":"list","data":[{"id":"deepseek-flash","effort":{"supported_levels":["unsupported"]}}]}""")) {
            assertThrows(Exception::class.java){DeepSeekCatalog.normalize(JSONObject(raw))}
        }
        assertFalse(DeepSeekCatalog.canReuseCredential("opencode-go","deepseek"));assertFalse(DeepSeekCatalog.canReuseCredential("deepseek","custom"))
        assertTrue(DeepSeekCatalog.canReuseCredential("deepseek","deepseek"));assertTrue(DeepSeekCatalog.canReuseCredential("opencode-go","custom"))
    }
    @Test fun modelsEndpointUsesNativeBearerAndDoesNotFollowRedirect() {
        MockWebServer().use {server->
            server.start();val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
            server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"deepseek-flash"}]}"""))
            val models=DeepSeekCatalog.fetch("fixture-key-not-a-cloud-key",client,server.url("/models").toString())
            assertEquals(1,models.length());val req=server.takeRequest();assertEquals("/models",req.path);assertEquals("GET",req.method)
            assertEquals("Bearer fixture-key-not-a-cloud-key",req.getHeader("Authorization"));assertNull(req.getHeader("x-opencode-session"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","http://127.0.0.1:1/credential-sink"))
            val error=assertThrows(IllegalArgumentException::class.java){DeepSeekCatalog.fetch("fixture-key-not-a-cloud-key",client,server.url("/models").toString())}
            assertTrue(error.message!!.contains("302"));assertFalse(error.message!!.contains("fixture-key"));assertEquals(2,server.requestCount)
        }
    }
}
