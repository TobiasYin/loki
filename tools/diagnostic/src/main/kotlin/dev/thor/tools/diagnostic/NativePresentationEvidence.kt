package dev.thor.tools.diagnostic

import android.content.Context
import android.os.SystemClock
import dev.thor.tools.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fixed read-only inspection. No command/package/title/display selection is
 * exposed to the model and no raw dumps leave the shell process. */
internal object NativePresentationEvidence {
    private val killer=Executors.newSingleThreadScheduledExecutor()
    private fun dump(service:String,argument:String?=null):String {
        val argv=mutableListOf("/system/bin/dumpsys","-t","2",service)
        if(argument!=null)argv.add(argument)
        val process=ProcessBuilder(argv).redirectErrorStream(true).start()
        val timeout=killer.schedule({process.destroyForcibly()},2500,TimeUnit.MILLISECONDS)
        val deadline=SystemClock.elapsedRealtime()+2600
        return try {
            val data=process.inputStream.use {Budgets.read(it,Budgets.CONFIG) {
                requireTool(SystemClock.elapsedRealtime()<deadline,Code.TIMEOUT,"Presentation metadata deadline exceeded")
            }}
            requireTool(process.waitFor(100,TimeUnit.MILLISECONDS) && process.exitValue()==0 && !data.truncated,Code.UNSUPPORTED,"Presentation metadata incomplete/unavailable")
            data.bytes.toString(Charsets.UTF_8)
        } finally {timeout.cancel(false);if(process.isAlive)process.destroyForcibly()}
    }
    fun inspect(context:Context,callerUid:Int,display:Int,ids:IntArray):List<PresentationWindowProof> {
        requireTool(display>=0 && ids.size in 1..8 && ids.all {it>=0} && ids.toSet().size==ids.size,Code.INVALID_ARGUMENT,"Bounded Presentation metadata ids required")
        val before=dump("accessibility")
        val windows=dump("window","windows")
        val after=dump("accessibility")
        return PresentationWindowEvidence.join(before,windows,after,display,ids.toSet()).onEach {proof->
            @Suppress("DEPRECATION") val info=context.packageManager.getApplicationInfo(proof.packageName,0)
            val system=info.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0
            PresentationOwnerPolicy.verify(proof,callerUid,context.packageName,info.uid,system)
        }
    }
}
