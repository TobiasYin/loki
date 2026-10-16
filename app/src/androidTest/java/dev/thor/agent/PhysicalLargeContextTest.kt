package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real WebView/Native RPC/SQLite component regression. No cloud generation, tool
 * execution, screen input or model-completion claim is made by this synthetic turn. */
@RunWith(AndroidJUnit4::class)
class PhysicalLargeContextTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val readerDelegate=lazy {SqliteStore(context,"product.db")}
    private val reader get()=readerDelegate.value
    @After fun closeReader(){if(readerDelegate.isInitialized())reader.close()}
    private fun <T> read(block:(SQLiteDatabase)->T):T {
        val end=SystemClock.elapsedRealtime()+40000
        while(true)try {return block(reader.readableDatabase)}catch(e:android.database.sqlite.SQLiteDatabaseLockedException){if(SystemClock.elapsedRealtime()>=end)throw e;Thread.sleep(100)}
    }
    private fun rows(table:String,runId:String?=null):List<JSONObject> {
        require(table in setOf("sessions","runs","messages","events"))
        return read {db->
            val where=if(runId==null)"" else if(table=="runs")" WHERE id=?" else " WHERE runId=?"
            db.rawQuery("SELECT payload FROM $table$where ORDER BY ${if(table=="events")"sequence" else "rowid"}",runId?.let {arrayOf(it)}).use {c->buildList {while(c.moveToNext())add(JSONObject(c.getString(0)))}}
        }
    }
    private fun historyIds():Map<String,Set<String>> = read {db->
        listOf("sessions","runs","messages","events","attachments").associateWith {table->db.rawQuery("SELECT id FROM $table",null).use {c->buildSet {while(c.moveToNext())add(c.getString(0))}}}
    }
    private fun sha(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun until(seconds:Int=40,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+seconds*1000L
        while(SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(50)}
        fail("Large-context component acknowledgement/setup timed out")
    }
    private fun js(web:WebView,script:String):String {
        val latch=CountDownLatch(1);var value=""
        ins.runOnMainSync {web.evaluateJavascript(script){value=it;latch.countDown()}}
        assertTrue("WebView evaluation did not acknowledge",latch.await(5,TimeUnit.SECONDS));return value
    }
    private fun ui(web:WebView,data:JSONObject){js(web,"ThorUI.postMessage(${JSONObject.quote(data.toString())});true")}

    private fun rpc(web:WebView,connection:String,op:String,data:JSONObject,transport:String="base64",conflictingInline:Boolean=false,seconds:Int=10):JSONObject {
        val requestId="large-context-${id()}"
        val header=obj("type" to "host.rpc","bridgeVersion" to 1,"connectionId" to connection,"requestId" to requestId,"op" to op,"dataInBody" to true)
        // Keep this conflict header small so it exercises inline/body ambiguity,
        // independently of the separate 64KiB metadata-size limit.
        if(conflictingInline)header.put("data",obj("forbiddenInline" to true))
        val quotedId=JSONObject.quote(requestId)
        val script="""
          (()=>{
            const header=JSON.parse(${JSONObject.quote(header.toString())});
            const body=new TextEncoder().encode(${JSONObject.quote(data.toString())});
            window.__largeSent[$quotedId]=performance.now();
            if(${JSONObject.quote(transport)}==='binary'){
              const metadata=new TextEncoder().encode(JSON.stringify(header));
              const packet=new Uint8Array(4+metadata.length+body.length);
              new DataView(packet.buffer).setUint32(0,metadata.length,false);
              packet.set(metadata,4);packet.set(body,4+metadata.length);
              ThorNative.postMessage(packet.buffer);
            }else{
              const chunks=[];
              for(let offset=0;offset<body.length;offset+=8192)chunks.push(String.fromCharCode(...body.subarray(offset,offset+8192)));
              ThorNative.postMessage(JSON.stringify({...header,bytes:btoa(chunks.join(''))}));
            }
            return true;
          })()
        """.trimIndent()
        js(web,script)
        until(seconds){js(web,"Object.prototype.hasOwnProperty.call(window.__largeAck,$quotedId)")=="true"}
        val encoded=js(web,"JSON.stringify(window.__largeAck[$quotedId])")
        val ack=JSONObject(JSONArray("[$encoded]").getString(0))
        assertEquals("host.result",ack.getJSONObject("message").getString("type"))
        assertEquals(requestId,ack.getJSONObject("message").getString("requestId"))
        assertTrue("RPC must acknowledge promptly, not wait for the 185s runtime timeout",ack.getDouble("elapsedMs")<seconds*1000)
        return ack.put("transport",transport).put("payloadBytes",data.toString().toByteArray(Charsets.UTF_8).size).put("payloadSha256",sha(data.toString()))
    }
    private fun successful(ack:JSONObject) {
        val message=ack.getJSONObject("message")
        assertTrue("RPC rejected: $message",message.isNull("code"))
        assertTrue("Host rejected persistence",message.getJSONObject("value").isNull("error"))
    }

    @Test fun largeUtf8TurnPersistsAndResumesWithoutCloudOrContextLoss() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("physicalAcceptance")=="true")
        assertEquals("AYN Thor",android.os.Build.MODEL)
        val profiles=context.getSharedPreferences("profiles",0);val credentials=context.getSharedPreferences("native-credentials",0)
        assertEquals("passed",profiles.getString("probe",null))
        val profileBefore=profiles.all.toMap();val credentialsBefore=credentials.all.toMap()
        val terminals=setOf("completed","failed","cancelled","interrupted")
        assertFalse("Do not launch over an active user task",rows("runs").any {it.optString("status") !in terminals})
        val before=historyIds()
        val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay")!!.toInt()).toBundle()) as MainActivity
        val ready=EmulatorRuntimeTestSupport.awaitReady(activity)
        val web=ready.web
        val executionWeb=ready.executionWeb
        val host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val restoreSession=args.getString("restoreSession") ?: host.selectedSession()
        val active=linkedMapOf<String,JSONObject>()
        var installedObserver=false
        val evidence=obj("schemaVersion" to 1,"evidenceMode" to "manual_platform_api","scope" to "real WebView/Native bridge/SQLite component only; synthetic DomainTurn; no cloud or input execution",
            "cloudStarted" to false,"modelExecutionProven" to false,"systemInjectionProven" to false,"acks" to JSONArray(),"cleanupErrors" to JSONArray(),"passed" to false)
        fun save(){File(context.filesDir,"physical-large-context.json").writeText(evidence.toString(2))}
        try {
            until {js(web,"window.productReady===true && !document.getElementById('new').disabled")=="true"}
            assertFalse(host.hasActiveRuns())
            val previous=host.selectedSession();ui(web,obj("op" to "new"));until {host.selectedSession()!=previous}
            val sid=host.selectedSession();evidence.put("sessionId",sid)
            for(cap in listOf("debug","perception","search","screen_control"))ui(web,obj("op" to "capability","sessionId" to sid,"capability" to cap,"enabled" to false))
            until {val caps=rows("sessions").single {it.getString("id")==sid}.getJSONObject("capabilities");listOf("debug","perception","search","screen_control").all {!caps.getBoolean(it)}}
            js(executionWeb,"""
                window.__largeAck={};window.__largeSent={};window.__largePrevious=ThorNative.onmessage;
                ThorNative.onmessage=function(event){
                  try {
                    if(typeof event.data==='string'){
                      const message=JSON.parse(event.data);
                      if(message.type==='host.result' && Object.prototype.hasOwnProperty.call(window.__largeSent,message.requestId))
                        window.__largeAck[message.requestId]={message,elapsedMs:performance.now()-window.__largeSent[message.requestId]};
                    }
                  }finally{window.__largePrevious.call(ThorNative,event);}
                };true
            """.trimIndent());installedObserver=true
            val first=host.prepareForRunner("大上下文组件回归：仅验证持久化和追问恢复，不调用模型或设备输入。").get(10,TimeUnit.SECONDS)
            assertEquals(sid,first.getJSONObject("lease").getString("sessionId"));assertEquals(0,first.getJSONArray("history").length())
            val firstLease=first.getJSONObject("lease");val runId=firstLease.getString("runId");active[runId]=firstLease
            val connection=first.getJSONObject("network").getJSONObject("binding").getString("connectionId")
            val userText="中文上下文保留完整。组件测试，不调用模型或设备输入。段落编号甲乙丙丁。🧭🎮🌾\n".repeat(2400)
            val toolText="合成工具结果，仅作为序列化数据；没有实际联网、截图或设备控制。核对字符：星露谷、上下屏、长篇记录。\n".repeat(1300)
            val callId="synthetic-component-${id()}";val turnId=rows("runs",runId).single().getJSONObject("turn").getString("turnId")
            val turn=obj("turnId" to turnId,"runId" to runId,"observedAt" to System.currentTimeMillis(),"source" to JSONArray(),"complete" to false,
                "messages" to JSONArray().put(obj("kind" to "user","content" to JSONArray().put(obj("type" to "text","text" to userText))))
                    .put(obj("kind" to "assistant","text" to "组件占位调用，未执行任何工具。","calls" to JSONArray().put(obj("id" to callId,"name" to "web_fetch","arguments" to obj("url" to "https://example.invalid/component-only-never-fetched")))))
                    .put(obj("kind" to "tool_result","toolCallId" to callId,"toolName" to "web_fetch","isError" to false,"content" to JSONArray().put(obj("type" to "text","text" to toolText))))
                    .put(obj("kind" to "assistant","text" to "组件占位结果，不代表真实模型完成。","calls" to JSONArray())))
            val body=JSONObject(firstLease.toString()).put("kind","turn").put("value",turn)
            val turnBytes=turn.toString().toByteArray(Charsets.UTF_8).size
            assertTrue("Regression must cross the old 128KiB RPC failure size",turnBytes>128*1024)
            evidence.put("firstRunId",runId).put("turnId",turnId).put("turnBytes",turnBytes).put("turnSha256",sha(turn.toString())).put("bodyBytes",body.toString().toByteArray(Charsets.UTF_8).size)
            val originalRun=rows("runs",runId).single().toString()
            val bad=rpc(executionWeb,connection,"persist",body,conflictingInline=true,seconds=5)
            evidence.put("conflictingInlineAndBody",bad)
            assertEquals("INVALID_REQUEST",bad.getJSONObject("message").getString("code"))
            assertEquals("Malformed body must not write any part of the run",sha(originalRun),sha(rows("runs",runId).single().toString()))
            evidence.put("badBodyDidNotWrite",true)
            val transports=mutableListOf("base64")
            val bridge=MainActivity::class.java.getDeclaredField("bridge").apply {isAccessible=true}.get(activity)
            if(bridge.javaClass.getMethod("getArrayBufferSupported").invoke(bridge)==true)transports.add("binary")
            evidence.put("binarySupported",transports.contains("binary"))
            for(transport in transports) {
                val ack=rpc(executionWeb,connection,"persist",body,transport);evidence.getJSONArray("acks").put(ack);successful(ack)
                val stored=rows("runs",runId).single().getJSONObject("turn")
                assertEquals("Persisted large turn differs byte-for-byte",sha(turn.toString()),sha(stored.toString()))
                assertEquals(userText,stored.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
                assertEquals(toolText,stored.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("text"))
                save()
            }
            successful(rpc(executionWeb,connection,"end",firstLease));active.remove(runId)
            assertEquals("Component execution must never claim model completion","failed",rows("runs",runId).single().getString("status"))
            // Use the real bridge in both directions: the large recovered history must
            // reach JavaScript in host.result, rather than being read through Java directly.
            val secondAck=rpc(executionWeb,connection,"begin",obj("sessionId" to sid,"text" to "继续之前的上下文；仍然只验证组件，不启动云端请求。"))
            successful(secondAck)
            val second=secondAck.getJSONObject("message").getJSONObject("value")
            // beginAck contains lease/network credentials; only history metrics may be evidence.
            evidence.put("secondBeginAckElapsedMs",secondAck.getDouble("elapsedMs"))
                .put("restoredHistoryBytes",second.getJSONArray("history").toString().toByteArray(Charsets.UTF_8).size)
                .put("restoredHistorySha256",sha(second.getJSONArray("history").toString()))
            val secondLease=second.getJSONObject("lease");val secondId=secondLease.getString("runId");active[secondId]=secondLease
            assertEquals(sid,secondLease.getString("sessionId"));assertNotEquals(runId,secondId)
            val restored=second.getJSONArray("history").objects().single {it.getString("runId")==runId}
            assertEquals(sha(turn.toString()),sha(restored.toString()))
            assertEquals(userText,restored.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
            assertEquals(toolText,restored.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("text"))
            evidence.put("secondRunId",secondId).put("historyRestored",true).put("restoredTurnSha256",sha(restored.toString()))
            successful(rpc(executionWeb,connection,"end",secondLease));active.remove(secondId)
            for(rid in listOf(runId,secondId)) {
                assertEquals("failed",rows("runs",rid).single().getString("status"))
                assertTrue("Manual turn must not manufacture real execution events",rows("events",rid).isEmpty())
                val run=rows("runs",rid).single();assertFalse("Component must not produce a model HTTP receipt",run.has("modelTransport"))
            }
            evidence.put("componentPassed",true)
        } catch(t:Throwable){evidence.put("failure","${t.javaClass.simpleName}: ${t.message}");throw t}
        finally {
            for((rid,lease) in active.toMap())runCatching {
                val connection=ProductHost::class.java.getDeclaredField("connection").apply {isAccessible=true}.get(host) as String
                successful(rpc(executionWeb,connection,"end",lease));active.remove(rid)
            }.onFailure {evidence.getJSONArray("cleanupErrors").put("$rid: ${it.message}")}
            if(installedObserver)runCatching {js(executionWeb,"ThorNative.onmessage=window.__largePrevious;delete window.__largePrevious;delete window.__largeAck;delete window.__largeSent;true")}
            runCatching {ui(web,obj("op" to "select","sessionId" to restoreSession));until {host.selectedSession()==restoreSession};evidence.put("selectionRestored",true)}
                .onFailure {evidence.getJSONArray("cleanupErrors").put("restore selection: ${it.message}")}
            val after=historyIds();val preserved=before.all {(table,ids)->after.getValue(table).containsAll(ids)}
            evidence.put("historyPreserved",preserved).put("profileConfigurationUnchanged",profileBefore==profiles.all).put("encryptedCredentialsUnchanged",credentialsBefore==credentials.all)
            evidence.put("passed",evidence.optBoolean("componentPassed") && evidence.getJSONArray("cleanupErrors").length()==0 && preserved && profileBefore==profiles.all && credentialsBefore==credentials.all);save()
            ins.runOnMainSync {activity.finish()};ins.waitForIdleSync()
            assertTrue(preserved);assertEquals(profileBefore,profiles.all);assertEquals(credentialsBefore,credentials.all)
            assertEquals("All component runs must end",0,active.size)
            assertEquals("Component cleanup must succeed",0,evidence.getJSONArray("cleanupErrors").length())
        }
    }
}
