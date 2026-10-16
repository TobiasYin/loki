package dev.thor.tools

/** Re-resolve role hints on a fresh display inventory; never revive old snapshots. */
object DisplayRolePolicy {
    fun resolve(facts:List<DisplayFact>,upperName:String?,lowerName:String?,hasSavedMapping:Boolean,isThor:Boolean):Pair<DisplayFact,DisplayFact>? {
        if(hasSavedMapping) {
            val upper=facts.singleOrNull {it.identity==upperName}
            val lower=facts.singleOrNull {it.identity==lowerName}
            return if(upper!=null && lower!=null && upper.id!=lower.id)upper to lower else null
        }
        if(!isThor)return null
        val primary=facts.singleOrNull {it.id==0} ?: return null
        val lower=facts.singleOrNull {it.id!=0 && it.identity=="Screen-2"} ?: return null
        return primary to lower
    }
}
