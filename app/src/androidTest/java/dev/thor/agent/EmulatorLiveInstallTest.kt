package dev.thor.agent

import android.app.ActivityOptions
import android.content.Intent
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.thor.tools.Status
import dev.thor.tools.diagnostic.AndroidBackend
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in real provider + official upstream release + actual UID 2000 install. Thor requires two explicit physical opt-ins and reuse of the saved profile. */
@RunWith(AndroidJUnit4::class)
class EmulatorLiveInstallTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun js(web:WebView,code:String):String {val done=CountDownLatch(1);var value="";ins.runOnMainSync {web.evaluateJavascript(code){value=it;done.countDown()}};assertTrue(done.await(5,TimeUnit.SECONDS));return value}
    private fun wait(web:WebView,code:String,seconds:Int=60) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong());while(System.nanoTime()<end){if(js(web,code)=="true")return;Thread.sleep(100)};fail("Timed out: $code")}
    @Test fun modelFindsOfficialReleaseDownloadsConfirmsInstallsAndVerifies() {
        val args=InstrumentationRegistry.getArguments()
        val physical=args.getString("physicalAcceptance")=="true" && args.getString("physicalLiveInstall")=="true"
        org.junit.Assume.assumeTrue(physical || args.getString("emulatorLiveInstall")=="true")
        ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val qemu=android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand("getprop ro.kernel.qemu")).bufferedReader().use {it.readText().trim()}
        if(physical) {assertEquals("AYN Thor",android.os.Build.MODEL);assertNotEquals("1",qemu);assertEquals("true",args.getString("reuseProfile"))} else assertEquals("Emulator only", "1",qemu)
        val reject=args.getString("rejectInstall")=="true"
        val prefs=context.getSharedPreferences("profiles",0)
        if(args.getString("reuseProfile")!="true") {
            val input=File(context.cacheDir,"emulator-live-credentials.json")
            val config=try {JSONObject(input.readText())}finally {assertTrue(input.delete())}
            CredentialVault(context).put("model",config.getString("modelKey"));config.remove("modelKey")
            assertTrue(prefs.edit().clear().putString("provider","custom").putString("name","Live OpenCode Go install acceptance")
                .putString("url","https://opencode.ai/zen/go/v1").putString("model","deepseek-v4.1-flash").putInt("protocol",0)
                .putInt("revision",1201).putBoolean("supportsImages",false).putInt("contextBudget",1000000).putInt("maxOutput",4096).putString("probe","untested").commit())
        }
        val intent=Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity=(if(physical)ins.startActivitySync(intent,ActivityOptions.makeBasic().setLaunchDisplayId(args.getString("chatDisplay")!!.toInt()).toBundle()) else ins.startActivitySync(intent)) as MainActivity
        ins.runOnMainSync {activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        val web=activity.findViewById<WebView>(android.R.id.custom)
        val host=MainActivity::class.java.getDeclaredField("host").apply {isAccessible=true}.get(activity) as ProductHost
        val backend=ProductHost::class.java.getDeclaredField("backend").apply {isAccessible=true}.get(host) as AndroidBackend
        val reportName=if(physical)"physical-live-install" else "emulator-live-install"
        val reader=SqliteStore(context,"product.db")
        fun rows(table:String,where:String="",values:Array<String>?=null):List<JSONObject> = reader.readableDatabase.rawQuery("SELECT payload FROM $table ${if(where.isBlank())"" else "WHERE $where"} ORDER BY rowid",values).use {c->buildList {while(c.moveToNext())add(JSONObject(c.getString(0)))}}
        val evidence=JSONObject().put("emulator",!physical).put("physical",physical).put("model","deepseek-v4.1-flash").put("repository","https://github.com/FossifyOrg/Calculator").put("approvalInteraction","instrumentation clicked actual product UI; not human approval")
        fun save() {File(context.filesDir,"$reportName.json").writeText(evidence.toString(2))}
        try {
            wait(web,"window.productReady===true && !document.getElementById('new').disabled")
            ins.runOnMainSync {host.bindDebug()}
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60)
            while(backend.availability("apps.install").status!=Status.READY && System.nanoTime()<end)Thread.sleep(100)
            assertEquals(Status.READY,backend.availability("apps.install").status)
            val n=js(web,"document.querySelectorAll('.session-row').length").toInt();js(web,"document.getElementById('new').click()")
            wait(web,"document.querySelectorAll('.session-row').length===${n+1}")
            js(web,"ThorUI.postMessage(JSON.stringify({op:'capability',sessionId:${JSONObject.quote(host.selectedSession())},capability:'debug',enabled:true}))")
            wait(web,"document.body.textContent.includes('ADB') || window.productReady===true")
            Thread.sleep(400)
            val prompt="请在当前${if(physical)"Thor 真机" else "模拟器"}完成一次真实的开源应用安装验收。目标只限官方仓库 https://github.com/FossifyOrg/Calculator 的 Fossify Calculator。先按需读取 adb-operations Skill 中的下载安装手册。用 web_request 请求 GitHub releases/latest API，根据实际 assets 选择最新稳定独立 APK（不要猜链接或版本），用 files_download 下载并在存在 digest 时传入官方 sha256。再调用 apps_install，等待页面的本次安装确认，最后必须调用 apps_package_info 核验实际 installed 和 versionCode 与下载 APK 一致。仅用这些工具完成全流程，不用其他应用来源，不执行卸载、清数据、授权、硬件设置或自行更新 Agent。如果用户拒绝安装，立即结束本次任务且不要重试或重新申请。用中文报告版本、包名、文件 SHA-256 和实际验证结果。"
            evidence.put("prompt",prompt);save()
            reader.readableDatabase
            val sid=host.selectedSession()
            val prior=rows("runs","sessionId=?",arrayOf(sid)).map {it.getString("id")}.toSet()
            js(web,"document.getElementById('input').value=${JSONObject.quote(prompt)};document.getElementById('composer').requestSubmit()")
            var approved=false;var lastSaved=0L;val submitted=System.nanoTime()
            while(true) {
                val current=rows("runs","sessionId=?",arrayOf(sid)).lastOrNull {it.getString("id") !in prior}
                if(current!=null) {
                    evidence.put("run",current)
                    val ev=rows("events","runId=?",arrayOf(current.getString("id")))
                    evidence.put("tools",JSONArray(ev.filter {it.getString("type") in setOf("tool_intent","tool_result","diagnostic")}.map {it}))
                    if(System.nanoTime()-lastSaved>TimeUnit.SECONDS.toNanos(5)){save();lastSaved=System.nanoTime()}
                    val approval=current.optJSONObject("approval")
                    if(approval?.optString("status")=="pending") {
                        assertFalse("Only one installation expected",approved)
                        assertTrue(approval.getString("summary").contains("org.fossify.math"));assertTrue(approval.getString("summary").contains("SHA-256"))
                        val before=android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand("pm path org.fossify.math")).bufferedReader().use {it.readText()}
                        evidence.put("packageBeforeApproval",before);save()
                        wait(web,"[...document.querySelectorAll('button')].some(b=>b.textContent==='批准此操作')",10)
                        js(web,"[...document.querySelectorAll('button')].find(b=>b.textContent===${JSONObject.quote(if(reject)"拒绝" else "批准此操作")}).click()")
                        approved=true;evidence.put("approvedViaProductUi",!reject).put("rejectedViaProductUi",reject);save()
                    }
                    if(current.getString("status") in setOf("completed","failed","cancelled","interrupted"))break
                }
                if(current==null && System.nanoTime()-submitted>TimeUnit.SECONDS.toNanos(40))error("UI did not submit installation task; no model request pending")
                Thread.sleep(750)
            }
            val run=rows("runs","sessionId=?",arrayOf(sid)).last()
            val results=rows("events","runId=?",arrayOf(run.getString("id"))).filter {it.getString("type")=="tool_result"}.map {it.getJSONObject("data")}
            evidence.put("run",run).put("results",JSONArray(results));save()
            if(reject) {
                val install=results.last {it.optString("toolId")=="apps.install"}
                assertEquals("PERMISSION_MISSING",install.getString("error"))
                val after=android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand("pm path org.fossify.math")).bufferedReader().use {it.readText()}
                assertEquals(evidence.getString("packageBeforeApproval"),after)
                evidence.put("packageAfterRejection",after).put("rejectionPreservedInstalledState",true).put("passed",true);save();return
            }
            assertEquals("completed",run.getString("status"));assertTrue("Install must await UI confirmation",approved)
            fun data(id:String):JSONObject {
                val event=results.last {it.optString("toolId")==id};assertTrue("$id failed",event.isNull("error"))
                return JSONObject(event.getJSONArray("content").objects().first {it.optString("type")=="text"}.getString("text"))
            }
            val request=data("web.request");assertEquals(200,request.getInt("status"));assertTrue(request.getString("url").startsWith("https://api.github.com/repos/FossifyOrg/Calculator/releases/"))
            val release=JSONObject(request.getString("text"));val downloaded=data("files.download")
            val asset=release.getJSONArray("assets").objects().single {it.getString("browser_download_url")==downloaded.getString("url")}
            assertEquals(asset.getLong("size"),downloaded.getLong("size"))
            if(!asset.isNull("digest"))assertEquals(asset.getString("digest").removePrefix("sha256:"),downloaded.getString("sha256"))
            val installed=data("apps.install");assertEquals(2000,installed.getInt("uid"));assertTrue(installed.getBoolean("installed"))
            val verified=data("apps.package_info");assertTrue(verified.getBoolean("installed"));assertEquals("org.fossify.math",verified.getString("packageName"))
            assertEquals(downloaded.getJSONObject("apk").getLong("versionCode").toString(),verified.getString("versionCode"))
            assertTrue(verified.getString("apkPath").startsWith("package:/data/app/"))
            evidence.put("release",release.getString("tag_name")).put("download",downloaded).put("installed",installed).put("verified",verified).put("passed",true);save()
            val bitmap=android.graphics.Bitmap.createBitmap(web.width,web.height,android.graphics.Bitmap.Config.ARGB_8888)
            ins.runOnMainSync {web.draw(android.graphics.Canvas(bitmap))};File(context.filesDir,"$reportName.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        } finally {save();reader.close();ins.runOnMainSync {activity.finish()};ins.waitForIdleSync()}
    }
}
