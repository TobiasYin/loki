package dev.thor.agent

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only navigates drafts on an emulator; never saves credentials or grants permissions. */
@RunWith(AndroidJUnit4::class)
class OnboardingNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun serviceShortcutsReturnToGuideAndPermissionsAreTiered() {
        assumeTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone"))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        EmulatorRuntimeTestSupport.awaitReady(activity)
        fun texts() = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        fun click(label: String) {
            val text = views(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == label }
            var target: View = text
            while (!target.isClickable) target = target.parent as View
            assertTrue(target.performClick())
        }
        instrumentation.runOnMainSync {
            MainActivity::class.java.getDeclaredMethod("showOnboarding", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, 0)
            assertTrue(texts().contains("必选 · LLM 模型"))
            assertTrue(texts().contains("可选 · 扩展能力"))
            click(texts().single { it.startsWith("连接 LLM 模型 ·") })
            assertTrue(texts().contains("添加模型服务"))
            click("‹ 返回")
            assertTrue(texts().contains("连接 AI 服务"))
            click("配置火山 AgentPlan")
            assertTrue(texts().contains("火山 AgentPlan"))
            assertTrue(views(activity.window.decorView).any {it.tag=="model-picker"})
            assertTrue(views(activity.window.decorView).any {it.tag=="provider-api-key"})
            click("‹ 返回")
            assertTrue(texts().contains("连接 AI 服务"))
            click(texts().single { it.startsWith("语音输入 ·") })
            assertTrue(texts().contains("语音输入"))
            click("‹ 返回")
            assertTrue(texts().contains("连接 AI 服务"))
            click(texts().single { it.startsWith("联网搜索 ·") })
            assertTrue(texts().contains("测试当前搜索连接"))
            click("‹ 返回")
            assertTrue(texts().contains("连接 AI 服务"))
            val next = views(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString() == "下一步 · 配置操作权限" }
            val host = MainActivity::class.java.getDeclaredField("host").apply { isAccessible = true }.get(activity) as ProductHost
            assertEquals(host.onboardingModelReady(), next.isEnabled)
            MainActivity::class.java.getDeclaredMethod("showOnboarding", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, 1)
            assertTrue(texts().contains("基础 · 无障碍服务"))
            assertTrue(texts().contains("进阶 · Shizuku 授权"))
            assertTrue(texts().contains("授权并绑定 Shizuku"))
            assertFalse(texts().any { it.startsWith("麦克风 ·") || it.startsWith("后台任务通知 ·") })
            click("‹ 返回")
            assertTrue(texts().contains("连接 AI 服务"))
        }
    }
}
