package whaleui.nightsky.ltd.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import whaleui.nightsky.ltd.domain.settings.RequiredPermission
import whaleui.nightsky.ltd.ui.permissions.PermissionsEvent
import whaleui.nightsky.ltd.ui.permissions.PermissionsViewModel

// ---------------------------------------------------------------------------
// 权限与后台常驻：系统设置跳转（R1 / W-051 从 WhaleRoot 拆出来）
// 这里的 Intent 都是系统权限 / 设置页，按「能解析就用，不能解析就回退」的顺序尝试。
// ---------------------------------------------------------------------------

/** 拼系统设置页的 Intent 并依次尝试启动。 */
internal class WhalePermissionIntents(private val context: Context) {

    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** 依次尝试，第一个能解析的启动；全都不行就什么也不做。 */
    fun startFirst(vararg intents: Intent) {
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // 试下一个
            } catch (_: SecurityException) {
                // 试下一个
            }
        }
    }

    fun openAppNotificationSettings() {
        startFirst(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            appDetailsIntent(),
        )
    }

    fun openAppDetails() {
        startFirst(appDetailsIntent())
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startFirst(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}"),
                ),
                appDetailsIntent(),
            )
        }
    }

    fun openBatteryOptimization() {
        startFirst(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetailsIntent(),
        )
    }

    /** 三星「后台使用限制」入口：电池设置，解析不到就回退应用详情。 */
    fun openBatterySettings() {
        startFirst(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), appDetailsIntent())
    }
}

/** 权限页的动作：申请通知权限 / 跳系统设置 / 打开后台常驻。 */
internal class WhalePermissionActions(
    private val context: Context,
    private val intents: WhalePermissionIntents,
    private val permissionsViewModel: PermissionsViewModel,
    private val notificationPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
    /** 本进程内是否已经弹过系统通知权限框。用来区分「第一次申请」和「永久拒绝后不再弹窗」。 */
    private val notificationAsked: MutableState<Boolean>,
) {

    fun requestNotificationPermission() {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            permissionsViewModel.onNotificationPermissionResult(true)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Android 13 以下没有运行时通知权限，只能去系统通知设置页。
            intents.openAppNotificationSettings()
            return
        }
        val canAskAgain = (context as? Activity)
            ?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ?: true
        if (!canAskAgain && notificationAsked.value) {
            // 系统不会再弹申请框了：直接去应用的通知设置页。
            intents.openAppNotificationSettings()
            return
        }
        notificationAsked.value = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun handle(permission: RequiredPermission) {
        when (permission) {
            RequiredPermission.Notifications -> requestNotificationPermission()
            RequiredPermission.ExactAlarm -> intents.openExactAlarmSettings()
            RequiredPermission.BatteryOptimization -> intents.openBatteryOptimization()
            RequiredPermission.BootStart -> intents.openAppDetails()
            RequiredPermission.KeepAlive -> permissionsViewModel.setKeepAlive(true)
            RequiredPermission.MediaFiles -> Unit
        }
    }

    fun openBatterySettings() = intents.openBatterySettings()
}

/**
 * 通知权限的申请框、W-019 的首次自动申请、权限页发来的申请请求都在这里接好。
 * [screen] 是当前页面：第一次进聊天页时才自动申请。
 */
@Composable
internal fun rememberWhalePermissionActions(
    permissionsViewModel: PermissionsViewModel,
    screen: String,
): WhalePermissionActions {
    val context = LocalContext.current

    // 通知权限申请结果：只负责把状态刷新掉。
    // 「永久拒绝（系统不再弹窗）」的判断放在点击时：如果系统已经不再显示申请框，
    // 就直接把用户送到应用的通知设置页（见 requestNotificationPermission）。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionsViewModel.onNotificationPermissionResult(granted)
    }

    val notificationAsked = remember { mutableStateOf(false) }

    // W-019：Android 13+ 第一次进聊天页时申请一次通知权限（主动消息靠通知送达）；
    // 只自动弹这一次，被拒后不再弹，之后只能从「权限与后台常驻」页手动去开。
    LaunchedEffect(screen) {
        if (screen != ScreenChat) return@LaunchedEffect
        val runtime = whaleui.nightsky.ltd.whale.WhaleRuntimeStore(context)
        val ask = whaleui.nightsky.ltd.whale.WhaleNotificationPrompt.shouldAutoAsk(
            sdkInt = Build.VERSION.SDK_INT,
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            alreadyAsked = runtime.notificationPermissionAsked,
        )
        // shouldAutoAsk 已经按 sdkInt 判断过；这里再写一次版本判断，lint（InlinedApi）才看得出来
        if (ask && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runtime.notificationPermissionAsked = true
            notificationAsked.value = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val actions = WhalePermissionActions(
        context = context,
        intents = WhalePermissionIntents(context),
        permissionsViewModel = permissionsViewModel,
        notificationPermissionLauncher = notificationPermissionLauncher,
        notificationAsked = notificationAsked,
    )

    // 「后台常驻」打开前要先有通知权限：请求交给 Activity 发起。
    LaunchedEffect(permissionsViewModel) {
        permissionsViewModel.events.collect { event ->
            when (event) {
                PermissionsEvent.RequestNotificationPermission -> actions.requestNotificationPermission()
            }
        }
    }
    return actions
}
