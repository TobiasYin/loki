package dev.thor.agent

import org.json.JSONObject

/** Small, read-only foreground hints. These identities never authorize input. */
internal data class AppContextTarget(val packageName: String?, val validity: String, val identity: String, val visible: Boolean = true)
internal data class AppContextMetadata(val label: String?, val versionName: String?, val versionCode: String?)

internal class UpperAppContext(
    private val upperDisplay: () -> Int?,
    private val currentTarget: (Int) -> AppContextTarget?,
    private val metadata: (String) -> AppContextMetadata?,
) {
    fun read(perceptionEnabled: Boolean, configurationProbe: Boolean): JSONObject {
        val result = JSONObject()
        // Disabled/revoked perception and configuration probes must not inspect
        // windows, packages or displays solely to populate a model context.
        if (!perceptionEnabled || configurationProbe) return result
        val display = runCatching { upperDisplay() }.getOrNull()
        val observation = JSONObject().put("status", "unavailable")
        if (display == null) return result.put("upperAppObservation", observation)
        observation.put("displayId", display)
        result.put("upperAppObservation", observation)
        val before = runCatching { currentTarget(display) }.getOrNull() ?: return result
        if (before.validity != "valid" || !before.visible) {
            observation.put("status", if (before.validity == "ambiguous") "ambiguous" else "unknown")
            return result
        }
        val pkg = before.packageName?.takeIf { it.length <= 255 && it.matches(PACKAGE) }
            ?: return result.also { observation.put("status", "unknown") }
        val info = runCatching { metadata(pkg) }.getOrNull()
        // PackageManager may cross IPC. Do not publish an old app if its window
        // or calibrated display changed while loading static package metadata.
        val afterDisplay = runCatching { upperDisplay() }.getOrNull()
        val after = if (afterDisplay == display) runCatching { currentTarget(display) }.getOrNull() else null
        if (after == null || after != before) return result.also {
            observation.put("status", "unknown")
            if (afterDisplay != display) observation.remove("displayId")
        }
        val app = JSONObject().put("packageName", pkg)
            .put("metadataStatus", if (info == null) "unavailable" else "available")
        info?.label?.let { boundedText(it, 48, 144) }?.let { app.put("label", it) }
        info?.versionName?.let { boundedText(it, 40, 96) }?.let { app.put("versionName", it) }
        info?.versionCode?.takeIf { it.length in 1..19 && it.all(Char::isDigit) }?.let { app.put("versionCode", it) }
        observation.put("status", "observed")
        return result.put("upperApp", app)
    }

    companion object {
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")
        /** Bound code points and UTF-8 bytes; never split a surrogate pair. */
        internal fun boundedText(value: String, maxPoints: Int, maxBytes: Int): String? {
            val out = StringBuilder()
            var index = 0; var count = 0; var bytes = 0
            while (index < value.length && count < maxPoints) {
                val point = value.codePointAt(index)
                index += Character.charCount(point)
                if (Character.isISOControl(point) || point in 0xD800..0xDFFF) continue
                val chars = String(Character.toChars(point))
                val size = chars.toByteArray(Charsets.UTF_8).size
                if (bytes + size > maxBytes) break
                out.append(chars); bytes += size; count++
            }
            return out.toString().trim().takeIf { it.isNotEmpty() }
        }
    }
}

/** Only static package metadata is cached. Foreground identity is always live. */
internal class AppContextMetadataCache(
    private val now: () -> Long,
    private val load: (String) -> AppContextMetadata?,
    private val ttlMs: Long = 60_000,
    private val capacity: Int = 16,
) {
    private data class Entry(val at: Long, val value: AppContextMetadata?)
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)

    @Synchronized fun get(packageName: String): AppContextMetadata? {
        val at = now()
        entries[packageName]?.takeIf { at >= it.at && at - it.at < ttlMs }?.let { return it.value }
        val value = runCatching { load(packageName) }.getOrNull()?.let {
            AppContextMetadata(UpperAppContext.boundedText(it.label.orEmpty(), 48, 144),
                UpperAppContext.boundedText(it.versionName.orEmpty(), 40, 96),
                it.versionCode?.takeIf { code -> code.length in 1..19 && code.all(Char::isDigit) })
        }
        entries[packageName] = Entry(at, value)
        while (entries.size > capacity.coerceAtLeast(1)) entries.remove(entries.keys.first())
        return value
    }
}
