package dev.thor.agent

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.Collator
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

internal data class BackgroundAppEntry(val packageName: String, val label: String, val configured: Boolean)
internal enum class BackgroundAppFilter { ALL, CONFIGURED, UNCONFIGURED }
internal fun filterBackgroundApps(apps: List<BackgroundAppEntry>, query: String, filter: BackgroundAppFilter) = apps.filter {
    it.label.contains(query.trim(), ignoreCase = true) && when (filter) {
        BackgroundAppFilter.ALL -> true
        BackgroundAppFilter.CONFIGURED -> it.configured
        BackgroundAppFilter.UNCONFIGURED -> !it.configured
    }
}

/** A full-height secondary page. Only visible rows load icons; discovery stays off main. */
internal class BackgroundAppsPage(
    private val activity: Activity,
    private val store: ChatBackgroundStore,
    private val foreground: () -> CompletableFuture<String?>,
    private val openApp: (String) -> Unit,
    initialQuery: String = "",
    initialFilter: BackgroundAppFilter = BackgroundAppFilter.ALL,
) : AutoCloseable {
    private val ui = SettingsUi(activity)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var pending = false
    private var currentPackage: String? = null
    private var loaded = false
    private var apps = emptyList<BackgroundAppEntry>()
    private var visible = emptyList<BackgroundAppEntry>()
    var query = initialQuery; private set
    var filter = initialFilter; private set
    val view = ui.column().apply { setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(8)) }
    private val icons = android.util.LruCache<String, Drawable>(48)
    private val current = ui.card(view)
    private val search = EditText(activity).apply {
        hint = "搜索应用名称"; contentDescription = "搜索应用名称"; setSingleLine(true)
        setTextColor(ui.ink); setHintTextColor(ui.muted); textSize = 15f
        setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)); background = ui.shape(ui.surface, 16)
        minimumHeight = ui.dp(44); setText(initialQuery)
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
    }
    private val filters = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(8), 0, ui.dp(4)) }
    private val status = ui.text("正在加载应用…", 12f, true).apply { setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(6)) }
    private val list = ListView(activity).apply { divider = null; clipToPadding = false; tag = "background-app-list" }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = visible.size
        override fun getItem(position: Int) = visible[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val app = visible[position]
            return appRow(app, if (app.packageName == currentPackage) "当前上屏" else if (app.configured) "已配置" else "跟随全局") { openApp(app.packageName) }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            if (!pending && view.isAttachedToWindow && view.windowVisibility == View.VISIBLE) {
                pending = true
                foreground().whenComplete { pkg, _ -> main.post {
                    pending = false
                    if (!closed) {
                        val changed = currentPackage != pkg
                        currentPackage = pkg; renderCurrent()
                        if (changed) {
                            if (pkg != null && apps.none { it.packageName == pkg }) loadApps() else renderList()
                        }
                    }
                } }
            }
            main.postDelayed(this, 1500)
        }
    }
    init {
        view.addView(search)
        view.addView(filters)
        view.addView(status)
        view.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        list.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString().orEmpty(); renderList() }
            override fun afterTextChanged(s: Editable?) {}
        })
        current.addView(ui.text("正在识别当前上屏应用…", 14f, true).apply { setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)) })
        loadApps(); main.post(tick)
    }
    private fun label(pkg: String) = runCatching { activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(pkg, 0)).toString().takeIf { it != pkg } ?: "未命名应用" }.getOrDefault("已移除的应用")
    private fun icon(pkg: String): Drawable = icons.get(pkg) ?: runCatching { activity.packageManager.getApplicationIcon(pkg) }
        .getOrElse { activity.getDrawable(android.R.drawable.sym_def_app_icon)!! }.also { icons.put(pkg, it) }
    private fun loadApps() {
        val currentPkg = currentPackage
        worker.execute {
            val configured = store.all().keys - ChatBackgroundStore.GLOBAL
            val packages = runCatching { activity.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName } }.getOrDefault(emptyList()).toSet() + configured + listOfNotNull(currentPkg)
            val collator = Collator.getInstance(Locale.getDefault())
            val result = packages.map { BackgroundAppEntry(it, label(it), it in configured) }.sortedWith { a, b -> collator.compare(a.label, b.label) }
            main.post { if (!closed) { apps = result; loaded = true; renderCurrent(); renderList() } }
        }
    }
    private fun renderCurrent() {
        current.removeAllViews()
        val pkg = currentPackage
        if (pkg == null) {
            current.addView(ui.text("当前上屏 · 暂未识别", 16f).apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(4)) })
            current.addView(ui.text("请检查上下屏映射与无障碍服务", 13f, true).apply { setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(14)) })
        } else {
            val app = apps.firstOrNull { it.packageName == pkg } ?: BackgroundAppEntry(pkg, label(pkg), pkg in store.all())
            current.addView(appRow(app, "当前上屏") { openApp(pkg) })
        }
    }
    private fun renderList() {
        filters.removeAllViews()
        val configured = apps.count { it.configured }
        listOf(BackgroundAppFilter.ALL to "全部 ${apps.size}", BackgroundAppFilter.CONFIGURED to "已配置 $configured",
            BackgroundAppFilter.UNCONFIGURED to "未配置 ${apps.size - configured}").forEach { (value, title) ->
            filters.addView(ui.button(title) { filter = value; renderList() }.apply {
                tag = "background-app-filter-${value.name.lowercase()}"; isSelected = filter == value
                setTextColor(if (isSelected) ui.accent else ui.muted)
                alpha = if (isSelected) 1f else .75f
                setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, ui.dp(40), 1f).apply { marginEnd = ui.dp(4) })
        }
        visible = filterBackgroundApps(apps, query, filter)
        status.text = when {
            !loaded -> "正在加载应用…"
            visible.isEmpty() && query.isNotBlank() -> "没有找到匹配的应用"
            visible.isEmpty() && filter == BackgroundAppFilter.CONFIGURED -> "还没有配置专属背景"
            visible.isEmpty() -> "没有符合条件的应用"
            else -> "${visible.size} 个应用 · 点按设置专属背景"
        }
        adapter.notifyDataSetChanged()
    }
    private fun appRow(app: BackgroundAppEntry, state: String, action: () -> Unit): View {
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(68); setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            background = ui.shape(ui.surface, 18); isClickable = true; isFocusable = true
            contentDescription = "${app.label}，$state${if (app.configured && state == "当前上屏") "，已配置" else ""}"
            setOnClickListener { action() }
        }
        row.addView(ImageView(activity).apply { setImageDrawable(icon(app.packageName)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), 0, ui.dp(8), 0)
            addView(ui.text(app.label, 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(ui.text(if (state == "当前上屏") "当前上屏 · ${if (app.configured) "已配置专属背景" else "跟随全局背景"}" else if (app.configured) "已配置专属背景" else "未配置 · 跟随全局背景", 12f, true))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ui.text("›", 24f, true))
        return FrameLayout(activity).apply {
            addView(row, FrameLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        }
    }
    override fun close() { closed = true; main.removeCallbacksAndMessages(null); worker.shutdown() }
}
