package whaleui.nightsky.ltd.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import whaleui.nightsky.ltd.domain.settings.PermissionStatusProvider
import whaleui.nightsky.ltd.domain.settings.PersonaKind
import whaleui.nightsky.ltd.domain.settings.PersonaStore
import whaleui.nightsky.ltd.domain.settings.SettingsRepository
import whaleui.nightsky.ltd.util.runCatchingCancellable
import java.time.LocalDate

/** 每条消息的（本地日期，usage.totalTokens）；usage 为空给 null。读库在调用方给的线程上。 */
fun interface TokenEntrySource {
    suspend fun entries(): Sequence<Pair<LocalDate, Int?>>
}

/**
 * 她的资料页（B）。只读：
 * - 人设是否导入：[PersonaStore.observe]（只看有没有文件，不读正文）；
 * - 主动消息开关：[SettingsRepository.settings]；
 * - 权限：[PermissionStatusProvider.statusUpdates]（权限页同一个来源）；
 * - 狗头军师技能文件：[skillInstalled]（读 APK assets，后台线程一次）；
 * - token 热力图：[enterPage] 时在后台把全部消息按天汇总一次，结果只存在内存里。
 */
class ProfileViewModel(
    personaStore: PersonaStore,
    settingsRepository: SettingsRepository,
    private val permissionStatusProvider: PermissionStatusProvider,
    private val tokenSource: TokenEntrySource,
    private val skillInstalled: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** 汇总耗时（毫秒）的去处；默认打 debug 日志，单测里换掉 */
    private val logTiming: (Long, Int) -> Unit = ::logLoadTiming,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            personaStore.observe(PersonaKind.Soul).collect { doc ->
                _uiState.update { it.copy(soulImported = doc != null) }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(proactiveEnabled = settings.proactiveEnabled) }
            }
        }
        viewModelScope.launch {
            permissionStatusProvider.statusUpdates.collect { statuses ->
                _uiState.update { it.copy(permissions = statuses) }
            }
        }
        viewModelScope.launch {
            val installed = withContext(io) { runCatching(skillInstalled).getOrDefault(false) }
            _uiState.update { it.copy(goutouInstalled = installed) }
        }
    }

    /** 每次进页面：刷新权限、重算一次热力图（上一次的结果先留着显示，算完再换）。 */
    fun enterPage() {
        permissionStatusProvider.refresh()
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val daily = withContext(io) {
                val started = System.nanoTime()
                var count = 0
                val totals = runCatchingCancellable {
                    TokenHeatmapMath.dailyTotals(tokenSource.entries().onEach { count++ })
                }.getOrDefault(emptyMap())
                logTiming((System.nanoTime() - started) / 1_000_000, count)
                totals
            }
            _uiState.update { it.copy(dailyTokens = daily) }
        }
    }
}

private fun logLoadTiming(millis: Long, messages: Int) {
    if (!me.rerere.rikkahub.BuildConfig.DEBUG) return
    // runCatching：JVM 单测里 android.util.Log 是 stub
    runCatching { android.util.Log.i("ProfileViewModel", "token heatmap: $messages messages in ${millis}ms") }
}
