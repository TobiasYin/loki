package dev.thor.agent

import dev.thor.tools.Code
import java.util.UUID

/** Model-visible declarations are immutable per request; execution admission is
 * deliberately separate and must still use current Native authorization. */
internal class RequestToolSchemas(private val maximum:Int=8) {
    private data class Issued(val revision:Int,val epoch:Long,val tools:Set<String>)
    private val issued=linkedMapOf<String,Issued>()
    private val pinned=ArrayDeque<String>()
    init {require(maximum>=3)}

    fun issue(revision:Int,epoch:Long,tools:Set<String>):String {
        issued.entries.firstOrNull {it.value==Issued(revision,epoch,tools)}?.let {return it.key}
        val id=UUID.randomUUID().toString()
        issued[id]=Issued(revision,epoch,tools.toSet())
        while(issued.size>maximum)issued.keys.first {it !in pinned}.let {issued.remove(it)}
        return id
    }

    fun requireMatch(id:String,revision:Int,epoch:Long,tools:List<String>) {
        val schema=issued[id] ?: error("Request tool schema is absent or expired")
        require(schema.revision==revision && schema.epoch==epoch) {"Request tool schema belongs to another profile or capability epoch"}
        require(tools.size==tools.toSet().size && schema.tools==tools.toSet()) {"Request tool declarations differ from the issued schema"}
        // Keep the current/previous admitted Pi loadouts while pruning transient
        // authority observations. Repeated RPCs cannot evict the active schema.
        pinned.remove(id);pinned.addLast(id)
        while(pinned.size>2)pinned.removeFirst()
    }

    internal fun size()=issued.size

    companion object {
        fun declarations(ready:Set<String>,rejections:Map<String,Code>,eligibleTargetInputs:Set<String>):Set<String> =
            ready+rejections.filter {it.value==Code.TARGET_CHANGED && it.key in eligibleTargetInputs}.keys
    }
}
