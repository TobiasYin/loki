package dev.thor.agent

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class VolcAsrProtocolTest {
    private fun response(text:String?,final:Boolean=false,gzip:Boolean=true):ByteArray {
        val json=obj("result" to obj("text" to text,"utterances" to org.json.JSONArray().put(obj("definite" to true,"text" to "已确认")))).toString().toByteArray()
        val payload=if(gzip)ByteArrayOutputStream().also {o->GZIPOutputStream(o).use {it.write(json)}}.toByteArray() else json
        return ByteBuffer.allocate(12+payload.size).put(0x11).put(if(final)0x93.toByte() else 0x91.toByte()).put(if(gzip)0x11 else 0x10).put(0).putInt(if(final)-2 else 1).putInt(payload.size).put(payload).array()
    }
    @Test fun parsesGzipAndPlainReplacementsAndTerminalSequence() {
        val first=VolcAsrProtocol.parse(response("你好世"));assertEquals("你好世",first.text);assertFalse(first.final);assertEquals("已确认",first.confirmed)
        val last=VolcAsrProtocol.parse(response("你好世界。",true,false));assertTrue(last.final);assertEquals("你好世界。",last.text)
        assertEquals(0x22,VolcAsrProtocol.audio(byteArrayOf(1,2),true)[1].toInt())
        assertEquals(0x10,VolcAsrProtocol.config("test")[1].toInt())
    }
    @Test fun releaseWaitsForFinalAndSubmitsOnlyOnce() {
        val draft=VoiceDraft();assertNull(draft.receive(VolcAsrProtocol.Result("你好世",false,"")));assertNull(draft.release())
        assertEquals("你好世界",draft.receive(VolcAsrProtocol.Result("你好世界",true,"")))
        assertNull(draft.receive(VolcAsrProtocol.Result("duplicate",true,"")));assertNull(draft.release())
    }
    @Test fun finalBeforeReleaseAndCancellationNeverSendEarly() {
        val draft=VoiceDraft();assertNull(draft.receive(VolcAsrProtocol.Result("好了",true,"")));assertEquals("好了",draft.release())
        val cancelled=VoiceDraft();cancelled.receive(VolcAsrProtocol.Result("draft",false,""));cancelled.cancel();assertNull(cancelled.release());assertNull(cancelled.receive(VolcAsrProtocol.Result("late",true,"")))
    }
    @Test fun malformedAndOversizedPacketsAreRejected() {
        val valid=response("hello")
        val bad=valid.clone().apply {this[0]=0x01}
        for(bytes in listOf(ByteArray(0),bad,valid.copyOf(valid.size-1),valid+0.toByte(),ByteArray(1048577))) {
            try {VolcAsrProtocol.parse(bytes);fail("Malformed packet accepted")}catch(_:IllegalArgumentException) {}
        }
        val error=ByteBuffer.allocate(8).put(0x11).put(0xf0.toByte()).put(0x10).put(0).putInt(401).array()
        try {VolcAsrProtocol.parse(error);fail("Bad error frame")}catch(_:IllegalArgumentException) {}
    }
    @Test fun authModesStayInNativeHeadersAtFixedEndpoint() {
        val modern=VoiceConfig("test-secret",null,null,"volc.seedasr.sauc.duration").request()
        assertEquals(modern.header("X-Api-Connect-Id"),modern.header("X-Api-Request-Id"));assertNotNull(modern.header("X-Api-Request-Id"));assertEquals("openspeech.bytedance.com",modern.url.host);assertEquals("test-secret",modern.header("X-Api-Key"));assertNull(modern.header("X-Api-Access-Key"))
        val old=VoiceConfig(null,"123","test-access","volc.bigasr.sauc.duration").request()
        assertEquals("123",old.header("X-Api-App-Key"));assertEquals("test-access",old.header("X-Api-Access-Key"));assertNull(old.header("X-Api-Key"))
    }
}
