package dev.thor.agent

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.*
import java.util.concurrent.Executors

/** Activity-owned presentation only. No images or app mappings enter model context. */
internal class ChatBackgroundController(
    private val activity: Activity,
    private val store: ChatBackgroundStore,
    private val web: () -> WebView?,
    private val foreground: () -> java.util.concurrent.CompletableFuture<String?>?,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var layer: FrameLayout? = null
    private var image: ImageView? = null
    private var veil: View? = null
    private var applied: BackgroundSpec? = null
    private var previewSpec: BackgroundSpec? = null
    private var appliedDark: Boolean? = null
    private var currentPackage: String? = null
    @Volatile private var generation = 0
    private var active = false
    @Volatile private var closed = false
    private var polling = false
    private var bitmap: android.graphics.Bitmap? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!active || closed) return
            refresh()
            if (!polling && store.all().keys.any { it != ChatBackgroundStore.GLOBAL }) {
                polling = true
                val request = foreground()
                if (request == null) polling = false
                else request.whenComplete { pkg, _ -> main.post {
                    polling = false
                    if (active && !closed) { currentPackage = pkg; refresh() }
                } }
            }
            main.postDelayed(this, 1200)
        }
    }
    fun attach(container: FrameLayout) {
        layer?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        layer = FrameLayout(activity).also { frame ->
            image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            veil = View(activity)
            frame.addView(image, FrameLayout.LayoutParams(-1, -1)); frame.addView(veil, FrameLayout.LayoutParams(-1, -1))
            container.addView(frame, 0, FrameLayout.LayoutParams(-1, -1))
        }
        web()?.setBackgroundColor(Color.TRANSPARENT)
        applied = null; refresh()
    }
    fun start() { active = true; main.removeCallbacks(tick); main.post(tick) }
    fun stop() { active = false; currentPackage = null; main.removeCallbacks(tick) }
    fun refresh() {
        if (closed) return
        val spec = previewSpec ?: store.effective(currentPackage)
        val palette = Appearance.palette(activity)
        // Restore explicit colors after navigation or reattaching the Service WebView.
        web()?.evaluateJavascript(Appearance.webScript(activity) + spec.webStyleScript() + "document.documentElement.classList.toggle('chat-background',${spec.mode != "default"})", null)
        if (applied == spec && appliedDark == palette.dark) return
        appliedDark = palette.dark
        val previousSpec = applied
        applied = spec
        val color = palette.background
        val wallpaper = spec.mode == "wallpaper"
        (layer?.parent as? ChatSurfaceLayout)?.wallpaperVisible = wallpaper
        // A translucent RGBA surface is required on the Thor secondary display.
        // Changing only the Drawable opacity can leave the old opaque buffer in place.
        activity.window.setFormat(if (wallpaper) android.graphics.PixelFormat.TRANSLUCENT else android.graphics.PixelFormat.OPAQUE)
        activity.window.setBackgroundDrawable(ColorDrawable(if (wallpaper) Color.TRANSPARENT else color))
        if (wallpaper) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        if (Build.VERSION.SDK_INT >= 31) {
            val radius = if (wallpaper) (spec.blur * activity.resources.displayMetrics.density).toInt() else 0
            activity.window.attributes = activity.window.attributes.apply { blurBehindRadius = radius }
            if (radius > 0) activity.window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
        layer?.setBackgroundColor(if (wallpaper) Color.TRANSPARENT else color)
        veil?.setBackgroundColor(color)
        veil?.alpha = if (wallpaper) palette.wallpaperScrimAlpha(spec.opacity) else if (spec.mode == "image") palette.scrimAlpha else 0f
        image?.alpha = spec.opacity / 100f
        if (previousSpec?.mode == spec.mode && previousSpec.image == spec.image && previousSpec.blur == spec.blur) return
        val token = ++generation
        val file = if (spec.mode == "image") store.file(spec) else null
        if (file == null) { image?.setImageDrawable(null); bitmap?.recycle(); bitmap = null }
        else worker.execute {
            if (closed || token != generation) return@execute
            val loaded = runCatching { backgroundBitmap(file, spec.blur) }.getOrNull()
            main.post {
                if (closed || token != generation) loaded?.recycle()
                else {
                    val previous = bitmap; bitmap = loaded; image?.setImageBitmap(loaded); previous?.recycle()
                }
            }
        }
    }
    fun effectiveSpec() = store.effective(currentPackage)
    fun preview(spec: BackgroundSpec?) { previewSpec = spec; refresh() }
    fun import(uri: android.net.Uri, key: String, done: (String?) -> Unit) {
        worker.execute {
            val result = runCatching {
                val name = store.import(activity, uri)
                store.save(key, store.get(key).copy(mode = "image", image = name))
            }
            main.post { if (!closed) { refresh(); done(result.exceptionOrNull()?.message) } }
        }
    }
    override fun close() {
        closed = true; stop(); generation++; worker.shutdown()
        image?.setImageDrawable(null); bitmap?.recycle(); bitmap = null
        layer = null; image = null; veil = null
    }
}
