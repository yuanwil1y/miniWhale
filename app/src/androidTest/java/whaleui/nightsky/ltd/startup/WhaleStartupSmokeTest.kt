package whaleui.nightsky.ltd.startup

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.RouteActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 冷启动冒烟测试（任务书 round4 3.3）。
 *
 * 为什么要它：round3 漏注册一个 Koin bean，App 一启动就崩，只有装到模拟器上才发现。
 * 这个 instrumented 测试冷启动 [RouteActivity]，**10 秒内不崩**就算通过；
 * Koin 会在 `RikkaHubApp.onCreate` 里真的解析一遍依赖图，缺注册会直接抛异常 → 测试失败。
 *
 * 运行：`adb -e shell am instrument -w -e class whaleui.nightsky.ltd.startup.WhaleStartupSmokeTest \
 *        ltd.nightsky.whale.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class WhaleStartupSmokeTest {

    @Test
    fun coldStartReachesChatPageWithinTenSeconds() {
        // 首次进聊天页会弹系统的通知权限框（W-019），它盖在页面上会让 Activity 停在 PAUSED；
        // 这里只验证冷启动，先当作已经问过
        val target = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        whaleui.nightsky.ltd.whale.WhaleRuntimeStore(target).notificationPermissionAsked = true
        ActivityScenario.launch(RouteActivity::class.java).use { scenario ->
            val deadline = System.currentTimeMillis() + 10_000
            var resumed = false
            while (System.currentTimeMillis() < deadline && !resumed) {
                scenario.onActivity { activity ->
                    resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                }
                if (!resumed) Thread.sleep(200)
            }
            assertEquals("冷启动 10 秒内没有进入 RESUMED（可能启动就崩了）", true, resumed)
        }
    }
}