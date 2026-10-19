package dev.thor.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.text.InputType
import android.view.View
import android.widget.*
import java.net.URI

/** One service at a time; all changes, including credential removal, remain drafts until Save. */
internal fun showSearchSettings(
    activity: Activity,
    canSave: () -> Boolean,
    onModels: () -> Unit,
    onSaved: () -> Unit,
    showPage: (LinearLayout, () -> Unit) -> Unit
) {
    val ui=SettingsUi(activity)
    val compact=activity.resources.configuration.let {it.screenWidthDp>it.screenHeightDp && it.screenHeightDp<600}
    val prefs=activity.getSharedPreferences("profiles",0)
    val vault=CredentialVault(activity)
    val available=SearchServices.available(activity)
    var selected=prefs.getString("searchBackend","自动选择").orEmpty().let {old->SearchServices.names.firstOrNull {it.equals(old,true)} ?: "自动选择"}
    val drafts=mutableMapOf("Tavily" to "","Brave" to "")
    val removed=mutableSetOf<String>()
    var braveUrl=prefs.getString("searchUrl",SearchServices.endpoint("Brave")).orEmpty().ifBlank {SearchServices.endpoint("Brave")}
    var advanced=false
    var manage=false
    var resetTest:()->Unit={}
    var generation=0
    val content=ui.column()
    fun ref(name:String)=if(name=="Tavily")"search.tavily" else "search"
    fun key(name:String)=if(name in removed)"" else drafts[name].orEmpty().ifBlank {runCatching {vault.readNative(ref(name))}.getOrNull().orEmpty()}
    fun services()=available.filter {it.backend=="DeepSeek"}+listOf("Tavily","Brave").mapNotNull {name->key(name).takeIf {it.isNotBlank()}?.let {SearchServices.Service(name,if(name=="Brave")braveUrl else SearchServices.endpoint(name),it)}}
    fun inset(parent:LinearLayout)=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(ui.dp(18),ui.dp(if(compact)12 else 16),ui.dp(18),ui.dp(if(compact)12 else 18));parent.addView(this)}
    fun note(parent:LinearLayout,value:String)=parent.addView(ui.text(value,13f,true).apply {setPadding(0,ui.dp(8),0,0)})
    fun heading(parent:LinearLayout,value:String) {
        if(compact)parent.addView(ui.text(value,13f,true).apply {setPadding(ui.dp(6),ui.dp(8),0,ui.dp(8))})
        else ui.heading(parent,value)
    }
    lateinit var render:()->Unit
    render={
        generation++
        resetTest={}
        content.removeAllViews()
        val chooser:LinearLayout
        val details:LinearLayout
        if(compact) {
            val columns=LinearLayout(activity).apply {orientation=LinearLayout.HORIZONTAL}
            chooser=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(0,0,ui.dp(14),0)}
            details=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
            columns.addView(chooser,LinearLayout.LayoutParams(0,-2,.36f))
            columns.addView(details,LinearLayout.LayoutParams(0,-2,.64f))
            content.addView(columns)
            heading(chooser,"搜索服务 · 点击更换")
        } else {
            chooser=content;details=content
            content.addView(ui.text("让回答参考实时网页",26f).apply {setPadding(ui.dp(4),ui.dp(14),0,ui.dp(6))})
            content.addView(ui.text("选择一个搜索服务，保存后生效。",14f,true).apply {setPadding(ui.dp(4),0,0,ui.dp(16))})
        }
        val selection=ui.card(chooser)
        val pickService:()->Unit={
            val labels=arrayOf("自动选择 · 推荐","DeepSeek · 共享模型 Key","Tavily","Brave","关闭联网搜索")
            AlertDialog.Builder(activity).setTitle("选择搜索服务").setSingleChoiceItems(labels,SearchServices.names.indexOf(selected)) {dialog,index->
                selected=SearchServices.names[index];advanced=false;dialog.dismiss();render()
            }.setNegativeButton("取消",null).show()
        }
        if(compact) {
            val body=inset(selection)
            body.addView(ui.button("${if(selected=="关闭")"关闭搜索" else selected}  ▾",pickService).apply {tag="search-service-picker";textSize=17f},LinearLayout.LayoutParams(-1,ui.dp(48)))
            note(body,when(selected){"自动选择"->"自动使用已配置服务";"DeepSeek"->"共享官方模型 Key";"关闭"->"不提供网页搜索工具";else->"独立搜索 Key"})
        } else {
            ui.row(selection,when(selected){"自动选择"->"自动选择 · 推荐";"关闭"->"关闭联网搜索";else->selected},
                when(selected){"自动选择"->"优先使用已配置的 DeepSeek，其次 Tavily、Brave。";"关闭"->"不向模型提供网页搜索工具。";"DeepSeek"->"与 DeepSeek 官方模型共享 API Key。";else->"使用 $selected 提供网页搜索。"},"⌕",pickService)
            selection.getChildAt(0).tag="search-service-picker"
        }
        when(selected) {
            "自动选择"->{
                heading(details,"自动选择将使用")
                val body=inset(ui.card(details));val current=SearchServices.choose(selected,services())
                body.addView(ui.text(current?.let {"${it.backend} · 已配置"} ?: "还没有可用的搜索服务",18f))
                note(body,if(current==null)"推荐先配置 DeepSeek 官方模型，一个 Key 同时用于模型和搜索。也可以单独配置 Tavily 或 Brave。" else if(current.backend=="DeepSeek")"已共享官方模型 Key，无需再填写。" else "已使用保存的 API Key。连接是否可用，请点击下方测试。")
                body.addView(ui.button(if(manage)"收起服务管理" else "配置或管理搜索服务") {manage=!manage;render()})
                if(manage) {
                    heading(details,"管理服务")
                    val card=ui.card(details)
                    for(name in listOf("DeepSeek","Tavily","Brave")) {
                        val configured=services().any {it.backend==name}
                        ui.row(card,"$name · ${if(configured)"已配置" else "未配置"}",if(name=="DeepSeek")"共享官方模型 Key" else "填写或更新 API Key",if(configured)"✓" else "+") {selected=name;render()}
                    }
                }
            }
            "DeepSeek"->{
                heading(details,"模型与搜索共用一个 Key")
                val body=inset(ui.card(details));val configured=services().any {it.backend=="DeepSeek"}
                body.addView(ui.text(if(configured)"✓ 已共享 DeepSeek 官方 Key" else "先连接 DeepSeek 官方模型",18f))
                note(body,if(configured)"无需重复配置。搜索和网页读取会使用已保存的官方模型 Key。" else "在模型服务中添加 DeepSeek 官方服务并保存 API Key，搜索会自动使用同一个 Key。")
                body.addView(ui.button(if(configured)"管理 DeepSeek 模型配置" else "前往配置模型服务",onModels).apply {setPadding(0,ui.dp(8),0,0)})
                note(body,"第三方平台上的 DeepSeek 模型不能共享此 Key。")
            }
            "Tavily","Brave"->{
                val name=selected
                heading(details,"$name 配置")
                val body=inset(ui.card(details))
                body.addView(ui.text("API Key",16f))
                val saved=available.any {it.backend==name}
                val keyStatus=ui.text(if(name in removed)"已标记删除，保存后生效" else if(saved)"已加密保存 · 留空继续使用" else "尚未配置 · 填写后保存即可使用",13f,true)
                body.addView(keyStatus)
                val input=EditText(activity).apply {
                    tag="search-key-$name";hint=if(saved)"输入新的 Key 以替换" else "输入 $name API Key"
                    setSingleLine(true);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;isSaveEnabled=false
                    setTextColor(ui.ink);setHintTextColor(ui.muted);backgroundTintList=ColorStateList.valueOf(ui.accent);minimumHeight=ui.dp(56)
                    setText(drafts[name]);body.addView(this,LinearLayout.LayoutParams(-1,-2))
                }
                var removeButton:Button?=null
                input.addTextChangedListener(object:android.text.TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){generation++;resetTest();drafts[name]=s.toString();if(!s.isNullOrBlank()){removed.remove(name);removeButton?.text="删除已保存的 Key";keyStatus.text="新的 Key 尚未保存"}}
                    override fun afterTextChanged(s:android.text.Editable?){}
                })
                if(saved || name in removed){removeButton=ui.button(if(name in removed)"撤销删除" else "删除已保存的 Key") {
                    if(name in removed)removed.remove(name) else {removed.add(name);drafts[name]=""};render()
                }.apply {tag="search-remove-$name"};body.addView(removeButton)}
                if(name=="Brave") {
                    body.addView(ui.button(if(advanced)"收起高级设置" else "高级设置 · 自定义 API 地址") {advanced=!advanced;render()})
                    if(advanced) {
                        note(body,"默认地址通常无需修改。")
                        body.addView(EditText(activity).apply {
                            tag="search-brave-url";hint="搜索 API 地址";setSingleLine(true);setText(braveUrl)
                            setTextColor(ui.ink);setHintTextColor(ui.muted);backgroundTintList=ColorStateList.valueOf(ui.accent)
                            addTextChangedListener(object:android.text.TextWatcher {
                                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){generation++;resetTest();braveUrl=s.toString()}
                                override fun afterTextChanged(s:android.text.Editable?){}
                            })
                        },LinearLayout.LayoutParams(-1,ui.dp(56)))
                    }
                }
            }
            else->{val body=inset(ui.card(details));body.addView(ui.text("网页搜索已关闭",18f));note(body,"保存后生效。仍可使用已知网址读取网页；会话中的搜索能力开关也会影响工具是否可用。")}
        }
        if(selected!="关闭") {
            heading(details,"连接测试")
            val body=inset(ui.card(details))
            val status=ui.text("使用当前页面的配置发起一次真实搜索，测试不会自动保存。",13f,true).apply {tag="search-test-status"}
            lateinit var test:Button
            test=ui.button("测试当前搜索连接") {
                val service=SearchServices.choose(selected,services())
                if(service==null) {status.text=if(selected=="DeepSeek")"请先在模型服务中保存 DeepSeek 官方 API Key。" else "请先填写 API Key，或选择一个已配置的服务。"}
                else {
                    val token=generation
                    test.isEnabled=false;status.text="正在测试 ${service.backend}…"
                    Thread {val result=SearchConnectionTest.run(service.url,service.key,service.backend)
                        activity.runOnUiThread {if(token==generation && status.isAttachedToWindow){test.isEnabled=true;status.text="${if(result.success)"✓ 连接成功" else "连接失败"} · ${service.backend} · ${result.elapsedMs} ms\n${result.message.replace("，当前草稿未保存","")}"}}
                    }.start()
                }
            }.apply {tag="search-test"}
            resetTest={test.isEnabled=true;status.text="配置已更改，请重新测试。"}
            body.addView(test,LinearLayout.LayoutParams(-1,ui.dp(48)));body.addView(status.apply {setPadding(0,ui.dp(10),0,0)})
        }
        chooser.addView(ui.text(if(compact)"右上角保存后生效" else "更改完成后，点击右上角「保存」。",12f,true).apply {setPadding(ui.dp(8),ui.dp(12),0,0)})
    }
    render()
    showPage(content) {
        if(canSave())try {
            drafts.values.forEach {require(it.length<=8192 && '\r' !in it && '\n' !in it) {"API Key 格式无效"}}
            if(selected=="Brave" || braveUrl!=prefs.getString("searchUrl",SearchServices.endpoint("Brave"))) {
                val uri=URI(braveUrl.trim());require(uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.rawQuery==null && uri.fragment==null) {"请检查 Brave API 地址"}
            }
            for(name in listOf("Tavily","Brave"))if(name in removed)vault.remove(ref(name)) else if(drafts[name].orEmpty().isNotBlank())vault.put(ref(name),drafts.getValue(name))
            check(prefs.edit().putString("searchBackend",selected).putString("searchUrl",braveUrl.trim()).commit())
            drafts.clear();onSaved()
        }catch(_:Exception){Toast.makeText(activity,"保存失败，请检查 API Key 和 Brave API 地址",Toast.LENGTH_LONG).show()}
    }
}
