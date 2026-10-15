package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class ShellCommandPolicyTest {
    private fun expect(code: Code, block: () -> Unit) {
        try {block();fail("Expected $code")}catch(e: ToolFailure){assertEquals(code,e.code)}
    }
    @Test fun publicReadsRunWithoutApprovalAndHaveCanonicalExecutable() {
        for(command in listOf("id","id -u","getprop ro.product.model","settings get system screen_brightness","settings get system screen_brightness_float","cmd display get-displays","cmd display help","wm size","dumpsys battery","cat /proc/uptime","cmd display set-brightness 0.5","settings put system screen_brightness 128","settings put system peak_refresh_rate 120.0","settings put system screen_brightness_mode 0","settings put system screen_off_timeout 60000")) {
            val d=ShellCommandPolicy.classify(command)
            assertEquals(command,ShellCommandPolicy.Tier.WHITE,d.tier)
            assertTrue(d.requireAllowed().first().startsWith("/system/bin/"))
        }
    }
    @Test fun reviewedMutationsAndSensitiveReadsRequireExactApproval() {
        for(command in listOf("settings put system screen_focus_lock 1","settings put system flip_button_layout 1","dumpsys display","getprop","settings get secure android_id","pm list packages -3")) {
            val d=ShellCommandPolicy.classify(command)
            assertEquals(command,ShellCommandPolicy.Tier.GRAY,d.tier)
            expect(Code.APPROVAL_REQUIRED){d.requireAllowed(null,command)}
            expect(Code.APPROVAL_REQUIRED){d.requireAllowed(hash("other".toByteArray()),command)}
            assertTrue(d.requireAllowed(hash(command.toByteArray()),command).isNotEmpty())
        }
    }
    @Test fun destructiveCommandsCannotBeApproved() {
        for(command in listOf("rm -rf /data","dd if=/dev/zero of=/dev/block/by-name/userdata","mkfs.ext4 /dev/block/x","fastboot flash boot x.img","adb reboot bootloader","reboot recovery","recovery --wipe_data","pm clear dev.thor.agent","pm uninstall dev.thor.agent","cmd recovery wipe","settings reset system","settings put global adb_enabled 0","settings put secure enabled_accessibility_services null","su -c id")) {
            val d=ShellCommandPolicy.classify(command)
            assertEquals(command,ShellCommandPolicy.Tier.BLACK,d.tier)
            expect(Code.COMMAND_BLOCKED){d.requireAllowed(hash(command.toByteArray()),command)}
        }
    }
    @Test fun shellAndWrapperBypassesFailClosed() {
        for(command in listOf("id; rm -rf /data","id && id","id | sh","id > /data/x","id\nreboot","sh -c 'id'","env id","toybox id","busybox id","/data/local/tmp/id","/system/bin/../bin/id","adb -s serial shell id","adb shell 'id; reboot'","settings get system \$(id)","id `id`","i\\d","i\td","id # comment","\"id\"\u0000","settings get system screen_brightness &","id \u001b","settings get system screen_brightness\r")) {
            assertEquals(command,ShellCommandPolicy.Tier.BLACK,ShellCommandPolicy.classify(command).tier)
        }
    }
    @Test fun parserAcceptsOnlyEquivalentSafeQuotedForms() {
        for(command in listOf("adb shell id","/system/bin/id","  'id'  ","settings get system \"screen_brightness\"")) {
            val d=ShellCommandPolicy.classify(command)
            assertEquals(command,ShellCommandPolicy.Tier.WHITE,d.tier)
        }
        for(command in listOf("'id","id ''","adb shell",""," ","id "+"x".repeat(8193)))assertEquals(ShellCommandPolicy.Tier.BLACK,ShellCommandPolicy.classify(command).tier)
    }
    @Test fun argumentInjectionAndOutOfRangeValuesNeverGainApproval() {
        for(command in listOf("settings put system screen_brightness 256","settings put system screen_brightness -1","settings put system screen_brightness 128 extra","settings --user 0 put system screen_brightness 128","settings put system screen_off_timeout 0","cmd display set-brightness NaN","cmd display set-brightness Infinity","cmd display set-brightness 1.01","cmd display set-brightness -1","cmd display set-brightness 1e-1","getprop x value","dumpsys battery set level 50","date -s 1","wm size 100x100","cat /sdcard/private","cat /proc/uptime /data/key"))assertEquals(command,ShellCommandPolicy.Tier.BLACK,ShellCommandPolicy.classify(command).tier)
    }
    @Test fun directArgvDoesNotInterpretShellText() {
        val cancel=Cancellation();val shell=BoundedShell("/bin/sh",Clock {System.nanoTime()/1000000})
        val literal="\$(id); unexpected"
        val process=shell.startArgv(listOf("/bin/echo",literal),cancel){}
        val out=shell.collect(process,2000,1024,cancel){}
        assertEquals(0,out.exitCode);assertEquals(literal+"\n",out.stdout)
    }
    @Test fun thorHardwareReadsAndSafeFanModesAreBounded() {
        for(path in listOf("/sys/class/gpio5_pwm2/speed","/sys/class/gpio5_pwm2/duty","/sys/class/gpio5_pwm2/state","/sys/class/backlight/panel1-backlight/brightness"))assertEquals(ShellCommandPolicy.Tier.WHITE,ShellCommandPolicy.classify("cat $path").tier)
        for(mode in 1..4)assertEquals(ShellCommandPolicy.Tier.WHITE,ShellCommandPolicy.classify("settings put system fan_mode $mode").tier)
        for(command in listOf("settings put system fan_mode 0","settings put system fan_mode 5","settings put system fan_speed 0","cat /sys/class/gpio5_pwm2/speed extra","echo 0 > /sys/class/gpio5_pwm2/state","service call SettingsController 16386 i32 4 i32 80"))assertEquals(ShellCommandPolicy.Tier.BLACK,ShellCommandPolicy.classify(command).tier)
    }
}
