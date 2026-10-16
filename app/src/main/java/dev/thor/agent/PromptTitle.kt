package dev.thor.agent

/** Ten Unicode code points, so a surrogate pair never gets split. */
fun promptTitle(prompt:String):String {
    val text=prompt.trim().replace(Regex("\\s+")," ")
    return if(text.isEmpty())"新会话" else text.substring(0,text.offsetByCodePoints(0,minOf(10,text.codePointCount(0,text.length))))
}
