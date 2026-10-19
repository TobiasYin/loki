package dev.thor.agent

import android.content.Context
import android.graphics.*
import android.view.View
import java.util.concurrent.Executors

/** Synthetic content only. Uses the actual image blur/compositing rules, never chat data. */
internal class ChatBackgroundPreview(context: Context, private val store: ChatBackgroundStore) : View(context), AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    @Volatile private var closed = false
    @Volatile private var generation = 0
    private var bitmap: Bitmap? = null
    var spec = BackgroundSpec(); private set
    init {
        tag = "chat-background-preview"
        contentDescription = "虚拟聊天实时预览"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    fun update(next: BackgroundSpec) {
        if (closed) return
        val before = spec; spec = next
        contentDescription = "虚拟聊天实时预览，卡片透明度 ${100 - next.cardOpacity}%，背景不透明度 ${next.opacity}%，毛玻璃 ${next.blur}"
        invalidate()
        if (before.mode == next.mode && before.image == next.image && before.blur == next.blur) return
        val token = ++generation
        val file = if (next.mode == "image") store.file(next) else null
        if (file == null) { bitmap?.recycle(); bitmap = null; return }
        worker.execute {
            if (closed || token != generation) return@execute
            val loaded = runCatching { backgroundBitmap(file, next.blur) }.getOrNull()
            post {
                if (closed || token != generation) loaded?.recycle()
                else { val old = bitmap; bitmap = loaded; invalidate(); old?.recycle() }
            }
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val palette = Appearance.palette(context)
        canvas.save()
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = 18 * resources.displayMetrics.density
        canvas.clipPath(Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) })
        if (spec.mode == "wallpaper") {
            // Punch through the opaque settings page to the live system wallpaper.
            // This view must not have an intermediate hardware layer: CLEAR must reach the window.
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            paint.color = palette.background; paint.alpha = (palette.wallpaperScrimAlpha(spec.opacity) * 255).toInt()
            canvas.drawRect(bounds, paint)
        } else {
            canvas.drawColor(palette.background)
            bitmap?.takeIf { spec.mode == "image" && !it.isRecycled }?.let { image ->
                val scale = maxOf(width.toFloat() / image.width, height.toFloat() / image.height)
                val w = image.width * scale; val h = image.height * scale
                paint.alpha = (spec.opacity * 2.55f).toInt()
                canvas.drawBitmap(image, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), paint)
                paint.color = palette.background; paint.alpha = (palette.scrimAlpha * 255).toInt(); canvas.drawRect(bounds, paint)
            }
        }
        val scale = minOf(width / 360f, height / 200f)
        val offset = (width - 360f * scale) / 2
        canvas.translate(offset, 0f)
        canvas.scale(scale, scale)
        val h = height / scale
        fun box(left: Float, top: Float, right: Float, bottom: Float, color: Int, radius: Float = 15f) {
            paint.color = color; paint.alpha = (spec.cardOpacity * 2.55f).toInt()
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
        }
        fun text(value: String, x: Float, y: Float, size: Float = 13f, muted: Boolean = false, bold: Boolean = false) {
            paint.color = if (muted) palette.muted else palette.ink; paint.alpha = 255
            paint.textSize = size; paint.typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            canvas.drawText(value, x, y, paint)
        }
        box(-offset / scale, 0f, 360f + offset / scale, 38f, palette.background, 0f)
        text("☰", 14f, 25f, 16f); text("虚拟聊天", 44f, 24f, 14f, bold = true); text("＋", 329f, 25f, 18f)
        val user = if (palette.dark) 0xff2b3443.toInt() else 0xffe9eff9.toInt()
        val compact = h < 230f
        val userTop = if (compact) 46f else 52f
        val answerTop = if (compact) 84f else 99f
        box(142f, userTop, 346f, userTop + if (compact) 28f else 34f, user)
        text("这张背景怎么样？", 156f, userTop + 22f)
        box(14f, answerTop, 324f, answerTop + if (compact) 43f else 54f, palette.background)
        text("调整参数，即时查看效果。", 27f, answerTop + if (compact) 17f else 22f)
        text("消息、顶部和输入框一起变化。", 27f, answerTop + if (compact) 35f else 42f, 12f, muted = true)
        box(12f, h - 54f, 348f, h - 10f, palette.surface, 18f)
        text("输入消息…", 27f, h - 27f, muted = true)
        paint.color = palette.accent; paint.alpha = 255; canvas.drawCircle(326f, h - 32f, 12f, paint)
        paint.color = if (palette.dark) 0xff10254a.toInt() else Color.WHITE; paint.textSize = 17f; canvas.drawText("↑", 321f, h - 26f, paint)
        canvas.restore()
        // A fine outline keeps the preview distinguishable when its background matches the page.
        paint.color = palette.muted; paint.alpha = 60; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        canvas.drawRoundRect(.5f, .5f, width - .5f, height - .5f, radius, radius, paint); paint.style = Paint.Style.FILL
    }
    override fun close() { closed = true; generation++; worker.shutdown(); bitmap?.recycle(); bitmap = null }
}
