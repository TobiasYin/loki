package dev.thor.bridge

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.URLDecoder

/** Installed by Native settings, never from bridge payloads. Paths are exact, canonical encoded paths. */
data class NetworkProfile(val ref: String, val origin: String?, val paths: Set<String>, val methods: Set<String>, val purposes: Set<Purpose>, val credentialRef: String? = null, val headerTemplates: Map<String, String> = emptyMap(), val explicitLocal: Boolean = false) {
    init {
        require(ref.isNotBlank() && methods.isNotEmpty() && purposes.isNotEmpty())
        require(origin != null || purposes.all { it == Purpose.web_fetch })
        require(origin != null || credentialRef == null)
        require(!explicitLocal || origin != null)
        require(paths.isNotEmpty() || origin == null)
        require(headerTemplates.values.all { "{{credential}}" in it })
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
        return Prepared(p, url, headers, object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = resolver.lookup(hostname).also { addresses ->
                requireBridge(addresses.isNotEmpty() && (p.explicitLocal || addresses.all(::isPublicAddress)), Code.PERMISSION_MISSING)
            }
        })
    }
    fun validateUrl(p: NetworkProfile, raw: String, method: String, purpose: Purpose): HttpUrl {
        requireBridge(raw.length <= 8192 && !vault.containsSecret(URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")), Code.PERMISSION_MISSING)
        val url = raw.toHttpUrlOrNull() ?: throw BridgeFailure(Code.INVALID_REQUEST)
        requireBridge(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null, Code.PERMISSION_MISSING)
        requireBridge(url.isHttps || (p.explicitLocal && url.scheme == "http"), Code.PERMISSION_MISSING)
        requireBridge(method in p.methods && purpose in p.purposes, Code.PERMISSION_MISSING)
        requireBridge(p.origin == null || "${url.scheme}://${url.host}:${url.port}" == canonicalOrigin(p.origin), Code.PERMISSION_MISSING)
        requireBridge(p.origin == null || url.encodedPath in p.paths, Code.PERMISSION_MISSING)
        // Reject ambiguous paths even if a settings mistake accidentally allowed them.
        requireBridge(!Regex("(?i)%2f|%5c|%2e").containsMatchIn(url.encodedPath) && '\\' !in raw.substringBefore('?'), Code.PERMISSION_MISSING)
        requireBridge(p.explicitLocal || isLiteralPublic(url.host), Code.PERMISSION_MISSING)
        return url
    }
    private fun canonicalOrigin(value: String): String { val u = value.toHttpUrlOrNull() ?: throw BridgeFailure(Code.INVALID_REQUEST); return "${u.scheme}://${u.host}:${u.port}" }
    private fun isLiteralPublic(host: String): Boolean = if (host.contains(':') || host.matches(Regex("[0-9.]+"))) isPublicAddress(InetAddress.getByName(host)) else host != "localhost" && !host.endsWith(".localhost")
}
/** Fail closed for local, multicast, reserved and IPv4-mapped IPv6 destinations. Checked at connect DNS. */
fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
    val b = address.address.map { it.toInt() and 255 }
    if (b.size == 4) return !(b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 || (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && (b[1] == 168 || b[1] == 0 || b[1] == 2)) || (b[0] == 198 && b[1] in 18..19) || (b[0] == 198 && b[1] == 51 && b[2] == 100) || (b[0] == 203 && b[1] == 0 && b[2] == 113))
    // Only global unicast 2000::/3; reject documentation and transition ranges.
    return b.size == 16 && b[0] in 0x20..0x3f && !(b[0] == 0x20 && b[1] == 0x01 && (b[2] == 0x0d && b[3] == 0xb8 || b[2] == 0 && b[3] == 0)) && !(b[0] == 0x20 && b[1] == 0x02)
}
