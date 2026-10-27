package whaleui.nightsky.ltd.ui.schedule

import androidx.compose.ui.graphics.compositeOver
import whaleui.nightsky.ltd.ui.components.EmptyState
import whaleui.nightsky.ltd.ui.components.HeaderCell
import whaleui.nightsky.ltd.ui.theme.WhaleMotion
import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.components.WhaleSwitch
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.domain.schedule.ScheduleTiming

// 从 ScheduleScreen.kt 拆出来（R1 / W-051，只搬代码不改实现）

// ---------------------------------------------------------------------------
// 时间线
// ---------------------------------------------------------------------------

@Composable
internal fun ScheduleTimeline(
    state: ScheduleUiState,
    listState: LazyListState,
    styles: ScheduleTextStyles,
    nowLabel: String,
    onToggle: (String, Boolean) -> Unit,
    onOpenTask: (String) -> Unit,
    onShowMenu: (String, Offset) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 19.dp, bottom = 96.dp),
    ) {
        // 任务和她自己定的闹钟在 state.slots 里已经按时间合并好：
        // 她的闹钟也用「时间列 + 圆点 + 卡片」的正常形状，并且插在正确的时间位置。
        state.slots.forEachIndexed { index, slot ->
            if (index == state.markerIndex) {
                item(key = "now-marker", contentType = "now") {
                    NowRow(
                        label = nowLabel,
                        time = formatTime(state.now),
                        styles = styles,
                        modifier = Modifier.animateItem(
                            fadeInSpec = ListFadeSpec,
                            placementSpec = ListPlacementSpec,
                            fadeOutSpec = ListFadeSpec,
                        ),
                    )
                }
            }
            when (slot) {
                is TimelineSlot.Task -> {
                    val entry = slot.entry
                    item(key = entry.task.id, contentType = "task") {
                        TimelineTaskRow(
                            entry = entry,
                            styles = styles,
                            onToggle = { onToggle(entry.task.id, it) },
                            onOpen = { onOpenTask(entry.task.id) },
                            onShowMenu = { touch -> onShowMenu(entry.task.id, touch) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = ListFadeSpec,
                                placementSpec = ListPlacementSpec,
                                fadeOutSpec = ListFadeSpec,
                            ),
                        )
                    }
                }

                is TimelineSlot.HerAlarm -> {
                    val row = slot.row
                    item(key = "her-${row.id}", contentType = "her") {
                        TimelineHerAlarmRow(
                            row = row,
                            styles = styles,
                            modifier = Modifier.animateItem(
                                fadeInSpec = ListFadeSpec,
                                placementSpec = ListPlacementSpec,
                                fadeOutSpec = ListFadeSpec,
                            ),
                        )
                    }
                }
            }
        }
        if (state.markerIndex >= state.slots.size) {
            item(key = "now-marker", contentType = "now") {
                NowRow(
                    label = nowLabel,
                    time = formatTime(state.now),
                    styles = styles,
                    modifier = Modifier.animateItem(
                        fadeInSpec = ListFadeSpec,
                        placementSpec = ListPlacementSpec,
                        fadeOutSpec = ListFadeSpec,
                    ),
                )
            }
        }
        // 「接下来」：今天以后她给自己定的闹钟（只读，提示词第 6 批）；没有就整组不显示
        if (state.upcoming.isNotEmpty()) {
            item(key = "upcoming-header", contentType = "header") {
                HeaderCell(
                    text = "接下来",
                    modifier = Modifier.animateItem(
                        fadeInSpec = ListFadeSpec,
                        placementSpec = ListPlacementSpec,
                        fadeOutSpec = ListFadeSpec,
                    ),
                )
            }
            state.upcoming.forEach { row ->
                item(key = "upcoming-${row.id}", contentType = "her") {
                    TimelineHerAlarmRow(
                        row = row,
                        styles = styles,
                        modifier = Modifier.animateItem(
                            fadeInSpec = ListFadeSpec,
                            placementSpec = ListPlacementSpec,
                            fadeOutSpec = ListFadeSpec,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineTaskRow(
    entry: ScheduleEntry,
    styles: ScheduleTextStyles,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onShowMenu: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    val status = entry.status
    val targetAlpha = when (status) {
        ScheduleStatus.Sent -> WhaleAlpha.Muted
        ScheduleStatus.Closed -> WhaleAlpha.Disabled
        else -> WhaleAlpha.Visible
    }
    // 开关关掉 / 状态变化时整行淡入淡出，不是瞬间跳变。
    val rowAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = WhaleMotion.toggle(),
        label = "scheduleRowAlpha",
    )
    val dotColor = when (status) {
        ScheduleStatus.Sent -> WhaleTheme.colors.textCaption
        ScheduleStatus.Waiting -> WhaleTheme.colors.accentFill
        ScheduleStatus.Missed -> colors.accent
        ScheduleStatus.Closed -> WhaleTheme.colors.divider
    }
    val timeColor = when (status) {
        ScheduleStatus.Missed -> colors.accent
        ScheduleStatus.Closed -> WhaleTheme.colors.textCaption
        else -> WhaleTheme.colors.textPrimary
    }

    val lineColor = WhaleTheme.colors.accent
    val dividerColor = WhaleTheme.colors.divider
    Box(modifier.fillMaxWidth()) {
        Canvas(Modifier.matchParentSize()) {
            drawLine(
                color = dividerColor,
                start = Offset(TimelineX.toPx(), 0f),
                end = Offset(TimelineX.toPx(), size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .graphicsLayer { alpha = rowAlpha },
                verticalAlignment = Alignment.Top,
            ) {
                Spacer(Modifier.width(16.dp))
                Box(
                    Modifier
                        .width(TimeColumnWidth)
                        .padding(top = 10.dp),
                ) {
                    Text(
                        text = entry.timeLabel,
                        modifier = Modifier.fillMaxWidth(),
                        style = styles.time,
                        color = timeColor,
                        textAlign = TextAlign.End,
                    )
                }
                Spacer(Modifier.width(TimelineX - 16.dp - TimeColumnWidth - DotSize / 2))
                Box(
                    Modifier
                        .width(DotSize)
                        .fillMaxHeight(),
                ) {
                    Box(
                        Modifier
                            .offset(y = 16.25.dp)
                            .size(DotSize)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
                Spacer(Modifier.width(CardLeft - TimelineX - DotSize / 2))
                ScheduleTaskCard(
                    entry = entry,
                    styles = styles,
                    onToggle = onToggle,
                    onOpen = onOpen,
                    onShowMenu = onShowMenu,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.5.dp))
            }
            // 卡片之间留出间距；这段间距也画在竖线上，时间线才是连续的。
            Spacer(Modifier.height(RowGap))
        }
    }
}

/**
 * 她自己定的一次性闹钟（只读，任务书 4.5）。
 *
 * 形状和 [TimelineTaskRow] 完全一致：左时间列 → 竖线 + 圆点 → 圆角卡片；
 * 区别只有卡片里没有开关（只读），副标题写「她自己定的 · 一次」，触发过的 55% 透明度 + 绿勾「已发送」。
 */
@Composable
private fun TimelineHerAlarmRow(
    row: HerAlarmRow,
    styles: ScheduleTextStyles,
    modifier: Modifier = Modifier,
) {
    val targetAlpha = if (row.sent) WhaleAlpha.Muted else WhaleAlpha.Visible
    val rowAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = WhaleMotion.toggle(),
        label = "scheduleHerAlarmAlpha",
    )
    val dotColor = if (row.sent) WhaleTheme.colors.textCaption else WhaleTheme.colors.accentFill
    val timeColor = if (row.sent) WhaleTheme.colors.textCaption else WhaleTheme.colors.textPrimary
    val dividerColor = WhaleTheme.colors.divider

    Box(modifier.fillMaxWidth()) {
        Canvas(Modifier.matchParentSize()) {
            drawLine(
                color = dividerColor,
                start = Offset(TimelineX.toPx(), 0f),
                end = Offset(TimelineX.toPx(), size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .graphicsLayer { alpha = rowAlpha },
                verticalAlignment = Alignment.Top,
            ) {
                Spacer(Modifier.width(16.dp))
                Box(
                    Modifier
                        .width(TimeColumnWidth)
                        .padding(top = 10.dp),
                ) {
                    // 「接下来」分组里时间上面多一行日子（明天 / 周五）
                    Text(
                        text = row.dayLabel?.let { "$it\n${row.timeLabel}" } ?: row.timeLabel,
                        modifier = Modifier.fillMaxWidth(),
                        style = styles.time,
                        color = timeColor,
                        textAlign = TextAlign.End,
                    )
                }
                Spacer(Modifier.width(TimelineX - 16.dp - TimeColumnWidth - DotSize / 2))
                Box(
                    Modifier
                        .width(DotSize)
                        .fillMaxHeight(),
                ) {
                    Box(
                        Modifier
                            .offset(y = 16.25.dp)
                            .size(DotSize)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
                Spacer(Modifier.width(CardLeft - TimelineX - DotSize / 2))
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CardShape)
                        .background(WhaleTheme.colors.surface)
                        .border(CardBorder, WhaleTheme.colors.divider, CardShape)
                        .padding(start = 17.dp, end = 17.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier.heightIn(min = 24.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = row.notePreview.ifBlank { "（她给自己定的闹钟）" },
                                style = styles.cardTitle,
                                color = WhaleTheme.colors.textPrimary,
                                maxLines = 2,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.height(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (row.sent) {
                                WhaleIcon(WhaleIcons.Check, tint = WhaleTheme.colors.success, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "已发送",
                                    style = styles.status,
                                    color = WhaleTheme.colors.success,
                                )
                            } else {
                                Text(
                                    text = "等她到点",
                                    style = styles.status,
                                    color = WhaleTheme.colors.textCaption,
                                )
                            }
                            Text(
                                text = " · 她自己定的 · 一次",
                                style = styles.status,
                                color = WhaleTheme.colors.textCaption,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(16.5.dp))
            }
            Spacer(Modifier.height(RowGap))
        }
    }
}

@Composable
private fun ScheduleTaskCard(
    entry: ScheduleEntry,
    styles: ScheduleTextStyles,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onShowMenu: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    var origin by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue = if (pressed) {
            WhaleTheme.colors.ripple.compositeOver(WhaleTheme.colors.surface)
        } else {
            WhaleTheme.colors.surface
        },
        animationSpec = tween(WhaleDuration.Dismiss, easing = TgEasing.Linear),
        label = "scheduleCardPress",
    )
    val statusText = when (entry.status) {
        ScheduleStatus.Sent -> "已发送"
        ScheduleStatus.Waiting ->
            if (entry.task.timing is ScheduleTiming.Random && entry.task.todayPickedTime == null) {
                "她自己挑时间"
            } else {
                "等待中"
            }

        ScheduleStatus.Missed -> "未发送"
        ScheduleStatus.Closed -> "已关闭"
    }
    val statusColor = when (entry.status) {
        ScheduleStatus.Sent -> WhaleTheme.colors.success
        ScheduleStatus.Missed -> colors.accent
        else -> WhaleTheme.colors.textCaption
    }

    Row(
        modifier = modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(entry.task.id) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onOpen() },
                    onLongPress = { touch -> onShowMenu(origin + touch) },
                )
            }
            .clip(CardShape)
            .background(background)
            .border(CardBorder, WhaleTheme.colors.divider, CardShape)
            .padding(start = 17.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Box(
                modifier = Modifier.heightIn(min = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = entry.task.title,
                    style = styles.cardTitle,
                    color = WhaleTheme.colors.textPrimary,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.height(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (entry.status == ScheduleStatus.Sent) {
                    WhaleIcon(WhaleIcons.Check, tint = WhaleTheme.colors.success, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = statusText,
                        style = styles.status,
                        color = statusColor,
                    )
                    Text(
                        text = " · ${entry.kindLabel}",
                        style = styles.status,
                        color = WhaleTheme.colors.textCaption,
                    )
                } else {
                    Text(
                        text = "$statusText · ${entry.kindLabel}",
                        style = styles.status,
                        color = statusColor,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        WhaleSwitch(
            checked = entry.task.enabled,
            onCheckedChange = onToggle,
            contentDescription = if (entry.task.enabled) "已开启" else "已关闭",
        )
    }
}


@Composable
private fun NowRow(
    label: String,
    time: String,
    styles: ScheduleTextStyles,
    modifier: Modifier = Modifier,
) {
    val lineColor = WhaleTheme.colors.accent
    val dividerColor = WhaleTheme.colors.divider
    Box(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawLine(
                color = dividerColor,
                start = Offset(TimelineX.toPx(), 0f),
                end = Offset(TimelineX.toPx(), size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .width(TimeColumnWidth)
                        .height(34.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.fillMaxWidth(),
                        style = styles.time,
                        color = lineColor,
                        textAlign = TextAlign.End,
                    )
                }
                Box(
                    modifier = Modifier
                        .offset(x = TimelineX - 8.dp)
                        .align(Alignment.CenterStart)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(WhaleTheme.colors.bgPage)
                        .border(1.5.dp, lineColor, CircleShape),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .padding(start = TimelineX + 8.dp, end = 72.dp)
                        .height(1.dp)
                        .background(lineColor),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .height(34.dp)
                        .padding(end = 16.5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = time,
                        style = styles.nowTime,
                        color = lineColor,
                    )
                }
            }
            Spacer(Modifier.height(RowGap))
        }
    }
}

@Composable
internal fun ScheduleEmptyState(
    title: String,
    subtitle: String,
    @Suppress("UNUSED_PARAMETER") styles: ScheduleTextStyles,
    modifier: Modifier = Modifier,
) {
    EmptyState(title = title, text = subtitle, icon = WhaleIcons.Alarm, modifier = modifier)
}
