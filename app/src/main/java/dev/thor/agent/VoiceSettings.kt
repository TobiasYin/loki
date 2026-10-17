package dev.thor.agent

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.*

fun showVoiceSettings(activity:Activity,present:(android.view.View,()->Unit)->Unit) {
    val prefs=activity.getSharedPreferences("voice",0);val vault=CredentialVault(activity)
    val content=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(28,20,28,20)}
    fun label(text:String) {content.addView(TextView(activity).apply {this.text=text;setPadding(0,12,0,6)})}
    fun secret(hint:String)=EditText(activity).apply {this.hint=hint;setSingleLine();inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;isSaveEnabled=false;content.addView(this)}
    label("火山 / 豆包双向流式语音识别\n按住说话，松手等待最终结果后发送。录音仅在前台进行，不保存音频文件。")
    label("识别时会附带当前会话最近几轮消息作为提示；开启屏幕感知后，还会附带上屏应用和可用的窗口标题，帮助识别游戏名、应用名等词汇。")
    label("固定端点：${VolcAsrProtocol.ENDPOINT}\n需开通流式识别资源；模型 API Key 不能用于此服务。")
    val auth=Spinner(activity).apply {adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,arrayOf("新版控制台 · X-Api-Key","旧版控制台 · App ID + Access Token"));setSelection(prefs.getInt("auth",0));content.addView(this)}
    label("新版语音 Key：${vault.status("voice.api")}；留空保留")
    val api=secret("替换新版语音 API Key")
    val app=EditText(activity).apply {hint="旧版 App ID";setSingleLine();setText(prefs.getString("appId",""));content.addView(this)}
    label("旧版 Access Token：${vault.status("voice.access")}；留空保留")
    val access=secret("替换旧版 Access Token")
    val options=arrayOf("volc.bigasr.sauc.duration","volc.bigasr.sauc.concurrent","volc.seedasr.sauc.duration","volc.seedasr.sauc.concurrent")
    label("选择已开通的资源（1.0/2.0 小时版或并发版）")
    val resource=Spinner(activity).apply {adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,options);setSelection(options.indexOf(prefs.getString("resource",options[0])).coerceAtLeast(0));content.addView(this)}
    val remove=CheckBox(activity).apply {text="删除所有已保存语音密钥";content.addView(this)}
    present(content) {
        try {
            require(api.length()<=8192 && access.length()<=8192 && app.length()<=200)
            if(remove.isChecked) {vault.remove("voice.api");vault.remove("voice.access")} else {
                if(api.length()>0)vault.put("voice.api",api.text.toString().trim())
                if(access.length()>0)vault.put("voice.access",access.text.toString().trim())
            }
            check(prefs.edit().putInt("auth",auth.selectedItemPosition).putString("appId",app.text.toString().trim()).putString("resource",options[resource.selectedItemPosition]).commit())
            api.text.clear();access.text.clear();Toast.makeText(activity,"语音设置已保存",Toast.LENGTH_SHORT).show()
        } catch(_:Exception) {Toast.makeText(activity,"语音设置保存失败，请检查输入或密钥存储",Toast.LENGTH_LONG).show()}
    }
}
