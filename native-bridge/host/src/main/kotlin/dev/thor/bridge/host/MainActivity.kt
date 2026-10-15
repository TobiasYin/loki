package dev.thor.bridge.host

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import dev.thor.bridge.*
import dev.thor.bridge.android.TrustedWebViewBridge
import org.json.JSONObject

/** Standalone test host. No real credentials, model SDK, device tool, or production storage. */
class MainActivity : Activity() {
    private var bridge: TrustedWebViewBridge? = null
    private lateinit var webView: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authority = Authority()
        authority.createSession("lab")
        val vault = CredentialVault(mapOf("fixture" to "fixture-secret"))
        val policy = NetworkPolicy(listOf(
            NetworkProfile("local", "http://127.0.0.1:8877", setOf("/stream", "/echo", "/status400", "/status500", "/slow", "/redirect"), setOf("GET", "POST", "HEAD"), setOf(Purpose.model)),
            NetworkProfile("local-auth", "http://127.0.0.1:8877", setOf("/redirect", "/auth"), setOf("GET"), setOf(Purpose.model), "fixture", mapOf("Authorization" to "Bearer {{credential}}"))
        ), vault)
        val transport = HttpTransport(authority, policy)
        val gateway = ToolGateway(authority, listOf(ToolDescriptor("mock.echo", 1, emptySet(), mapOf("text" to ParamRule(ParamType.STRING)), setOf("fixture"), executor = ToolExecutor { ctx, params -> ctx.check(); params.getValue("text") as String })), { mapOf("fixture" to "fixture-v1") }, vault::redact)
        webView = WebView(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply { text = "Bridge Lab · localhost fixtures only · no device tools" })
        column.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(column)
        bridge = TrustedWebViewBridge(webView, authority, transport, gateway, { connection ->
            val b = authority.beginRun("lab", connection, mapOf(Purpose.model to setOf("local", "local-auth")))
            JSONObject().put("context", JSONObject().put("binding", JSONObject().put("sessionId", b.sessionId).put("runId", b.runId).put("lease", b.lease).put("connectionId", b.connectionId).put("capabilityEpoch", b.capabilityEpoch)).put("purpose", "model").put("profileRef", "local").put("deadlineMs", authority.clock.nowMs() + 175000).put("timeoutMs", 175000))
                .put("effectiveTools", org.json.JSONArray(gateway.effectiveTools(b).toList()))
        })
        if (bridge!!.supported) bridge!!.load() else column.addView(TextView(this).apply { text = "UNSUPPORTED: WEB_MESSAGE_LISTENER; no insecure fallback installed" })
    }
    override fun onRestart() { super.onRestart(); recreate() }
    override fun onStop() { bridge?.close(); super.onStop() }
    override fun onDestroy() { bridge?.close(); webView.destroy(); super.onDestroy() }
}
