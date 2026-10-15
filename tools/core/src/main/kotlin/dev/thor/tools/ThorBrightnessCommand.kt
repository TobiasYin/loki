package dev.thor.tools

/** Fixed vendor operation; never exposes transaction numbers or arbitrary Binder payloads. */
data class ThorBrightnessCommand(val action: String,val screen: String,val value: Int?) {
    val mutates get()=action=="set"
    // Safe, bounded brightness adjustment is explicitly user-designated white.
    val needsApproval get()=false
    val summary get()="白名单 · Thor ${if(screen=="upper")"上屏" else "下屏"}亮度改为 $value（0–255）\n仅修改所选屏幕，完成后读取两屏验证。"
    companion object {
        fun parse(args: Map<String,Any?>): ThorBrightnessCommand {
            requireTool(args.keys.all {it in setOf("action","screen","value")},message="Unknown brightness parameter")
            val action=args["action"] as? String;val screen=args["screen"] as? String
            requireTool(action in setOf("read","set") && screen in setOf("upper","lower"),message="Select read/set and upper/lower")
            val n=(args["value"] as? Number)?.toDouble()
            requireTool(if(action=="set")n!=null && n.isFinite() && n==kotlin.math.floor(n) && n in 0.0..255.0 else "value" !in args,message="Set requires integer brightness 0..255; read has no value")
            return ThorBrightnessCommand(action!!,screen!!,n?.toInt())
        }
    }
}
