package dev.thor.tools

/** Fixed-component framework acknowledgement. Raw dumps and unrelated service
 * identities must remain inside the native reader. Unknown is never OFF. */
object AccessibilityFrameworkState {
    data class State(val known:Boolean, val enabled:Boolean=false, val binding:Boolean=false,
        val crashed:Boolean=false, val bound:Boolean?=null) {
        val offAcknowledged:Boolean get()=known && !enabled && !binding && !crashed && bound==false
        /** Android's package force-stop callback only clears binding/crashed
         * entries that are still present in its enabled-service inventory. A crashed
         * service without an outstanding bind can be cycled normally. */
        val retainEnabledForRestart:Boolean get()=known && binding
        val phase:String get()=when {
            !known -> "OFF_ACK_UNAVAILABLE"
            binding -> "FRAMEWORK_BINDING"
            crashed -> "FRAMEWORK_CRASHED"
            enabled -> "FRAMEWORK_ENABLED"
            bound==true -> "FRAMEWORK_BOUND"
            bound==null -> "OFF_ACK_UNAVAILABLE"
            else -> "OFF_ACKNOWLEDGED"
        }
    }
    fun inspect(dump:String,user:Int,applicationPackage:String,ownBound:Boolean?,
        publicBoundListPopulated:Boolean=false,dumpComplete:Boolean=true):State {
        if(dump.length>512*1024 || user<0)return State(false)
        val current=Regex("\\bcurrentUserId\\s*=\\s*(\\d+)").findAll(dump).toList()
        if(current.size!=1 || current.single().groupValues[1].toIntOrNull()!=user)return State(false)
        val starts=Regex("User state\\[").findAll(dump).map {it.range.first}.toList()
        val sections=starts.mapIndexed {i,start->dump.substring(start,starts.getOrNull(i+1) ?: dump.length)}
        val matched=sections.filter {Regex("attributes:\\{id=$user(?:,|\\})").containsMatchIn(it)}
        if(matched.size!=1)return State(false)
        val section=matched.single()
        if(!section.contains("Client list info:{"))return State(false)
        fun body(name:String):String? {
            val matches=Regex("(?m)^\\s*${Regex.escape(name)}:\\{").findAll(section).toList()
            if(matches.size!=1)return null
            val start=matches.single().range.last+1
            var depth=1
            for(i in start until section.length) {
                if(section[i]=='{')depth++
                if(section[i]=='}' && --depth==0)return section.substring(start,i)
            }
            return null
        }
        val own="$applicationPackage/dev.thor.tools.diagnostic.ThorAccessibilityService"
        fun owns(name:String):Boolean? {
            val value=body(name) ?: return null
            if(value.isBlank())return false
            val components=value.split(',').map {it.trim()}
            if(components.any {!it.matches(Regex("\\{[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+\\}"))})return null
            return components.any {it.substring(1,it.length-1).let {c->
                val p=c.substringBefore('/');val cls=c.substringAfter('/');"$p/${if(cls.startsWith('.'))p+cls else cls}"==own
            }}
        }
        val enabled=owns("Enabled services") ?: return State(false)
        val binding=owns("Binding services") ?: return State(false)
        val crashed=owns("Crashed services") ?: return State(false)
        val boundDump=body("Bound services") ?: return State(false)
        // Suppression always returns an empty public list in Android. A nonempty
        // list therefore proves that FLAG_DONT_SUPPRESS is in effect (or there
        // is no automation), even though the dump does not expose that flag.
        // Incomplete unrelated tail must not be used to infer no automation.
        val bound=when {
            ownBound==true -> true
            boundDump.isBlank() -> false
            ownBound==false && publicBoundListPopulated -> false
            !dumpComplete || dump.contains("Ui Automation[") -> null
            else -> ownBound
        }
        return State(true,enabled,binding,crashed,bound)
    }
}
