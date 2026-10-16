package dev.thor.tools

/** Fixed assistant-owned task operation, never a generic task/package API. */
object AgentBackground {
    fun parse(args:Map<String,Any?>) {requireTool(args.isEmpty(),message="Background accepts no package, task, display or command arguments")}
    fun verifyReceipt(data:Map<String,Any?>,submitted:Boolean) {
        val already=data["alreadyBackground"] as? Boolean ?: fail(Code.IO_ERROR,"Background state was not acknowledged")
        val moved=data["movedToBackground"] as? Boolean ?: fail(Code.IO_ERROR,"Background movement was not acknowledged")
        requireTool(already!=moved && submitted==moved,Code.IO_ERROR,"Background acknowledgment and actual submission disagree")
    }
}
