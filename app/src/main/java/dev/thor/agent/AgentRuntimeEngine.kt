package dev.thor.agent

import android.app.Activity
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import dev.thor.bridge.HttpTransport
import dev.thor.bridge.ToolGateway
import dev.thor.bridge.android.TrustedWebViewBridge
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture

/** Service-owned execution document. Attaching a screen never recreates its JS
 * RuntimeSession, authority, bridge or database writer. All methods run on main. */
internal class AgentRuntimeEngine(private val owner:AgentRuntimeService):AutoCloseable {
    interface Ui {
        fun runtimeUiCommand(op:String,data:JSONObject)
        fun runtimeUnavailable(message:String)
        fun isChatVisible():Boolean
        fun moveToBackground():Boolean
    }
    private val main=Handler(Looper.getMainLooper())
    private val webContext=MutableContextWrapper(owner.applicationContext)
    val web=WebView(webContext).apply {id=android.R.id.custom;WebView.setWebContentsDebuggingEnabled(false)}
    val host:ProductHost
    val bridge:TrustedWebViewBridge
    val closeCompletion get()=host.closeCompletion
    val identity=java.util.UUID.randomUUID().toString()
    private var ui=WeakReference<Ui>(null)
    private var closed=false
    var failed=false;private set
    private var lastState:JSONObject?=null
    private val uiStates=LatestValue<JSONObject>({task->main.postDelayed({task()},50)},{state,delivered->
        if(!closed && !failed)web.evaluateJavascript("window.receiveProductState?.(JSON.parse(${JSONObject.quote(state.toString())}))"){delivered()} else delivered()
    })
    init {
        check(Looper.myLooper()==Looper.getMainLooper())
        host=try {ProductHost.create(owner.applicationContext) {state->main.post {observe(state);uiStates.offer(state)}}}
            catch(error:Exception){uiStates.close();web.destroy();throw error}
        host.setBackgroundAgentAction {cp->
            check(Looper.myLooper()==Looper.getMainLooper())
            cp.check()
            val attached=ui.get()
            if(attached==null || !attached.isChatVisible())mapOf("alreadyBackground" to true,"movedToBackground" to false)
            else {
                var accepted=false
                cp.effect {cp.submitted();accepted=attached.moveToBackground()}
                dev.thor.tools.requireTool(accepted,dev.thor.tools.Code.IO_ERROR,"Android did not accept moving the chat task to background")
                mapOf("alreadyBackground" to false,"movedToBackground" to true)
            }
        }
        bridge=try {TrustedWebViewBridge(web,host.authority,HttpTransport(host.authority,host.policy),ToolGateway(host.authority,emptyList(),{emptyMap()}),
            {connection->host.bootstrap(connection)},BridgePolicy.PAGE,{connection,request->
                val op=request.optString("op")
                if(op in setOf("runtime.work.begin","runtime.work.end")) {
                    val id=request.optJSONObject("data")?.optString("workId").orEmpty()
                    try {
                        if(op=="runtime.work.begin")owner.beginWork(id) else owner.endWork(id)
                        CompletableFuture.completedFuture(JSONObject())
                    }catch(_:Exception){CompletableFuture.completedFuture(obj("error" to "BACKEND_UNAVAILABLE"))}
                }else if(op.startsWith("voice.") || op in setOf("openLink","voiceSettings","skillImport","pickImage","settings")) {
                    val attached=ui.get()
                    if(attached==null)CompletableFuture.completedFuture(obj("error" to "BACKEND_UNAVAILABLE"))
                    else {attached.runtimeUiCommand(op,request.optJSONObject("data") ?: JSONObject());CompletableFuture.completedFuture(JSONObject())}
                }else {
                    if(op=="begin") {
                        try {owner.ensureForegroundForRun()}
                        catch(_:Exception) {return@TrustedWebViewBridge CompletableFuture.completedFuture(obj("error" to "BACKEND_UNAVAILABLE"))}
                    }
                    host.rpc(connection,request).also {future->future.whenComplete {result,error->main.post {
                        if(!closed && !failed) {
                            if(result?.has("activeRuns")==true)observe(result)
                            if(op=="begin")owner.beginResolved(error==null && result?.has("lease")==true)
                        }
                    }}}
                }
            },{host.interrupt("执行页面连接失效；保留上下文，不重放");owner.connectionInvalidated()},host::auditHttp,{
                failed=true;uiStates.close();host.interrupt("执行渲染进程已退出；保留上下文，不重放")
                owner.rendererFailed();ui.get()?.runtimeUnavailable("聊天执行进程已退出；运行已中断，历史保留，不会自动重放。")
            })}catch(error:Exception){uiStates.close();host.close();web.destroy();throw error}
        if(bridge.supported)bridge.load() else {failed=true;uiStates.close()}
    }
    private fun observe(state:JSONObject) {
        if(closed || failed)return
        lastState=state
        owner.updateActiveRuns(state.optJSONArray("activeRuns")?.length() ?: 0)
    }
    fun attach(activity:Activity,container:FrameLayout,callbacks:Ui) {
        check(!closed)
        ui=WeakReference(callbacks);(web.parent as? ViewGroup)?.removeView(web)
        webContext.baseContext=activity
        web.settings.textZoom=(activity.resources.configuration.fontScale*100).toInt().coerceIn(75,200)
        container.addView(web,FrameLayout.LayoutParams(-1,-1))
        // Keep document timers/network callbacks alive while the UI is detached.
        web.onResume()
        lastState?.let {uiStates.offer(it)}
        if(failed)callbacks.runtimeUnavailable("聊天执行进程不可用；历史已保留，请重新打开聊天。")
    }
    fun detach(callbacks:Ui) {
        if(ui.get()!==callbacks)return
        ui.clear();(web.parent as? ViewGroup)?.removeView(web);webContext.baseContext=owner.applicationContext
        // Do not call WebView.onPause/pauseTimers, destroy or close the bridge.
    }
    fun stopAll() {
        if(closed)return
        if(!failed)web.evaluateJavascript("window.stopAllNativeRuns?.()",null)
        host.stopAllFromNotification()
    }
    override fun close() {
        if(closed)return;closed=true
        uiStates.close();ui.clear();(web.parent as? ViewGroup)?.removeView(web);webContext.baseContext=owner.applicationContext
        host.setBackgroundAgentAction(null);bridge.close();host.close()
        if(!failed)web.destroy() // renderer callback already destroyed its WebView.
    }
}
