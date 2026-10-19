package dev.thor.agent

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit foreground TTS. PCM and credentials are kept in Native memory only. */
class VolcPlanSpeech:AutoCloseable {
    companion object {
        const val ENDPOINT="https://openspeech.bytedance.com/api/v3/plan/tts/unidirectional"
        const val RESOURCE="seed-tts-2.0"
        const val SPEAKER="zh_female_vv_uranus_bigtts"
        internal fun payload(text:String):JSONObject {
            require(text.isNotBlank() && text.codePointCount(0,text.length)<=1000)
            return obj("req_params" to obj("text" to text,"speaker" to SPEAKER,"audio_params" to obj("format" to "pcm","sample_rate" to 24000)))
        }
        internal fun decode(lines:Sequence<String>):ByteArray {
            val output=ByteArrayOutputStream();var terminal=false
            for(line in lines) {
                require(line.length<=3*1024*1024)
                if(line.isBlank())continue
                val event=JSONObject(line);val code=event.getInt("code")
                require(code in setOf(0,20000000)) {"语音合成服务拒绝请求（code $code）"}
                event.optString("data").takeIf {it.isNotEmpty()}?.let {encoded->
                    val bytes=java.util.Base64.getDecoder().decode(encoded)
                    require(output.size()+bytes.size<=4*1024*1024);output.write(bytes)
                }
                if(code==20000000){terminal=true;break}
            }
            require(terminal && output.size()>0 && output.size()%2==0) {"语音合成结果不完整"}
            return output.toByteArray()
        }
    }
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(60,TimeUnit.SECONDS).build()
    @Volatile private var call:Call?=null
    @Volatile private var track:AudioTrack?=null
    @Volatile private var closed=false
    fun play(key:String,text:String) {
        require(!closed && key.isNotBlank() && key.length<=8192 && key.none {it=='\r' || it=='\n'})
        val request=Request.Builder().url(ENDPOINT).header("X-Api-Key",key).header("X-Api-Resource-Id",RESOURCE)
            .header("X-Api-Request-Id",UUID.randomUUID().toString()).post(payload(text).toString().toRequestBody("application/json".toMediaType())).build()
        val pending=client.newCall(request)
        synchronized(this){check(!closed);call=pending}
        val pcm=pending.execute().use {response->
            require(response.isSuccessful) {"语音合成失败（HTTP ${response.code}）"}
            val source=requireNotNull(response.body).source();var bytes=0
            decode(sequence {
                while(!source.exhausted()) {
                    check(!closed)
                    val line=source.readUtf8LineStrict(3L*1024*1024);bytes+=line.toByteArray(Charsets.UTF_8).size
                    require(bytes<=8*1024*1024);yield(line)
                }
            })
        }
        val audio=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
        synchronized(this) {if(closed){audio.release();error("Cancelled")};track=audio}
        try {
            check(audio.write(pcm,0,pcm.size)==pcm.size);audio.play()
            while(!closed && audio.playbackHeadPosition<pcm.size/2)Thread.sleep(30)
        } finally {synchronized(this){if(track===audio){track=null;audio.release()}};pcm.fill(0)}
    }
    override fun close() {synchronized(this){closed=true;call?.cancel();track?.let {runCatching {it.stop()}}}}
}
