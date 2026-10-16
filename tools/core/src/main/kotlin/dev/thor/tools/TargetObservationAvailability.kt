package dev.thor.tools

/** Availability is still non-ready. A fixed reason distinguishes an observation
 * that must be renewed from an actually missing/unauthorized execution backend.
 * This projection never authorizes or dispatches input. */
object TargetObservationAvailability {
    private val targetCodes=setOf(Code.TARGET_CHANGED,Code.PERMISSION_MISSING,Code.SECURE_CONTENT,Code.DISPLAY_GONE)
    fun project(base:Availability,descriptor:Descriptor,snapshot:Snapshot?,selectedRole:String,validate:()->Unit):Availability {
        // Genuine platform state has priority; do not relabel permissions,
        // unsupported hardware or a pending unknown gesture as target drift.
        if(base.status!=Status.READY || !descriptor.targetRequired)return base
        fun unavailable(reason:String,code:Code?=null)=base.copy(
            status=if(code==Code.PERMISSION_MISSING)Status.PERMISSION_MISSING else Status.TEMPORARILY_UNAVAILABLE,
            reason=reason,reasonCode=code)
        if(snapshot==null)return unavailable("无法确定上下屏，请在设备设置确认映射",Code.DISPLAY_GONE)
        if(descriptor.toolId!="perception.select_screen" && snapshot.role!=selectedRole)
            return unavailable("目标屏幕暂不可访问，请先选择其他屏幕",if(descriptor.effect==Effect.INPUT)Code.TARGET_CHANGED else null)
        if(descriptor.effect!=Effect.INPUT)return base
        try {validate()}
        catch(t:ToolFailure){return unavailable(t.message,t.code.takeIf {it in targetCodes})}
        catch(_:Exception){return unavailable("目标屏幕观察无法验证，请重新观察")}
        if(snapshot.target.validity!="valid")return unavailable("目标屏幕应用无法唯一确定，请重新观察",Code.TARGET_CHANGED)
        return base
    }
}
