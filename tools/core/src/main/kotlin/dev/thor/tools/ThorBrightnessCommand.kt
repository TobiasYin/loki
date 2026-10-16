package dev.thor.tools

/** Fixed vendor operation; never exposes transaction numbers or arbitrary Binder payloads. */
data class ThorBrightnessCommand(val action: String,val screen: String,val value: Int?) {
    val mutates get()=action=="set"
    // Safe, bounded brightness adjustment is explicitly user-designated white.
    val needsApproval get()=false
    val summary get()="白名单 · Thor ${if(screen=="upper")"上屏" else "下屏"}亮度改为 $value%\n仅修改所选屏幕，完成后读取两屏验证。"
    companion object {
        fun parse(args: Map<String,Any?>): ThorBrightnessCommand {
            requireTool(args.keys.all {it in setOf("action","screen","value")},message="Unknown brightness parameter")
            val action=args["action"] as? String;val screen=args["screen"] as? String
            requireTool(action in setOf("read","set") && screen in setOf("upper","lower"),message="Select read/set and upper/lower")
            val n=(args["value"] as? Number)?.toDouble()
            requireTool(if(action=="set")n!=null && n.isFinite() && n==kotlin.math.floor(n) && n in 0.0..100.0 else "value" !in args,message="Set requires integer brightness percent 0..100; read has no value")
            return ThorBrightnessCommand(action!!,screen!!,n?.toInt())
        }
    }
}

/** Public values are integer percentages. Firmware quantization stays behind this boundary. */
object ThorBrightnessPercent {
    const val FIRMWARE_MAX=255
    const val FIRMWARE_TOLERANCE=1
    fun toFirmware(percent:Int):Int {
        requireTool(percent in 0..100,message="Brightness percent must be 0..100")
        return (percent*FIRMWARE_MAX+50)/100
    }
    fun fromFirmware(raw:Int):Int {
        requireTool(raw in 0..FIRMWARE_MAX,Code.IO_ERROR,"Firmware returned invalid brightness")
        return (raw*100+FIRMWARE_MAX/2)/FIRMWARE_MAX
    }
    fun verified(percent:Int,raw:Int):Boolean {
        // Validate the raw value even when it cannot match. Do not silently clamp it.
        fromFirmware(raw)
        return kotlin.math.abs(toFirmware(percent)-raw)<=FIRMWARE_TOLERANCE
    }
}
