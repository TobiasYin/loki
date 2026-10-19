package dev.thor.agent

import android.webkit.*
import androidx.webkit.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID

/** Display-only document. It has neither the execution bridge nor a native
 * authority/network/tool endpoint. Every navigation rotates its private peer. */
internal class ChatUiBridge(private val web:WebView,private val onCommand:(JSONObject)->Unit,
    private val onHello:()->Unit,private val onRendererGone:()->Unit,private val onDocumentInvalidated:()->Unit,private val startupAttempt:Long=0,
    private val readImage:(ChatImageRequest)->ChatImage?={null}):AutoCloseable {
    @Volatile private var closed=false
    @Volatile private var generation=""
    @Volatile private var committed=false
    private var peer:JavaScriptReplyProxy?=null
    val supported=WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private val assets=setOf("/assets/chat/index.html","/assets/chat/style.css","/assets/chat/ui.js","/assets/chat/chat-host.js",
        "/assets/chat/send-errors.js","/assets/chat/usage-footer.js","/assets/chat/tool-presentation.js","/assets/chat/vendor/marked.umd.js","/assets/chat/vendor/purify.min.js")
    private val loader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(web.context)).build()
    init {
        web.settings.apply {
            javaScriptEnabled=true;allowFileAccess=false;allowContentAccess=false;domStorageEnabled=false
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs=false
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs=false
            mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(false);mediaPlaybackRequiresUserGesture=true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
        web.webViewClient=object:WebViewClient() {
            override fun onPageStarted(view:WebView,url:String,favicon:android.graphics.Bitmap?) {invalidate()}
            override fun onPageFinished(view:WebView,url:String) {
                if(closed || view!==web || url!=BridgePolicy.PAGE || web.url!=BridgePolicy.PAGE)return
                committed=true
                RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.CHAT_PAGE_FINISHED)
                web.evaluateJavascript(Appearance.webScript(web.context) + ChatBackgroundStore(web.context).get(ChatBackgroundStore.GLOBAL).webStyleScript() + "window.receiveChatBootstrap?.(${JSONObject().put("generation",generation)})",null)
            }
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                if(view!==web || !request.isForMainFrame || request.url.toString()!=BridgePolicy.PAGE){invalidate();return true}
                return false
            }
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse {
                val uri=request.url
                if(!request.isForMainFrame && uri.scheme=="https" && uri.host=="appassets.androidplatform.net" && uri.port==-1 && uri.encodedQuery==null && uri.fragment==null && request.method=="GET") {
                    val imageRequest=ChatImagePolicy.parse(uri.encodedPath.orEmpty())
                    if(imageRequest!=null && !closed && committed) {
                        val document=generation
                        val image=runCatching {readImage(imageRequest)}.getOrNull()
                        if(image!=null && !closed && committed && document==generation)return WebResourceResponse(image.mimeType,null,200,"OK",
                            mapOf("Cache-Control" to "no-store","X-Content-Type-Options" to "nosniff"),ByteArrayInputStream(image.bytes))
                    }
                }
                if(uri.scheme=="https" && uri.host=="appassets.androidplatform.net" && uri.port==-1 && uri.encodedQuery==null && uri.fragment==null && uri.path in assets) {
                    loader.shouldInterceptRequest(uri)?.let {response->
                        response.responseHeaders=mapOf("Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'none'; img-src 'self'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'","X-Content-Type-Options" to "nosniff","Cache-Control" to "no-store")
                        return response
                    }
                }
                return WebResourceResponse("text/plain","UTF-8",403,"Forbidden",emptyMap(),ByteArrayInputStream(ByteArray(0)))
            }
            override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:android.net.http.SslError){handler.cancel();invalidate()}
            override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {close();view.destroy();onRendererGone();return true}
        }
        if(supported)WebViewCompat.addWebMessageListener(web,"ThorChat",setOf(BridgePolicy.ORIGIN)) {view,message,origin,mainFrame,reply->
            if(closed || message.type!=WebMessageCompat.TYPE_STRING)return@addWebMessageListener
            val raw=message.data ?: return@addWebMessageListener
            if(raw.length>BridgePolicy.UI_MAX_BYTES)return@addWebMessageListener
            runCatching {
                val envelope=JSONObject(raw)
                if(!BridgePolicy.acceptsChat(origin.toString(),mainFrame,view===web,view.url,raw.toByteArray(Charsets.UTF_8).size,
                        generation,envelope.optString("generation"),committed))return@runCatching
                val type=envelope.optString("type")
                if(type=="ui.hello" && envelope.keys().asSequence().toSet()==setOf("type","generation")) {
                    RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.CHAT_HELLO)
                    peer=reply;onHello()
                }else if(type=="ui.command" && peer!=null && envelope.keys().asSequence().toSet()==setOf("type","generation","command")) {
                    val command=envelope.getJSONObject("command")
                    val values=command.keys().asSequence().associateWith {key->command.get(key).let {value->if(value is JSONArray)(0 until value.length()).map {value.get(it)} else value}}
                    if(BridgePolicy.acceptsCommand(values,BuildConfig.DEBUG))onCommand(command)
                    else {
                        val safe=JSONObject()
                        for(key in setOf("op","sessionId","clientSendId","capability","clientMutationId"))
                            (command.opt(key) as? String)?.takeIf {it.length in 1..160}?.let {safe.put(key,it)}
                        post("rejected",JSONObject().put("code","INVALID_ARGUMENT").put("command",safe))
                    }
                }
            }
        }
    }
    private fun invalidate(){generation=UUID.randomUUID().toString();committed=false;peer=null;onDocumentInvalidated()}
    fun post(name:String,payload:JSONObject):Boolean {
        if(closed || !committed || web.url!=BridgePolicy.PAGE)return false
        val target=peer ?: return false
        return try {
            val text=JSONObject().put("type","ui.$name").put("generation",generation).put("payload",payload).toString()
            target.postMessage(text)
            if(name=="state")RuntimeStartupDiagnostics.payload(startupAttempt,RuntimeStartupPhase.CHAT_STATE_POST,text)
            else if(name=="ready")RuntimeStartupDiagnostics.mark(startupAttempt,RuntimeStartupPhase.CHAT_READY_POST)
            true
        }catch(_:RuntimeException){peer=null;false} // A dead chat never stalls execution delivery.
    }
    fun load(){check(!closed);invalidate();web.loadUrl(BridgePolicy.PAGE)}
    override fun close(){if(closed)return;closed=true;invalidate();if(supported)WebViewCompat.removeWebMessageListener(web,"ThorChat")}
}
