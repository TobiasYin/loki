package dev.thor.tools

/** Fixed Android keyboard keys. This is not the physical gamepad or an arbitrary keycode API. */
enum class KeyboardModifier(val keyCode:Int,val metaMask:Int) {
    CTRL(113,0x1000), ALT(57,0x2), SHIFT(59,0x1), META(117,0x10000)
}

data class KeyboardCommand(val key:String,val keyCode:Int,val modifiers:List<KeyboardModifier>) {
    companion object {
        val keyCodes:Map<String,Int> = ('A'..'Z').associate {it.toString() to 29+(it-'A')} +
            ('0'..'9').associate {it.toString() to 7+(it-'0')} + mapOf(
                "ENTER" to 66,"TAB" to 61,"BACKSPACE" to 67,"DELETE" to 112,"ESCAPE" to 111,"SPACE" to 62,
                "UP" to 19,"DOWN" to 20,"LEFT" to 21,"RIGHT" to 22,"HOME" to 122,"END" to 123,
                "PAGE_UP" to 92,"PAGE_DOWN" to 93)
        fun parse(args:Map<String,Any?>):KeyboardCommand {
            requireTool(args.keys.all {it in setOf("key","modifiers")} && "key" in args,
                message="Keyboard input accepts only a fixed key and optional modifiers")
            val key=args["key"] as? String ?: fail(Code.INVALID_ARGUMENT,"key must be a supported keyboard name")
            val code=keyCodes[key] ?: fail(Code.INVALID_ARGUMENT,"Unsupported keyboard key")
            val raw=if("modifiers" in args) args["modifiers"] as? List<*>
                ?: fail(Code.INVALID_ARGUMENT,"modifiers must be an array") else emptyList<Any?>()
            requireTool(raw.size<=4 && raw.distinct().size==raw.size,message="At most four distinct keyboard modifiers are accepted")
            val modifiers=raw.map {name->KeyboardModifier.entries.firstOrNull {it.name==name}
                ?: fail(Code.INVALID_ARGUMENT,"Unsupported keyboard modifier")}
            return KeyboardCommand(key,code,modifiers)
        }
    }
}

/** No repeat, hold or sleep. Every attempted DOWN is paired with UP even after cancellation or I/O failure. */
object KeyboardPair {
    fun dispatch(command:KeyboardCommand,check:()->Unit,markSubmitted:()->Unit,
        send:(keyCode:Int,down:Boolean,metaState:Int)->Boolean) {
        val attempted=mutableListOf<Pair<Int,Int>>()
        var meta=0
        var failure:Throwable?=null
        check()
        markSubmitted()
        try {
            for(modifier in command.modifiers) {
                check()
                meta=meta or modifier.metaMask
                attempted+=modifier.keyCode to modifier.metaMask
                requireTool(send(modifier.keyCode,true,meta),Code.IO_ERROR,"Keyboard modifier DOWN was rejected")
            }
            check()
            attempted+=command.keyCode to 0
            requireTool(send(command.keyCode,true,meta),Code.IO_ERROR,"Keyboard DOWN was rejected")
            check()
        } catch(t:Throwable) {failure=t}
        finally {
            for((code,mask) in attempted.asReversed()) {
                // Cleanup deliberately does not call check(): Stop must never strand a modifier.
                meta=meta and mask.inv()
                try {requireTool(send(code,false,meta),Code.IO_ERROR,"Keyboard UP cleanup was rejected; delivery is unknown")}
                catch(t:Throwable) {if(failure==null)failure=t else if(failure!==t)failure!!.addSuppressed(t)}
            }
        }
        failure?.let {throw it}
    }
}
