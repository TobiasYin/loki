package dev.thor.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.EnumMap

/** DEBUG only. Bounded numeric stage history, accessible to instrumentation by
 * Native reflection, never to either WebView, model, tool or public RPC. */
internal object RuntimeStartupDiagnostics {
    private data class Phase(var count:Long=0,var firstMs:Long=0,var lastMs:Long=0,var totalValue:Long=0,var maxValue:Long=0)
    private data class Attempt(val started:Long,val phases:EnumMap<RuntimeStartupPhase,Phase> = EnumMap(RuntimeStartupPhase::class.java))
    private val attempts=LinkedHashMap<Long,Attempt>()
    private var sequence=0L
    private fun now()=System.nanoTime()/1_000_000
    @Synchronized fun begin():Long {
        val id=++sequence;attempts[id]=Attempt(now());while(attempts.size>4)attempts.remove(attempts.keys.first())
        mark(id,RuntimeStartupPhase.ENGINE_BEGIN);return id
    }
    @Synchronized fun mark(attempt:Long,phase:RuntimeStartupPhase,value:Long=0) {
        val state=attempts[attempt] ?: return
        val elapsed=(now()-state.started).coerceAtLeast(0);val item=state.phases.getOrPut(phase){Phase(firstMs=elapsed)}
        item.count++;item.lastMs=elapsed;item.totalValue+=value.coerceAtLeast(0);item.maxValue=maxOf(item.maxValue,value.coerceAtLeast(0))
    }
    fun payload(attempt:Long,phase:RuntimeStartupPhase,text:String) {
        // Count UTF-8 without allocating another multi-MiB byte array just for diagnostics.
        var bytes=0L;var index=0
        while(index<text.length) {
            val c=text[index].code
            bytes+=when {c<0x80->1;c<0x800->2;c in 0xd800..0xdbff && index+1<text.length && text[index+1].code in 0xdc00..0xdfff->{index++;4};c in 0xd800..0xdfff->1;else->3}
            index++
        }
        mark(attempt,phase,bytes)
    }
    @Synchronized fun snapshot():JSONObject = JSONObject().put("schemaVersion",1).put("debugOnly",true)
        .put("attempts",JSONArray(attempts.map {(id,state)->JSONObject().put("attempt",id).put("elapsedMs",(now()-state.started).coerceAtLeast(0))
            .put("phases",JSONObject().apply {state.phases.forEach {(key,value)->put(key.name,JSONObject().put("count",value.count)
                .put("firstMs",value.firstMs).put("lastMs",value.lastMs).put("totalValue",value.totalValue).put("maxValue",value.maxValue))}})}))
}
