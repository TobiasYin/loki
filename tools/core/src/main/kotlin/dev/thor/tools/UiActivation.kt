package dev.thor.tools

data class UiActivationGuard(val runId:String,val sessionId:String,val snapshotId:String,val windowId:Int,
    val packageName:String?,val path:String,val signature:String,val expires:Long) {
    fun check(run:String,session:String,snapshot:String?,window:Int,pkg:String?,resolvedPath:String,
        sameNodeIdentity:Boolean,freshSignature:String,now:Long) {
        requireTool(run==runId && session==sessionId && snapshot==snapshotId && window==windowId && pkg==packageName &&
            resolvedPath==path && sameNodeIdentity && freshSignature==signature && now<expires,
            Code.TARGET_CHANGED,"Activation ref owner/tree/path/window/app/node changed; read ui_tree again")
    }
    companion object {
        fun parse(args:Map<String,Any?>):String {
            requireTool(args.keys==setOf("nodeRef"),message="Activation accepts only a current ui_tree nodeRef")
            val ref=args["nodeRef"] as? String ?: fail(Code.INVALID_ARGUMENT,"nodeRef is required")
            requireTool(ref.length in 1..1024 && ref.none(Char::isISOControl),message="Invalid nodeRef")
            return ref
        }
        fun requireClickable(visible:Boolean,enabled:Boolean,clickable:Boolean,password:Boolean,hasClickAction:Boolean,
            bounds:TouchableBounds,display:DisplayFact,clip:TouchableBounds?) {
            requireTool(!password && visible && enabled && clickable && hasClickAction,Code.INVALID_ARGUMENT,
                "Activation requires a visible enabled non-password clickable node; choose a clickable ancestor if needed")
            requireVisibleBounds(bounds,display,clip)
        }
        fun requireVisibleBounds(bounds:TouchableBounds,display:DisplayFact,clip:TouchableBounds?) {
            val viewport=clip ?: TouchableBounds(0,0,display.width,display.height)
            requireTool(bounds.left>=0 && bounds.top>=0 && bounds.right<=display.width && bounds.bottom<=display.height &&
                bounds.left<bounds.right && bounds.top<bounds.bottom && bounds.left>=viewport.left && bounds.top>=viewport.top &&
                bounds.right<=viewport.right && bounds.bottom<=viewport.bottom,Code.TARGET_CHANGED,"Activation node is outside the current visible upper region")
        }
    }
}
