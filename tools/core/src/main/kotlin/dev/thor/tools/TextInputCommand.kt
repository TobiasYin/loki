package dev.thor.tools

/** Unicode field editing through ACTION_SET_TEXT, without implicit Enter, IME action or submission. */
data class TextInputCommand(val nodeRef:String,val text:String,val mode:String) {
    fun updatedText(current:String,selectionStart:Int,selectionEnd:Int):String {
        if(mode=="replace")return text
        requireTool(current.length<=MAX_RESULT_UTF16_UNITS,Code.LIMIT_EXCEEDED,"Current field exceeds the text editing budget")
        val noSelection=selectionStart == -1 && selectionEnd == -1
        requireTool(noSelection || (selectionStart in 0..current.length && selectionEnd in 0..current.length),
            Code.TARGET_CHANGED,"Text selection is unavailable or out of bounds; read ui_tree again")
        val start=if(noSelection)current.length else minOf(selectionStart,selectionEnd)
        val end=if(noSelection)current.length else maxOf(selectionStart,selectionEnd)
        fun boundary(index:Int)=index==0 || index==current.length ||
            !(current[index-1].isHighSurrogate() && current[index].isLowSurrogate())
        requireTool(boundary(start) && boundary(end),Code.TARGET_CHANGED,"Text selection splits a Unicode character")
        requireTool(current.length-(end-start)+text.length<=MAX_RESULT_UTF16_UNITS,Code.LIMIT_EXCEEDED,
            "Resulting text exceeds the text editing budget")
        return current.substring(0,start)+text+current.substring(end)
    }
    companion object {
        const val MAX_TEXT_CODE_POINTS=4096
        const val MAX_RESULT_UTF16_UNITS=65536
        fun parse(args:Map<String,Any?>):TextInputCommand {
            requireTool(args.keys.all {it in setOf("nodeRef","text","mode")} && args.keys.containsAll(setOf("nodeRef","text")),
                message="Text input accepts only nodeRef, text and optional mode")
            val ref=args["nodeRef"] as? String ?: fail(Code.INVALID_ARGUMENT,"nodeRef is required")
            // Use exactly the existing activation ref validation and retain its owner/window/node identity guard.
            UiActivationGuard.parse(mapOf("nodeRef" to ref))
            val text=args["text"] as? String ?: fail(Code.INVALID_ARGUMENT,"text must be a string")
            requireTool(text.codePointCount(0,text.length)<=MAX_TEXT_CODE_POINTS,Code.LIMIT_EXCEEDED,"text exceeds 4096 Unicode code points")
            var index=0
            while(index<text.length) {
                val c=text[index++]
                requireTool(!c.isLowSurrogate() && (!c.isHighSurrogate() ||
                    (index<text.length && text[index++].isLowSurrogate())),message="text contains an invalid Unicode surrogate")
            }
            val mode=if("mode" in args)args["mode"] as? String ?: fail(Code.INVALID_ARGUMENT,"mode must be insert or replace") else "insert"
            requireTool(mode in setOf("insert","replace"),message="mode must be insert or replace")
            return TextInputCommand(ref,text,mode)
        }
    }
}

object UiTextInputGuard {
    fun requireEditable(visible:Boolean,enabled:Boolean,editable:Boolean,password:Boolean,hasSetTextAction:Boolean,
        bounds:TouchableBounds,display:DisplayFact,clip:TouchableBounds?) {
        requireTool(visible && enabled && editable && !password && hasSetTextAction,Code.INVALID_ARGUMENT,
            "Text input requires a visible enabled non-password editable field with ACTION_SET_TEXT")
        UiActivationGuard.requireVisibleBounds(bounds,display,clip)
    }
}
