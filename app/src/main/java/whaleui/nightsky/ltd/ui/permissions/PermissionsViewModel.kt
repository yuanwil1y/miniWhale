package whaleui.nightsky.ltd.ui.permissions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whaleui.nightsky.ltd.service.KeepAliveService
import whaleui.nightsky.ltd.domain.settings.PermissionState
import whaleui.nightsky.ltd.domain.settings.PermissionStatusProvider
import whaleui.nightsky.ltd.domain.settings.SettingsRepository
import whaleui.nightsky.ltd.domain.settings.RequiredPermission
import whaleui.nightsky.ltd.ui.settings.SettingsToast
import whaleui.nightsky.ltd.ui.settings.SettingsToastKind
import java.util.concurrent.atomic.AtomicLong
import whaleui.nightsky.ltd.util.runCatchingCancellable

/**
 * 权限与后台常驻页的状态机。
 *
 * - 状态全部来自 [PermissionStatusProvider]（真实系统检查），
 *   它会在每次回到前台 / 开关变化时重发，所以从系统设置页回来会自动刷新；
 * - 「后台常驻」开关落 [SettingsRepository]，真正的保活是
 *   [KeepAliveService]；
 * - 需要 Activity 才能完成的通知权限申请通过 [events] 交给 MainActivity。
 */
class PermissionsViewModel(
    application: Application,
    private val provider: PermissionStatusProvider,
    private val settingsRepository: SettingsRepository,
    private val alarmReschedule: whaleui.nightsky.ltd.alarm.WhaleAlarmReschedule,
    /** 应用级 scope：重排闹钟要跑完，不跟着页面走 */
    private val appScope: kotlinx.coroutines.CoroutineScope,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PermissionsUiState())
    val uiState: StateFlow<PermissionsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PermissionsEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<PermissionsEvent> = _events.asSharedFlow()

    /** 用户在通知权限弹窗还没回来时点了「后台常驻」：拿到结果后要不要接着开。 */
    private var pendingKeepAlive = false

    private val toastIds = AtomicLong()
    private var toastJob: Job? = null

    /** 从系统设置页回来时精确闹钟权限变了 → 重排全部闹钟（W-018）。 */
    private val exactAlarmWatcher = whaleui.nightsky.ltd.alarm.ExactAlarmPermissionWatcher()

    init {
        viewModelScope.launch {
            provider.statusUpdates.collect { statuses ->
                _uiState.update { it.copy(statuses = statuses, isLoading = false) }
                if (exactAlarmWatcher.changed(statuses[RequiredPermission.ExactAlarm])) {
                    // 重排要跑完，不跟着页面走：挂在应用级 scope 上
                    appScope.launch { alarmReschedule.all() }
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(keepAliveChecked = settings.keepAliveEnabled) }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 后台常驻开关
    // -----------------------------------------------------------------------

    /**
     * 打开前先确认通知权限：没开就先去申请，拿到结果后再继续（见
     * [onNotificationPermissionResult]）。
     */
    fun setKeepAlive(enabled: Boolean) {
        if (_uiState.value.keepAliveBusy) return
        if (enabled && _uiState.value.stateOf(RequiredPermission.Notifications) != PermissionState.Granted) {
            pendingKeepAlive = true
            viewModelScope.launch { _events.emit(PermissionsEvent.RequestNotificationPermission) }
            return
        }
        applyKeepAlive(enabled)
    }

    private fun applyKeepAlive(enabled: Boolean) {
        // 先动界面（跟手），失败再回滚。
        _uiState.update { it.copy(keepAliveChecked = enabled, keepAliveBusy = true) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val ok = runCatchingCancellable {
                settingsRepository.setKeepAliveEnabled(enabled)
                if (enabled) {
                    KeepAliveService.start(app)
                } else {
                    KeepAliveService.stop(app)
                    true
                }
            }.getOrDefault(false)
            if (!ok) {
                runCatchingCancellable { settingsRepository.setKeepAliveEnabled(!enabled) }
                _uiState.update { it.copy(keepAliveChecked = !enabled, keepAliveBusy = false) }
                showToast("系统不让开后台常驻，先把电池优化关掉再试", SettingsToastKind.Error)
            } else {
                _uiState.update { it.copy(keepAliveBusy = false) }
                provider.refresh()
            }
        }
    }

    /** 通知权限弹窗 / 系统设置页返回后的结果。 */
    fun onNotificationPermissionResult(granted: Boolean) {
        provider.refresh()
        if (!pendingKeepAlive) return
        pendingKeepAlive = false
        if (granted) {
            applyKeepAlive(true)
        } else {
            _uiState.update { it.copy(keepAliveChecked = false) }
            showToast("通知没打开，后台常驻开不了", SettingsToastKind.Error)
        }
    }

    /** 页面重新进入 / 从系统页面回来时主动重算一次。 */
    fun refresh() {
        provider.refresh()
    }

    fun enterPage() {
        _uiState.update { it.copy(keepAliveBusy = false) }
        provider.refresh()
    }

    // -----------------------------------------------------------------------
    // 提示条
    // -----------------------------------------------------------------------

    private fun showToast(text: String, kind: SettingsToastKind) {
        val toast = SettingsToast(id = toastIds.incrementAndGet(), text = text, kind = kind)
        _uiState.update { it.copy(toast = toast) }
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(4_000)
            if (_uiState.value.toast?.id == toast.id) {
                _uiState.update { it.copy(toast = null) }
            }
        }
    }

    fun dismissToast() {
        toastJob?.cancel()
        _uiState.update { it.copy(toast = null) }
    }
}
