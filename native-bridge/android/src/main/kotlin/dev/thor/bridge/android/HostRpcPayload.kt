package dev.thor.bridge.android

import dev.thor.bridge.BridgeFailure
import dev.thor.bridge.Code
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Host RPC framing only. Body data never merges with routing or Native authorization fields. */
object HostRpcPayload {
    const val MAX_HEADER_BYTES=65536
    const val MAX_BODY_BYTES=16*1024*1024
    private fun requirePayload(condition:Boolean,code:Code=Code.INVALID_REQUEST) {if(!condition)throw BridgeFailure(code)}
    fun materialize(header:JSONObject,body:ByteArray):JSONObject {
        requirePayload(header.optString("type")=="host.rpc")
        requirePayload(header.toString().toByteArray(Charsets.UTF_8).size<=MAX_HEADER_BYTES,Code.LIMIT_EXCEEDED)
        val result=JSONObject(header.toString())
        if(!header.has("dataInBody")) {
            requirePayload(body.isEmpty())
            return result
        }
        requirePayload(header.opt("dataInBody")==true && !header.has("data"))
        requirePayload(body.isNotEmpty())
        requirePayload(body.size<=MAX_BODY_BYTES,Code.LIMIT_EXCEEDED)
        val data=try {
            val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val text=decoder.decode(ByteBuffer.wrap(body)).toString()
            // Raw NUL is invalid JSON and must not masquerade as JSONTokener EOF.
            requirePayload(!text.contains('\u0000'))
            val parser=JSONTokener(text)
            val value=parser.nextValue()
            requirePayload(value is JSONObject && parser.nextClean()=='\u0000')
            value as JSONObject
        }catch(error:BridgeFailure){throw error}
        catch(_:Exception){throw BridgeFailure(Code.INVALID_REQUEST)}
        result.remove("dataInBody")
        result.put("data",data)
        return result
    }
}
