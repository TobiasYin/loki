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
    private val startupAttempt=RuntimeStartupDiagnostics.begin()
    private val webContext=MutableContextWrapper(owner.applicationContext)
    // This document is never attached, even once: WebView 109 otherwise freezes
    // its JS after the Activity stays hidden. No fake visibility/overlay is used.
    val executionWeb=WebView(owner.applicationContext).apply {WebView.setWebContentsDebuggingEnabled(false)}
    var web=WebView(webContext).apply {id=android.R.id.custom};private set
    private lateinit var chatBridge:ChatUiBridge
    private var chatRendererGone=false
    private var executionReady=false
    private val queuedCommands=java.util.ArrayDeque<String>()
    private var queuedBytes=0
    val host:ProductHost
    val bridge:TrustedWebViewBridge
    val closeCompletion get()=host.closeCompletion
    val identity=java.util.UUID.randomUUID().toString()
    private var ui=WeakReference<Ui>(null)
    @Volatile private var closed=false
    var failed=false;private set
    private var lastState:JSONObject?=null
    private val pendingUiEvents=LinkedHashMap<String,Pair<String,JSONObject>>()
    private val uiStates=LatestValue<JSONObject>({task->main.postDelayed({task()},50)},{state,delivered->
        if(!closed && !failed) {
            if(chatVisible())chatBridge.post("state",state)
            val text=ExecutionStateProjection.project(state).toString();RuntimeStartupDiagnostics.payload(startupAttempt,RuntimeStartupPhase.EXEC_STATE_SEND,text)
            executionWeb.evaluateJavascript("window.receiveProductState?.(JSON.parse(${JSONObject.quote(text)}))"){RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.EXEC_STATE_ACK);delivered()}
        }else delivered()
    })
    // ProductHost publishes from its serial writer. Queue one stable Runnable,
    // rather than one closure retaining a full snapshot for every notification.
    private val stateUpdates=RuntimeStateMailbox<JSONObject>({main.post(it)},{main.removeCallbacks(it)}) {state->
        if(!closed && !failed){observe(state);uiStates.offer(state)}
    }
    init {
        check(Looper.myLooper()==Looper.getMainLooper())
        RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.WEBS_CREATED)
        RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.HOST_CREATE_BEGIN)
        host=try {ProductHost.create(owner.applicationContext,stateUpdates::offer,startupAttempt)}
            catch(error:Exception){stateUpdates.close();uiStates.close();main.removeCallbacksAndMessages(null);web.destroy();executionWeb.destroy();throw error}
        RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.HOST_CREATE_END)
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
        createChatBridge()
        bridge=try {TrustedWebViewBridge(executionWeb,host.authority,HttpTransport(host.authority,host.policy),ToolGateway(host.authority,emptyList(),{emptyMap()}),
            {connection->RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.EXEC_HELLO);host.bootstrap(connection)},BridgePolicy.RUNTIME_PAGE,{connection,request->
                val op=request.optString("op")
                if(op=="runtime.ui.event") {
                    relayUi(request.optJSONObject("data") ?: JSONObject())
                    CompletableFuture.completedFuture(JSONObject())
                }else if(op in setOf("runtime.work.begin","runtime.work.end")) {
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
                    host.rpc(connection,request).also {future->future.whenComplete {result,error->if(!closed && !failed)main.post {
                        if(!closed && !failed) {
                            if(result?.has("activeRuns")==true){RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.VIEW_RESPONSE);observe(result);uiStates.offer(result)}
                            if(op=="begin")owner.beginResolved(error==null && result?.has("lease")==true)
                        }
                    }}}
                }
            },{executionReady=false;queuedCommands.clear();queuedBytes=0;host.interrupt("执行页面连接失效；保留上下文，不重放");owner.connectionInvalidated()},host::auditHttp,{
                failed=true;stateUpdates.close();lastState=null;uiStates.close();host.interrupt("执行渲染进程已退出；保留上下文，不重放")
                owner.rendererFailed();ui.get()?.runtimeUnavailable("聊天执行进程已退出；运行已中断，历史保留，不会自动重放。")
            })}catch(error:Exception){stateUpdates.close();lastState=null;uiStates.close();main.removeCallbacksAndMessages(null);host.close();chatBridge.close();web.destroy();executionWeb.destroy();throw error}
        // onResume before load while unattached; never attach this WebView later.
        RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.BRIDGES_CREATED)
        executionWeb.onResume()
        RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.BRIDGE_LOAD)
        if(bridge.supported)bridge.load() else {failed=true;stateUpdates.close();lastState=null;uiStates.close()}
        if(chatBridge.supported)chatBridge.load() else ui.get()?.runtimeUnavailable("当前 WebView 不支持安全聊天连接。")
    }
    private fun createChatBridge() {
        chatBridge=ChatUiBridge(web,::chatCommand,{
            deliverChat()
            forwardCommand(obj("op" to "ready").toString())
        },{
            chatRendererGone=true
            ui.get()?.runtimeUnavailable("聊天显示进程已退出；执行状态由服务保留，请重新打开聊天。")
        },{queuedCommands.clear();queuedBytes=0;pendingUiEvents.clear();RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.UI_DOCUMENT_INVALIDATED)},startupAttempt,
            readImage={request->host.readChatImage(request).get(5,java.util.concurrent.TimeUnit.SECONDS)})
    }
    private fun relayUi(data:JSONObject) {
        if(data.keys().asSequence().toSet()!=setOf("name","payload"))return
        val name=data.optString("name");val payload=data.optJSONObject("payload") ?: return
        val fields=payload.keys().asSequence().associateWith {payload.get(it)}
        if(!RuntimeUiEventPolicy.accepts(name,fields,data.toString().toByteArray(Charsets.UTF_8).size))return
        if(name=="ready") {
            RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.EXEC_READY)
            executionReady=true
            while(queuedCommands.isNotEmpty())executionWeb.evaluateJavascript("window.ThorUI?.postMessage(${JSONObject.quote(queuedCommands.removeFirst())})",null)
            queuedBytes=0
        }
        if(name!="ready") {
            val key=when(name){"preparationState"->"prep:${payload.optString("sessionId")}";"sendFailure"->"send:${payload.optString("clientSendId")}";"capabilityFailure"->"cap:${payload.optString("sessionId")}:${payload.optString("capability")}";else->name}
            pendingUiEvents[key]=name to payload
            while(pendingUiEvents.size>128)pendingUiEvents.remove(pendingUiEvents.keys.first())
        }
        deliverChat() // Never wait for a visible/attached chat; no body queue.
    }
    private fun chatVisible()=ui.get()?.isChatVisible()==true && web.isAttachedToWindow && web.windowVisibility==android.view.View.VISIBLE
    private fun deliverChat() {
        if(closed || !chatVisible() || chatRendererGone)return
        lastState?.let {chatBridge.post("state",it)}
        if(executionReady)chatBridge.post("ready",JSONObject())
        val delivered=mutableListOf<String>()
        for((key,event) in pendingUiEvents)if(chatBridge.post(event.first,event.second))delivered.add(key)
        delivered.forEach {pendingUiEvents.remove(it)}
    }
    fun uiVisibilityChanged(callbacks:Ui) {
        if(ui.get()!==callbacks || closed)return
        RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.UI_VISIBLE,if(chatVisible())1 else 0)
        if(chatVisible()){deliverChat();forwardCommand(obj("op" to "ready").toString())}
    }
    private fun chatCommand(command:JSONObject) {
        if(closed)return
        // Host owns all state. The chat never receives its connection or lease.
        when(command.optString("op")) {
            "stop"->host.stopFromChat(runId=command.getString("runId"))
            "stopPreparing"->host.stopFromChat(sessionId=command.getString("sessionId"))
        }
        forwardCommand(command.toString())
    }
    private fun forwardCommand(raw:String) {
        if(closed || failed)return
        if(executionReady)executionWeb.evaluateJavascript("window.ThorUI?.postMessage(${JSONObject.quote(raw)})",null)
        else {
            val size=raw.toByteArray(Charsets.UTF_8).size
            if(queuedCommands.size>=32 || queuedBytes+size>1024*1024){
                val command=JSONObject(raw)
                val failure=when(command.optString("op")) {
                    "send"->obj("name" to "sendFailure","payload" to obj("sessionId" to command.getString("sessionId"),"clientSendId" to command.getString("clientSendId"),"errorCode" to "LIMIT_EXCEEDED"))
                    "capability"->obj("name" to "capabilityFailure","payload" to obj("sessionId" to command.getString("sessionId"),"capability" to command.getString("capability"),"clientMutationId" to command.getString("clientMutationId")))
                    else->obj("name" to "operationFailure","payload" to JSONObject())
                }
                relayUi(failure);return
            }
            queuedCommands.addLast(raw);queuedBytes+=size
        }
    }
    private fun observe(state:JSONObject) {
        if(closed || failed)return
        lastState=state
        owner.updateActiveRuns(state.optJSONArray("activeRuns")?.length() ?: 0)
    }
    fun attach(activity:Activity,container:FrameLayout,callbacks:Ui) {
        check(!closed)
        ui=WeakReference(callbacks)
        if(chatRendererGone){web=WebView(webContext).apply {id=android.R.id.custom};chatRendererGone=false;createChatBridge();chatBridge.load()}
        (web.parent as? ViewGroup)?.removeView(web)
        webContext.baseContext=activity
        web.settings.textZoom=(activity.resources.configuration.fontScale*100).toInt().coerceIn(75,200)
        container.addView(web,FrameLayout.LayoutParams(-1,-1))
        RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.UI_ATTACH)
        web.onResume() // UI only; executionWeb has never had a parent.
        deliverChat()
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
        host.stopAllFromNotification()
        if(!failed)runCatching {executionWeb.evaluateJavascript("window.stopAllNativeRuns?.()",null)}
    }
    override fun close() {
        if(closed)return;closed=true
        RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.ENGINE_CLOSED)
        stateUpdates.close();lastState=null;uiStates.close();main.removeCallbacksAndMessages(null)
        ui.clear();(web.parent as? ViewGroup)?.removeView(web);webContext.baseContext=owner.applicationContext
        queuedCommands.clear();queuedBytes=0;executionReady=false;pendingUiEvents.clear()
        host.setBackgroundAgentAction(null);chatBridge.close();bridge.close();host.close()
        if(!chatRendererGone)web.destroy()
        if(!failed)executionWeb.destroy() // execution renderer callback already destroyed it.
    }
}

/** Cross-thread ingress; pending notifications retain only the newest value.
 * The posted task captures this mailbox, never an individual state. Closing is
 * synchronized with posting so it cannot leave a late queued snapshot behind. */
internal class RuntimeStateMailbox<T:Any>(private val post:(Runnable)->Unit,
    private val remove:(Runnable)->Unit,private val deliver:(T)->Unit):AutoCloseable {
    private val lock=Any()
    private var closed=false
    private var scheduled=false
    private var pending:T?=null
    private val delivery=Runnable {
        val value=synchronized(lock) {
            scheduled=false
            if(closed)null else pending.also {pending=null}
        }
        if(value!=null)deliver(value)
    }
    fun offer(value:T) {
        synchronized(lock) {
            if(closed)return
            pending=value
            if(!scheduled){scheduled=true;post(delivery)}
        }
    }
    override fun close() {
        synchronized(lock) {
            if(closed)return
            closed=true;pending=null;scheduled=false;remove(delivery)
        }
    }
}
