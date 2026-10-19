package dev.thor.tools.diagnostic

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import dev.thor.tools.*

/** PackageManager and a fixed launcher Intent only. No shell, arbitrary component,
 * data URI, extras, grants, task clearing, or package enumeration output. */
class AppLauncher(private val context:Context) {
    private val ownPackages get()=setOf(context.packageName,"com.tobias.loki")
    private fun intent(packageName:String?=null)=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {if(packageName!=null)setPackage(packageName)}
    @Suppress("DEPRECATION") private fun candidates(packageName:String?,cp:Checkpoint):List<ResolveInfo> {
        cp.check()
        val found=context.packageManager.queryIntentActivities(intent(packageName),0)
        requireTool(found.size<=4096,Code.LIMIT_EXCEEDED,"Launcher activity discovery budget exceeded")
        val eligible=found.filter {entry->
            cp.cancellation.check()
            entry.activityInfo?.let {it.exported && it.enabled && it.applicationInfo.enabled && it.packageName !in ownPackages && (packageName==null || it.packageName==packageName)
                && it.name.length in 1..512 && it.packageName.length<=255
                && (it.permission.isNullOrBlank() || context.checkSelfPermission(it.permission)==android.content.pm.PackageManager.PERMISSION_GRANTED)}==true
        }
        cp.check();return eligible
    }
    private fun facts(info:ResolveInfo):LaunchableApp {
        val a=info.activityInfo
        val label=info.loadLabel(context.packageManager)?.toString().orEmpty().filterNot(Char::isISOControl).take(160).ifBlank {a.packageName}
        return LaunchableApp(a.packageName,a.name,label)
    }
    fun find(args:Map<String,Any?>,cp:Checkpoint):Map<String,Any?> {
        val query=FindAppsQuery.parse(args)
        val eligible=candidates(null,cp).map {cp.cancellation.check();facts(it)}
        val matches=LauncherApps.find(eligible,query)
        cp.check()
        return mapOf("query" to query.query,"apps" to matches.map {mapOf("packageName" to it.packageName,"activityName" to it.activityName,"label" to it.label,"launchable" to true)},
            "limit" to query.limit,"truncated" to (eligible.count {LauncherApps.matches(it,query.query)}>matches.size),
            "scope" to "matching enabled exported MAIN/LAUNCHER activities visible to PackageManager; not a complete installed-package inventory")
    }
    fun launch(args:Map<String,Any?>,displayId:Int,cp:Checkpoint,role:String="upper",beforeSubmission:()->Unit):Map<String,Any?> {
        val command=LaunchAppCommand.parse(args,ownPackages)
        requireTool(displayId>=0 && role in setOf("upper","lower"),message="Resolved calibrated screen role/display required")
        val resolved=candidates(command.packageName,cp).map(::facts).distinctBy {it.activityName}.sortedBy {it.activityName}
        val chosen=resolved.firstOrNull() ?: fail(Code.UNSUPPORTED,"No enabled exported MAIN/LAUNCHER activity is visible for this package")
        val component=ComponentName(chosen.packageName,chosen.activityName)
        val launcher=intent(command.packageName).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options=ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        beforeSubmission()
        // Metadata IPC stays outside the authority commit lock. Android rechecks
        // component existence/export/permission at startActivity itself.
        requireTool(candidates(command.packageName,cp).any {it.activityInfo.name==chosen.activityName},Code.TARGET_CHANGED,"Launcher changed before submission")
        cp.effect {
            beforeSubmission()
            cp.check()
            cp.submitted() // A throwing Binder call can still have reached the activity manager.
            context.startActivity(launcher,options)
        }
        cp.check()
        return mapOf("packageName" to chosen.packageName,"activityName" to chosen.activityName,"label" to chosen.label,
            "displayId" to displayId,"displayRole" to role,"submitted" to true,"foregroundVerified" to false,
            "nextStep" to "Observe the selected screen app/window/UI or take a fresh screenshot to confirm launch and obtain a new target snapshot before input")
    }
}
