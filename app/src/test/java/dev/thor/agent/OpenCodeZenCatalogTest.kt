package dev.thor.agent

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OpenCodeZenCatalogTest {
    private fun model(id:String="free",price:Any=0)=obj("id" to id,"name" to id,"reasoning" to true,"tool_call" to true,
        "cost" to obj("input" to price,"output" to 0),"limit" to obj("context" to 200000,"input" to 160000,"output" to 32000),
        "modalities" to obj("input" to JSONArray(listOf("text")),"output" to JSONArray(listOf("text"))))
    private fun metadata(models:JSONObject)=obj("opencode" to obj("npm" to "@ai-sdk/openai-compatible","models" to models))
    private fun available(vararg ids:String)=obj("object" to "list","data" to JSONArray(ids.map {obj("id" to it)}))
    @Test fun onlyAvailableExplicitlyZeroPricedChatAndToolModelsAreOffered() {
        val models=obj("free" to model(),"big-pickle" to model("big-pickle"),"paid-free" to model("paid-free",0.1),
            "unknown" to model().apply {remove("cost")},"string-price" to model(price="0"),"retired" to model(),
            "cache-paid" to model().apply {getJSONObject("cost").put("cache_read",1)},
            "systemone-free" to model().put("tool_call",false),"wrong-protocol" to model().put("provider",obj("npm" to "@ai-sdk/google")))
        val result=OpenCodeZenCatalog.normalize(metadata(models),available("free","big-pickle","paid-free","unknown","string-price","cache-paid","systemone-free","wrong-protocol"))
        assertEquals(listOf("big-pickle","free"),result.objects().map {it.getString("id")})
        val m=result.getJSONObject(0);assertEquals(160000,m.getInt("maxInput"));assertEquals(200000,m.getInt("contextWindow"));assertEquals(32000,m.getInt("maxOutput"))
        assertEquals("openai-completions",m.getString("protocol"));assertTrue(m.getBoolean("free"));OpenCodeZenCatalog.validate(result)
        assertEquals("max_tokens",m.getJSONObject("compat").getString("maxTokensField"))
        m.remove("compat");OpenCodeZenCatalog.validate(result)
        assertEquals("max_tokens",m.getJSONObject("compat").getString("maxTokensField"))
        assertEquals(0,OpenCodeZenCatalog.normalize(metadata(models),available("paid-free")).length())
    }
    @Test fun invalidOrFractionalInputLimitsAreNotInvented() {
        for(limit in listOf(0,200001,160000.5,"160000")) {
            val m=model();m.getJSONObject("limit").put("input",limit)
            assertEquals(0,OpenCodeZenCatalog.normalize(metadata(obj("free" to m)),available("free")).length())
        }
    }
    @Test fun fetchSendsKeyOnlyToAvailabilityAndRejectsRedirectsOrAuthenticationFailure() {
        MockWebServer().use {server->
            val client=OkHttpClient()
            server.enqueue(MockResponse().setBody(available("free").toString()))
            server.enqueue(MockResponse().setBody(metadata(obj("free" to model())).toString()))
            assertEquals(1,OpenCodeZenCatalog.fetch("fixture-key",client,server.url("/models").toString(),server.url("/metadata").toString()).length())
            assertEquals("Bearer fixture-key",server.takeRequest().getHeader("Authorization"));assertNull(server.takeRequest().getHeader("Authorization"))
            for(code in listOf(401,429,302)) {
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Location",server.url("/never")))
                assertThrows(IllegalArgumentException::class.java) {OpenCodeZenCatalog.fetch("fixture-key",client,server.url("/models").toString(),server.url("/metadata").toString())}
                server.takeRequest()
            }
            assertEquals(5,server.requestCount)
        }
    }
    @Test fun publicMetadataHasItsOwnBoundedReadAboveTheToolResultLimit() {
        val large=obj("padding" to "x".repeat(2*1024*1024)).toString().toByteArray()
        assertTrue(ModelCatalog.readCatalog(large.inputStream(),4*1024*1024).has("padding"))
        assertThrows(IllegalArgumentException::class.java) {ModelCatalog.readCatalog(large.inputStream(),1024*1024)}
    }
    @Test fun checkedInSnapshotHasExplicitFreePricesAndIndependentInputCaps() {
        val path=listOf(File("src/main/assets/opencode-zen-models.json"),File("app/src/main/assets/opencode-zen-models.json")).first {it.exists()}
        val snapshot=JSONObject(path.readText());val models=OpenCodeZenCatalog.normalize(snapshot.getJSONObject("metadata"),snapshot.getJSONObject("available"))
        assertTrue(models.length()>1);OpenCodeZenCatalog.validate(models)
        val pickle=models.objects().single {it.getString("id")=="big-pickle"};assertEquals(160000,pickle.getInt("maxInput"))
        assertTrue(OfficialProfilePolicy.metadataKnown(OpenCodeZenCatalog.KIND,OpenCodeZenCatalog.BASE_URL,true))
        assertFalse(OfficialProfilePolicy.metadataKnown(OpenCodeZenCatalog.KIND,"https://opencode.ai/zen/go/v1",true))
        assertEquals("https://opencode.ai/zen",OfficialProfilePolicy.protocolBase(OpenCodeZenCatalog.KIND,"anthropic-messages",OpenCodeZenCatalog.BASE_URL))
    }
}
