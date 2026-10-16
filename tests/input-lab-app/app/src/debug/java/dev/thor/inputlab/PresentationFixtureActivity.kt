package dev.thor.inputlab

import android.app.Activity
import android.app.Presentation
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID

/** Debug-only foreign-UID receiver. The runner may prepare/show/protect/seal the
 * fixture, but has no button activation, gesture or result override method. */
class PresentationFixtureActivity:Activity() {
    companion object {@Volatile var current:WeakReference<PresentationFixtureActivity>?=null}
    private var panel:ReceiverPresentation?=null
    private var journal:Journal?=null
    private var attempt=""
    private var testRun=""
    private var sequence=0L
    private var sealed=false
    private var semanticCount=0
    private var touchCount=0
    private var secure=false
    private var dispatchToken:Long?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);current=WeakReference(this)
        setContentView(TextView(this).apply {text="独立 Presentation 接收端待准备";setTextColor(Color.BLACK);setBackgroundColor(Color.WHITE);textSize=22f})
    }
    fun prepare(run:String) {
        check(journal==null);check(run.length in 1..128)
        testRun=run;attempt=UUID.randomUUID().toString();journal=Journal(File(filesDir,"presentation-reports/$attempt"),2000,2L*1024*1024,64)
        panel=ReceiverPresentation().apply {show()}
        record("fixture_ready",mapOf("actualWindowType" to panel!!.window!!.attributes.type),"fixture_setup")
    }
    private fun record(kind:String,data:Map<String,Any?>,origin:String) {
        if(sealed)return
        journal?.append(mapOf("sequence" to ++sequence,"eventKind" to kind,"recordOrigin" to origin,"uptimeMs" to SystemClock.uptimeMillis(),
            "data" to (data+mapOf("displayId" to display?.displayId,"dispatchToken" to dispatchToken))))
    }
    private inner class ReceiverPresentation:Presentation(this@PresentationFixtureActivity,this@PresentationFixtureActivity.display) {
        override fun onCreate(savedInstanceState:Bundle?) {
            super.onCreate(savedInstanceState)
            window!!.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
            window!!.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            val root=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(32,40,32,32);setBackgroundColor(Color.WHITE)}
            val count=TextView(context).apply {id=R.id.presentation_count;text="独立接收端：语义 0，坐标 0";setTextColor(Color.BLACK);textSize=22f}
            root.addView(count)
            fun button(label:String,resource:Int,callback:()->Unit)=Button(context).apply {text=label;id=resource;isAllCaps=false;textSize=22f;setOnClickListener {
                callback();record("click",mapOf("viewId" to resources.getResourceEntryName(resource),"semanticCount" to semanticCount,"touchCount" to touchCount),"semantic_callback")
                count.text="独立接收端：语义 $semanticCount，坐标 $touchCount"
            }}
            root.addView(button("Presentation 语义计数",R.id.presentation_semantic){semanticCount++},LinearLayout.LayoutParams(-1,100))
            root.addView(button("Presentation 坐标计数",R.id.presentation_touch){touchCount++},LinearLayout.LayoutParams(-1,100))
            setContentView(root);window!!.setLayout(-1,-1)
        }
        override fun dispatchTouchEvent(event:MotionEvent):Boolean {
            val token=++sequence;dispatchToken=token
            return try {
                record("touch",mapOf("actionMasked" to event.actionMasked,"x" to event.x,"y" to event.y,"source" to event.source,"deviceId" to event.deviceId,
                    "downTime" to event.downTime,"eventTime" to event.eventTime,"motionDisplayId" to null,"motionDisplayIdReason" to "not_exposed_by_public_InputEvent_API"),"android_dispatch")
                super.dispatchTouchEvent(event)
            }finally {dispatchToken=null}
        }
    }
    fun setSecure(value:Boolean) {
        check(!sealed && panel?.isShowing==true);secure=value
        if(value)panel!!.window!!.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else panel!!.window!!.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        record("fixture_secure",mapOf("enabled" to value),"fixture_setup")
    }
    fun snapshot():String {
        val log=journal ?: error("Presentation not prepared")
        val prefix=log.snapshot()
        return Json.encode(mapOf("attemptId" to attempt,"testRunId" to testRun,"evidenceMode" to "manual_platform_api","agentAnswerVerified" to false,"systemInjectionProven" to false,
            "displayId" to display?.displayId,"actualWindowType" to panel?.window?.attributes?.type,"showing" to (panel?.isShowing==true),"secure" to secure,"actualSecureFlag" to ((panel?.window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0),
            "semanticCount" to semanticCount,"touchCount" to touchCount,"sealed" to sealed,
            "evidence" to mapOf("relativePath" to "presentation-reports/$attempt/events.jsonl","eventPrefixBytes" to prefix.first,"eventPrefixSha256" to prefix.second,
                "incomplete" to log.incomplete.get(),"droppedSamples" to log.dropped.get())))
    }
    fun release():String {
        check(!sealed);record("fixture_release",emptyMap(),"fixture_setup");sealed=true
        val report=snapshot();atomicFile(File(journal!!.dir,"report.json"),report);panel?.dismiss();journal?.close();return report
    }
    override fun onDestroy() {if(!sealed && journal!=null)runCatching {release()};if(current?.get()===this)current=null;super.onDestroy()}
}
