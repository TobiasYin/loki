package dev.thor.inputlab

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

object Capture {
    val standardAxes = listOf(0,1,11,14,12,13,17,18,23,22,15,16)
    fun ranges(d: InputDevice?) = d?.motionRanges?.map { Range(it.axis,it.source,it.min,it.max,it.flat) } ?: emptyList()
    fun device(d: InputDevice?): Map<String,Any?> = if(d == null) mapOf("available" to false,"reason" to "device_not_available") else mapOf(
        "available" to true, "deviceId" to d.id,"name" to d.name,"descriptor" to d.descriptor,"vendorId" to d.vendorId,
        "productId" to d.productId,"isVirtual" to d.isVirtual,"sources" to d.sources,
        "motionRanges" to d.motionRanges.map { mapOf("axis" to it.axis,"source" to it.source,"min" to it.min,"max" to it.max,"flat" to it.flat,"fuzz" to it.fuzz,"resolution" to it.resolution) })
    fun key(e: KeyEvent): Pair<Raw, Map<String,Any?>> = Raw("key",e.action,e.eventTime,e.downTime,e.source,e.deviceId,e.keyCode,e.repeatCount) to mapOf(
        "action" to e.action,"keyCode" to e.keyCode,"scanCode" to e.scanCode,"repeatCount" to e.repeatCount,
        "metaState" to e.metaState,"flags" to e.flags,"source" to e.source,"deviceId" to e.deviceId,
        "downTime" to e.downTime,"eventTime" to e.eventTime,"eventClockId" to "android_uptime_ms",
        "eventDisplayId" to null,"eventDisplayIdReason" to "not_exposed_by_public_InputEvent_API")
    fun motion(e: MotionEvent, kind: String): Pair<Raw, Map<String,Any?>> {
        val axisIds = (standardAxes + (e.device?.motionRanges?.map { it.axis } ?: emptyList())).distinct()
        val batches = (0..e.historySize).map { h ->
            val historical = h < e.historySize
            mapOf("sampleIndex" to h,"historical" to historical,"eventTime" to if(historical) e.getHistoricalEventTime(h) else e.eventTime,
                "pointers" to (0 until e.pointerCount).map { p -> mapOf(
                    "pointerId" to e.getPointerId(p),"toolType" to e.getToolType(p),
                    "x" to if(historical) e.getHistoricalX(p,h) else e.getX(p),"y" to if(historical) e.getHistoricalY(p,h) else e.getY(p),
                    "rawX" to if(historical) null else e.getRawX(p),"rawY" to if(historical) null else e.getRawY(p),
                    "rawCoordinateReason" to if(historical) "historical_raw_coordinates_not_exposed" else null,
                    "pressure" to if(historical) e.getHistoricalPressure(p,h) else e.getPressure(p),"size" to if(historical) e.getHistoricalSize(p,h) else e.getSize(p),
                    "axes" to axisIds.associate { axis -> axis.toString() to if(historical) e.getHistoricalAxisValue(axis,p,h) else e.getAxisValue(axis,p) }) })
        }
        val samples = (0..e.historySize).map { h -> Sample(
            if(h<e.historySize) e.getHistoricalEventTime(h) else e.eventTime,
            if(h<e.historySize) e.getHistoricalX(0,h) else e.x,
            if(h<e.historySize) e.getHistoricalY(0,h) else e.y,
            axisIds.associateWith { axis -> if(h<e.historySize) e.getHistoricalAxisValue(axis,0,h) else e.getAxisValue(axis,0) }) }
        return Raw(kind,e.actionMasked,e.eventTime,e.downTime,e.source,e.deviceId,samples=samples,pointerCount=e.pointerCount) to mapOf(
            "actionMasked" to e.actionMasked,"actionIndex" to e.actionIndex,"pointerCount" to e.pointerCount,"historySize" to e.historySize,
            "buttonState" to e.buttonState,"metaState" to e.metaState,"flags" to e.flags,"source" to e.source,"deviceId" to e.deviceId,
            "downTime" to e.downTime,"eventTime" to e.eventTime,"eventClockId" to "android_uptime_ms","samples" to batches,
            "advertisedAxisIds" to (e.device?.motionRanges?.filter { e.isFromSource(it.source) }?.map { it.axis } ?: emptyList<Int>()),
            "axisAvailabilityPolicy" to "raw_common_axes_included_even_if_unadvertised; assertions_use_only_advertised_source_ranges",
            "eventDisplayId" to null,"eventDisplayIdReason" to "not_exposed_by_public_InputEvent_API")
    }
}
