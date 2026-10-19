package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class VolcAgentPlanTest {
    private fun rows()=JSONArray().put(obj("id" to "example-model","name" to "Example","contextWindow" to 32768,"maxTokens" to 4096,"input" to JSONArray(listOf("text","image"))))
    private fun document(models:JSONArray=rows(),url:String=VolcAgentPlan.BASE_URL)=JSONObject().put("models",obj("providers" to obj(VolcAgentPlan.KIND to obj("baseUrl" to url,"api" to "openai-responses","models" to models))))
    @Test fun officialDiscoveryUsesStructuredProfilesAndRejectsDestinationSpoofing() {
        val markdown="untrusted prose\n```JSON\n${document()}\n```"
        val model=VolcAgentPlanCatalog.discover(markdown).getJSONObject(0)
        assertEquals("example-model",model.getString("id"))
        assertEquals(4096,model.getInt("maxOutput"))
        assertTrue(model.getBoolean("supportsTools"))
        assertTrue(model.getJSONArray("input").toString().contains("image"))
        assertThrows(IllegalStateException::class.java){VolcAgentPlanCatalog.discover("```JSON\n${document(url="https://evil.invalid")}\n```")}
        assertThrows(IllegalArgumentException::class.java){VolcAgentPlanCatalog.normalize(rows().put(rows().getJSONObject(0)))}
        assertThrows(IllegalArgumentException::class.java){VolcAgentPlanCatalog.normalize(JSONArray().put(rows().getJSONObject(0).put("maxTokens",4000001)))}
        assertThrows(IllegalStateException::class.java){VolcAgentPlanCatalog.discover("malformed documentation")}
    }
    @Test fun bundledCatalogRemainsUsableOffline() {
        val root=File("src/main/assets/volc-agent-plan-models.json")
        val catalog=VolcAgentPlanCatalog.normalize(JSONArray(root.readText()))
        assertTrue(catalog.length()>=10)
        assertTrue(catalog.objects().any {it.getString("id")=="deepseek-v4.1-flash"})
    }
    @Test fun officialTrustIsBoundToTheExactPlanEndpoint() {
        assertTrue(OfficialProfilePolicy.metadataKnown(VolcAgentPlan.KIND,VolcAgentPlan.BASE_URL,true))
        for(url in listOf("https://ark.cn-beijing.volces.com/api/v3","http://ark.cn-beijing.volces.com/api/plan/v3","https://evil.invalid/api/plan/v3"))
            assertFalse(OfficialProfilePolicy.metadataKnown(VolcAgentPlan.KIND,url,true))
        val config=obj("kind" to VolcAgentPlan.KIND,"url" to VolcAgentPlan.BASE_URL,"model" to "ark-code-latest","protocol" to 0)
        assertEquals(VolcAgentPlan.KIND,ProviderStore.normalize(config,"test",1).getString("kind"))
        assertThrows(IllegalArgumentException::class.java){ProviderStore.normalize(JSONObject(config.toString()).put("url","https://evil.invalid"),"test",1)}
        assertThrows(IllegalArgumentException::class.java){ProviderStore.normalize(JSONObject(config.toString()).put("protocol",2),"test",1)}
        assertEquals(4096,OutputBudgetPolicy.resolve(VolcAgentPlan.KIND,32768,4096).tokens)
    }
    @Test fun planVoiceUsesSeparatePathAndSameKeyWithoutLeakingIntoDiagnostics() {
        val config=VoiceConfig("private-test-key",null,null,VolcAgentPlan.ASR_RESOURCE,VolcAgentPlan.ASR_ENDPOINT)
        val request=config.request()
        assertEquals("/api/v3/plan/sauc/bigmodel_async",request.url.encodedPath)
        assertEquals("private-test-key",request.header("X-Api-Key"))
        assertEquals(VolcAgentPlan.ASR_RESOURCE,request.header("X-Api-Resource-Id"))
        assertFalse(config.toString().contains("private-test-key"))
        assertFalse(request.headers.toString().contains("Authorization"))
    }
    @Test fun searchAutoPrefersCurrentPlanAndNeverOverridesAnExplicitChoice() {
        val plan=SearchServices.Service(VolcAgentPlan.SEARCH_NAME,VolcAgentPlan.SEARCH_ENDPOINT,"private-key")
        val deep=SearchServices.Service("DeepSeek",SearchServices.endpoint("DeepSeek"),"other-key")
        assertEquals(plan,SearchServices.choose("自动选择",listOf(deep,plan)))
        assertEquals(deep,SearchServices.choose("DeepSeek",listOf(deep,plan)))
        assertNull(SearchServices.choose("关闭",listOf(plan)))
        assertFalse(plan.toString().contains("private-key"))
    }
    @Test fun ttsRequiresCompleteBoundedPcmAndDoesNotSwallowServiceFailures() {
        val frame=obj("code" to 0,"data" to Base64.getEncoder().encodeToString(byteArrayOf(0,1,2,3))).toString()
        assertArrayEquals(byteArrayOf(0,1,2,3),VolcPlanSpeech.decode(sequenceOf(frame,"{\"code\":20000000}")))
        assertArrayEquals(byteArrayOf(0,1,2,3),VolcPlanSpeech.decode(sequenceOf(JSONObject(frame).put("code",20000000).toString())))
        assertThrows(IllegalArgumentException::class.java){VolcPlanSpeech.decode(sequenceOf(frame))}
        assertThrows(IllegalArgumentException::class.java){VolcPlanSpeech.decode(sequenceOf("{\"code\":45000000}"))}
        assertThrows(IllegalArgumentException::class.java){VolcPlanSpeech.payload(" ")}
        assertThrows(IllegalArgumentException::class.java){VolcPlanSpeech.payload("字".repeat(1001))}
        assertEquals("pcm",VolcPlanSpeech.payload("你好").getJSONObject("req_params").getJSONObject("audio_params").getString("format"))
    }
}
