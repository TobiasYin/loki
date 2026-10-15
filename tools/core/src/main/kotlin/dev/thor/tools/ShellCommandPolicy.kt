package dev.thor.tools

/** Shared by the product host, Android backend and the shell-UID process.
 * This is an argv allowlist, not a filter in front of sh -c. Unknown forms fail closed.
 */
object ShellCommandPolicy {
    const val VERSION = "4"
    enum class Tier { WHITE, GRAY, BLACK }
    data class Decision(val tier: Tier, val reason: String, val argv: List<String> = emptyList()) {
        fun requireAllowed(approvalHash: String? = null, command: String? = null): List<String> {
            requireTool(tier != Tier.BLACK, Code.COMMAND_BLOCKED, reason)
            if(tier == Tier.GRAY) requireTool(command != null && approvalHash == hash(command.toByteArray()), Code.APPROVAL_REQUIRED, "灰名单命令需要用户在页面确认本次具体操作：$reason")
            return argv
        }
        fun data(): Map<String, Any?> = mapOf("policyVersion" to VERSION,"policyTier" to tier.name.lowercase(),"reason" to reason,"requiresApproval" to (tier==Tier.GRAY),"allowed" to (tier!=Tier.BLACK))
    }
    private val publicProperties = setOf("ro.product.model","ro.product.manufacturer","ro.product.brand","ro.product.device","ro.product.name","ro.build.display.id","ro.build.version.release","ro.build.version.sdk","ro.build.version.security_patch")
    private val publicSettings = setOf("screen_brightness","screen_brightness_float","screen_brightness_mode","screen_off_timeout","accelerometer_rotation","user_rotation","dual_screen_brightness_level","dual_screen_display_mode","min_refresh_rate","peak_refresh_rate","secondary_screen_volume_level","performance_mode","fan_mode","fan_speed","screen_focus_lock","enable_system_key_focus_lock","trigger_input_mode","temp_abxy_layout_mode","flip_button_layout","global_gamepad_to_mouse_mode","joystick_light_enabled","led_light_brightness_percent","percent_80_charge_limit","is_charging_separation","display_pwm_value")
    private val dangerous = setOf("rm","rmdir","dd","mkfs","mke2fs","format","fastboot","flash_erase","mount","umount","parted","fdisk","sgdisk","bootctl","avbctl","fsck","resize2fs","recovery","reboot","wipe","su","sudo")
    private fun blocked(reason: String) = Decision(Tier.BLACK,reason)
    fun classify(command: String): Decision {
        if(command.isBlank() || command.toByteArray().size>8192) return blocked("命令为空或超过长度限制")
        // Disallow expansions/operators even inside quotes. No escapes, response files,
        // pipelines, redirection, environments, interpreters or host-side transports.
        if(command.any { it.isISOControl() || it in "\u007f$`\\;|&<>(){}[]*?!" })return blocked("禁止脚本、控制字符、转义、变量展开、重定向和命令组合")
        val words=mutableListOf<String>();val word=StringBuilder();var quote: Char?=null;var started=false
        for(c in command) {
            when {
                quote!=null -> if(c==quote)quote=null else word.append(c)
                c=='\'' || c=='\"' -> {quote=c;started=true}
                c==' ' -> if(started) {words.add(word.toString());word.setLength(0);started=false}
                else -> {word.append(c);started=true}
            }
        }
        if(quote!=null)return blocked("引号未闭合")
        if(started)words.add(word.toString())
        if(words.take(2)==listOf("adb","shell"))repeat(2){words.removeAt(0)}
        if(words.isEmpty() || words.any {it.isEmpty()})return blocked("需要一条可解析的设备命令")
        val exe=words.first().removePrefix("/system/bin/")
        if('/' in exe || exe.startsWith('-'))return blocked("只允许经过审查的系统可执行文件")
        if(exe in dangerous || exe.startsWith("mkfs."))return blocked("禁止抹除、格式化、刷写、分区、重启恢复模式和提权操作")
        val a=words.drop(1)
        fun decision(tier: Tier, reason: String)=Decision(tier,reason,listOf("/system/bin/$exe")+a)
        fun white(reason: String)=decision(Tier.WHITE,reason)
        fun gray(reason: String)=decision(Tier.GRAY,reason)
        when(exe) {
            "id" -> if(a.isEmpty() || a in listOf(listOf("-u"),listOf("-g")))return white("读取执行身份")
            "whoami" -> if(a.isEmpty())return white("读取执行身份")
            "uname" -> if(a.isEmpty() || a.size==1 && a[0] in setOf("-a","-r","-m","-s"))return white("读取系统版本")
            "date" -> if(a.isEmpty() || a==listOf("-u"))return white("读取时间")
            "getprop" -> {
                if(a.size==1 && a[0] in publicProperties)return white("读取公开设备型号或系统版本")
                if(a.isEmpty() || a.size==1 && a[0].matches(Regex("[a-zA-Z0-9_.-]{1,128}")))return gray("读取系统属性，可能包含设备标识；请确认读取范围")
            }
            "settings" -> {
                if(a.size==3 && a[0]=="get" && a[1] in setOf("system","secure","global") && a[2].matches(Regex("[a-zA-Z0-9_]{1,128}"))) {
                    if(a[1]=="system" && a[2] in publicSettings || a[1]=="global" && a[2]=="airplane_mode_on")return white("读取显示或设备状态")
                    return gray("读取系统设置，可能包含隐私信息；请确认具体设置项")
                }
                if(a.size==4 && a.take(2)==listOf("put","system")) {
                    val n=a[3].takeIf {it.matches(Regex("[0-9]{1,7}"))}?.toIntOrNull()
                    val valid=when(a[2]) {
                        "screen_brightness" -> n!=null && n in 0..255
                        "screen_brightness_mode","accelerometer_rotation" -> n!=null && n in 0..1
                        "user_rotation" -> n!=null && n in 0..3
                        "screen_off_timeout" -> n!=null && n in 15000..1800000
                        "fan_mode" -> n in setOf(1,4,5)
                        "min_refresh_rate","peak_refresh_rate" -> a[3] in setOf("60","60.0","120","120.0")
                        "screen_focus_lock","enable_system_key_focus_lock","flip_button_layout","global_gamepad_to_mouse_mode" -> n!=null && n in 0..1
                        else -> false
                    }
                    if(valid) {
                        val reason=if(a[2]=="fan_mode")"切换 Thor 风扇模式为 ${a[3]}（1 低速/4 智能/5 高速）；需要回读转速并按任务恢复" else "修改限定范围的设备设置 ${a[2]} 为 ${a[3]}；需要回读验证并按任务恢复"
                        return if(a[2] in setOf("screen_focus_lock","enable_system_key_focus_lock","flip_button_layout","global_gamepad_to_mouse_mode"))gray(reason) else white(reason)
                    }
                }
                return blocked("禁止未审查的设置写入、删除、重置、权限或连接配置变更")
            }
            "cmd" -> {
                if(a==listOf("display","help") || a==listOf("display","get-displays"))return white("读取显示服务信息")
                if(a.size==3 && a.take(2)==listOf("display","set-brightness")) {
                    val value=a[2].takeIf {it.matches(Regex("(?:0(?:\\.[0-9]{1,8})?|1(?:\\.0{1,8})?)"))}?.toDoubleOrNull()
                    if(value!=null && value in 0.0..1.0)return white("修改主屏亮度为 $value（0–1）；需要回读验证并按任务恢复")
                }
            }
            "wm" -> if(a==listOf("size") || a==listOf("density"))return white("读取屏幕尺寸或密度")
            "dumpsys" -> {
                if(a==listOf("battery"))return white("读取电池状态")
                if(a in listOf(listOf("display"),listOf("power"),listOf("window","windows"),listOf("activity","activities")))return gray("读取诊断信息，可能包含应用、窗口或使用状态")
            }
            "cat" -> if(a.size==1 && (a[0] in setOf("/proc/meminfo","/proc/cpuinfo","/proc/uptime","/sys/class/power_supply/battery/capacity","/sys/class/gpio5_pwm2/speed","/sys/class/gpio5_pwm2/duty","/sys/class/gpio5_pwm2/period","/sys/class/gpio5_pwm2/state","/sys/class/gpio5_pwm2/thermal") || a[0].matches(Regex("/sys/class/backlight/panel[01]-backlight/(?:brightness|max_brightness)"))))return white("读取公开硬件状态")
            "pm" -> {
                if(a.size==4 && a.take(3)==listOf("list","packages","--show-versioncode") && a[3].matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")))return white("核验指定应用的包名和版本")
                if(a.size==2 && a[0]=="path" && a[1].matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")))return white("核验指定应用的安装路径")
                if(a==listOf("list","packages") || a==listOf("list","packages","-3") || a.size==2 && a[0]=="path" && a[1].matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")))return gray("读取已安装应用信息")
                return blocked("禁止应用清除、卸载、安装、授权和其他未审查的包管理操作")
            }
        }
        return blocked("该可执行文件或参数形式尚未审查，拒绝执行；用户确认不能绕过策略")
    }
    fun describe(): Map<String,Any?> = mapOf("policyVersion" to VERSION,
        "white" to listOf("id","getprop ro.product.model","settings get system screen_brightness","wm size","dumpsys battery","cmd display get-displays","cmd display set-brightness 0.5","settings put system fan_mode 5","settings put system peak_refresh_rate 60.0","settings put system screen_off_timeout 60000"),
        "gray" to listOf("apps.install：已下载 APK 的本次 UI 确认（不可通过 debug.shell 安装）","settings put system screen_focus_lock 1","settings put system flip_button_layout 1","dumpsys display","pm list packages -3"),
        "black" to listOf("抹除/格式化/刷机/分区/提权/重启","应用数据清除或卸载","权限和连接配置写入","脚本、管道、重定向、复合命令、未知命令或参数"),
        "thorBrightness" to "debug.thor_brightness: read and set upper/lower value 0..255 are white. Stored dual_screen_brightness_level is percent only, not an effective backlight command.",
        "execution" to "Native 与 shell UID 进程共同检查；直接执行已审核 argv，不调用 sh -c；灰名单逐条 UI 确认，黑名单不可放行")
}
