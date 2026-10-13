package dev.thor.inputlab

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Debug-only receiver preparation/report; never dispatches input or sets a verdict. */
class RunnerProvider : ContentProvider() {
    private var owner = -1
    private var lease: String? = null
    private var testRun: String? = null
    private var deadline = 0L
    override fun onCreate() = true
    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val caller = Binder.getCallingUid()
        val names = context!!.packageManager.getPackagesForUid(caller).orEmpty()
        require(names.any { it == "dev.thor.agent" || it == "dev.thor.agent.test" })
        require(context!!.checkCallingPermission("dev.thor.inputlab.permission.CONTROL") == android.content.pm.PackageManager.PERMISSION_GRANTED)
        val e = extras ?: error("Missing request"); require(e.getInt("version") == 1)
        val a = LabActivity.runnerActivity?.get() ?: error("Receiver is not running")
        val done = CountDownLatch(1); var response = ""; var failure: Throwable? = null
        if(method == "prepare") {
            require(lease == null || SystemClock.elapsedRealtime() >= deadline)
            owner = caller; lease = UUID.randomUUID().toString(); testRun = e.getString("testRunId"); deadline = SystemClock.elapsedRealtime()+15000
        } else if(method != "describe") require(caller == owner && e.getString("lease") == lease && e.getString("testRunId") == testRun && SystemClock.elapsedRealtime() < deadline)
        Handler(Looper.getMainLooper()).post {
            try {
                when(method) {
                    "prepare" -> a.runnerPrepare(e.getString("caseId")!!, testRun!!, e.getString("agentRunId")!!, e.getString("senderTraceRef")!!)
                    "report", "describe" -> Unit
                    "release" -> { lease = null; owner = -1 }
                    else -> error("Unsupported runner operation")
                }
                response = if(method == "describe") a.runnerDescription() else a.runnerSnapshot()
            } catch(t: Throwable) { failure = t }
            finally { done.countDown() }
        }
        check(done.await(3,TimeUnit.SECONDS)); failure?.let { throw IllegalStateException("Receiver rejected preparation") }
        return Bundle().apply { putString("lease",lease); putString("snapshot",response) }
    }
    override fun query(uri: Uri,p: Array<out String>?,s: String?,args: Array<out String>?,o: String?): Cursor? = throw SecurityException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri,v: ContentValues?): Uri? = throw SecurityException()
    override fun delete(uri: Uri,s: String?,args: Array<out String>?) = throw SecurityException()
    override fun update(uri: Uri,v: ContentValues?,s: String?,args: Array<out String>?) = throw SecurityException()
}
