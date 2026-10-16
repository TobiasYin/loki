package dev.thor.agent

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real loopback HTTP/SSE. This server supplies bytes, never DB values, Native
 * acknowledgements, clock overrides, visibility hooks or a model verdict. */
internal class LongBackgroundSseFixture:AutoCloseable {
    private val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
    private val workers=Executors.newCachedThreadPool()
    private val sockets=CopyOnWriteArrayList<Socket>()
    private val failures=CopyOnWriteArrayList<String>()
    val bodies=CopyOnWriteArrayList<String>() // Private test memory; never public evidence.
    val requestCount=AtomicInteger()
    val longArrived=CountDownLatch(1)
    val startBackground=CountDownLatch(1)
    val lastBackgroundDelta=CountDownLatch(1)
    val finishLong=CountDownLatch(1)
    val stopArrived=CountDownLatch(1)
    val stopRelease=CountDownLatch(1)
    val seconds=380
    val port get()=server.localPort
    val fullText="LONG-BEGIN\n"+(1..seconds).joinToString(""){marker(it)}
    fun marker(index:Int)="[[BG${index.toString().padStart(3,'0')}]]\n"
    fun errors()=failures.toList()
    init {workers.execute {while(!server.isClosed)try {val socket=server.accept();sockets.add(socket);workers.execute {serve(socket)}}catch(_:Exception){break}}}
    private fun event(value:JSONObject)="event: ${value.getString("type")}\ndata: $value\n\n"
    private fun prefix(index:Int)=listOf(
        obj("type" to "response.created","response" to obj("id" to "long_resp_$index","status" to "in_progress")),
        obj("type" to "response.output_item.added","output_index" to 0,"item" to obj("type" to "message","id" to "long_msg_$index","role" to "assistant","content" to JSONArray(),"status" to "in_progress")),
        obj("type" to "response.content_part.added","output_index" to 0,"content_index" to 0,"item_id" to "long_msg_$index","part" to obj("type" to "output_text","text" to "","annotations" to JSONArray()))
    ).joinToString(""){event(it)}
    private fun delta(index:Int,text:String)=event(obj("type" to "response.output_text.delta","output_index" to 0,"content_index" to 0,"item_id" to "long_msg_$index","delta" to text))
    private fun suffix(index:Int,text:String):String {
        val item=obj("type" to "message","id" to "long_msg_$index","role" to "assistant","status" to "completed","content" to JSONArray().put(obj("type" to "output_text","text" to text,"annotations" to JSONArray())))
        return event(obj("type" to "response.output_item.done","output_index" to 0,"item" to item))+event(obj("type" to "response.completed","response" to obj("id" to "long_resp_$index","status" to "completed","output" to JSONArray().put(item),"usage" to obj("input_tokens" to 10,"output_tokens" to 1000,"total_tokens" to 1010))))
    }
    private fun probe():String {
        val item=obj("type" to "function_call","id" to "fc_probe","call_id" to "probe_once","name" to "probe_picture","arguments" to "{}","status" to "completed")
        return listOf(obj("type" to "response.created","response" to obj("id" to "probe_1","status" to "in_progress")),obj("type" to "response.output_item.added","output_index" to 0,"item" to JSONObject(item.toString()).put("arguments","").put("status","in_progress")),obj("type" to "response.function_call_arguments.delta","output_index" to 0,"item_id" to "fc_probe","delta" to "{}"),obj("type" to "response.output_item.done","output_index" to 0,"item" to item),obj("type" to "response.completed","response" to obj("id" to "probe_1","status" to "completed","output" to JSONArray().put(item),"usage" to obj("input_tokens" to 10,"output_tokens" to 8,"total_tokens" to 18)))).joinToString(""){event(it)}
    }
    private fun serve(socket:Socket) {
        var index=0
        try {socket.use {
            val input=it.getInputStream()
            fun line():String? {val bytes=java.io.ByteArrayOutputStream();while(bytes.size()<65536){val value=input.read();if(value<0)return null;if(value==10)return bytes.toString("US-ASCII").trimEnd('\r');bytes.write(value)};error("Fixture header limit")}
            check(line()?.startsWith("POST ")==true)
            val headers=mutableMapOf<String,String>()
            while(true){val h=line() ?: error("Incomplete headers");if(h.isEmpty())break;headers[h.substringBefore(':').lowercase()]=h.substringAfter(':').trim()}
            val body=ByteArray(headers.getValue("content-length").toInt().also {n->require(n in 1..1024*1024)})
            var n=0;while(n<body.size){val read=input.read(body,n,body.size-n);check(read>0);n+=read}
            index=requestCount.incrementAndGet();bodies.add(body.toString(Charsets.UTF_8))
            val text=when(index){2->"PROBE_OK";3->fullText;4->"LONG-FOLLOWUP-OK";5->"STOP-PARTIAL-RETAIN";6->"AFTER-STOP-FOLLOWUP-OK";else->"UNEXPECTED-REQUEST"}
            val chunks=when(index){1->listOf(probe());3->listOf(prefix(index),delta(index,"LONG-BEGIN\n"))+(1..seconds).map {i->delta(index,marker(i))}+suffix(index,text);else->listOf(prefix(index),delta(index,text),suffix(index,text))}
            val size=chunks.sumOf {x->x.toByteArray(Charsets.UTF_8).size}
            val output=it.getOutputStream();output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray())
            fun write(s:String){output.write(s.toByteArray(Charsets.UTF_8));output.flush()}
            if(index==3) {
                write(chunks[0]);write(chunks[1]);longArrived.countDown()
                check(startBackground.await(60,TimeUnit.SECONDS))
                val started=SystemClock.elapsedRealtime()
                for(i in 1..seconds) {
                    val target=started+i*1000L
                    while(SystemClock.elapsedRealtime()<target)Thread.sleep((target-SystemClock.elapsedRealtime()).coerceIn(1,100))
                    write(chunks[i+1])
                }
                lastBackgroundDelta.countDown()
                check(finishLong.await(60,TimeUnit.SECONDS));write(chunks.last())
            } else if(index==5) {
                write(chunks[0]);write(chunks[1]);stopArrived.countDown()
                stopRelease.await(60,TimeUnit.SECONDS);write(chunks.last())
            } else chunks.forEach {chunk->write(chunk)}
        }}catch(t:Throwable){if(!server.isClosed && index!=5)failures.add("request$index:${t.javaClass.simpleName}")}
    }
    override fun close(){startBackground.countDown();finishLong.countDown();stopRelease.countDown();server.close();sockets.forEach {runCatching {it.close()}};workers.shutdownNow()}
}
