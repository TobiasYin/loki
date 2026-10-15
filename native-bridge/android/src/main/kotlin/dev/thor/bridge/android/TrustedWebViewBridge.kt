package dev.thor.bridge.android

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.*
import androidx.webkit.*
import dev.thor.bridge.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

/** Call and close on the UI thread. Use a dedicated WebView; never load remote documents into it. */
class TrustedWebViewBridge(
    private val webView: WebView,
    private val authority: Authority,
    private val transport: HttpTransport,
    private val tools: ToolGateway,
    private val bootstrap: (String) -> JSONObject,
    private val page: String = "$ORIGIN/assets/index.html",
    private val hostRpc: ((String, JSONObject) -> java.util.concurrent.CompletableFuture<JSONObject>)? = null,
    private val onInvalidated: (() -> Unit)? = null,
    private val networkObserver: ((HttpStart, HttpEvent) -> Unit)? = null,
    private val onRendererGone: (() -> Unit)? = null
): AutoCloseable {
    companion object { const val ORIGIN = "https://appassets.androidplatform.net" }
    private val ui = Handler(Looper.getMainLooper())
    private var connection: String? = null
    private var binary = false
    private val aborted = mutableSetOf<String>()
    private val deliveryBudget = DeliveryBudget(transport.limits.windowBytes, transport.limits.windowBytes * 2)
    private var closed = false
    val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    val arrayBufferSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
    private val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(webView.context)).build()
    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false; allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            domStorageEnabled = false; mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.toString() != page || !request.isForMainFrame) { invalidate(); return true }
                return false
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { invalidate() }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                if (url.scheme == "https" && url.host == "appassets.androidplatform.net" && url.port == -1 && url.path?.startsWith("/assets/") == true) {
                    val response = loader.shouldInterceptRequest(url)
                    if (response != null) {
                        response.responseHeaders = mapOf("Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'none'; img-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'", "X-Content-Type-Options" to "nosniff", "Cache-Control" to "no-store")
                        return response
                    }
                }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { close(); view.destroy(); onRendererGone?.invoke(); return true }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel(); invalidate() }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.addWebMessageListener(webView, "ThorNative", setOf(ORIGIN)) { view, message, origin, mainFrame, reply ->
            if (closed || !BridgePeerPolicy.accepts(origin.toString(), mainFrame, view === webView, view.url, page)) return@addWebMessageListener
            var requestId = ""
            try {
                val (json, body) = decode(message)
                requestId = json.optString("requestId")
                when (json.getString("type")) {
                    "bridge.hello" -> {
                        requireBridgePublic(connection == null, Code.UNTRUSTED_BRIDGE)
                        val c = authority.connect(); connection = c
                        val context = bootstrap(c)
                        context.put("type", "bridge.ready").put("bridgeVersion", 1).put("arrayBufferSupported", arrayBufferSupported)
                        postString(reply, context.toString())
                    }
                    "bridge.binaryProbe" -> {
                        requireBridgePublic(connection != null && arrayBufferSupported && message.type == WebMessageCompat.TYPE_ARRAY_BUFFER && body.contentEquals(byteArrayOf(0, -1, 42)), Code.UNSUPPORTED)
                        binary = true; postString(reply, JSONObject().put("bridgeVersion", 1).put("type", "bridge.binaryReady").toString())
                    }
                    else -> {
                        requireBridgePublic(json.getInt("bridgeVersion") == 1, Code.UNSUPPORTED)
                        if (json.getString("type") == "host.rpc") {
                            val c = connection ?: throw BridgeFailure(Code.UNTRUSTED_BRIDGE)
                            requireBridgePublic(json.getString("connectionId") == c, Code.UNTRUSTED_BRIDGE)
                            val handler = hostRpc ?: throw BridgeFailure(Code.UNSUPPORTED)
                            handler(c, json).whenComplete { value, error -> ui.post {
                                if (!closed && connection == c && webView.url == page) postString(reply, JSONObject()
                                    .put("type", "host.result").put("requestId", requestId)
                                    .put("value", value ?: JSONObject.NULL).put("code", if (error == null) JSONObject.NULL else "IO_ERROR").toString())
                            } }
                            return@addWebMessageListener
                        }
                        val b = binding(json.getJSONObject("binding"))
                        requireBridgePublic(b.connectionId == connection, Code.UNTRUSTED_BRIDGE)
                        when (json.getString("type")) {
                            "request.start" -> {
                                val e = NetworkEnvelope(1, requestId, b, Purpose.valueOf(json.getString("purpose")), json.getString("profileRef"), json.getLong("deadlineMs"))
                                val h = json.getJSONObject("headers"); val headers = h.keys().asSequence().associateWith { h.getString(it) }
                                // Android JSONObject.optString turns explicit JSON null into
                                // the literal "null"; unauthenticated web requests need null.
                                val credentialRef=if(json.isNull("credentialRef")) null else json.getString("credentialRef").takeIf {it.isNotEmpty()}
                                val start = HttpStart(e, json.getString("url"), json.getString("method"), headers, if (json.optBoolean("hasBody")) body else null, credentialRef)
                                val c = connection!!
                                transport.start(start) { event -> networkObserver?.invoke(start,event); postEvent(c, reply, e, event) }
                            }
                            "stream.credit" -> transport.credit(requestId, b, json.getInt("bytes"))
                            "request.abort" -> { requireBridgePublic(aborted.size < 512 || requestId in aborted, Code.LIMIT_EXCEEDED); transport.abort(requestId, b); aborted.add(requestId) }
                            "tool.call" -> {
                                val p = json.getJSONObject("params")
                                val params = p.keys().asSequence().associateWith { key -> p.get(key).also { value -> requireBridgePublic(value is String || value is Int || value is Long || value is Boolean, Code.INVALID_REQUEST) } }
                                val call = ToolCall(requestId, b, json.getString("toolCallId"), json.getString("toolId"), json.getInt("schemaVersion"), json.getLong("deadlineMs"), params, json.getString("resource"), json.getString("snapshotId"))
                                val c = connection!!
                                tools.submit(call).thenAccept { rawResult -> ui.post {
                                    var result = rawResult
                                    try { authority.validate(b) } catch (e: BridgeFailure) { result = result.copy(code = e.code, text = e.code.name) }
                                    if (connection == c && !closed) postString(reply, JSONObject().put("bridgeVersion", 1).put("type", "tool.result").put("requestId", result.requestId).put("code", result.code?.name ?: JSONObject.NULL).put("text", result.text).put("observedAt", result.observedAt).put("source", result.source).put("truncated", result.truncated).put("outcome", result.outcome).toString()) } }
                            }
                            else -> throw BridgeFailure(Code.UNSUPPORTED)
                        }
                    }
                }
            } catch (e: Exception) {
                // Never return exception messages: parser/HTTP errors can include payloads and secrets.
                val code = (e as? BridgeFailure)?.code ?: Code.INVALID_REQUEST
                postString(reply, JSONObject().put("bridgeVersion", 1).put("type", "response.error").put("requestId", requestId.take(100)).put("code", code.name).toString())
            }
        }
    }
    private fun binding(j: JSONObject) = Binding(j.getString("sessionId"), j.getString("runId"), j.getString("lease"), j.getString("connectionId"), j.getLong("capabilityEpoch"))
    private fun decode(message: WebMessageCompat): Pair<JSONObject, ByteArray> {
        if (message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) {
            requireBridgePublic(arrayBufferSupported, Code.UNSUPPORTED)
            val data = message.arrayBuffer
            requireBridgePublic(data.size in 4..(16 * 1024 * 1024 + 65540), Code.LIMIT_EXCEEDED)
            val n = ByteBuffer.wrap(data, 0, 4).int
            requireBridgePublic(n in 1..65536 && n + 4 <= data.size, Code.INVALID_REQUEST)
            return JSONObject(data.copyOfRange(4, 4 + n).toString(Charsets.UTF_8)) to data.copyOfRange(4 + n, data.size)
        }
        val data = message.data ?: throw BridgeFailure(Code.INVALID_REQUEST)
        requireBridgePublic(data.length <= 16 * 1024 * 1024 * 4 / 3 + 65536, Code.LIMIT_EXCEEDED)
        val j = JSONObject(data)
        val body = if (j.opt("bytes") is String) Base64.decode(j.getString("bytes"), Base64.NO_WRAP) else ByteArray(0)
        requireBridgePublic(body.size <= 16 * 1024 * 1024, Code.LIMIT_EXCEEDED)
        if (j.opt("bytes") is String) j.remove("bytes")
        requireBridgePublic(j.toString().length <= 65536, Code.LIMIT_EXCEEDED)
        return j to body
    }
    private fun postEvent(c: String, reply: JavaScriptReplyProxy, envelope: NetworkEnvelope, event: HttpEvent) {
        val j = JSONObject().put("bridgeVersion", 1).put("requestId", event.requestId)
        when (event) {
            is HttpEvent.Head -> { j.put("type", "response.head").put("status", event.status).put("statusText", event.statusText).put("url", event.url); val h = JSONObject(); event.headers.forEach { (k, v) -> h.put(k, JSONArray(v)) }; j.put("headers", h) }
            is HttpEvent.Chunk -> j.put("type", "response.chunk").put("sequence", event.sequence)
            is HttpEvent.End -> j.put("type", "response.end")
            is HttpEvent.Error -> j.put("type", "response.error").put("code", event.code.name)
        }
        val cost = j.toString().toByteArray(Charsets.UTF_8).size + if (event is HttpEvent.Chunk) event.bytes.size else 0
        // A terminal error is small and must still reach JS when the data budget is exhausted.
        val ticket = if (event is HttpEvent.Error) null else deliveryBudget.reserve(event.requestId, cost)
        val posted = ui.post {
            try {
                if (closed || connection != c) return@post
                if (event !is HttpEvent.Error) {
                    if (event.requestId in aborted) return@post
                    try { authority.validateNetwork(envelope, false) } catch (_: BridgeFailure) { return@post }
                }
                if (event is HttpEvent.Chunk && binary && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)) {
                    val meta = j.toString().toByteArray(Charsets.UTF_8)
                    reply.postMessage(ByteBuffer.allocate(4 + meta.size + event.bytes.size).putInt(meta.size).put(meta).put(event.bytes).array())
                } else {
                    if (event is HttpEvent.Chunk) j.put("bytes", Base64.encodeToString(event.bytes, Base64.NO_WRAP))
                    postString(reply, j.toString())
                }
            } finally { ticket?.close() }
        }
        if (!posted) { ticket?.close(); throw BridgeFailure(Code.IO_ERROR) }
    }
    private fun postString(reply: JavaScriptReplyProxy, payload: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) reply.postMessage(payload)
    }
    fun load() { check(!closed); webView.loadUrl(page) }
    private fun invalidate() { connection?.let { authority.disconnect(it); onInvalidated?.invoke() }; connection = null; binary = false; aborted.clear() }
    override fun close() {
        if (closed) return
        closed = true; invalidate(); transport.close(); tools.close()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(webView, "ThorNative")
    }
}
private fun requireBridgePublic(condition: Boolean, code: Code) { if (!condition) throw BridgeFailure(code) }
