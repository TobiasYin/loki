package dev.thor.agent

import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class VoiceTransportTest {
    private class Input:PcmInput {
        @Volatile var stopped=false
        val reads=AtomicInteger();val closes=AtomicInteger()
        override fun start() {}
        override fun read(bytes:ByteArray):Int {Thread.sleep(20);reads.incrementAndGet();bytes.fill(0);return if(stopped)-1 else bytes.size}
        override fun stop() {stopped=true}
        override fun close() {closes.incrementAndGet()}
    }
    private fun reply(text:String,last:Boolean=false):ByteString {
        val payload=obj("result" to obj("text" to text)).toString().toByteArray()
        return ByteBuffer.allocate(12+payload.size).put(0x11).put(if(last)0x93.toByte() else 0x91.toByte()).put(0x10).put(0).putInt(if(last)-1 else 1).putInt(payload.size).put(payload).array().toByteString()
    }
    @Test fun microphonePacketsStreamThroughSocketAndReleaseUsesCorrectedFinalOnce() {
        val server=MockWebServer();val interim=CountDownLatch(1);val done=CountDownLatch(1);val packets=AtomicInteger();val terminal=AtomicInteger()
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onMessage(webSocket:WebSocket,bytes:ByteString) {
                val type=bytes[1].toInt() and 255
                if(type==0x20 && packets.incrementAndGet()==1) {webSocket.send(reply("你好事"));webSocket.send(reply("你好世界"))}
                if(type==0x22) {terminal.incrementAndGet();webSocket.send(reply("你好世界。",true));webSocket.send(reply("重复",true))}
            }
        }));server.start()
        val input=Input();val events=CopyOnWriteArrayList<JSONObject>()
        val voice=VoiceInput(VoiceConfig("local-test",null,null,"volc.bigasr.sauc.duration"),{input},{e->events.add(e);if(e.optString("text")=="你好世界")interim.countDown();if(e.optString("phase")=="complete")done.countDown()},endpoint=server.url("/").toString().replace("http:","ws:"))
        try {
            voice.start();assertTrue(interim.await(5,TimeUnit.SECONDS));assertEquals(0,events.count {it.optString("phase")=="complete"})
            voice.finish();voice.finish();assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals(1,events.count {it.optString("phase")=="complete"});assertEquals("你好世界。",events.single {it.optString("phase")=="complete"}.getString("submit"))
            assertEquals(1,terminal.get());assertTrue(packets.get()>0);assertEquals("local-test",server.takeRequest(1,TimeUnit.SECONDS)!!.getHeader("X-Api-Key"))
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(input.closes.get()==0 && System.nanoTime()<end)Thread.sleep(10)
            assertEquals(1,input.closes.get())
            assertFalse(events.toString().contains("local-test"))
        } finally {voice.close();server.shutdown()}
    }
    @Test fun cancellationAndSocketFailureNeverSubmitDrafts() {
        for(cancel in listOf(false,true)) {
            val server=MockWebServer();val interim=CountDownLatch(1);val failed=CountDownLatch(1)
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onMessage(webSocket:WebSocket,bytes:ByteString) {if((bytes[1].toInt() and 255)==0x20){webSocket.send(reply("草稿"));if(!cancel)webSocket.close(1000,"")}}
            }));server.start()
            val input=Input();val events=CopyOnWriteArrayList<JSONObject>()
            val voice=VoiceInput(VoiceConfig("local-test",null,null,"volc.bigasr.sauc.duration"),{input},{e->events.add(e);if(e.optString("text")=="草稿")interim.countDown();if(e.optString("phase")=="error")failed.countDown()},endpoint=server.url("/").toString().replace("http:","ws:"))
            try {voice.start();assertTrue(interim.await(5,TimeUnit.SECONDS));if(cancel)voice.close() else assertTrue(failed.await(5,TimeUnit.SECONDS));voice.finish();assertFalse(events.any {it.optString("phase")=="complete"})}
            finally {voice.close();server.shutdown()}
        }
    }
}
