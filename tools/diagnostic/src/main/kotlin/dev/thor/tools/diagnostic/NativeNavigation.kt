package dev.thor.tools.diagnostic

import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import dev.thor.tools.*

/** Fixed Android navigation, explicitly a virtual keyboard event, never a gamepad. */
@android.annotation.SuppressLint("BlockedPrivateApi")
internal class NativeNavigation private constructor(private val displayId:Int,private val expectedPackage:String,
    private val manager:Any,private val inject:java.lang.reflect.Method,private val down:KeyEvent,private val up:KeyEvent) {
    private var used=false
    private val preparedAt=SystemClock.uptimeMillis()
    fun submit(checkAlive:()->Unit,markSubmitted:()->Unit):Map<String,Any?> {
        requireTool(!used,Code.STALE_AUTHORIZATION,"Navigation preparation was already used")
        requireTool(SystemClock.uptimeMillis()-preparedAt<2000,Code.TIMEOUT,"Navigation preparation expired; re-observe and retry")
        used=true
        checkAlive();verifyFocus(displayId,expectedPackage,checkAlive)
        NavigationPair.dispatch(checkAlive,markSubmitted,
            {inject.invoke(manager,down,0) as Boolean}, // async: no wait or key hold inside the authority lock
            {inject.invoke(manager,up,0) as Boolean})
        return mapOf("action" to "back","submitted" to true,"released" to true,"displayId" to displayId,
            "source" to "android-keyboard-navigation","keyCode" to KeyEvent.KEYCODE_BACK,
            "deviceId" to KeyCharacterMap.VIRTUAL_KEYBOARD,"foregroundVerified" to false,
            "delivery" to "Android InputManager accepted a fixed BACK down/up pair; target handling is unverified",
            "nextStep" to "Re-observe the upper app/window/UI or take a fresh screenshot before any next input")
    }
    companion object {
        fun prepare(command:NavigationCommand,displayId:Int,expectedPackage:String?,checkAlive:()->Unit):NativeNavigation {
            requireTool(Process.myUid()==2000,Code.PERMISSION_MISSING,"Navigation requires the bound shell UID 2000 service")
            requireTool(command.action=="back" && displayId>=0,message="Fixed BACK and resolved upper display required")
            checkAlive();verifyFocus(displayId,expectedPackage,checkAlive)
            val type=Class.forName("android.hardware.input.InputManager")
            val manager=type.getDeclaredMethod("getInstance").invoke(null)
            val inject=type.getMethod("injectInputEvent",InputEvent::class.java,Int::class.javaPrimitiveType)
            val setDisplay=InputEvent::class.java.getMethod("setDisplayId",Int::class.javaPrimitiveType)
            val getDisplay=InputEvent::class.java.getMethod("getDisplayId")
            val now=SystemClock.uptimeMillis()
            fun event(action:Int)=KeyEvent(now,now,action,KeyEvent.KEYCODE_BACK,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,
                KeyEvent.FLAG_FROM_SYSTEM,InputDevice.SOURCE_KEYBOARD).also {
                setDisplay.invoke(it,displayId)
                requireTool(getDisplay.invoke(it)==displayId,Code.UNSUPPORTED,"Android navigation display routing could not be verified")
            }
            val down=event(KeyEvent.ACTION_DOWN);val up=event(KeyEvent.ACTION_UP)
            checkAlive()
            return NativeNavigation(displayId,expectedPackage!!,manager,inject,down,up)
        }
        internal fun verifyFocus(displayId:Int,expectedPackage:String?,checkAlive:()->Unit) {
            // A fixed per-display BACK can safely target the upper app while a
            // lower Presentation owns global focus. Physical gamepad stays stricter.
            val process=ProcessBuilder("/system/bin/dumpsys","-t","2","input").redirectErrorStream(true).start()
            try {
                val read=process.inputStream.use {Budgets.read(it,512*1024,checkAlive)}
                requireTool(!read.truncated && process.waitFor(2500,java.util.concurrent.TimeUnit.MILLISECONDS) && process.exitValue()==0,
                    Code.TARGET_CHANGED,"Current per-display navigation focus could not be read")
                checkAlive();NavigationFocus.verify(read.bytes.toString(Charsets.UTF_8),displayId,expectedPackage)
            } finally {if(process.isAlive)process.destroyForcibly()}
        }
    }
}
