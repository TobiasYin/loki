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
    private fun serviceReply(code:Int,json:Boolean=false):ByteString {
        if(json)return VolcAsrProtocol.packet(9,0,1,obj("code" to code,"message" to "PRIVATE_PROVIDER_BODY").toString().toByteArray()).toByteString()
        val payload="PRIVATE_PROVIDER_BODY".toByteArray()
        return ByteBuffer.allocate(12+payload.size).put(0x11).put(0xf0.toByte()).put(0).put(0).putInt(code).putInt(payload.size).put(payload).array().toByteString()
    }
    private fun voice(server:MockWebServer,input:Input,events:CopyOnWriteArrayList<JSONObject>,notify:(JSONObject)->Unit={})=
        VoiceInput(VoiceConfig("local-voice-fixture",null,null,"volc.bigasr.sauc.duration"),{input},{e->events.add(e);notify(e)},endpoint=server.url("/").toString().replace("http:","ws:"))
    private fun assertNoSubmission(events:List<JSONObject>) {
        assertFalse(events.any {!it.isNull("submit")});assertFalse(events.any {it.optString("phase")=="complete"})
    }
    @Test fun silenceBeforeReleaseClearsPartialAndSurvivesRealLateSocketFailureWithoutSubmitting() {
        for(code in listOf(20000003,1013))for(json in listOf(false,true)) {
            val server=MockWebServer();val received=CountDownLatch(1);val partial=java.util.concurrent.atomic.AtomicBoolean()
            val sent=java.util.concurrent.atomic.AtomicBoolean();val serverFailure=CountDownLatch(1)
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable>();var shutdownAttempted=false
            fun shutdownServer() {if(!shutdownAttempted){shutdownAttempted=true;server.shutdown()}}
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) {failure.set(t);serverFailure.countDown()}
                override fun onMessage(webSocket:WebSocket,bytes:ByteString) {
                    if((bytes[1].toInt() and 255)==0x20 && sent.compareAndSet(false,true)) {
                        webSocket.send(reply("stale partial"));webSocket.send(serviceReply(code,json))
                    }
                }
            }));server.start()
            val events=CopyOnWriteArrayList<JSONObject>();val input=Input()
            val voice=voice(server,input,events) {e->
                if(e.optString("text")=="stale partial")partial.set(true)
                if(partial.get() && e.optString("phase")=="recording" && e.optString("text")=="")received.countDown()
            }
            try {
                voice.start();assertTrue(received.await(5,TimeUnit.SECONDS));assertNoSubmission(events)
                assertFalse(events.any {it.optString("phase") in setOf("empty","error")})
                // Server-side MockWebSocket has no client Call to cancel. Close
                // its actual TCP sockets instead, after the accepted terminal.
                shutdownServer()
                assertTrue("Closing the server must cause an observed real WebSocket transport failure",serverFailure.await(5,TimeUnit.SECONDS))
                assertTrue("The connection failed through real I/O",failure.get() is java.io.IOException)
                Thread.sleep(150);assertFalse(events.any {it.optString("phase")=="error"});assertNoSubmission(events)
                voice.finish();voice.finish();assertEquals(1,events.count {it.optString("phase")=="empty"})
                assertEquals("",events.single {it.optString("phase")=="empty"}.getString("text"));assertNoSubmission(events)
            }finally {voice.close();shutdownServer()}
        }
    }
    @Test fun silenceAfterReleaseEmitsOneEmptyForBothFrameFormsWithoutSendingAnEmptyMessage() {
        for(code in listOf(20000003,1013))for(json in listOf(false,true)) {
            val server=MockWebServer();val interim=CountDownLatch(1);val empty=CountDownLatch(1);val sent=java.util.concurrent.atomic.AtomicBoolean()
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onMessage(webSocket:WebSocket,bytes:ByteString) {
                    val type=bytes[1].toInt() and 255
                    if(type==0x20 && sent.compareAndSet(false,true))webSocket.send(reply("stale partial"))
                    if(type==0x22){webSocket.send(serviceReply(code,json));webSocket.send(reply("late duplicate",true))}
                }
            }));server.start()
            val events=CopyOnWriteArrayList<JSONObject>();val voice=voice(server,Input(),events) {e->if(e.optString("text")=="stale partial")interim.countDown();if(e.optString("phase")=="empty")empty.countDown()}
            try {
                voice.start();assertTrue(interim.await(5,TimeUnit.SECONDS));voice.finish();assertTrue(empty.await(5,TimeUnit.SECONDS));voice.finish()
                assertEquals(1,events.count {it.optString("phase")=="empty"});assertFalse(events.any {it.optString("phase")=="error"});assertNoSubmission(events)
            }finally {voice.close();server.shutdown()}
        }
    }
    @Test fun successfulEmptyAndJsonNullTerminalNeverSubmitPartialOrLiteralNull() {
        for(text in listOf<Any?>("",null)) {
            val server=MockWebServer();val interim=CountDownLatch(1);val empty=CountDownLatch(1);val sent=java.util.concurrent.atomic.AtomicBoolean()
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onMessage(webSocket:WebSocket,bytes:ByteString) {
                    val type=bytes[1].toInt() and 255
                    if(type==0x20 && sent.compareAndSet(false,true))webSocket.send(reply("stale partial"))
                    if(type==0x22)webSocket.send(VolcAsrProtocol.packet(9,2,1,obj("code" to 20000000,"result" to obj("text" to text)).toString().toByteArray()).toByteString())
                }
            }));server.start()
            val events=CopyOnWriteArrayList<JSONObject>();val voice=voice(server,Input(),events) {e->if(e.optString("text")=="stale partial")interim.countDown();if(e.optString("phase")=="empty")empty.countDown()}
            try {
                voice.start();assertTrue(interim.await(5,TimeUnit.SECONDS));voice.finish();assertTrue(empty.await(5,TimeUnit.SECONDS))
                assertEquals("",events.single {it.optString("phase")=="empty"}.getString("text"));assertNoSubmission(events);assertFalse(events.any {it.optString("phase")=="error"})
            }finally {voice.close();server.shutdown()}
        }
    }
    @Test fun realServiceErrorsIncluding45000002StayErrorsWithFixedMessagesAndNoDraftSubmission() {
        for(code in listOf(45000002,45000081,45000151,1002,55000031,55123456)) {
            val server=MockWebServer();val failed=CountDownLatch(1);val sent=java.util.concurrent.atomic.AtomicBoolean()
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onMessage(webSocket:WebSocket,bytes:ByteString) {
                    if((bytes[1].toInt() and 255)==0x20 && sent.compareAndSet(false,true)){webSocket.send(reply("do not submit partial"));webSocket.send(serviceReply(code,code%2==0))}
                }
            }));server.start()
            val events=CopyOnWriteArrayList<JSONObject>();val voice=voice(server,Input(),events) {e->if(e.optString("phase")=="error")failed.countDown()}
            try {
                voice.start();assertTrue(failed.await(5,TimeUnit.SECONDS));voice.finish();assertNoSubmission(events)
                assertEquals(1,events.count {it.optString("phase")=="error"});assertFalse(events.any {it.optString("phase")=="empty"})
                val message=events.single {it.optString("phase")=="error"}.getString("error");assertTrue(message.contains(code.toString()));assertFalse(message.contains("PRIVATE_PROVIDER_BODY"))
            }finally {voice.close();server.shutdown()}
        }
    }
    @Test fun malformedSilenceIsAnErrorAndCancellationAfterTerminalStillNeverEmitsEmpty() {
        for(cancel in listOf(false,true)) {
            val server=MockWebServer();val seen=CountDownLatch(1);val sent=java.util.concurrent.atomic.AtomicBoolean();val partial=java.util.concurrent.atomic.AtomicBoolean()
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onMessage(webSocket:WebSocket,bytes:ByteString) {
                    if((bytes[1].toInt() and 255)==0x20 && sent.compareAndSet(false,true)) {
                        webSocket.send(reply("partial"));val packet=serviceReply(20000003)
                        webSocket.send(if(cancel)packet else packet.substring(0,packet.size-1))
                    }
                }
            }));server.start()
            val events=CopyOnWriteArrayList<JSONObject>();val voice=voice(server,Input(),events) {e->
                if(e.optString("text")=="partial")partial.set(true)
                if(e.optString("phase")=="error" || partial.get() && e.optString("phase")=="recording" && e.optString("text")=="")seen.countDown()
            }
            try {
                voice.start();assertTrue(seen.await(5,TimeUnit.SECONDS));if(cancel)voice.close();voice.finish();assertNoSubmission(events)
                assertFalse(events.any {it.optString("phase")=="empty"});assertEquals(if(cancel)0 else 1,events.count {it.optString("phase")=="error"})
                if(cancel)assertEquals(1,events.count {it.optString("phase")=="cancelled"})
            }finally {voice.close();server.shutdown()}
        }
    }

    @Test fun terminalPhasePublicationIsAtomicWithConcurrentRelease() {
        val server=MockWebServer();val sent=java.util.concurrent.atomic.AtomicBoolean();val partial=java.util.concurrent.atomic.AtomicBoolean()
        val terminalPublication=CountDownLatch(1);val allowPublication=CountDownLatch(1);val finishStarted=CountDownLatch(1);val finishDone=CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onMessage(webSocket:WebSocket,bytes:ByteString) {
                if((bytes[1].toInt() and 255)==0x20 && sent.compareAndSet(false,true)){webSocket.send(reply("partial"));webSocket.send(serviceReply(20000003))}
            }
        }));server.start()
        val events=CopyOnWriteArrayList<JSONObject>();val input=Input()
        val voice=VoiceInput(VoiceConfig("local-voice-fixture",null,null,"volc.bigasr.sauc.duration"),{input},{event->
            if(event.optString("text")=="partial")partial.set(true)
            if(partial.get() && event.optString("phase")=="recording" && event.optString("text")=="") {
                terminalPublication.countDown();check(allowPublication.await(5,TimeUnit.SECONDS))
            }
            events.add(event)
        },endpoint=server.url("/").toString().replace("http:","ws:"))
        var finisher:Thread?=null
        try {
            voice.start();assertTrue(terminalPublication.await(5,TimeUnit.SECONDS))
            val releaseThread=Thread {finishStarted.countDown();try {voice.finish()}finally {finishDone.countDown()}}
            finisher=releaseThread;releaseThread.start()
            assertTrue(finishStarted.await(5,TimeUnit.SECONDS))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(finishDone.count>0 && releaseThread.state!=Thread.State.BLOCKED && System.nanoTime()<deadline)Thread.sleep(2)
            assertEquals("Release must wait for the accepted terminal phase publication",Thread.State.BLOCKED,releaseThread.state)
            assertEquals(1L,finishDone.count);assertFalse(events.any {it.optString("phase")=="empty"})
            allowPublication.countDown();assertTrue(finishDone.await(5,TimeUnit.SECONDS))
            assertEquals("empty",events.last().getString("phase"));assertEquals(1,events.count {it.optString("phase")=="empty"});assertNoSubmission(events)
        }finally {allowPublication.countDown();finisher?.join(5000);voice.close();server.shutdown()}
    }

}
