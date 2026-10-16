package dev.thor.agent

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabaseLockedException
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.hash
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in wall-clock test. Native/Pi consumes real HTTP/SSE for >6 minutes after
 * HOME and Activity destruction. The observer reads only SQLite during that
 * interval; no evaluateJavascript, UI launch, visibility callback or fake time. */
@RunWith(AndroidJUnit4::class)
class EmulatorLongBackgroundRuntimeTest:EmulatorBackgroundTestState() {
    @Test fun realTime380SecondBackgroundStreamRetainsExecutionAndFollowupContext() {
        assumeTrue("Explicit longBackgroundAcceptance=true required",InstrumentationRegistry.getArguments().getString("longBackgroundAcceptance")=="true")
        if(Build.VERSION.SDK_INT>=33)assertEquals("Temporarily grant notifications using preserving outer harness",PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val preferences=context.getSharedPreferences("profiles",0)
        val vault=context.getSharedPreferences("native-credentials",0)
        assertFalse("Never replace a real cloud key",vault.contains("model"))
        val before=preferences.all.toMap();val credentialsBefore=vault.all.toMap()
        val fixture=LongBackgroundSseFixture()
        val reader=SqliteStore(context,"product.db")
        val report=obj("schemaVersion" to 1,"evidenceMode" to "manual_platform_api","mode" to "real-loopback-sse-wall-clock",
            "cloudRequests" to 0,"deviceInputCalls" to 0,"modelAutonomyProven" to false,"passed" to false,
            "requestedBackgroundSeconds" to fixture.seconds,"observer" to "SQLite only during measured background interval",
            "backgroundEvaluateJavascriptCalls" to 0,"backgroundUiLaunches" to 0,"fakeClockUsed" to false,"checkpoints" to JSONArray())
        fun save(phase:String){report.put("phase",phase);File(context.filesDir,"long-background-runtime-report.json").writeText(report.toString(2))}
        fun <T> read(block:()->T):T {val end=SystemClock.elapsedRealtime()+10000;while(true)try{return block()}catch(e:SQLiteDatabaseLockedException){if(SystemClock.elapsedRealtime()>=end)throw e;Thread.sleep(50)}}
        fun runs()=read {reader.readableDatabase.rawQuery("SELECT payload FROM runs ORDER BY rowid",null).use {c->buildList {while(c.moveToNext())add(JSONObject(c.getString(0)))}}}
        fun run(id:String)=runs().single {it.getString("id")==id}
        fun rawEvents(id:String)=read {reader.readableDatabase.rawQuery("SELECT payload FROM events WHERE runId=? ORDER BY rowid",arrayOf(id)).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}}
        fun progress(id:String):Triple<Int,Long,Int> =read {
            val db=reader.readableDatabase
            val totals=db.rawQuery("SELECT COUNT(*),COALESCE(SUM(LENGTH(CAST(payload AS BLOB))),0) FROM events WHERE runId=?",arrayOf(id)).use {c->c.moveToFirst();c.getInt(0) to c.getLong(1)}
            // Return numeric observations only. Recursive slicing also handles
            // several deltas legitimately aggregated into one persisted block.
            val latest=db.rawQuery("WITH RECURSIVE chunks(chunk) AS (SELECT substr(payload,instr(payload,'[[BG')) FROM events WHERE runId=? AND instr(payload,'[[BG')>0 UNION ALL SELECT substr(chunk,instr(substr(chunk,2),'[[BG')+1) FROM chunks WHERE instr(substr(chunk,2),'[[BG')>0) SELECT COALESCE(MAX(CAST(substr(chunk,5,3) AS INTEGER)),0) FROM chunks",arrayOf(id)).use {c->c.moveToFirst();c.getInt(0)}
            Triple(totals.first,totals.second,latest)
        }
        fun samePi(id:String)=rawEvents(id).any {raw->val e=JSONObject(raw);e.optString("type")=="context"&&e.getJSONObject("data").optBoolean("piSessionReused")}
        var activity:MainActivity?=null
        var service:AgentRuntimeService?=null
        var ownedSession:String?=null
        try {
            preferences.edit().putString("provider","custom").putString("url","http://127.0.0.1:${fixture.port}/v1").putString("model","long-background-fixture")
                .putString("name","本地长后台验收").putInt("protocol",0).putInt("revision",61002).putInt("contextBudget",32768).putInt("maxOutput",4096)
                .putBoolean("supportsImages",false).remove("probe").remove("probeRevision").commit()
            CredentialVault(context).put("model","local-disposable-long-background-fixture")
            activity=launch();val original=activity!!
            val ready=EmulatorRuntimeTestSupport.awaitReady(original)
            val uiWeb=ready.web;val execution=ready.executionWeb;val host=ready.host
            service=field(original,"runtimeService") as AgentRuntimeService
            val engine=service!!.engine!!
            ins.runOnMainSync {assertNull("Execution document must never attach",execution.parent)}
            js(execution,"window.__longBackgroundExecutionMarker='unattached-execution';true")
            val previous=host.selectedSession()
            js(uiWeb,"ThorUI.postMessage(JSON.stringify({op:'new'}));true")
            until("New long-stream session"){host.selectedSession()!=previous}
            val sid=host.selectedSession();ownedSession=sid
            val connection=ProductHost::class.java.getDeclaredField("connection").apply {isAccessible=true}.get(host) as String
            for(cap in listOf("perception","screen_control","search","debug")) {
                val ack=host.rpc(connection,obj("op" to "ui","data" to obj("op" to "capability","sessionId" to sid,"capability" to cap,"enabled" to false))).get(20,TimeUnit.SECONDS)
                assertTrue("Local fixture capability configuration failed",ack.isNull("error"))
            }
            fun send(web:WebView,text:String){js(web,"ThorUI.postMessage(${JSONObject.quote(obj("op" to "send","sessionId" to sid,"text" to text).toString())});true")}
            send(uiWeb,"持续执行这个本地长后台流测试。")
            assertTrue("Native/Pi long-stream HTTP never arrived",fixture.longArrived.await(60,TimeUnit.SECONDS))
            until("Long stream must be durably active before leaving UI") {
                fixture.requestCount.get()==3 && host.hasActiveRuns() && service!!.foreground && runs().any {it.optString("sessionId")==sid && !it.optBoolean("probe") && it.optString("status") !in setOf("completed","failed","cancelled","interrupted")}
            }
            val runId=runs().last {it.optString("sessionId")==sid&&!it.optBoolean("probe")}.getString("id")
            report.put("runId",runId).put("ordinaryUiSendAndRealProbe",true).put("executionUnattachedBeforeBackground",true);save("leaving-chat")
            ins.runOnMainSync {original.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));original.finish()}
            until("Chat Activity must actually be destroyed"){original.isDestroyed}
            val started=SystemClock.elapsedRealtime();fixture.startBackground.countDown()
            var lastMarker=0;var progressAt=started;var nextCheckpoint=started+30000
            // Measured interval: absolutely no WebView/Activity/Host method calls.
            // SQLite rows are observations of actual Native/Pi persistence only.
            while(true) {
                val now=SystemClock.elapsedRealtime();val observed=progress(runId)
                val marker=observed.third
                if(marker>lastMarker){lastMarker=marker;progressAt=now}
                val state=run(runId)
                assertTrue("Long stream unexpectedly became terminal while completion was held",state.getString("status") !in setOf("completed","failed","cancelled","interrupted"))
                assertTrue("Background persistence stalled for over 15 real seconds",now-progressAt<15000)
                assertTrue("Fixture exceeded the measured interval without progress",now-started<(fixture.seconds+25)*1000L)
                if(now>=nextCheckpoint || marker==fixture.seconds) {
                    val elapsed=now-started
                    val required=((elapsed/1000).toInt()-10).coerceIn(0,fixture.seconds)
                    assertTrue("DB progress must follow real background SSE within ten seconds",marker>=required)
                    report.getJSONArray("checkpoints").put(obj("elapsedMs" to elapsed,"latestMarker" to marker,"persistedEventCount" to observed.first,"persistedEventBytes" to observed.second))
                    save("sqlite-only-background-observation");nextCheckpoint=now+30000
                }
                if(marker==fixture.seconds)break
                Thread.sleep(250)
            }
            val measured=SystemClock.elapsedRealtime()-started
            assertTrue("Must exceed the former five-minute WebView threshold",measured>=370000)
            assertEquals(fixture.seconds,lastMarker);assertEquals(0L,fixture.lastBackgroundDelta.count)
            assertEquals("No replay or unexpected request during background interval",3,fixture.requestCount.get())
            report.put("measuredBackgroundMs",measured).put("all380LiveMarkersObserved",true).put("completionHeldUntilUiReturn",true)
                .put("activityDestroyedThroughoutMeasuredInterval",true);save("background-interval-complete")
            // Only after the full measured interval may UI/JS be observed again.
            activity=launch();val returned=EmulatorRuntimeTestSupport.awaitReady(activity!!)
            assertSame(host,returned.host);assertSame(engine,service!!.engine);assertSame(execution,returned.executionWeb)
            ins.runOnMainSync {assertNull(execution.parent)}
            assertEquals("\"unattached-execution\"",js(execution,"window.__longBackgroundExecutionMarker"))
            fixture.finishLong.countDown()
            until("Long real SSE stream must reach durable completion") {run(runId).getString("status")=="completed"&&!host.hasActiveRuns()&&!service!!.foreground}
            val turn=run(runId).getJSONObject("turn")
            assertTrue(turn.getBoolean("complete"));val text=turn.toString()
            for(i in 1..fixture.seconds)assertTrue("Durable turn omitted background marker $i",text.contains(fixture.marker(i).trim()))
            assertFalse("The text fixture cannot invoke device tools",rawEvents(runId).any {JSONObject(it).optString("type")=="tool_intent"})
            send(returned.web,"沿用刚才的长后台上下文继续回答。")
            until("Follow-up must complete through the same Pi session") {fixture.requestCount.get()==4&&!host.hasActiveRuns()&&!service!!.foreground}
            val followup=runs().last {it.optString("sessionId")==sid&&!it.optBoolean("probe")}
            assertEquals("completed",followup.getString("status"));assertTrue("Follow-up must reuse the actual Pi Agent",samePi(followup.getString("id")))
            val request=fixture.bodies[3]
            for(i in 1..fixture.seconds)assertTrue("Follow-up HTTP history omitted marker $i",request.contains(fixture.marker(i).trim()))
            assertTrue("Loopback fixture workers must have no unexpected errors",fixture.errors().isEmpty())
            report.put("sameHost",true).put("sameUnattachedExecutionWeb",true).put("samePiSessionFollowup",true)
                .put("durableCompleteTurnRetained",true).put("followupHttpContainsAll380Markers",true)
                .put("longTextSha256",hash(fixture.fullText.toByteArray())).put("longTextBytes",fixture.fullText.toByteArray().size)
                .put("requestCount",fixture.requestCount.get()).put("shortBackgroundNotificationStopCoveredSeparately",true).put("passed",true);save("complete")
        }catch(t:Throwable){report.put("failureType",t.javaClass.simpleName).put("failure",t.message);save("failed");throw t}
        finally {
            // Cancel only this fixture's own live execution via the actual Stop
            // notification. Never silently reconfigure a still-running engine.
            if(ownedSession!=null && runsSafelyActive())runCatching {
                val action=context.getSystemService(NotificationManager::class.java).activeNotifications.single {it.id==AgentRuntimeService.NOTIFICATION_ID}.notification.actions.single {it.title.toString()=="停止所有任务"}
                action.actionIntent.send();until("Fixture cancellation must finish before cleanup"){!runsSafelyActive()}
            }.onFailure {report.put("cleanupFailure",it.message).put("passed",false)}
            fixture.close();reader.close()
            ins.runOnMainSync {activity?.takeUnless {it.isDestroyed}?.finish()}
            if(!runsSafelyActive()) {
                context.stopService(Intent(context,AgentRuntimeService::class.java))
                restore(preferences,before);restore(vault,credentialsBefore)
            }else report.put("cleanupDeferred","Fixture Run remains active; no reconfiguration or forced service stop").put("passed",false)
            save(report.optString("phase","cleanup"))
        }
    }
}
