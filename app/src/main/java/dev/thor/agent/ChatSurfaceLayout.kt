package dev.thor.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.widget.FrameLayout

/** Clear the translucent window buffer before compositing its transparent children.
 * Some secondary-display drivers otherwise retain pixels from the opaque startup frame. */
internal class ChatSurfaceLayout(context: Context) : FrameLayout(context) {
    var wallpaperVisible = false
        set(value) { if (field != value) { field = value; invalidate() } }
    override fun dispatchDraw(canvas: Canvas) {
        if (wallpaperVisible) canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        super.dispatchDraw(canvas)
    }
}
