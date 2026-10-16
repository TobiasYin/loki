package dev.thor.agent

import dev.thor.bridge.BridgeFailure
import dev.thor.bridge.Code
import dev.thor.bridge.android.HostRpcPayload
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HostRpcPayloadTest {
    private fun header()=JSONObject().put("type","host.rpc").put("bridgeVersion",1).put("connectionId","native-connection").put("requestId","original-request").put("op","persist").put("dataInBody",true)
    private fun rejected(code:Code=Code.INVALID_REQUEST,action:()->Unit) {
        val error=assertThrows(BridgeFailure::class.java,action);assertEquals(code,error.code)
        assertEquals("Only a local enum may escape validation",code.name,error.message)
    }
    @Test fun largeChineseTurnRoundTripsWithoutBeingPutBackIntoTheHeader() {
        val text="长会话：已执行工具，继续保留全部结果😀。".repeat(8000)
        val data=JSONObject().put("kind","turn").put("sessionId","session").put("value",JSONObject().put("messages",text))
        val bytes=data.toString().toByteArray(Charsets.UTF_8);assertTrue(bytes.size>128*1024)
        val metadata=header();assertTrue(metadata.toString().toByteArray(Charsets.UTF_8).size<65536)
        val materialized=HostRpcPayload.materialize(metadata,bytes)
        assertEquals(text,materialized.getJSONObject("data").getJSONObject("value").getString("messages"))
        assertFalse(metadata.has("data"));assertTrue(metadata.getBoolean("dataInBody"));assertFalse(materialized.has("dataInBody"))
    }
    @Test fun bodyRoutingAndBindingNamesRemainOnlyDataAndCannotReplaceHeaderAuthority() {
        val body=JSONObject().put("type","request.start").put("bridgeVersion",900).put("connectionId","wrong").put("requestId","stolen").put("op","other-operation").put("binding",JSONObject().put("lease","wrong"))
        val result=HostRpcPayload.materialize(header(),body.toString().toByteArray())
        assertEquals("host.rpc",result.getString("type"));assertEquals(1,result.getInt("bridgeVersion"));assertEquals("native-connection",result.getString("connectionId"));assertEquals("original-request",result.getString("requestId"));assertEquals("persist",result.getString("op"));assertFalse(result.has("binding"))
        assertEquals("wrong",result.getJSONObject("data").getString("connectionId"))
    }
    @Test fun legacyInlineDataIsRetainedAndUndeclaredBodyIsRejected() {
        val metadata=header().apply {remove("dataInBody");put("data",JSONObject().put("text","small"))}
        assertEquals("small",HostRpcPayload.materialize(metadata,byteArrayOf()).getJSONObject("data").getString("text"))
        rejected {HostRpcPayload.materialize(metadata,"{}".toByteArray())}
    }
    @Test fun inlineDataAndBodyMarkerAlwaysConflictEvenWhenInlineNull() {
        for(value in listOf(JSONObject(),JSONObject.NULL))rejected {HostRpcPayload.materialize(header().put("data",value),"{}".toByteArray())}
        for(marker in listOf(false,"true",1,JSONObject.NULL))rejected {HostRpcPayload.materialize(header().put("dataInBody",marker),"{}".toByteArray())}
        rejected {HostRpcPayload.materialize(header(),byteArrayOf())}
    }
    @Test fun invalidUtf8AndNonObjectOrTrailingPayloadNeverMaterialize() {
        val prefix="{\"value\":\"".toByteArray();val suffix="\"}".toByteArray()
        for(invalid in listOf(byteArrayOf(0xc3.toByte(),0x28),byteArrayOf(0xed.toByte(),0xa0.toByte(),0x80.toByte()),byteArrayOf(0xf0.toByte(),0x9f.toByte())))
            rejected {HostRpcPayload.materialize(header(),prefix+invalid+suffix)}
        for(value in listOf("[]","null","123","\"text\"","{broken", "{} {}", "{} trailing", "{}\u0000 hidden"))rejected {HostRpcPayload.materialize(header(),value.toByteArray())}
    }
    @Test fun headerAndBodyLimitsRemainIndependent() {
        rejected(Code.LIMIT_EXCEEDED){HostRpcPayload.materialize(header(),ByteArray(HostRpcPayload.MAX_BODY_BYTES+1))}
        rejected(Code.LIMIT_EXCEEDED){HostRpcPayload.materialize(header().put("unexpectedHeader","中".repeat(24000)),"{}".toByteArray())}
        rejected {HostRpcPayload.materialize(header().put("type","request.start"),"{}".toByteArray())}
    }
}
