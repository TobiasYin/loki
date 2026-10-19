package dev.thor.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

internal data class BackgroundSpec(val mode: String = "default", val image: String = "", val blur: Int = 0, val opacity: Int = 100, val cardOpacity: Int = 84) {
    fun json() = JSONObject().put("mode", mode).put("image", image).put("blur", blur).put("opacity", opacity).put("cardOpacity", cardOpacity)
    fun webStyleScript() = "document.documentElement.style.setProperty('--card-alpha','${cardOpacity.coerceIn(0, 100) / 100.0}');"
    companion object {
        fun parse(json: JSONObject) = BackgroundSpec(
            json.optString("mode").takeIf { it in setOf("default", "image", "wallpaper") } ?: "default",
            json.optString("image").takeIf { it.matches(Regex("[a-f0-9-]+\\.jpg")) }.orEmpty(),
            json.optInt("blur", 0).coerceIn(0, 30), json.optInt("opacity", 100).coerceIn(0, 100), json.optInt("cardOpacity", 84).coerceIn(0, 100))
        fun resolve(global: BackgroundSpec, overrides: Map<String, BackgroundSpec>, packageName: String?) = overrides[packageName] ?: global
    }
}

/** Private, bounded image copies: document URI grants never need to survive a restart. */
internal class ChatBackgroundStore(context: Context) {
    private val prefs = context.getSharedPreferences("chat-background", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "chat-backgrounds").apply { mkdirs() }
    fun all(): Map<String, BackgroundSpec> = prefs.all.mapNotNull { (key, value) ->
        runCatching { key to BackgroundSpec.parse(JSONObject(value as String)) }.getOrNull()
    }.toMap()
    fun get(key: String) = all()[key] ?: BackgroundSpec()
    fun effective(packageName: String?) = all().let { BackgroundSpec.resolve(it[GLOBAL] ?: BackgroundSpec(), it, packageName) }
    fun save(key: String, spec: BackgroundSpec) {
        val previous = get(key).image
        prefs.edit().putString(key, spec.json().toString()).apply()
        clean(previous)
    }
    fun remove(key: String) {
        val previous = get(key).image
        prefs.edit().remove(key).apply()
        clean(previous)
    }
    fun file(spec: BackgroundSpec): File? = spec.image.takeIf { it.isNotEmpty() }?.let { File(directory, it).takeIf(File::isFile) }
    private fun clean(previous: String) {
        if (previous.isNotEmpty() && all().values.none { it.image == previous }) File(directory, previous).delete()
    }
    fun import(context: Context, uri: android.net.Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 24 * 1024 * 1024) { "请选择小于 24 MB 的图片" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法读取图片")
        val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(maxOf(1, (maxOf(info.size.width, info.size.height) + 1599) / 1600))
        }
        val name = "${java.util.UUID.randomUUID()}.jpg"
        try { File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
        finally { bitmap.recycle() }
        return name
    }
    companion object { const val GLOBAL = "global" }
}

/** CPU blur also works on Android 11. Work stays off the UI thread. */
internal fun backgroundBitmap(file: File, blur: Int): Bitmap? {
    val original = BitmapFactory.decodeFile(file.path) ?: return null
    if (blur == 0) return original
    val scale = minOf(1f, 360f / maxOf(original.width, original.height))
    val small = Bitmap.createScaledBitmap(original, maxOf(1, (original.width * scale).toInt()), maxOf(1, (original.height * scale).toInt()), true)
    val w = small.width; val h = small.height
    var pixels = IntArray(w * h); small.getPixels(pixels, 0, w, 0, 0, w, h)
    val radius = maxOf(1, (blur * scale).toInt())
    repeat(3) {
        for (horizontal in listOf(true, false)) {
            val output = IntArray(pixels.size)
            val length = if (horizontal) w else h
            val lines = if (horizontal) h else w
            fun index(line: Int, point: Int) = if (horizontal) line * w + point else point * w + line
            for (line in 0 until lines) {
                var r = 0; var g = 0; var b = 0
                fun add(point: Int, sign: Int) { val c = pixels[index(line, point.coerceIn(0, length - 1))]; r += ((c shr 16) and 255) * sign; g += ((c shr 8) and 255) * sign; b += (c and 255) * sign }
                for (p in -radius..radius) add(p, 1)
                val count = radius * 2 + 1
                for (p in 0 until length) {
                    output[index(line, p)] = (255 shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                    add(p - radius, -1); add(p + radius + 1, 1)
                }
            }
            pixels = output
        }
    }
    val result = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    if (small !== original) small.recycle()
    original.recycle()
    return result
}
