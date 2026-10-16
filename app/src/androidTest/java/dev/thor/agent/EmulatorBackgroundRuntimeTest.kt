package dev.thor.agent

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Local real SSE + Native bridge/Pi execution; never uses a cloud key or device input. */
@RunWith(AndroidJUnit4::class)
class EmulatorBackgroundRuntimeTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun until(reason:String,check:()->Boolean) {val end=SystemClock.elapsedRealtime()+60000;while(SystemClock.elapsedRealtime()<end){if(try {check()}catch(_:android.database.sqlite.SQLiteDatabaseLockedException){false})return;Thread.sleep(80)};fail(reason)}
    private fun js(web:WebView,source:String):String {val done=CountDownLatch(1);var value="";ins.runOnMainSync {web.evaluateJavascript(source){value=it;done.countDown()}};assertTrue(done.await(10,TimeUnit.SECONDS));return value}
    private fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.get(a)
    private fun launch():MainActivity {
        val a=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        until("Service engine attach"){a.findViewById<WebView>(android.R.id.custom)!=null}
        val web=a.findViewById<WebView>(android.R.id.custom)
        until("Native document ready"){js(web,"window.productReady===true && !document.getElementById('new').disabled")=="true"}
        return a
    }
    private fun restore(prefs:android.content.SharedPreferences,values:Map<String,*>) {
        val edit=prefs.edit().clear();for((key,value) in values)when(value) {
            is String->edit.putString(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value)
            is Boolean->edit.putBoolean(key,value);is Float->edit.putFloat(key,value)
            is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
        };check(edit.commit())
    }
    @Test fun homeFinishAndReturnKeepTheSameExecutionAndNotificationStopPreservesContext() {
        assertTrue("Emulator only",Build.HARDWARE in setOf("ranchu","goldfish"))
        if(Build.VERSION.SDK_INT>=33)assertEquals("Grant POST_NOTIFICATIONS to the emulator test app before this case",PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val preferences=context.getSharedPreferences("profiles",0);val vaultPrefs=context.getSharedPreferences("native-credentials",0)
        assertFalse("Disposable emulator key only",vaultPrefs.contains("model"))
        val before=preferences.all.toMap();val vaultBefore=vaultPrefs.all.toMap()
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));val workers=Executors.newCachedThreadPool()
        val requests=AtomicInteger();val releaseProbe=CountDownLatch(1);val releaseThird=CountDownLatch(1);val fifthArrived=CountDownLatch(1)
        val sockets=java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        val report=obj("mode" to "local-real-sse","cloudRequests" to 0,"deviceInputCalls" to 0,"passed" to false)
        fun record(phase:String){report.put("phase",phase);File(context.filesDir,"background-runtime-report.json").writeText(report.toString(2))}
        fun event(value:JSONObject)="event: ${value.getString("type")}\ndata: $value\n\n"
        fun response(index:Int,text:String,call:Boolean=false):String {
            val item=if(call)obj("type" to "function_call","id" to "fc_probe","call_id" to "probe_once","name" to "probe_picture","arguments" to "{}","status" to "completed") else obj("type" to "message","id" to "msg_$index","role" to "assistant","content" to JSONArray().put(obj("type" to "output_text","text" to text,"annotations" to JSONArray())),"status" to "completed")
            val events=mutableListOf(obj("type" to "response.created","response" to obj("id" to "resp_$index","status" to "in_progress")),obj("type" to "response.output_item.added","output_index" to 0,"item" to JSONObject(item.toString()).put("status","in_progress").apply {if(call)put("arguments","") else put("content",JSONArray())}))
            if(call)events.add(obj("type" to "response.function_call_arguments.delta","output_index" to 0,"item_id" to "fc_probe","delta" to "{}"))
            else {events.add(obj("type" to "response.content_part.added","output_index" to 0,"content_index" to 0,"item_id" to "msg_$index","part" to obj("type" to "output_text","text" to "","annotations" to JSONArray())));events.add(obj("type" to "response.output_text.delta","output_index" to 0,"content_index" to 0,"item_id" to "msg_$index","delta" to text))}
            events.add(obj("type" to "response.output_item.done","output_index" to 0,"item" to item))
            events.add(obj("type" to "response.completed","response" to obj("id" to "resp_$index","status" to "completed","output" to JSONArray().put(item),"usage" to obj("input_tokens" to 10,"output_tokens" to 8,"total_tokens" to 18))))
            return events.joinToString(""){event(it)}
        }
        workers.execute {
            while(!server.isClosed)try {
                val socket=server.accept();sockets.add(socket);workers.execute {
                    try {socket.use {
                        val input=it.getInputStream()
                        fun line():String? {val bytes=java.io.ByteArrayOutputStream();while(bytes.size()<65536){val value=input.read();if(value<0)return null;if(value==10)return bytes.toString("US-ASCII").trimEnd('\r');bytes.write(value)};error("Header budget exceeded")}
                        line();val headers=mutableMapOf<String,String>()
                        while(true){val header=line() ?: break;if(header.isEmpty())break;headers[header.substringBefore(':').lowercase()]=header.substringAfter(':').trim()}
                        val body=ByteArray(headers.getValue("content-length").toInt().also {size->require(size in 1..16*1024*1024)})
                        var read=0;while(read<body.size){val n=input.read(body,read,body.size-read);if(n<0)error("Incomplete body");read+=n}
                        val index=requests.incrementAndGet();val text=when(index){2->"PROBE_OK";3->"后台执行正文保留";4->"沿用同一会话继续";else->"停止测试正文"}
                        val bytes=response(index,text,index==1).toByteArray()
                        val output=it.getOutputStream();output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        if(index==1 || index==3 || index==5) {
                            // Send actual partial output before detaching the Activity.
                            val boundary=String(bytes,Charsets.UTF_8).substringBefore("event: response.output_item.done").toByteArray(Charsets.UTF_8).size
                            output.write(bytes,0,boundary);output.flush()
                            if(index==1)assertTrue(releaseProbe.await(60,TimeUnit.SECONDS))
                            else if(index==3)assertTrue(releaseThird.await(60,TimeUnit.SECONDS))
                            else {fifthArrived.countDown();Thread.sleep(60000)}
                            output.write(bytes,boundary,bytes.size-boundary)
                        }else output.write(bytes)
                        output.flush()
                    }}catch(_:Exception){}
                }
            }catch(_:Exception){break}
        }
        var activity:MainActivity?=null
        val reader=SqliteStore(context,"product.db")
        fun runs()=reader.readableDatabase.rawQuery("SELECT payload FROM runs ORDER BY rowid",null).use {c->buildList {while(c.moveToNext())add(JSONObject(c.getString(0)))}}
        try {
            assertTrue("Never interrupt existing work",runs().all {it.optString("status") in setOf("completed","failed","cancelled","interrupted")})
            preferences.edit().putString("provider","custom").putString("url","http://127.0.0.1:${server.localPort}/v1").putString("model","background-fixture").putString("name","后台测试本地模型").putInt("protocol",0).putInt("revision",61001).putInt("contextBudget",32768).putInt("maxOutput",2048).putBoolean("supportsImages",false).remove("probe").remove("probeRevision").commit()
            CredentialVault(context).put("model","local-disposable-background-fixture")
            activity=launch();val first=activity!!;val web=first.findViewById<WebView>(android.R.id.custom);val host=field(first,"host") as ProductHost
            val service=field(first,"runtimeService") as AgentRuntimeService;val engine=service.engine!!
            val previous=host.selectedSession()
            js(web,"window.__backgroundRuntimeMarker='same-document';ThorUI.postMessage(JSON.stringify({op:'new'}));true")
            until("New test session"){host.selectedSession()!=previous};val sid=host.selectedSession()
            fun send(text:String){js(web,"ThorUI.postMessage(${JSONObject.quote(obj("op" to "send","sessionId" to sid,"text" to text).toString())});true")}
            // Normal user send starts a real custom-model probe. Leave the UI
            // before it finishes, then require the actual task to start in background.
            send("在后台继续这个本地测试任务")
            until("Real configuration probe started"){requests.get()==1 && host.hasActiveRuns() && service.foreground}
            ins.runOnMainSync {first.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));first.finish()}
            until("Activity destroyed during probe"){first.isDestroyed}
            releaseProbe.countDown()
            until("Actual task starts after background probe handoff"){requests.get()==3 && preferences.getString("probe","")=="passed" && host.hasActiveRuns() && service.foreground}
            val runId=runs().last {it.getString("sessionId")==sid && !it.optBoolean("probe")}.getString("id")
            report.put("backgroundProbeHandoff",true).put("firstRunId",runId);record("background-probe-and-task-started")
            assertSame(engine,service.engine);assertSame(host,service.engine!!.host);assertTrue(service.foreground)
            assertNull("Detached WebView retains no Activity context",(web.context as android.content.MutableContextWrapper).baseContext as? android.app.Activity)
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any {it.id==AgentRuntimeService.NOTIFICATION_ID})
            activity=launch();assertSame(web,activity!!.findViewById<WebView>(android.R.id.custom));assertSame(host,field(activity!!,"host"))
            assertEquals("\"same-document\"",js(web,"window.__backgroundRuntimeMarker"))
            report.put("sameWebView",true).put("sameHost",true);record("same-document-returned")
            releaseThird.countDown()
            until("Background run completes"){runs().single {it.getString("id")==runId}.optString("status")=="completed" && !host.hasActiveRuns() && !service.foreground}
            record("first-task-complete")
            send("在同一会话追问")
            until("Follow-up completes"){requests.get()==4 && !host.hasActiveRuns() && !service.foreground}
            val followup=runs().last {it.getString("sessionId")==sid && !it.optBoolean("probe")}
            assertEquals("completed",followup.getString("status"))
            val reuse=reader.readableDatabase.rawQuery("SELECT payload FROM events WHERE runId=?",arrayOf(followup.getString("id"))).use {c->var yes=false;while(c.moveToNext()){val e=JSONObject(c.getString(0));if(e.optString("type")=="context" && e.getJSONObject("data").optBoolean("piSessionReused"))yes=true};yes}
            assertTrue("Same Pi Agent reused after Activity recreation",reuse)
            report.put("samePiSession",reuse).put("followupRunId",followup.getString("id"));record("followup-complete")
            send("测试通知停止这个后台任务");assertTrue(fifthArrived.await(30,TimeUnit.SECONDS))
            until("Second run foreground and partial text durably recorded"){
                host.hasActiveRuns() && service.foreground && reader.readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE payload LIKE ?",arrayOf("%停止测试正文%")).use {it.moveToFirst();it.getInt(0)>0}
            }
            val stopping=runs().last {it.getString("sessionId")==sid && !it.optBoolean("probe")}
            ins.runOnMainSync {activity!!.finish()};until("Second UI destroyed"){activity!!.isDestroyed}
            val notification=context.getSystemService(NotificationManager::class.java).activeNotifications.single {it.id==AgentRuntimeService.NOTIFICATION_ID}.notification
            notification.actions.single {it.title.toString()=="停止所有任务"}.actionIntent.send()
            until("Notification stop records cancellation"){runs().single {it.getString("id")==stopping.getString("id")}.optString("status")=="cancelled"}
            val saved=runs().single {it.getString("id")==stopping.getString("id")}.getJSONObject("turn")
            assertFalse(saved.getBoolean("complete"));assertTrue(saved.toString().contains("停止测试正文"))
            val count=requests.get();Thread.sleep(500);assertEquals("No automatic replay after stop",count,requests.get())
            report.put("backgroundProbeHandoff",true).put("sameWebView",true).put("sameHost",true).put("samePiSession",reuse).put("foregroundNotification",true).put("notificationStoppedRun",true).put("partialContextRetained",true).put("requestCount",count).put("passed",true);record("complete")
        }finally {
            releaseProbe.countDown();releaseThird.countDown();sockets.forEach {runCatching {it.close()}};server.close();workers.shutdownNow();reader.close()
            ins.runOnMainSync {activity?.takeIf {!it.isDestroyed}?.finish()}
            // This fixture only tears down after observed terminal state. Never stop a live run in cleanup.
            if(!runsSafelyActive()) {
                context.stopService(Intent(context,AgentRuntimeService::class.java))
                restore(preferences,before);restore(vaultPrefs,vaultBefore)
            } else report.put("cleanupDeferred","Test run still active; did not stop or reconfigure its runtime")
            File(context.filesDir,"background-runtime-report.json").writeText(report.toString(2))
        }
    }
    @Test fun idleConfigurationReloadWaitsForTheActualWriterCloseWithoutBlockingMain() {
        assertTrue("Emulator only",Build.HARDWARE in setOf("ranchu","goldfish"))
        val activity=launch();val oldHost=field(activity,"host") as ProductHost
        assertFalse("Do not reload active work",oldHost.hasActiveRuns())
        val oldWeb=activity.findViewById<WebView>(android.R.id.custom)
        val service=field(activity,"runtimeService") as AgentRuntimeService
        val serial=ProductHost::class.java.getDeclaredField("serial").apply {isAccessible=true}.get(oldHost) as java.util.concurrent.ExecutorService
        val admitted=CountDownLatch(1);val released=CountDownLatch(1)
        serial.submit {admitted.countDown();released.await(15,TimeUnit.SECONDS)}
        assertTrue(admitted.await(2,TimeUnit.SECONDS))
        try {
            val start=SystemClock.elapsedRealtime()
            ins.runOnMainSync {MainActivity::class.java.getDeclaredMethod("reloadRuntime").apply {isAccessible=true}.invoke(activity)}
            assertTrue("Reload must not synchronously wait for the old writer",SystemClock.elapsedRealtime()-start<1000)
            Thread.sleep(1300) // Exceeds the former create() semaphore wait.
            ins.runOnMainSync {assertNull("Do not construct a second owner while close is pending",service.engine)}
            assertFalse(oldHost.closeCompletion.isDone)
            var mainResponsive=false;ins.runOnMainSync {mainResponsive=true};assertTrue(mainResponsive)
            released.countDown()
            until("New document after actual close") {activity.findViewById<WebView>(android.R.id.custom)?.let {it!==oldWeb}==true}
            val ready=EmulatorRuntimeTestSupport.awaitReady(activity,oldWeb)
            assertTrue(oldHost.closeCompletion.isDone);assertNotSame(oldHost,ready.host)
        }finally {
            released.countDown();ins.runOnMainSync {activity.finish()}
        }
    }
    private fun runsSafelyActive():Boolean=runCatching {SqliteStore(context,"product.db").use {store->store.readableDatabase.rawQuery("SELECT payload FROM runs",null).use {c->var active=false;while(c.moveToNext())if(JSONObject(c.getString(0)).optString("status") !in setOf("completed","failed","cancelled","interrupted"))active=true;active}}}.getOrDefault(true)
}
