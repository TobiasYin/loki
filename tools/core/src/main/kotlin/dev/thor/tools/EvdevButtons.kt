package dev.thor.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit Thor kernel codes and the shipped getevent labels, never caller supplied codes. */
object EvdevButtons {
    data class Mapping(val code: Int,val label: String,val layout: String)
    val mappings=mapOf(
        "A" to Mapping(0x130,"BTN_GAMEPAD","BUTTON_A"),"B" to Mapping(0x131,"BTN_EAST","BUTTON_B"),
        "X" to Mapping(0x133,"BTN_NORTH","BUTTON_X"),"Y" to Mapping(0x134,"BTN_WEST","BUTTON_Y"),
        "L1" to Mapping(0x136,"BTN_TL","BUTTON_L1"),"R1" to Mapping(0x137,"BTN_TR","BUTTON_R1"),
        "L2" to Mapping(0x138,"BTN_TL2","BUTTON_L2"),"R2" to Mapping(0x139,"BTN_TR2","BUTTON_R2"),
        "SELECT" to Mapping(0x13a,"BTN_SELECT","BUTTON_SELECT"),"START" to Mapping(0x13b,"BTN_START","BUTTON_START"),
        "MODE" to Mapping(0x13c,"BTN_MODE","BUTTON_MODE"),"L3" to Mapping(0x13d,"BTN_THUMBL","BUTTON_THUMBL"),
        "R3" to Mapping(0x13e,"BTN_THUMBR","BUTTON_THUMBR"),"DPAD_UP" to Mapping(0x220,"BTN_DPAD_UP","DPAD_UP"),
        "DPAD_DOWN" to Mapping(0x221,"BTN_DPAD_DOWN","DPAD_DOWN"),"DPAD_LEFT" to Mapping(0x222,"BTN_DPAD_LEFT","DPAD_LEFT"),
        "DPAD_RIGHT" to Mapping(0x223,"BTN_DPAD_RIGHT","DPAD_RIGHT"))
    fun states(metadata: String): Map<String,Boolean> {
        requireTool(metadata.length<=65536,Code.LIMIT_EXCEEDED,"Controller metadata exceeds budget")
        val section=Regex("(?s)KEY\\s*\\(0001\\):(.+?)(?=\\n\\s*[A-Z]{2,3}\\s*\\(|\\n\\s*input props:|$)").find(metadata)?.groupValues?.get(1)
            ?: fail(Code.UNSUPPORTED,"Controller does not report its key capability/state block")
        val tokens=Regex("\\b((?:BTN|KEY)_[A-Z0-9_]+)(\\*)?").findAll(section).toList()
        val result=linkedMapOf<String,Boolean>()
        for((name,mapping) in mappings) {
            val token=tokens.filter {it.groupValues[1]==mapping.label}
            requireTool(token.size<=1,Code.UNSUPPORTED,"Ambiguous controller button state")
            if(token.size==1)result[name]=token.single().groupValues[2]=="*"
        }
        return result
    }
    fun requireReleased(button: String,states: Map<String,Boolean>) {
        requireTool(button in mappings && button in states,Code.UNSUPPORTED,"Requested button is not reported by the audited controller")
        requireTool(states[button]==false,Code.BACKEND_UNAVAILABLE,"Requested controller button is already physically pressed; release before control")
    }
    fun verifyLayout(button: String,text: String) {
        val mapping=mappings[button] ?: fail(Code.UNSUPPORTED,"Unknown evdev button")
        val entries=Regex("(?m)^[ \\t]*key[ \\t]+(0x[0-9a-fA-F]+|[0-9]+)[ \\t]+([A-Z0-9_]+)(?:[ \\t]+[^\\n]*)?$").findAll(text).filter {
            val raw=it.groupValues[1];val code=if(raw.startsWith("0x"))raw.drop(2).toIntOrNull(16) else raw.toIntOrNull()
            code==mapping.code
        }.toList()
        requireTool(entries.size==1 && entries.single().groupValues[2]==mapping.layout,Code.UNSUPPORTED,"Firmware key layout does not map $button as audited")
    }
    fun frame(button: String,pressed: Boolean): ByteArray {
        val mapping=mappings[button] ?: fail(Code.UNSUPPORTED,"Unknown evdev button")
        return ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(0).putLong(0).putShort(1).putShort(mapping.code.toShort()).putInt(if(pressed)1 else 0)
            putLong(0).putLong(0).putShort(0).putShort(0).putInt(0)
        }.array()
    }
}
