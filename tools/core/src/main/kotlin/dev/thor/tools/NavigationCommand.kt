package dev.thor.tools

/** The model cannot select arbitrary Android key codes or routing parameters. */
data class NavigationCommand(val action:String) {
    companion object {
        fun parse(args:Map<String,Any?>):NavigationCommand {
            requireTool(args.keys==setOf("action") && args["action"]=="back",message="Navigation accepts only action=back")
            return NavigationCommand("back")
        }
    }
}

/** No hold, repeat or sleep. Once DOWN is attempted, UP cleanup ignores cancellation. */
object NavigationPair {
    fun dispatch(check:()->Unit,markSubmitted:()->Unit,down:()->Boolean,up:()->Boolean) {
        check()
        markSubmitted()
        try {
            requireTool(down(),Code.IO_ERROR,"Android BACK down submission was rejected")
            check()
        } finally {
            requireTool(up(),Code.IO_ERROR,"Android BACK up cleanup was rejected; delivery is unknown")
        }
    }
}
