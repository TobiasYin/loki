package dev.thor.tools.diagnostic

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.Parcel
import android.view.Display
import dev.thor.tools.*

/** AYN firmware SettingsController protocol, isolated from generic service/shell access. */
class ThorBrightness(private val context: Context) {
    fun execute(command: ThorBrightnessCommand, beforeMutation: ()->Unit): Map<String,Any?> {
        requireTool(android.os.Build.MODEL=="AYN Thor",Code.UNSUPPORTED,"Thor firmware required")
        requireTool(android.os.Build.DISPLAY=="Thor_V1.0.0.377_20260206_165408_user",Code.UNSUPPORTED,"Thor firmware brightness protocol not audited for this build")
        val displays=context.getSystemService(DisplayManager::class.java).displays
        val display=if(command.screen=="upper")displays.singleOrNull {it.displayId==Display.DEFAULT_DISPLAY}
            else displays.singleOrNull {it.name=="Screen-2" && it.displayId!=Display.DEFAULT_DISPLAY}
        requireTool(display!=null,Code.DISPLAY_GONE,"Selected Thor screen is unavailable or ambiguous")
        val binder=Class.forName("android.os.ServiceManager").getDeclaredMethod("getService",String::class.java).invoke(null,"SettingsController") as? IBinder
            ?: fail(Code.BACKEND_UNAVAILABLE,"Thor SettingsController missing")
        fun transact(value: Int?): Int {
            val input=Parcel.obtain();val output=Parcel.obtain()
            try {
                input.writeInt(display!!.displayId)
                if(value!=null){input.writeInt(value);input.writeInt(0)}
                // This shipped firmware dispatches GET at 0x4004 (0x4001 is
                // unimplemented), verified against its own command table.
                requireTool(binder.transact(if(value==null)16388 else 16386,input,output,0),Code.UNSUPPORTED,"Firmware brightness transaction unsupported")
                try {output.readException()}catch(e:SecurityException) {fail(Code.PERMISSION_MISSING,"Thor firmware refused brightness IPC: ${e.message.orEmpty().take(180)}")}
                val actual=output.readInt()
                requireTool(actual in 0..255,Code.IO_ERROR,"Firmware returned invalid brightness")
                return actual
            } finally {input.recycle();output.recycle()}
        }
        val before=transact(null)
        if(command.mutates){beforeMutation();transact(command.value)}
        val after=transact(null)
        return mapOf("screen" to command.screen,"displayId" to display!!.displayId,"displayName" to display.name,"before" to before,"brightness" to after,
            "requested" to command.value,"verified" to (!command.mutates || kotlin.math.abs(command.value!!-after)<=1),"quantizationTolerance" to 1,"policyTier" to "white","policyVersion" to ShellCommandPolicy.VERSION)
    }
}
