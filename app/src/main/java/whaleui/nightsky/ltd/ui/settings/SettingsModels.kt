package whaleui.nightsky.ltd.ui.settings

import androidx.compose.runtime.Immutable
import whaleui.nightsky.ltd.domain.settings.AppSettings
import whaleui.nightsky.ltd.domain.settings.ExportError
import whaleui.nightsky.ltd.domain.settings.PersonaDocument
import whaleui.nightsky.ltd.domain.settings.PersonaKind
import whaleui.nightsky.ltd.domain.settings.SecretProvider
import whaleui.nightsky.ltd.domain.settings.VoiceOption

// ---------------------------------------------------------------------------
// 设置页的界面状态。
//
// 组件只读这里的数据，所有动作都从 SettingsViewModel 走。
// 密钥不在这里填（D：只在 高级设置 → 供应商 里填），这里只知道 DeepSeek 有没有填，给「⚙️ 高级」行提示。
// ---------------------------------------------------------------------------

/** 会盖住页面的浮层，返回键按这个顺序逐层关闭。 */
enum class SettingsOverlay { VoiceSheet, PersonaSheet, QuietHoursSheet, RemovePersonaDialog, ImportSheet }

/** 底部提示条的类型。 */
enum class SettingsToastKind { Info, Success, Error }

@Immutable
data class SettingsToast(
    val id: Long,
    val text: String,
    val kind: SettingsToastKind = SettingsToastKind.Info,
    /** 有值时提示条右侧显示这个动作（例如「撤销」）。 */
    val actionLabel: String? = null,
)

/** 正在执行的导出任务，行上显示进度。 */
enum class ExportKind { All }

/** 正在试听的音色（列表行 / 设置行上的播放状态）。 */
@Immutable
data class VoicePreviewState(
    val voiceId: String? = null,
    val isPlaying: Boolean = false,
)

/** 人设弹层里的只读预览。 */
@Immutable
data class PersonaSheetState(
    val kind: PersonaKind,
    val document: PersonaDocument?,
    val text: String? = null,
    val isLoading: Boolean = false,
)

/**
 * 设置页整页状态。
 *
 * [scrollOffset] 让页面在往返其他页面后停在原来的滚动位置（ViewModel 持有）。
 */
@Immutable
data class SettingsUiState(
    val isLoading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val voices: List<VoiceOption> = emptyList(),
    /** DeepSeek 密钥有没有填（没填时「⚙️ 高级」行右边提示「没填密钥」）。 */
    val deepSeekKeySaved: Boolean = true,
    val personas: Map<PersonaKind, PersonaDocument?> = emptyMap(),
    val permissionsMissing: Int = 0,
    // --- 浮层 ---
    val overlay: SettingsOverlay? = null,
    val previewingVoice: VoicePreviewState = VoicePreviewState(),
    val personaSheet: PersonaSheetState? = null,
    val removePersonaKind: PersonaKind? = null,
    val quietDraftStart: java.time.LocalTime = java.time.LocalTime.of(23, 30),
    val quietDraftEnd: java.time.LocalTime = java.time.LocalTime.of(7, 30),
    // --- 导出 ---
    val exportInProgress: ExportKind? = null,
    // --- 清空数据 ---
    /** 清空数据的确认框是否打开。 */
    val resetDialog: Boolean = false,
    /** 正在清空（按钮/提示用，防止连点）。 */
    val resetInProgress: Boolean = false,
    // --- 提示 ---
    val toast: SettingsToast? = null,
    // --- 滚动位置 ---
    val scrollOffset: Int = 0,
    /** enterPage 时 +1，界面据此把滚动位置恢复到 [scrollOffset]。 */
    val scrollRestoreTick: Int = 0,
) {
    val voice: VoiceOption? get() = voices.firstOrNull { it.id == settings.voiceId }

    val quietLabel: String get() = "${formatClock(settings.quietStart)}–${formatClock(settings.quietEnd)}"

    fun persona(kind: PersonaKind): PersonaDocument? = personas[kind]
}

/** HH:mm。 */
fun formatClock(value: java.time.LocalTime): String =
    "%02d:%02d".format(value.hour, value.minute)

/** 导出失败原因的文案。 */
fun exportErrorText(error: ExportError): String = when (error) {
    ExportError.NoData -> "没有可导出的内容"
    ExportError.WriteFailed -> "写入失败，检查存储空间后重试"
    ExportError.ReadFailed -> "读取数据失败，请重试"
}

/** 导出成功的提示文案。 */
fun exportSuccessText(kind: ExportKind, fileCount: Int): String = when (kind) {
    ExportKind.All -> "全部数据已导出 · $fileCount 个文件"
}

/** 音色的"名称 · 标签"展示串。 */
fun VoiceOption.displayName(): String = "$name · $style"

/** 人设状态行的文案：已导入显示大小和日期，否则"未导入"。 */
fun personaStatusText(document: PersonaDocument?, importedLabel: String): String =
    if (document == null) "未导入" else "已导入 · ${formatFileSize(document.sizeBytes)} · $importedLabel"

/** 2.1 KB / 340 B / 1.2 MB。 */
fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
