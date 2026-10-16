package dev.thor.agent

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModelCatalogTest {
    @Test fun bundledDeepseekProfileHasOneMillionContextAndOnlyDeclaredEfforts() {
        val asset=File("src/main/assets/opencode-go-models.json").takeIf {it.exists()} ?: File("app/src/main/assets/opencode-go-models.json")
        val model=JSONObject(asset.readText()).getJSONArray("models").objects().single {it.getString("id")=="deepseek-v4.1-flash"}
        assertEquals(1000000,model.getInt("contextWindow"));assertEquals("openai-responses",model.getString("protocol"))
        val efforts=model.getJSONArray("efforts");assertEquals(setOf("low","high","max"),(0 until efforts.length()).map {efforts.getString(it)}.toSet())
        assertTrue(model.getBoolean("providerMetadataKnown"));assertTrue(model.getBoolean("supportsTools"))
        ModelCatalog.validateCached(JSONObject(asset.readText()).getJSONArray("models"))
    }
    @Test fun remoteMetadataPreservesLimitsAndExcludesNullUnsupportedLevels() {
        val source=JSONObject("""{"openai-completions":{"chat:test":{"id":"test","name":"Test","contextWindow":1000000,"maxTokens":64000,"reasoning":true,"thinkingLevelMap":{"off":null,"low":"low","high":"high","max":"max"}}}}""")
        val m=ModelCatalog.normalize(source).getJSONObject(0)
        assertEquals(1000000,m.getInt("contextWindow"));assertEquals(64000,m.getInt("maxOutput"));assertFalse(m.getJSONArray("efforts").toString().contains("off"))
    }
    @Test fun sparseMappingsPreserveBasicLevelsLikePiAndOnlyExtendDeclaredLevels() {
        val source=JSONObject("""{"anthropic-messages":{"m":{"id":"m","name":"M","contextWindow":1000000,"maxTokens":64000,"reasoning":true,"thinkingLevelMap":{"xhigh":"xhigh"}}}}""")
        val efforts=ModelCatalog.normalize(source).getJSONObject(0).getJSONArray("efforts")
        val levels=(0 until efforts.length()).map {efforts.getString(it)}
        assertTrue("high" in levels);assertTrue("xhigh" in levels);assertFalse("max" in levels)
    }
    @Test fun corruptOrUnknownMetadataCannotReplaceTheCatalog() {
        for(source in listOf("{}","""{"openai-responses":{"bad":{"id":"bad","name":"Bad","contextWindow":100,"maxTokens":200}}}""")) {
            try {ModelCatalog.normalize(JSONObject(source));fail("Invalid catalog accepted")}catch(_:IllegalArgumentException){}
        }
    }
    private fun official()=JSONObject("""{"opencode-go":{"npm":"@ai-sdk/openai-compatible","models":{"m":{"name":"Official M","reasoning":true,"tool_call":true,"limit":{"context":1000000,"output":384000},"modalities":{"input":["text","image","audio"]},"reasoning_options":[{"type":"effort","values":["low","high","max","unknown-future"]}]},"text-only":{"name":"Text","reasoning":false,"tool_call":false,"limit":{"context":500000,"output":500000},"modalities":{"input":["text"]}},"unsupported":{"provider":{"npm":"@ai-sdk/google"},"limit":{"context":1000000,"output":64000},"modalities":{"input":["text"]}}}}}""")
    private fun ids(vararg values:String)=JSONObject().put("data",JSONArray(values.map {obj("id" to it)}))
    @Test fun officialServiceIdsAndCatalogFactsDefinePerModelCapabilitiesWithoutGuessing() {
        val transport=JSONArray().put(obj("id" to "m","protocol" to "openai-responses","compat" to obj("supportsStore" to false)))
        val models=ModelCatalog.normalizeOfficial(official(),ids("m","text-only","not-in-catalog","unsupported"),transport)
        assertEquals(2,models.length());val m=models.objects().single {it.getString("id")=="m"}
        assertEquals("openai-responses",m.getString("protocol"));assertEquals(384000,m.getInt("maxOutput"));assertTrue(m.getBoolean("supportsTools"))
        assertEquals(listOf("low","high","max"),(0 until m.getJSONArray("efforts").length()).map {m.getJSONArray("efforts").getString(it)})
        assertEquals(listOf("text","image"),(0 until m.getJSONArray("input").length()).map {m.getJSONArray("input").getString(it)})
        assertTrue(m.getJSONArray("providerInput").toString().contains("audio"));assertFalse(m.getJSONObject("compat").getBoolean("supportsStore"))
        val plain=models.objects().single {it.getString("id")=="text-only"};assertFalse(plain.getBoolean("supportsTools"));assertFalse(plain.getBoolean("reasoning"))
        assertEquals("[\"text\"]",plain.getJSONArray("input").toString());assertEquals(500000,plain.getInt("maxOutput"));ModelCatalog.validateCached(models)
    }
    @Test fun availabilityRemovalAndMalformedNewMetadataDoNotInventModelsOrNumericLimits() {
        val raw=official();val source=raw.getJSONObject("opencode-go").getJSONObject("models").getJSONObject("m")
        source.getJSONObject("limit").put("context",1000000.5)
        assertEquals("text-only",ModelCatalog.normalizeOfficial(raw,ids("m","text-only"),JSONArray()).getJSONObject(0).getString("id"))
        try {ModelCatalog.normalizeOfficial(raw,ids("m","unknown"),JSONArray());fail("No complete usable metadata") }catch(_:IllegalArgumentException){}
        assertEquals(1,ModelCatalog.normalizeOfficial(official(),ids("text-only"),JSONArray()).length())
    }
    @Test fun oldOrCorruptCachedProfilesCannotPretendToBeOfficialMetadata() {
        val valid=ModelCatalog.normalizeOfficial(official(),ids("m"),JSONArray())
        for(change in listOf<(JSONObject)->Unit>({it.remove("providerMetadataKnown")},{it.remove("supportsTools")},{it.put("efforts",JSONArray(listOf("unknown")))},{it.put("input",JSONArray(listOf("video")))})) {
            val corrupted=JSONArray(valid.toString());change(corrupted.getJSONObject(0))
            try {ModelCatalog.validateCached(corrupted);fail("Corrupt official cache") }catch(_:IllegalArgumentException){}
        }
    }
}
