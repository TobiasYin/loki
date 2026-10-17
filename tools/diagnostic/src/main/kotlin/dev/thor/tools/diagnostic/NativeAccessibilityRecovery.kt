package dev.thor.tools.diagnostic

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Binder
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import dev.thor.tools.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Read only, fixed command. Never sends a dump or other service's name over IPC. */
internal object NativeAccessibilityRecovery {
    private val killer=Executors.newSingleThreadScheduledExecutor()
    data class Result(val state:AccessibilityFrameworkState.State,val readerPhase:String,val readerError:String,
        val dumpBytes:Int,val publicBoundPhase:String,val dumpExitCode:Int,val dumpTimeoutMarker:Boolean,val dumpTruncated:Boolean,val dumpProcessExited:Boolean)
    fun inspect(host:Context,user:Int,check:()->Unit):Result {
        var phase="DUMP_START"
        var bytes=0
        var publicPhase="NOT_CHECKED"
        var exitCode=-1
        var timeoutMarker=false
        var truncated=false
        var exited=false
        var publicListPopulated=false
        return try {
            check()
            val process=ProcessBuilder("/system/bin/dumpsys","-t","1","accessibility").redirectErrorStream(true).start()
            val timeout=killer.schedule({process.destroyForcibly()},1250,TimeUnit.MILLISECONDS)
            val deadline=SystemClock.elapsedRealtime()+1400
            val dump=try {
                phase="DUMP_READ"
                val output=process.inputStream.use {Budgets.read(it,512*1024) {
                    check();requireTool(SystemClock.elapsedRealtime()<deadline,Code.TIMEOUT,"Accessibility acknowledgement timed out")
                }}
                bytes=output.bytes.size;truncated=output.truncated;phase="DUMP_EXIT"
                exited=process.waitFor(100,TimeUnit.MILLISECONDS)
                if(exited)exitCode=process.exitValue()
                output.bytes.toString(Charsets.UTF_8).also {timeoutMarker=it.contains("DUMP TIMEOUT")}
            } finally {timeout.cancel(false);if(process.isAlive)process.destroyForcibly()}
            phase="PUBLIC_BOUND";val identity=Binder.clearCallingIdentity()
            val bound=try {runCatching {
                // This shell Context belongs to the requesting application's
                // Android user; never query another profile's service list.
                if(android.os.Process.myUid()/100000!=user){publicPhase="USER_MISMATCH";null} else {
                    val manager=host.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                    if(manager==null){publicPhase="MANAGER_MISSING";null} else {
                        val services=manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                        publicListPopulated=services.isNotEmpty()
                        services.any {
                            val service=it.resolveInfo?.serviceInfo
                            service?.packageName==host.packageName && service.name==ThorAccessibilityService::class.java.name
                        }.also {publicPhase=if(it)"OWN_BOUND" else if(publicListPopulated)"OTHER_BOUND" else "EMPTY_LIST"}
                    }
                }
            }.onFailure {publicPhase=when(it) {is SecurityException->"PERMISSION_ERROR";is android.os.RemoteException->"REMOTE_ERROR";else->"QUERY_ERROR"}}.getOrNull()} finally {Binder.restoreCallingIdentity(identity)}
            check();phase="PARSE"
            val complete=exited && exitCode==0 && !truncated && !timeoutMarker
            // Android dumps all user service inventories before UiAutomation,
            // windows and input filters. Only those complete, strictly parsed
            // inventories are required; a slow unrelated tail is not evidence
            // that the already-written user state is missing or malformed.
            val state=AccessibilityFrameworkState.inspect(dump,user,host.packageName,bound,publicListPopulated,complete)
            Result(state,if(state.known)if(complete)"PARSED" else "PARSED_PREFIX" else "PARSE_UNKNOWN","NONE",bytes,publicPhase,exitCode,timeoutMarker,truncated,exited)
        } catch(error:Exception) {
            check() // cancellation/retired lease must still propagate
            val code=when(error) {is ToolFailure->error.code.name;is java.util.regex.PatternSyntaxException->"REGEX_ERROR";is SecurityException->"SECURITY_ERROR";is java.io.IOException->"IO_ERROR";is InterruptedException->"INTERRUPTED";else->"INTERNAL_ERROR"}
            Result(AccessibilityFrameworkState.State(false),phase,code,bytes,publicPhase,exitCode,timeoutMarker,truncated,exited)
        }
    }
}
