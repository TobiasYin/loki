package dev.thor.tools.diagnostic

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.thor.tools.Point

/** Local preference shared by the product settings and Accessibility service. */
object AgentActionFeedback {
    fun enabled(context: Context) = context.getSharedPreferences("agent-action-feedback", Context.MODE_PRIVATE).getBoolean("enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("agent-action-feedback", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) ThorAccessibilityService.instance?.actionOverlay?.clear()
    }
}

/** Main-thread-only, non-interactive overlay on the actual input display. */
class AgentActionOverlay(private val service: ThorAccessibilityService) {
    private var window: WindowManager? = null
    private var view: View? = null
    private var animator: ValueAnimator? = null
    private val ownedIds = linkedSetOf<Int>()
    private fun rememberWindowId() {
        val node = view?.createAccessibilityNodeInfo() ?: return
        try { if (node.windowId >= 0) ownedIds.add(node.windowId) }
        finally { @Suppress("DEPRECATION") node.recycle() }
    }
    @Synchronized fun ownsWindow(id: Int): Boolean {
        rememberWindowId()
        return id >= 0 && id in ownedIds
    }
    @Synchronized fun show(displayId: Int, points: List<Point>, kind: String, durationMs: Long): Boolean {
        clear()
        if (!AgentActionFeedback.enabled(service) || points.isEmpty()) return false
        return try {
            val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return false
            val context = service.createDisplayContext(display)
            val manager = context.getSystemService(WindowManager::class.java)
            val cursor = CursorView(context, points, kind, durationMs)
            val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                setTitle("Thor Agent action feedback")
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            }
            window = manager; view = cursor
            manager.addView(cursor, params)
            animator = ValueAnimator.ofFloat(0f, cursor.animationDurationMs.toFloat()).apply {
                duration = cursor.animationDurationMs
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { cursor.elapsedMs = it.animatedValue as Float; cursor.invalidate() }
                start()
            }
            true
        } catch (_: Exception) { clear(); false }
    }
    @Synchronized fun clear() {
        rememberWindowId()
        animator?.cancel(); animator = null
        view?.let { runCatching { window?.removeViewImmediate(it) } }
        view = null; window = null
        while (ownedIds.size > 32) ownedIds.remove(ownedIds.first())
    }
    private class CursorView(context: Context, private val points: List<Point>, private val kind: String, durationMs: Long) : View(context) {
        var elapsedMs = 0f
        private val actionDurationMs = durationMs.coerceAtLeast(100).toFloat()
        private val settleDurationMs = 180f
        val animationDurationMs = actionDurationMs.toLong() + settleDurationMs.toLong()
        private val coordinates = FloatArray(2)
        private val moving = points.size > 1
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = when (kind) {
            "input.tap", "input.activate" -> "点击"
            "input.long_press" -> "长按"
            "input.swipe" -> "滑动"
            "input.drag" -> "拖动"
            "input.scroll" -> "滚动"
            else -> "输入"
        }
        private val motionPath = Path().apply {
            moveTo(points.first().x.toFloat(), points.first().y.toFloat())
            points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
        }
        private val measure = PathMeasure(motionPath, false)
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        override fun onDraw(canvas: Canvas) {
            val travel = (elapsedMs / actionDurationMs).coerceIn(0f, 1f)
            // Ease the visual marker gently; endpoints remain the exact input coordinates.
            val easedTravel = travel * .75f + (travel * travel * (3f - 2f * travel)) * .25f
            val release = ((elapsedMs - actionDurationMs) / settleDurationMs).coerceIn(0f, 1f)
            val spring = (kotlin.math.sin(release * Math.PI * 2).toFloat() * (1f - release) * (1f - release))
            val fade = 1f - ((release - .65f) / .35f).coerceIn(0f, 1f)
            coordinates[0] = points.first().x.toFloat(); coordinates[1] = points.first().y.toFloat()
            if (measure.length > 0) measure.getPosTan(easedTravel * measure.length, coordinates, null)
            val x = coordinates[0]; val y = coordinates[1]
            val scale = when {
                moving -> .86f + .14f * minOf(travel * 5f, 1f) + .18f * spring
                elapsedMs >= actionDurationMs && kind == "input.long_press" ->
                    .84f + .16f * (1f - (1f - release) * (1f - release) * (1f - release)) + .22f * spring
                elapsedMs >= actionDurationMs -> 1f + .22f * spring
                kind == "input.long_press" -> 1f - .16f * minOf(travel * 5f, 1f)
                else -> .78f + .4f * kotlin.math.sin(travel * Math.PI).toFloat() + .22f * travel
            }
            val squash = if (moving) .12f * spring else 0f
            canvas.save()
            canvas.scale(scale * (1f + squash), scale * (1f - squash), x, y)
            paint.style = Paint.Style.FILL; paint.color = 0x403B82F6; paint.alpha = (64 * fade).toInt()
            canvas.drawCircle(x, y, 18 * density, paint)
            paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.alpha = (235 * fade).toInt(); paint.strokeWidth = 2 * density
            canvas.drawCircle(x, y, 18 * density, paint)
            paint.style = Paint.Style.FILL; paint.color = 0xEE3B82F6.toInt(); paint.alpha = (238 * fade).toInt()
            canvas.drawCircle(x, y, 4 * density, paint)
            canvas.restore()
            paint.textSize = 13 * density
            val textWidth = paint.measureText(label)
            val left = (x - textWidth / 2 - 8 * density).coerceIn(0f, (width - textWidth - 16 * density).coerceAtLeast(0f))
            val top = if (y > 70 * density) y - 62 * density else y + 32 * density
            paint.color = 0xDD172033.toInt(); paint.alpha = (221 * fade).toInt()
            canvas.drawRoundRect(left, top, left + textWidth + 16 * density, top + 26 * density, 8 * density, 8 * density, paint)
            paint.color = Color.WHITE; paint.alpha = (255 * fade).toInt()
            canvas.drawText(label, left + 8 * density, top + 18 * density, paint)
        }
    }
}
