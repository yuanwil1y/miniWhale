package whaleui.nightsky.ltd.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W-063：闹钟身份改成「key 编进 Intent 的 data」之后，在真机 / 模拟器的 AlarmManager 上验证：
 * 撞 hashCode 的两个 key 互不影响；旧格式（requestCode = hashCode、不带 data）登记的闹钟在重新登记 / 取消时被替换掉。
 * 只用测试专用的 key，测完全部取消。
 */
@RunWith(AndroidJUnit4::class)
class WhaleAlarmRegistrarTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val farFuture = System.currentTimeMillis() + 30L * 24 * 3600_000

    // "Aa" 和 "BB" 的 String.hashCode 都是 2112
    private val keys = listOf("b5-test-Aa", "b5-test-BB", "b5-test-legacy")

    private fun intent(key: String): Intent = WhaleAlarmReceiver.fireIntent(context, key, farFuture)

    private fun keyedExists(key: String): Boolean = PendingIntent.getBroadcast(
        context,
        WhaleAlarmRegistrar.KEYED_REQUEST_CODE,
        WhaleAlarmRegistrar.keyedIntent(key, intent(key)),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    ) != null

    private fun legacyPending(key: String, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        key.hashCode(),
        intent(key),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    @After
    fun tearDown() {
        keys.forEach { WhaleAlarmRegistrar.cancel(context, it, intent(it)) }
    }

    @Test
    fun colliding_keys_are_separate_alarms() {
        assertEquals("前提：两个 key 撞 hashCode", "b5-test-Aa".hashCode(), "b5-test-BB".hashCode())
        WhaleAlarmRegistrar.register(context, "b5-test-Aa", farFuture, intent("b5-test-Aa"))
        WhaleAlarmRegistrar.register(context, "b5-test-BB", farFuture + 60_000, intent("b5-test-BB"))
        assertEquals(true, keyedExists("b5-test-Aa"))
        assertEquals(true, keyedExists("b5-test-BB"))

        WhaleAlarmRegistrar.cancel(context, "b5-test-Aa", intent("b5-test-Aa"))

        assertEquals("取消一个不能连带另一个", false, keyedExists("b5-test-Aa"))
        assertEquals(true, keyedExists("b5-test-BB"))
    }

    @Test
    fun register_replaces_an_alarm_left_by_the_old_version() {
        // 旧版本的登记方式：requestCode = key.hashCode()，Intent 不带 data
        val legacy = legacyPending("b5-test-legacy", PendingIntent.FLAG_UPDATE_CURRENT)!!
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, farFuture, legacy)
        assertNotNull(legacyPending("b5-test-legacy", PendingIntent.FLAG_NO_CREATE))

        WhaleAlarmRegistrar.register(context, "b5-test-legacy", farFuture, intent("b5-test-legacy"))

        assertNull("旧格式闹钟要被取消，否则升级后会响两遍", legacyPending("b5-test-legacy", PendingIntent.FLAG_NO_CREATE))
        assertEquals(true, keyedExists("b5-test-legacy"))
    }

    @Test
    fun cancel_also_removes_an_old_version_alarm() {
        val legacy = legacyPending("b5-test-legacy", PendingIntent.FLAG_UPDATE_CURRENT)!!
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, farFuture, legacy)

        WhaleAlarmRegistrar.cancel(context, "b5-test-legacy", intent("b5-test-legacy"))

        assertNull(legacyPending("b5-test-legacy", PendingIntent.FLAG_NO_CREATE))
        assertEquals(false, keyedExists("b5-test-legacy"))
    }
}
