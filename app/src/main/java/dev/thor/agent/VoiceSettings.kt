package dev.thor.agent

import android.app.Activity
import android.text.InputType
import android.view.View
import android.widget.*

fun showVoiceSettings(activity:Activity,onSaved:()->Unit={},present:(View,()->Unit)->Unit) {
    val prefs=activity.getSharedPreferences("voice",0)
    val vault=CredentialVault(activity)
    val form=SettingsForm(activity)
    val ui=form.ui
    val service=form.section(form.rail,"语音服务")
    service.addView(ui.text("火山 / 豆包",18f))
    form.note(service,if(form.compact)"流式语音识别" else "流式语音识别\n按住说话，松手发送。")
    val standalone=CheckBox(activity).apply {text="始终使用独立语音凭据";isChecked=prefs.getBoolean("standalone",false)}
    form.move(service,standalone)
    form.note(service,"默认跟随当前火山 AgentPlan，共享套餐 Key 和 ASR 2.0；其他模型服务继续使用下方独立语音配置。")
    val plan=VolcAgentPlan.selected(activity)
    if(plan!=null)form.note(service,"当前 AgentPlan：${plan.optString("name")} · ${if(VolcAgentPlan.credential(activity)!=null)"Key 已保存" else "请在模型服务填写 Key"}")
    val method=form.section(form.rail,"接入方式")
    val auth=Spinner(activity).apply {
        tag="voice-auth"
        adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,arrayOf("新版 Key","旧版 Token"))
        setSelection(prefs.getInt("auth",0).coerceIn(0,1))
    }
    form.move(method,auth)
    form.note(method,if(form.compact)"使用语音专用凭据" else "独立配置使用语音控制台凭据；AgentPlan 自动共享套餐 Key。")
    val credentials=form.section(form.main,"连接凭据")
    val currentStatus=ui.text("",13f,true).apply {tag="voice-key-status"}
    credentials.addView(currentStatus)
    fun secret(hint:String,tagValue:String)=EditText(activity).apply {
        this.hint=hint;tag=tagValue;setSingleLine()
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;isSaveEnabled=false
    }
    val api=secret("输入语音 API Key（留空保留）","voice-api-key")
    val app=EditText(activity).apply {hint="输入 App ID";tag="voice-app-id";setSingleLine();setText(prefs.getString("appId",""))}
    val access=secret("输入 Access Token（留空保留）","voice-access-token")
    val apiGroup=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;credentials.addView(this)}
    val legacyGroup=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;credentials.addView(this)}
    form.field(apiGroup,"API Key",api)
    form.field(legacyGroup,"App ID",app);form.field(legacyGroup,"Access Token",access)
    var removed=false
    var resetCheck:()->Unit={}
    val remove=ui.button("删除已保存的语音密钥",{}).apply {tag="voice-remove-keys"}
    credentials.addView(remove)
    fun refreshAuth() {
        val modern=auth.selectedItemPosition==0
        apiGroup.visibility=if(modern)View.VISIBLE else View.GONE
        legacyGroup.visibility=if(modern)View.GONE else View.VISIBLE
        val status=vault.status(if(modern)"voice.api" else "voice.access")
        currentStatus.text=if(removed)"已标记删除，保存后生效" else if(status=="未保存")"尚未配置，请填写语音凭据" else "$status · 留空继续使用"
        api.isEnabled=!removed;access.isEnabled=!removed
        resetCheck()
        remove.text=if(removed)"撤销删除" else "删除已保存的语音密钥"
    }
    auth.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent:AdapterView<*>?)=Unit
        override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long)=refreshAuth()
    }
    remove.setOnClickListener {removed=!removed;refreshAuth()}
    refreshAuth()
    val resources=form.section(if(form.compact)form.rail else form.main,"识别资源")
    val options=arrayOf("volc.bigasr.sauc.duration","volc.bigasr.sauc.concurrent","volc.seedasr.sauc.duration","volc.seedasr.sauc.concurrent")
    val resource=Spinner(activity).apply {
        tag="voice-resource"
        adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,arrayOf("1.0 时长","1.0 并发","2.0 时长","2.0 并发"))
        setSelection(options.indexOf(prefs.getString("resource",options[0])).coerceAtLeast(0))
    }
    form.move(resources,resource)
    form.note(resources,if(form.compact)"选择已开通的资源" else "选择控制台已开通的版本和计费方式。")
    val check=form.section(form.main,"配置检查")
    val checkStatus=ui.text("保存后，可在聊天中按住话筒试用。",13f,true).apply {tag="voice-check-status"}
    check.addView(ui.button("检查当前配置") {
        val modern=auth.selectedItemPosition==0
        val configured=if(removed)false else if(modern)api.text.isNotBlank() || !vault.readNative("voice.api").isNullOrBlank()
            else app.text.isNotBlank() && (access.text.isNotBlank() || !vault.readNative("voice.access").isNullOrBlank())
        checkStatus.text=if(!standalone.isChecked && plan!=null) {if(VolcAgentPlan.credential(activity)!=null)"✓ 将使用当前 AgentPlan 的 Key、ASR 2.0 和套餐专属入口；保存后生效。" else "请在模型服务保存 AgentPlan Key。"} else if(configured)"✓ 必填资料已齐全。此检查不连接服务、不录音；保存后可在聊天中试用。" else if(removed)"语音密钥已标记删除，请撤销删除或重新配置。" else if(modern)"请填写语音 API Key。" else "请填写 App ID 和 Access Token。"
    }.apply {tag="voice-check"})
    check.addView(checkStatus)
    check.addView(LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;tag="voice-permission-slot"})
    fun refreshSource() {
        val shared=plan!=null && !standalone.isChecked
        method.visibility=if(shared)View.GONE else View.VISIBLE
        credentials.visibility=if(shared)View.GONE else View.VISIBLE
        resources.visibility=if(shared)View.GONE else View.VISIBLE
        checkStatus.text=if(shared) {
            if(VolcAgentPlan.credential(activity)!=null)"✓ 已共享当前 AgentPlan Key，使用 ASR 2.0，无需填写独立语音凭据。" else "请先在模型服务保存当前 AgentPlan Key。"
        } else "可检查必填资料；保存后在聊天中试用。"
    }
    standalone.setOnCheckedChangeListener {_,_->refreshSource()}
    resetCheck={refreshSource()}
    refreshSource()
    for(input in listOf(api,app,access))input.addTextChangedListener(object:android.text.TextWatcher {
        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=resetCheck()
        override fun afterTextChanged(s:android.text.Editable?){}
    })
    if(plan!=null) {
        val speech=form.section(form.main,"AgentPlan 语音合成 · TTS 2.0")
        form.note(speech,"共享当前套餐 Key。输入要朗读的文字，合成后在本机播放；每次调用消耗套餐额度。")
        val words=EditText(activity).apply {hint="输入朗读文字（最多 1000 字）";setText("你好，我是 Loki，火山 AgentPlan 语音已接入。")}
        form.move(speech,words)
        val state=ui.text("豆包语音合成 2.0 · VV 音色",13f,true)
        var player:VolcPlanSpeech?=null
        val stop=ui.button("停止播放") {player?.close();player=null;state.text="已停止"}
        lateinit var play:Button
        play=ui.button("合成并播放") {
            val credential=VolcAgentPlan.credential(activity)
            val value=words.text.toString()
            if(credential==null || value.isBlank() || value.codePointCount(0,value.length)>1000)state.text="请保存当前 AgentPlan Key，输入 1–1000 字。"
            else {
                player?.close();val current=VolcPlanSpeech();player=current;play.isEnabled=false;state.text="正在合成与播放…"
                Thread {
                    val success=runCatching {current.play(credential,value)}.isSuccess
                    current.close()
                    activity.runOnUiThread {if(player===current && state.isAttachedToWindow){player=null;play.isEnabled=true;state.text=if(success)"播放完成" else "合成失败，请检查套餐 Key、网络和额度。"} else if(play.isAttachedToWindow)play.isEnabled=true}
                }.start()
            }
        }
        speech.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View){}
            override fun onViewDetachedFromWindow(v:View){player?.close();player=null}
        })
        form.move(speech,play);form.move(speech,stop);form.move(speech,state)
    }
    val help=form.section(form.rail,"接入说明")
    val helpBody=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;visibility=View.GONE}
    help.addView(ui.button("查看接入与隐私说明") {helpBody.visibility=if(helpBody.visibility==View.VISIBLE)View.GONE else View.VISIBLE})
    help.addView(helpBody)
    form.note(helpBody,"独立配置使用语音控制台凭据；AgentPlan 自动共享套餐 Key。需开通流式识别资源。固定端点：${VolcAsrProtocol.ENDPOINT}")
    form.note(helpBody,"录音仅在前台进行，不保存音频文件。识别会附带当前会话最近几轮消息；开启屏幕感知时，还会附带上屏应用和可用的窗口标题，帮助识别专有词汇。")
    form.note(form.rail,"右上角保存后生效。")
    present(form.root) {
        try {
            require(api.length()<=8192 && access.length()<=8192 && app.length()<=200)
            for(value in listOf(api.text.toString(),access.text.toString()))require('\r' !in value && '\n' !in value)
            if(removed) {vault.remove("voice.api");vault.remove("voice.access")} else {
                if(api.text.isNotBlank())vault.put("voice.api",api.text.toString().trim())
                if(access.text.isNotBlank())vault.put("voice.access",access.text.toString().trim())
            }
            check(prefs.edit().putBoolean("standalone",standalone.isChecked).putInt("auth",auth.selectedItemPosition).putString("appId",app.text.toString().trim()).putString("resource",options[resource.selectedItemPosition]).commit())
            api.text.clear();access.text.clear();removed=false;refreshAuth();checkStatus.text="✓ 已保存，可在聊天中按住话筒试用。";Toast.makeText(activity,"语音设置已保存",Toast.LENGTH_SHORT).show();onSaved()
        } catch(_:Exception) {Toast.makeText(activity,"语音设置保存失败，请检查输入或密钥存储",Toast.LENGTH_LONG).show()}
    }
}
