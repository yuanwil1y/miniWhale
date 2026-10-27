package whaleui.nightsky.ltd.whale

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W-019：Android 13+ 首次进聊天页申请一次通知权限，被拒后不再反复弹。 */
class WhaleNotificationPromptTest {

    @Test
    fun `first visit on android 13 without notifications asks once`() {
        assertTrue(WhaleNotificationPrompt.shouldAutoAsk(sdkInt = 33, notificationsEnabled = false, alreadyAsked = false))
        assertTrue(WhaleNotificationPrompt.shouldAutoAsk(sdkInt = 36, notificationsEnabled = false, alreadyAsked = false))
    }

    @Test
    fun `after being asked or denied it never pops up again`() {
        assertFalse(WhaleNotificationPrompt.shouldAutoAsk(sdkInt = 36, notificationsEnabled = false, alreadyAsked = true))
    }

    @Test
    fun `granted notifications or older android need no prompt`() {
        assertFalse(WhaleNotificationPrompt.shouldAutoAsk(sdkInt = 36, notificationsEnabled = true, alreadyAsked = false))
        assertFalse(WhaleNotificationPrompt.shouldAutoAsk(sdkInt = 32, notificationsEnabled = false, alreadyAsked = false))
    }
}
