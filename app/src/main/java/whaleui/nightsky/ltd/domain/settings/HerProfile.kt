package whaleui.nightsky.ltd.domain.settings

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow

/**
 * 她在界面上的名字和头像（资料页「编辑」里改）。
 *
 * 只管**显示**：聊天顶栏、抽屉头部、资料页、通知标题和大图标。不进提示词、不改人设，
 * 也不影响导出 / 导入格式。默认是白色圆形头像 + 名字 [DEFAULT_NICKNAME]。
 *
 * @property avatarPath App 私有目录里头像文件的绝对路径；null = 默认白色头像。
 */
@Immutable
data class HerProfile(
    val nickname: String = DEFAULT_NICKNAME,
    val avatarPath: String? = null,
) {
    val hasCustomAvatar: Boolean get() = avatarPath != null

    companion object {
        const val DEFAULT_NICKNAME = "default"

        /** 昵称最多几个字符（按 Unicode 码点数，一个 emoji 算一个）。 */
        const val MAX_NICKNAME_LENGTH = 20

        /** 剩几个字时开始显示计数（Telegram 改名框的做法：快到上限才出数字）。 */
        const val COUNTER_THRESHOLD = 5

        /** 码点数：界面计数和截断都按它算，不会把 emoji 劈成两半。 */
        fun lengthOf(text: String): Int = text.codePointCount(0, text.length)

        /** 输入框里最多留 [MAX_NICKNAME_LENGTH] 个字符（多出来的截掉）。 */
        fun clampInput(text: String): String =
            if (lengthOf(text) <= MAX_NICKNAME_LENGTH) text
            else text.substring(0, text.offsetByCodePoints(0, MAX_NICKNAME_LENGTH))

        /** 存盘前的规整：换行压成空格、去掉首尾空白、截到上限；空的存成 [DEFAULT_NICKNAME]。 */
        fun normalizeNickname(raw: String): String {
            val oneLine = raw.replace('\n', ' ').replace('\r', ' ').trim()
            return clampInput(oneLine).trim().ifEmpty { DEFAULT_NICKNAME }
        }
    }
}

/** 她的名字和头像的存取。实现落在设置页同一个 DataStore（`whale_settings`）里，不进 Room。 */
interface HerProfileStore {
    val profile: Flow<HerProfile>

    /** 一次写完两项；[nickname] 会先过 [HerProfile.normalizeNickname]。 */
    suspend fun save(nickname: String, avatarPath: String?)
}

/** 头像文件的处理与清理；线上是 `whaleui.nightsky.ltd.data.HerAvatarFiles`，单测里换成内存实现。 */
interface HerAvatarStorage {
    /** 处理选中的图片：成功返回私有文件路径，失败返回给用户看的原因。 */
    fun import(uri: String): AvatarImport

    /** 删掉头像目录里除 [keep] 以外的文件。 */
    fun retainOnly(keep: String?)
}

sealed interface AvatarImport {
    data class Ok(val path: String) : AvatarImport
    data class Rejected(val reason: String) : AvatarImport
}
