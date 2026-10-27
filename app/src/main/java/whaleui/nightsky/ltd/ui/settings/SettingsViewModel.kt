package whaleui.nightsky.ltd.ui.settings

import whaleui.nightsky.ltd.ui.components.BulletinBus
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import whaleui.nightsky.ltd.data.BundleFormat
import whaleui.nightsky.ltd.data.ImportBundleReader
import whaleui.nightsky.ltd.data.WhaleDiaryImporter
import whaleui.nightsky.ltd.memory.MemoryMarkdownImporter
import whaleui.nightsky.ltd.memory.UserMdImporter
import whaleui.nightsky.ltd.domain.settings.ExportResult
import whaleui.nightsky.ltd.domain.settings.PersonaImportError
import whaleui.nightsky.ltd.domain.settings.PersonaImportResult
import whaleui.nightsky.ltd.domain.settings.PersonaKind
import whaleui.nightsky.ltd.domain.settings.PermissionState
import whaleui.nightsky.ltd.domain.settings.SecretProvider
import whaleui.nightsky.ltd.domain.settings.SpeechRateRange
import whaleui.nightsky.ltd.data.displayName
import java.util.concurrent.atomic.AtomicLong
import whaleui.nightsky.ltd.util.runCatchingCancellable

/**
 * 设置页的状态机。
 *
 * 依赖全部由 Koin 构造注入（见 `WhaleModule`），界面只读 [uiState]、所有动作都从这里走。
 */
