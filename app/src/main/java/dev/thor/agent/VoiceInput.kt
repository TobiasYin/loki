package dev.thor.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface PcmInput:AutoCloseable { fun start(); fun read(bytes:ByteArray):Int; fun stop() }
class MicrophoneInput(context:Context):PcmInput {
    private val recorder:AudioRecord
    init {
        require(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
        val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        require(min>0)
        recorder=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION).setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(min,12800)).build()
        check(recorder.state==AudioRecord.STATE_INITIALIZED)
    }
    override fun start() {recorder.startRecording();check(recorder.recordingState==AudioRecord.RECORDSTATE_RECORDING)}
    override fun read(bytes:ByteArray)=recorder.read(bytes,0,bytes.size,AudioRecord.READ_BLOCKING)
    override fun stop() {runCatching {recorder.stop()}}
    override fun close() {stop();recorder.release()}
}

data class VoiceConfig(val apiKey:String?,val appId:String?,val accessKey:String?,val resource:String) {
    fun request(endpoint:String=VolcAsrProtocol.ENDPOINT):Request {
        require(resource in setOf("volc.bigasr.sauc.duration","volc.bigasr.sauc.concurrent","volc.seedasr.sauc.duration","volc.seedasr.sauc.concurrent"))
        val requestId=UUID.randomUUID().toString()
        val request=Request.Builder().url(endpoint).header("X-Api-Resource-Id",resource).header("X-Api-Request-Id",requestId).header("X-Api-Connect-Id",requestId)
        if(!apiKey.isNullOrBlank())request.header("X-Api-Key",apiKey)
        else {require(!appId.isNullOrBlank() && !accessKey.isNullOrBlank());request.header("X-Api-App-Key",appId).header("X-Api-Access-Key",accessKey)}
        return request.build()
    }
}

