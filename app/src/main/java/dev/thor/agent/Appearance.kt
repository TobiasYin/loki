package dev.thor.agent

import android.content.Context
import android.content.res.Configuration

internal enum class ColorMode(val key: String, val label: String) {
    SYSTEM("system", "跟随系统"), LIGHT("light", "亮色"), DARK("dark", "暗色");
    fun isDark(systemDark: Boolean) = when (this) { SYSTEM -> systemDark; LIGHT -> false; DARK -> true }
    companion object { fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: SYSTEM }
}

/** Shared native colors match the explicit light/dark CSS tokens. */
internal data class AppearancePalette(val dark: Boolean) {
    val background = if (dark) 0xff181b20.toInt() else 0xfff8f9fc.toInt()
    val surface = if (dark) 0xff252a33.toInt() else 0xffffffff.toInt()
    val ink = if (dark) 0xffedf0f5.toInt() else 0xff202632.toInt()
    val muted = if (dark) 0xffaeb8c8.toInt() else 0xff526074.toInt()
    val accent = if (dark) 0xff91b7ff.toInt() else 0xff245dc1.toInt()
    val scrimAlpha = if (dark) .30f else .22f
    fun wallpaperScrimAlpha(opacity: Int) = 1f - opacity.coerceIn(0, 100) / 100f * (1f - scrimAlpha)
}

internal object Appearance {
    fun mode(context: Context) = ColorMode.parse(context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("mode", null))
    fun save(context: Context, mode: ColorMode) { context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("mode", mode.key).apply() }
    fun palette(context: Context) = AppearancePalette(mode(context).isDark(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES))
    fun webScript(context: Context) = "document.documentElement.dataset.theme='${if (palette(context).dark) "dark" else "light"}';"
}
