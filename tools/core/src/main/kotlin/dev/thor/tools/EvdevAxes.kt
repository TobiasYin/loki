package dev.thor.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fixed, audited Thor axes. No caller-supplied node, event type, kernel code or raw value. */
object EvdevAxes {
    val codes=mapOf("X" to 0,"Y" to 1,"Z" to 2,"RZ" to 5,"GAS" to 9,"BRAKE" to 10)
    data class Node(val path: String,val vendor: Int,val product: Int,val sysfs: String)
    data class Axis(val name: String,val current: Int,val min: Int,val max: Int,val flat: Int) {
        val neutral get()=if(min<0)0 else min
        fun isNeutral()=abs(current.toLong()-neutral)<=flat.toLong()
        fun value(normalized: Float): Int {
            requireTool(normalized.isFinite() && normalized in (if(name in setOf("GAS","BRAKE"))0f else -1f)..1f,message="Invalid normalized evdev axis")
            val value=if(name in setOf("GAS","BRAKE"))min+normalized.toDouble()*(max.toLong()-min)
                else if(normalized>=0)normalized.toDouble()*max else -normalized.toDouble()*min
            return value.roundToInt().coerceIn(min,max)
        }
    }
    fun node(proc: String): Node {
        requireTool(proc.length<=128*1024,Code.LIMIT_EXCEEDED,"Input device metadata too large")
        val candidates=proc.split(Regex("\\n\\s*\\n")).filter {Regex("(?m)^N: Name=\"Odin Controller\"\\s*$").containsMatchIn(it)}
        requireTool(candidates.size==1,Code.BACKEND_UNAVAILABLE,"A unique Odin Controller kernel node is required")
        val block=candidates.single()
        val identifiers=Regex("(?m)^I: Bus=[0-9a-fA-F]+ Vendor=([0-9a-fA-F]{4}) Product=([0-9a-fA-F]{4}) Version=[0-9a-fA-F]+\\s*$").find(block)
            ?: fail(Code.UNSUPPORTED,"Missing kernel controller identity")
        val handlers=Regex("(?m)^H: Handlers=([^\\n]+)$").find(block)?.groupValues?.get(1).orEmpty().split(Regex("\\s+"))
        val event=handlers.filter {it.matches(Regex("event[0-9]{1,4}"))}.singleOrNull() ?: fail(Code.UNSUPPORTED,"Ambiguous controller event node")
        val sysfs=Regex("(?m)^S: Sysfs=(/devices/[A-Za-z0-9_./:@,-]+)\\s*$").find(block)?.groupValues?.get(1) ?: fail(Code.UNSUPPORTED,"Missing controller sysfs identity")
        requireTool(".." !in sysfs,message="Invalid controller sysfs identity")
        return Node("/dev/input/$event",identifiers.groupValues[1].toInt(16),identifiers.groupValues[2].toInt(16),sysfs)
    }
    fun profile(text: String,node: Node): Map<String,Axis> {
        requireTool(text.length<=65536,Code.LIMIT_EXCEEDED,"Axis metadata too large")
        val headers=Regex("(?m)^add device [0-9]+: (/dev/input/event[0-9]+)\\s*$").findAll(text).map {it.groupValues[1]}.toList()
        requireTool(headers==listOf(node.path) && Regex("(?m)^\\s*name:\\s*\"Odin Controller\"\\s*$").containsMatchIn(text),Code.TARGET_CHANGED,"Axis metadata belongs to another input node")
        val result=linkedMapOf<String,Axis>()
        val pattern=Regex("ABS_([A-Z0-9_]+)\\s*:\\s*value (-?[0-9]+), min (-?[0-9]+), max (-?[0-9]+), fuzz [0-9]+, flat ([0-9]+)")
        for(match in pattern.findAll(text)) {
            val name=match.groupValues[1];if(name !in codes)continue
            requireTool(name !in result,Code.UNSUPPORTED,"Duplicate evdev axis")
            val n=match.groupValues.drop(2).map {it.toIntOrNull() ?: fail(Code.UNSUPPORTED,"Invalid evdev range")}
            requireTool(n[1] in -1000000..1000000 && n[2] in -1000000..1000000 && n[1]<n[2] && n[0] in n[1]..n[2] && n[3] in 0..(n[2]-n[1]),Code.UNSUPPORTED,"Invalid evdev range")
            requireTool(if(name in setOf("GAS","BRAKE"))n[1]>=0 else n[1]<0 && n[2]>0,Code.UNSUPPORTED,"Unexpected evdev axis domain")
            result[name]=Axis(name,n[0],n[1],n[2],n[3])
        }
        requireTool(result.keys.containsAll(codes.keys),Code.UNSUPPORTED,"Controller does not report the audited six-axis profile")
        return result
    }
    /** Linux arm64 input_event: timeval(16) + type(2) + code(2) + value(4). */
    fun frame(values: Map<String,Int>,profile: Map<String,Axis>): ByteArray {
        requireTool(values.isNotEmpty() && values.keys.all {it in codes && it in profile},message="Unknown evdev axis")
        val frame=ByteBuffer.allocate((values.size+1)*24).order(ByteOrder.LITTLE_ENDIAN)
        for((name,value) in values) {
            val range=profile.getValue(name);requireTool(value in range.min..range.max,message="Raw axis value outside discovered range")
            frame.putLong(0).putLong(0).putShort(3).putShort(codes.getValue(name).toShort()).putInt(value)
        }
        frame.putLong(0).putLong(0).putShort(0).putShort(0).putInt(0)
        return frame.array()
    }
}
