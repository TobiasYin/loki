package dev.thor.agent

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Emulator-only real WebView component checks using synthetic presentation data.
 * Touches/keys are platform-injected and trusted by WebView. Native commands are
 * captured, never sent to a model or used to mutate provider/history settings. */
@RunWith(AndroidJUnit4::class)
class EmulatorModernChatUiTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private lateinit var web:WebView
    private fun js(code:String):String {
        val done=CountDownLatch(1);var result=""
        ins.runOnMainSync {web.evaluateJavascript(code){result=it;done.countDown()}}
        assertTrue("WebView did not answer component check",done.await(5,TimeUnit.SECONDS))
        return result
    }
    private fun json(code:String)=JSONObject(js(code))
    private fun waitFor(code:String,seconds:Int=8) {
        val end=SystemClock.elapsedRealtime()+seconds*1000L
        while(SystemClock.elapsedRealtime()<end){if(js(code)=="true")return;Thread.sleep(25)}
        fail("Component condition did not settle: $code")
    }
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
    private fun fixture():JSONObject {
        val sid="ui-component";val rid="component-run"
        val title="这是一条超过十个字且会根据侧栏可用宽度展示的完整会话标题：模型与能力交互测试"
        val data=obj("nodes" to JSONArray((0..7).map {obj("text" to "可读测试节点 $it","visibleToUser" to (it<3),"scrollAncestorRef" to "fixture-container","description" to "本地合成字段，不是设备截图识别结果")}),"coverage" to obj("source" to "synthetic UI component fixture"))
        return obj("fixture" to true,"selected" to sid,"sessions" to JSONArray().put(obj("id" to sid,"title" to title,"archivedAt" to null,"updatedAt" to 1,"capabilityRevision" to 5,"capabilities" to obj("perception" to true,"search" to true,"screen_control" to false,"debug" to false))),
            "messages" to JSONArray().put(obj("id" to "component-message","sessionId" to sid,"runId" to rid,"role" to "assistant","text" to "这是独立模拟器 UI 组件测试。")),
            "runs" to JSONArray().put(obj("id" to rid,"sessionId" to sid,"status" to "executing_tools","expanded" to false,"stopReason" to "未校验动作")),
            "events" to JSONArray().put(obj("runId" to rid,"sequence" to 1,"type" to "thinking_delta","data" to obj("text" to (1..12).joinToString("\n"){"思考步骤 $it：观察界面并核对数据。"})))
                .put(obj("runId" to rid,"sequence" to 2,"type" to "tool_intent","toolCallId" to "c","outcome" to "unknown","data" to obj("toolId" to "perception.ui_tree")))
                .put(obj("runId" to rid,"sequence" to 3,"type" to "tool_started","toolCallId" to "c","data" to obj("toolId" to "perception.ui_tree","arguments" to obj("query" to "测试节点"))))
                .put(obj("runId" to rid,"sequence" to 4,"type" to "tool_result","toolCallId" to "c","data" to obj("toolId" to "perception.ui_tree","outcome" to "observed","content" to JSONArray().put(obj("type" to "text","text" to data.toString()))))),
            "availability" to obj("perception" to "组件模拟就绪","search" to "组件模拟就绪","screen_control" to "未开启","debug" to "未开启"),
            "modelChoices" to JSONArray().put(obj("id" to "fixture-one","name" to "组件模型一")).put(obj("id" to "fixture-two","name" to "组件模型二")),
            "modelSelection" to obj("modelId" to "fixture-one","label" to "组件模型一","thinkingEffort" to "low","efforts" to JSONArray(listOf("low","high","max")),"contextWindow" to 1000000))
    }
    private fun installFixture(value:JSONObject) {
        assertEquals("true",js("""(() => {
            window.__componentNativeReceive=window.receiveNative;window.__componentNativePost=ThorUI.postMessage.bind(ThorUI);
            window.__componentCommands=[];window.__componentInputs=[];window.__componentTraces={};
            ThorUI.postMessage=raw=>window.__componentCommands.push(JSON.parse(raw));
            window.receiveNative=next=>{if(next.fixture)window.__componentNativeReceive(next);};
            window.__componentFixture=JSON.parse(${JSONObject.quote(value.toString())});window.__componentApply=()=>window.receiveNative(JSON.parse(JSON.stringify(window.__componentFixture)));
            for(const type of ['pointerdown','pointerup','click','keydown'])document.addEventListener(type,e=>window.__componentInputs.push({type,trusted:e.isTrusted,target:e.target.id||e.target.tagName,key:e.key||'',x:e.clientX,y:e.clientY}),true);
            window.__componentTrace=(key,selector)=>{
                const record={frames:[],done:false,inputStarted:false};window.__componentTraces[key]=record;
                const armed=performance.now();let start=armed;
                function begin(e){if(!e.isTrusted||record.inputStarted)return;record.inputStarted=true;record.inputType=e.type;start=performance.now();record.inputAtMs=start-armed;document.removeEventListener('pointerdown',begin,true);document.removeEventListener('keydown',begin,true);}
                document.addEventListener('pointerdown',begin,true);document.addEventListener('keydown',begin,true);
                function frame(t){
                    const now=performance.now(),n=document.querySelector(selector),animations=n?.getAnimations?.()??[];
                    const animating=n?.dataset.disclosureAnimating==='true'||animations.some(a=>a.pending||a.playState==='running');
                    if(n)record.frames.push({at:t-armed,callbackAt:now-armed,height:n.getBoundingClientRect().height,open:n.open,expanded:n.dataset.expanded,animating,body:!!n.querySelector('.tool-body'),animations:animations.map(a=>({pending:a.pending,playState:a.playState,currentTime:a.currentTime,startTime:a.startTime}))});
                    const settled=record.inputStarted&&now-start>=550&&!animating;
                    if(settled||now-armed>=5000){record.done=true;record.timedOut=!settled;document.removeEventListener('pointerdown',begin,true);document.removeEventListener('keydown',begin,true);}
                    else requestAnimationFrame(frame);
                }
                requestAnimationFrame(frame);
            };
            window.__componentApply();return true;
        })()"""))
    }
    private fun touch(selector:String) {
        val target=json("(() => {const n=document.querySelector(${JSONObject.quote(selector)});if(!n)throw Error('Missing target');n.scrollIntoView({block:'center'});const r=n.getBoundingClientRect();return{x:r.left+r.width/2,y:r.top+r.height/2,vw:innerWidth,vh:innerHeight};})()")
        val location=IntArray(2);ins.runOnMainSync {web.getLocationOnScreen(location)}
        val x=location[0]+target.getDouble("x").toFloat()*web.width/target.getDouble("vw").toFloat()
        val y=location[1]+target.getDouble("y").toFloat()*web.height/target.getDouble("vh").toFloat()
        val down=SystemClock.uptimeMillis()
        for((action,delay) in listOf(MotionEvent.ACTION_DOWN to 0L,MotionEvent.ACTION_UP to 20L)) {
            if(delay>0)Thread.sleep(delay)
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).apply {source=InputDevice.SOURCE_TOUCHSCREEN}
            try {assertTrue("Actual emulator touch was rejected",ins.uiAutomation.injectInputEvent(event,true))}finally {event.recycle()}
        }
    }
    private fun key(code:Int){ins.sendKeyDownUpSync(code)}
    private fun trace(name:String,selector:String){js("window.__componentTrace(${JSONObject.quote(name)},${JSONObject.quote(selector)});true")}
    private fun collectTrace(name:String,evidence:JSONObject,expectIntermediate:Boolean=true) {
        waitFor("window.__componentTraces[${JSONObject.quote(name)}].done===true")
        val trace=json("window.__componentTraces[${JSONObject.quote(name)}]");evidence.getJSONObject("traces").put(name,trace)
        assertTrue("No actual platform input started the frame observation",trace.getBoolean("inputStarted"))
        assertFalse("Disclosure never settled within the bounded frame observation",trace.getBoolean("timedOut"))
        val frames=trace.getJSONArray("frames").objects()
        assertTrue("Real requestAnimationFrame samples missing",frames.size>=4)
        val heights=frames.map {it.getDouble("height")};val min=heights.min();val max=heights.max()
        if(expectIntermediate){assertTrue("Disclosure did not change height in the real WebView",max-min>8);assertTrue("No genuine intermediate animation frames",heights.count {it>min+1 && it<max-1}>=2)}
    }
    private fun screenshot(name:String,evidence:JSONObject) {
        waitFor("document.getAnimations().every(a=>a.playState!=='running' || a.effect.getComputedTiming().iterations===Infinity)")
        val drawn=CountDownLatch(1)
        ins.runOnMainSync {web.postVisualStateCallback(System.nanoTime(),object:WebView.VisualStateCallback(){override fun onComplete(requestId:Long){web.postOnAnimation {web.postOnAnimation {drawn.countDown()}}}})}
        assertTrue(drawn.await(5,TimeUnit.SECONDS));val bitmap=ins.uiAutomation.takeScreenshot() ?: error("Emulator screenshot unavailable")
        try {File(context.filesDir,"ui-modern-emulator-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {bitmap.recycle()}
        evidence.getJSONArray("screenshots").put("ui-modern-emulator-$name.png")
    }
    @Test fun actualWebViewAnimatesDisclosuresAndHandlesInlineChoicesAndOptimisticCapabilities() {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("emulatorUiComponents")=="true")
        assertNotEquals("Never run these fixture controls on Thor","AYN Thor",android.os.Build.MODEL)
        assertTrue("Emulator hardware required",android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        assertEquals("1",shell("getprop ro.kernel.qemu"))
        val profiles=context.getSharedPreferences("profiles",0);val beforeProfile=profiles.all.toMap()
        val credentials=context.getSharedPreferences("native-credentials",0);val beforeCredentials=credentials.all.toMap()
        val evidence=obj("schemaVersion" to 1,"emulator" to true,"syntheticFixture" to true,"nativeCommandsIntercepted" to true,"cloudInvoked" to false,"passed" to false,"traces" to JSONObject(),"screenshots" to JSONArray())
        val reducedOnly=args.getString("reducedMotionOnly")=="true"
        val originalScale=shell("settings get global animator_duration_scale")
        if(!reducedOnly)shell("settings put global animator_duration_scale 1")
        else assertEquals("Set the actual emulator preference before starting this fresh test process",0.0,originalScale.toDouble(),0.0)
        val activity=ins.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            web=EmulatorRuntimeTestSupport.awaitReady(activity).web
            waitFor("window.productReady===true && typeof window.receiveNative==='function'",30)
            Thread.sleep(400);installFixture(fixture());evidence.put("webViewVersion",WebView.getCurrentWebViewPackage()?.versionName)
            if(reducedOnly) {
                assertEquals("true",js("matchMedia('(prefers-reduced-motion: reduce)').matches"))
                trace("reduced-operations-open","details.run");touch("details.run > summary");collectTrace("reduced-operations-open",evidence,false)
                trace("reduced-tool-open",".tool-card");touch(".tool-card > summary");collectTrace("reduced-tool-open",evidence,false)
                trace("reduced-tool-close",".tool-card");touch(".tool-card > summary");collectTrace("reduced-tool-close",evidence,false)
                assertEquals("true",js("!document.querySelector('.tool-card').open && !document.querySelector('.tool-body') && !document.querySelector('[data-disclosure-animating=true]')"))
                for(name in evidence.getJSONObject("traces").keys())assertTrue(evidence.getJSONObject("traces").getJSONObject(name).getJSONArray("frames").objects().none {it.optBoolean("animating")})
                evidence.put("reducedMotionSystemPreferenceVerified",true).put("systemPreferenceAppliedBeforeWebViewProcess",true).put("inputs",JSONArray(js("window.__componentInputs"))).put("passed",true)
                screenshot("reduced-motion",evidence);return
            }
            assertEquals("false",js("matchMedia('(prefers-reduced-motion: reduce)').matches"));assertEquals("true",js("document.querySelector('.attention')===null && document.querySelector('.tool-card')===null"))
            screenshot("collapsed",evidence)
            trace("thought-open",".thinking");touch(".thinking > summary");collectTrace("thought-open",evidence);screenshot("thinking",evidence)
            trace("thought-close",".thinking");touch(".thinking > summary");collectTrace("thought-close",evidence);waitFor("!document.querySelector('.thinking').open")
            trace("operations-open","details.run");touch("details.run > summary");collectTrace("operations-open",evidence);assertEquals("true",js("document.querySelectorAll('.tool-card').length===1 && !document.querySelector('.structured')"))
            trace("tool-open",".tool-card");touch(".tool-card > summary");collectTrace("tool-open",evidence);assertEquals("true",js("document.querySelector('.tool-body').textContent.includes('可读测试节点 7')"));screenshot("tool",evidence)
            trace("tool-close",".tool-card");touch(".tool-card > summary");collectTrace("tool-close",evidence)
            val closeFrames=evidence.getJSONObject("traces").getJSONObject("tool-close").getJSONArray("frames").objects();assertTrue(closeFrames.any {it.optString("expanded")=="false" && it.optBoolean("body") && it.optBoolean("open")});assertEquals("true",js("!document.querySelector('.tool-card').open && !document.querySelector('.tool-body')"))
            trace("rapid-stream",".tool-card");touch(".tool-card > summary");Thread.sleep(35);touch(".tool-card > summary");Thread.sleep(25);touch(".tool-card > summary")
            js("document.querySelector('.tool-card > summary').focus();window.__componentFixture.messages[0].text+=' 流式更新';window.__componentFixture.events.push({runId:'component-run',sequence:5,type:'text_delta',data:{text:'流式更新'}});window.__componentApply();true")
            collectTrace("rapid-stream",evidence);waitFor("document.querySelector('.tool-card').open && document.querySelector('.tool-card').dataset.disclosureAnimating!=='true'")
            // Real Enter on a focused native summary must follow the same path.
            assertEquals("true",js("document.activeElement===document.querySelector('.tool-card > summary')"));key(KeyEvent.KEYCODE_ENTER);waitFor("!document.querySelector('.tool-card').open && !document.querySelector('.tool-body')")
            touch("#model-picker");assertEquals("true",js("!document.querySelector('#model-panel').hidden && !document.querySelector('#model-panel select') && document.activeElement.id==='model-select'"))
            key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_ENTER);assertEquals("true",js("window.__componentCommands.some(c=>c.op==='sessionOptions' && c.modelId==='fixture-two')"));touch("#effort-select [data-value=max]");assertEquals("true",js("window.__componentCommands.some(c=>c.thinkingEffort==='max')"));screenshot("model-picker",evidence)
            key(KeyEvent.KEYCODE_ESCAPE);waitFor("document.querySelector('#model-panel').hidden && document.activeElement.id==='model-picker'")
            touch("#capabilities");touch("#cap-panel input[aria-label='屏幕感知']");assertEquals("true",js("!document.querySelector('#cap-panel input').checked && document.querySelector('#cap-panel input').getAttribute('aria-busy')==='true'"))
            js("window.__componentApply();true");assertEquals("false",js("document.querySelector('#cap-panel input').checked"))
            js("const c=window.__componentCommands.findLast(c=>c.op==='capability');window.__componentFixture.capabilityAcks=[{...c,ok:false,error:'组件模拟保存失败',capabilityRevision:5,enabled:true}];window.__componentApply();true")
            assertEquals("true",js("document.querySelector('#cap-panel input').checked && !document.querySelector('#cap-panel input').hasAttribute('aria-busy') && document.querySelector('.cap-error').textContent.includes('保存失败')"));screenshot("capability-rollback",evidence)
            touch("#cap-panel input[aria-label='屏幕感知']")
            js("window.receiveCapabilityFailure(window.__componentCommands.findLast(c=>c.op==='capability'));true")
            assertEquals("true",js("document.querySelector('#cap-panel input').checked && !document.querySelector('#cap-panel input').hasAttribute('aria-busy') && document.querySelector('.cap-error').textContent.includes('上次确认')"));evidence.put("bridgeFailureUiRollbackVerified",true)
            touch("#menu");assertEquals("true",js("document.querySelector('.session-select strong').textContent.length>10 && getComputedStyle(document.querySelector('.session-select strong')).textOverflow==='ellipsis'"));screenshot("topics",evidence);touch("#close-drawer")
            val trusted=JSONArray(js("window.__componentInputs")).objects()
            assertTrue("Actual platform touches must reach trusted WebView clicks",trusted.any {it.optString("type")=="click" && it.optBoolean("trusted")})
            assertTrue("Actual platform keys must reach trusted WebView key events",trusted.any {it.optString("type")=="keydown" && it.optBoolean("trusted")})
            evidence.put("trustedInputsBeforeReload",JSONArray(trusted)).put("actualTrustedTouchAndKeyboardVerified",true)
            // Reduced-motion is exercised in a second fresh instrumentation
            // process after the host sets the real Android preference to zero.
            // This WebView build does not propagate a live scale change to its
            // existing media-query state; neither test overrides matchMedia.
            evidence.put("passed",true)
        } catch(t:Throwable){
            evidence.put("failure",t.javaClass.simpleName+": "+t.message)
            if(::web.isInitialized)runCatching {
                evidence.put("inputsAtFailure",JSONArray(js("window.__componentInputs || []")))
                screenshot("failure",evidence)
            }
            throw t
        }
        finally {
            shell(if(originalScale=="null")"settings delete global animator_duration_scale" else "settings put global animator_duration_scale $originalScale")
            // Finishing an Activity now retains its Service-owned document. Drop
            // intercepted Native handlers and synthetic data via the idle owner
            // reload so no fixture leaks into the next test or a returning UI.
            try {EmulatorRuntimeTestSupport.reloadIdle(activity);evidence.put("serviceFixtureDocumentReleased",true)}
            catch(t:Throwable){evidence.put("passed",false).put("cleanupFailureType",t.javaClass.simpleName);throw t}
            finally {
                ins.runOnMainSync {activity.finish()};ins.waitForIdleSync()
                evidence.put("profileUnchanged",beforeProfile==profiles.all).put("credentialsUnchanged",beforeCredentials==credentials.all)
                File(context.filesDir,if(reducedOnly)"ui-modern-emulator-reduced.json" else "ui-modern-emulator.json").writeText(evidence.toString(2))
            }
            assertEquals(beforeProfile,profiles.all);assertEquals(beforeCredentials,credentials.all)
        }
    }
}
