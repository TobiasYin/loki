package dev.thor.tools.diagnostic

import android.content.ComponentName
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.view.InputDevice
import dev.thor.tools.*
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Fixed gamepad writer. Never accepts a caller path, kernel code, type or unscaled value. */
internal class EvdevGamepad private constructor(val node: EvdevAxes.Node,val profile: Map<String,EvdevAxes.Axis>,
    val buttons: Map<String,Boolean>,private val keylayout: String,private val fd: FileDescriptor,private val openedStat: StructStat,private val sysfsIdentity: String) {
    private var closed=false
    fun verifyIdentity() {
        requireTool(!closed,Code.CANCELLED,"Controller event fd is closed")
        val current=Os.stat(node.path)
        requireTool(current.st_ino==openedStat.st_ino && current.st_rdev==openedStat.st_rdev && current.st_dev==openedStat.st_dev,
            Code.TARGET_CHANGED,"Controller kernel node was replaced")
        requireTool(File("/sys/class/input/${File(node.path).name}/device").canonicalPath==sysfsIdentity,
            Code.TARGET_CHANGED,"Controller sysfs identity was rebuilt")
    }
    fun verifyNeutral(names: Set<String>) {
        requireTool(names.isNotEmpty() && names.all {it in EvdevAxes.codes},Code.UNSUPPORTED,"Only X/Y/Z/RZ/GAS/BRAKE evdev axes are supported")
        requireTool(names.all {profile.getValue(it).isNeutral()},Code.BACKEND_UNAVAILABLE,"Requested controller axis is physically non-neutral; release it before control")
    }
    fun verifyButtonReleased(button: String) {
        EvdevButtons.verifyLayout(button,keylayout)
        EvdevButtons.requireReleased(button,buttons)
    }
    fun write(values: Map<String,Int>) {
        requireTool(!closed,Code.CANCELLED,"Controller event fd is closed")
        writeFrame(EvdevAxes.frame(values,profile))
    }
    fun writeButton(button: String,pressed: Boolean) {
        requireTool(button in buttons,Code.UNSUPPORTED,"Button is absent from the discovered controller")
        writeFrame(EvdevButtons.frame(button,pressed))
    }
    private fun writeFrame(bytes: ByteArray) {
        requireTool(!closed,Code.CANCELLED,"Controller event fd is closed")
        // One complete frame avoids per-axis processes and preserves one SYN_REPORT boundary.
        val written=Os.write(fd,bytes,0,bytes.size)
        requireTool(written==bytes.size,Code.IO_ERROR,"Incomplete evdev frame; release cleanup is required")
    }
    fun close() {if(!closed){closed=true;Os.close(fd)}}
    fun describe()=mapOf("backend" to "shizuku-evdev/odin-gamepad","kernelNode" to node.path,"kernelButtonsPressed" to buttons,"kernelAxisRanges" to profile.mapValues {(_,axis)->
        mapOf("code" to EvdevAxes.codes.getValue(axis.name),"value" to axis.current,"min" to axis.min,"max" to axis.max,"neutral" to axis.neutral,"flat" to axis.flat)},
        "displayRouting" to "physical-controller-focus; not displayId injection")
    companion object {
        private fun boundedText(file: File,max: Int): String = file.inputStream().use {input->
            val bytes=Budgets.read(input,max);requireTool(!bytes.truncated,Code.LIMIT_EXCEEDED,"Controller metadata exceeds budget");bytes.bytes.toString(Charsets.UTF_8)
        }
        fun open(device: InputDevice,checkAlive: ()->Unit): EvdevGamepad {
            requireTool(Process.myUid()==2000 && Build.MODEL=="AYN Thor" && Process.is64Bit() && ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN,
                Code.UNSUPPORTED,"Evdev axis writer requires shell UID 2000 on a 64-bit little-endian AYN Thor")
            checkAlive()
            val node=EvdevAxes.node(boundedText(File("/proc/bus/input/devices"),128*1024))
            requireTool(device.name=="Odin Controller" && device.vendorId==node.vendor && device.productId==node.product,
                Code.TARGET_CHANGED,"Kernel and Android controller identities differ")
            val event=File(node.path).name;val sys=File("/sys/class/input/$event/device")
            val sysfs=sys.canonicalPath
            requireTool(sysfs==File("/sys${node.sysfs}").canonicalPath && boundedText(File(sys,"name"),512).trim()=="Odin Controller",
                Code.TARGET_CHANGED,"Controller sysfs identity differs")
            requireTool(boundedText(File(sys,"id/vendor"),64).trim().toInt(16)==node.vendor && boundedText(File(sys,"id/product"),64).trim().toInt(16)==node.product,
                Code.TARGET_CHANGED,"Controller vendor/product differs")
            requireTool(File(node.path).canonicalPath==node.path,message="Controller event node may not be a symlink")
            val before=Os.stat(node.path)
            requireTool(OsConstants.S_ISCHR(before.st_mode),Code.UNSUPPORTED,"Controller node is not a character device")
            // The audited firmware's axis names must map to the expected Android axes. Never write this file.
            val layout=File("/system/usr/keylayout/Vendor_%04x_Product_%04x.kl".format(node.vendor,node.product))
            val keylayout=boundedText(layout,64*1024)
            for((name,code) in EvdevAxes.codes)requireTool(Regex("(?m)^\\s*axis\\s+(?:0x0*${code.toString(16)}|$code)\\s+$name\\s*(?:#.*)?$").containsMatchIn(keylayout),
                Code.UNSUPPORTED,"Firmware key layout does not map evdev $name as expected")
            val process=ProcessBuilder("/system/bin/getevent","-lp",node.path).redirectErrorStream(true).start()
            val text=try {
                requireTool(process.waitFor(2,TimeUnit.SECONDS),Code.TIMEOUT,"Controller axis discovery timed out")
                checkAlive()
                val bytes=Budgets.read(process.inputStream,65536,checkAlive)
                requireTool(process.exitValue()==0 && !bytes.truncated,Code.IO_ERROR,"Controller axis discovery failed")
                bytes.bytes.toString(Charsets.UTF_8)
            } finally {if(process.isAlive)process.destroyForcibly()}
            val profile=EvdevAxes.profile(text,node)
            val buttons=EvdevButtons.states(text)
            requireTool(buttons.keys.containsAll(EvdevButtons.mappings.keys),Code.UNSUPPORTED,"Controller does not report the audited button profile")
            for(button in buttons.keys)EvdevButtons.verifyLayout(button,keylayout)
            checkAlive()
            val fd=try {Os.open(node.path,OsConstants.O_WRONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,0)}
                catch(_:android.system.ErrnoException){fail(Code.PERMISSION_MISSING,"Shizuku shell cannot open the Odin controller node for bounded axis input")}
            try {
                val stat=Os.fstat(fd)
                requireTool(stat.st_ino==before.st_ino && stat.st_rdev==before.st_rdev && stat.st_dev==before.st_dev,Code.TARGET_CHANGED,"Controller node changed while opening")
                return EvdevGamepad(node,profile,buttons,keylayout,fd,stat,sysfs).also {it.verifyIdentity();checkAlive()}
            }catch(t:Throwable){Os.close(fd);throw t}
        }
        // This method runs only in the shell-UID Shizuku service, where the
        // app-target hidden-API restriction does not apply. Never try an app-UID fallback.
        @android.annotation.SuppressLint("BlockedPrivateApi")
        fun focusedTarget(): Pair<Int,String?> {
            requireTool(Process.myUid()==2000,Code.PERMISSION_MISSING,"Focused-task inspection requires the bound shell service")
            val atm=Class.forName("android.app.ActivityTaskManager").getDeclaredMethod("getService").invoke(null)
            val task=Class.forName("android.app.IActivityTaskManager").getMethod("getFocusedRootTaskInfo").invoke(atm)
                ?: fail(Code.TARGET_CHANGED,"No focused task for physical controller routing")
            val display=task.javaClass.getField("displayId").getInt(task)
            val component=task.javaClass.getField("topActivity").get(task) as? ComponentName
            return display to component?.packageName
        }
        fun verifyFocus(displayId: Int,expectedPackage: String?) {
            requireTool(expectedPackage!=null && expectedPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")),message="Verified target package required for physical controller routing")
            val focus=focusedTarget()
            requireTool(focus.first==displayId && focus.second==expectedPackage,Code.TARGET_CHANGED,"Physical controller focus is not the authorized upper app; no focus-lock change was made")
        }
    }
}
