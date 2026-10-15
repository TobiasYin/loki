package dev.thor.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModelCatalogTest {
    @Test fun bundledDeepseekProfileHasOneMillionContextAndOnlyDeclaredEfforts() {
        val asset=File("src/main/assets/opencode-go-models.json").takeIf {it.exists()} ?: File("app/src/main/assets/opencode-go-models.json")
        val model=JSONObject(asset.readText()).getJSONArray("models").objects().single {it.getString("id")=="deepseek-v4.1-flash"}
        assertEquals(1000000,model.getInt("contextWindow"));assertEquals("openai-responses",model.getString("protocol"))
        val efforts=model.getJSONArray("efforts");assertEquals(setOf("low","high","max"),(0 until efforts.length()).map {efforts.getString(it)}.toSet())
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
}
