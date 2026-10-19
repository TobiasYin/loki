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
    // Aliases retained for settings and acceptance tests; Service owns these.
    private var bridge: dev.thor.bridge.android.TrustedWebViewBridge? = null
    private var runtimeService:AgentRuntimeService?=null
    private var attachedEngine:AgentRuntimeEngine?=null
    private lateinit var container:FrameLayout
    private var serviceBound=false
    private var runtimeRecreationHold: RuntimeRecreationHold? = null
    private var chatStarted=false
    private val runtimeUi=object:AgentRuntimeEngine.Ui {
        override fun runtimeUiCommand(op:String,data:JSONObject)=handleRuntimeUi(op,data)
        override fun runtimeUnavailable(message:String)=showRuntimeError(message)
        override fun isChatVisible()=chatStarted && !isFinishing && !isDestroyed && window.decorView.windowVisibility==View.VISIBLE
        override fun moveToBackground()=if(isFinishing || isDestroyed)false else moveTaskToBack(true)
    }
    private val serviceConnection=object:android.content.ServiceConnection {
        override fun onServiceConnected(name:android.content.ComponentName,binder:android.os.IBinder) {
            if(isDestroyed || isFinishing)return
            val service=(binder as AgentRuntimeService.LocalBinder).service
            runtimeService=service
            runtimeRecreationHold?.close(); runtimeRecreationHold = null
            val screen=java.lang.ref.WeakReference(this@MainActivity)
            service.whenEngineReady {screen.get()?.let {if(it.runtimeService===service && !it.isDestroyed && !it.isFinishing)it.attachEngine()}}
        }
        override fun onServiceDisconnected(name:android.content.ComponentName) {
            attachedEngine=null;runtimeService=null
            if(!isDestroyed && !isFinishing)showRuntimeError("执行服务已退出；历史保留，运行不会自动重放。")
        }
    }
    private val backgroundStore by lazy { ChatBackgroundStore(this) }
    private lateinit var backgrounds: ChatBackgroundController
    private var backgroundPickerKey: String? = null
    private var backgroundPageKey: String? = null
    private var appearancePageVisible = false
    private var backgroundAppsPage: BackgroundAppsPage? = null
    private var backgroundPreview: ChatBackgroundPreview? = null
    private var backgroundAppsVisible = false
    private var backgroundAppsQuery = ""
    private var backgroundAppsFilter = BackgroundAppFilter.ALL
    private var pickerSession: String? = null
    private var pickerKind="image"
    private var pendingPickerResult:PendingPickerResult?=null
    private var settingsView:View?=null
    private var settingsScroll: ScrollView? = null
    private var settingsScrollKey: String? = null
    private val settingsScrollPositions = mutableMapOf<String, Int>()
    private val settingsBackCallback = if (android.os.Build.VERSION.SDK_INT >= 33)
        android.window.OnBackInvokedCallback { settingsBack() } else null
    private var onboardingStep:Int?=null
    private var refreshPermissionPage:(()->Unit)?=null
    private var settingsBack:()->Unit={closeSettings()}
    private var microphonePermissionPending=false
    private var voice:VoiceInput?=null
    private var voiceId:String?=null
    override fun attachBaseContext(newBase: android.content.Context) {
        val mode = Appearance.mode(newBase)
        val base = if (mode == ColorMode.SYSTEM) newBase else {
            val configuration = android.content.res.Configuration(newBase.resources.configuration)
            configuration.uiMode = (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (mode == ColorMode.DARK) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
            newBase.createConfigurationContext(configuration)
        }
        super.attachBaseContext(base)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = Appearance.palette(this).dark
        setTheme(if (dark) R.style.AppThemeDark else R.style.AppThemeLight)
        super.onCreate(savedInstanceState)
        runtimeRecreationHold = lastNonConfigurationInstance as? RuntimeRecreationHold
        savedInstanceState?.getBundle("settingsScrollPositions")?.let { positions ->
            positions.keySet().forEach { key -> settingsScrollPositions[key] = positions.getInt(key) }
        }
        appearancePageVisible = savedInstanceState?.getBoolean("appearancePageVisible", false) ?: false
        val bars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        @Suppress("DEPRECATION")
        window.statusBarColor = Appearance.palette(this).background
        @Suppress("DEPRECATION")
        window.navigationBarColor = Appearance.palette(this).background
        onboardingStep=savedInstanceState?.takeIf {it.containsKey("onboardingStep")}?.getInt("onboardingStep")
        pickerSession=savedInstanceState?.getString("pickerSession")
        pickerKind=savedInstanceState?.getString("pickerKind") ?: "image"
        pendingPickerResult=PendingPickerResult.restore(savedInstanceState)
        backgroundPickerKey = savedInstanceState?.getString("backgroundPickerKey")
        backgroundPageKey = savedInstanceState?.getString("backgroundPageKey")
        backgroundAppsVisible = savedInstanceState?.getBoolean("backgroundAppsVisible", false) ?: false
        backgroundAppsQuery = savedInstanceState?.getString("backgroundAppsQuery").orEmpty()
        backgroundAppsFilter = runCatching { BackgroundAppFilter.valueOf(savedInstanceState?.getString("backgroundAppsFilter").orEmpty()) }.getOrDefault(BackgroundAppFilter.ALL)
        backgrounds = ChatBackgroundController(this, backgroundStore, { if (::web.isInitialized) web else null }, { attachedEngine?.host?.backgroundUpperPackage() })
        container=ChatSurfaceLayout(this)
        container.addView(TextView(this).apply {text="正在打开聊天…";gravity=android.view.Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
        setContentView(container)
        // PhoneWindow needs its decor view installed before accessing the insets controller.
        window.insetsController?.setSystemBarsAppearance(if (dark) 0 else bars, bars)
        container.setOnApplyWindowInsetsListener {view,insets->
            val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        serviceBound=bindService(android.content.Intent(this,AgentRuntimeService::class.java),serviceConnection,BIND_AUTO_CREATE)
        if(!serviceBound)showRuntimeError("执行服务暂不可用；历史已保留，请重新打开聊天。")
    }
    private fun attachEngine() {
        val service=runtimeService ?: return
        val engine=service.engine ?: run {showRuntimeError(service.initializationError ?: "执行环境暂不可用。");return}
        attachedEngine?.detach(runtimeUi);attachedEngine=engine
        host=engine.host;bridge=engine.bridge
        rememberSettingsScroll(); unregisterSettingsBack(); settingsScroll=null; settingsScrollKey=null
        refreshPermissionPage=null;settingsView=null;container.removeAllViews();engine.attach(this,container,runtimeUi)
        web=engine.web // Display-only WebView; execution document stays in Service.
        backgrounds.attach(container)
        consumePendingPickerResult()
        if(onboardingStep!=null || !getSharedPreferences("onboarding",MODE_PRIVATE).getBoolean("completed",false))showOnboarding(onboardingStep ?: 0)
        else if (backgroundAppsVisible) chooseBackgroundApp()
        else if (appearancePageVisible) showAppearanceSettings()
        else backgroundPageKey?.let { showBackgroundSettings(it) }
    }
    private fun reloadRuntime() {
        val service=runtimeService ?: return
        val screen=java.lang.ref.WeakReference(this)
        if(!service.reloadConfiguration {screen.get()?.let {if(it.runtimeService===service && !it.isDestroyed && !it.isFinishing)it.attachEngine()}}) {
            Toast.makeText(this,"请等待活动任务结束后再重新加载设置",Toast.LENGTH_LONG).show();return
        }
        rememberSettingsScroll(); unregisterSettingsBack(); settingsScroll=null; settingsScrollKey=null
        attachedEngine=null;settingsView=null;container.removeAllViews()
        container.addView(TextView(this).apply {text="正在加载已保存的设置…";gravity=android.view.Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
    }
    private fun showRuntimeError(message:String) {
        if(isDestroyed || isFinishing)return
        container.removeAllViews()
        container.addView(LinearLayout(this).apply {orientation=LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {text=message})
            addView(Button(this@MainActivity).apply {text="重新打开聊天";setOnClickListener {reloadRuntime()}})
        },FrameLayout.LayoutParams(-1,-1))
    }
    private fun handleRuntimeUi(op:String,data:JSONObject) {
        if(isDestroyed || isFinishing)return
        if(op.startsWith("voice.")){handleVoice(op,data);return}
        when(op) {
            "openLink"->runCatching {
                val raw=data.optString("url");val uri=URI(raw)
                require(uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null && raw.length<=8192)
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,Uri.parse(raw)))
            }.onFailure {Toast.makeText(this,"无法打开此链接",Toast.LENGTH_SHORT).show()}
            "voiceSettings","settings"->showSettings()
            "skillImport"->runCatching {
                pickerSession=host.requestImagePicker();pickerKind="skill"
                startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(android.content.Intent.EXTRA_MIME_TYPES,arrayOf("text/markdown","text/plain","application/zip","application/octet-stream")),72)
            }.onFailure {pickerSession=null;Toast.makeText(this,"请先停止活动运行再导入 Skill",Toast.LENGTH_LONG).show()}
            "pickImage"->runCatching {
                pickerSession=host.requestImagePicker();pickerKind="image"
                startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("image/*"),71)
            }.onFailure {pickerSession=null;Toast.makeText(this,"请先停止活动运行再附图",Toast.LENGTH_LONG).show()}
        }
    }
    private fun offerNotificationPermission() {
        if(android.os.Build.VERSION.SDK_INT<33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),74)
    }
    private val accessibilityMonitor=android.os.Handler(android.os.Looper.getMainLooper())
    private var lastAccessibilityState:String?=null
    private val accessibilityNotice=dev.thor.tools.AccessibilityRecoveryNotice(dev.thor.tools.Clock {android.os.SystemClock.elapsedRealtime()})
    private var accessibilityReminder:AlertDialog?=null
    private val accessibilityCheck=object:Runnable {
        override fun run() {
            if(::host.isInitialized && !isDestroyed && !isFinishing) {
                val status=host.accessibilityStatus()
                val ready=status.status==dev.thor.tools.Status.READY
                val recovering=host.accessibilityRecoveryPending()
                val state="${status.status}:${status.reason}"
                if(lastAccessibilityState!=state) {
                    lastAccessibilityState=state
                    refreshPermissionPage?.invoke()
                }
                val showReminder=accessibilityNotice.update(ready,recovering,
                    dev.thor.tools.diagnostic.ThorAccessibilityService.settingEnabled(this@MainActivity))
                if(ready || recovering) {
                    accessibilityReminder?.dismiss();accessibilityReminder=null
                } else if(showReminder) {
                    accessibilityReminder=AlertDialog.Builder(this@MainActivity)
                        .setTitle("无障碍服务连接已失效")
                        .setMessage(if(host.accessibilityRestartRequired())host.accessibilitySetupStatus() else "${host.accessibilitySetupStatus()}。当前无法读取屏幕或执行操作，请在系统无障碍设置中关闭后重新开启 Loki 服务。")
                        .setPositiveButton(if(host.accessibilityRestartRequired())"打开应用设置" else "去重新开启") {_,_->
                            openSystemSettings(if(host.accessibilityRestartRequired())android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))
                                else android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        .setNegativeButton("稍后处理",null).show()
                }
            }
            accessibilityMonitor.postDelayed(this,1000)
        }
    }
    override fun onResume() {super.onResume();refreshPermissionPage?.invoke();accessibilityMonitor.post(accessibilityCheck)}
    override fun onStart() {super.onStart();backgrounds.start();chatStarted=true;attachedEngine?.uiVisibilityChanged(runtimeUi)}
    override fun onStop() {backgrounds.stop();chatStarted=false;super.onStop()}
    override fun onWindowFocusChanged(hasFocus:Boolean) {super.onWindowFocusChanged(hasFocus);attachedEngine?.uiVisibilityChanged(runtimeUi)}
    override fun onPause() {
        accessibilityMonitor.removeCallbacks(accessibilityCheck)
        accessibilityReminder?.dismiss();accessibilityReminder=null
        super.onPause();voice?.close();voice=null;voiceId=null
    }
    override fun onDestroy() {
        unregisterSettingsBack()
        backgroundPreview?.close(); backgroundPreview = null
        backgrounds.close(); backgroundAppsPage?.close()
        voice?.close();voice=null;voiceId=null
        attachedEngine?.detach(runtimeUi);attachedEngine=null
        if(serviceBound){unbindService(serviceConnection);serviceBound=false}
        runtimeService=null
        runtimeRecreationHold?.close(); runtimeRecreationHold = null
        super.onDestroy()
    }
    @Deprecated("Activity configuration handoff")
    override fun onRetainNonConfigurationInstance(): Any? {
        if (!serviceBound) return null
        // Keep one application-owned binding across the old/new Activity gap.
        // Idle services otherwise die on the final unbind and lose the live documents.
        return runtimeRecreationHold?.also { runtimeRecreationHold = null }
            ?: RuntimeRecreationHold(applicationContext)
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out);out.putString("pickerSession",pickerSession);out.putString("pickerKind",pickerKind)
        rememberSettingsScroll()
        out.putBundle("settingsScrollPositions", Bundle().apply {
            settingsScrollPositions.forEach { (key, position) -> putInt(key, position) }
        })
        out.putBoolean("backgroundAppsVisible", backgroundAppsVisible)
        out.putString("backgroundAppsQuery", backgroundAppsPage?.query ?: backgroundAppsQuery)
        out.putString("backgroundAppsFilter", (backgroundAppsPage?.filter ?: backgroundAppsFilter).name)
        out.putBoolean("appearancePageVisible", appearancePageVisible)
        out.putString("backgroundPickerKey", backgroundPickerKey);out.putString("backgroundPageKey", backgroundPageKey)
        pendingPickerResult?.save(out)
        onboardingStep?.let {out.putInt("onboardingStep",it)}
    }
    @Deprecated("System document picker result")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == 75) {
            val key = backgroundPickerKey; backgroundPickerKey = null
            if (resultCode == RESULT_OK && data?.data != null && key != null) {
                backgrounds.import(data.data!!, key) { error ->
                    if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    if (backgroundPageKey == key && ::web.isInitialized && attachedEngine != null) showBackgroundSettings(key)
                }
            }
            return
        }
        if(requestCode !in setOf(71,72))return
        val uri=data?.data
        if(resultCode!=RESULT_OK || uri==null || requestCode==71 && pickerSession==null) {
            pendingPickerResult=null;pickerSession=null;return
        }
        // Android can deliver this result before the recreated Activity receives
        // its asynchronous Service connection. Retain its original session.
        pendingPickerResult=PendingPickerResult(requestCode,uri.toString(),pickerSession)
        consumePendingPickerResult()
    }
    private fun consumePendingPickerResult() {
        if(isDestroyed || isFinishing)return
        val engine=attachedEngine ?: return
        if(engine.failed || runtimeService?.engine!==engine)return
        val pending=pendingPickerResult ?: return
        // Consume before dispatch. A second attach/recreation cannot import the
        // same result again, and an old callback cannot clear a newer picker.
        pendingPickerResult=null;pickerSession=null
        val uri=Uri.parse(pending.uri)
        val future=if(pending.requestCode==72)engine.host.importSkill(uri)
            else engine.host.addUserImage(requireNotNull(pending.sessionId),uri)
        future.whenComplete {_,error->runOnUiThread {
            if(!isDestroyed && !isFinishing && attachedEngine===engine) {
                val message=if(pending.requestCode==72) {
                    if(error==null)"Skill 已导入，可在管理页面查看" else "导入失败：检查 Skill 格式、大小和重名"
                } else if(error==null)"图片已保存在本会话，随下一条消息发送" else "图片导入失败：检查格式/大小或存储"
                Toast.makeText(this,message,Toast.LENGTH_LONG).show()
            }
        }}
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
                    val config=VoiceServices.resolve(this) ?: error("No voice credential")
                    // Capture audio immediately. Resolve a small, live scene on
                    // the host worker before the first ASR frame; slow
                    // or unavailable hints must not block ordinary voice input.
                    voice=VoiceInput(config,{MicrophoneInput(this)},{event->runOnUiThread {if(voiceId==id)sendVoice(event,id,sid)}},
                        recognitionContextProvider={
                            val contextFuture=host.requestVoiceRecognitionContext(sid)
                            try {contextFuture.get(250,java.util.concurrent.TimeUnit.MILLISECONDS)}
                            catch(_:Exception){contextFuture.cancel(false);null}
                        })
                    voice!!.start()
                } catch(_:Exception) {voiceId=null;val failed=voice;voice=null;failed?.close();sendVoice(obj("phase" to "error","error" to "语音不可用：请在语音设置配置火山凭据，并允许麦克风权限；活动回答需先停止。"),id,sid)}
            }
        }
    }
    @Deprecated("Android permission callback")
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode in setOf(73,74,75))refreshPermissionPage?.invoke()
        if(requestCode==73) {microphonePermissionPending=false;Toast.makeText(this,if(grantResults.firstOrNull()==android.content.pm.PackageManager.PERMISSION_GRANTED)"麦克风已允许，请长按开始说话" else "麦克风未允许，可继续文字输入或在系统设置开启",Toast.LENGTH_LONG).show()}
    }
    @Deprecated("Android back callback")
    override fun onBackPressed() {if(settingsView!=null)settingsBack() else super.onBackPressed()}
    private fun showAgentActionSettings() {
        val ui = SettingsUi(this)
        val content = ui.column()
        content.addView(ui.text("在 Agent 操作的屏幕上显示半透明圆形光标，点击时缩放，滑动时平滑移动并轻弹回位。", 14f, true))
        content.addView(Switch(this).apply {
            text = "显示 Agent 操作光标"
            isChecked = dev.thor.tools.diagnostic.AgentActionFeedback.enabled(this@MainActivity)
            setTextColor(ui.ink)
            setOnCheckedChangeListener { _, enabled ->
                dev.thor.tools.diagnostic.AgentActionFeedback.setEnabled(this@MainActivity, enabled)
            }
        })
        content.addView(ui.text("默认开启，即时保存。开启会为动作展示增加少量操作延迟；关闭后不再展示或等待光标动画。操作后的页面截图仍会自动返回给 Agent。", 13f, true))
        settingsPage("Agent 操作展示", content, back = { showSettings() })
    }

    private fun showAppearanceSettings() {
        val ui = SettingsUi(this)
        val content = ui.column()
        content.addView(ui.text("页面配色", 28f))
        content.addView(ui.text("聊天、设置、菜单和系统栏使用统一配色。图片与壁纸的颜色叠加层会一起变化。", 14f, true))
        ui.heading(content, "选择配色")
        val group = RadioGroup(this)
        ColorMode.entries.forEach { mode ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = mode.label; minHeight = ui.dp(56)
                setTextColor(ui.ink); buttonTintList = android.content.res.ColorStateList.valueOf(ui.accent)
                isChecked = Appearance.mode(this@MainActivity) == mode
                setOnClickListener {
                    if (Appearance.mode(this@MainActivity) != mode) {
                        Appearance.save(this@MainActivity, mode)
                        appearancePageVisible = true
                        recreate()
                    }
                }
            })
        }
        ui.card(content).addView(group)
        ui.heading(content, "背景显示")
        content.addView(ui.text("亮色使用轻薄的浅色叠加，暗色使用柔和的深色叠加，保留背景细节。消息和输入框另有衬底，保证文字清晰。", 14f, true))
        content.addView(ui.text("跟随系统会随系统深浅模式自动切换；手动选择亮色或暗色后，系统变化不会覆盖你的选择。", 13f, true))
        content.addView(ui.button("调整聊天背景") { showBackgroundSettings() })
        val previewSpec = backgrounds.effectiveSpec()
        showPreviewSettings("页面配色", content, previewSpec, "页面配色") { showSettings() }
        appearancePageVisible = true
    }
    /** Fixed preview, independently scrolling controls: both remain usable on Thor's lower display. */
    private fun showPreviewSettings(title: String, controls: LinearLayout, spec: BackgroundSpec, key: String, back: () -> Unit) {
        val ui = SettingsUi(this)
        val preview = ChatBackgroundPreview(this, backgroundStore)
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(10))
            addView(ui.text("虚拟聊天 · 实时预览", 13f, true).apply { setPadding(0, 0, 0, ui.dp(6)) })
            addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        controls.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(20))
        val scroller = ScrollView(this).apply { isFillViewport = true; addView(controls) }
        val wide = resources.configuration.screenWidthDp >= 520
        val body = LinearLayout(this).apply {
            tag = "settings-designed"
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (wide) {
                addView(pane, LinearLayout.LayoutParams(0, -1, .48f))
                addView(scroller, LinearLayout.LayoutParams(0, -1, .52f))
            } else {
                addView(pane, LinearLayout.LayoutParams(-1, ui.dp(220)))
                addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        settingsPage(title, body, scrollKey = key, back = back, scroll = false)
        backgroundPreview = preview
        backgrounds.preview(spec); preview.update(spec)
        settingsScroll = scroller; settingsScrollKey = key
        val position = settingsScrollPositions[key] ?: 0
        scroller.post { scroller.scrollTo(0, position) }
    }
    private fun showBackgroundSettings(key: String = ChatBackgroundStore.GLOBAL) {
        backgroundPageKey = key
        val ui = SettingsUi(this)
        val content = ui.column()
        val global = key == ChatBackgroundStore.GLOBAL
        fun title(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("已移除的应用")
        content.addView(ui.text(if (global) "聊天背景" else title(key), 28f))
        content.addView(ui.text(if (global) "全局背景；有专属设置的上屏应用会优先使用自己的背景。" else "切换到此上屏应用时自动生效，设置即时保存。", 13f, true))
        val spec = backgroundStore.get(key)
        fun save(next: BackgroundSpec) {
            backgroundStore.save(key, next)
            backgrounds.preview(next); backgroundPreview?.update(next)
        }
        ui.heading(content, "背景来源")
        val modes = listOf("default" to "纯色背景", "image" to "手动设置背景图片", "wallpaper" to "透传系统当前壁纸")
        val group = RadioGroup(this)
        modes.forEach { (mode, label) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = label; isChecked = spec.mode == mode; minHeight = ui.dp(48)
                setOnClickListener {
                    if (mode == "image" && backgroundStore.file(backgroundStore.get(key)) == null) {
                        pickBackgroundImage(key); showBackgroundSettings(key)
                    } else { save(backgroundStore.get(key).copy(mode = mode)); showBackgroundSettings(key) }
                }
            })
        }
        ui.card(content).addView(group)
        content.addView(ui.button(if (spec.image.isEmpty()) "选择背景图片" else "更换背景图片") { pickBackgroundImage(key) })
        if (spec.mode == "image") {
            content.addView(ui.text("图片已保存在应用内；透明度越低，底色越明显。", 13f, true))
        }
        ui.heading(content, "显示效果")
        content.addView(ui.text("颜色叠加层随页面配色自动变化，毛玻璃与不透明度保留当前设置。", 13f, true))
        if (spec.mode == "default") content.addView(ui.text("选择图片或系统壁纸后可调整显示效果。", 13f, true))
        fun slider(label: String, max: Int, value: Int, enabled: Boolean = spec.mode != "default", changed: (Int) -> Unit) {
            val card = ui.card(content)
            val text = ui.text("$label：$value${if (max == 100) "%" else ""}").apply { setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0) }
            card.addView(text)
            card.addView(SeekBar(this).apply {
                this.max = max; progress = value; contentDescription = label; minimumHeight = ui.dp(48)
                isEnabled = enabled
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        text.text = "$label：$progress${if (max == 100) "%" else ""}"
                        if (fromUser) changed(progress)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) {}
                })
            })
        }
        slider("毛玻璃程度", 30, spec.blur) { save(backgroundStore.get(key).copy(blur = it)) }
        slider("背景不透明度", 100, spec.opacity) { save(backgroundStore.get(key).copy(opacity = it)) }
        slider("卡片透明度", 100, 100 - spec.cardOpacity, enabled = true) { save(backgroundStore.get(key).copy(cardOpacity = 100 - it)) }
        content.addView(ui.text("0% 为实心卡片，100% 隐藏卡片底色；文字和按钮保持清晰。", 12f, true))
        if (spec.mode == "wallpaper") {
            val blurSupported = android.os.Build.VERSION.SDK_INT >= 31 && getSystemService(android.view.WindowManager::class.java).isCrossWindowBlurEnabled
            content.addView(ui.text(if (blurSupported) "透传会跟随系统壁纸，包括动态壁纸；效果取决于系统壁纸在此屏幕的支持。" else "透传会跟随系统壁纸；当前系统不支持窗口毛玻璃，壁纸将以原始清晰度显示。图片背景仍支持毛玻璃。", 13f, true))
        }
        content.addView(ui.button("查看聊天效果") { closeSettings() })
        if (global) {
            ui.heading(content, "上屏应用专属背景")
            content.addView(ui.text("按已校准的上屏和无障碍窗口识别应用。无法识别或无专属设置时使用全局背景。", 13f, true))
            val count = backgroundStore.all().keys.count { it != ChatBackgroundStore.GLOBAL }
            ui.row(ui.card(content), "上屏应用背景", "已配置 $count 个应用 · 搜索并管理", "▧") { chooseBackgroundApp() }
        } else {
            content.addView(ui.button("移除此应用设置，跟随全局") {
                backgroundStore.remove(key); backgrounds.refresh(); chooseBackgroundApp()
            })
        }
        showPreviewSettings(if (global) "聊天背景" else "应用背景", content, spec, "background:$key", back = {
            if (global) { backgroundPageKey = null; showSettings() } else chooseBackgroundApp()
        })
        // settingsPage closes the preceding page before attaching the new one.
        backgroundPageKey = key
    }
    private fun pickBackgroundImage(key: String) {
        backgroundPickerKey = key
        runCatching {
            startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("image/*"), 75)
        }.onFailure { backgroundPickerKey = null; Toast.makeText(this, "无法打开图片选择器", Toast.LENGTH_LONG).show() }
    }
    private fun chooseBackgroundApp() {
        // Capture before replacing the preceding page, including search and filter state.
        backgroundAppsPage?.let { backgroundAppsQuery = it.query; backgroundAppsFilter = it.filter }
        val page = BackgroundAppsPage(this, backgroundStore,
            { attachedEngine?.host?.backgroundUpperPackage() ?: java.util.concurrent.CompletableFuture.completedFuture(null) },
            { showBackgroundSettings(it) }, backgroundAppsQuery, backgroundAppsFilter)
        settingsPage("上屏应用背景", page.view, back = { showBackgroundSettings() }, scroll = false)
        backgroundAppsPage = page; backgroundAppsVisible = true
    }
    private fun rememberSettingsScroll() {
        val key = settingsScrollKey ?: return
        settingsScroll?.let { settingsScrollPositions[key] = it.scrollY }
    }
    private fun unregisterSettingsBack() {
        if (android.os.Build.VERSION.SDK_INT >= 33) settingsBackCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
    }
    private fun closeSettings() = removeSettings(preserveScroll = false)
    private fun removeSettings(preserveScroll: Boolean) {
        backgroundPreview?.close(); backgroundPreview = null
        if (::backgrounds.isInitialized) backgrounds.preview(null)
        backgroundAppsPage?.let { backgroundAppsQuery = it.query; backgroundAppsFilter = it.filter; it.close() }
        backgroundAppsPage=null; backgroundAppsVisible=false
        rememberSettingsScroll()
        unregisterSettingsBack()
        appearancePageVisible=false; backgroundPageKey=null; refreshPermissionPage=null
        if(::web.isInitialized)web.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        settingsView?.let {(it.parent as? android.view.ViewGroup)?.removeView(it)}
        settingsView=null; settingsScroll=null; settingsScrollKey=null
        if (!preserveScroll) settingsScrollPositions.clear()
    }
    private fun settingsPage(title:String,content:View,save:(()->Unit)?=null,scrollKey:String=title,back:()->Unit={closeSettings()},scroll:Boolean=true):LinearLayout {
        removeSettings(preserveScroll = true)
        val ui=SettingsUi(this)
        val page=LinearLayout(this).apply {tag="thor-settings-page";orientation=LinearLayout.VERTICAL;setBackgroundColor(ui.background)}
        val toolbar=LinearLayout(this).apply {gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(ui.dp(12),ui.dp(8),ui.dp(12),ui.dp(8))}
        toolbar.addView(ui.button("‹ 返回",back).apply {contentDescription="返回上一页"})
        toolbar.addView(ui.text(title,20f).apply {setPadding(ui.dp(12),0,0,0)},LinearLayout.LayoutParams(0,-2,1f))
        if(save!=null)toolbar.addView(ui.button("保存",save))
        ui.styleForm(content)
        val scrollView = if (scroll) ScrollView(this).apply { isFillViewport=true; addView(content) } else null
        page.addView(toolbar);page.addView(scrollView ?: content,LinearLayout.LayoutParams(-1,0,1f))
        settingsScroll = scrollView; settingsScrollKey = if (scroll) scrollKey else null
        val position = settingsScrollPositions[scrollKey] ?: 0
        scrollView?.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(view:View,left:Int,top:Int,right:Int,bottom:Int,oldLeft:Int,oldTop:Int,oldRight:Int,oldBottom:Int) {
                scrollView.removeOnLayoutChangeListener(this)
                scrollView.scrollTo(0, position)
            }
        })
        web.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        container.addView(page,FrameLayout.LayoutParams(-1,-1));settingsView=page;settingsBack=back
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, settingsBackCallback!!)
        }
        return page
    }
    private fun showModelProviders() {
        val store=ProviderStore(this)
        val ui=SettingsUi(this)
        val content=ui.column()
        content.addView(ui.text("选择你的 AI 服务",24f).apply {setPadding(ui.dp(6),ui.dp(12),0,ui.dp(6))})
        content.addView(ui.text("保存多套账号配置，选择一个作为新会话的默认服务。",14f,true).apply {setPadding(ui.dp(6),0,0,ui.dp(12))})
        if(store.list().isEmpty()) {
            val empty=SettingsForm(this)
            val body=empty.section(content,"连接第一个模型服务")
            body.addView(ui.text("推荐 DeepSeek 官方 API",18f))
            empty.note(body,"也可选择火山 AgentPlan，一个套餐 Key 用于模型、语音与搜索；支持 OpenCode Go 与自定义接口。")
        }
        for(config in store.list().sortedBy {if(it.getString("id")==store.defaultId())0 else 1}) {
            val id=config.getString("id")
            val row=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(ui.dp(18),ui.dp(16),ui.dp(18),ui.dp(14))}
            ui.card(content).addView(row)
            row.addView(ui.text(if(store.defaultId()==id)"当前默认" else if(config.getBoolean("enabled"))"可切换" else "已停用",12f,true).apply {setTextColor(ui.accent)})
            row.addView(ui.text(config.getString("name"),18f).apply {setPadding(0,ui.dp(5),0,ui.dp(4))})
            row.addView(ui.text("${config.getString("model")} · ${store.credentialStatus(id)}",13f,true))
            val buttons=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
            buttons.addView(Button(this).apply {text="编辑";tag="provider-edit-$id";setOnClickListener {showProviderEditor(config,config.getString("kind"))}})
            if(config.getBoolean("enabled") && store.defaultId()!=id)buttons.addView(Button(this).apply {text="使用此服务";tag="provider-activate-$id";setOnClickListener {
                isEnabled=false
                host.activateProvider(id).whenComplete {_,error->runOnUiThread {
                    if(error==null)showModelProviders() else {isEnabled=true;Toast.makeText(this@MainActivity,"请先编辑并保存此服务的 API Key",Toast.LENGTH_LONG).show()}
                }}
            }})
            buttons.addView(Button(this).apply {text="删除";setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("删除 ${config.getString("name")}？").setMessage("只会删除此服务配置和它的 API Key；其他服务与聊天记录会保留。")
                    .setNegativeButton("取消",null).setPositiveButton("删除") {_,_->if(changeProviderSettings {store.delete(id)})showModelProviders()}.show()
            }})
            for(index in 0 until buttons.childCount) {
                val button=buttons.getChildAt(index) as Button
                button.isAllCaps=false;button.setTextColor(ui.accent);button.backgroundTintList=null;button.background=ui.button("",{}).background;button.textSize=14f
                button.layoutParams=LinearLayout.LayoutParams(0,ui.dp(48),1f)
            }
            row.addView(buttons,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(10)})
        }
        val addProvider=ui.button("添加模型服务",{}).apply {tag="provider-add";isEnabled=store.list().size<ProviderStore.MAX_PROVIDERS;setOnClickListener {
            val kinds=listOf("opencode-go","deepseek",VolcAgentPlan.KIND,"custom")
            AlertDialog.Builder(this@MainActivity).setTitle("添加模型服务").setItems(kinds.map(::providerKindLabel).toTypedArray()) {_,index->showProviderEditor(null,kinds[index])}.show()
        }}
        content.addView(addProvider,2,LinearLayout.LayoutParams(-1,ui.dp(48)).apply {bottomMargin=ui.dp(12)})
        settingsPage("模型服务",content,back={showSettings()})
    }
    private fun providerKindLabel(kind:String)=when(kind){"opencode-go"->"OpenCode Go";"deepseek"->"DeepSeek 官方（推荐 · 模型 + 搜索）";VolcAgentPlan.KIND->"火山 AgentPlan（模型 + 语音 + 搜索）";else->"自定义 API Key"}
    private fun changeProviderSettings(action:()->Unit):Boolean = try {
        require(!host.hasActiveRuns()) {"请等待活动任务完成后再修改服务配置"}
        action()
        val prefs=getSharedPreferences("profiles",MODE_PRIVATE)
        check(prefs.edit().putInt("revision",prefs.getInt("revision",0)+1).commit())
        reloadRuntime();true
    }catch(_:Exception){Toast.makeText(this,"修改未完成：请等待活动任务结束，并检查服务配置",Toast.LENGTH_LONG).show();false}
    private fun showProviderEditor(saved:JSONObject?,kind:String) {
        val store=ProviderStore(this);val custom=kind=="custom"
        val config=saved ?: JSONObject().put("kind",kind).put("enabled",true).put("name",when(kind){"deepseek"->"DeepSeek 官方";"opencode-go"->"OpenCode Go";VolcAgentPlan.KIND->"火山 AgentPlan";else->"自定义服务"}).put("protocol",0)
            .put("url",when(kind){"opencode-go"->"https://opencode.ai/zen/go/v1";"deepseek"->DeepSeekCatalog.BASE_URL;VolcAgentPlan.KIND->VolcAgentPlan.BASE_URL;else->""})
            .put("model","").put("contextBudget",32768).put("maxOutput",2048).put("supportsImages",true)
        val id=saved?.getString("id")
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,16,24,24)}
        fun text(value:String)=TextView(this).apply {text=value;setPadding(0,12,0,8);content.addView(this)}
        fun field(hintValue:String,key:String,secret:Boolean=false)=EditText(this).apply {
            hint=hintValue;setSingleLine(true);inputType=if(secret)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            if(!secret)setText(config.optString(key)) else {importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;isSaveEnabled=false}
            content.addView(this)
        }
        text("服务类型：${providerKindLabel(kind)}")
        val name=field("服务名称（便于区分账号）","name")
        val enabled=CheckBox(this).apply {text="启用此服务";isChecked=config.optBoolean("enabled",true);content.addView(this)}
        val protocol=Spinner(this).apply {adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("OpenAI Responses","Anthropic Messages","OpenAI Chat Completions"));setSelection(config.optInt("protocol",0));content.addView(this);visibility=if(custom)View.VISIBLE else View.GONE}
        val url=field("模型基础 URL（HTTP / HTTPS）","url").apply {visibility=if(custom)View.VISIBLE else View.GONE}
        val model=field("模型 ID","model").apply {visibility=if(custom)View.VISIBLE else View.GONE}
        val contextBudget=field("上下文上限（tokens）","contextBudgetText").apply {if(text.isBlank())setText(config.optInt("contextBudget",32768).toString());inputType=InputType.TYPE_CLASS_NUMBER}
        val maxOutput=field("输出上限（tokens；留空跟随模型）","maxOutputText").apply {inputType=InputType.TYPE_CLASS_NUMBER}
        val outputHelp=text(if(custom)"输出上限留空使用 2048。" else "输出上限留空自动跟随所选模型，包含思考与正文。")
        val imageSupport=CheckBox(this).apply {text="支持图片输入";isChecked=config.optBoolean("supportsImages",true);content.addView(this)}
        val catalog=ModelCatalog(this);val deepseek=DeepSeekCatalog(this);val volc=VolcAgentPlanCatalog(this)
        fun officialModels()=when(kind){"deepseek"->deepseek.models();VolcAgentPlan.KIND->volc.models();else->catalog.models()}
        fun officialSource()=when(kind){"deepseek"->deepseek.source();VolcAgentPlan.KIND->volc.source();else->catalog.source()}
        val modelPicker=Spinner(this).apply {tag="model-picker";contentDescription="选择模型";visibility=if(custom)View.GONE else View.VISIBLE;content.addView(this)}
        val catalogStatus=text("").apply {tag="model-catalog-status";visibility=modelPicker.visibility}
        val refreshModels=Button(this).apply {text=when(kind){"deepseek"->"刷新 DeepSeek 官方模型列表";VolcAgentPlan.KIND->"发现 / 刷新火山官方模型目录";else->"刷新 OpenCode Go 模型列表"};visibility=modelPicker.visibility;content.addView(this)}
        val headerName=field("自定义凭据头（可选）","headerName")
        val headerTemplate=field("凭据格式，例如 Bearer {{credential}}（可选）","headerTemplate")
        text("API Key：${id?.let {store.credentialStatus(it)} ?: "未保存"}；留空保留此服务已保存的 Key")
        val key=field(if(id==null)"输入 API Key" else "输入新的 Key 以替换","",true).apply {tag="provider-api-key"}
        val removeKey=CheckBox(this).apply {text="删除此服务已保存的 API Key";content.addView(this)}
        fun draftKey():String {
            if(removeKey.isChecked)return ""
            if(key.text.isNotBlank())return key.text.toString()
            if(id!=null)require(saved.getString("url").trimEnd('/')==url.text.toString().trim().trimEnd('/')) {"修改服务地址后请重新填写 API Key"}
            return id?.let {store.credential(it)}.orEmpty()
        }
        var advancedExpanded=false
        val advanced=listOf(contextBudget,maxOutput,outputHelp,imageSupport,headerName,headerTemplate)
        fun renderAdvanced() {
            advanced.forEach {it.visibility=if(advancedExpanded && (custom || it !in listOf(headerName,headerTemplate)))View.VISIBLE else View.GONE}
            contextBudget.isEnabled=custom;imageSupport.isEnabled=custom
        }
        renderAdvanced()
        val advancedToggle=Button(this).apply {text="高级选项 ›";setOnClickListener {advancedExpanded=!advancedExpanded;renderAdvanced();text=if(advancedExpanded)"收起高级选项 ‹" else "高级选项 ›"}}
        content.addView(advancedToggle)
        fun metadata():JSONObject?=(if(custom)null else officialModels())?.objects()?.firstOrNull {it.getString("id")==model.text.toString() && it.getString("protocol")==ModelCatalog.protocols[protocol.selectedItemPosition]}
        fun updateModels() {
            if(custom)return
            val choices=officialModels().objects()
            modelPicker.onItemSelectedListener=null
            modelPicker.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,choices.map {it.getString("name")})
            fun applyChoice(index:Int) {
                val choice=choices[index];model.setText(choice.getString("id"));protocol.setSelection(ModelCatalog.protocols.indexOf(choice.getString("protocol")))
                contextBudget.setText(choice.getInt("contextWindow").toString());imageSupport.isChecked=choice.optJSONArray("input")?.let {a->(0 until a.length()).any {a.optString(it)=="image"}}==true
                catalogStatus.text="${officialSource()}\n上下文 ${choice.getInt("contextWindow")} · 输出容量 ${choice.getInt("maxOutput")} tokens\n思考强度 ${choice.getJSONArray("efforts").join(" / ")}"
            }
            val choice=choices.indexOfFirst {it.getString("id")==model.text.toString()}.coerceAtLeast(0)
            modelPicker.setSelection(choice);applyChoice(choice)
            modelPicker.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?)=Unit
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,itemId:Long)=applyChoice(position)
            }
        }
        updateModels()
        refreshModels.setOnClickListener {
            val credential=draftKey()
            if(kind=="deepseek" && credential.isBlank()){catalogStatus.text="请填写此服务的 DeepSeek API Key 后刷新";return@setOnClickListener}
            refreshModels.isEnabled=false;catalogStatus.text="正在读取模型资料…"
            Thread {val success=runCatching {when(kind){"deepseek"->deepseek.refresh(credential);VolcAgentPlan.KIND->volc.refresh();else->catalog.refresh()}}.isSuccess
                runOnUiThread {if(refreshModels.isAttachedToWindow){refreshModels.isEnabled=true;if(success)updateModels() else catalogStatus.text="暂时无法刷新，请检查此服务的 Key、网络或额度；保留缓存模型资料"}}
            }.start()
        }
        fun outputBudget()=OutputBudgetPolicy.resolve(kind,contextBudget.text.toString().toInt(),metadata()?.getInt("maxOutput"),maxOutput.text.toString(),rejectExcess=true)
        fun draft()=ModelConnectionTest.Draft(url.text.toString().trim(),ModelCatalog.protocols[protocol.selectedItemPosition],model.text.toString().trim(),draftKey(),if(custom)headerName.text.toString() else "",if(custom)headerTemplate.text.toString() else "",maxOutput=outputBudget().tokens).withProfile(metadata(),maxOutput.text.toString())
        val testStatus=text("测试只使用此服务当前填写的配置与 Key；草稿不会自动保存。").apply {tag="model-test-status"}
        val test=Button(this).apply {text="测试当前填写的连接";tag="model-test";content.addView(this)}
        test.setOnClickListener {
            val testDraft=runCatching {draft().also {it.validate()}}.getOrElse {testStatus.text="请检查此服务的地址、模型、输出预算和 API Key；草稿未保存";return@setOnClickListener}
            test.isEnabled=false;testStatus.text="正在测试当前填写的连接…"
            Thread {val result=ModelConnectionTest.run(testDraft);runOnUiThread {if(test.isAttachedToWindow){test.isEnabled=true;testStatus.text="${result.message} · ${result.elapsedMs} ms\n草稿未保存，点击保存后生效。"}}}.start()
        }
        val form=SettingsForm(this)
        val identity=form.section(form.rail,"服务配置")
        identity.addView(form.ui.text(when(kind){"deepseek"->"DeepSeek 官方";"opencode-go"->"OpenCode Go";VolcAgentPlan.KIND->"火山 AgentPlan";else->"自定义接口"},18f))
        if(kind=="deepseek")form.note(identity,"与联网搜索共享 Key。")
        if(kind==VolcAgentPlan.KIND)form.note(identity,"一个套餐 Key 用于文本、语音识别、语音合成与豆包搜索。使用此服务后语音自动接入；搜索请选择自动选择或豆包搜索。请在火山控制台开启豆包搜索抵扣。模型目录不代表当前档位已获权限。")
        form.field(identity,"服务名称",name);form.move(identity,enabled)
        val connection=form.section(form.main,if(custom)"接口与模型" else "选择模型")
        if(custom) {
            form.field(connection,"接口协议",protocol);form.field(connection,"API 基础地址",url);form.field(connection,"模型 ID",model)
        } else {
            form.move(connection,modelPicker)
            if(kind==VolcAgentPlan.KIND){form.move(connection,refreshModels);form.move(connection,catalogStatus)}
        }
        val credentials=form.section(form.main,"连接凭据")
        form.note(credentials,if(id==null)"填写此服务的 API Key，保存后即可使用。" else "${store.credentialStatus(id)} · 留空继续使用")
        form.move(credentials,key)
        val validation=form.section(form.main,"连接测试")
        form.move(validation,test);form.move(validation,testStatus)
        val options=form.section(form.rail,"更多设置")
        form.move(options,advancedToggle)
        val advancedBody=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        options.addView(advancedBody)
        form.field(advancedBody,"上下文上限",contextBudget)
        form.field(advancedBody,"输出上限",maxOutput);form.move(advancedBody,outputHelp);form.move(advancedBody,imageSupport)
        if(custom){form.field(advancedBody,"凭据请求头",headerName);form.field(advancedBody,"凭据格式",headerTemplate)}
        else if(kind!=VolcAgentPlan.KIND){form.move(advancedBody,refreshModels);form.move(advancedBody,catalogStatus)}
        if(id!=null)form.move(advancedBody,removeKey)
        advancedToggle.setOnClickListener {
            advancedExpanded=!advancedExpanded;renderAdvanced();advancedBody.visibility=if(advancedExpanded)View.VISIBLE else View.GONE
            advancedToggle.text=if(advancedExpanded)"收起高级选项 ‹" else "高级选项 ›"
        }
        form.note(form.rail,"右上角保存后生效。")
        settingsPage(if(saved==null)"添加模型服务" else "编辑模型服务",form.root,save={
            try {
                val value=JSONObject(config.toString()).put("kind",kind).put("name",name.text.toString().trim()).put("enabled",enabled.isChecked)
                    .put("url",url.text.toString().trim()).put("protocol",protocol.selectedItemPosition).put("model",model.text.toString().trim())
                    .put("contextBudget",contextBudget.text.toString().toInt()).put("contextBudgetText",contextBudget.text.toString()).put("maxOutputText",maxOutput.text.toString())
                    .put("maxOutput",outputBudget().tokens).put("supportsImages",imageSupport.isChecked)
                    .put("headerName",if(custom)headerName.text.toString() else "").put("headerTemplate",if(custom)headerTemplate.text.toString() else "")
                // Validate destination changes before touching this record's encrypted credential.
                if(id!=null && saved.getString("url").trimEnd('/')!=value.getString("url").trimEnd('/') && key.text.isBlank() && !removeKey.isChecked) {
                    testStatus.text="修改服务地址后，请重新填写此服务的 API Key；不会将已保存 Key 自动发送到新地址。";return@settingsPage
                }
                val initializing=onboardingStep!=null
                if(initializing && (!enabled.isChecked || draftKey().isBlank())) {
                    testStatus.text="请启用此服务并填写 API Key，再保存并继续初始化。";return@settingsPage
                }
                if(changeProviderSettings {
                    val record=store.save(value,key.text.toString(),removeKey.isChecked)
                    if(initializing)store.setDefault(record.getString("id"))
                }){key.text.clear();if(onboardingStep==null)showModelProviders()}
            }catch(_:Exception){testStatus.text="保存失败：请检查服务名称、地址、模型、预算和 Key；其他服务配置保持不变"}
        },back={key.text.clear();if(onboardingStep!=null)showOnboarding(0) else showModelProviders()})
    }
    private fun showSettings(section:String?=null) {
        if(section==null && onboardingStep!=null) {showOnboarding(onboardingStep!!);return}
        if(section==null) {
            val ui=SettingsUi(this)
            val content=ui.column()
            content.addView(ui.text("设置",34f).apply {setPadding(ui.dp(8),ui.dp(20),0,ui.dp(4))})
            content.addView(ui.text("让 Thor 按你的方式工作",14f,true).apply {setPadding(ui.dp(8),0,0,ui.dp(8))})
            ui.heading(content,"AI 与输入")
            val ai=ui.card(content)
            ui.row(ai,"模型服务",host.modelLabel()+" · "+host.modelStatus(),"✦"){showSettings("model")}
            ui.row(ai,"联网搜索","选择搜索服务、管理 Key 与测试连接","⌕"){showSettings("search")}
            ui.row(ai,"语音输入","火山流式识别与语音凭据","≋"){showVoiceSettingsPage()}
            ui.heading(content,"应用与设备")
            val device=ui.card(content)
            ui.row(device,"页面配色",Appearance.mode(this).label,"◐"){showAppearanceSettings()}
            ui.row(device,"聊天背景","图片、按上屏应用设置与系统壁纸","▧"){showBackgroundSettings()}
            ui.row(device,"Agent 操作展示","半透明光标、点击与滑动动效，可关闭","◎"){showAgentActionSettings()}
            ui.row(device,"应用权限",permissionSummary(),"✓"){showPermissions()}
            ui.row(device,"设备与调试","上下屏映射、无障碍与 Shizuku","▣"){showSettings("device")}
            ui.heading(content,"帮助")
            ui.row(ui.card(content),"使用引导","重新查看介绍与权限说明","☀"){showOnboarding(0)}
            settingsPage("设置",content)
            refreshPermissionPage={rememberSettingsScroll();showSettings()}
            return
        }
        val prefs = getSharedPreferences("profiles", MODE_PRIVATE); val vault = CredentialVault(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 20, 32, 20) }
        fun label(text: String) { content.addView(TextView(this).apply { this.text = text;setPadding(0,20,0,8);if(text in setOf("模型服务","联网搜索 · Brave","语音输入","设备与权限")){textSize=18f;setTypeface(null,android.graphics.Typeface.BOLD)}else textSize=13f }) }
        fun field(hint: String, name: String, secret: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint; setSingleLine(true)
            inputType = if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            if(!secret) setText(prefs.getString(name, ""))
            if(secret) { importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS; setSaveEnabled(false) }
            content.addView(this)
        }
        if(section=="model") { showModelProviders(); return }
        if(section=="search") {
        showSearchSettings(this,
            canSave={if(host.hasActiveRuns()){Toast.makeText(this,"请等待活动任务完成后再保存",Toast.LENGTH_LONG).show();false}else true},
            onModels={showModelProviders()},onSaved={closeSettings();reloadRuntime()},
            showPage={page,save->settingsPage("联网搜索",page,save=save,back={showSettings()})})
        return
        }
        label("设备与权限")
        val facts = dev.thor.tools.diagnostic.displayFacts(this)
        val upper = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, facts.map { "目标上屏 ${it.id}: ${it.width}×${it.height}" }); content.addView(this) }
        val lower = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, facts.map { "聊天下屏 ${it.id}: ${it.width}×${it.height}" }); content.addView(this); if(facts.size > 1) setSelection(1) }
        content.addView(Button(this).apply { text = "把聊天移动到所选下屏"; setOnClickListener { val target = facts[lower.selectedItemPosition].id; startActivity(android.content.Intent(this@MainActivity,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),android.app.ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle()) } })
        content.addView(Button(this).apply { text = "确认本次上下屏映射"; setOnClickListener { runCatching { host.calibrate(facts[upper.selectedItemPosition].id, facts[lower.selectedItemPosition].id) }.onSuccess { Toast.makeText(this@MainActivity, "映射已校准；只授权新运行", Toast.LENGTH_LONG).show() }.onFailure { Toast.makeText(this@MainActivity, "必须选择两个不同且存在的显示", Toast.LENGTH_LONG).show() } } })
        val accessibilityStatusView=TextView(this).apply {text=host.accessibilitySetupStatus();content.addView(this)}
        content.addView(Button(this).apply { text = "打开系统无障碍设置（无 Shizuku 时手动开启）"; setOnClickListener { startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        content.addView(Button(this).apply { text = "请求/绑定 Shizuku（shell UID）"; setOnClickListener { runCatching { host.bindDebug() }.onFailure { Toast.makeText(this@MainActivity, "Shizuku 尚未就绪或没有 shell 授权", Toast.LENGTH_LONG).show() } } })
        label("设备状态：确认上下屏映射后使用屏幕工具；手柄控制暂不可用。\nADB 调试采用命令分级：白名单命令及亮度、风扇、刷新率等常用调整直接执行；涉及隐私或按键分配的灰名单需逐条确认；抹除、格式化、刷机和未审查命令禁止执行。内置操作手册可在 Skill 管理中查看。")
        settingsPage("设备与权限",content,back={showSettings()})
        refreshPermissionPage={accessibilityStatusView.text=host.accessibilitySetupStatus()}
    }
    private fun granted(permission:String)=checkSelfPermission(permission)==android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun notificationsGranted()=android.os.Build.VERSION.SDK_INT<33 || granted(android.Manifest.permission.POST_NOTIFICATIONS)
    private fun accessibilityGranted()=host.accessibilityStatus().status==dev.thor.tools.Status.READY
    private fun accessibilityLabel()=when {
        accessibilityGranted()->"已连接"
        host.accessibilityRestartRequired()->"需重启应用"
        host.accessibilityRecoveryPending()->"正在自动恢复"
        dev.thor.tools.diagnostic.ThorAccessibilityService.settingEnabled(this)->"连接失效，需重新开启"
        else->"未开启"
    }
    private fun permissionSummary()="通知${if(notificationsGranted())"已开启" else "未开启"} · 麦克风${if(granted(android.Manifest.permission.RECORD_AUDIO))"已允许" else "未允许"} · 无障碍${accessibilityLabel()}"
    private fun openSystemSettings(intent:android.content.Intent) {
        runCatching {startActivity(intent)}.onFailure {Toast.makeText(this,"无法打开系统设置，请在系统设置中找到 Loki",Toast.LENGTH_LONG).show()}
    }
    private fun requestOptionalPermission(permission:String,code:Int) {
        val prefs=getSharedPreferences("onboarding",MODE_PRIVATE)
        if(prefs.getBoolean("asked.$permission",false) && !shouldShowRequestPermissionRationale(permission)) {
            openSystemSettings(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
        } else {
            prefs.edit().putBoolean("asked.$permission",true).apply()
            requestPermissions(arrayOf(permission),code)
        }
    }
    private fun permissionCards(ui:SettingsUi,content:LinearLayout) {
        ui.heading(content,"按需开启，随时可改")
        val card=ui.card(content)
        ui.row(card,"后台任务通知 · "+if(notificationsGranted())"已开启" else "未开启","在通知栏查看任务进度并停止任务。","◉") {
            if(notificationsGranted())openSystemSettings(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))
            else requestOptionalPermission(android.Manifest.permission.POST_NOTIFICATIONS,74)
        }
        ui.row(card,"麦克风 · "+if(granted(android.Manifest.permission.RECORD_AUDIO))"已允许" else "未允许","用于按住说话；录音发送给配置的语音识别服务，需另行配置语音凭据。","≋") {
            if(granted(android.Manifest.permission.RECORD_AUDIO))openSystemSettings(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
            else requestOptionalPermission(android.Manifest.permission.RECORD_AUDIO,75)
        }
        ui.row(card,"无障碍服务 · ${accessibilityLabel()}","${host.accessibilitySetupStatus()}。连接后可读取屏幕与执行操作；使用时还需开启会话中的相应能力。","▣") {
            openSystemSettings(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        content.addView(ui.text("文字聊天无需以上权限。Shizuku 与上下屏映射可在「设备与调试」中按需设置。",13f,true).apply {setPadding(ui.dp(8),ui.dp(12),ui.dp(8),ui.dp(20))})
    }
    private fun showPermissions() {
        val ui=SettingsUi(this);val content=ui.column()
        permissionCards(ui,content)
        settingsPage("应用权限",content,back={showSettings()})
        refreshPermissionPage={showPermissions()}
    }
    private fun finishOnboarding() {
        if(!host.onboardingModelReady()) {
            Toast.makeText(this,"请先连接 LLM 模型服务",Toast.LENGTH_LONG).show();showOnboarding(0);return
        }
        if(!getSharedPreferences("onboarding",MODE_PRIVATE).edit().putBoolean("completed",true).commit()) {
            Toast.makeText(this,"无法保存引导状态，请重试",Toast.LENGTH_LONG).show();return
        }
        onboardingStep=null;closeSettings()
    }
    private fun leaveOnboarding() {
        if(getSharedPreferences("onboarding",MODE_PRIVATE).getBoolean("completed",false)) {
            onboardingStep=null;showSettings()
        } else moveTaskToBack(true)
    }
    private fun voiceConfigured():Boolean = VoiceServices.resolve(this)!=null
    private fun showOnboarding(step:Int) {
        onboardingStep=step
        val ui=SettingsUi(this);val content=ui.column()
        content.addView(ui.text(if(step==0)"连接 AI 服务" else "配置操作权限",34f).apply {setPadding(ui.dp(8),ui.dp(28),0,ui.dp(12))})
        content.addView(ui.text(if(step==0)"第 1 步，共 2 步 · 选择你的 AI 服务。火山 AgentPlan 一个 Key 即可连接模型、语音与搜索。" else "第 2 步，共 2 步 · 基础开启无障碍，进阶授权 Shizuku。",15f,true).apply {setPadding(ui.dp(8),0,ui.dp(8),ui.dp(20))})
        if(step==0) {
            val ready=host.onboardingModelReady()
            val store=ProviderStore(this);val config=store.defaultId()?.let {store.get(it)}
            ui.heading(content,"必选 · LLM 模型")
            ui.row(ui.card(content),"连接 LLM 模型 · ${if(ready)"已配置" else "待配置"}",
                if(ready)"${config?.optString("name")} · ${config?.optString("model")}\n点击管理或测试连接" else "可选择火山 AgentPlan、DeepSeek 官方、OpenCode Go 或自定义接口。","✦"){showModelProviders()}
            ui.row(ui.card(content),"配置火山 AgentPlan","填写一个套餐 Key，选择模型；语音和豆包搜索共享此 Key。","✦") {
                val plans=store.list().filter {it.getString("kind")==VolcAgentPlan.KIND && it.getBoolean("enabled")}
                val saved=plans.firstOrNull {it.getString("id")==store.defaultId()} ?: plans.firstOrNull()
                showProviderEditor(saved,VolcAgentPlan.KIND)
            }
            ui.heading(content,"可选 · 扩展能力")
            val optional=ui.card(content)
            ui.row(optional,"语音输入 · ${if(voiceConfigured())"已配置" else "未配置"}",if(VolcAgentPlan.selected(this)!=null)"已跟随当前 AgentPlan，共享套餐 Key；可在语音设置改用独立服务。" else "可共享火山 AgentPlan Key，或配置独立语音识别服务。","≋"){showVoiceSettingsPage()}
            ui.row(optional,"联网搜索 · ${if(host.onboardingSearchReady())"已配置" else "未配置"}","火山 AgentPlan 与 DeepSeek 官方可共享模型 Key；也可选择 Tavily 或 Brave。","⌕"){showSettings("search")}
            content.addView(ui.button("下一步 · 配置操作权限") {
                if(host.onboardingModelReady())showOnboarding(1)
                else Toast.makeText(this,"请先连接 LLM 模型服务",Toast.LENGTH_LONG).show()
            }.apply {isEnabled=ready})
            if(!ready)content.addView(ui.text("请先完成必选的 LLM 配置；语音与搜索不影响继续。",13f,true).apply {setPadding(ui.dp(8),ui.dp(12),ui.dp(8),0)})
        } else {
            ui.heading(content,"基础 · 无障碍服务")
            ui.row(ui.card(content),"开启无障碍 · ${accessibilityLabel()}",
                "${host.accessibilitySetupStatus()}。点击打开系统设置，选择 Loki；连接失效时先关闭再重新开启服务。","▣") {
                openSystemSettings(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            ui.heading(content,"进阶 · Shizuku 授权")
            ui.row(ui.card(content),"授权并绑定 Shizuku","用于进阶设备调试与 Shell 操作。请先安装并启动 Shizuku，然后点击授权。","⌘") {
                runCatching {host.bindDebug()}.onFailure {Toast.makeText(this,"请先安装并以无线调试 / ADB 方式启动 Shizuku，再返回授权（仅支持 shell 身份）",Toast.LENGTH_LONG).show()}
            }
            val status=ui.text(host.shizukuSetupStatus(),13f,true).apply {setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(16))}
            content.addView(status)
            status.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
                val update=object:Runnable {
                    override fun run() {if(status.isAttachedToWindow){status.text=host.shizukuSetupStatus();status.postDelayed(this,1000)}}
                }
                override fun onViewAttachedToWindow(view:View) {status.post(update)}
                override fun onViewDetachedFromWindow(view:View) {status.removeCallbacks(update)}
            })
            content.addView(ui.text("操作权限可稍后开启。文字聊天只需 LLM 模型；会话中的屏幕感知、操作和调试能力仍由你单独控制。",13f,true).apply {setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(20))})
            content.addView(ui.button("完成，开始使用"){finishOnboarding()})
        }
        settingsPage("使用引导 · ${step+1}/2",content,back={if(step==1)showOnboarding(0) else leaveOnboarding()})
        refreshPermissionPage={showOnboarding(step)}
    }
    private fun showVoiceSettingsPage() {
        showVoiceSettings(this,onSaved={if(onboardingStep!=null)showOnboarding(0)}) {content,save->
            val wrapper=LinearLayout(this).apply {tag="settings-designed";orientation=LinearLayout.VERTICAL;addView(content)}
            val microphone=SettingsUi(this).button("麦克风权限 · ${if(granted(android.Manifest.permission.RECORD_AUDIO))"已允许" else "点击授予"}") {
                if(granted(android.Manifest.permission.RECORD_AUDIO))openSystemSettings(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
                else requestOptionalPermission(android.Manifest.permission.RECORD_AUDIO,75)
            }
            (content.findViewWithTag<LinearLayout>("voice-permission-slot") ?: wrapper).addView(microphone)
            settingsPage("语音输入",wrapper,save,back={showSettings()})
            refreshPermissionPage={microphone.text="麦克风权限 · ${if(granted(android.Manifest.permission.RECORD_AUDIO))"已允许" else "点击授予"}"}
        }
    }

}

/** A document result waiting for the existing Service connection. No Runtime,
 * Activity, ContentResolver or writer is retained in saved instance state. */
internal data class PendingPickerResult(val requestCode:Int,val uri:String,val sessionId:String?) {
    fun save(out:Bundle) {
        out.putInt("pickerResult.requestCode",requestCode)
        out.putString("pickerResult.uri",uri)
        out.putString("pickerResult.sessionId",sessionId)
    }
    companion object {
        fun restore(state:Bundle?):PendingPickerResult? {
            val code=state?.getInt("pickerResult.requestCode") ?: return null
            val uri=state.getString("pickerResult.uri")?.takeIf {it.isNotBlank()} ?: return null
            val sid=state.getString("pickerResult.sessionId")
            if(code !in setOf(71,72) || code==71 && sid==null)return null
            return PendingPickerResult(code,uri,sid)
        }
    }
}

/** Holds no Activity. Released as soon as its replacement has a service binding. */
private class RuntimeRecreationHold(private val context: android.content.Context) : AutoCloseable {
    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {}
        override fun onServiceDisconnected(name: android.content.ComponentName) {}
    }
    private var bound = context.bindService(android.content.Intent(context, AgentRuntimeService::class.java), connection, android.content.Context.BIND_AUTO_CREATE)
    override fun close() { if (bound) { context.unbindService(connection); bound = false } }
}
