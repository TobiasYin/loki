package dev.thor.agent

import android.content.Context

/** Resolve at hold start; provider switching never copies secrets to voice preferences. */
object VoiceServices {
    fun usesPlan(context:Context)=!context.getSharedPreferences("voice",0).getBoolean("standalone",false) && VolcAgentPlan.selected(context)!=null
    fun resolve(context:Context):VoiceConfig? {
        if(usesPlan(context))return VolcAgentPlan.credential(context)?.let {VoiceConfig(it,null,null,VolcAgentPlan.ASR_RESOURCE,VolcAgentPlan.ASR_ENDPOINT)}
        val prefs=context.getSharedPreferences("voice",0);val vault=CredentialVault(context)
        val modern=prefs.getInt("auth",0)==0
        val api=if(modern)vault.readNative("voice.api") else null
        val app=prefs.getString("appId",null);val token=if(!modern)vault.readNative("voice.access") else null
        if(if(modern)api.isNullOrBlank() else app.isNullOrBlank() || token.isNullOrBlank())return null
        return VoiceConfig(api,app,token,prefs.getString("resource","volc.bigasr.sauc.duration")!!)
    }
}
