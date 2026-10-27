package whaleui.nightsky.ltd.ui.sticker

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import whaleui.nightsky.ltd.sticker.SafStickerSource
import whaleui.nightsky.ltd.sticker.Sticker
import whaleui.nightsky.ltd.sticker.StickerImportPlan
import whaleui.nightsky.ltd.sticker.StickerImportReport
import whaleui.nightsky.ltd.sticker.StickerImporter
import whaleui.nightsky.ltd.sticker.StickerPack
import whaleui.nightsky.ltd.sticker.StickerRepository
import whaleui.nightsky.ltd.sticker.StickerSource
import whaleui.nightsky.ltd.sticker.ZipStickerSource
import whaleui.nightsky.ltd.ui.settings.SettingsToast
import whaleui.nightsky.ltd.ui.settings.SettingsToastKind
import whaleui.nightsky.ltd.util.runCatchingCancellable

// ---------------------------------------------------------------------------
// 设置 → 表情包管理的状态机（任务书 3.2）。
//
// 页面只读 [StickerManagerUiState]，所有动作走 [StickerManagerActions]；
// 依赖（StickerRepository / StickerImporter）由 StickerManagerEntry 从 Koin 拿，
// 这个 ViewModel 不注册进 Koin（按任务书要求由界面本地创建）。
//
// 导入是两段式的：先 inspect（只解析、查冲突），必要时弹出「已导入过」确认，
// 再 execute（后台复制文件、可取消、回调进度）；结束后弹汇总，关掉自动刷新列表
// （列表本身是 Room Flow，不用手动 reload）。
// ---------------------------------------------------------------------------

/** 一条列表项：系列 + 它的全部表情（按编号排好，第一张就是封面）。 */
internal data class StickerPackUi(
    val pack: StickerPack,
    val stickers: List<Sticker>,
) {
    val stickerCount: Int get() = stickers.size

    val cover: Sticker? get() = stickers.firstOrNull()
}

/** 导入弹窗的阶段。 */
internal enum class StickerImportStage {
    /** 正在 inspect，没有进度可显示。 */
    Inspecting,

    /** 有系列号冲突，等用户点「继续」。 */
    ConfirmingConflicts,

    /** execute 进行中，显示「已处理 X / Y」。 */
    Importing,

    /** 结束（成功 / 取消 / 失败都是这个阶段），等用户点「关闭」。 */
    Result,
}

