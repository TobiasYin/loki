package dev.thor.tools.diagnostic

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.thor.tools.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Buttons and axes use a fixed physical evdev writer; injected event identity is never trusted. */
@android.annotation.SuppressLint("BlockedPrivateApi") // Instantiated exclusively inside the UID-checked shell service.
internal class NativeGamepad {
    init {requireTool(android.os.Process.myUid()==2000,Code.PERMISSION_MISSING,"Native gamepad injection requires the bound shell service")}
    private val scheduler=Executors.newSingleThreadScheduledExecutor { runnable->Thread(runnable,"thor-gamepad-release").apply {isDaemon=true} }
    private var held: Action?=null
    // Metadata discovery may wait for getevent. It must not hold the action/timer monitor.
    fun status(): Map<String,Any?> {
        val device=controller()
        val ranges=axisIds.mapNotNull {(name,id)->device.getMotionRange(id,InputDevice.SOURCE_JOYSTICK)?.let {range->
            name to mapOf("axisId" to id,"min" to range.min,"max" to range.max,"flat" to range.flat,"fuzz" to range.fuzz)
        }}.toMap()
        val reportedKeys=device.hasKeys(*buttonKeys.values.toIntArray())
        var evdev: Map<String,Any?>?=null;var evdevError: String?=null
        try {EvdevGamepad.open(device) {}.let {backend->try {evdev=backend.describe()}finally {backend.close()}}}
        catch(t:Exception){evdevError=(t as? ToolFailure)?.message ?: "Evdev inspection failed: ${t.javaClass.simpleName}"}
        return mapOf("deviceId" to device.id,"deviceName" to device.name,"sources" to device.sources,
            "gamepad" to (device.supportsSource(InputDevice.SOURCE_GAMEPAD) && evdev!=null),"joystick" to (device.supportsSource(InputDevice.SOURCE_JOYSTICK) && evdev!=null),
            "axes" to if(evdev!=null)EvdevAxes.codes.keys.toList() else emptyList<String>(),"motionRanges" to ranges,"buttons" to buttonKeys.keys.filterIndexed {index,_->reportedKeys[index]},
            "buttonBackend" to if(evdev!=null)"shizuku-evdev/odin-buttons" else null,
            "axisBackend" to evdev,"axisBackendError" to evdevError,"evdevBackendError" to evdevError,"receiverVerified" to false,
            "releaseGuarantee" to "bounded-service-watchdog; service-process-SIGKILL-cannot-guarantee-release")
    }
    private fun controller(): InputDevice {
        val candidates=InputDevice.getDeviceIds().toList().mapNotNull(InputDevice::getDevice).filter {
            !it.isVirtual && (it.supportsSource(InputDevice.SOURCE_GAMEPAD)||it.supportsSource(InputDevice.SOURCE_JOYSTICK))
        }
        val odin=candidates.filter {it.name.equals("Odin Controller",ignoreCase=true)}
        return (if(odin.isNotEmpty())odin else candidates).singleOrNull()
            ?: fail(Code.BACKEND_UNAVAILABLE,"Physical controller missing or ambiguous; no keyboard/touch fallback")
    }
    @Synchronized fun prepare(command: GamepadCommand, displayId: Int, expectedPackage: String?, checkAlive: ()->Unit): Action {
        requireTool(displayId>=0,message="Explicit displayId required")
        requireTool(held==null,Code.BACKEND_UNAVAILABLE,"Another gamepad action is still held")
        checkAlive()
        val device=controller()
        val key=command.button?.let {buttonKeys.getValue(it)}
        var evdev: EvdevGamepad?=null
        try {
            if(key!=null) {
                requireTool(device.supportsSource(InputDevice.SOURCE_GAMEPAD),Code.UNSUPPORTED,"Controller has no GAMEPAD source")
                requireTool(device.hasKeys(key).single(),Code.UNSUPPORTED,"Requested button is not reported by the physical controller")
            } else {
                requireTool(device.supportsSource(InputDevice.SOURCE_JOYSTICK),Code.UNSUPPORTED,"Controller has no JOYSTICK source")
                requireTool(command.axes.keys.all {it in EvdevAxes.codes},Code.UNSUPPORTED,"Only audited X/Y/Z/RZ/GAS/BRAKE evdev axes are supported")
                command.axes.forEach {(name,_)->requireTool(device.getMotionRange(axisIds.getValue(name),InputDevice.SOURCE_JOYSTICK)!=null,Code.UNSUPPORTED,"Controller does not report axis $name")}
            }
            EvdevGamepad.verifyFocus(displayId,expectedPackage)
            evdev=EvdevGamepad.open(device,checkAlive)
            val button=command.button
            if(button!=null)evdev.verifyButtonReleased(button) else evdev.verifyNeutral(command.axes.keys)
            return Action(command,displayId,expectedPackage,device,key,evdev,checkAlive)
        }catch(t:Throwable){runCatching {evdev?.close()};throw t}
    }
    @Synchronized fun release() {held?.finish(ToolFailure(Code.CANCELLED,"Run-owned gamepad inputs released"))}
    @Synchronized fun close() {release();scheduler.shutdownNow()}

