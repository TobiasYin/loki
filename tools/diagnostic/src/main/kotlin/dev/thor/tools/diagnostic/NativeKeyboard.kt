package dev.thor.tools.diagnostic

import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import dev.thor.tools.*

/** One fixed virtual-keyboard stroke; Unicode text is handled by Accessibility. */
@android.annotation.SuppressLint("BlockedPrivateApi")
internal class NativeKeyboard private constructor(private val command:KeyboardCommand,private val displayId:Int,
    private val expectedPackage:String,private val manager:Any,private val inject:java.lang.reflect.Method,
    private val setDisplay:java.lang.reflect.Method,private val getDisplay:java.lang.reflect.Method) {
    private var used=false
    private val preparedAt=SystemClock.uptimeMillis()
    fun submit(checkAlive:()->Unit,markSubmitted:()->Unit):Map<String,Any?> {
        requireTool(!used,Code.STALE_AUTHORIZATION,"Keyboard preparation was already used")
        requireTool(SystemClock.uptimeMillis()-preparedAt<2000,Code.TIMEOUT,"Keyboard preparation expired; re-observe and retry")
        used=true
        checkAlive();NativeNavigation.verifyFocus(displayId,expectedPackage,checkAlive)
        val downTime=SystemClock.uptimeMillis()
        KeyboardPair.dispatch(command,checkAlive,markSubmitted) {keyCode,down,metaState->
            val event=event(keyCode,down,metaState,downTime)
            inject.invoke(manager,event,0) as Boolean
        }
        return mapOf("key" to command.key,"modifiers" to command.modifiers.map {it.name},"submitted" to true,
            "released" to true,"displayId" to displayId,"source" to "android-virtual-keyboard",
            "keyCode" to command.keyCode,"deviceId" to KeyCharacterMap.VIRTUAL_KEYBOARD,"foregroundVerified" to false,
            "delivery" to "Android InputManager accepted a bounded keyboard down/up sequence; target handling is unverified",
            "nextStep" to "Re-observe the selected app/window/UI before any next input")
    }
    private fun event(keyCode:Int,down:Boolean,metaState:Int,downTime:Long)=KeyEvent(downTime,SystemClock.uptimeMillis(),
        if(down)KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,keyCode,0,metaState,KeyCharacterMap.VIRTUAL_KEYBOARD,0,
        KeyEvent.FLAG_FROM_SYSTEM,InputDevice.SOURCE_KEYBOARD).also {
        setDisplay.invoke(it,displayId)
        requireTool(getDisplay.invoke(it)==displayId,Code.UNSUPPORTED,"Keyboard display routing could not be verified")
    }
    companion object {
        fun prepare(command:KeyboardCommand,displayId:Int,expectedPackage:String?,checkAlive:()->Unit):NativeKeyboard {
            requireTool(Process.myUid()==2000,Code.PERMISSION_MISSING,"Keyboard requires the bound shell UID 2000 service")
            requireTool(displayId>=0 && !expectedPackage.isNullOrBlank(),Code.TARGET_CHANGED,"Keyboard requires a resolved selected-screen app")
            checkAlive();NativeNavigation.verifyFocus(displayId,expectedPackage,checkAlive)
            val type=Class.forName("android.hardware.input.InputManager")
            val manager=type.getDeclaredMethod("getInstance").invoke(null)
            val inject=type.getMethod("injectInputEvent",InputEvent::class.java,Int::class.javaPrimitiveType)
            val setDisplay=InputEvent::class.java.getMethod("setDisplayId",Int::class.javaPrimitiveType)
            val getDisplay=InputEvent::class.java.getMethod("getDisplayId")
            val action=NativeKeyboard(command,displayId,expectedPackage!!,manager,inject,setDisplay,getDisplay)
            // Check route construction without sending any event during preparation.
            val now=SystemClock.uptimeMillis()
            action.event(command.keyCode,true,0,now);action.event(command.keyCode,false,0,now)
            command.modifiers.forEach {action.event(it.keyCode,true,it.metaMask,now);action.event(it.keyCode,false,0,now)}
            checkAlive();return action
        }
    }
}
