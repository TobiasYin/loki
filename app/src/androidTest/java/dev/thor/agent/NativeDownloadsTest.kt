package dev.thor.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.*
import dev.thor.tools.diagnostic.NativeDownloads
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class NativeDownloadsTest {
    private fun expect(code:Code,action:()->Unit) {try {action();fail("Expected $code")}catch(t:ToolFailure){assertEquals(code,t.code)}}
    @Test fun realHttpMethodsRedirectsChecksumsBoundsAndCancellation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val server=ServerSocket(0);val pool=Executors.newCachedThreadPool();val sockets=java.util.concurrent.CopyOnWriteArrayList<Socket>()
        val prefix="http://127.0.0.1:${server.localPort}"
        val downloads=NativeDownloads(context);val cp=Checkpoint({},Cancellation())
        pool.submit {while(!server.isClosed)try {val s=server.accept();sockets.add(s);pool.submit {
            s.use {socket->val input=socket.getInputStream().bufferedReader();val request=input.readLine();val headers=linkedMapOf<String,String>();while(true){val line=input.readLine() ?: break;if(line.isEmpty())break;headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
                val chars=CharArray(headers["content-length"]?.toInt() ?: 0);var at=0;while(at<chars.size){val n=input.read(chars,at,chars.size-at);if(n<0)break;at+=n}
                val path=request.split(' ')[1];val status=if(path=="/missing")404 else if(path in setOf("/redirect","/redirect-other"))302 else 200
                val body=if(path=="/echo")"${request.substringBefore(' ')}:${String(chars)}".toByteArray() else if(path=="/escaping")ByteArray(65000){1} else if(path=="/auth")(headers["authorization"] ?: "absent").toByteArray() else if(path=="/headers")(headers["x-test"] ?: "absent").toByteArray() else "download-body".toByteArray()
                val out=socket.getOutputStream();val extra=if(path=="/redirect")"Location: /file\r\n" else if(path=="/redirect-other")"Location: http://localhost:${server.localPort}/headers\r\n" else ""
                out.write("HTTP/1.1 $status OK\r\nContent-Length: ${body.size}\r\nContent-Type: text/plain\r\n${extra}Connection: close\r\n\r\n".toByteArray());out.write(body);out.flush()
            }
        }}catch(_:Exception){}}
        try {
            val response=downloads.execute("web.request",mapOf("url" to "$prefix/echo","method" to "POST","body" to "hello"),cp)
            assertEquals(200,response["status"]);assertEquals("POST:hello",response["text"])
            assertEquals(404,downloads.execute("web.request",mapOf("url" to "$prefix/missing"),cp)["status"])
            assertEquals("present",downloads.execute("web.request",mapOf("url" to "$prefix/headers","headers" to listOf(mapOf("name" to "X-Test","value" to "present"))),cp)["text"])
            assertEquals("absent",downloads.execute("web.request",mapOf("url" to "$prefix/redirect-other","headers" to listOf(mapOf("name" to "X-Test","value" to "present"))),cp)["text"])
            assertEquals("PATCH:hello",downloads.execute("web.request",mapOf("url" to "$prefix/echo","method" to "PATCH","body" to "hello"),cp)["text"])
            assertEquals("Bearer explicit-user-token",downloads.execute("web.request",mapOf("url" to "$prefix/auth","headers" to listOf(mapOf("name" to "Authorization","value" to "Bearer explicit-user-token"))),cp)["text"])
            val escaped=downloads.execute("web.request",mapOf("url" to "$prefix/escaping"),cp)
            assertEquals(true,escaped["truncated"]);assertTrue(org.json.JSONObject(escaped).toString().toByteArray().size<=56000)
            val short=downloads.execute("web.request",mapOf("url" to "$prefix/file","maxBytes" to 3),cp);assertEquals("dow",short["text"]);assertEquals(true,short["truncated"])
            val record=downloads.execute("files.download",mapOf("url" to "$prefix/redirect","filename" to "test.txt","maxBytes" to 1024),cp)
            val id=record["downloadId"] as String;assertEquals(hash("download-body".toByteArray()),record["sha256"]);assertTrue((record["finalUrl"] as String).endsWith("/file"))
            assertEquals(record["sha256"],NativeDownloads(context).info(id).getString("sha256"))
            expect(Code.INVALID_ARGUMENT){downloads.installPreview(id,record["sha256"] as String)}
            expect(Code.HASH_CONFLICT){downloads.execute("files.download",mapOf("url" to "$prefix/file","sha256" to "0".repeat(64),"maxBytes" to 1024),cp)}
            expect(Code.LIMIT_EXCEEDED){downloads.execute("files.download",mapOf("url" to "$prefix/file","maxBytes" to 3),cp)}
            expect(Code.IO_ERROR){downloads.execute("files.download",mapOf("url" to "$prefix/missing"),cp)}
            expect(Code.INVALID_ARGUMENT){downloads.execute("files.download",mapOf("url" to "$prefix/file","filename" to "../bad.apk"),cp)}
            expect(Code.INVALID_ARGUMENT){downloads.execute("web.request",mapOf("url" to "file:///data/local/tmp/test"),cp)}
            expect(Code.PERMISSION_MISSING){downloads.execute("web.request",mapOf("url" to "$prefix/file","headers" to listOf(mapOf("name" to "Host","value" to "evil"))),cp)}
            expect(Code.INVALID_ARGUMENT){downloads.execute("web.request",mapOf("url" to "$prefix/file","headers" to listOf(mapOf("name" to "X-Test","value" to "x\r\ny"))),cp)}
            downloads.apkFile(id).writeText("tampered")
            expect(Code.HASH_CONFLICT){downloads.info(id)}
            val cancel=Cancellation().apply {cancel()}
            expect(Code.CANCELLED){downloads.execute("files.download",mapOf("url" to "$prefix/file"),Checkpoint({},cancel))}
        } finally {server.close();sockets.forEach {runCatching {it.close()}};pool.shutdownNow()}
    }
}
