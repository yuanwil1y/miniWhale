package whaleui.nightsky.ltd.whale

import android.content.Context
import kotlin.uuid.Uuid

/**
 * 我们自己的少量运行时标记（不属于 RikkaHub 的设置体系，也不进 DataStore）。
 *
 * 只存**非敏感**的运行期索引，目前只有一条：我们那个固定对话的 id。
 * 用 SharedPreferences 而不是 DataStore，是因为它要在 `suspend` 之外被同步读到，且只有一个键。
 *
 * 备份策略：`allowBackup=false`，且 `backup_rules` / `data_extraction_rules` 里排除了 `sharedpref`。
 */
class WhaleRuntimeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 固定对话的 id；还没创建过时返回 null。 */
    var fixedConversationId: Uuid?
        get() = prefs.getString(KEY_FIXED_CONVERSATION, null)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_FIXED_CONVERSATION) else putString(KEY_FIXED_CONVERSATION, value.toString())
            }.apply()
        }

    /** 小鲸鱼助手的 id 缓存（真正的数据在 RikkaHub 的 settings 里，这里只是加速查找）。 */
    var whaleAssistantId: Uuid?
        get() = prefs.getString(KEY_WHALE_ASSISTANT, null)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_WHALE_ASSISTANT) else putString(KEY_WHALE_ASSISTANT, value.toString())
            }.apply()
        }

    /** 首次进聊天页时是否已经自动申请过通知权限（W-019）：只弹一次，被拒后不再自动弹。 */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "whale_runtime"
        const val KEY_FIXED_CONVERSATION = "fixed_conversation_id"
        const val KEY_WHALE_ASSISTANT = "whale_assistant_id"
        const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
    }
}
