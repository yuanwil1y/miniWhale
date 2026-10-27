package whaleui.nightsky.ltd.ui.settings

import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.flow.first
import whaleui.nightsky.ltd.ui.components.Cell
import whaleui.nightsky.ltd.ui.components.CellDivider
import whaleui.nightsky.ltd.ui.components.SectionCard
import whaleui.nightsky.ltd.ui.components.SectionGap
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.domain.settings.PersonaKind
import whaleui.nightsky.ltd.domain.settings.SecretProvider
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import java.time.LocalTime

// ---------------------------------------------------------------------------
// 数值来源
//
// C 起改成 Telegram 13 的卡片式分组（组件库 SectionCard / CellDivider / InfoFootnote），
// 下面是卡片内各行沿用的旧数值。原来的说明：
// 布局照 Telegram 设置页：分组标题（HeaderCell）→ 一行行平铺的单元格 →
// 分组之间一条 12dp 的分隔条（ShadowSectionCell）→ 分组下方说明小字
// （TextInfoPrivacyCell）。左右边距 / 行高 / 分隔线缩进取 Telegram 的值：
// - 行左边距 21dp（HeaderCell / TextSettingsCell 的 padding）；
// - 单行 48dp、带副标题 64dp、开关行 64dp；
// - 分组标题 38dp 高、15sp / 700、强调色；
// - 说明小字 13sp、次级色、上下各 8dp；
// - 分隔线 1px，从左边距 21dp 开始，右边通到底；
// - 分组之间的 ShadowSectionCell 12dp；
// - 按压态用系统 ripple（Telegram 列表单元格的做法）。
//
// 颜色全部取聊天页 / 记忆页那套深海蓝：页面 #0E1621、输入框 #17212E、
// 细线 #2B3A4B、强调 #3E78B2、强调小字 #7BA8D8、已导入绿 #7CC79B。
// ---------------------------------------------------------------------------

internal val RowStart = 21.dp
internal val RowEnd = 16.dp
internal val CellDoubleHeight = 64.dp
internal val FieldShape = RoundedCornerShape(WhaleRadius.iconTile)
internal val FieldHeight = 46.dp

