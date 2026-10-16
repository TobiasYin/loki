package dev.thor.tools

/** Screen automation must never click its own permission/approval controls.
 * The fixed background operation is separate and cannot approve anything. */
object InputTargetPolicy {
    fun check(toolId:String,target:Target,assistantPackages:Set<String>) {
        val tool=ToolPack.descriptors[toolId] ?: return
        if(tool.effect==Effect.INPUT && tool.targetRequired)
            requireTool(target.packageName !in assistantPackages,Code.COMMAND_BLOCKED,"Screen input cannot operate the assistant's own controls")
    }
}