    inner class Action internal constructor(private val command: GamepadCommand,private val displayId: Int,private val expectedPackage: String?,
        private val device: InputDevice,private val keyCode: Int?,private val evdev: EvdevGamepad,private val checkAlive: ()->Unit) {
        private val preparedAt=SystemClock.uptimeMillis()
        @Volatile var started=false;private set
        @Volatile var complete=false;private set
        @Volatile private var failure: Throwable?=null
        @Volatile private var cleanupReceipt: GamepadCleanup.Receipt?=null
        private var repeat: java.util.concurrent.ScheduledFuture<*>?=null
        private var submissionAttempted=false
        val mayHaveSubmitted get()=submissionAttempted
        fun start()=synchronized(this@NativeGamepad) {
            requireTool(!started && !complete && held==null,Code.STALE_AUTHORIZATION,"Gamepad preparation was used or cancelled")
            requireTool(SystemClock.uptimeMillis()-preparedAt<2000,Code.TIMEOUT,"Gamepad preparation expired; observe and prepare again")
            checkAlive();checkIdentity()
            started=true;held=this
            try {
                submit(false)
                scheduler.schedule({finish(null)},command.durationMs.toLong(),TimeUnit.MILLISECONDS)
                repeat=scheduler.scheduleAtFixedRate({tick()},16,16,TimeUnit.MILLISECONDS)
            }catch(t:Throwable){finish(t);throw t}
        }
        private fun tick()=synchronized(this@NativeGamepad) {
            if(complete)return@synchronized
            try {checkAlive();checkIdentity()}catch(t:Throwable){finish(t)}
        }
        private fun checkIdentity() {
            val current=InputDevice.getDevice(device.id)
            requireTool(current!=null && current.descriptor==device.descriptor && current.sources==device.sources,Code.TARGET_CHANGED,"Physical controller was rebuilt during gamepad action")
            evdev.verifyIdentity();EvdevGamepad.verifyFocus(displayId,expectedPackage)
        }
        private fun submit(neutral: Boolean) {
            if(!neutral){checkAlive();checkIdentity()}
            submissionAttempted=true
            val button=command.button
            if(button!=null)evdev.writeButton(button,!neutral)
            else evdev.write(command.axes.mapValues {(name,value)->val axis=evdev.profile.getValue(name);if(neutral)axis.neutral else axis.value(value)})
        }
        internal fun finish(error: Throwable?)=synchronized(this@NativeGamepad) {
            if(complete)return@synchronized
            repeat?.cancel(false)
            failure=error
            // Cleanup only writes neutral to the already-open fd. It never reopens/rebinds a replacement device.
            cleanupReceipt=GamepadCleanup.finish(submissionAttempted,{submit(true)},{evdev.close()})
            cleanupReceipt?.error?.let {failure=it}
            complete=true
            if(held===this)held=null
        }
        fun cleanupStatus(): Map<String,Any?> = synchronized(this@NativeGamepad) {
            (cleanupReceipt?.data() ?: mapOf("released" to false,"releaseAttempted" to submissionAttempted,
                "releaseAcknowledged" to false,"resourceClosed" to false,"cleanupComplete" to false,"cleanupError" to "cleanup-not-complete"))+
                mapOf("actionComplete" to complete,"actionStarted" to started)
        }
        fun result(): Map<String,Any?> {
            requireTool(complete,Code.INVALID_ARGUMENT,"Gamepad action is not complete")
            val f=failure
            if(f!=null) {
                val cause=if(f is java.lang.reflect.InvocationTargetException)f.targetException ?: f else f
                throw (cause as? ToolFailure ?: ToolFailure(if(cause is SecurityException)Code.PERMISSION_MISSING else Code.IO_ERROR,"Gamepad injection failed: ${cause.javaClass.simpleName}"))
            }
            requireTool(cleanupReceipt?.complete==true && cleanupReceipt?.releaseAcknowledged==true,Code.IO_ERROR,"Gamepad release was not acknowledged")
            return mapOf("submitted" to true,"released" to true,"button" to command.button,"axes" to command.axes,"durationMs" to command.durationMs,
                "deviceId" to device.id,"deviceName" to device.name,"displayId" to displayId,"source" to (if(keyCode!=null)InputDevice.SOURCE_GAMEPAD else InputDevice.SOURCE_JOYSTICK),
                "backend" to if(keyCode!=null)"shizuku-evdev/odin-buttons" else "shizuku-evdev/odin-six-axes",
                "displayRouting" to "verified-focused-physical-controller; no displayId injection",
                "receiverVerified" to false,"delivery" to "Kernel evdev frame written; receiver must independently confirm",
                "releaseGuarantee" to "bounded-service-watchdog; service-process-SIGKILL-cannot-guarantee-release")
        }
    }

