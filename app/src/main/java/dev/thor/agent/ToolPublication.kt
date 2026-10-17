package dev.thor.agent

import dev.thor.tools.Availability
import dev.thor.tools.Code
import dev.thor.tools.Status
import dev.thor.tools.fail

/** Preserve only explicit Native target-admission refusals when projecting the
 * effective tool set. This does not publish or authorize any rejected tool. */
internal object ToolPublication {
    private val targetCodes=setOf(Code.TARGET_CHANGED,Code.PERMISSION_MISSING,Code.SECURE_CONTENT,Code.DISPLAY_GONE)
    fun projectedRejection(availability:Availability,capabilityEnabled:Boolean,modelSupportsTools:Boolean):Code? {
        if(!capabilityEnabled || !modelSupportsTools)return null
        val code=availability.reasonCode?.takeIf {it in targetCodes} ?: return null
        val expected=if(code==Code.PERMISSION_MISSING)Status.PERMISSION_MISSING else Status.TEMPORARILY_UNAVAILABLE
        return code.takeIf {availability.status==expected}
    }
    fun requirePublished(toolId:String,published:Set<String>,rejections:Map<String,Code>) {
        if(toolId in published)return
        rejections[toolId]?.takeIf {it in targetCodes}?.let {code->
            fail(code,if(code==Code.TARGET_CHANGED)"目标观察已变化，请重新观察" else "工具暂不可用（${code.name}）")
        }
        require(false) {"Tool is not published for the selected model and capabilities"}
    }
}
