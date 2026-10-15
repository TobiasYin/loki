package dev.thor.bridge

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/** Installed by Native settings, never from bridge payloads. Paths are exact, canonical encoded paths. */
data class NetworkProfile(val ref: String, val origin: String?, val paths: Set<String>, val methods: Set<String>, val purposes: Set<Purpose>, val credentialRef: String? = null, val headerTemplates: Map<String, String> = emptyMap(), val nativeSessionHeader: String? = null) {
    init {
        require(ref.isNotBlank() && methods.isNotEmpty() && purposes.isNotEmpty())
        require(origin != null || purposes.all { it == Purpose.web_fetch })
        require(origin != null || credentialRef == null)
        require(paths.isNotEmpty() || origin == null)
        require(headerTemplates.values.all { "{{credential}}" in it })
        require(nativeSessionHeader == null || nativeSessionHeader == "x-opencode-session" && origin != null && purposes.all { it == Purpose.model || it == Purpose.configuration_test } && headerTemplates.keys.none { it.equals(nativeSessionHeader, true) })
    }
}
class CredentialVault(values: Map<String, String>) {
    private val values = values.toMap()
    init { require(values.values.all { it.isNotEmpty() && '\r' !in it && '\n' !in it }) }
    internal fun value(ref: String): String = values[ref] ?: throw BridgeFailure(Code.PERMISSION_MISSING)
    fun redact(text: String): String = values.values.fold(text) { result, secret -> result.replace(secret, "[REDACTED]") }
    internal fun containsSecret(text: String): Boolean = values.values.any { it in text }
    override fun toString() = "CredentialVault([REDACTED])"
}
class NetworkPolicy(profiles: List<NetworkProfile>, val vault: CredentialVault, private val resolver: Dns = Dns.SYSTEM) {
    private val profiles = profiles.map { it.copy(paths = it.paths.toSet(), methods = it.methods.toSet(), purposes = it.purposes.toSet(), headerTemplates = it.headerTemplates.toMap()) }.associateBy { it.ref }
    init { require(this.profiles.size == profiles.size) }
    private val forbiddenHeaders = setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization", "cookie", "set-cookie", "upgrade", "te", "trailer", "accept-encoding")
    data class Prepared(val profile: NetworkProfile, val url: HttpUrl, val headers: Map<String, String>, val dns: Dns) { override fun toString() = "Prepared(profileRef=${profile.ref}, headers=[REDACTED])" }
    fun prepare(start: HttpStart): Prepared {
        val p = profiles[start.envelope.profileRef] ?: throw BridgeFailure(Code.PERMISSION_MISSING)
        val url = validateUrl(p, start.url, start.method, start.envelope.purpose)
        requireBridge(start.credentialRef == p.credentialRef, Code.PERMISSION_MISSING)
        requireBridge(start.headers.size <= 64 && start.headers.entries.sumOf { it.key.length + it.value.length } <= 16384, Code.LIMIT_EXCEEDED)
        val templates = p.headerTemplates.mapKeys { it.key.lowercase() }
        val templateNames = templates.keys
        val headers = linkedMapOf<String, String>()
        start.headers.forEach { (key, value) ->
            val name = key.lowercase()
            requireBridge(name.matches(Regex("[!#$%&'*+.^_`|~0-9a-z-]+")) && '\r' !in value && '\n' !in value, Code.INVALID_REQUEST)
            requireBridge(name !in forbiddenHeaders && !vault.containsSecret(value), Code.PERMISSION_MISSING)
            if (name in templateNames) requireBridge(value == templates.getValue(name), Code.PERMISSION_MISSING)
            else {
                requireBridge(name !in setOf("authorization", "x-api-key") && "{{credential}}" !in value, Code.PERMISSION_MISSING)
                headers[name] = value
            }
        }
        p.headerTemplates.forEach { (name, template) ->
            requireBridge(name.lowercase() !in forbiddenHeaders && name.matches(Regex("[A-Za-z0-9-]+")), Code.PERMISSION_MISSING)
            val secret = vault.value(p.credentialRef ?: throw BridgeFailure(Code.PERMISSION_MISSING))
            headers[name.lowercase()] = template.replace("{{credential}}", secret)
        }
        p.nativeSessionHeader?.let { name ->
            val session = start.envelope.binding.sessionId
            requireBridge(session.matches(Regex("[A-Za-z0-9_-]{1,100}")), Code.INVALID_REQUEST)
            // Routing identity is always derived from the Native-authorized binding.
            // A caller-supplied header cannot route a turn into another conversation.
            headers[name] = session
        }
        // Reachability and proxy DNS are owned by the system network. Do not
        // reject LAN, loopback, reserved/fake-IP or mixed-family DNS results.
        return Prepared(p, url, headers, resolver)
    }

    fun validateUrl(p: NetworkProfile, raw: String, method: String, purpose: Purpose): HttpUrl {
        requireBridge(raw.length <= 8192 && !vault.containsSecret(URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")), Code.PERMISSION_MISSING)
        val url = raw.toHttpUrlOrNull() ?: throw BridgeFailure(Code.INVALID_REQUEST)
        requireBridge(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null, Code.PERMISSION_MISSING)
        requireBridge(url.scheme in setOf("http", "https"), Code.PERMISSION_MISSING)
        requireBridge(method in p.methods && purpose in p.purposes, Code.PERMISSION_MISSING)
        requireBridge(p.origin == null || "${url.scheme}://${url.host}:${url.port}" == canonicalOrigin(p.origin), Code.PERMISSION_MISSING)
        requireBridge(p.origin == null || url.encodedPath in p.paths, Code.PERMISSION_MISSING)
        // Reject ambiguous paths even if a settings mistake accidentally allowed them.
        if(p.origin != null) requireBridge(!Regex("(?i)%2f|%5c|%2e").containsMatchIn(url.encodedPath) && '\\' !in raw.substringBefore('?'), Code.PERMISSION_MISSING)
        return url
    }
    private fun canonicalOrigin(value: String): String { val u = value.toHttpUrlOrNull() ?: throw BridgeFailure(Code.INVALID_REQUEST); return "${u.scheme}://${u.host}:${u.port}" }
}
