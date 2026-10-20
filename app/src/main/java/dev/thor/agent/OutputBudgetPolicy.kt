package dev.thor.agent

/** Model capacity and an optional user ceiling are separate. Blank official settings
 * follow each selected model; old implicit 2048 preferences never become a ceiling. */
object OutputBudgetPolicy {
    const val CUSTOM_DEFAULT = 2048
    data class Budget(val tokens:Int,val limit:Int,val automatic:Boolean)

    fun resolve(provider:String,contextWindow:Int,modelLimit:Int?=null,explicitText:String?=null,
                legacyCustomOutput:Int=CUSTOM_DEFAULT,rejectExcess:Boolean=false):Budget {
        require(provider in setOf("custom","opencode-go",OpenCodeZenCatalog.KIND,"deepseek",VolcAgentPlan.KIND))
        require(contextWindow in 4096..4000000)
        val official=provider!="custom"
        if(official)require(modelLimit!=null && modelLimit>=16 && modelLimit<=contextWindow)
        val limit=minOf(modelLimit ?: contextWindow-1,contextWindow-1)
        val text=explicitText.orEmpty().trim()
        val override=if(text.isEmpty())null else {
            require(text.matches(Regex("[0-9]+"))) { "输出上限必须为整数" }
            requireNotNull(text.toIntOrNull()) { "输出上限超出范围" }.also {require(it>=16)}
        }
        val requested=override ?: if(official)limit else legacyCustomOutput
        require(requested>=16)
        if(rejectExcess)require(requested<=limit) { "输出上限不能超过所选模型与上下文容量" }
        return Budget(minOf(requested,limit),limit,official && override==null)
    }
}
