package dev.thor.inputlab

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.*
import android.view.*
import android.widget.*
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID

/** Offline DEBUG receiver: animation changes pixels, never the target's geometry.
 * Provider setup cannot click, dispatch input or override receiver counters. */
class DynamicContentFixtureActivity:Activity() {
    companion object {@Volatile var current:WeakReference<DynamicContentFixtureActivity>?=null}
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var ticker:TextView
    private lateinit var target:Button
    private lateinit var scroll:ScrollView
    private lateinit var canvas:View
    private var dialog:AlertDialog?=null
    private var journal:Journal?=null
    private var run="";private var attempt="";private var sequence=0L
    private var ticks=0L;private var frames=0L;private var clicks=0;private var dispatches=0;private var scrolls=0
    private var sealed=false
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private val tick=object:Runnable {override fun run(){if(journal!=null && !sealed){ticks++;ticker.text="TICK %012d".format(ticks)};handler.postDelayed(this,100)}}
    private val animate=object:Runnable {override fun run(){if(journal!=null && !sealed){frames++;canvas.invalidate()};handler.postDelayed(this,16)}}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);current=WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        scroll=ScrollView(this).apply {id=R.id.dynamic_scroll;isFillViewport=true}
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(18),dp(24),dp(18));setBackgroundColor(Color.WHITE)}
        ticker=TextView(this).apply {id=R.id.dynamic_ticker;text="TICK 000000000000";textSize=20f;typeface=android.graphics.Typeface.MONOSPACE;setTextColor(Color.BLACK)}
        body.addView(ticker,LinearLayout.LayoutParams(-1,dp(36)))
        canvas=object:View(this){private val paint=Paint();override fun onDraw(c:Canvas){paint.color=Color.rgb(((frames*11)%200).toInt(),70,130);c.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint);paint.color=Color.WHITE;c.drawCircle((frames*7%maxOf(width,1)).toFloat(),height/2f,dp(18).toFloat(),paint)}}.apply {contentDescription="Animated game surface"}
        body.addView(canvas,LinearLayout.LayoutParams(-1,dp(100)))
        body.addView(EditText(this).apply {id=R.id.dynamic_cursor;setText("Cursor stays in a fixed field");showSoftInputOnFocus=false;isCursorVisible=true;isSingleLine=true;requestFocus();setSelection(text.length)},LinearLayout.LayoutParams(-1,dp(48)))
        target=Button(this).apply {id=R.id.dynamic_target;text="Dynamic target";isAllCaps=false;setOnClickListener {clicks++;record("click",mapOf("clickCount" to clicks),"receiver_callback")}}
        body.addView(target,LinearLayout.LayoutParams(-1,dp(64)))
        val rowHeight=dp(90)
        val rowCount=maxOf(22,resources.displayMetrics.heightPixels/rowHeight+12)
        repeat(rowCount){i->body.addView(TextView(this).apply {text="Local scroll row ${i+1}";textSize=20f;setTextColor(Color.BLACK)},LinearLayout.LayoutParams(-1,rowHeight))}
        scroll.addView(body);setContentView(scroll)
        scroll.setOnScrollChangeListener {_,_,y,_,oldY->if(y!=oldY){scrolls++;record("scroll",mapOf("scrollY" to y,"oldY" to oldY),"android_scroll")}}
        handler.post(tick);handler.post(animate)
    }
    fun prepare(testRun:String) {
        check(journal==null);require(testRun.length in 1..128);run=testRun;attempt=UUID.randomUUID().toString()
        journal=Journal(File(filesDir,"dynamic-reports/$attempt"),10000,4L*1024*1024,128)
        record("prepared",emptyMap(),"fixture_setup")
    }
    fun configure(change:String) {
        check(journal!=null && !sealed)
        when(change) {
            "dialog"->{check(dialog==null);dialog=AlertDialog.Builder(this).setTitle("Real target change").setMessage("Independent modal window").setPositiveButton("Close",null).create().also {it.show()}}
            "dismiss"->{dialog?.dismiss();dialog=null}
            "move"->{val lp=target.layoutParams as LinearLayout.LayoutParams;lp.topMargin+=dp(72);target.layoutParams=lp}
            else->error("Unknown fixture setup")
        }
        record("configure",mapOf("change" to change),"fixture_setup")
    }
    override fun dispatchTouchEvent(e:MotionEvent):Boolean {
        dispatches++;record("touch",mapOf("actionMasked" to e.actionMasked,"x" to e.x,"y" to e.y,"source" to e.source,"deviceId" to e.deviceId,"downTime" to e.downTime,"eventTime" to e.eventTime),"android_dispatch")
        return super.dispatchTouchEvent(e)
    }
    private fun record(kind:String,data:Map<String,Any?>,origin:String) {if(!sealed)journal?.append(mapOf("sequence" to ++sequence,"eventKind" to kind,"recordOrigin" to origin,"uptimeMs" to SystemClock.uptimeMillis(),"data" to data))}
    private fun bounds(v:View):List<Int> {val xy=IntArray(2);v.getLocationOnScreen(xy);return listOf(xy[0],xy[1],xy[0]+v.width,xy[1]+v.height)}
    fun snapshot():String {
        val log=journal ?: error("Not prepared");val prefix=log.snapshot()
        val viewportHeight=(scroll.height-scroll.paddingTop-scroll.paddingBottom).coerceAtLeast(0)
        val contentHeight=scroll.getChildAt(0)?.height ?: 0
        return Json.encode(mapOf("attemptId" to attempt,"testRunId" to run,"evidenceMode" to "manual_platform_api","agentRunId" to "no-agent:$run","agentAnswerVerified" to false,"systemInjectionProven" to false,
            "displayId" to display?.displayId,"windowType" to window.attributes.type,"ticks" to ticks,"frames" to frames,"clickCount" to clicks,"touchCount" to dispatches,"scrollCount" to scrolls,"scrollY" to scroll.scrollY,"buttonBounds" to bounds(target),"scrollBounds" to bounds(scroll),"dialogShowing" to (dialog?.isShowing==true),"sealed" to sealed,
            "viewportHeight" to viewportHeight,"contentHeight" to contentHeight,"scrollRange" to (contentHeight-viewportHeight).coerceAtLeast(0),"canScrollDown" to scroll.canScrollVertically(1),
            "evidence" to mapOf("relativePath" to "dynamic-reports/$attempt/events.jsonl","eventPrefixBytes" to prefix.first,"eventPrefixSha256" to prefix.second,"eventCount" to sequence,"incomplete" to log.incomplete.get(),"droppedSamples" to log.dropped.get(),"sealed" to sealed)))
    }
    fun release():String {check(!sealed);record("release",emptyMap(),"fixture_setup");sealed=true;val result=snapshot();journal!!.close();dialog?.dismiss();finish();return result}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);if(!sealed){sealed=true;journal?.close()};dialog?.dismiss();if(current?.get()===this)current=null;super.onDestroy()}
}
