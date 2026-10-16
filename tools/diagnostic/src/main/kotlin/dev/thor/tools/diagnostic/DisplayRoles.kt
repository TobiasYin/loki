package dev.thor.tools.diagnostic

import android.content.Context
import android.os.Build
import dev.thor.tools.DisplayFact

/** Persist user role hints, never stale numeric display IDs or input snapshots. */
class DisplayRoles(context: Context) {
    private val prefs=context.getSharedPreferences("display-roles",Context.MODE_PRIVATE)
    fun resolve(facts:List<DisplayFact>):Pair<DisplayFact,DisplayFact>? = dev.thor.tools.DisplayRolePolicy.resolve(
        facts,prefs.getString("upper",null),prefs.getString("lower",null),prefs.contains("upper") || prefs.contains("lower"),Build.MODEL.contains("Thor",true))
    fun save(upper:DisplayFact,lower:DisplayFact) {
        require(upper.id!=lower.id && upper.identity!=lower.identity)
        check(prefs.edit().putString("upper",upper.identity).putString("lower",lower.identity).commit())
    }
}
