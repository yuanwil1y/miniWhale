package whaleui.nightsky.ltd.ui.settings

import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.components.TimeWheel
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.domain.settings.PersonaKind
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Date
import java.util.Locale

// 从 SettingsScreen.kt 拆出来（R1 / W-051，只搬代码不改实现）

// ---------------------------------------------------------------------------
// 音色选择弹层
// ---------------------------------------------------------------------------

@Composable
internal fun VoiceSheet(
    state: SettingsUiState,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onPreview: (String) -> Unit,
) {
    val colors = WhaleTheme.colors
    SettingsSheet(onDismiss = onDismiss) {
        Text(text = "音色", style = SettingsType.sheetTitle, color = colors.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "选一个她说话的声音，可以逐个试听",
            style = SettingsType.info,
            color = colors.textCaption,
        )
        Spacer(Modifier.height(8.dp))
        state.voices.forEach { voice ->
            val selected = voice.id == state.settings.voiceId
            val playing = state.previewingVoice.isPlaying && state.previewingVoice.voiceId == voice.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(WhaleRadius.m))
                    .clickable { onSelect(voice.id) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 单选指示：Telegram 的单选列表把指示器放在行首（TextRadioCell：
                // 20dp、行首 22dp 处）；这里按底部弹层的宽度收成 30dp 槽位。
                Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterStart) {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            (fadeIn(tween(WhaleDuration.Toggle, easing = TgEasing.Decelerate)) +
                                scaleIn(
                                    animationSpec = tween(WhaleDuration.Toggle, easing = TgEasing.Decelerate),
                                    initialScale = 0.6f,
                                )) togetherWith fadeOut(tween(WhaleDuration.Dismiss, easing = TgEasing.Decelerate))
                        },
                        label = "voiceSelected",
                    ) { checked ->
                        if (checked) {
                            WhaleIcon(WhaleIcons.Check, tint = colors.accentFill, modifier = Modifier.size(24.dp))
                        } else {
                            Spacer(Modifier.size(20.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = voice.name,
                        style = SettingsType.listItemTitle,
                        color = if (selected) colors.accent else colors.textPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = voice.style + if (voice.traits.isEmpty()) "" else " · " + voice.traits.joinToString(" · "),
                        style = SettingsType.info,
                        color = colors.textCaption,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(CircleShape)
                        .border(1.dp, if (playing) colors.accentFill else colors.accent, CircleShape)
                        .background(if (playing) colors.accentFill.copy(alpha = WhaleAlpha.Tint) else Color.Transparent)
                        .clickable { onPreview(voice.id) }
                        .padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (playing) {
                        PlayingBarsGlyph(Modifier.size(11.dp), colors.accent)
                    } else {
                        WhaleIcon(WhaleIcons.Play, tint = colors.accent, modifier = Modifier.size(12.dp))
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (playing) "播放中" else "试听",
                        style = SettingsType.info,
                        color = colors.accent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 人设弹层
// ---------------------------------------------------------------------------

@Composable
internal fun PersonaSheet(
    sheet: PersonaSheetState,
    onDismiss: () -> Unit,
    onReimport: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = WhaleTheme.colors
    SettingsSheet(onDismiss = onDismiss) {
        Text(
            text = sheet.kind.title,
            style = SettingsType.sheetTitle,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = sheet.document?.let {
                "已导入 · ${formatFileSize(it.sizeBytes)} · ${formatImportDate(it.importedAt)}"
            } ?: "未导入",
            style = SettingsType.info,
            color = colors.success,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 300.dp)
                .clip(RoundedCornerShape(WhaleRadius.m))
                .background(colors.bgPage)
                .border(0.5.dp, colors.divider, RoundedCornerShape(WhaleRadius.m))
                .padding(14.dp),
        ) {
            when {
                sheet.isLoading -> Text(
                    text = "读取中…",
                    style = SettingsType.info,
                    color = colors.textCaption,
                )

                sheet.text.isNullOrBlank() -> Text(
                    text = "文件是空的",
                    style = SettingsType.info,
                    color = colors.textCaption,
                )

                else -> Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 272.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = sheet.text,
                        style = SettingsType.preview,
                        color = colors.textPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WhaleIcon(WhaleIcons.Trash, tint = WhaleTheme.colors.danger, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "移除",
                    style = SettingsType.button,
                    color = WhaleTheme.colors.danger,
                )
            }
            Spacer(Modifier.weight(1f))
            SettingsFilledButton(label = "重新导入", enabled = true, onClick = onReimport)
        }
    }
}

// ---------------------------------------------------------------------------
// 免打扰时段弹层
// ---------------------------------------------------------------------------

@Composable
internal fun QuietHoursSheet(
    state: SettingsUiState,
    onDismiss: () -> Unit,
    onStartChange: (LocalTime) -> Unit,
    onEndChange: (LocalTime) -> Unit,
    onSave: () -> Unit,
) {
    val colors = WhaleTheme.colors
    SettingsSheet(onDismiss = onDismiss) {
        Text(text = "免打扰时段", style = SettingsType.sheetTitle, color = colors.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "这段时间她不会主动发消息，可以跨零点",
            style = SettingsType.info,
            color = colors.textCaption,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            WheelColumn(
                label = "开始",
                time = state.quietDraftStart,
                onChange = onStartChange,
            )
            Spacer(Modifier.width(28.dp))
            WheelColumn(
                label = "结束",
                time = state.quietDraftEnd,
                onChange = onEndChange,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            SettingsTextButton(label = "取消", color = colors.textSecondary, onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            SettingsFilledButton(label = "保存", enabled = true, onClick = onSave)
        }
    }
}

@Composable
private fun WheelColumn(
    label: String,
    time: LocalTime,
    onChange: (LocalTime) -> Unit,
) {
    val colors = WhaleTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = SettingsType.info, color = colors.textCaption)
        Spacer(Modifier.height(4.dp))
        TimeWheel(time = time, onTimeChange = onChange)
        Spacer(Modifier.height(4.dp))
        Text(
            text = formatClock(time),
            style = SettingsType.cellValue,
            color = colors.textPrimary,
        )
    }
}

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

internal fun formatImportDate(importedAt: Long): String =
    SimpleDateFormat("M月d日", Locale.CHINA).format(Date(importedAt))

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 导入面板（u4 §3.6，D 用户定：六个导入合成设置页一行「导入」→ 这个底部面板）
// 只换入口位置：选文件、导入逻辑、结果提示都和原来一样。
// ---------------------------------------------------------------------------

@Composable
internal fun ImportSheet(
    state: SettingsUiState,
    onDismiss: () -> Unit,
    onOpenPersona: (PersonaKind) -> Unit,
    onImportDiary: () -> Unit,
    onImportMemory: () -> Unit,
    onImportChat: () -> Unit,
    onOpenStickerPacks: () -> Unit,
) {
    val colors = WhaleTheme.colors
    SettingsSheet(onDismiss = onDismiss) {
        Text(text = "导入", style = SettingsType.sheetTitle, color = colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        ImportSheetHeader("她的人设")
        PersonaKind.entries.forEach { kind ->
            val imported = state.persona(kind) != null
            ImportSheetRow(
                title = "📄 ${kind.title}",
                value = if (imported) "已导入" else "未导入",
                valueColor = if (imported) colors.success else colors.textCaption,
                onClick = { onOpenPersona(kind) },
            )
        }
        Spacer(Modifier.height(8.dp))
        ImportSheetHeader("内容")
        ImportSheetRow(title = "日记", subtitle = "每天一个 YYYY-MM-DD.md，或一个 ZIP", onClick = onImportDiary)
        ImportSheetRow(title = "记忆", subtitle = "导出包或 memories.md，按 ## 分类", onClick = onImportMemory)
        ImportSheetRow(title = "聊天记录", subtitle = "导出的 ZIP，按日期合并，重复的跳过", onClick = onImportChat)
        ImportSheetRow(title = "表情包", subtitle = "导入、按系列管理；只收 WebP", chevron = true, onClick = onOpenStickerPacks)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "都只合并、不覆盖；导出的整包 ZIP 也可以直接选。",
            style = SettingsType.info,
            color = colors.textCaption,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ImportSheetHeader(text: String) {
    Text(
        text = text,
        style = WhaleTheme.type.header,
        color = WhaleTheme.colors.accentHeader,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** 面板里的一行：和音色面板同样的圆角点按区；右边可选状态值或 ›。 */
@Composable
private fun ImportSheetRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = WhaleTheme.colors.textCaption,
    chevron: Boolean = false,
) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(WhaleRadius.m))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = SettingsType.cellTitle, color = colors.textPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = subtitle, style = SettingsType.cellSubtitle, color = colors.textCaption)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(10.dp))
            Text(text = value, style = SettingsType.cellValue, color = valueColor)
        }
        if (chevron) {
            Spacer(Modifier.width(10.dp))
            RowChevron()
        }
    }
}
