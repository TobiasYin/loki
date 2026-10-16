package dev.thor.tools

/** The product deliberately offers bounded actions, never persistent down/up state. */
data class GamepadCommand(val button: String?, val axes: Map<String,Float>, val durationMs: Int) {
    companion object {
        val buttons = setOf("A","B","X","Y","L1","R1","L2","R2","START","SELECT","MODE","L3","R3","DPAD_UP","DPAD_DOWN","DPAD_LEFT","DPAD_RIGHT")
        val stickAxes = setOf("X","Y","Z","RZ","RX","RY","HAT_X","HAT_Y")
        val triggerAxes = setOf("LTRIGGER","RTRIGGER","BRAKE","GAS")
        fun parse(tool: String, args: Map<String,Any?>): GamepadCommand {
            val button = if(tool=="input.press_button") {
                requireTool(args.keys.all {it in setOf("button","phase","durationMs")},message="Unknown gamepad button argument")
                requireTool(args["phase"]=="hold",message="Only bounded hold is supported; down/up are not persistent operations")
                (args["button"] as? String).also {requireTool(it in buttons,message="Unknown gamepad button")}
            } else {
                requireTool(tool=="input.set_axes",message="Unknown gamepad operation")
                requireTool(args.keys.all {it in setOf("axes","durationMs")},message="Unknown gamepad axis argument")
                null
            }
            val duration=if("durationMs" in args)(args["durationMs"] as? Number)?.toDouble() ?: Double.NaN else if(button!=null)100.0 else Double.NaN
            requireTool(duration.isFinite() && duration==kotlin.math.floor(duration) && duration in 1.0..5000.0,message="Gamepad duration must be an integer 1..5000 ms")
            val axes=if(button!=null)emptyMap() else {
                val raw=args["axes"] as? Map<*,*> ?: fail(Code.INVALID_ARGUMENT,"axes must be an object")
                requireTool(raw.isNotEmpty() && raw.size<=12,message="Supply at least one and at most 12 axes")
                raw.entries.associate { (key,value) ->
                    val name=key as? String ?: fail(Code.INVALID_ARGUMENT,"Axis names must be strings")
                    requireTool(name in stickAxes || name in triggerAxes,message="Unknown gamepad axis $name")
                    val number=(value as? Number)?.toDouble() ?: fail(Code.INVALID_ARGUMENT,"Axis value must be numeric")
                    requireTool(number.isFinite() && number in (if(name in triggerAxes)0.0 else -1.0)..1.0,message="Axis $name is outside its normalized range")
                    name to number.toFloat()
                }
            }
            return GamepadCommand(button,axes,duration.toInt())
        }
    }
}
