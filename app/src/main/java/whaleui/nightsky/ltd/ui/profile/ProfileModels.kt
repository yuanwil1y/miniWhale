package whaleui.nightsky.ltd.ui.profile

import androidx.compose.runtime.Immutable
import whaleui.nightsky.ltd.domain.settings.PermissionState
import whaleui.nightsky.ltd.domain.settings.RequiredPermission
import java.time.LocalDate

// ---------------------------------------------------------------------------
// 她的资料页（B）的界面状态。全部只读：读现成的设置 / 权限 / 人设状态，不加开关、不写数据。
// ---------------------------------------------------------------------------

/** 点一行能跳到哪个现成页面；null = 不可点。 */
enum class ProfileTarget { Settings, Permissions }

/** 「她」分组的一行：名字 + 状态文字（运行类的行带 🟢 / 🚧，和文字同字号），[active] 决定状态用主色还是灰。 */
@Immutable
data class HerRow(
    val title: String,
    val value: String,
    val active: Boolean,
    val target: ProfileTarget? = null,
)

@Immutable
data class ProfileUiState(
    /** soul.md 是否已导入（只看有没有，不读正文） */
    val soulImported: Boolean = false,
    /** 设置页「主动消息」总开关 */
    val proactiveEnabled: Boolean = true,
    /** 权限页同一个来源（PermissionStatusProvider） */
    val permissions: Map<RequiredPermission, PermissionState> = emptyMap(),
    /** 狗头军师技能：在助手启用列表里，且 SKILL.md 在 APK 里读得到 */
    val goutouInstalled: Boolean = false,
    /** 按天汇总的 token；null = 还没算完（第一次进页面） */
    val dailyTokens: Map<LocalDate, Long>? = null,
)

const val RowRunning = "🟢 运行中"
const val RowStopped = "🚧 未运行"
const val RowCanReach = "🟢 能按时找你"
const val RowCannotReach = "🚧 不能"

/** 「能按时找你」要这三项系统权限都已开启（u3 §2 的判定；开机自启系统查不到，不算）。 */
val ProactivePermissions = listOf(
    RequiredPermission.Notifications,
    RequiredPermission.ExactAlarm,
    RequiredPermission.BatteryOptimization,
)

/**
 * 「她」分组的五行（README §5 B）。
 *
 * 守护 / 狗头军师 / 热点新闻在代码里都是常驻的（没有开关），所以「运行中」= 她这一轮真的会发请求：
 * 填了 DeepSeek 密钥（[keyReady]）且小鲸鱼助手已建好（[assistantReady]）；狗头军师另要技能文件在（[ProfileUiState.goutouInstalled]）。
 * Proactive Agent：主动消息开着 + [ProactivePermissions] 全部已开启。
 */
fun herRows(state: ProfileUiState, keyReady: Boolean, assistantReady: Boolean): List<HerRow> {
    val canRun = keyReady && assistantReady
    fun running(on: Boolean) = if (on) RowRunning else RowStopped
    val canReach = state.proactiveEnabled &&
        ProactivePermissions.all { state.permissions[it] == PermissionState.Granted }
    return listOf(
        HerRow(
            title = "人设",
            value = if (state.soulImported) "已导入" else "未导入",
            active = state.soulImported,
            target = ProfileTarget.Settings,
        ),
        HerRow(title = "Persona Guard", value = running(canRun), active = canRun),
        HerRow(
            title = "狗头军师",
            value = running(canRun && state.goutouInstalled),
            active = canRun && state.goutouInstalled,
        ),
        HerRow(title = "热点新闻", value = running(canRun), active = canRun),
        HerRow(
            title = "Proactive Agent",
            value = if (canReach) RowCanReach else RowCannotReach,
            active = canReach,
            target = ProfileTarget.Permissions,
        ),
    )
}
