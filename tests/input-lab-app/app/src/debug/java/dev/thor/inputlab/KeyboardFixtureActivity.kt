package dev.thor.inputlab

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID

/** Independent DEBUG receiver. Setup can change selection/geometry, never write
 * text or inject keys after prepare. Real Android callbacks are the evidence. */
class KeyboardFixtureActivity:Activity() {
    companion object {@Volatile var current:WeakReference<KeyboardFixtureActivity>?=null}
    private lateinit var normal:EditText
    private lateinit var password:EditText
    private lateinit var disabled:EditText
    private lateinit var readOnly:TextView
    private var journal:Journal?=null
    private var run="";private var attempt="";private var sequence=0L
    private var changes=0;private var editorActions=0;private var submissions=0;private var keys=0
    private var sealed=false
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);current=WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(24));setBackgroundColor(Color.WHITE)}
        fun field(idValue:Int,label:String,initial:String):EditText {
            body.addView(TextView(this).apply {text=label;textSize=18f;setTextColor(Color.BLACK)},LinearLayout.LayoutParams(-1,dp(32)))
            return EditText(this).apply {
                id=idValue;setText(initial);isSingleLine=true;showSoftInputOnFocus=false
                imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                setOnFocusChangeListener {_,focused->record("focus",mapOf("field" to label,"focused" to focused),"android_focus_callback")}
                setOnEditorActionListener {_,action,event->editorActions++;record("editor_action",mapOf("actionId" to action,"keyCode" to event?.keyCode),"android_editor");false}
                addTextChangedListener(object:TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                        if(journal!=null){changes++;record("text_changed",mapOf("field" to label,"length" to (s?.length ?: 0),"start" to start,"before" to before,"count" to count),"android_text_watcher")}
                    }
                    override fun afterTextChanged(s:Editable?){}
                })
                body.addView(this,LinearLayout.LayoutParams(-1,dp(64)))
            }
        }
        normal=field(R.id.keyboard_normal,"Normal editor","seed")
        password=field(R.id.keyboard_password,"Password editor","").apply {inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD}
        disabled=field(R.id.keyboard_disabled,"Disabled editor","disabled sentinel").apply {isEnabled=false}
        body.addView(TextView(this).apply {text="Read only editor";textSize=18f;setTextColor(Color.BLACK)},LinearLayout.LayoutParams(-1,dp(32)))
        readOnly=TextView(this).apply {id=R.id.keyboard_readonly;text="read only sentinel";textSize=18f;setTextColor(Color.BLACK);setTextIsSelectable(true)}
        body.addView(readOnly,LinearLayout.LayoutParams(-1,dp(64)))
        body.addView(Button(this).apply {id=R.id.keyboard_submit;text="Submit keyboard fixture";setOnClickListener {submissions++;record("submit",mapOf("count" to submissions),"receiver_click")}},LinearLayout.LayoutParams(-1,dp(64)))
        setContentView(body);normal.requestFocus();normal.setSelection(normal.text.length)
    }
    fun prepare(testRun:String) {
        check(journal==null);require(testRun.length in 1..128);run=testRun;attempt=UUID.randomUUID().toString()
        journal=Journal(File(filesDir,"keyboard-reports/$attempt"),10000,4L*1024*1024,128)
        record("prepared",emptyMap(),"fixture_setup")
    }
    fun configure(change:String) {
        check(journal!=null && !sealed)
        when(change) {
            "select_emoji"->{check(normal.text.toString()=="中🙂文");normal.requestFocus();normal.setSelection(1,3)}
            "focus_normal"->normal.requestFocus()
            "move"->{val lp=normal.layoutParams as LinearLayout.LayoutParams;lp.topMargin+=dp(72);normal.layoutParams=lp}
            else->error("Unknown keyboard fixture setup")
        }
        record("configure",mapOf("change" to change),"fixture_setup")
    }
    override fun dispatchKeyEvent(e:KeyEvent):Boolean {
        keys++;record("key",mapOf("action" to e.action,"keyCode" to e.keyCode,"metaState" to e.metaState,"source" to e.source,"deviceId" to e.deviceId),"android_key_dispatch")
        return super.dispatchKeyEvent(e)
    }
    private fun record(kind:String,data:Map<String,Any?>,origin:String) {if(!sealed)journal?.append(mapOf("sequence" to ++sequence,"eventKind" to kind,"recordOrigin" to origin,"uptimeMs" to SystemClock.uptimeMillis(),"data" to data))}
    fun snapshot():String {
        val log=journal ?: error("Not prepared");val prefix=log.snapshot()
        return Json.encode(mapOf("attemptId" to attempt,"testRunId" to run,"evidenceMode" to "manual_platform_api","agentRunId" to "no-agent:$run","agentAnswerVerified" to false,
            "displayId" to display?.displayId,"windowType" to window.attributes.type,"normalFocused" to normal.hasFocus(),"passwordFocused" to password.hasFocus(),"disabledFocused" to disabled.hasFocus(),"readOnlyFocused" to readOnly.hasFocus(),"normalText" to normal.text.toString(),"selectionStart" to normal.selectionStart,"selectionEnd" to normal.selectionEnd,
            "passwordLength" to password.text.length,"disabledText" to disabled.text.toString(),"readOnlyText" to readOnly.text.toString(),"textChangeCount" to changes,"editorActionCount" to editorActions,"submissionCount" to submissions,"keyEventCount" to keys,"sealed" to sealed,
            "evidence" to mapOf("relativePath" to "keyboard-reports/$attempt/events.jsonl","eventPrefixBytes" to prefix.first,"eventPrefixSha256" to prefix.second,"eventCount" to sequence,"incomplete" to log.incomplete.get(),"droppedSamples" to log.dropped.get(),"sealed" to sealed)))
    }
    fun release():String {check(!sealed);record("release",emptyMap(),"fixture_setup");sealed=true;val result=snapshot();journal!!.close();finish();return result}
    override fun onDestroy(){if(!sealed){sealed=true;journal?.close()};if(current?.get()===this)current=null;super.onDestroy()}
}
