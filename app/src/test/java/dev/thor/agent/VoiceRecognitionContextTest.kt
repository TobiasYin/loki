package dev.thor.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

class VoiceRecognitionContextTest {
    private fun config(context: VoiceRecognitionContext? = null): JSONObject {
        val frame = VolcAsrProtocol.config("fixture-user", context)
        assertEquals(0x11, frame[0].toInt()); assertEquals(0x10, frame[1].toInt()); assertEquals(0x11, frame[2].toInt())
        assertEquals(frame.size - 8, ByteBuffer.wrap(frame, 4, 4).int)
        val json = GZIPInputStream(frame.copyOfRange(8, frame.size).inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        return JSONObject(json)
    }
    private fun inner(context: VoiceRecognitionContext): JSONObject {
        val request = config(context).getJSONObject("request")
        assertTrue(request.getJSONObject("corpus").get("context") is String)
        return JSONObject(request.getJSONObject("corpus").getString("context"))
    }
    @Test fun emptyContextKeepsLegacyRequestAndAudioConfiguration() {
        assertNull(VoiceRecognitionContext.build(emptyList()))
        val cfg = config(); assertFalse(cfg.getJSONObject("request").has("corpus"))
        assertEquals("fixture-user", cfg.getJSONObject("user").getString("uid"))
        assertEquals(16000, cfg.getJSONObject("audio").getInt("rate"))
        assertEquals(1, cfg.getJSONObject("audio").getInt("channel"))
        assertTrue(cfg.getJSONObject("request").getBoolean("enable_nonstream"))
        assertEquals("full", cfg.getJSONObject("request").getString("result_type"))
    }
    @Test fun configIncludesSerializedCorpusWithSpecificGameAndNewestConversationFirst() {
        val context = VoiceRecognitionContext.build(listOf(
            VoiceRecognitionContext.Message("user", "到铁匠那里拿剑"),
            VoiceRecognitionContext.Message("assistant", "已进入海拉鲁。"),
            VoiceRecognitionContext.Message("user", "打开这个游戏"),
        ), VoiceRecognitionContext.App("Citra", "org.citra.citra_emu"), "塞尔达传说：众神的三角力量2")!!
        val value = inner(context); assertEquals("dialog_ctx", value.getString("context_type"))
        val data = value.getJSONArray("context_data")
        assertEquals(4, data.length()); assertTrue(data.getJSONObject(0).getString("text").contains("当前运行游戏：塞尔达"))
        assertEquals("到铁匠那里拿剑", data.getJSONObject(1).getString("text")); assertEquals("user", data.getJSONObject(1).getString("speaker"))
        assertEquals("bot", data.getJSONObject(2).getString("speaker")); assertEquals("打开这个游戏", data.getJSONObject(3).getString("text"))
        assertEquals("塞尔达传说：众神的三角力量2", value.getJSONArray("hotwords").getJSONObject(0).getString("word"))
        assertEquals("Citra", value.getJSONArray("hotwords").getJSONObject(1).getString("word"))
    }
    @Test fun emulatorNeverInventsGameAndConversationDoesNotBecomeAHardcodedGameTitle() {
        val value = inner(VoiceRecognitionContext.build(listOf(VoiceRecognitionContext.Message("user", "打开星露谷")), VoiceRecognitionContext.App("Citra", "org.citra.citra_emu"))!!)
        val scene = value.getJSONArray("context_data").getJSONObject(0).getString("text")
        assertFalse(scene.contains("当前运行游戏")); assertFalse(scene.contains("星露谷"))
        assertEquals(1, value.getJSONArray("hotwords").length())
    }
    @Test fun messageOnlyContextSupportsPerceptionDisabledWithoutInventedApplication() {
        val value = inner(VoiceRecognitionContext.build(listOf(VoiceRecognitionContext.Message("user", "把亮度设成百分之五十")))!!)
        assertEquals(1, value.getJSONArray("context_data").length()); assertFalse(value.has("hotwords"))
        assertEquals("把亮度设成百分之五十", value.getJSONArray("context_data").getJSONObject(0).getString("text"))
    }
    @Test fun toolsSystemThinkingEmptyAndDuplicatesAreExcluded() {
        val value = inner(VoiceRecognitionContext.build(listOf(
            VoiceRecognitionContext.Message("tool", "raw_tool_payload"), VoiceRecognitionContext.Message("system", "system_prompt"),
            VoiceRecognitionContext.Message("thinking", "private_reasoning"), VoiceRecognitionContext.Message("user", "   "),
            VoiceRecognitionContext.Message("user", "打开 Citra"), VoiceRecognitionContext.Message("user", "打开 Citra"),
            VoiceRecognitionContext.Message("assistant", "打开 Citra"),
        ))!!)
        val data = value.getJSONArray("context_data"); assertEquals(2, data.length())
        assertFalse(value.toString().contains("raw_tool_payload")); assertFalse(value.toString().contains("private_reasoning"))
    }
    @Test fun onlySixRecentValidMessagesAreKeptAndTextHasASeparateByteBudget() {
        val messages = (0..99).map { VoiceRecognitionContext.Message(if (it % 2 == 0) "user" else "assistant", "$it " + "文".repeat(300)) }
        val value = inner(VoiceRecognitionContext.build(messages)!!)
        val data = value.getJSONArray("context_data")
        assertTrue(data.length() <= VoiceRecognitionContext.MAX_MESSAGES)
        var total = 0
        for (i in 0 until data.length()) {val text = data.getJSONObject(i).getString("text"); total += text.toByteArray(Charsets.UTF_8).size; assertTrue(text.startsWith("$i "))}
        assertTrue(total <= VoiceRecognitionContext.MAX_TEXT_BYTES)
        assertTrue(value.toString().toByteArray(Charsets.UTF_8).size <= VoiceRecognitionContext.MAX_SERIALIZED_BYTES)
    }
    @Test fun boundsNeverSplitEmojiOrEmitUnpairedSurrogatesOrControls() {
        val value = inner(VoiceRecognitionContext.build(listOf(VoiceRecognitionContext.Message("user", "\u0000\uD800🎮\n" + "测试🎮".repeat(100) + "\uDC00")))!!)
        val text = value.getJSONArray("context_data").getJSONObject(0).getString("text")
        assertTrue(text.startsWith("🎮 ")); assertTrue(text.toByteArray(Charsets.UTF_8).size <= 240)
        assertFalse(text.any { Character.isISOControl(it) })
        assertEquals(text, String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }
    @Test fun expandedJsonEscapesAlsoRespectSerializedCap() {
        val context = VoiceRecognitionContext.build((0..5).map { VoiceRecognitionContext.Message("user", "$it" + "\"\\".repeat(200)) })!!
        assertTrue(context.serialize().toByteArray(Charsets.UTF_8).size <= VoiceRecognitionContext.MAX_SERIALIZED_BYTES)
        assertTrue(inner(context).getJSONArray("context_data").length() > 0)
    }
    @Test fun hotwordsAreDeduplicatedAndLargeTitlesAreNotTruncatedIntoHotwords() {
        val duplicate = inner(VoiceRecognitionContext.build(emptyList(), VoiceRecognitionContext.App("Citra", null), "citra")!!)
        assertEquals(1, duplicate.getJSONArray("hotwords").length())
        val large = inner(VoiceRecognitionContext.build(emptyList(), VoiceRecognitionContext.App("Citra", null), "游".repeat(100))!!)
        assertEquals(1, large.getJSONArray("hotwords").length())
        assertEquals("Citra", large.getJSONArray("hotwords").getJSONObject(0).getString("word"))
    }
    @Test fun oversizedOriginalNamesCannotBecomeShortHotwordsAfterCleaningOrSourceTruncation() {
        val original = "Cit" + "\u0000".repeat(16_384) + "ra"
        val value = inner(VoiceRecognitionContext.build(emptyList(), VoiceRecognitionContext.App(original, "org.citra.citra_emu", original), original)!!)
        assertFalse(value.has("hotwords"))
        assertTrue(value.getJSONArray("context_data").getJSONObject(0).getString("text").contains("Cit"))
    }
    @Test fun credentialsAreOmittedRatherThanSentToRecognitionProvider() {
        val value = inner(VoiceRecognitionContext.build(listOf(
            VoiceRecognitionContext.Message("user", "apikey 为 abcdefghijk-private"),
            VoiceRecognitionContext.Message("assistant", "Authorization: Bearer private-access-value"),
            VoiceRecognitionContext.Message("user", "oc_sk_private-secret"),
            VoiceRecognitionContext.Message("user", "打开 Citra"),
        ))!!)
        assertEquals(1, value.getJSONArray("context_data").length()); assertFalse(value.toString().contains("private"))
    }
    @Test fun distinctObservedWindowTitleIsASceneAndHotwordWithoutClaimingAGame() {
        val value = inner(VoiceRecognitionContext.build(emptyList(), VoiceRecognitionContext.App("Citra", "org.citra.citra_emu", "塞尔达传说"))!!)
        val scene = value.getJSONArray("context_data").getJSONObject(0).getString("text")
        assertTrue(scene.contains("上屏窗口标题：塞尔达传说")); assertFalse(scene.contains("当前运行游戏"))
        assertEquals("塞尔达传说", value.getJSONArray("hotwords").getJSONObject(0).getString("word"))
        val duplicate = inner(VoiceRecognitionContext.build(emptyList(), VoiceRecognitionContext.App("Citra", null, "citra"))!!)
        assertFalse(duplicate.toString().contains("上屏窗口标题")); assertEquals(1, duplicate.getJSONArray("hotwords").length())
    }
    @Test fun emptyAndCredentialWindowTitlesAreExcluded() {
        for (title in listOf("  ", "\u0000\n", "apikey: private-test-key")) {
            val value = inner(VoiceRecognitionContext.build(emptyList(), VoiceRecognitionContext.App("Citra", null, title))!!)
            assertFalse(value.toString().contains("上屏窗口标题")); assertFalse(value.toString().contains("private-test-key"))
        }
    }
    @Test fun invalidPackageAndNullNamesAreNotIncluded() {
        assertNull(VoiceRecognitionContext.build(emptyList(), VoiceRecognitionContext.App("\n", "bad package ; command")))
        val value = inner(VoiceRecognitionContext.build(emptyList(), VoiceRecognitionContext.App(null, "org.citra.citra_emu"))!!)
        assertFalse(value.has("hotwords")); assertTrue(value.toString().contains("org.citra.citra_emu"))
    }
    @Test fun contextSnapshotIsImmutableAndSeparateHoldsCannotCarryOldGame() {
        val messages = mutableListOf(VoiceRecognitionContext.Message("user", "打开游戏"))
        val first = VoiceRecognitionContext.build(messages, VoiceRecognitionContext.App("Citra", null), "塞尔达")!!
        val before = first.serialize(); messages.clear(); messages.add(VoiceRecognitionContext.Message("user", "新闻列表"))
        val second = VoiceRecognitionContext.build(messages, VoiceRecognitionContext.App("Thor Input Lab", null))!!
        assertEquals(before, first.serialize()); assertFalse(second.serialize().contains("塞尔达")); assertFalse(second.serialize().contains("Citra"))
    }
}
