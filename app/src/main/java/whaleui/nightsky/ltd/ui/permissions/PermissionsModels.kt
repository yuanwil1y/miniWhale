package whaleui.nightsky.ltd.ui.permissions

import androidx.compose.runtime.Immutable
import whaleui.nightsky.ltd.domain.settings.PermissionState
import whaleui.nightsky.ltd.domain.settings.RequiredPermission
import whaleui.nightsky.ltd.ui.settings.SettingsToast

// ---------------------------------------------------------------------------
// 权限与后台常驻页的界面状态。
//
// 页面只读这里的数据；权限状态全部来自 PermissionStatusProvider（真实系统接口），
// 开关状态来自 SettingsRepository，界面本身不持有任何系统检查逻辑。
// ---------------------------------------------------------------------------

/** 一行权限。 */
@Immutable
data class PermissionRowUi(
    val permission: RequiredPermission,
    /** 行标题（可以比 [RequiredPermission.title] 更适合界面，例如「后台常驻通知」）。 */
    val title: String,
    val subtitle: String,
    /** 第三行补充说明（三星电池说明 / 强行停止后收不到广播），没有则为 null。 */
    val note: String? = null,
    val state: PermissionState,
)

/** 一个分组。 */
@Immutable
data class PermissionGroupUi(
    val title: String,
    val rows: List<PermissionRowUi>,
)

/** 页面状态。 */
@Immutable
data class PermissionsUiState(
    val isLoading: Boolean = true,
    val statuses: Map<RequiredPermission, PermissionState> = emptyMap(),
    /** 「后台常驻」开关（存 SettingsRepository）。 */
    val keepAliveChecked: Boolean = false,
    /** 正在启停服务，避免连点。 */
    val keepAliveBusy: Boolean = false,
    val toast: SettingsToast? = null,
) {
    fun stateOf(permission: RequiredPermission): PermissionState =
        statuses[permission] ?: PermissionState.Unknown

    /** 顶部提示条统计的项数：只算 Denied。 */
    val deniedCount: Int get() = statuses.count { it.value == PermissionState.Denied }

    /** 没有任何 Denied 项就是「都开好了」。 */
    val allGranted: Boolean get() = statuses.isNotEmpty() && deniedCount == 0

    val groups: List<PermissionGroupUi> get() = buildGroups(this)
}

/** 权限页的一次性事件（需要 Activity 才能完成的动作）。 */
sealed interface PermissionsEvent {
    /** 打开系统通知权限申请（Android 13+）。 */
    data object RequestNotificationPermission : PermissionsEvent
}

/** 三星（One UI）自己的后台限制，系统没有公开接口能检测，只能给提示 + 入口。 */
const val SamsungBatteryNote =
    "三星手机还要在「设置 → 电池 → 后台使用限制」里，把 miniWhale 加进「永不休眠的应用」"

/** 强行停止后系统不会再发开机广播，这一点写在那一行的说明里。 */
const val BootStartNote = "强行停止应用后收不到开机广播"

const val PermissionBannerAllGood = "都开好了，她可以准时来找你"

fun permissionBannerDeniedText(count: Int): String =
    "有 $count 项未开启，系统可能会在后台把 miniWhale 关掉，她就没法准时来找你了。"

/** 固定顺序、固定文案，和设计图 07-permissions.png 的分组和顺序一致。 */
private fun buildGroups(state: PermissionsUiState): List<PermissionGroupUi> = listOf(
    PermissionGroupUi(
        title = "让她准时来找你",
        rows = listOf(
            PermissionRowUi(
                permission = RequiredPermission.Notifications,
                title = "通知",
                subtitle = "她发的消息和提醒",
                state = state.stateOf(RequiredPermission.Notifications),
            ),
            PermissionRowUi(
                permission = RequiredPermission.ExactAlarm,
                title = "闹钟和提醒",
                subtitle = "主动消息和定时任务准时触发",
                state = state.stateOf(RequiredPermission.ExactAlarm),
            ),
            PermissionRowUi(
                permission = RequiredPermission.BatteryOptimization,
                title = "忽略电池优化",
                subtitle = "防止系统在后台杀掉 miniWhale",
                note = SamsungBatteryNote,
                state = state.stateOf(RequiredPermission.BatteryOptimization),
            ),
            PermissionRowUi(
                permission = RequiredPermission.BootStart,
                title = "开机自启",
                subtitle = "重启手机后自动恢复",
                note = BootStartNote,
                state = state.stateOf(RequiredPermission.BootStart),
            ),
        ),
    ),
    PermissionGroupUi(
        title = "常驻",
        rows = listOf(
            PermissionRowUi(
                permission = RequiredPermission.KeepAlive,
                title = "后台常驻通知",
                subtitle = "保持服务运行时显示的那条通知",
                state = state.stateOf(RequiredPermission.KeepAlive),
            ),
        ),
    ),
    PermissionGroupUi(
        title = "发送内容",
        rows = listOf(
            PermissionRowUi(
                permission = RequiredPermission.MediaFiles,
                title = "相册和文件",
                subtitle = "通过系统选择器发送",
                state = state.stateOf(RequiredPermission.MediaFiles),
            ),
        ),
    ),
)
