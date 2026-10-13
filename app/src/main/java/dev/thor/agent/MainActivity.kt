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
    private lateinit var host: Host
    private val origin = BridgePolicy.ORIGIN
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = Host.get(this)
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            setContentView(TextView(this).apply { text = "请更新 Android System WebView：当前版本缺少安全消息桥，模拟聊天无法启动。" }); return
        }
        web = WebView(this)
        web.id = android.R.id.custom
        val container = FrameLayout(this).apply { addView(web, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(container)
        container.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }; insets
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(75, 200)
        }
        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().setAcceptCookie(false)
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (request.url.scheme == "https" && request.url.host == "appassets.androidplatform.net" && request.url.path?.startsWith("/assets/chat/") == true) {
                    loader.shouldInterceptRequest(request.url)?.let { return it }
                }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream("Blocked".toByteArray()))
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                host.interrupt("WebView renderer 退出；不重放任何动作")
                host.detach(this@MainActivity); view.destroy()
                setContentView(TextView(this@MainActivity).apply { text = "聊天渲染进程退出。请重新打开应用；原运行已停止，不自动继续。" }); return true
            }
        }
        WebViewCompat.addWebMessageListener(web, "ThorNative", setOf(origin)) { _, message, sourceOrigin, mainFrame, _ ->
            val raw = message.data ?: return@addWebMessageListener
            if(!BridgePolicy.accepts(sourceOrigin.toString(), mainFrame, web.url, raw.length)) return@addWebMessageListener
            try {
                val command = JSONObject(raw)
                if(command.optString("op") == "settings") showSettings() else host.command(command)
            } catch(_: Exception) { /* Invalid messages never reach execution ports. */ }
        }
        host.attach(this) { state ->
            if(!isFinishing && !isDestroyed) web.evaluateJavascript("window.receiveNative(JSON.parse(${JSONObject.quote(state.toString())}))", null)
        }
        web.loadUrl("$origin/assets/chat/index.html")
    }
    override fun onStop() {
        super.onStop()
        if(!isChangingConfigurations && host.isAttached(this)) host.interrupt("应用离开前台；模拟运行停止，不自动恢复")
    }
    override fun onDestroy() {
        host.detach(this)
        if(::web.isInitialized) { (web.parent as? android.view.ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
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
        label("连接资料 · Native 设置\n当前仅模拟模式。保存不等于可用；端点测试/真实 HTTP/搜索执行尚未接入。密钥不会回填聊天页面。")
        val name = field("模型显示名", "name")
        val protocol = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("OpenAI Responses", "Anthropic Messages")); setSelection(prefs.getInt("protocol", 0)); content.addView(this) }
        val url = field("模型基础 URL（HTTPS）", "url"); val model = field("模型 ID", "model")
        label("模型 API Key：${vault.status("model")}；留空保留，勾选后删除")
        val modelKey = field("替换模型 Key（仅 Native）", "", true)
        val removeModel = CheckBox(this).apply { text = "删除已保存模型 Key"; content.addView(this) }
        label("搜索连接资料（尚未接入执行）")
        val backend = field("后端名称，如 Brave / SearXNG", "searchBackend"); val searchUrl = field("搜索 URL（HTTPS）", "searchUrl")
        label("搜索 Key：${vault.status("search")}")
        val searchKey = field("替换搜索 Key（仅 Native）", "", true)
        val removeSearch = CheckBox(this).apply { text = "删除已保存搜索 Key"; content.addView(this) }
        label("设备状态：无真实授权。下屏映射、无障碍、Shizuku、触摸和手柄待实机适配。\nADB 调试开启后，Agent 可运行设备 Shell 并访问系统允许的文件。Shell 可能截图、操作屏幕或联网，因此其他能力开关无法限制 Shell 的这些行为。部分应用私有文件仍可能无法访问。")
        val dialog = AlertDialog.Builder(this).setTitle("设置").setView(ScrollView(this).apply { addView(content) }).setNegativeButton("返回", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    for(value in listOf(url.text.toString(), searchUrl.text.toString())) if(value.isNotBlank()) {
                        val uri = URI(value); require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null) { "请输入无凭据、查询或片段的 HTTPS URL" }
                    }
                    require(modelKey.length() <= 8192 && searchKey.length() <= 8192)
                    if(removeModel.isChecked) vault.remove("model") else if(modelKey.length() > 0) vault.put("model", modelKey.text.toString())
                    if(removeSearch.isChecked) vault.remove("search") else if(searchKey.length() > 0) vault.put("search", searchKey.text.toString())
                    check(prefs.edit().putString("name", name.text.toString().take(80)).putInt("protocol", protocol.selectedItemPosition)
                        .putString("url", url.text.toString()).putString("model", model.text.toString().take(200)).putString("searchBackend", backend.text.toString().take(80))
                        .putString("searchUrl", searchUrl.text.toString()).putInt("revision", prefs.getInt("revision",0)+1).putString("probe", "untested").commit())
                    modelKey.text.clear(); searchKey.text.clear(); dialog.dismiss(); Toast.makeText(this, "已保存 Native 连接资料；真实服务尚未测试", Toast.LENGTH_LONG).show()
                } catch(_: Exception) { Toast.makeText(this, "保存失败：检查 HTTPS 地址/输入长度或 Keystore 状态；不会向聊天暴露错误详情", Toast.LENGTH_LONG).show() }
            }
        }
        dialog.setOnDismissListener { modelKey.text.clear(); searchKey.text.clear() }
        dialog.show()
    }
}