    companion object {
        val buttonKeys=mapOf("A" to KeyEvent.KEYCODE_BUTTON_A,"B" to KeyEvent.KEYCODE_BUTTON_B,"X" to KeyEvent.KEYCODE_BUTTON_X,"Y" to KeyEvent.KEYCODE_BUTTON_Y,
            "L1" to KeyEvent.KEYCODE_BUTTON_L1,"R1" to KeyEvent.KEYCODE_BUTTON_R1,"L2" to KeyEvent.KEYCODE_BUTTON_L2,"R2" to KeyEvent.KEYCODE_BUTTON_R2,
            "START" to KeyEvent.KEYCODE_BUTTON_START,"SELECT" to KeyEvent.KEYCODE_BUTTON_SELECT,"MODE" to KeyEvent.KEYCODE_BUTTON_MODE,
            "L3" to KeyEvent.KEYCODE_BUTTON_THUMBL,"R3" to KeyEvent.KEYCODE_BUTTON_THUMBR,"DPAD_UP" to KeyEvent.KEYCODE_DPAD_UP,
            "DPAD_DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,"DPAD_LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,"DPAD_RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT)
        val axisIds=mapOf("X" to MotionEvent.AXIS_X,"Y" to MotionEvent.AXIS_Y,"Z" to MotionEvent.AXIS_Z,"RZ" to MotionEvent.AXIS_RZ,
            "RX" to MotionEvent.AXIS_RX,"RY" to MotionEvent.AXIS_RY,"LTRIGGER" to MotionEvent.AXIS_LTRIGGER,"RTRIGGER" to MotionEvent.AXIS_RTRIGGER,
            "BRAKE" to MotionEvent.AXIS_BRAKE,"GAS" to MotionEvent.AXIS_GAS,"HAT_X" to MotionEvent.AXIS_HAT_X,"HAT_Y" to MotionEvent.AXIS_HAT_Y)
    }
}
