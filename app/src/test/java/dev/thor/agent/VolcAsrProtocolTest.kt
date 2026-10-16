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
    private fun serviceError(code:Int,json:Boolean=false,gzip:Boolean=false,body:ByteArray?=null):ByteArray {
        val raw=body ?: if(json)"{\"message\":\"provider-private-body\"}".toByteArray() else "provider-private-body".toByteArray()
        val encoded=if(gzip)ByteArrayOutputStream().also {out->GZIPOutputStream(out).use {it.write(raw)}}.toByteArray() else raw
        return ByteBuffer.allocate(12+encoded.size).put(0x11).put(0xf0.toByte()).put(((if(json)0x10 else 0) or (if(gzip)1 else 0)).toByte()).put(0).putInt(code).putInt(encoded.size).put(encoded).array()
    }
    private fun jsonCode(code:Int)=VolcAsrProtocol.packet(9,0,1,obj("code" to code,"message" to "provider-private-body").toString().toByteArray())
    private fun malformed(bytes:ByteArray) {
        try {VolcAsrProtocol.parse(bytes);fail("Malformed silence frame must not be classified as normal empty")}
        catch(error:Exception){assertFalse("Envelope failure must precede service-code classification",error is AsrServiceError)}
    }
    @Test fun onlyExactSilenceCodesProduceValidatedTerminalEmptyForBothPacketForms() {
        for(code in listOf(20000003,1013)) {
            for(json in listOf(false,true))for(gzip in listOf(false,true)) {
                val result=VolcAsrProtocol.parse(serviceError(code,json,gzip))
                assertTrue(result.final);assertTrue(result.noSpeech);assertEquals("",result.text);assertEquals("",result.confirmed)
            }
            val result=VolcAsrProtocol.parse(jsonCode(code));assertTrue(result.final);assertTrue(result.noSpeech);assertEquals("",result.text)
        }
    }
    @Test fun audioInputTimeoutFormatAuthBusyAndUnknownErrorsRemainRealErrors() {
        for(code in listOf(45000002,45000081,45000151,45000001,1002,1003,1004,1005,55000031,55001010,55123456,999999)) {
            for(bytes in listOf(serviceError(code),jsonCode(code))) {
                try {VolcAsrProtocol.parse(bytes);fail("Real service error swallowed: $code")}
                catch(e:AsrServiceError){assertEquals(code,e.code);assertTrue(e.userMessage().contains(code.toString()));assertFalse(e.userMessage().contains("provider-private-body"))}
            }
        }
        assertTrue(AsrServiceError(45000002).userMessage().contains("麦克风"))
        assertTrue(AsrServiceError(45000081).userMessage().contains("超时"));assertTrue(AsrServiceError(55000031).userMessage().contains("繁忙"))
    }
    @Test fun knownSilenceNeverExcusesMalformedHeaderLengthCompressionEncodingOrJson() {
        val valid=serviceError(20000003)
        malformed(valid.copyOf(valid.size-1));malformed(valid+0.toByte())
        malformed(valid.clone().apply {this[0]=0x01})
        malformed(valid.clone().apply {this[0]=0x10})
        malformed(valid.clone().apply {this[1]=0xf1.toByte()})
        malformed(valid.clone().apply {this[2]=0x02})
        malformed(valid.clone().apply {this[2]=0xf0.toByte()})
        malformed(valid.clone().apply {this[3]=1})
        malformed(serviceError(20000003,gzip=true).apply {this[12]=0})
        malformed(serviceError(1013,body=byteArrayOf(0xff.toByte())))
        malformed(serviceError(20000003,json=true,body="{".toByteArray()))
        malformed(serviceError(20000003,json=true,body="{} trailing".toByteArray()))
        val truncatedJson=jsonCode(20000003);malformed(truncatedJson.copyOf(truncatedJson.size-1))
    }
    @Test fun silenceClearsPartialAndRequiresReleaseExactlyOnce() {
        for(releaseFirst in listOf(false,true)) {
            val draft=VoiceDraft();assertNull(draft.receive(VolcAsrProtocol.Result("old partial",false,"")))
            if(releaseFirst)assertNull(draft.release())
            val received=draft.receive(VolcAsrProtocol.parse(serviceError(20000003)))
            assertEquals("",draft.text)
            if(releaseFirst)assertEquals("",received) else {assertNull(received);assertFalse(draft.ended);assertEquals("",draft.release())}
            assertTrue(draft.ended);assertNull(draft.release());assertNull(draft.receive(VolcAsrProtocol.Result("late stale text",true,"")))
        }
    }
    @Test fun successfulEmptyAndJsonNullFinalClearPartialAndRejectNonStringText() {
        for(value in listOf<Any?>("",null)) {
            val draft=VoiceDraft();draft.receive(VolcAsrProtocol.Result("old partial",false,""))
            val bytes=VolcAsrProtocol.packet(9,2,1,obj("code" to 20000000,"result" to obj("text" to value)).toString().toByteArray())
            val result=VolcAsrProtocol.parse(bytes);assertEquals("",result.text);assertTrue(result.final);assertFalse(result.noSpeech)
            assertNull(draft.receive(result));assertEquals("",draft.release())
        }
        val missing=VolcAsrProtocol.parse(VolcAsrProtocol.packet(9,2,1,obj("code" to 20000000).toString().toByteArray()));assertEquals("",missing.text)
        for(value in listOf<Any>(123,obj("body" to "not transcript")))malformed(VolcAsrProtocol.packet(9,2,1,obj("result" to obj("text" to value)).toString().toByteArray()))
    }
    @Test fun firstTerminalIsImmutableAndCancellationNeverReleasesSilence() {
        val draft=VoiceDraft();assertNull(draft.receive(VolcAsrProtocol.parse(jsonCode(1013))))
        assertNull(draft.receive(VolcAsrProtocol.Result("duplicate must not replace final",true,"")));assertEquals("",draft.release())
        val cancelled=VoiceDraft();cancelled.receive(VolcAsrProtocol.parse(serviceError(20000003)));cancelled.cancel();assertNull(cancelled.release())
    }

}