/** 导入相关的界面状态；null 表示没有导入在跑。 */
internal data class StickerImportUiState(
    val stage: StickerImportStage,
    val conflicts: List<String> = emptyList(),
    val done: Int = 0,
    val total: Int = 0,
    val summary: String = "",
    val details: List<String> = emptyList(),
    val cancelled: Boolean = false,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** 整页状态。 */
internal data class StickerManagerUiState(
    val isLoading: Boolean = true,
    val packs: List<StickerPackUi> = emptyList(),
    /** 非 null 表示已经点进某个系列，界面显示它的网格。 */
    val openPackId: String? = null,
    /** 点开单张后底部弹层里的那张。 */
    val stickerSheet: Sticker? = null,
    /** 等待确认删除的系列 / 单张（确认框）。 */
    val pendingDeletePack: StickerPack? = null,
    val pendingDeleteSticker: Sticker? = null,
    /** 导入流程的浮层状态。 */
    val importUi: StickerImportUiState? = null,
    val toast: SettingsToast? = null,
) {
    val openPack: StickerPackUi? get() = packs.firstOrNull { it.pack.id == openPackId }

    val pendingDeletePackCount: Int
        get() = pendingDeletePack?.let { pack -> packs.firstOrNull { it.pack.id == pack.id }?.stickerCount } ?: 0

    /** 有没有盖住页面的东西；返回键先关这些，再交给外层导航。 */
    val hasOverlay: Boolean
        get() = openPackId != null || stickerSheet != null || pendingDeletePack != null ||
            pendingDeleteSticker != null || importUi != null
}

/** 页面能触发的全部动作。ViewModel 实现它，界面只认这个接口（方便 Preview 用假实现）。 */
internal interface StickerManagerActions {

    fun importFolder(uri: Uri)

    fun importZip(uri: Uri)

    fun openPack(packId: String)

    fun closePack()

    fun openSticker(sticker: Sticker)

    fun closeSticker()

    fun setEnabledForHer(packId: String, enabled: Boolean)

    fun requestDeletePack(pack: StickerPack)

    fun requestDeleteSticker(sticker: Sticker)

    fun cancelDelete()

    fun confirmDelete()

    /** 冲突提示里点「继续」。 */
    fun confirmImport()

    /** 冲突提示里点「取消」。 */
    fun cancelImportConfirm()

    /** 导入进度里点「取消」（不立刻关弹窗，等 execute 返回）。 */
    fun cancelImport()

    /** 汇总弹窗点「关闭」。 */
    fun dismissImportResult()

    fun dismissToast()

    /** 返回键：按浮层优先级逐层关闭；没有浮层时什么都不做（交给外层）。 */
    fun handleBack()
}

internal class StickerManagerViewModel(
    private val appContext: Context,
    private val repository: StickerRepository,
    private val importer: StickerImporter,
) : ViewModel(), StickerManagerActions {

    private val _uiState = MutableStateFlow(StickerManagerUiState())
    val uiState: StateFlow<StickerManagerUiState> = _uiState.asStateFlow()

    private val toastIds = AtomicLong()
    private var toastJob: Job? = null
    private var importJob: Job? = null

    // 导入的中间对象只活在 ViewModel 里，不进 UiState。
    private var pendingSource: StickerSource? = null
    private var pendingZip: ZipFile? = null
    private var pendingCacheFile: File? = null
    private var pendingPlan: StickerImportPlan? = null

    /** execute 每处理一张检查一次；用户点「取消」后置 true。 */
    @Volatile
    private var cancelRequested = false

    init {
        // 列表和网格都直接订阅 Room，删除 / 导入后自动刷新。
        viewModelScope.launch {
            combine(repository.observePacks(), repository.observeStickers()) { packs, stickers ->
                val byPack = stickers.groupBy { it.packId }
                packs.map { pack ->
                    StickerPackUi(
                        pack = pack,
                        stickers = byPack[pack.id].orEmpty().sortedBy { it.id },
                    )
                }
            }.collect { packList ->
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        packs = packList,
                        // 正在看的系列 / 单张被别处删掉时，把浮层一起收掉，不留空壳。
                        openPackId = state.openPackId?.takeIf { id -> packList.any { it.pack.id == id } },
                        stickerSheet = state.stickerSheet?.let { found(it.id, packList) },
                        pendingDeletePack = state.pendingDeletePack
                            ?.let { pack -> packList.firstOrNull { it.pack.id == pack.id }?.pack },
                        pendingDeleteSticker = state.pendingDeleteSticker?.let { found(it.id, packList) },
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 系列 / 单张的浏览
    // -----------------------------------------------------------------------

    /** 从别的页面进来时收掉上次留下的浮层（列表本身是 Flow，不需要重载）。 */
    fun onEnterPage() {
        if (_uiState.value.importUi != null) return
        _uiState.update {
            it.copy(
                openPackId = null,
                stickerSheet = null,
                pendingDeletePack = null,
                pendingDeleteSticker = null,
            )
        }
    }

    override fun openPack(packId: String) {
        if (_uiState.value.packs.none { it.pack.id == packId }) return
        _uiState.update { it.copy(openPackId = packId) }
    }

    override fun closePack() {
        _uiState.update { it.copy(openPackId = null) }
    }

    override fun openSticker(sticker: Sticker) {
        _uiState.update { it.copy(stickerSheet = sticker) }
    }

    override fun closeSticker() {
        _uiState.update { it.copy(stickerSheet = null) }
    }

    // -----------------------------------------------------------------------
    // 她能不能用
    // -----------------------------------------------------------------------

    override fun setEnabledForHer(packId: String, enabled: Boolean) {
        // 开关先跟手改状态，写库失败再回滚并提示。
        _uiState.update { state ->
            state.copy(packs = state.packs.map { item ->
                if (item.pack.id == packId) item.copy(pack = item.pack.copy(enabledForHer = enabled)) else item
            })
        }
        viewModelScope.launch {
            runCatchingCancellable { repository.setEnabledForHer(packId, enabled) }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(packs = state.packs.map { item ->
                            if (item.pack.id == packId) {
                                item.copy(pack = item.pack.copy(enabledForHer = !enabled))
                            } else {
                                item
                            }
                        })
                    }
                    showToast("保存失败：${error.message ?: "未知错误"}", SettingsToastKind.Error)
                }
        }
    }

    // -----------------------------------------------------------------------
    // 删除
    // -----------------------------------------------------------------------

    override fun requestDeletePack(pack: StickerPack) {
        _uiState.update { it.copy(pendingDeletePack = pack, pendingDeleteSticker = null) }
    }

    override fun requestDeleteSticker(sticker: Sticker) {
        _uiState.update { it.copy(pendingDeleteSticker = sticker, pendingDeletePack = null) }
    }

    override fun cancelDelete() {
        _uiState.update { it.copy(pendingDeletePack = null, pendingDeleteSticker = null) }
    }

    override fun confirmDelete() {
        val state = _uiState.value
        val sticker = state.pendingDeleteSticker
        val pack = state.pendingDeletePack
        when {
            sticker != null -> {
                // 先收浮层，列表随后由 Flow 刷新。
                _uiState.update { it.copy(pendingDeleteSticker = null, stickerSheet = null) }
                viewModelScope.launch {
                    runCatchingCancellable { repository.deleteSticker(sticker.id) }
                        .onSuccess { showToast("已删除这张表情", SettingsToastKind.Success) }
                        .onFailure { showToast("删除失败：${it.message ?: "未知错误"}", SettingsToastKind.Error) }
                }
            }

            pack != null -> {
                _uiState.update { current ->
                    current.copy(
                        pendingDeletePack = null,
                        openPackId = current.openPackId?.takeIf { it != pack.id },
                    )
                }
                viewModelScope.launch {
                    runCatchingCancellable { repository.deletePack(pack.id) }
                        .onSuccess { showToast("已删除「${pack.displayName()}」", SettingsToastKind.Success) }
                        .onFailure { showToast("删除失败：${it.message ?: "未知错误"}", SettingsToastKind.Error) }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 导入：inspect → （冲突确认）→ execute → 汇总
    // -----------------------------------------------------------------------

    override fun importFolder(uri: Uri) = startImport(uri, zip = false)

    override fun importZip(uri: Uri) = startImport(uri, zip = true)

    private fun startImport(uri: Uri, zip: Boolean) {
        if (importJob?.isActive == true) return
        closePendingSource()
        _uiState.update { it.copy(importUi = StickerImportUiState(StickerImportStage.Inspecting)) }
        importJob = viewModelScope.launch {
            val source = runCatchingCancellable { prepareSource(uri, zip) }.getOrElse { error ->
                finishWithError("打开文件失败：${error.message ?: "未知错误"}")
                return@launch
            }
            pendingSource = source
            val plan = runCatchingCancellable { importer.inspect(source) }.getOrElse { error ->
                finishWithError("读不了这个表情包：${error.message ?: "未知错误"}")
                return@launch
            }
            pendingPlan = plan
            when {
                plan.isEmpty -> {
                    // 没有任何可导入的系列：直接给汇总，不再往下走。
                    val details = buildList {
                        plan.parsed.skippedSeries.forEach { add("跳过系列「${it.subject}」：${it.reason}") }
                        plan.parsed.skippedStickers.forEach { add("跳过「${it.subject}」：${it.reason}") }
                        addAll(plan.parsed.warnings)
                    }
                    _uiState.update {
                        it.copy(
                            importUi = StickerImportUiState(
                                stage = StickerImportStage.Result,
                                summary = "没有可导入的内容",
                                details = details,
                            ),
                        )
                    }
                    closePendingSource()
                }

                plan.conflicts.isNotEmpty() -> {
                    _uiState.update {
                        it.copy(
                            importUi = StickerImportUiState(
                                stage = StickerImportStage.ConfirmingConflicts,
                                conflicts = plan.conflicts,
                                total = plan.stickerCount,
                            ),
                        )
                    }
                }

                else -> executeImport(plan)
            }
        }
    }

    override fun confirmImport() {
        val plan = pendingPlan ?: return
        val state = _uiState.value.importUi ?: return
        if (state.stage != StickerImportStage.ConfirmingConflicts) return
        executeImport(plan)
    }

    override fun cancelImportConfirm() {
        closePendingSource()
        pendingPlan = null
        _uiState.update { it.copy(importUi = null) }
    }

    private fun executeImport(plan: StickerImportPlan) {
        val source = pendingSource ?: return
        cancelRequested = false
        _uiState.update {
            it.copy(
                importUi = StickerImportUiState(
                    stage = StickerImportStage.Importing,
                    done = 0,
                    total = plan.stickerCount,
                ),
            )
        }
        importJob = viewModelScope.launch {
            val report = runCatchingCancellable {
                importer.execute(
                    source = source,
                    plan = plan,
                    onProgress = { progress ->
                        _uiState.update { state ->
                            val ui = state.importUi
                            if (ui == null || ui.stage != StickerImportStage.Importing) {
                                state
                            } else {
                                state.copy(
                                    importUi = ui.copy(done = progress.done, total = progress.total),
                                )
                            }
                        }
                    },
                    isCancelled = { cancelRequested },
                )
            }.getOrElse { error ->
                finishWithError("导入失败：${error.message ?: "未知错误"}")
                return@launch
            }
            pendingPlan = null
            _uiState.update { it.copy(importUi = resultState(report)) }
            closePendingSource()
        }
    }

    override fun cancelImport() {
        cancelRequested = true
    }

    override fun dismissImportResult() {
        closePendingSource()
        _uiState.update { it.copy(importUi = null) }
    }

    private fun resultState(report: StickerImportReport): StickerImportUiState = StickerImportUiState(
        stage = StickerImportStage.Result,
        done = report.stickersImported,
        total = report.stickersImported,
        summary = report.summary(),
        details = report.details(),
        cancelled = report.cancelled,
    )

    private fun finishWithError(message: String) {
        closePendingSource()
        pendingPlan = null
        _uiState.update {
            it.copy(
                importUi = StickerImportUiState(
                    stage = StickerImportStage.Result,
                    summary = message,
                ),
            )
        }
    }

    /** 目录 → SafStickerSource；zip → 复制到 cacheDir（最多 200 MB）再包 ZipStickerSource。 */
    private suspend fun prepareSource(uri: Uri, zip: Boolean): StickerSource = withContext(Dispatchers.IO) {
        if (!zip) {
            SafStickerSource(appContext, uri)
        } else {
            val cacheFile = File(appContext.cacheDir, "sticker-import-${System.currentTimeMillis()}.zip")
            val input = appContext.contentResolver.openInputStream(uri)
                ?: error("无法读取选中的文件")
            // N-005：整包按实际字节限流，超过上限就中止并删掉临时文件
            input.use { source -> whaleui.nightsky.ltd.sticker.copyStickerZipLimited(source, cacheFile) }
            pendingCacheFile = cacheFile
            val zipFile = ZipFile(cacheFile)
            pendingZip = zipFile
            ZipStickerSource(zipFile)
        }
    }

    /** 关掉 zip 并删掉临时文件；目录源没有资源要释放。 */
    private fun closePendingSource() {
        runCatching { pendingZip?.close() }
        pendingZip = null
        pendingCacheFile?.let { runCatching { it.delete() } }
        pendingCacheFile = null
        pendingSource = null
    }

    // -----------------------------------------------------------------------
    // 返回键 / 提示条
    // -----------------------------------------------------------------------

    override fun handleBack() {
        val state = _uiState.value
        when {
            state.pendingDeletePack != null || state.pendingDeleteSticker != null -> cancelDelete()
            state.stickerSheet != null -> closeSticker()
            state.importUi != null -> {
                // 导入中只允许用弹窗里的「取消」；其余阶段返回键等于关掉弹窗。
                if (state.importUi.stage != StickerImportStage.Importing) {
                    if (state.importUi.stage == StickerImportStage.ConfirmingConflicts) {
                        cancelImportConfirm()
                    } else {
                        dismissImportResult()
                    }
                }
            }

            state.openPackId != null -> closePack()
        }
    }

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

    override fun dismissToast() {
        toastJob?.cancel()
        _uiState.update { it.copy(toast = null) }
    }

    override fun onCleared() {
        cancelRequested = true
        importJob?.cancel()
        toastJob?.cancel()
        closePendingSource()
    }

    private companion object {
        fun found(stickerId: String, packs: List<StickerPackUi>): Sticker? =
            packs.asSequence().flatMap { it.stickers.asSequence() }.firstOrNull { it.id == stickerId }
    }
}

/** 系列在按钮文案里用的名字：名称空着时退回系列号。 */
internal fun StickerPack.displayName(): String = titleOriginal.ifBlank { name }.ifBlank { id }
