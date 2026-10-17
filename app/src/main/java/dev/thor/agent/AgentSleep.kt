package dev.thor.agent

import dev.thor.tools.Code
import dev.thor.tools.ToolFailure
import dev.thor.tools.requireTool
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Run-scoped, cancellable timers. Waiting occupies neither the host queue nor a device worker. */
internal class AgentSleep(private val now:()->Long,
    private val scheduler:ScheduledExecutorService=Executors.newSingleThreadScheduledExecutor()):AutoCloseable {
    data class Receipt(val durationMs:Long,val elapsedMs:Long)
    private class Pending(val runId:String,val started:Long,val duration:Long,val result:CompletableFuture<Receipt>) {
        var task:ScheduledFuture<*>?=null
    }
    private val pending=mutableMapOf<String,Pending>()
    private var closed=false
    companion object {
        fun duration(args:JSONObject):Long {
            requireTool(args.keys().asSequence().toSet()==setOf("durationMs"),Code.INVALID_ARGUMENT,"Only durationMs is accepted")
            val raw=args.get("durationMs")
            requireTool(raw is Int || raw is Long,Code.INVALID_ARGUMENT,"durationMs must be an integer")
            val value=(raw as Number).toLong()
            requireTool(value in 1..60000,Code.INVALID_ARGUMENT,"durationMs must be 1..60000 milliseconds")
            return value
        }
    }
    @Synchronized fun start(runId:String,requestId:String,durationMs:Long,deadline:Long):CompletableFuture<Receipt> {
        requireTool(!closed,Code.CANCELLED,"Timer owner is closed")
        requireTool(durationMs in 1..60000 && requestId !in pending,Code.INVALID_ARGUMENT,"Invalid or duplicate wait")
        val started=now()
        requireTool(deadline-started>=durationMs,Code.TIMEOUT,"Wait exceeds the remaining tool deadline")
        val result=CompletableFuture<Receipt>();val entry=Pending(runId,started,durationMs,result)
        pending[requestId]=entry
        result.whenComplete {_,_->synchronized(this) {
            if(pending[requestId]===entry)pending.remove(requestId)
            entry.task?.cancel(false)
        }}
        entry.task=scheduler.schedule({result.complete(Receipt(durationMs,(now()-started).coerceAtLeast(0)))},durationMs,TimeUnit.MILLISECONDS)
        return result
    }
    fun cancel(runId:String,requestId:String?=null) {
        val targets=synchronized(this) {pending.filter {(id,p)->p.runId==runId && (requestId==null || requestId==id)}.values.toList()}
        targets.forEach {it.result.completeExceptionally(ToolFailure(Code.CANCELLED,"Wait cancelled"))}
    }
    fun cancelAll() {
        val targets=synchronized(this) {pending.values.toList()}
        targets.forEach {it.result.completeExceptionally(ToolFailure(Code.CANCELLED,"Wait cancelled"))}
    }
    @get:Synchronized val size:Int get()=pending.size
    override fun close() {synchronized(this){closed=true};cancelAll();scheduler.shutdownNow()}
}
