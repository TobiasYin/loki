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
            val screen=java.lang.ref.WeakReference(this@MainActivity)
            service.whenEngineReady {screen.get()?.let {if(it.runtimeService===service && !it.isDestroyed && !it.isFinishing)it.attachEngine()}}
        }
        override fun onServiceDisconnected(name:android.content.ComponentName) {
            attachedEngine=null;runtimeService=null
            if(!isDestroyed && !isFinishing)showRuntimeError("执行服务已退出；历史保留，运行不会自动重放。")
        }
    }
    private var pickerSession: String? = null
    private var pickerKind="image"
    private var pendingPickerResult:PendingPickerResult?=null
    private var settingsView:View?=null
    private var settingsBack:()->Unit={closeSettings()}
    private var microphonePermissionPending=false
    private var voice:VoiceInput?=null
    private var voiceId:String?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerSession=savedInstanceState?.getString("pickerSession")
        pickerKind=savedInstanceState?.getString("pickerKind") ?: "image"
        pendingPickerResult=PendingPickerResult.restore(savedInstanceState)
        container=FrameLayout(this)
        container.addView(TextView(this).apply {text="正在打开聊天…";gravity=android.view.Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
        setContentView(container)
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
        settingsView=null;container.removeAllViews();engine.attach(this,container,runtimeUi)
        web=engine.web // Display-only WebView; execution document stays in Service.
        consumePendingPickerResult()
    }
    private fun reloadRuntime() {
        val service=runtimeService ?: return
        val screen=java.lang.ref.WeakReference(this)
        if(!service.reloadConfiguration {screen.get()?.let {if(it.runtimeService===service && !it.isDestroyed && !it.isFinishing)it.attachEngine()}}) {
            Toast.makeText(this,"请等待活动任务结束后再重新加载设置",Toast.LENGTH_LONG).show();return
        }
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
    override fun onStart() {super.onStart();chatStarted=true;attachedEngine?.uiVisibilityChanged(runtimeUi)}
    override fun onStop() {chatStarted=false;super.onStop()}
    override fun onWindowFocusChanged(hasFocus:Boolean) {super.onWindowFocusChanged(hasFocus);attachedEngine?.uiVisibilityChanged(runtimeUi)}
    override fun onPause() {super.onPause();voice?.close();voice=null;voiceId=null}
    override fun onDestroy() {
        voice?.close();voice=null;voiceId=null
        attachedEngine?.detach(runtimeUi);attachedEngine=null
        if(serviceBound){unbindService(serviceConnection);serviceBound=false}
        runtimeService=null
        super.onDestroy()
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out);out.putString("pickerSession",pickerSession);out.putString("pickerKind",pickerKind)
        pendingPickerResult?.save(out)
    }
    @Deprecated("System document picker result")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
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
    @Deprecated("Android back callback")
    override fun onBackPressed() {if(settingsView!=null)settingsBack() else super.onBackPressed()}
    private fun closeSettings() {settingsView?.let {(it.parent as? android.view.ViewGroup)?.removeView(it)};settingsView=null}
    private fun settingsPage(title:String,content:View,save:(()->Unit)?=null,back:()->Unit={closeSettings()}):LinearLayout {
        closeSettings()
        val page=LinearLayout(this).apply {tag="thor-settings-page";orientation=LinearLayout.VERTICAL;setBackgroundColor(if(resources.configuration.uiMode and 0x30==0x20)0xff202020.toInt() else 0xfffafafa.toInt())}
        val toolbar=LinearLayout(this).apply {gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(12,8,12,8)}
        toolbar.addView(Button(this).apply {text="‹ 返回";backgroundTintList=android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT);setOnClickListener {back()}})
        toolbar.addView(TextView(this).apply {text=title;textSize=20f;setTextColor(if(resources.configuration.uiMode and 0x30==0x20)0xffeeeeee.toInt() else 0xff222222.toInt())},LinearLayout.LayoutParams(0,-2,1f))
        if(save!=null)toolbar.addView(Button(this).apply {text="保存";setTextColor(android.graphics.Color.WHITE);backgroundTintList=android.content.res.ColorStateList.valueOf(0xff2f6edb.toInt());setOnClickListener {save()}})
        page.addView(toolbar);page.addView(ScrollView(this).apply {isFillViewport=true;addView(content)},LinearLayout.LayoutParams(-1,0,1f))
        (web.parent as FrameLayout).addView(page,FrameLayout.LayoutParams(-1,-1));settingsView=page;settingsBack=back
        return page
    }
    private fun showSettings(section:String?=null) {
        if(section==null) {
            val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,16,24,24)}
            val modelLabel=host.modelLabel()
            fun row(title:String,subtitle:String,action:()->Unit) {
                content.addView(Button(this).apply {text=title;isAllCaps=false;textSize=16f;gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL;minimumHeight=64;setPadding(12,8,12,0);backgroundTintList=android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT);setOnClickListener {action()}},LinearLayout.LayoutParams(-1,-2))
                content.addView(TextView(this).apply {text=subtitle;textSize=13f;setPadding(12,0,12,18)})
                content.addView(View(this).apply {setBackgroundColor(0x22888888)},LinearLayout.LayoutParams(-1,1))
            }
            row("模型服务 ›",modelLabel+" · "+host.modelStatus()){showSettings("model")}
            row("联网搜索 ›","Brave 搜索与 API Key"){showSettings("search")}
            row("火山流式语音识别 ›","语音输入、识别资源与 API Key"){showVoiceSettingsPage()}
            row("设备与权限 ›","显示映射、无障碍与 Shizuku"){showSettings("device")}
            val notificationsAllowed=android.os.Build.VERSION.SDK_INT<33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED
            row("后台任务通知 ›",if(notificationsAllowed)"通知中可查看运行并停止任务" else "未允许通知；后台任务仍可运行，点此开启通知") {
                if(!notificationsAllowed)offerNotificationPermission()
                else startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))
            }
            settingsPage("设置",content);return
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
        if(section=="model") {
        label("模型服务")
        val savedProvider=prefs.getString("provider",null) ?: if(prefs.getString("url","").orEmpty().startsWith("https://opencode.ai/zen/go"))"opencode-go" else "custom"
        val providerIds=listOf("opencode-go","custom","deepseek") // Keep existing Go/custom positions compatible.
        val provider=Spinner(this).apply {
            tag="model-provider";contentDescription="模型服务商"
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("OpenCode Go","自定义 API Key","DeepSeek 官方"))
            setSelection(if(prefs.getString("url","").isNullOrBlank())2 else providerIds.indexOf(savedProvider).let {if(it<0)1 else it});content.addView(this)
        }
        fun providerId()=providerIds[provider.selectedItemPosition]
        val name = field("模型显示名", "name")
        val protocol = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("OpenAI Responses", "Anthropic Messages","OpenAI Chat Completions")); setSelection(prefs.getInt("protocol", 0)); content.addView(this) }
        val url = field("模型基础 URL（HTTP / HTTPS）", "url"); val model = field("模型 ID", "model")
        val contextBudget = field("自定义模型上下文上限（tokens）", "contextBudgetText")
        val maxOutput = field("最大输出预算（默认 2048；按服务实际限制填写）", "maxOutputText")
        val imageSupport=CheckBox(this).apply {text="支持图片输入";isChecked=prefs.getBoolean("supportsImages",true);content.addView(this)}
        val catalog=ModelCatalog(this);val deepseek=DeepSeekCatalog(this)
        val modelPicker=Spinner(this).apply {tag="model-picker";contentDescription="选择模型"};content.addView(modelPicker)
        val catalogStatus=TextView(this).apply {tag="model-catalog-status";content.addView(this)}
        val refreshModels=Button(this).apply {content.addView(this)}
        val headerName = field("自定义凭据头（可选）", "headerName")
        val headerTemplate = field("凭据格式，例如 Bearer {{credential}}（可选）", "headerTemplate")
        val keyStatus=TextView(this).apply {setPadding(0,20,0,8);content.addView(this)}
        val modelKey = field("API Key（留空保留）", "", true)
        val removeModel = CheckBox(this).apply { text = "删除已保存模型 Key"; content.addView(this) }
        var advancedExpanded=false
        val advanced=listOf(contextBudget,maxOutput,imageSupport,headerName,headerTemplate)
        fun renderAdvanced() {
            advanced.forEach {it.visibility=if(advancedExpanded && (providerId()=="custom" || it==contextBudget || it==maxOutput || it==imageSupport))View.VISIBLE else View.GONE}
            contextBudget.isEnabled=providerId()=="custom";imageSupport.isEnabled=providerId()=="custom"
        }
        renderAdvanced()
        content.addView(Button(this).apply {text="高级选项 ›";setOnClickListener {advancedExpanded=!advancedExpanded;renderAdvanced();text=if(advancedExpanded)"收起高级选项 ‹" else "高级选项 ›"}})
        fun draftKey():String=if(removeModel.isChecked)"" else modelKey.text.toString().ifBlank {
            if(DeepSeekCatalog.canReuseCredential(savedProvider,providerId()))vault.readNative("model").orEmpty() else ""
        }
        fun selectedMetadata():JSONObject?=when(providerId()) {"deepseek"->deepseek.models();"opencode-go"->catalog.models();else->null}
            ?.objects()?.find {it.optString("id")==model.text.toString() && it.optString("protocol")==ModelCatalog.protocols[protocol.selectedItemPosition]}
        fun updateModels() {
            val selectedProvider=providerId();if(selectedProvider=="custom")return
            val choices=(if(selectedProvider=="deepseek")deepseek.models() else catalog.models()).objects()
            modelPicker.onItemSelectedListener=null
            modelPicker.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,choices.map {it.getString("name")})
            val chosen=choices.indexOfFirst {it.getString("id")==model.text.toString()}.coerceAtLeast(0)
            fun applyChoice(position:Int) {
                val choice=choices[position];model.setText(choice.getString("id"));name.setText(choice.getString("name"))
                contextBudget.setText(choice.getInt("contextWindow").toString());protocol.setSelection(ModelCatalog.protocols.indexOf(choice.getString("protocol")))
                imageSupport.isChecked=choice.optJSONArray("input")?.let {a->(0 until a.length()).any {a.getString(it)=="image"}}==true
                val source=if(selectedProvider=="deepseek")deepseek.source() else catalog.source()
                val labels=mapOf("off" to "关闭","low" to "低","high" to "高","max" to "最大")
                val effort=if(selectedProvider=="deepseek")choice.getJSONArray("efforts").let {a->(0 until a.length()).joinToString(" / "){labels[a.getString(it)] ?: a.getString(it)}}+"（默认${labels[choice.optString("defaultEffort","high")] ?: "高"}）" else choice.getJSONArray("efforts").join(" / ")
                catalogStatus.text="$source\n上下文 ${choice.getInt("contextWindow")} tokens · 思考强度 $effort\n"+if(imageSupport.isChecked)"支持文字和图片" else "仅支持文字"
            }
            modelPicker.setSelection(chosen);applyChoice(chosen)
            modelPicker.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?)=Unit
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,itemId:Long) {if(providerId()==selectedProvider)applyChoice(position)}
            }
        }
        refreshModels.setOnClickListener {
            val selectedProvider=providerId();val key=draftKey()
            if(selectedProvider=="deepseek" && key.isBlank()){catalogStatus.text="请填写 DeepSeek 官方 API Key 后刷新；可先选择内置官方模型";return@setOnClickListener}
            refreshModels.isEnabled=false;catalogStatus.text="正在读取模型资料…"
            Thread {
                val success=runCatching {if(selectedProvider=="deepseek")deepseek.refresh(key) else catalog.refresh()}.isSuccess
                runOnUiThread {if(refreshModels.isAttachedToWindow){refreshModels.isEnabled=true;if(providerId()==selectedProvider){if(success)updateModels() else catalogStatus.text="暂时无法刷新，请检查该服务的 Key、网络或额度；保留已缓存模型资料"}}}
            }.start()
        }
        var lastProvider=savedProvider
        provider.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?)=Unit
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,itemId:Long) {
                val id=providerId();val fixed=id!="custom"
                if(!DeepSeekCatalog.canReuseCredential(lastProvider,id))modelKey.text.clear()
                lastProvider=id
                modelPicker.visibility=if(fixed)View.VISIBLE else View.GONE;refreshModels.visibility=modelPicker.visibility;catalogStatus.visibility=modelPicker.visibility
                for(v in listOf(protocol,model,name,url))v.visibility=if(fixed)View.GONE else View.VISIBLE
                keyStatus.text=if(DeepSeekCatalog.canReuseCredential(savedProvider,id))"模型 API Key：${vault.status("model")}；留空保留，勾选后删除" else "切换服务请填写该服务的 API Key；不会复用其他服务的 Key"
                refreshModels.text=if(id=="deepseek")"刷新 DeepSeek 官方模型列表" else "刷新 OpenCode Go 模型列表"
                if(fixed){url.setText(if(id=="deepseek")DeepSeekCatalog.BASE_URL else "https://opencode.ai/zen/go/v1");updateModels()}
                renderAdvanced()
            }
        }
        fun currentModelDraft():ModelConnectionTest.Draft {
            val custom=providerId()=="custom"
            return ModelConnectionTest.Draft(url.text.toString().trim(),ModelCatalog.protocols[protocol.selectedItemPosition],model.text.toString().trim(),
                draftKey(),if(custom)headerName.text.toString() else "",if(custom)headerTemplate.text.toString() else "").withProfile(selectedMetadata())
        }
        val testStatus=TextView(this).apply {tag="model-test-status";text="测试当前页面填写的配置；同一服务的空白 Key 使用已保存 Key。草稿不会自动保存。";setPadding(0,12,0,12);content.addView(this)}
        val test=Button(this).apply {text="测试当前填写的连接";tag="model-test";content.addView(this)}
        test.setOnClickListener {
            val draft=currentModelDraft()
            if(runCatching {draft.validate()}.isFailure){testStatus.text="请填写该服务有效的 API Key 和模型配置；草稿未保存";return@setOnClickListener}
            test.isEnabled=false;testStatus.text="正在测试当前填写的连接…（草稿未保存）"
            Thread {
                val result=ModelConnectionTest.run(draft)
                runOnUiThread {if(testStatus.isAttachedToWindow){test.isEnabled=true;testStatus.text="${result.message} · ${result.elapsedMs} ms\n草稿未保存，点击右上角保存后生效。"}}
            }.start()
        }
        val fullTest=Button(this).apply {text="完整检查已保存配置（图片与工具）";content.addView(this)}
        fullTest.setOnClickListener {
            try {
                require(!host.hasActiveRuns())
                val token=host.requestProbe();val sid=host.selectedSession()
                attachedEngine?.executionWeb?.evaluateJavascript("window.runNativeProbe(${JSONObject.quote(token)}, ${JSONObject.quote(sid)})",null)
                Toast.makeText(this,"正在检查已保存的配置；当前草稿不会参与",Toast.LENGTH_LONG).show()
            } catch(_:Exception){Toast.makeText(this,"请先保存有效配置，并等待当前连接检查结束",Toast.LENGTH_LONG).show()}
        }
        val probeStatus=TextView(this).apply {text="已保存配置：${host.modelStatus()}";content.addView(this)}
        val statusPoll=object:Runnable {override fun run(){if(probeStatus.isAttachedToWindow){val value="已保存配置：${host.modelStatus()}";if(probeStatus.text.toString()!=value)probeStatus.text=value;probeStatus.postDelayed(this,1000)}}}
        probeStatus.postDelayed(statusPoll,1000)
        settingsPage("模型服务",content,save={
            try {
                require(!host.hasActiveRuns())
                currentModelDraft().let {if(removeModel.isChecked)it.copy(credential="removed") else it}.validate()
                val budget=contextBudget.text.toString().ifBlank {"32768"}.toInt()
                val outputBudget=maxOutput.text.toString().ifBlank {"2048"}.toInt()
                require(budget in 4096..4000000 && outputBudget in 16..16384 && outputBudget<budget)
                val nextRevision=prefs.getInt("revision",0)+1
                check(prefs.edit().putInt("revision",nextRevision).putString("probe","untested").remove("probeRevision").commit())
                if(removeModel.isChecked)vault.remove("model") else if(modelKey.length()>0)vault.put("model",modelKey.text.toString())
                check(prefs.edit().putBoolean("supportsImages",imageSupport.isChecked).putString("provider",providerId()).putString("name",name.text.toString().take(80)).putInt("protocol",protocol.selectedItemPosition)
                    .putString("url",url.text.toString().trim()).putString("model",model.text.toString().trim()).putString("contextBudgetText",contextBudget.text.toString()).putString("maxOutputText",maxOutput.text.toString())
                    .putInt("contextBudget",budget).putInt("maxOutput",outputBudget).putString("headerName",if(providerId()=="custom")headerName.text.toString() else "").putString("headerTemplate",if(providerId()=="custom")headerTemplate.text.toString() else "").commit())
                modelKey.text.clear();closeSettings();reloadRuntime();Toast.makeText(this,if(providerId()!="custom")"设置已保存；官方模型可直接使用，也可在设置中测试连接" else "设置已保存；自定义模型首次使用会检查连接与工具能力",Toast.LENGTH_LONG).show()
            }catch(_:Exception){Toast.makeText(this,"保存失败：请先停止活动任务，并检查地址、预算和密钥配置",Toast.LENGTH_LONG).show()}
        },back={showSettings()})
        return
        }
        if(section=="search") {
        label("联网搜索 · Brave")
        val searchUrl = field("搜索 API 地址", "searchUrl").apply {if(text.isBlank())setText("https://api.search.brave.com/res/v1/web/search")}
        label("搜索 Key：${vault.status("search")}")
        val searchKey = field("API Key（留空保留）", "", true)
        val removeSearch = CheckBox(this).apply { text = "删除已保存搜索 Key"; content.addView(this) }
        val searchTestStatus=TextView(this).apply {text="测试当前填写的地址与 Key；留空使用已保存 Key，不自动保存。";content.addView(this)}
        val searchTest=Button(this).apply {text="测试当前搜索连接";content.addView(this)}
        searchTest.setOnClickListener {
            val testUrl=searchUrl.text.toString().trim();val testKey=if(removeSearch.isChecked)"" else searchKey.text.toString().ifBlank {vault.readNative("search").orEmpty()}
            searchTest.isEnabled=false;searchTestStatus.text="正在测试当前搜索配置…"
            Thread {val result=SearchConnectionTest.run(testUrl,testKey);runOnUiThread {if(searchTestStatus.isAttachedToWindow){searchTest.isEnabled=true;searchTestStatus.text="${result.message} · ${result.elapsedMs} ms"}}}.start()
        }
        settingsPage("联网搜索",content,save={
            try {
                require(!host.hasActiveRuns())
                val value=searchUrl.text.toString().trim();val uri=URI(value)
                require(uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.rawQuery==null && uri.fragment==null && searchKey.length()<=8192)
                if(removeSearch.isChecked)vault.remove("search") else if(searchKey.length()>0)vault.put("search",searchKey.text.toString())
                check(prefs.edit().putString("searchBackend","Brave").putString("searchUrl",value).commit())
                searchKey.text.clear();closeSettings();reloadRuntime()
            }catch(_:Exception){Toast.makeText(this,"保存失败：请停止活动任务，并检查搜索地址和 Key",Toast.LENGTH_LONG).show()}
        },back={showSettings()})
        return
        }
        label("设备与权限")
        val facts = dev.thor.tools.diagnostic.displayFacts(this)
        val upper = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, facts.map { "目标上屏 ${it.id}: ${it.width}×${it.height}" }); content.addView(this) }
        val lower = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, facts.map { "聊天下屏 ${it.id}: ${it.width}×${it.height}" }); content.addView(this); if(facts.size > 1) setSelection(1) }
        content.addView(Button(this).apply { text = "把聊天移动到所选下屏"; setOnClickListener { val target = facts[lower.selectedItemPosition].id; startActivity(android.content.Intent(this@MainActivity,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),android.app.ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle()) } })
        content.addView(Button(this).apply { text = "确认本次上下屏映射"; setOnClickListener { runCatching { host.calibrate(facts[upper.selectedItemPosition].id, facts[lower.selectedItemPosition].id) }.onSuccess { Toast.makeText(this@MainActivity, "映射已校准；只授权新运行", Toast.LENGTH_LONG).show() }.onFailure { Toast.makeText(this@MainActivity, "必须选择两个不同且存在的显示", Toast.LENGTH_LONG).show() } } })
        label("${host.accessibilitySetupStatus()}。已有 Shizuku shell 授权时自动开启本应用无障碍；会话能力开关仍由你选择。")
        content.addView(Button(this).apply { text = "打开系统无障碍设置（无 Shizuku 时手动开启）"; setOnClickListener { startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        content.addView(Button(this).apply { text = "请求/绑定 Shizuku（shell UID）"; setOnClickListener { runCatching { host.bindDebug() }.onFailure { Toast.makeText(this@MainActivity, "Shizuku 尚未就绪或没有 shell 授权", Toast.LENGTH_LONG).show() } } })
        label("设备状态：确认上下屏映射后使用屏幕工具；手柄控制暂不可用。\nADB 调试采用命令分级：白名单命令及亮度、风扇、刷新率等常用调整直接执行；涉及隐私或按键分配的灰名单需逐条确认；抹除、格式化、刷机和未审查命令禁止执行。内置操作手册可在 Skill 管理中查看。")
        settingsPage("设备与权限",content,back={showSettings()})
    }
    private fun showVoiceSettingsPage() {
        showVoiceSettings(this) {content,save->settingsPage("语音输入",content,save,back={showSettings()})}
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
