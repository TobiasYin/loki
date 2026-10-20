package dev.thor.agent

import android.content.Context
import java.security.SecureRandom

/** A stable provider routing identity per Native chat. Does not change the app's own IDs.
 * Format follows OpenCode Identifier.descending("session"): timestamp/counter + Base62. */
internal class OpenCodeSessionIds(context:Context) {
    private val prefs=context.getSharedPreferences("opencode-session-routing",0)
    fun forSession(nativeSession:String):String = synchronized(lock) {
        require(nativeSession.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        prefs.getString(nativeSession,null)?.takeIf {valid(it)} ?: create().also {
            check(prefs.edit().putString(nativeSession,it).commit())
        }
    }
    companion object {
        private val lock=Any()
        private val random=SecureRandom()
        private const val alphabet="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private var lastTimestamp=0L
        private var counter=0L
        fun valid(value:String)=value.matches(Regex("ses_[0-9a-f]{12}[0-9A-Za-z]{14}"))
        fun create(timestamp:Long=System.currentTimeMillis()):String = synchronized(lock) {
            require(timestamp>=0)
            if(timestamp!=lastTimestamp){lastTimestamp=timestamp;counter=0}
            counter++
            val time=((timestamp*0x1000+counter).inv() and 0xffffffffffffL).toString(16).padStart(12,'0')
            "ses_$time"+(1..14).map {alphabet[random.nextInt(alphabet.length)]}.joinToString("")
        }
    }
}
