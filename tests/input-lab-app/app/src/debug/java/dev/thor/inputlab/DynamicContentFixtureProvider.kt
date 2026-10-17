package dev.thor.inputlab

import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Signature+caller+owner+lease control. Only fixture setup/state; never input. */
class DynamicContentFixtureProvider:ContentProvider() {
    private var owner=-1;private var lease:String?=null;private var run:String?=null
    override fun onCreate()=true
    @Synchronized override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
        val ctx=context!!;val caller=Binder.getCallingUid();val e=extras ?: error("Request required")
        require(ctx.checkCallingPermission("dev.thor.inputlab.permission.CONTROL")==android.content.pm.PackageManager.PERMISSION_GRANTED)
        require(ctx.packageManager.getPackagesForUid(caller).orEmpty().any {it in setOf("dev.thor.agent","dev.thor.agent.test")})
        require(e.getInt("version")==1 && method in setOf("prepare","report","configure","release"))
        if(method=="prepare"){require(lease==null);require(e.getString("testRunId").orEmpty().length in 1..128)}
        else require(owner==caller && lease!=null && lease==e.getString("lease") && run==e.getString("testRunId"))
        val done=CountDownLatch(1);var report="";var failure:Throwable?=null
        Handler(Looper.getMainLooper()).post {try {
            val a=DynamicContentFixtureActivity.current?.get() ?: error("Receiver not running")
            when(method){
                "prepare"->{a.prepare(e.getString("testRunId")!!);owner=caller;run=e.getString("testRunId");lease=UUID.randomUUID().toString()}
                "configure"->a.configure(e.getString("change") ?: error("Change required"))
            }
            report=if(method=="release")a.release() else a.snapshot()
            if(method=="release"){owner=-1;run=null;lease=null}
        }catch(t:Throwable){failure=t}finally{done.countDown()}}
        check(done.await(8,TimeUnit.SECONDS));failure?.let {throw IllegalStateException("Dynamic fixture rejected: ${it.javaClass.simpleName}",it)}
        return Bundle().apply {putString("snapshot",report);putString("lease",lease)}
    }
    override fun query(uri:Uri,p:Array<out String>?,s:String?,args:Array<out String>?,o:String?):Cursor?=throw SecurityException()
    override fun getType(uri:Uri):String?=null
    override fun insert(uri:Uri,v:ContentValues?):Uri?=throw SecurityException()
    override fun delete(uri:Uri,s:String?,args:Array<out String>?):Int=throw SecurityException()
    override fun update(uri:Uri,v:ContentValues?,s:String?,args:Array<out String>?):Int=throw SecurityException()
}
