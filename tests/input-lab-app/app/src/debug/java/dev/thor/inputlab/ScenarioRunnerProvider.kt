package dev.thor.inputlab

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Debug signature-only preparation/observation, no input or verdict override API. */
class ScenarioRunnerProvider:ContentProvider() {
    private var owner=-1
    private var lease:String?=null
    private var testRun:String?=null
    override fun onCreate()=true
    @Synchronized override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
        val caller=Binder.getCallingUid();val ctx=context!!
        require(ctx.packageManager.getPackagesForUid(caller).orEmpty().any {it=="com.tobias.loki" || it=="com.tobias.loki.test"})
        require(ctx.checkCallingPermission("dev.thor.inputlab.permission.CONTROL")==android.content.pm.PackageManager.PERMISSION_GRANTED)
        val e=extras ?: error("Missing request");require(e.getInt("version")==1);require(method in setOf("describe","prepare","report","release","finish"))
        @Suppress("DEPRECATION") val model=if(method in setOf("describe","prepare")) {
            require(e.get("seed") is Long);ScenarioModel(e.getString("scene")!!,e.getLong("seed"))
        }else null
        if(method=="describe")return Bundle().apply {putString("snapshot",Json.encode(mapOf("scene" to model!!.scene,"seed" to model.seed,"taskPrompt" to model.taskPrompt)))}
        if(method=="prepare") {
            require(lease==null)
            require(e.getString("testRunId").orEmpty().length in 1..128 && e.getString("agentRunId").orEmpty().length in 1..128 && e.getString("senderTraceRef").orEmpty().length in 1..512)
        }else require(caller==owner && lease!=null && e.getString("lease")==lease && e.getString("testRunId")==testRun)
        val done=CountDownLatch(1);var snapshot="";var failure:Throwable?=null
        Handler(Looper.getMainLooper()).post {
            try {
                val activity=ScenarioActivity.runnerActivity?.get()
                when(method) {
                    "prepare"->{check(activity!=null);activity.checkRunnerPrepareAllowed();val mode=if(e.containsKey("evidenceMode"))RunnerEvidenceMode.parse(e.getString("evidenceMode")) else RunnerEvidenceMode.parse();val session=ScenarioSession(model!!,e.getString("testRunId")!!,e.getString("agentRunId")!!,e.getString("senderTraceRef")!!,mode,ctx.filesDir);activity.prepare(session);owner=caller;lease=UUID.randomUUID().toString();testRun=session.testRunId;snapshot=activity.snapshot()}
                    "report"->{snapshot=if(activity!=null)activity.snapshot() else ScenarioSessions.current!!.snapshot()}
                    "release","finish"->{val session=ScenarioSessions.current!!;session.seal();snapshot=session.snapshot();activity?.freeze();lease=null;owner=-1;testRun=null}
                }
            }catch(t:Throwable){failure=t}finally{done.countDown()}
        }
        check(done.await(5,TimeUnit.SECONDS));failure?.let {throw IllegalStateException("Scenario rejected: ${it.javaClass.simpleName}",it)}
        return Bundle().apply {putString("lease",lease);putString("snapshot",snapshot)}
    }
    override fun query(uri:Uri,p:Array<out String>?,s:String?,args:Array<out String>?,o:String?):Cursor?=throw SecurityException()
    override fun getType(uri:Uri):String?=null
    override fun insert(uri:Uri,v:ContentValues?):Uri?=throw SecurityException()
    override fun delete(uri:Uri,s:String?,args:Array<out String>?):Int=throw SecurityException()
    override fun update(uri:Uri,v:ContentValues?,s:String?,args:Array<out String>?):Int=throw SecurityException()
}
