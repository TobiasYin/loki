package dev.thor.agent

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** SAUC v3 binary WebSocket protocol. Integers are big endian; PCM is signed 16-bit LE. */
object VolcAsrProtocol {
    const val ENDPOINT="wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    private const val LIMIT=1024*1024
    data class Result(val text:String?,val final:Boolean,val confirmed:String)
    private fun gzip(bytes:ByteArray):ByteArray=ByteArrayOutputStream().also {out->GZIPOutputStream(out).use {it.write(bytes)}}.toByteArray()
    fun packet(type:Int,flags:Int,serialization:Int,payload:ByteArray):ByteArray {
        val compressed=gzip(payload)
        return ByteBuffer.allocate(8+compressed.size).put(0x11.toByte()).put(((type shl 4) or flags).toByte()).put(((serialization shl 4) or 1).toByte()).put(0).putInt(compressed.size).put(compressed).array()
    }
    fun config(uid:String):ByteArray=packet(1,0,1,obj("user" to obj("uid" to uid),"audio" to obj("format" to "pcm","codec" to "raw","rate" to 16000,"bits" to 16,"channel" to 1),
        "request" to obj("model_name" to "bigmodel","enable_itn" to true,"enable_punc" to true,"show_utterances" to true,"enable_nonstream" to true,"result_type" to "full")).toString().toByteArray())
    fun audio(bytes:ByteArray,final:Boolean)=packet(2,if(final)2 else 0,0,bytes)
    fun parse(bytes:ByteArray):Result {
        require(bytes.size in 8..LIMIT)
        val b=ByteBuffer.wrap(bytes);val first=b.get().toInt() and 255
        require(first ushr 4==1);val header=(first and 15)*4;require(header>=4 && header<=bytes.size-4)
        val second=b.get().toInt() and 255;val type=second ushr 4;val flags=second and 15
        val third=b.get().toInt() and 255;require((third and 15) in 0..1)
        b.position(header)
        require(type==9 || type==15)
        var sequence=0
        if(type==15) {require(b.remaining()>=8);val code=b.int;throw AsrServiceError(code)}
        require(flags in 0..3)
        if(flags and 1!=0) {require(b.remaining()>=8);sequence=b.int}
        val size=b.int;require(size>=0 && size==b.remaining())
        val compressed=ByteArray(size);b.get(compressed)
        val payload=if(third and 15==1)GZIPInputStream(compressed.inputStream()).use {SkillStore.bounded(it,LIMIT)} else compressed
        require(payload.size<=LIMIT && third ushr 4==1)
        val json=JSONObject(SkillStore.utf8(payload))
        val code=json.optInt("code",0);if(code!=0 && code!=1000 && code!=20000000)throw AsrServiceError(code)
        val result=json.optJSONObject("result") ?: json.optJSONArray("result")?.optJSONObject(0)
        val text=result?.optString("text")?.takeIf {result.has("text")}
        require(text==null || text.length<=8000)
        val confirmed=result?.optJSONArray("utterances")?.objects()?.filter {it.optBoolean("definite") }?.joinToString("") {it.optString("text")}.orEmpty()
        return Result(text,flags and 2!=0 || sequence<0,confirmed.take(8000))
    }
}
class AsrServiceError(val code:Int):RuntimeException("ASR_SERVICE_ERROR")

/** A replacement transcript, never concatenated deltas. Only release + terminal result can submit. */
class VoiceDraft {
    var text="";private set
    var released=false;private set
    var ended=false;private set
    private var final=false
    @Synchronized fun release():String? {if(ended)return null;released=true;return submit()}
    @Synchronized fun receive(result:VolcAsrProtocol.Result):String? {
        if(ended)return null
        result.text?.let {text=it};final=final || result.final
        return submit()
    }
    @Synchronized private fun submit():String? {
        if(!released || !final || ended)return null
        ended=true;return text.trim()
    }
    @Synchronized fun cancel() {ended=true}
}
