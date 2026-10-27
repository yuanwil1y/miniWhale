package whaleui.nightsky.ltd.ui.schedule

import java.time.LocalTime
import whaleui.nightsky.ltd.ui.components.TimeWheel
import whaleui.nightsky.ltd.ui.components.WhaleBottomSheet
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.TgEasing
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import kotlinx.coroutines.delay
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

// 从 ScheduleScreen.kt 拆出来（R1 / W-051，只搬代码不改实现）

// ---------------------------------------------------------------------------
// 编辑弹层
// ---------------------------------------------------------------------------

@Composable
internal fun ScheduleEditorSheet(
    editor: ScheduleEditor,
    styles: ScheduleTextStyles,
    onChange: (ScheduleEditor) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = WhaleTheme.colors
    // 底部弹层 = 组件库 WhaleBottomSheet（U0）：400ms 打开 / 180ms 关闭、压暗 40%、下拉关闭
    // 内置任务的提示词默认收起（Q6）；换一条任务时重置
    var advancedOpen by remember(editor.taskId) { mutableStateOf(false) }
    // 保存 / 取消放在固定底栏：内容再长、键盘弹出时都不会被挤出屏幕（§4 #5）
    WhaleBottomSheet(
        onDismiss = onCancel,
        footer = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScheduleTextButton(
                    label = "取消",
                    color = WhaleTheme.colors.textSecondary,
                    styles = styles,
                    onClick = { dismiss() },
                )
                Spacer(Modifier.width(WhaleSpacing.s))
                ScheduleFilledButton(
                    label = "保存",
                    enabled = editor.canSave,
                    styles = styles,
                    onClick = { dismiss(onSave) },
                )
            }
        },
    ) { column ->
        with(column) {
            Text(
                text = if (editor.isNew) "添加任务" else "编辑任务",
                style = styles.editorTitle,
                color = WhaleTheme.colors.textPrimary,
            )
            Spacer(Modifier.height(12.dp))
            Text("任务名", style = styles.fieldLabel, color = WhaleTheme.colors.textCaption)
            Spacer(Modifier.height(6.dp))
            ScheduleTextField(
                value = editor.title,
                onValueChange = { onChange(editor.copy(title = it)) },
                placeholder = "任务名称",
                styles = styles,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!editor.isBuiltIn) {
                Spacer(Modifier.height(12.dp))
                Text("给她的提示", style = styles.fieldLabel, color = WhaleTheme.colors.textCaption)
                Spacer(Modifier.height(6.dp))
                ScheduleTextField(
                    value = editor.hint,
                    onValueChange = { onChange(editor.copy(hint = it)) },
                    placeholder = "告诉她这条消息大概要说什么（可不填）",
                    styles = styles,
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("时间类型", style = styles.fieldLabel, color = WhaleTheme.colors.textCaption)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScheduleChip(
                    label = "固定时间",
                    selected = editor.timingKind == ScheduleTimingKind.Fixed,
                    styles = styles,
                    onClick = { onChange(editor.copy(timingKind = ScheduleTimingKind.Fixed)) },
                )
                ScheduleChip(
                    label = "随机时段",
                    selected = editor.timingKind == ScheduleTimingKind.Random,
                    styles = styles,
                    onClick = { onChange(editor.copy(timingKind = ScheduleTimingKind.Random)) },
                )
            }
            Spacer(Modifier.height(10.dp))
            // 换任务 / 换时间类型时重建滚轮，避免沿用上一条任务的滚动位置。
            key(editor.taskId, editor.timingKind) {
                if (editor.timingKind == ScheduleTimingKind.Fixed) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        TimeWheel(
                            time = LocalTime.of(editor.hour, editor.minute),
                            onTimeChange = { onChange(editor.copy(hour = it.hour, minute = it.minute)) },
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "开始",
                                style = styles.fieldLabel,
                                color = WhaleTheme.colors.textCaption,
                            )
                            TimeWheel(
                                time = LocalTime.of(editor.windowStartHour, editor.windowStartMinute),
                                onTimeChange = { onChange(editor.copy(windowStartHour = it.hour, windowStartMinute = it.minute)) },
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "结束",
                                style = styles.fieldLabel,
                                color = WhaleTheme.colors.textCaption,
                            )
                            TimeWheel(
                                time = LocalTime.of(editor.windowEndHour, editor.windowEndMinute),
                                onTimeChange = { onChange(editor.copy(windowEndHour = it.hour, windowEndMinute = it.minute)) },
                            )
                        }
                    }
                    if (!editor.isWindowValid) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "结束时间要晚于开始时间",
                            style = styles.fieldLabel,
                            color = colors.accent,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("重复", style = styles.fieldLabel, color = WhaleTheme.colors.textCaption)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScheduleChip(
                    label = "每天",
                    selected = editor.repeatKind == ScheduleRepeatKind.Daily,
                    styles = styles,
                    onClick = { onChange(editor.copy(repeatKind = ScheduleRepeatKind.Daily)) },
                )
                ScheduleChip(
                    label = "工作日",
                    selected = editor.repeatKind == ScheduleRepeatKind.Weekdays,
                    styles = styles,
                    onClick = { onChange(editor.copy(repeatKind = ScheduleRepeatKind.Weekdays)) },
                )
                ScheduleChip(
                    label = "自定义",
                    selected = editor.repeatKind == ScheduleRepeatKind.Custom,
                    styles = styles,
                    onClick = { onChange(editor.copy(repeatKind = ScheduleRepeatKind.Custom)) },
                )
            }
            if (editor.repeatKind == ScheduleRepeatKind.Custom) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEachIndexed { index, day ->
                        ScheduleDayChip(
                            label = listOf("一", "二", "三", "四", "五", "六", "日")[index],
                            selected = day in editor.customDays,
                            styles = styles,
                            onClick = {
                                val days = if (day in editor.customDays) {
                                    editor.customDays - day
                                } else {
                                    editor.customDays + day
                                }
                                onChange(editor.copy(customDays = days))
                            },
                        )
                    }
                }
            }
            if (editor.isBuiltIn) {
                Spacer(Modifier.height(WhaleSpacing.l))
                ScheduleAdvancedHeader(open = advancedOpen, styles = styles, onToggle = { advancedOpen = !advancedOpen })
                AnimatedVisibility(
                    visible = advancedOpen,
                    enter = expandVertically(tween(WhaleDuration.Short, easing = TgEasing.Default)) + fadeIn(tween(WhaleDuration.Short, easing = TgEasing.Default)),
                    exit = shrinkVertically(tween(WhaleDuration.Short, easing = TgEasing.Default)) + fadeOut(tween(WhaleDuration.Short, easing = TgEasing.Default)),
                ) {
                    Column {
                        Text(
                            text = "这是内置任务给她的整段提示，带着她要遵守的标记。改错了她可能不按时找你。",
                            style = styles.fieldLabel,
                            color = WhaleTheme.colors.textCaption,
                        )
                        Spacer(Modifier.height(WhaleSpacing.s))
                        ScheduleTextField(
                            value = editor.hint,
                            onValueChange = { onChange(editor.copy(hint = it)) },
                            placeholder = "告诉她这条消息大概要说什么（可不填）",
                            styles = styles,
                            singleLine = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 72.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 「高级」折叠行：文字 + 右侧箭头，展开时箭头转 180°。整行 48dp 可点。 */
@Composable
private fun ScheduleAdvancedHeader(open: Boolean, styles: ScheduleTextStyles, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(WhaleDuration.Short, easing = TgEasing.Default),
        label = "advancedChevron",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = WhaleSpacing.touchTarget)
            .clip(WhaleShapes.field)
            .clickable(onClick = onToggle)
            .semantics { stateDescription = if (open) "已展开" else "已收起" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("高级", style = styles.fieldLabel, color = WhaleTheme.colors.textCaption, modifier = Modifier.weight(1f))
        WhaleIcon(
            WhaleIcons.ChevronDown,
            tint = WhaleTheme.colors.textCaption,
            size = WhaleSpacing.icon,
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
        )
    }
}

@Composable
private fun ScheduleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    styles: ScheduleTextStyles,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(WhaleRadius.m))
            .background(WhaleTheme.colors.surfaceInput)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = styles.field,
                color = WhaleTheme.colors.textCaption,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            textStyle = styles.field.copy(color = WhaleTheme.colors.textPrimary),
            cursorBrush = SolidColor(WhaleTheme.colors.accentFill),
        )
    }
}
