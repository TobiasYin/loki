package dev.thor.agent

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var host: ProductHost
    private var bridge: dev.thor.bridge.android.TrustedWebViewBridge? = null
    private var pickerSession: String? = null
    private var pickerKind="image"
    private var microphonePermissionPending=false
    private var voice:VoiceInput?=null
    private var voiceId:String?=null
    private val origin = BridgePolicy.ORIGIN
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerSession=savedInstanceState?.getString("pickerSession")
        pickerKind=savedInstanceState?.getString("pickerKind") ?: "image"
        web = WebView(this); web.id = android.R.id.custom
        val container = FrameLayout(this).apply { addView(web, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(container)
        container.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        web.settings.textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(75, 200)
        WebView.setWebContentsDebuggingEnabled(false)
        try { host = ProductHost.create(this) { state -> runOnUiThread {
            if (!isFinishing && !isDestroyed) web.evaluateJavascript("window.receiveProductState?.(JSON.parse(${JSONObject.quote(state.toString())}))", null)
        } }
        } catch(error:Exception) {if(BuildConfig.DEBUG)android.util.Log.e("ThorInit",error.javaClass.simpleName+"\n"+error.stackTrace.joinToString("\n"));setContentView(LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;addView(TextView(this@MainActivity).apply {text="本地存储或安全配置初始化失败；历史保留，未执行网络或设备操作。"});addView(Button(this@MainActivity).apply {text="重试打开";setOnClickListener {recreate()}})});return}
        val transport = dev.thor.bridge.HttpTransport(host.authority, host.policy)
        val toolGateway = dev.thor.bridge.ToolGateway(host.authority, emptyList(), { emptyMap() })
        bridge = dev.thor.bridge.android.TrustedWebViewBridge(web, host.authority, transport, toolGateway,
            { c -> host.bootstrap(c) }, "$origin/assets/chat/index.html", { c, request ->
                if(request.optString("op").startsWith("voice.")) {
                    runOnUiThread { handleVoice(request.optString("op"),request.optJSONObject("data") ?: JSONObject()) }
                    java.util.concurrent.CompletableFuture.completedFuture(JSONObject())
                } else if(request.optString("op") == "voiceSettings") {
                    runOnUiThread {showVoiceSettings(this)};java.util.concurrent.CompletableFuture.completedFuture(JSONObject())
                } else if(request.optString("op") == "skillImport") {
                    runOnUiThread {runCatching {pickerSession=host.requestImagePicker();pickerKind="skill";startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(android.content.Intent.EXTRA_MIME_TYPES,arrayOf("text/markdown","text/plain","application/zip","application/octet-stream")),72)}.onFailure {pickerSession=null;Toast.makeText(this,"请先停止活动运行再导入 Skill",Toast.LENGTH_LONG).show()}}
                    java.util.concurrent.CompletableFuture.completedFuture(JSONObject())
                } else if(request.optString("op") == "pickImage") {
                    runOnUiThread {
                        runCatching { pickerSession=host.requestImagePicker();pickerKind="image";startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("image/*"),71) }
                            .onFailure { Toast.makeText(this,"请先停止活动运行再附图",Toast.LENGTH_LONG).show() }
                    }; java.util.concurrent.CompletableFuture.completedFuture(JSONObject())
                } else if(request.optString("op") == "settings") {
                    runOnUiThread { showSettings() }; java.util.concurrent.CompletableFuture.completedFuture(JSONObject())
                } else host.rpc(c, request)
            }, { host.interrupt("页面/renderer 连接失效；不重放") }, host::auditHttp, {
            val panel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;addView(TextView(this@MainActivity).apply {text="聊天渲染进程已退出；当前运行已中断，不会重放。"});addView(Button(this@MainActivity).apply {text="重新打开聊天";setOnClickListener {recreate()}})}
            setContentView(panel)
        })
        if(bridge!!.supported) bridge!!.load() else setContentView(TextView(this).apply { text = "请更新 Android System WebView：安全消息桥不可用。" })
    }
    override fun onPause() {
        super.onPause()
        voice?.close();voice=null;voiceId=null
        // Android may create the next Activity before destroying this one. Release the single writer before that handoff.
        if(pickerSession == null && !microphonePermissionPending) {bridge?.close();if(::host.isInitialized)host.close()}
    }
    override fun onStop() {
        super.onStop()
        bridge?.close(); if(::host.isInitialized)host.interrupt("应用离开前台；不重放任何动作")
    }
    override fun onRestart() { super.onRestart(); if(pickerSession == null && !microphonePermissionPending)recreate() }
    override fun onDestroy() {
        bridge?.close(); if(::host.isInitialized)host.close()
        if(::web.isInitialized) { (web.parent as? android.view.ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
    }
    override fun onSaveInstanceState(out: Bundle) {super.onSaveInstanceState(out);out.putString("pickerSession",pickerSession);out.putString("pickerKind",pickerKind)}
    @Deprecated("System document picker result")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==72) {
            val uri=data?.data
            if(resultCode==RESULT_OK && uri!=null)host.importSkill(uri).whenComplete {_,error->runOnUiThread {pickerSession=null;Toast.makeText(this,if(error==null)"Skill 已导入，可在管理页面查看" else "导入失败：检查 Skill 格式、大小和重名",Toast.LENGTH_LONG).show();recreate()}}
            else {pickerSession=null;recreate()}
        }
        if(requestCode == 71) {
            val sid=pickerSession;val uri=data?.data
            if(resultCode == RESULT_OK && uri != null && sid != null)host.addUserImage(sid,uri).whenComplete {_,error ->runOnUiThread {pickerSession=null;Toast.makeText(this,if(error==null)"图片已保存在本会话，随下一条消息发送" else "图片导入失败：检查格式/大小或存储",Toast.LENGTH_LONG).show();recreate()} }
            else {pickerSession=null;recreate()}
        }
    }
    private fun sendVoice(state:JSONObject,id:String?,sid:String?) {
        if(isDestroyed || isFinishing)return
        state.put("id",id).put("sessionId",sid)
        web.evaluateJavascript("window.receiveVoiceState?.(JSON.parse(${JSONObject.quote(state.toString())}))",null)
    }
    private fun handleVoice(op:String,data:JSONObject) {
        val id=data.optString("id");val sid=data.optString("sessionId")
        when(op) {
            "voice.permission" -> if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
                microphonePermissionPending=true;requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO),73)
            }
            "voice.cancel" -> if(id==voiceId) {voice?.close();voice=null;voiceId=null}
            "voice.finish" -> if(id==voiceId)voice?.finish()
            "voice.start" -> {
                try {
                    require(id.length in 1..100);host.requestVoiceSession(sid)
                    require(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)
                    voice?.close();voiceId=id
                    val prefs=getSharedPreferences("voice",MODE_PRIVATE);val vault=CredentialVault(this)
                    val config=VoiceConfig(if(prefs.getInt("auth",0)==0)vault.readNative("voice.api") else null,prefs.getString("appId",null),if(prefs.getInt("auth",0)==1)vault.readNative("voice.access") else null,prefs.getString("resource","volc.bigasr.sauc.duration")!!)
                    voice=VoiceInput(config,{MicrophoneInput(this)},{event->runOnUiThread {if(voiceId==id)sendVoice(event,id,sid)}})
                    voice!!.start()
                } catch(_:Exception) {voiceId=null;val failed=voice;voice=null;failed?.close();sendVoice(obj("phase" to "error","error" to "语音不可用：请在语音设置配置火山凭据，并允许麦克风权限；活动回答需先停止。"),id,sid)}
            }
        }
    }
    @Deprecated("Android permission callback")
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==73) {microphonePermissionPending=false;Toast.makeText(this,if(grantResults.firstOrNull()==android.content.pm.PackageManager.PERMISSION_GRANTED)"麦克风已允许，请长按开始说话" else "麦克风未允许，可继续文字输入或在系统设置开启",Toast.LENGTH_LONG).show()}
    }
    private fun showSettings() {
        val prefs = getSharedPreferences("profiles", MODE_PRIVATE); val vault = CredentialVault(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 20, 32, 20) }
        fun label(text: String) { content.addView(TextView(this).apply { this.text = text; setPadding(0,16,0,8) }) }
        fun field(hint: String, name: String, secret: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint; setSingleLine(true)
            inputType = if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            if(!secret) setText(prefs.getString(name, ""))
            if(secret) { importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS; setSaveEnabled(false) }
            content.addView(this)
        }
        label("连接资料 · Native 设置\n保存后需要重新测试模型连接；文字、图片与工具测试通过后即可发送。搜索需单独配置。密钥不会回填聊天页面。")
        val name = field("模型显示名", "name")
        val protocol = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("OpenAI Responses", "Anthropic Messages")); setSelection(prefs.getInt("protocol", 0)); content.addView(this) }
        val url = field("模型基础 URL（HTTPS）", "url"); val model = field("模型 ID", "model")
        val contextBudget = field("本地上下文预算（不是模型能力声明；默认 32768）", "contextBudgetText")
        val maxOutput = field("最大输出预算（默认 2048；按服务实际限制填写）", "maxOutputText")
        val headerName = field("Native 凭据头名称（留空使用协议默认）", "headerName")
        val headerTemplate = field("Native 模板，如 Bearer {{credential}}（留空默认）", "headerTemplate")
        label("模型 API Key：${vault.status("model")}；留空保留，勾选后删除")
        val modelKey = field("替换模型 Key（仅 Native）", "", true)
        val removeModel = CheckBox(this).apply { text = "删除已保存模型 Key"; content.addView(this) }
        label("搜索连接资料：Brave；密钥独立绑定端点")
        val backend = field("搜索后端：当前支持 Brave", "searchBackend"); val searchUrl = field("搜索 URL：完整 /res/v1/web/search（HTTPS）", "searchUrl")
        label("搜索 Key：${vault.status("search")}")
        val searchKey = field("替换搜索 Key（仅 Native）", "", true)
        val removeSearch = CheckBox(this).apply { text = "删除已保存搜索 Key"; content.addView(this) }
        val test = Button(this).apply { text = "测试当前已保存模型：图片输入与工具回传"; content.addView(this) }
        test.setOnClickListener {
            try {
                val token = host.requestProbe(); val sid = host.selectedSession()
                web.evaluateJavascript("window.runNativeProbe(${JSONObject.quote(token)}, ${JSONObject.quote(sid)})", null)
                Toast.makeText(this, "已开始真实配置探测；请返回聊天观察结果", Toast.LENGTH_LONG).show()
            } catch (_: Exception) { Toast.makeText(this, "请先保存有效配置，且停止活动运行", Toast.LENGTH_LONG).show() }
        }
        content.addView(Button(this).apply {text="实时语音输入设置 · 火山流式识别";setOnClickListener {showVoiceSettings(this@MainActivity)}})
        val facts = dev.thor.tools.diagnostic.displayFacts(this)
        val upper = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, facts.map { "目标上屏 ${it.id}: ${it.width}×${it.height}" }); content.addView(this) }
        val lower = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, facts.map { "聊天下屏 ${it.id}: ${it.width}×${it.height}" }); content.addView(this); if(facts.size > 1) setSelection(1) }
        content.addView(Button(this).apply { text = "把聊天移动到所选下屏"; setOnClickListener { val target = facts[lower.selectedItemPosition].id; startActivity(android.content.Intent(this@MainActivity,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),android.app.ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle()) } })
        content.addView(Button(this).apply { text = "确认本次上下屏映射"; setOnClickListener { runCatching { host.calibrate(facts[upper.selectedItemPosition].id, facts[lower.selectedItemPosition].id) }.onSuccess { Toast.makeText(this@MainActivity, "映射已校准；只授权新运行", Toast.LENGTH_LONG).show() }.onFailure { Toast.makeText(this@MainActivity, "必须选择两个不同且存在的显示", Toast.LENGTH_LONG).show() } } })
        label("${host.accessibilitySetupStatus()}。已有 Shizuku shell 授权时自动开启本应用无障碍；会话能力开关仍由你选择。")
        content.addView(Button(this).apply { text = "打开系统无障碍设置（无 Shizuku 时手动开启）"; setOnClickListener { startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        content.addView(Button(this).apply { text = "请求/绑定 Shizuku（shell UID）"; setOnClickListener { runCatching { host.bindDebug() }.onFailure { Toast.makeText(this@MainActivity, "Shizuku 尚未就绪或没有 shell 授权", Toast.LENGTH_LONG).show() } } })
        label("设备状态：确认上下屏映射后使用屏幕工具；手柄控制暂不可用。\nADB 调试开启后，Agent 可运行设备 Shell 并访问系统允许的文件。Shell 可能截图、操作屏幕或联网，因此其他能力开关无法限制 Shell 的这些行为。部分应用私有文件仍可能无法访问。")
        val dialog = AlertDialog.Builder(this).setTitle("设置").setView(ScrollView(this).apply { addView(content) }).setNegativeButton("返回", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    for(value in listOf(url.text.toString(), searchUrl.text.toString())) if(value.isNotBlank()) {
                        val uri = URI(value); require((uri.scheme == "https" || BuildConfig.DEBUG && uri.scheme == "http" && uri.host == "127.0.0.1") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null) { "请输入无凭据、查询或片段的 HTTPS URL" }
                    }
                    require(modelKey.length() <= 8192 && searchKey.length() <= 8192)
                    require(headerName.text.isBlank() || headerName.text.toString().matches(Regex("[A-Za-z0-9-]+")))
                    require(headerTemplate.text.isBlank() || headerTemplate.text.toString().split("{{credential}}").size == 2 && !headerTemplate.text.contains('\r') && !headerTemplate.text.contains('\n'))
                    val budget = contextBudget.text.toString().ifBlank { "32768" }.toInt()
                    val outputBudget = maxOutput.text.toString().ifBlank { "2048" }.toInt()
                    require(budget in 4096..524288 && outputBudget in 16..16384 && outputBudget < budget)
                    val nextRevision = prefs.getInt("revision",0)+1
                    check(prefs.edit().putInt("revision",nextRevision).putString("probe","untested").remove("probeRevision").commit())
                    if(removeModel.isChecked) vault.remove("model") else if(modelKey.length() > 0) vault.put("model", modelKey.text.toString())
                    if(removeSearch.isChecked) vault.remove("search") else if(searchKey.length() > 0) vault.put("search", searchKey.text.toString())
                    check(prefs.edit().putString("name", name.text.toString().take(80)).putInt("protocol", protocol.selectedItemPosition)
                        .putString("url", url.text.toString()).putString("model", model.text.toString().take(200)).putString("searchBackend", backend.text.toString().take(80))
                        .putString("contextBudgetText",contextBudget.text.toString()).putString("maxOutputText",maxOutput.text.toString()).putInt("contextBudget",budget).putInt("maxOutput",outputBudget).putString("headerName", headerName.text.toString()).putString("headerTemplate", headerTemplate.text.toString()).putString("searchUrl", searchUrl.text.toString()).putInt("revision", nextRevision).putString("probe", "untested").commit())
                    modelKey.text.clear(); searchKey.text.clear(); dialog.dismiss(); recreate(); Toast.makeText(this, "已保存新修订；请重新打开设置运行实际探测", Toast.LENGTH_LONG).show()
                } catch(_: Exception) { Toast.makeText(this, "保存失败：检查 HTTPS 地址/输入长度或 Keystore 状态；不会向聊天暴露错误详情", Toast.LENGTH_LONG).show() }
            }
        }
        dialog.setOnDismissListener { modelKey.text.clear(); searchKey.text.clear() }
        dialog.show()
    }
}
