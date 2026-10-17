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
    private fun showModelProviders() {
        val store=ProviderStore(this)
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,16,24,24)}
        content.addView(TextView(this).apply {text="保存多套服务配置，一次使用一个 Provider。在这里切换后直接使用已保存的 API Key；发送框可选择当前服务的模型。";setPadding(0,0,0,16)})
        for(config in store.list()) {
            val id=config.getString("id")
            val row=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(0,12,0,16)}
            row.addView(TextView(this).apply {text=config.getString("name")+if(store.defaultId()==id)" · 当前使用" else "";textSize=18f;setTypeface(null,android.graphics.Typeface.BOLD)})
            row.addView(TextView(this).apply {text="${providerKindLabel(config.getString("kind"))} · ${config.getString("model")}\nAPI Key：${store.credentialStatus(id)}";setPadding(0,6,0,8)})
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
            row.addView(buttons);content.addView(row)
            content.addView(View(this).apply {setBackgroundColor(0x22888888)},LinearLayout.LayoutParams(-1,1))
        }
        content.addView(Button(this).apply {text="添加模型服务";tag="provider-add";isEnabled=store.list().size<ProviderStore.MAX_PROVIDERS;setOnClickListener {
            val kinds=listOf("opencode-go","deepseek","custom")
            AlertDialog.Builder(this@MainActivity).setTitle("添加模型服务").setItems(kinds.map(::providerKindLabel).toTypedArray()) {_,index->showProviderEditor(null,kinds[index])}.show()
        }})
        settingsPage("模型服务",content,back={showSettings()})
    }
    private fun providerKindLabel(kind:String)=when(kind){"opencode-go"->"OpenCode Go";"deepseek"->"DeepSeek 官方";else->"自定义 API Key"}
    private fun changeProviderSettings(action:()->Unit):Boolean = try {
        require(!host.hasActiveRuns()) {"请等待活动任务完成后再修改服务配置"}
        action()
        val prefs=getSharedPreferences("profiles",MODE_PRIVATE)
        check(prefs.edit().putInt("revision",prefs.getInt("revision",0)+1).commit())
        reloadRuntime();true
    }catch(_:Exception){Toast.makeText(this,"修改未完成：请等待活动任务结束，并检查服务配置",Toast.LENGTH_LONG).show();false}
    private fun showProviderEditor(saved:JSONObject?,kind:String) {
        val store=ProviderStore(this);val custom=kind=="custom"
        val config=saved ?: JSONObject().put("kind",kind).put("enabled",true).put("name",providerKindLabel(kind)).put("protocol",0)
            .put("url",when(kind){"opencode-go"->"https://opencode.ai/zen/go/v1";"deepseek"->DeepSeekCatalog.BASE_URL;else->""})
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
        val enabled=CheckBox(this).apply {text="允许使用此配置";isChecked=config.optBoolean("enabled",true);content.addView(this)}
        val protocol=Spinner(this).apply {adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("OpenAI Responses","Anthropic Messages","OpenAI Chat Completions"));setSelection(config.optInt("protocol",0));content.addView(this);visibility=if(custom)View.VISIBLE else View.GONE}
        val url=field("模型基础 URL（HTTP / HTTPS）","url").apply {visibility=if(custom)View.VISIBLE else View.GONE}
        val model=field("模型 ID","model").apply {visibility=if(custom)View.VISIBLE else View.GONE}
        val contextBudget=field("上下文上限（tokens）","contextBudgetText").apply {if(text.isBlank())setText(config.optInt("contextBudget",32768).toString());inputType=InputType.TYPE_CLASS_NUMBER}
        val maxOutput=field("输出上限（tokens；留空跟随模型）","maxOutputText").apply {inputType=InputType.TYPE_CLASS_NUMBER}
        val outputHelp=text(if(custom)"输出上限留空使用 2048。" else "输出上限留空自动跟随所选模型，包含思考与正文。")
        val imageSupport=CheckBox(this).apply {text="支持图片输入";isChecked=config.optBoolean("supportsImages",true);content.addView(this)}
        val catalog=ModelCatalog(this);val deepseek=DeepSeekCatalog(this)
        val modelPicker=Spinner(this).apply {tag="model-picker";contentDescription="选择模型";visibility=if(custom)View.GONE else View.VISIBLE;content.addView(this)}
        val catalogStatus=text("").apply {tag="model-catalog-status";visibility=modelPicker.visibility}
        val refreshModels=Button(this).apply {text=if(kind=="deepseek")"刷新 DeepSeek 官方模型列表" else "刷新 OpenCode Go 模型列表";visibility=modelPicker.visibility;content.addView(this)}
        val headerName=field("自定义凭据头（可选）","headerName")
        val headerTemplate=field("凭据格式，例如 Bearer {{credential}}（可选）","headerTemplate")
        text("API Key：${id?.let {store.credentialStatus(it)} ?: "未保存"}；留空保留此服务已保存的 Key")
        val key=field("此服务的 API Key（留空保留）","",true).apply {tag="provider-api-key"}
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
        content.addView(Button(this).apply {text="高级选项 ›";setOnClickListener {advancedExpanded=!advancedExpanded;renderAdvanced();text=if(advancedExpanded)"收起高级选项 ‹" else "高级选项 ›"}})
        fun metadata():JSONObject?=(when(kind){"deepseek"->deepseek.models();"opencode-go"->catalog.models();else->null})?.objects()?.firstOrNull {it.getString("id")==model.text.toString() && it.getString("protocol")==ModelCatalog.protocols[protocol.selectedItemPosition]}
        fun updateModels() {
            if(custom)return
            val choices=(if(kind=="deepseek")deepseek.models() else catalog.models()).objects()
            modelPicker.onItemSelectedListener=null
            modelPicker.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,choices.map {it.getString("name")})
            fun applyChoice(index:Int) {
                val choice=choices[index];model.setText(choice.getString("id"));protocol.setSelection(ModelCatalog.protocols.indexOf(choice.getString("protocol")))
                contextBudget.setText(choice.getInt("contextWindow").toString());imageSupport.isChecked=choice.optJSONArray("input")?.let {a->(0 until a.length()).any {a.optString(it)=="image"}}==true
                catalogStatus.text="${if(kind=="deepseek")deepseek.source() else catalog.source()}\n上下文 ${choice.getInt("contextWindow")} · 输出容量 ${choice.getInt("maxOutput")} tokens\n思考强度 ${choice.getJSONArray("efforts").join(" / ")}"
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
            Thread {val success=runCatching {if(kind=="deepseek")deepseek.refresh(credential) else catalog.refresh()}.isSuccess
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
        settingsPage(if(saved==null)"添加模型服务" else "编辑模型服务",content,save={
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
                if(changeProviderSettings {store.save(value,key.text.toString(),removeKey.isChecked)}){key.text.clear();showModelProviders()}
            }catch(_:Exception){testStatus.text="保存失败：请检查服务名称、地址、模型、预算和 Key；其他服务配置保持不变"}
        },back={key.text.clear();showModelProviders()})
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
        if(section=="model") { showModelProviders(); return }
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
