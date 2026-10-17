package dev.thor.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses random disposable SQLite files only, never the user's product DB or credentials. */
@RunWith(AndroidJUnit4::class)
class TokenUsageStorageTest {
    @Test fun completeUsageSurvivesChatPaginationAndCacheInvalidationWithoutLoadingOtherHistory() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="token-usage-${id()}.db"
        try {SqliteStore(context,name).use {store->
            val events=JSONArray()
            repeat(80) {round->
                repeat(10) {n->events.put(obj("id" to "thought-$round-$n","sessionId" to "a","runId" to "ra","sequence" to round*11+n+1,"type" to "thinking_delta","data" to obj("text" to if(round==0)"OLDER_PRIVATE_THINKING" else "recent thought")))}
                events.put(obj("id" to "usage-$round","sessionId" to "a","runId" to "ra","sequence" to round*11+11,"wallTime" to 1_792_285_200_000L,"type" to "model_round","data" to obj("modelRound" to round+1,"totalInputTokens" to 1000,"cacheReadTokens" to 800,"outputTokens" to 100,"unneeded" to "DO_NOT_PROJECT")))
            }
            events.put(obj("id" to "foreign-usage","sessionId" to "b","runId" to "rb","sequence" to 1,"type" to "model_round","data" to obj("modelRound" to 1,"totalInputTokens" to 999999999)))
            val source=obj("selected" to "a","sessions" to JSONArray().put(obj("id" to "a")).put(obj("id" to "b")),
                "runs" to JSONArray().put(obj("id" to "ra","sessionId" to "a","status" to "completed")).put(obj("id" to "rb","sessionId" to "b","status" to "completed")),
                "messages" to JSONArray(),"events" to events,"attachments" to JSONArray())
            store.save(source)
            val page=store.loadChatPage("a",1,2);assertEquals(2,page.getJSONArray("events").length())
            val receipts=page.getJSONArray("runs").getJSONObject(0).getJSONArray("usageRounds")
            assertEquals(80,receipts.length());assertEquals(80000,receipts.objects().sumOf {it.getJSONObject("data").getInt("totalInputTokens")})
            assertFalse("Older thought bodies must stay outside the page",page.toString().contains("OLDER_PRIVATE_THINKING"))
            assertFalse("Usage receipts include only required numeric metadata",receipts.toString().contains("DO_NOT_PROJECT"))
            assertFalse("Other sessions must remain on disk",page.toString().contains("999999999"))
            receipts.getJSONObject(0).getJSONObject("data").put("totalInputTokens",7)
            assertEquals(1000,store.loadChatPage("a",1,0).getJSONArray("runs").getJSONObject(0).getJSONArray("usageRounds").getJSONObject(0).getJSONObject("data").getInt("totalInputTokens"))
            // A late durable usage receipt invalidates a terminal run's small cache.
            val late=obj("id" to "late","sessionId" to "a","runId" to "ra","sequence" to 881,"type" to "model_round","data" to obj("modelRound" to 81,"totalInputTokens" to 2000))
            source.getJSONArray("events").put(late);store.saveRunUpdate(source,source.getJSONArray("runs").getJSONObject(0),late)
            assertEquals(81,store.loadChatPage("a",1,0).getJSONArray("runs").getJSONObject(0).getJSONArray("usageRounds").length())
            // Full replacement and selecting another session cannot reuse stale metadata.
            source.getJSONArray("runs").getJSONObject(0).put("status","generating");store.save(source)
            assertFalse(store.loadChatPage("a",1,0).getJSONArray("runs").getJSONObject(0).has("usageRounds"))
            assertEquals(1,store.loadChatPage("b",1,0).getJSONArray("runs").getJSONObject(0).getJSONArray("usageRounds").length())
        }}finally {assertTrue(context.deleteDatabase(name))}
    }
}