/**
 * 设置页。无状态：所有数据来自 [state]，所有动作都从回调走。
 *
 * 返回键：先关最上面的浮层/对话框，没有浮层时才退出页面（由外层 BackHandler 接管）。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onScrolled: (Int) -> Unit,
    onOpenVoiceSheet: () -> Unit,
    onCloseOverlay: () -> Unit,
    onSelectVoice: (String) -> Unit,
    onToggleVoicePreview: (String) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onImportPersona: (PersonaKind) -> Unit,
    /** 导入日记（任务书 4.7） */
    onImportDiary: () -> Unit,
    modifier: Modifier = Modifier,
    /** 导入记忆：读导出包里的 memory/memories.md（或同格式单个 md） */
    onImportMemory: () -> Unit = {},
    /** 导入聊天记录：读导出包里的 chat 目录 md，合并进固定对话 */
    onImportChat: () -> Unit = {},
    /** 打开「表情包」管理页（round5 3.2） */
    onOpenStickerPacks: () -> Unit = {},
    /** 保存「常住城市」（round4 4.3）；空串表示清空 */
    onHomeCityChange: (String) -> Unit,
    onOpenPersonaSheet: (PersonaKind) -> Unit,
    onRequestRemovePersona: () -> Unit,
    onDismissRemovePersona: () -> Unit,
    onConfirmRemovePersona: () -> Unit,
    onUndoRemovePersona: () -> Unit,
    onProactiveChange: (Boolean) -> Unit,
    onOpenQuietHours: () -> Unit,
    onQuietStartChange: (LocalTime) -> Unit,
    onQuietEndChange: (LocalTime) -> Unit,
    onSaveQuietHours: () -> Unit,
    /** 导出全部数据：soul / user / 记忆 / 日记 / 聊天记录 / 表情包，一个 zip */
    onExportAll: () -> Unit = {},
    /** 打开「清空数据」确认框 */
    onResetClick: () -> Unit = {},
    onDismissReset: () -> Unit = {},
    onConfirmReset: () -> Unit = {},
    onPermissionsClick: () -> Unit,
    /** 打开「高级设置」（S9a）：供应商、模型、代理、日志 */
    onOpenAdvanced: () -> Unit = {},
    onDismissToast: () -> Unit,
    /** 「导入」行：打开导入面板 */
    onOpenImportSheet: () -> Unit = {},
    title: String = "设置",
) {
    val colors = WhaleTheme.colors
    val scrollState = rememberScrollState(initial = state.scrollOffset)
    // 离开页面时把滚动位置交回 ViewModel，回来时恢复到同一位置。
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onScrolled(scrollState.value) }
    }

    BackHandler(enabled = state.removePersonaKind != null) { onDismissRemovePersona() }
    BackHandler(enabled = state.removePersonaKind == null && state.overlay != null) { onCloseOverlay() }
    BackHandler(enabled = state.resetDialog) { onDismissReset() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPage),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            SettingsTopBar(title = title, onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
            ) {
                Spacer(Modifier.height(WhaleSpacing.s))
                // 分组顺序（README §5「C 追加」，D 用户定：密钥组移到 高级设置 → 供应商）：关于你 → 她的声音 →
                // 主动消息 → 导入与数据 → 权限与后台常驻 → 高级 → 关于。同类东西只出现在一个分组里。
                SectionCard(header = "🧑 关于你") {
                    // 常住城市（round4 4.3）：手填，失焦或 Done 保存
                    HomeCityCell(
                        city = state.settings.homeCity,
                        onCommit = onHomeCityChange,
                    )
                }
                SectionGap()

                SectionCard(header = "🎙️ 她的声音") {
                    // D6：试听只留在音色弹层每一行，这里不再放「试听」按钮
                    VoiceCell(state = state, onClick = onOpenVoiceSheet)
                    CellDivider()
                    SpeechRateCell(
                        rate = state.settings.speechRate,
                        onRateChange = onSpeechRateChange,
                    )
                }
                SectionGap()

                SectionCard(header = "💌 主动消息") {
                    SwitchCell(
                        title = "让她主动找你",
                        subtitle = "她想你的时候会发消息",
                        checked = state.settings.proactiveEnabled,
                        onCheckedChange = onProactiveChange,
                    )
                    CellDivider()
                    QuietHoursCell(
                        value = state.quietLabel,
                        enabled = state.settings.proactiveEnabled,
                        onClick = onOpenQuietHours,
                    )
                }
                SectionGap()

                SectionCard(header = "📥 导入与数据") {
                    // 六个导入合成一行，点开底部面板（u4 §3.6，D 用户定）
                    ImportEntryCell(onClick = onOpenImportSheet)
                    CellDivider()
                    ExportCell(
                        title = "导出全部数据",
                        subtitle = "Soul.md · User.md · 记忆 · 日记 · 聊天记录 · 表情包（ZIP）",
                        inProgress = state.exportInProgress != null,
                        enabled = state.exportInProgress == null,
                        onClick = onExportAll,
                    )
                    CellDivider()
                    SettingsActionRow(
                        title = "清空数据",
                        subtitle = "Soul.md · User.md · 记忆 · 日记 · 表情包（聊天记录保留）",
                        actionLabel = if (state.resetInProgress) "清空中" else "清空",
                        onClick = { if (!state.resetInProgress) onResetClick() },
                        actionIcon = WhaleIcons.Trash,
                        danger = true,
                    )
                }
                SectionGap()

                SectionCard(header = "🛡️ 权限与后台常驻") {
                    PermissionsCell(missing = state.permissionsMissing, onClick = onPermissionsClick)
                }
                SectionGap()

                SectionCard(header = "⚙️ 高级") {
                    AdvancedCell(keyMissing = !state.deepSeekKeySaved, onClick = onOpenAdvanced)
                }
                SectionGap()

                SectionCard(header = "ℹ️ 关于") {
                    AboutCell()
                }
                Spacer(Modifier.height(WhaleSpacing.xxl))
            }
        }

        // ------------------------------ 浮层 ------------------------------
        when (state.overlay) {
            SettingsOverlay.VoiceSheet -> VoiceSheet(
                state = state,
                onDismiss = onCloseOverlay,
                onSelect = onSelectVoice,
                onPreview = onToggleVoicePreview,
            )

            SettingsOverlay.PersonaSheet -> {
                val sheet = state.personaSheet
                if (sheet != null) {
                    PersonaSheet(
                        sheet = sheet,
                        onDismiss = onCloseOverlay,
                        onReimport = {
                            onCloseOverlay()
                            onImportPersona(sheet.kind)
                        },
                        onRemove = onRequestRemovePersona,
                    )
                }
            }

            SettingsOverlay.ImportSheet -> ImportSheet(
                state = state,
                onDismiss = onCloseOverlay,
                onOpenPersona = { kind ->
                    // 已导入：换成人设面板（导入 / 重新导入 / 移除）；没导入：关面板直接选文件
                    if (state.persona(kind) != null) {
                        onOpenPersonaSheet(kind)
                    } else {
                        onCloseOverlay()
                        onImportPersona(kind)
                    }
                },
                // 先关面板再选文件：结果 Bulletin 不会压在面板下面
                onImportDiary = {
                    onCloseOverlay()
                    onImportDiary()
                },
                onImportMemory = {
                    onCloseOverlay()
                    onImportMemory()
                },
                onImportChat = {
                    onCloseOverlay()
                    onImportChat()
                },
                onOpenStickerPacks = {
                    onCloseOverlay()
                    onOpenStickerPacks()
                },
            )

            SettingsOverlay.QuietHoursSheet -> QuietHoursSheet(
                state = state,
                onDismiss = onCloseOverlay,
                onStartChange = onQuietStartChange,
                onEndChange = onQuietEndChange,
                onSave = onSaveQuietHours,
            )

            null -> Unit
            SettingsOverlay.RemovePersonaDialog -> Unit
        }

        state.removePersonaKind?.let { kind ->
            SettingsConfirmDialog(
                title = "移除 ${kind.title}",
                message = "移除后她就不再按这份设定说话，可以撤销。",
                confirmLabel = "移除",
                onCancel = onDismissRemovePersona,
                onConfirm = onConfirmRemovePersona,
            )
        }

        if (state.resetDialog) {
            SettingsConfirmDialog(
                title = "清空这些数据？",
                message = "会删除 Soul.md、User.md、全部记忆、日记和表情包，不能撤销。" +
                    "聊天记录、设置和密钥会保留。",
                confirmLabel = "清空",
                onCancel = onDismissReset,
                onConfirm = onConfirmReset,
            )
        }

        // ------------------------------ 提示条 ------------------------------
        state.toast?.let { toast ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                SettingsToastBar(
                    toast = toast,
                    onAction = {
                        if (toast.actionLabel != null) onUndoRemovePersona() else onDismissToast()
                    },
                    onExpired = onDismissToast,
                )
            }
        }
    }
}