/** Owns a single foreground hold. No audio files, JS audio buffers, credentials, or retries. */
class VoiceInput(private val config:VoiceConfig,private val inputFactory:()->PcmInput,private val emit:(JSONObject)->Unit,
    private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build(),
    private val endpoint:String=VolcAsrProtocol.ENDPOINT,
    private val recognitionContext:VoiceRecognitionContext?=null,
    private val recognitionContextProvider:(()->VoiceRecognitionContext?)?=null):AutoCloseable {
    private val worker=Executors.newSingleThreadExecutor()
    private val timer=Executors.newSingleThreadScheduledExecutor()
    private val draft=VoiceDraft()
    @Volatile private var socket:WebSocket?=null
    @Volatile private var input:PcmInput?=null
    @Volatile private var stopped=false
    @Volatile private var cancelled=false
    @Volatile private var closed=false
    @Volatile private var finalSeen=false
    private val sendLock=Any()
    private val buffered=java.util.ArrayDeque<ByteArray>()
    private var opened=false
    private var captureDone=false
    private fun event(phase:String,error:String?=null,submit:String?=null,confirmed:String="") {
        emit(obj("phase" to phase,"text" to draft.text,"confirmed" to confirmed,"error" to error,"submit" to submit))
    }
    fun start() {
        val request=config.request(endpoint)
        event("connecting")
        timer.schedule({if(!closed)fail("语音连接或录音超时，请重试")},65,TimeUnit.SECONDS)
        worker.execute {
            try {
                if(!stopped && !cancelled) {
                    val mic=inputFactory();input=mic
                    try {
                        if(!stopped && !cancelled)mic.start()
                        val buffer=ByteArray(6400)
                        while(!stopped && !cancelled && !finalSeen) {
                            val n=mic.read(buffer)
                            if(cancelled || finalSeen)break
                            if(n<=0 && stopped)break
                            check(n>0 && n%2==0)
                            synchronized(sendLock) {
                                if(opened) {val ws=socket ?: error("No socket");check(ws.queueSize()<256*1024 && ws.send(VolcAsrProtocol.audio(buffer.copyOf(n),false).toByteString()))}
                                else {check(buffered.size<50);buffered.addLast(buffer.copyOf(n))}
                            }
                        }
                    } finally {input=null;mic.close()}
                }
                synchronized(sendLock) {
                    captureDone=true
                    if(opened && !cancelled && !closed && !finalSeen)check(socket!!.send(VolcAsrProtocol.audio(ByteArray(0),true).toByteString()))
                }
            } catch(_:Exception) {if(!cancelled && !closed && !finalSeen)fail("麦克风或语音传输失败，请重试")}
        }
        socket=client.newWebSocket(request,object:WebSocketListener() {
            override fun onOpen(webSocket:WebSocket,response:Response) {
                if(cancelled || closed){webSocket.cancel();return}
                socket=webSocket
                // Microphone capture has already started on its own worker. A
                // bounded, caller-owned lookup may overlap the socket handshake;
                // missing hints must never turn a valid recording into an error.
                val context=try {recognitionContextProvider?.invoke() ?: recognitionContext} catch(_:Exception) {recognitionContext}
                synchronized(this@VoiceInput) {
                    // The lookup runs outside this lock so close/cancel remains
                    // immediate. Recheck after it returns before sending any frame.
                    if(cancelled || closed){webSocket.cancel();return}
                    if(!webSocket.send(VolcAsrProtocol.config(UUID.randomUUID().toString(),context).toByteString())) {fail("语音连接未就绪");return}
                    try { synchronized(sendLock) {
                        opened=true
                        while(buffered.isNotEmpty())check(webSocket.send(VolcAsrProtocol.audio(buffered.removeFirst(),false).toByteString()))
                        if(captureDone)check(webSocket.send(VolcAsrProtocol.audio(ByteArray(0),true).toByteString()))
                    } } catch(_:Exception) {fail("语音传输失败，请重试")}
                }
            }
            override fun onMessage(webSocket:WebSocket,bytes:ByteString) {
                if(cancelled || closed || finalSeen)return
                try {
                    accept(VolcAsrProtocol.parse(bytes.toByteArray()))
                } catch(e:AsrServiceError) {fail(e.userMessage())}
                catch(_:Exception) {fail("语音响应格式无效，请重试")}
            }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) {if(!cancelled && !closed && !finalSeen)fail(if(response!=null)"语音连接失败（HTTP ${response.code}），请检查语音设置" else "语音连接失败，请检查网络")}
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String) {if(!closed && !finalSeen)fail("语音连接提前结束，请重试") else webSocket.close(1000,"")}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String) {if(!closed && !finalSeen)fail("语音连接提前结束，请重试")}
        })
        if(closed)socket?.cancel()
    }
    /** Terminal acceptance and its phase publication are atomic with release,
     * cancellation and completion; no recording event can follow terminal empty. */
    @Synchronized private fun accept(result:VolcAsrProtocol.Result) {
        if(closed || cancelled || finalSeen)return
        if(result.final){finalSeen=true;input?.stop()}
        val submit=draft.receive(result)
        if(submit!=null)complete(submit) else event(if(stopped)"finalizing" else "recording",confirmed=result.confirmed)
    }
    @Synchronized fun finish() {
        if(stopped || cancelled || closed)return
        stopped=true;input?.stop()
        val submit=draft.release()
        if(submit!=null)complete(submit) else {
            event("finalizing")
            timer.schedule({if(!closed)fail("等待最终识别超时，文字已保留，可重试或转为文字编辑")},12,TimeUnit.SECONDS)
        }
    }
    @Synchronized private fun complete(text:String) {if(closed || cancelled)return;if(text.isBlank())event("empty") else event("complete",submit=text);shutdown()}
    @Synchronized private fun fail(message:String) {if(closed || cancelled || finalSeen)return;draft.cancel();event("error",message);shutdown()}
    private fun shutdown() {closed=true;stopped=true;input?.stop();socket?.cancel();timer.shutdownNow();worker.shutdown();synchronized(sendLock){buffered.clear()};client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    @Synchronized override fun close() {if(closed)return;cancelled=true;draft.cancel();event("cancelled");shutdown()}
}
