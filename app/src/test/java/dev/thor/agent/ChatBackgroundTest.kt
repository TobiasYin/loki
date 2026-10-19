package dev.thor.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatBackgroundTest {
    @Test fun unknownOrAmbiguousAppFallsBackToGlobalAndOverrideWins() {
        val global = BackgroundSpec("wallpaper", blur = 10, opacity = 70)
        val game = BackgroundSpec("image", "abc-123.jpg", 25, 45)
        val overrides = mapOf("dev.game" to game, "dev.reader" to BackgroundSpec())
        assertEquals(global, BackgroundSpec.resolve(global, overrides, null))
        assertEquals(global, BackgroundSpec.resolve(global, overrides, "dev.other"))
        assertEquals(game, BackgroundSpec.resolve(global, overrides, "dev.game"))
        assertEquals(BackgroundSpec(), BackgroundSpec.resolve(global, overrides, "dev.reader"))
    }
    @Test fun persistedSettingsRoundTripAndBoundUntrustedValues() {
        val spec = BackgroundSpec("image", "a123-456.jpg", 12, 65, 35)
        assertEquals(spec, BackgroundSpec.parse(spec.json()))
        val invalid = BackgroundSpec.parse(JSONObject().put("mode", "http://example.com")
            .put("image", "../../private.jpg").put("blur", 999).put("opacity", -10))
        assertEquals(BackgroundSpec("default", "", 30, 0), invalid)
        assertEquals(BackgroundSpec(), BackgroundSpec.parse(JSONObject()))
    }
    @Test fun cardOpacityMigratesOldSettingsAndClampsSeparatelyFromBackground() {
        val legacy = BackgroundSpec.parse(JSONObject().put("mode", "image").put("opacity", 40))
        assertEquals(84, legacy.cardOpacity)
        assertEquals(40, legacy.opacity)
        assertEquals(100, BackgroundSpec.parse(JSONObject().put("cardOpacity", 500)).cardOpacity)
        assertEquals(0, BackgroundSpec.parse(JSONObject().put("cardOpacity", -1)).cardOpacity)
        val clear = legacy.copy(cardOpacity = 0)
        assertTrue(clear.webStyleScript().contains("'0.0'"))
        assertEquals(40, clear.opacity)
    }

}
