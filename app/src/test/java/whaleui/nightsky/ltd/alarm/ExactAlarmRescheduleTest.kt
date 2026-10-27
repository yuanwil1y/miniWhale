package whaleui.nightsky.ltd.alarm

import org.junit.Assert.assertTrue
import org.junit.Test

/** W-018：精确闹钟权限授予后要重排全部闹钟。 */
class ExactAlarmRescheduleTest {

    @Test
    fun `receiver reschedules when exact alarm permission is granted`() {
        assertTrue(
            "授权后已排的闹钟要从非精确换成精确",
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" in WhaleAlarmReceiver.RESCHEDULE_ACTIONS,
        )
    }
}