class SettingsViewModel(
    application: Application,
    private val secretStore: whaleui.nightsky.ltd.domain.settings.SecretStore,
    private val settingsRepository: whaleui.nightsky.ltd.domain.settings.SettingsRepository,
    private val personaStore: whaleui.nightsky.ltd.domain.settings.PersonaStore,
    private val voiceCatalog: whaleui.nightsky.ltd.domain.settings.VoiceCatalog,
    private val exportService: whaleui.nightsky.ltd.domain.settings.ExportService,
    private val memoryRepository: whaleui.nightsky.ltd.domain.memory.MemoryRepository,
    private val resetService: whaleui.nightsky.ltd.data.DataResetService,
    private val permissionStatusProvider: whaleui.nightsky.ltd.domain.settings.PermissionStatusProvider,
    /** 导入日记写入口（appendSegment 只在 Room 实现上） */
    private val diaryRepository: whaleui.nightsky.ltd.data.RoomDiaryRepository,
    private val chatBundleImporter: whaleui.nightsky.ltd.data.ChatBundleImporter,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        SettingsUiState(voices = voiceCatalog.voices),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val toastIds = AtomicLong()
    private var toastJob: Job? = null
    private var rateJob: Job? = null
    private var previewJob: Job? = null

    /** 撤销「移除人设」用的临时副本，只存在内存里，4 秒后丢弃。 */
    private var removedPersona: Pair<PersonaKind, String>? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings, isLoading = false) }
            }
        }
        PersonaKind.entries.forEach { kind ->
            viewModelScope.launch {
                personaStore.observe(kind).collect { document ->
                    _uiState.update { state ->
                        state.copy(personas = state.personas + (kind to document))
                    }
                }
            }
        }
        refreshSecretStatus()
        // 权限一行读真实结果：回到前台 / 从系统设置页回来会自动重算。
        viewModelScope.launch {
            permissionStatusProvider.statusUpdates.collect { statuses ->
                val missing = statuses.count { it.value == PermissionState.Denied }
                _uiState.update { it.copy(permissionsMissing = missing) }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 页面进出
    // -----------------------------------------------------------------------

    /** 从抽屉进入。收起上次留下的浮层/提示，刷新"是否已设置"，守住滚动位置。 */
    fun enterPage() {
        viewModelScope.launch {
            voiceCatalog.stopPreview()
            _uiState.update {
                it.copy(
                    overlay = null,
                    personaSheet = null,
                    removePersonaKind = null,
                    toast = null,
                    previewingVoice = VoicePreviewState(),
                    scrollRestoreTick = it.scrollRestoreTick + 1,
                )
            }
        }
        refreshSecretStatus()
    }

    /**
     * 刷新「DeepSeek 密钥有没有填」（进页面、从高级设置回来时用，不动滚动位置和浮层）。
     * 只问有没有，不读原文；密钥本身只在 高级设置 → 供应商 里填（D）。
     */
    fun refreshSecretStatus() {
        viewModelScope.launch {
            val saved = runCatchingCancellable { secretStore.has(SecretProvider.DeepSeek) }.getOrDefault(true)
            _uiState.update { it.copy(deepSeekKeySaved = saved) }
        }
    }

    /** 「📥 导入与数据 → 导入」：打开导入面板（六个导入入口合成一行，u4 §3.6）。 */
    fun openImportSheet() {
        _uiState.update { it.copy(overlay = SettingsOverlay.ImportSheet, removePersonaKind = null) }
    }

    /** 页面滚动位置，由界面在滚动时回写；离开页面后仍然保留。 */
    fun onScrolled(offset: Int) {
        if (_uiState.value.scrollOffset != offset) {
            _uiState.update { it.copy(scrollOffset = offset) }
        }
    }

    // -----------------------------------------------------------------------
    // 音色
    // -----------------------------------------------------------------------

    fun openVoiceSheet() {
        _uiState.update { it.copy(overlay = SettingsOverlay.VoiceSheet) }
    }

    fun closeOverlay() {
        val overlay = _uiState.value.overlay
        if (overlay == SettingsOverlay.VoiceSheet) {
            voiceCatalog.stopPreview()
            previewJob?.cancel()
            _uiState.update {
                it.copy(
                    overlay = null,
                    previewingVoice = VoicePreviewState(),
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    overlay = null,
                    removePersonaKind = null,
                    personaSheet = null,
                )
            }
        }
    }

    fun selectVoice(voiceId: String) {
        // 语音工具是同步取音色，这里同步缓存（任务书 7.2）
        whaleui.nightsky.ltd.whale.WhaleVoicePrefs.update(voiceId = voiceId, speed = null)
        voiceCatalog.stopPreview()
        previewJob?.cancel()
        viewModelScope.launch {
            settingsRepository.setVoiceId(voiceId)
            _uiState.update {
                it.copy(
                    overlay = null,
                    previewingVoice = VoicePreviewState(),
                )
            }
        }
    }

    /** 试听：再点同一个正在播放的音色就是停止；同一时间只播一个。 */
    fun toggleVoicePreview(voiceId: String) {
        val current = _uiState.value.previewingVoice
        if (current.isPlaying && current.voiceId == voiceId) {
            voiceCatalog.stopPreview()
            previewJob?.cancel()
            _uiState.update { it.copy(previewingVoice = VoicePreviewState()) }
            return
        }
        voiceCatalog.stopPreview()
        previewJob?.cancel()
        _uiState.update { it.copy(previewingVoice = VoicePreviewState(voiceId, isPlaying = true)) }
        previewJob = viewModelScope.launch {
            // 换一个音色 / 关掉弹层时这次试听会被取消：取消照常往外抛，但「播放中」状态照样收掉
            try {
                runCatchingCancellable { voiceCatalog.preview(voiceId) }
            } finally {
                _uiState.update { state ->
                    if (state.previewingVoice.voiceId == voiceId) {
                        state.copy(previewingVoice = VoicePreviewState())
                    } else {
                        state
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 语速
    // -----------------------------------------------------------------------

    /** 拖动时先本地更新（跟手），停手 200ms 后写库。 */
    /** 保存「常住城市」（任务书 round4 4.3）：写设置 + 刷新进程内缓存。 */
    fun setHomeCity(city: String) {
        val trimmed = city.trim()
        whaleui.nightsky.ltd.whale.WhaleHomeCity.update(trimmed)
        _uiState.update { state -> state.copy(settings = state.settings.copy(homeCity = trimmed)) }
        viewModelScope.launch { settingsRepository.setHomeCity(trimmed) }
    }
    fun setSpeechRate(rate: Float) {
        whaleui.nightsky.ltd.whale.WhaleVoicePrefs.update(voiceId = null, speed = rate)
        val normalized = SpeechRateRange.normalize(rate)
        _uiState.update { state ->
            state.copy(settings = state.settings.copy(speechRate = normalized))
        }
        rateJob?.cancel()
        rateJob = viewModelScope.launch {
            delay(200)
            settingsRepository.setSpeechRate(normalized)
        }
    }

    // -----------------------------------------------------------------------
    // 主动消息 / 免打扰
    // -----------------------------------------------------------------------

    fun setProactiveEnabled(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(settings = state.settings.copy(proactiveEnabled = enabled))
        }
        viewModelScope.launch { settingsRepository.setProactiveEnabled(enabled) }
    }

    fun openQuietHoursSheet() {
        val settings = _uiState.value.settings
        _uiState.update {
            it.copy(
                overlay = SettingsOverlay.QuietHoursSheet,
                quietDraftStart = settings.quietStart,
                quietDraftEnd = settings.quietEnd,
            )
        }
    }

    fun setQuietDraftStart(time: java.time.LocalTime) {
        _uiState.update { it.copy(quietDraftStart = time) }
    }

    fun setQuietDraftEnd(time: java.time.LocalTime) {
        _uiState.update { it.copy(quietDraftEnd = time) }
    }

    fun saveQuietHours() {
        val start = _uiState.value.quietDraftStart
        val end = _uiState.value.quietDraftEnd
        _uiState.update { it.copy(overlay = null) }
        viewModelScope.launch {
            settingsRepository.setQuietHours(start, end)
            showToast("免打扰时段已更新", SettingsToastKind.Success)
        }
    }

    // -----------------------------------------------------------------------
    // 她的人设
    // -----------------------------------------------------------------------

    fun importPersona(kind: PersonaKind, uri: Uri) {
        viewModelScope.launch {
            when (val result = personaStore.import(kind, uri)) {
                is PersonaImportResult.Success -> {
                    _uiState.update {
                        it.copy(
                            personas = it.personas + (kind to result.document),
                            overlay = null,
                            personaSheet = null,
                        )
                    }
                    // 任务书 3.2：导入 user.md = 把里面的每一条并进记忆（只合并、不覆盖，也不改人设文件本身）
                    val importedMemories = if (kind == PersonaKind.User) importUserMdAsMemories() else 0
                    showToast(
                        if (kind == PersonaKind.User) {
                            "${kind.title} 已导入，新增 $importedMemories 条记忆"
                        } else {
                            "${kind.title} 已导入"
                        },
                        SettingsToastKind.Success,
                    )
                }

                is PersonaImportResult.Failure -> {
                    showToast(importErrorText(kind, result.error), SettingsToastKind.Error)
                }
            }
        }
    }

    /**
     * 把 user.md 按 `§` 拆成一条条记忆并进去（任务书 3.2 / 3.3）。
     *
     * - 只合并、不覆盖：已有同内容的不重复加，已有的不删；
     * - 分类固定 `AboutYou`、来源 `UserAdded`；
     * - `UserMdImporter.merge` 返回的是「要新增的、按文件顺序」，
     *   而 `RoomMemoryRepository.add` 是插到最前，所以这里倒序插入，让页面顺序与文件一致。
     *
     * 返回实际新增的条数（用于 toast）。
     */
    private suspend fun importUserMdAsMemories(): Int = runCatchingCancellable {
        val raw = personaStore.readText(PersonaKind.User) ?: return@runCatchingCancellable 0
        val incoming = UserMdImporter.parse(raw)
        if (incoming.isEmpty()) return@runCatchingCancellable 0
        val existing = memoryRepository.observeAll().first()
        val toAdd = UserMdImporter.merge(existing, incoming, System.currentTimeMillis())
        toAdd.asReversed().forEach { memory ->
            memoryRepository.add(
                content = memory.content,
                category = memory.category,
                origin = memory.origin,
            )
        }
        toAdd.size
    }.getOrDefault(0)

    /** 已导入时点整行：打开只读预览弹层。 */
    fun openPersonaSheet(kind: PersonaKind) {
        val document = _uiState.value.persona(kind) ?: return
        _uiState.update {
            it.copy(
                overlay = SettingsOverlay.PersonaSheet,
                personaSheet = PersonaSheetState(kind, document, isLoading = true),
                removePersonaKind = null,
            )
        }
        viewModelScope.launch {
            val text = personaStore.readText(kind)
            _uiState.update { state ->
                val sheet = state.personaSheet
                if (sheet != null && sheet.kind == kind) {
                    state.copy(personaSheet = sheet.copy(text = text, isLoading = false))
                } else {
                    state
                }
            }
        }
    }

    fun requestRemovePersona() {
        val sheet = _uiState.value.personaSheet ?: return
        _uiState.update { it.copy(removePersonaKind = sheet.kind) }
    }

    fun dismissRemovePersona() {
        _uiState.update { it.copy(removePersonaKind = null) }
    }

    /** 确认移除：先把正文留在内存里，撤销时原样写回。 */
    fun confirmRemovePersona() {
        val kind = _uiState.value.removePersonaKind ?: return
        viewModelScope.launch {
            val text = personaStore.readText(kind)
            personaStore.remove(kind)
            removedPersona = text?.let { kind to it }
            _uiState.update {
                it.copy(
                    overlay = null,
                    personaSheet = null,
                    removePersonaKind = null,
                    personas = it.personas + (kind to null),
                )
            }
            showToast(
                text = "${kind.title} 已移除",
                kind = SettingsToastKind.Info,
                actionLabel = if (text != null) "撤销" else null,
            )
        }
    }

    fun undoRemovePersona() {
        val removed = removedPersona ?: return
        removedPersona = null
        dismissToast()
        viewModelScope.launch {
            val document = personaStore.restore(removed.first, removed.second)
            if (document != null) {
                _uiState.update { it.copy(personas = it.personas + (removed.first to document)) }
                showToast("${removed.first.title} 已恢复", SettingsToastKind.Success)
            } else {
                showToast("恢复失败，请重新导入", SettingsToastKind.Error)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 数据导入 / 导出
    // -----------------------------------------------------------------------

    /**
     * 导入记忆：读导出包里的 `memory/memories.md`（或单个同格式的 md 文件），
     * 按内容去重后只增不减地写进记忆页。
     */
    fun importMemory(uri: Uri) {
        viewModelScope.launch {
            val summary = try {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val name = displayName(app, uri)
                    val bytes = readSelected(uri) ?: error("打不开这个文件")
                    val files = ImportBundleReader.extractMarkdown(bytes, name, BundleFormat::isMemoryMarkdown)
                    val incoming = files.flatMap { MemoryMarkdownImporter.parse(it.content) }
                    if (incoming.isEmpty()) {
                        error("文件里没有可导入的记忆（需要 ## 分类 和 - 内容）")
                    }
                    val existing = memoryRepository.observeAll().first()
                    val plan = MemoryMarkdownImporter.plan(existing, incoming)
                    // 仓库的 add 是「插到最前」，倒序插入让页面顺序和文件顺序一致。
                    plan.asReversed().forEach { item ->
                        memoryRepository.add(
                            content = item.content,
                            category = item.category,
                            origin = item.origin,
                        )
                    }
                    MemoryImportSummary(files = files.size, parsed = incoming.size, added = plan.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("导入失败：${e.message ?: e::class.simpleName}", SettingsToastKind.Error)
                return@launch
            }
            val skipped = summary.parsed - summary.added
            val suffix = if (skipped > 0) "，跳过 $skipped 条重复" else ""
            showToast(
                text = "记忆已导入 · 新增 ${summary.added} 条$suffix",
                kind = if (summary.added > 0) SettingsToastKind.Success else SettingsToastKind.Info,
            )
        }
    }

    /**
     * 导入日记（任务书 4.7）：可以选多个 md，也可以选一个 zip；只合并不覆盖。
     *
     * 在 ViewModel 里跑（不挂在界面的 Composable scope 上，离开页面也会做完），读文件和解压都在 IO 线程，
     * 每个文件有上限地流式读取。结果用系统 Toast 提示，文案和原来一致。
     */
    fun importDiary(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val text = try {
                withContext(Dispatchers.IO) { runDiaryImport(uris).summary() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "导入失败：" + (e.message ?: "未知原因")
            }
            showSystemToast(text)
        }
    }

    private suspend fun runDiaryImport(uris: List<Uri>): WhaleDiaryImporter.Result {
        val app = getApplication<Application>()
        val diaryRepo = diaryRepository
        val files = mutableListOf<WhaleDiaryImporter.SourceFile>()
        // 多选时所有文件合起来也不超过一个导出包的上限
        var remaining = ImportBundleReader.MAX_ARCHIVE_BYTES
        uris.forEach { uri ->
            val name = displayName(app, uri)
            val bytes = readSelected(uri, remaining) ?: return@forEach
            remaining -= bytes.size
            files += WhaleDiaryImporter.sourcesOf(name, bytes)
        }
        val existing = diaryRepo.observeAll().first().associate { it.date to it.content }
        val summary = WhaleDiaryImporter.importAll(files, existing)
        summary.contentByDate.forEach { (date, content) -> diaryRepo.setContent(date, content) }
        return summary
    }

    /**
     * 导入聊天记录：选导出的 zip（chat 目录下的 md），合并进固定对话。
     * 成功后回调 [onImported]（聊天页要重读一遍才会出现新节点）。
     */
    fun importChat(uri: Uri, onImported: () -> Unit) {
        viewModelScope.launch {
            val text = try {
                val result = withContext(Dispatchers.IO) {
                    val name = displayName(getApplication(), uri)
                    val bytes = readSelected(uri) ?: error("打不开这个文件")
                    val files = ImportBundleReader.extractMarkdown(
                        bytes = bytes,
                        displayName = name,
                        selector = BundleFormat::isChatMarkdown,
                    )
                    if (files.isEmpty()) error("这个包里没有聊天记录（chat/*.md）")
                    chatBundleImporter.import(files)
                }
                when (result) {
                    is whaleui.nightsky.ltd.data.ChatBundleImporter.Result.Success -> {
                        onImported()
                        buildString {
                            append("聊天记录已导入 · 新增 ${result.added} 条")
                            if (result.skipped > 0) append("，跳过 ${result.skipped} 条重复")
                        }
                    }

                    is whaleui.nightsky.ltd.data.ChatBundleImporter.Result.Failure ->
                        "导入失败：" + result.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "导入失败：" + (e.message ?: e::class.simpleName)
            }
            showSystemToast(text)
        }
    }

    /** 有上限地读选中的文件（超过 [limit] 直接拒绝，不先整读）；打不开返回 null。必须在 IO 线程调。 */
    private fun readSelected(uri: Uri, limit: Int = ImportBundleReader.MAX_ARCHIVE_BYTES): ByteArray? =
        getApplication<Application>().contentResolver.openInputStream(uri)
            ?.use { ImportBundleReader.readStream(it, limit) }

    /** 日记 / 聊天导入的结果：全局 Bulletin（U0：不再用系统 Toast）。 */
    private fun showSystemToast(text: String) {
        BulletinBus.post(text)
    }

    /** 导出全部数据（人设 / 记忆 / 日记 / 聊天记录 / 表情包）到一个 zip。 */
    fun exportAll(destination: Uri) {
        if (_uiState.value.exportInProgress != null) return
        _uiState.update { it.copy(exportInProgress = ExportKind.All) }
        viewModelScope.launch {
            when (val result = exportService.exportAll(destination)) {
                is ExportResult.Success -> {
                    _uiState.update { it.copy(exportInProgress = null) }
                    showToast(exportSuccessText(ExportKind.All, result.fileCount), SettingsToastKind.Success)
                }

                is ExportResult.Failure -> {
                    _uiState.update { it.copy(exportInProgress = null) }
                    showToast(exportErrorText(result.error), SettingsToastKind.Error)
                }
            }
        }
    }

    private data class MemoryImportSummary(val files: Int, val parsed: Int, val added: Int)

    // -----------------------------------------------------------------------
    // 清空数据
    // -----------------------------------------------------------------------

    /** 点「重置」：先弹确认框，不直接删。 */
    fun requestReset() {
        if (_uiState.value.resetInProgress) return
        _uiState.update { it.copy(resetDialog = true) }
    }

    fun dismissReset() {
        if (_uiState.value.resetInProgress) return
        _uiState.update { it.copy(resetDialog = false) }
    }

    /** 确认清空：soul / user / 记忆 / 日记 / 表情包；聊天记录、设置和密钥不动。 */
    fun confirmReset() {
        if (_uiState.value.resetInProgress) return
        _uiState.update { it.copy(resetDialog = false, resetInProgress = true) }
        viewModelScope.launch {
            val error = try {
                resetService.resetAll()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            _uiState.update { it.copy(resetInProgress = false) }
            if (error == null) {
                showToast(
                    "已清空 Soul.md · User.md · 记忆 · 日记 · 表情包",
                    SettingsToastKind.Success,
                )
            } else {
                showToast(
                    "清空失败：${error.message ?: error::class.simpleName}",
                    SettingsToastKind.Error,
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // 提示条
    // -----------------------------------------------------------------------

    fun showToast(
        text: String,
        kind: SettingsToastKind = SettingsToastKind.Info,
        actionLabel: String? = null,
    ) {
        val toast = SettingsToast(
            id = toastIds.incrementAndGet(),
            text = text,
            kind = kind,
            actionLabel = actionLabel,
        )
        _uiState.update { it.copy(toast = toast) }
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(4_000)
            if (_uiState.value.toast?.id == toast.id) {
                removedPersona = null
                _uiState.update { it.copy(toast = null) }
            }
        }
    }

    fun dismissToast() {
        toastJob?.cancel()
        _uiState.update { it.copy(toast = null) }
    }

    override fun onCleared() {
        voiceCatalog.stopPreview()
    }
}

private fun importErrorText(kind: PersonaKind, error: PersonaImportError): String = when (error) {
    PersonaImportError.NotReadable -> "打不开这个文件，换一个再试"
    PersonaImportError.NotText -> "只能是文本或 Markdown 文件"
    PersonaImportError.InvalidEncoding -> "文件编码不是 UTF-8，转成 UTF-8 再导入"
    PersonaImportError.TooLarge -> "文件超过 1 MB，先删减一些"
}
