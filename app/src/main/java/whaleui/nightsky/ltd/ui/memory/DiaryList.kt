package whaleui.nightsky.ltd.ui.memory

import androidx.compose.ui.graphics.compositeOver
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import java.time.YearMonth
import whaleui.nightsky.ltd.domain.memory.DiaryEntry

// 从 MemoryScreen.kt 拆出来（R1 / W-051，只搬代码不改实现）

// ---------------------------------------------------------------------------
// 日记列表
// ---------------------------------------------------------------------------

@Composable
internal fun DiaryListPage(
    state: MemoryUiState,
    styles: MemoryTextStyles,
    listState: LazyListState,
    onToggleDiary: (String) -> Unit,
    onShowMenu: (String, Offset) -> Unit,
) {
    val colors = WhaleTheme.colors
    val months = state.diaryMonths()
    val showMonthHeaders = months.size >= 2

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "她每天夜里写一篇",
            style = styles.section,
            color = colors.textCaption,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (state.diary.isEmpty()) {
                MemoryEmptyState(
                    title = "还没有日记",
                    subtitle = "她每天夜里写一篇，明天再来看看",
                    styles = styles,
                    modifier = Modifier.align(Alignment.Center),
                    icon = whaleui.nightsky.ltd.ui.components.WhaleIcons.Book,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (showMonthHeaders) {
                        months.forEach { (month, entries) ->
                            item(
                                key = "month-${month.year}-${month.monthValue}",
                                contentType = "month",
                            ) {
                                MonthHeader(
                                    month = month,
                                    styles = styles,
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = ListFadeSpec,
                                        placementSpec = ListPlacementSpec,
                                        fadeOutSpec = ListFadeSpec,
                                    ),
                                )
                            }
                            items(
                                items = entries,
                                key = { it.id },
                                contentType = { "diary" },
                            ) { entry ->
                                DiaryCard(
                                    entry = entry,
                                    expanded = state.isDiaryExpanded(entry.id),
                                    styles = styles,
                                    onToggle = { onToggleDiary(entry.id) },
                                    onShowMenu = { touch -> onShowMenu(entry.id, touch) },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = ListFadeSpec,
                                        placementSpec = ListPlacementSpec,
                                        fadeOutSpec = ListFadeSpec,
                                    ),
                                )
                            }
                        }
                    } else {
                        items(
                            items = state.diary,
                            key = { it.id },
                            contentType = { "diary" },
                        ) { entry ->
                            DiaryCard(
                                entry = entry,
                                expanded = state.isDiaryExpanded(entry.id),
                                styles = styles,
                                onToggle = { onToggleDiary(entry.id) },
                                onShowMenu = { touch -> onShowMenu(entry.id, touch) },
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
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    styles: MemoryTextStyles,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    Text(
        text = monthLabel(month),
        style = styles.section,
        color = colors.textCaption,
        modifier = modifier.padding(start = 2.dp, top = 4.dp),
    )
}

@Composable
private fun DiaryCard(
    entry: DiaryEntry,
    expanded: Boolean,
    styles: MemoryTextStyles,
    onToggle: () -> Unit,
    onShowMenu: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    var origin by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    var overflow by remember(entry.id) { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue = if (pressed) {
            colors.ripple.compositeOver(colors.surface)
        } else {
            colors.surface
        },
        animationSpec = tween(WhaleDuration.Dismiss, easing = TgEasing.Linear),
        label = "diaryCardPress",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = DiaryExpandSpec)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(entry.id) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { if (expanded || overflow) onToggle() },
                    onLongPress = { touch -> onShowMenu(origin + touch) },
                )
            }
            .clip(CardShape)
            .background(background)
            .border(CardBorder, colors.divider, CardShape),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = monthDayLabel(entry.date),
                    style = styles.diaryDate,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = weekdayLabel(entry.date),
                    style = styles.diaryMeta,
                    color = colors.textCaption,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = entry.weather,
                    style = styles.diaryMeta,
                    color = colors.accent,
                )
                var dotsCenter by remember { mutableStateOf(Offset.Zero) }
                Box(
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .onGloballyPositioned {
                            dotsCenter = it.positionInRoot() +
                                Offset(it.size.width / 2f, it.size.height / 2f)
                        }
                        .size(40.dp)
                        .clip(WhaleShapes.pill)
                        .clickable { onShowMenu(dotsCenter) }
                        .semantics { contentDescription = "日记菜单" },
                    contentAlignment = Alignment.Center,
                ) {
                    WhaleIcon(WhaleIcons.More, tint = colors.textSecondary, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = entry.content,
                style = styles.diaryBody,
                color = colors.textPrimary,
                maxLines = if (expanded) Int.MAX_VALUE else CollapsedDiaryLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (!expanded) overflow = result.hasVisualOverflow
                },
            )
            if (overflow) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (expanded) "收起" else "展开全文",
                    style = styles.diaryMeta.copy(color = colors.accent),
                    color = colors.accent,
                )
            }
        }
    }
}
