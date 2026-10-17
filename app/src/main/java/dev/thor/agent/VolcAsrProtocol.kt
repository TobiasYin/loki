package dev.thor.agent

import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** SAUC v3 binary WebSocket protocol. Integers are big endian; PCM is signed 16-bit LE. */
object VolcAsrProtocol {
    const val ENDPOINT="wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    private const val LIMIT=1024*1024
    data class Result(val text:String?,val final:Boolean,val confirmed:String,val noSpeech:Boolean=false)
    private fun gzip(bytes:ByteArray):ByteArray=ByteArrayOutputStream().also {out->GZIPOutputStream(out).use {it.write(bytes)}}.toByteArray()
    fun packet(type:Int,flags:Int,serialization:Int,payload:ByteArray):ByteArray {
        val compressed=gzip(payload)
        return ByteBuffer.allocate(8+compressed.size).put(0x11.toByte()).put(((type shl 4) or flags).toByte()).put(((serialization shl 4) or 1).toByte()).put(0).putInt(compressed.size).put(compressed).array()
    }
    fun config(uid:String,context:VoiceRecognitionContext?=null):ByteArray {
        val request=obj("model_name" to "bigmodel","enable_itn" to true,"enable_punc" to true,"show_utterances" to true,"enable_nonstream" to true,"result_type" to "full")
        context?.let { request.put("corpus",obj("context" to it.serialize())) }
        return packet(1,0,1,obj("user" to obj("uid" to uid),"audio" to obj("format" to "pcm","codec" to "raw","rate" to 16000,"bits" to 16,"channel" to 1),"request" to request).toString().toByteArray(Charsets.UTF_8))
    }
    fun audio(bytes:ByteArray,final:Boolean)=packet(2,if(final)2 else 0,0,bytes)
    private val noSpeechCodes=setOf(20000003,1013)
    private fun payload(bytes:ByteArray,compression:Int,serialization:Int):String {
        require(compression in 0..1 && serialization in 0..1)
        val decoded=if(compression==1)GZIPInputStream(bytes.inputStream()).use {SkillStore.bounded(it,LIMIT)} else bytes
        require(decoded.size<=LIMIT)
        return SkillStore.utf8(decoded)
    }
    private fun jsonValue(text:String):Any {
        val tokener=JSONTokener(text);val value=tokener.nextValue()
        require(tokener.nextClean()=='\u0000')
        return value
    }
    private fun code(json:JSONObject):Int {
        if(!json.has("code"))return 0
        val raw=json.get("code")
        return when(raw) {
            is Number->{val value=raw.toLong();require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && raw.toDouble()==value.toDouble());value.toInt()}
            is String->raw.toIntOrNull() ?: throw IllegalArgumentException("Invalid ASR code")
            else->throw IllegalArgumentException("Invalid ASR code")
        }
    }
    fun parse(bytes:ByteArray):Result {
        require(bytes.size in 8..LIMIT)
        val b=ByteBuffer.wrap(bytes);val first=b.get().toInt() and 255
        require(first ushr 4==1);val header=(first and 15)*4;require(header>=4 && header<=bytes.size-4)
        val second=b.get().toInt() and 255;val type=second ushr 4;val flags=second and 15
        val third=b.get().toInt() and 255;val serialization=third ushr 4;val compression=third and 15
        require(compression in 0..1 && b.get().toInt()==0)
        b.position(header)
        require(type==9 || type==15)
        var sequence=0
        if(type==15) {
            require(flags==0 && serialization in 0..1 && b.remaining()>=8)
            val code=b.int;val size=b.int;require(size>=0 && size==b.remaining())
            val encoded=ByteArray(size);b.get(encoded)
            val text=payload(encoded,compression,serialization)
            // Even a known silence code cannot excuse a broken/truncated frame,
            // bad compression, invalid UTF-8 or a malformed declared JSON body.
            if(serialization==1)jsonValue(text)
            if(code in noSpeechCodes)return Result("",true,"",noSpeech=true)
            throw AsrServiceError(code)
        }
        require(flags in 0..3 && serialization==1)
        if(flags and 1!=0) {require(b.remaining()>=8);sequence=b.int}
        val size=b.int;require(size>=0 && size==b.remaining())
        val encoded=ByteArray(size);b.get(encoded)
        val json=jsonValue(payload(encoded,compression,serialization)) as? JSONObject ?: throw IllegalArgumentException("Invalid ASR response")
        val code=code(json)
        if(code in noSpeechCodes)return Result("",true,"",noSpeech=true)
        if(code!=0 && code!=1000 && code!=20000000)throw AsrServiceError(code)
        val result=json.optJSONObject("result") ?: json.optJSONArray("result")?.optJSONObject(0)
        val terminal=flags and 2!=0 || sequence<0
        val text=if(result==null || !result.has("text") || result.isNull("text")) {
            if(terminal)"" else null
        } else {
            val value=result.get("text");require(value is String);value
        }
        require(text==null || text.length<=8000)
        val confirmed=result?.optJSONArray("utterances")?.objects()?.filter {it.optBoolean("definite") }?.joinToString("") {it.optString("text")}.orEmpty()
        return Result(text,terminal,confirmed.take(8000))
    }

}
class AsrServiceError(val code:Int):RuntimeException("ASR_SERVICE_ERROR") {
    fun userMessage():String=when(code) {
        45000001,45000151->"语音参数或音频格式无效，请检查语音设置（$code）"
        45000002->"未收到可识别音频，请检查麦克风或输入（$code）"
        1002->"语音鉴权失败，请检查语音设置（$code）"
        45000081,55001010->"语音服务等待超时，请重试（$code）"
        55000031,1005->"语音服务繁忙，请稍后重试（$code）"
        else->"语音服务异常（$code），请稍后重试"
    }
}

/** A replacement transcript, never concatenated deltas. Only release + terminal result can submit. */
class VoiceDraft {
    var text="";private set
    var released=false;private set
    var ended=false;private set
    private var final=false
    @Synchronized fun release():String? {if(ended)return null;released=true;return submit()}
    @Synchronized fun receive(result:VolcAsrProtocol.Result):String? {
        if(ended || final)return null
        if(result.noSpeech)text="" else result.text?.let {text=it}
        final=result.final || result.noSpeech
        return submit()
    }
    @Synchronized private fun submit():String? {
        if(!released || !final || ended)return null
        ended=true;return text.trim()
    }
    @Synchronized fun cancel() {ended=true}
}
