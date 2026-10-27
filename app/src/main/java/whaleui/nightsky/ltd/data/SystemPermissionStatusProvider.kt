package whaleui.nightsky.ltd.data

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whaleui.nightsky.ltd.domain.settings.PermissionState
import whaleui.nightsky.ltd.domain.settings.PermissionStatusProvider
import whaleui.nightsky.ltd.domain.settings.RequiredPermission
import whaleui.nightsky.ltd.domain.settings.SettingsRepository

/**
 * 权限与后台常驻状态的真实实现。
 *
 * 每一项的来源：
 * - 通知：[NotificationManagerCompat.areNotificationsEnabled]（不需要弹权限框也能查）；
 * - 精确闹钟：API 31+ 用 [AlarmManager.canScheduleExactAlarms]，更低版本没有这个要求，返回 [PermissionState.NotRequired]；
 *   13+ 声明了 USE_EXACT_ALARM（安装即授予、不能撤销），这一行恒为「已授予」，保留显示让用户知道闹钟是准点的（F5）；
 * - 忽略电池优化：[PowerManager.isIgnoringBatteryOptimizations]，没忽略就是 [PermissionState.Denied]；
 * - 开机自启：系统没有公开查询接口，永远 [PermissionState.Unknown]，只做说明不做判断；
 * - 后台常驻：不是系统权限，取 [SettingsRepository] 里的 keepAliveEnabled 开关；
 * - 相册和文件：走系统选择器，永远 [PermissionState.NotRequired]。
 *
 * 刷新时机（对应契约里的三条）：
 * 1. 订阅 [statusUpdates] 立刻算一次；
 * 2. Activity 回到前台（包括从系统设置页返回）——只注册 [Application.ActivityLifecycleCallbacks] 的
 *    onActivityResumed，不保存 Activity 引用，进程级单例所以不需要反注册；
 * 3. 设置里的 keepAliveEnabled 变化；另外 [refresh] 供权限申请返回后主动要求重算。
 *
 * 线程模型：[statuses] 同步读 [_cachedStatuses]；需要重算时把 tick +1，[statusUpdates] 的
 * 每个订阅者各自算一遍并回写缓存。缓存用 @Volatile，tick 用 [MutableStateFlow]（自带并发合并），
 * 因此 [refresh] 可以从任意线程调用。
 *
 * @param context 只取 applicationContext，不持有 Activity。
 * @param settingsRepository 用来读「后台常驻」开关。
 */
class SystemPermissionStatusProvider(
    context: Context,
    private val settingsRepository: SettingsRepository,
) : PermissionStatusProvider {

    private val appContext: Context = context.applicationContext

    /** 进程级作用域：这个类是 Koin 单例，跟着进程走，不随页面销毁。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 「后台常驻」开关的同步缓存：[statuses] 是同步函数，DataStore 只能异步读，
     * 所以这里由 [scope] 订阅设置流来保持最新值。
     */
    @Volatile
    private var keepAliveEnabled: Boolean = false

    /** 最近一次算出的结果；主动置空表示「下次读的时候重新算」。 */
    @Volatile
    private var cachedStatuses: Map<RequiredPermission, PermissionState>? = null

    /** 每要求重算一次就 +1；StateFlow 会把并发多次刷新合并成一次重发，天然幂等。 */
    private val refreshTick = MutableStateFlow(0)

    init {
        // 设置里的「后台常驻」开关变化 → 更新同步缓存 + 广播重算。
        scope.launch {
            settingsRepository.settings
                .map { it.keepAliveEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    keepAliveEnabled = enabled
                    invalidate()
                }
        }

        // 每次回到前台（含从系统设置页返回）都重算；只记 onActivityResumed，不碰 Activity 实例。
        (appContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    invalidate()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    override val statusUpdates: Flow<Map<RequiredPermission, PermissionState>> = refreshTick
        // 每个订阅者收到 tick 时现算一次，保证「订阅立刻发一次」且拿到的是最新系统状态。
        .map { computeStatuses() }
        .onEach { statuses -> cachedStatuses = statuses }

    override fun statuses(): Map<RequiredPermission, PermissionState> =
        cachedStatuses ?: computeStatuses().also { cachedStatuses = it }

    override fun missing(): List<RequiredPermission> {
        val current = statuses()
        // 顺序固定跟着 RequiredPermission.entries，界面上的「N 项未开启」不会跳动。
        return RequiredPermission.entries.filter { current[it] == PermissionState.Denied }
    }

    override fun refresh() {
        invalidate()
    }

    /** 让缓存失效并通知所有订阅者重算。幂等、任意线程可调。 */
    private fun invalidate() {
        cachedStatuses = null
        refreshTick.update { it + 1 }
    }

    private fun computeStatuses(): Map<RequiredPermission, PermissionState> =
        RequiredPermission.entries.associateWith { permission -> stateOf(permission) }

    private fun stateOf(permission: RequiredPermission): PermissionState = when (permission) {
        RequiredPermission.Notifications ->
            if (notificationsEnabled()) PermissionState.Granted else PermissionState.Denied

        RequiredPermission.ExactAlarm -> exactAlarmState()

        RequiredPermission.BatteryOptimization -> batteryOptimizationState()

        // 没有公开接口能查，只能让用户自己去系统设置里确认。
        RequiredPermission.BootStart -> PermissionState.Unknown

        RequiredPermission.KeepAlive ->
            if (keepAliveEnabled) PermissionState.Granted else PermissionState.Denied

        // 相册 / 文件走系统选择器，不申请存储权限。
        RequiredPermission.MediaFiles -> PermissionState.NotRequired
    }

    private fun notificationsEnabled(): Boolean = try {
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    } catch (e: Exception) {
        // 查询失败按「没开」处理，界面至少能给出去开启的入口。
        false
    }

    /** API 31 起才有精确闹钟权限；更低版本系统默认允许精确闹钟。 */
    private fun exactAlarmState(): PermissionState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return PermissionState.NotRequired
        }
        return try {
            val alarmManager = appContext.getSystemService(AlarmManager::class.java)
            if (alarmManager != null && alarmManager.canScheduleExactAlarms()) {
                PermissionState.Granted
            } else {
                PermissionState.Denied
            }
        } catch (e: Exception) {
            PermissionState.Denied
        }
    }

    private fun batteryOptimizationState(): PermissionState = try {
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        val ignoring = powerManager != null &&
            powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
        if (ignoring) PermissionState.Granted else PermissionState.Denied
    } catch (e: Exception) {
        PermissionState.Denied
    }
}
