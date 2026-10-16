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
    private lateinit var uiStates:LatestValue<JSONObject>
    private var bridge: dev.thor.bridge.android.TrustedWebViewBridge? = null
    private var pickerSession: String? = null
    private var pickerKind="image"
    private var settingsView:View?=null
    private var settingsBack:()->Unit={closeSettings()}
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
        uiStates=LatestValue({task->web.postDelayed({task()},50)},{state,delivered->
            if(!isFinishing && !isDestroyed)web.evaluateJavascript("window.receiveProductState?.(JSON.parse(${JSONObject.quote(state.toString())}))") {delivered()} else delivered()
        })
        try { host = ProductHost.create(this) { state -> runOnUiThread {uiStates.offer(state)} }
        } catch(error:Exception) {if(BuildConfig.DEBUG)android.util.Log.e("ThorInit",error.javaClass.simpleName+"\n"+error.stackTrace.joinToString("\n"));setContentView(LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;addView(TextView(this@MainActivity).apply {text="本地存储或安全配置初始化失败；历史保留，未执行网络或设备操作。"});addView(Button(this@MainActivity).apply {text="重试打开";setOnClickListener {recreate()}})});return}
        val transport = dev.thor.bridge.HttpTransport(host.authority, host.policy)
        val toolGateway = dev.thor.bridge.ToolGateway(host.authority, emptyList(), { emptyMap() })
        bridge = dev.thor.bridge.android.TrustedWebViewBridge(web, host.authority, transport, toolGateway,
            { c -> host.bootstrap(c) }, "$origin/assets/chat/index.html", { c, request ->
                if(request.optString("op").startsWith("voice.")) {
                    runOnUiThread { handleVoice(request.optString("op"),request.optJSONObject("data") ?: JSONObject()) }
                    java.util.concurrent.CompletableFuture.completedFuture(JSONObject())
                } else if(request.optString("op") == "openLink") {
                    val raw=request.optJSONObject("data")?.optString("url").orEmpty()
                    runOnUiThread {runCatching {
                        val uri=URI(raw);require(uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null && raw.length<=8192)
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,Uri.parse(raw)))
                    }.onFailure {Toast.makeText(this,"无法打开此链接",Toast.LENGTH_SHORT).show()}}
                    java.util.concurrent.CompletableFuture.completedFuture(JSONObject())
                } else if(request.optString("op") == "voiceSettings") {
                    runOnUiThread {showSettings()};java.util.concurrent.CompletableFuture.completedFuture(JSONObject())
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
            uiStates.close()
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
        if(::uiStates.isInitialized)uiStates.close()
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
        val provider=Spinner(this).apply {adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("OpenCode Go","自定义 API Key"));setSelection(if((prefs.getString("provider",null)?.let {it=="opencode-go"} ?: prefs.getString("url","").orEmpty().startsWith("https://opencode.ai/zen/go")))0 else 1);content.addView(this)}
        val name = field("模型显示名", "name")
        val protocol = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("OpenAI Responses", "Anthropic Messages","OpenAI Chat Completions")); setSelection(prefs.getInt("protocol", 0)); content.addView(this) }
        val url = field("模型基础 URL（HTTP / HTTPS）", "url"); val model = field("模型 ID", "model")
        val contextBudget = field("自定义模型上下文上限（tokens）", "contextBudgetText")
        val maxOutput = field("最大输出预算（默认 2048；按服务实际限制填写）", "maxOutputText")
        val imageSupport=CheckBox(this).apply {text="支持图片输入";isChecked=prefs.getBoolean("supportsImages",true);content.addView(this)}
        val catalog=ModelCatalog(this)
        val modelPicker=Spinner(this);content.addView(modelPicker)
        val catalogStatus=TextView(this).apply {text=catalog.source();content.addView(this)}
        fun updateModels() {
            val choices=catalog.models().objects()
            modelPicker.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,choices.map {it.getString("name")})
            modelPicker.setSelection(choices.indexOfFirst {it.getString("id")==model.text.toString()}.coerceAtLeast(0))
            modelPicker.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?)=Unit
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,itemId:Long) {
                    if(provider.selectedItemPosition!=0)return
                    val choice=choices[position];model.setText(choice.getString("id"));name.setText(choice.getString("name"))
                    contextBudget.setText(choice.getInt("contextWindow").toString());protocol.setSelection(ModelCatalog.protocols.indexOf(choice.getString("protocol")))
                    catalogStatus.text="${catalog.source()}\n上下文 ${choice.getInt("contextWindow")} tokens · 思考强度 ${choice.getJSONArray("efforts").join(" / ")}"
                }
            }
        }
        updateModels()
        val refreshModels=Button(this).apply {text="刷新 OpenCode Go 模型列表";content.addView(this);setOnClickListener {
            isEnabled=false;catalogStatus.text="正在读取模型资料…"
            Thread {val success=runCatching {catalog.refresh()}.isSuccess;runOnUiThread {isEnabled=true;if(success)updateModels() else catalogStatus.text="暂时无法刷新，继续使用已缓存模型资料"}}.start()
        }}
        provider.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?)=Unit
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,itemId:Long) {
                val go=position==0;modelPicker.visibility=if(go)View.VISIBLE else View.GONE;refreshModels.visibility=modelPicker.visibility;catalogStatus.visibility=modelPicker.visibility
                protocol.visibility=if(go)View.GONE else View.VISIBLE;model.visibility=protocol.visibility;name.visibility=protocol.visibility;url.visibility=protocol.visibility;contextBudget.isEnabled=!go
                if(go){url.setText("https://opencode.ai/zen/go/v1");updateModels()}
            }
        }
        val headerName = field("自定义凭据头（可选）", "headerName")
        val headerTemplate = field("凭据格式，例如 Bearer {{credential}}（可选）", "headerTemplate")
        label("模型 API Key：${vault.status("model")}；留空保留，勾选后删除")
        val modelKey = field("API Key（留空保留）", "", true)
        val removeModel = CheckBox(this).apply { text = "删除已保存模型 Key"; content.addView(this) }
        val advanced=listOf(contextBudget,maxOutput,imageSupport,headerName,headerTemplate)
        advanced.forEach {it.visibility=View.GONE}
        content.addView(Button(this).apply {text="高级选项 ›";setOnClickListener {val expand=advanced.first().visibility!=View.VISIBLE;advanced.forEach {it.visibility=if(expand)View.VISIBLE else View.GONE};text=if(expand)"收起高级选项 ‹" else "高级选项 ›"}})
        fun currentModelDraft():ModelConnectionTest.Draft {
            val draft=ModelConnectionTest.Draft(url.text.toString().trim(),ModelCatalog.protocols[protocol.selectedItemPosition],model.text.toString().trim(),
                if(removeModel.isChecked)"" else modelKey.text.toString().ifBlank {vault.readNative("model").orEmpty()},headerName.text.toString(),headerTemplate.text.toString())
            val uri=runCatching {URI(draft.baseUrl)}.getOrNull()
            return if(uri?.scheme=="https" && uri.host=="opencode.ai" && uri.port in setOf(-1,443) && uri.path.trimEnd('/')=="/zen/go/v1")
                draft.withProfile(catalog.models().objects().find {it.optString("id")==draft.model && it.optString("protocol")==draft.protocol}) else draft
        }
        val testStatus=TextView(this).apply {text="测试当前页面填写的配置；空白 Key 使用已保存 Key。草稿不会自动保存。";setPadding(0,12,0,12);content.addView(this)}
        val test=Button(this).apply {text="测试当前填写的连接";content.addView(this)}
        test.setOnClickListener {
            val draft=currentModelDraft()
            if(runCatching {draft.validate()}.isFailure){testStatus.text="请填写有效的地址、接口类型、模型 ID 和 API Key；草稿未保存";return@setOnClickListener}
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
                web.evaluateJavascript("window.runNativeProbe(${JSONObject.quote(token)}, ${JSONObject.quote(sid)})",null)
                Toast.makeText(this,"正在检查已保存的配置；当前草稿不会参与",Toast.LENGTH_LONG).show()
            } catch(_:Exception){Toast.makeText(this,"请先保存有效配置，并等待当前连接检查结束",Toast.LENGTH_LONG).show()}
        }
        val probeStatus=TextView(this).apply {text="已保存配置：${host.modelStatus()}";content.addView(this)}
        val statusPoll=object:Runnable {override fun run(){if(probeStatus.isAttachedToWindow){val value="已保存配置：${host.modelStatus()}";if(probeStatus.text.toString()!=value)probeStatus.text=value;probeStatus.postDelayed(this,1000)}}}
        probeStatus.postDelayed(statusPoll,1000)
        settingsPage("模型服务",content,save={
            try {
                require(!host.hasActiveRuns())
                ModelConnectionTest.Draft(url.text.toString().trim(),ModelCatalog.protocols[protocol.selectedItemPosition],model.text.toString().trim(),
                    if(removeModel.isChecked)"removed" else modelKey.text.toString().ifBlank {vault.readNative("model").orEmpty()},headerName.text.toString(),headerTemplate.text.toString()).validate()
                val budget=contextBudget.text.toString().ifBlank {"32768"}.toInt()
                val outputBudget=maxOutput.text.toString().ifBlank {"2048"}.toInt()
                require(budget in 4096..4000000 && outputBudget in 16..16384 && outputBudget<budget)
                val nextRevision=prefs.getInt("revision",0)+1
                check(prefs.edit().putInt("revision",nextRevision).putString("probe","untested").remove("probeRevision").commit())
                if(removeModel.isChecked)vault.remove("model") else if(modelKey.length()>0)vault.put("model",modelKey.text.toString())
                check(prefs.edit().putBoolean("supportsImages",imageSupport.isChecked).putString("provider",if(provider.selectedItemPosition==0)"opencode-go" else "custom").putString("name",name.text.toString().take(80)).putInt("protocol",protocol.selectedItemPosition)
                    .putString("url",url.text.toString().trim()).putString("model",model.text.toString().trim()).putString("contextBudgetText",contextBudget.text.toString()).putString("maxOutputText",maxOutput.text.toString())
                    .putInt("contextBudget",budget).putInt("maxOutput",outputBudget).putString("headerName",headerName.text.toString()).putString("headerTemplate",headerTemplate.text.toString()).commit())
                modelKey.text.clear();closeSettings();recreate();Toast.makeText(this,"设置已保存；新模型首次使用会检查图片与工具能力",Toast.LENGTH_LONG).show()
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
                searchKey.text.clear();closeSettings();recreate()
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
