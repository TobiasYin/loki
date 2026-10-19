package dev.thor.agent

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture

@RunWith(AndroidJUnit4::class)
class BackgroundAppsPageTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun until(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < end) { var done = false; ins.runOnMainSync { done = condition() }; if (done) return; SystemClock.sleep(40) }
        fail("Application page did not reach expected state")
    }
    @Test fun fullPageSupportsIconsSearchFiltersAndCurrentAppWithoutPackages() {
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone"))
        val context = ins.targetContext
        val store = ChatBackgroundStore(context)
        val pkg = context.packageName
        val saved = store.all()[pkg]
        store.save(pkg, (saved ?: BackgroundSpec()).copy(mode = "default"))
        val activity = ins.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var page: BackgroundAppsPage? = null
        try {
            EmulatorRuntimeTestSupport.awaitReady(activity)
            val method = MainActivity::class.java.getDeclaredMethod("chooseBackgroundApp").apply { isAccessible = true }
            ins.runOnMainSync { method.invoke(activity) }
            until { views(activity.window.decorView).filterIsInstance<ListView>().singleOrNull()?.adapter?.count?.let { it > 0 } == true }
            ins.runOnMainSync {
                val all = views(activity.window.decorView)
                assertTrue(all.filterIsInstance<ImageView>().any { it.drawable != null })
                assertFalse(all.filterIsInstance<TextView>().any { it.text.toString().contains(pkg) })
                assertTrue(all.filterIsInstance<TextView>().any { it.text.toString().startsWith("当前上屏") })
                val search = all.filterIsInstance<EditText>().single { it.contentDescription == "搜索应用名称" }
                search.setText("no matching application 834923")
            }
            until { views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "没有找到匹配的应用" } }
            ins.runOnMainSync {
                val search = views(activity.window.decorView).filterIsInstance<EditText>().single()
                search.setText("")
                views(activity.window.decorView).single { it.tag == "background-app-filter-configured" }.performClick()
            }
            until { views(activity.window.decorView).filterIsInstance<ListView>().single().adapter.count > 0 }
            ins.runOnMainSync {
                val list = views(activity.window.decorView).filterIsInstance<ListView>().single()
                assertTrue(list.height > 0)
                assertTrue((0 until list.adapter.count).all { (list.adapter.getItem(it) as BackgroundAppEntry).configured })
                (list.getChildAt(0) as ViewGroup).getChildAt(0).performClick()
                assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "背景来源" })
                MainActivity::class.java.getDeclaredMethod("onBackPressed").apply { isAccessible = true }.invoke(activity)
                assertTrue(views(activity.window.decorView).filterIsInstance<ListView>().isNotEmpty())
                MainActivity::class.java.getDeclaredMethod("closeSettings").apply { isAccessible = true }.invoke(activity)
                // Deterministic live foreground source validates the current-app card separately.
                page = BackgroundAppsPage(activity, store, { CompletableFuture.completedFuture(pkg) }, {})
                activity.addContentView(page!!.view, ViewGroup.LayoutParams(-1, -1))
            }
            until { views(page!!.view).filterIsInstance<TextView>().any { it.text.toString().startsWith("当前上屏 · 已配置") } }
            ins.runOnMainSync { assertFalse(views(page!!.view).filterIsInstance<TextView>().any { it.text.toString().contains(pkg) }) }
        } finally {
            ins.runOnMainSync { page?.close(); activity.finish() }
            if (saved == null) store.remove(pkg) else store.save(pkg, saved)
        }
    }
}
