package dev.thor.inputlab

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.lang.ref.WeakReference
import kotlin.math.hypot

/** Synthetic offline multi-page UI. Runner only prepares/observes; every action
 * below originates in the normal Android UI dispatch path or a widget callback. */
class ScenarioActivity:Activity() {
    companion object {@Volatile var runnerActivity:WeakReference<ScenarioActivity>?=null}
    private var session:ScenarioSession?=null
    private lateinit var root:LinearLayout
    private var dispatchToken:Long?=null
    private var dispatchSequence=0L
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun log(kind:String,data:Map<String,Any?> =emptyMap(),origin:String="receiver_ui") {session?.record(kind,data+mapOf("dispatchToken" to dispatchToken,"displayId" to display?.displayId),origin)}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(BuildConfig.DEBUG)runnerActivity=WeakReference(this)
        session=ScenarioSessions.current?.takeUnless {it.isManual && it.sealed};render()
    }
    override fun onConfigurationChanged(newConfig:android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig);session?.geometry=geometry();log("configuration_changed",mapOf("keyboard" to newConfig.keyboard,"keyboardHidden" to newConfig.keyboardHidden,"navigation" to newConfig.navigation))
    }
    fun checkRunnerPrepareAllowed() {check(BuildConfig.DEBUG);check(session?.let {it.sealed || it.isManual}!=false)}
    fun prepare(value:ScenarioSession) {
        checkRunnerPrepareAllowed()
        session?.takeIf {!it.sealed && it.isManual}?.let {it.seal("replaced_by_runner");it.snapshot()}
        session=value;ScenarioSessions.current=value;render();value.geometry=geometry();log("scene_ready",mapOf("scene" to value.model.scene))
    }
    private fun finishManual(reason:String) {
        session?.takeIf {it.isManual && !it.sealed}?.let {it.seal(if(it.model.completed())null else reason);it.snapshot()}
    }
    private fun selectManualScene(scene:String) {
        check(session?.let {it.isManual || it.sealed}!=false)
        finishManual("manual_restart")
        val value=ScenarioSession.manual(ScenarioModel(scene,java.security.SecureRandom().nextLong()),filesDir)
        session=value;ScenarioSessions.current=value;render();value.geometry=geometry();log("scene_ready",mapOf("scene" to scene,"manual" to true));showTask()
    }
    private fun showTask() {session?.let {AlertDialog.Builder(this).setTitle("本轮任务").setMessage(it.model.taskPrompt).setPositiveButton("开始",null).show()}}
    private fun showSelector() {
        finishManual("manual_return_to_selector");session=null;ScenarioSessions.current=null;render()
    }
    private fun selector() {
        root.addView(label("多步交互演练",27f));root.addView(label("本地合成数据，不修改系统设置。每次随机生成一组任务；可查看任务后自行操作。",16f))
        listOf("settings" to "设置 · 修改并保存","game" to "游戏 · 两关目标辨识","news" to "新闻 · 滚动检索","article" to "长文 · 阅读首尾核对码").forEach { (scene,title)->root.addView(button(title,resources.getIdentifier("scenario_select_$scene","id",packageName)){selectManualScene(scene)},LinearLayout.LayoutParams(-1,dp(56)))}
        root.addView(button("返回基础 Input Lab",R.id.scenario_manual_exit){finish()})
    }
    private fun manualControls(s:ScenarioSession) {
        val row=LinearLayout(this)
        row.addView(button("查看任务",R.id.scenario_manual_task){showTask()},LinearLayout.LayoutParams(0,dp(44),1f))
        row.addView(button("新的一组",R.id.scenario_manual_new){selectManualScene(s.model.scene)},LinearLayout.LayoutParams(0,dp(44),1f))
        row.addView(button("选择场景",R.id.scenario_manual_home){showSelector()},LinearLayout.LayoutParams(0,dp(44),1f))
        root.addView(row)
    }
    fun freeze() {render()}
    private fun geometry():Map<String,Any?> {val metrics=android.util.DisplayMetrics();@Suppress("DEPRECATION") display?.getRealMetrics(metrics);return mapOf("displayId" to display?.displayId,"width" to metrics.widthPixels,"height" to metrics.heightPixels,"rotation" to display?.rotation,"densityDpi" to metrics.densityDpi)}
    fun snapshot():String {val s=session ?: error("Scenario has not been prepared");s.geometry=geometry();return s.snapshot()}
    private fun label(text:String,size:Float=19f)=TextView(this).apply {this.text=text;textSize=size;setTextColor(Color.rgb(20,35,55));setPadding(dp(8),dp(6),dp(8),dp(6))}
    private fun button(text:String,id:Int,action:()->Unit)=Button(this).apply {this.text=text;this.id=id;isAllCaps=false;textSize=17f;minHeight=dp(48);setOnClickListener {log("click",mapOf("label" to text,"viewId" to resources.getResourceEntryName(id)));action()}}
    private fun render() {
        val s=session
        root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(s?.model?.accentPadding ?: 16),dp(8),dp(16),dp(8));setBackgroundColor(Color.rgb(247,249,252))};setContentView(root)
        root.addView(label("离线交互演练 · ${s?.model?.scene ?: "待准备"}",15f))
        if(s==null){selector();return}
        if(s.sealed){root.addView(label("演练记录已封存"));root.addView(label(if(s.model.completed())"界面流程完成；模型答案由独立测试核对" else "流程未全部完成"));root.addView(button("选择新的手动演练",R.id.scenario_manual_home){showSelector()});return}
        if(s.isManual)manualControls(s)
        when(s.model.scene){"settings"->settings(s);"game"->game(s);"news"->if(s.model.page=="detail")detail(s,false)else news(s);"article"->if(s.model.page=="detail")detail(s,true)else articleHome(s)}
    }
    private fun settings(s:ScenarioSession) {
        val m=s.model
        when(m.page) {
            "home"->{root.addView(label("设置首页",26f));root.addView(button("设备偏好",R.id.scenario_preferences){m.openSettings();log("navigation",mapOf("to" to m.page));render()});root.addView(button("显示说明",R.id.scenario_distractor){log("distractor",mapOf("name" to "display-help"));Toast.makeText(this,"这是本地模拟设置，不修改系统配置",Toast.LENGTH_SHORT).show()})}
            "saved"->{root.addView(label("保存结果",26f));root.addView(label("已保存到本地演练记录"));root.addView(label("模式：${m.mode}\n夜间阅读：${if(m.enabled)"开启" else "关闭"}\n音量：${m.volume}%",22f));root.addView(button("返回首页",R.id.scenario_back){m.back();log("navigation",mapOf("to" to m.page));render()})}
            else->{
                root.addView(label("设备偏好",24f));root.addView(label("选择模式",17f))
                val choices=RadioGroup(this).apply {orientation=RadioGroup.HORIZONTAL}
                m.modes.forEach {name->choices.addView(RadioButton(this).apply {id=View.generateViewId();text=name;textSize=18f;isChecked=m.mode==name;setOnCheckedChangeListener {_,checked->if(checked){m.chooseMode(name);log("setting_change",mapOf("field" to "mode","value" to name))}}},LinearLayout.LayoutParams(0,dp(48),1f))};root.addView(choices)
                root.addView(Switch(this).apply {id=R.id.scenario_switch;text="夜间阅读";textSize=19f;isChecked=m.enabled;minHeight=dp(52);setOnCheckedChangeListener {_,checked->m.toggle(checked);log("setting_change",mapOf("field" to "enabled","value" to checked))}})
                val value=label("音量：${m.volume}%（每格 10%）",20f);root.addView(value)
                var downX=0f;var downY=0f;var moves=0;var maxDistance=0.0;var changes=0;var down=false
                val slider=SeekBar(this).apply {
                    id=R.id.scenario_slider;contentDescription="音量滑块，步进10百分比";max=10;progress=m.volume/10;minHeight=dp(64)
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar:SeekBar?,progress:Int,fromUser:Boolean){if(fromUser){changes++;m.slider(progress*10);value.text="音量：${m.volume}%（每格 10%）";log("slider_progress",mapOf("progress" to progress,"percent" to m.volume,"fromUser" to true))}}
                        override fun onStartTrackingTouch(bar:SeekBar?) {log("slider_tracking_start")}
                        override fun onStopTrackingTouch(bar:SeekBar?) {log("slider_tracking_stop")}
                    })
                    setOnTouchListener {_,e->
                        when(e.actionMasked){MotionEvent.ACTION_DOWN->{down=true;downX=e.x;downY=e.y;moves=0;maxDistance=0.0;changes=0};MotionEvent.ACTION_MOVE->if(down){moves++;maxDistance=maxOf(maxDistance,hypot((e.x-downX).toDouble(),(e.y-downY).toDouble()))};MotionEvent.ACTION_UP->{if(down && moves>=2 && maxDistance>=dp(24) && changes>=2){m.qualifyDrag();log("slider_drag_qualified",mapOf("moves" to moves,"maxDistancePx" to maxDistance,"fromUserChanges" to changes))};down=false};MotionEvent.ACTION_CANCEL->down=false}
                        log("slider_touch",mapOf("actionMasked" to e.actionMasked,"x" to e.x,"y" to e.y,"downTime" to e.downTime,"eventTime" to e.eventTime,"source" to e.source,"deviceId" to e.deviceId),"android_dispatch");false
                    }
                };root.addView(slider,LinearLayout.LayoutParams(-1,dp(70)))
                root.addView(button("保存",R.id.scenario_save){val ok=m.saveSettings();log("settings_save",mapOf("accepted" to ok,"values" to m.settingsValues));if(ok){s.persistSettings();render()}else Toast.makeText(this,"请核对目标配置，并实际拖动滑块后保存",Toast.LENGTH_LONG).show()})
                root.addView(button("返回首页",R.id.scenario_back){m.back();log("navigation",mapOf("to" to m.page));render()})
            }
        }
    }
    private fun game(s:ScenarioSession) {
        val m=s.model
        if(m.page=="victory"){root.addView(label("两关完成",28f));root.addView(label("通关口令：${m.gameResult}",28f));root.addView(label("误触：${m.gameWrong} 次"));return}
        val board=m.gameBoards[m.gameStep];val target=board.single {it.id==m.gameTargets[m.gameStep]}
        root.addView(label("第 ${m.gameStep+1} / 2 关 · 目标：${target.label()}",23f));root.addView(label("只有颜色、形状和编号全部相同才正确。误触：${m.gameWrong} 次",15f))
        root.addView(object:View(this) {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG);var pressed:String?=null
            init {id=R.id.scenario_board;contentDescription="彩色目标棋盘；请根据画面识别六个方块";isClickable=true}
            fun rect(index:Int):android.graphics.RectF {val col=index%3;val row=index/3;val w=width/3f;val h=height/2f;return android.graphics.RectF(col*w+dp(10),row*h+dp(8),(col+1)*w-dp(10),(row+1)*h-dp(8))}
            override fun onDraw(canvas:Canvas){super.onDraw(canvas);board.forEachIndexed {index,tile->val r=rect(index);paint.color=Color.WHITE;canvas.drawRoundRect(r,dp(14).toFloat(),dp(14).toFloat(),paint);paint.color=when(tile.color){"红色"->Color.rgb(207,55,62);"蓝色"->Color.rgb(41,102,202);else->Color.rgb(25,136,81)};val cx=r.centerX();val cy=r.top+r.height()*.42f;val size=minOf(r.width(),r.height())*.20f;when(tile.shape){"圆形"->canvas.drawCircle(cx,cy,size,paint);"方形"->canvas.drawRect(cx-size,cy-size,cx+size,cy+size,paint);else->{val path=android.graphics.Path();path.moveTo(cx,cy-size);path.lineTo(cx-size,cy+size);path.lineTo(cx+size,cy+size);path.close();canvas.drawPath(path,paint)}};paint.textSize=dp(19).toFloat();paint.textAlign=Paint.Align.CENTER;paint.color=Color.rgb(20,35,55);canvas.drawText("${tile.color}${tile.shape}",cx,r.bottom-dp(40),paint);canvas.drawText("编号 ${tile.number}",cx,r.bottom-dp(15),paint)}}
            override fun onTouchEvent(e:MotionEvent):Boolean {val tile=board.indices.firstOrNull {rect(it).contains(e.x,e.y)}?.let {board[it]};when(e.actionMasked){MotionEvent.ACTION_DOWN->{pressed=tile?.id;return true};MotionEvent.ACTION_UP->{val chosen=pressed;pressed=null;if(tile!=null && chosen==tile.id){performClick();val correct=m.pickGame(tile.id);log("game_pick",mapOf("tileId" to tile.id,"label" to tile.label(),"correct" to correct,"gameStep" to m.gameStep));render()};return true};MotionEvent.ACTION_CANCEL->{pressed=null;return true}};return true}
            override fun performClick():Boolean {super.performClick();return true}
        },LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun scroll(kind:String,content:LinearLayout,footer:TextView?=null):ScrollView {
        val s=session!!
        val view=ScrollView(this).apply {id=if(kind=="list")R.id.scenario_news_scroll else R.id.scenario_detail_scroll;isFillViewport=false;isVerticalScrollBarEnabled=true;scrollBarStyle=View.SCROLLBARS_INSIDE_OVERLAY;addView(content)}
        fun footerVisibility(){if(footer!=null){val bounds=Rect();if(view.scrollY>0 && footer.getGlobalVisibleRect(bounds) && footer.height>0 && footer.width>0 && bounds.height()>=footer.height && bounds.width()>=footer.width && !s.model.footerVisible){s.model.revealFooter();log("footer_visible",mapOf("visibleBounds" to listOf(bounds.left,bounds.top,bounds.right,bounds.bottom),"scrollY" to view.scrollY))}}}
        view.setOnScrollChangeListener {_:View,_,y,_,oldY->s.model.scroll(kind,y);log("scroll",mapOf("container" to kind,"scrollY" to y,"oldScrollY" to oldY,"viewportHeight" to view.height,"contentHeight" to content.height));footerVisibility()}
        view.viewTreeObserver.addOnGlobalLayoutListener {footerVisibility()}
        return view
    }
    private fun news(s:ScenarioSession) {
        root.addView(label("本地新闻 · 28 条",25f));root.addView(label("列表为原生 ScrollView 完整子树；向下滚动查找目标。",14f))
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        repeat(28){i->content.addView(button(s.model.storyTitle(i),resources.getIdentifier("scenario_story_$i","id",packageName)){s.model.openStory("story-$i");log("article_open",mapOf("articleId" to "story-$i","title" to s.model.storyTitle(i)));render()},LinearLayout.LayoutParams(-1,maxOf(dp(76),resources.displayMetrics.heightPixels/8)))}
        root.addView(scroll("list",content),LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun articleHome(s:ScenarioSession){root.addView(label("阅读专栏",27f));root.addView(button("打开《${s.model.articleTitle}》",R.id.scenario_article){s.model.openArticle();log("article_open",mapOf("articleId" to "long-article"));render()});root.addView(label("全文是本地合成内容，文末另有核对码。"))}
    private fun detail(s:ScenarioSession,long:Boolean) {
        val m=s.model;val title=if(long)m.articleTitle else m.storyTitle(m.openedArticleId!!.substringAfter('-').toInt())
        root.addView(button("‹ 返回列表",R.id.scenario_back){m.back();log("navigation",mapOf("to" to m.page));render()})
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};content.addView(label(title,26f));content.addView(label("开头核对码：${if(long)m.articleTopCode else m.storyCode(m.openedArticleId!!)}",22f))
        val paragraphs=if(long)36 else 5
        repeat(paragraphs){i->content.addView(label("第 ${i+1} 段：这是本地合成的观测记录。路线、天气与时间仅用于测试页面阅读和滚动，不代表真实新闻。请按用户任务读取画面，不要把未显示的内容当作已阅读。",18f).apply {setPadding(dp(10),dp(20),dp(10),dp(20))})}
        val footer=label("文末核对码：${if(long)m.articleBottomCode else "FIN-${m.storyCode(m.openedArticleId!!)}"}\n文章结束",24f).apply {id=R.id.scenario_footer;setPadding(dp(10),dp(30),dp(10),dp(48))};content.addView(footer)
        root.addView(scroll("detail",content,footer),LinearLayout.LayoutParams(-1,0,1f))
    }
    override fun dispatchTouchEvent(e:MotionEvent):Boolean {
        dispatchToken=++dispatchSequence
        log("touch",mapOf("actionMasked" to e.actionMasked,"source" to e.source,"deviceId" to e.deviceId,"downTime" to e.downTime,"eventTime" to e.eventTime,"x" to e.x,"y" to e.y,"rawX" to e.rawX,"rawY" to e.rawY,"historySize" to e.historySize,"pointerCount" to e.pointerCount),"android_dispatch")
        return try{super.dispatchTouchEvent(e)}finally{dispatchToken=null}
    }
    override fun dispatchKeyEvent(e:KeyEvent):Boolean {log("key",Capture.key(e).second,"android_dispatch");return super.dispatchKeyEvent(e)}
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);log("window_focus",mapOf("hasFocus" to hasFocus))}
    @Deprecated("Legacy back callback supported on API 30+")
    override fun onBackPressed() {if(session?.isManual==true){showSelector()}else super.onBackPressed()}
    override fun onDestroy(){session?.let {if(!it.sealed){it.seal("activity_destroyed");it.snapshot()}};if(runnerActivity?.get()===this)runnerActivity=null;super.onDestroy()}
}
