package dev.thor.agent

/** Preserve the first meaningful line; presentation decides visual ellipsis. */
fun promptTitle(prompt:String):String {
    val text=prompt.lineSequence().map {it.trim().replace(Regex("[\\t ]+")," ")}.firstOrNull {it.isNotEmpty()} ?: return "新会话"
    // User prompts are bounded to 8000 chars. Keep a generous title budget and
    // never split a surrogate pair while excluding multiline bodies/code.
    return text.substring(0,text.offsetByCodePoints(0,minOf(512,text.codePointCount(0,text.length))))
}
