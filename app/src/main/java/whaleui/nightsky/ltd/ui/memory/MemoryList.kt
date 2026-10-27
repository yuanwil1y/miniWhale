package whaleui.nightsky.ltd.ui.memory

import androidx.compose.ui.graphics.vector.ImageVector
import whaleui.nightsky.ltd.ui.components.EmptyState

import androidx.compose.ui.graphics.compositeOver
import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory
import whaleui.nightsky.ltd.domain.memory.MemoryOrigin

// 从 MemoryScreen.kt 拆出来（R1 / W-051，只搬代码不改实现）

// ---------------------------------------------------------------------------
// 记忆列表
// ---------------------------------------------------------------------------

@Composable
internal fun MemoryListPage(
    state: MemoryUiState,
    styles: MemoryTextStyles,
    listState: LazyListState,
    onFilterChange: (MemoryCategory?) -> Unit,
    onAddMemory: () -> Unit,
    onOpenMemory: (String) -> Unit,
    onShowMenu: (String, Offset) -> Unit,
) {
    val colors = WhaleTheme.colors
    val fabVisible = rememberFabVisible(listState)
    val fabProgress by animateFloatAsState(
        targetValue = if (fabVisible) 1f else 0f,
        animationSpec = FabSpec,
        label = "fabProgress",
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MemoryFilterChip(
                    label = "全部",
                    selected = state.category == null,
                    styles = styles,
                    onClick = { onFilterChange(null) },
                )
                MemoryCategory.entries.forEach { category ->
                    MemoryFilterChip(
                        label = category.label,
                        selected = state.category == category,
                        styles = styles,
                        onClick = { onFilterChange(category) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    state.allMemories.isEmpty() -> MemoryEmptyState(
                        title = "还没有记忆",
                        subtitle = "她会慢慢记住关于你的事",
                        styles = styles,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    state.visibleMemories.isEmpty() -> MemoryEmptyState(
                        title = "这个分类还没有记忆",
                        subtitle = "换个分类看看",
                        styles = styles,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 96.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = state.visibleMemories,
                            key = { it.id },
                            contentType = { "memory" },
                        ) { memory ->
                            MemoryCard(
                                memory = memory,
                                styles = styles,
                                onOpen = { onOpenMemory(memory.id) },
                                onShowMenu = { touch -> onShowMenu(memory.id, touch) },
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

        // 悬浮按钮：位置和尺寸按设计图（56dp 胶囊 / 右 20 底 28）；
        // 出现和滚动隐藏的动画按 Telegram FragmentFloatingButton。
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 28.dp)
                .graphicsLayer {
                    alpha = fabProgress
                    scaleX = 0.4f + 0.6f * fabProgress
                    scaleY = 0.4f + 0.6f * fabProgress
                    translationY = 40.dp.toPx() * (1f - fabProgress)
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .shadow(
                    elevation = 12.dp,
                    shape = WhaleTheme.shapes.pill,
                    ambientColor = WhaleTheme.colors.bubbleShadow,
                    spotColor = WhaleTheme.colors.bubbleShadow,
                )
                .clip(WhaleTheme.shapes.pill)
                .background(colors.accentFill)
                .clickable(enabled = fabProgress > 0.99f, onClick = onAddMemory)
                .height(56.dp)
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WhaleIcon(WhaleIcons.Plus, tint = colors.onAccent, size = 22.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "添加记忆",
                    style = styles.buttonLabel,
                    color = colors.onAccent,
                )
            }
        }
    }
}

@Composable
private fun MemoryCard(
    memory: Memory,
    styles: MemoryTextStyles,
    onOpen: () -> Unit,
    onShowMenu: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    var origin by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue = if (pressed) {
            // Telegram 夜间列表 ripple：白 5.1%。
            colors.ripple.compositeOver(colors.surface)
        } else {
            colors.surface
        },
        animationSpec = tween(WhaleDuration.Dismiss, easing = TgEasing.Linear),
        label = "memoryCardPress",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(memory.id) {
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
            .border(CardBorder, colors.divider, CardShape),
    ) {
        Column(
            Modifier.padding(start = 18.dp, end = 44.dp, top = 16.dp, bottom = 16.dp),
        ) {
            Text(
                text = memory.content,
                style = styles.memoryBody,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${memory.category.label} · " +
                        memoryDateLabel(memory.createdAt.toLocalDate()),
                    style = styles.meta,
                    color = colors.textCaption,
                )
                if (memory.origin == MemoryOrigin.HerRecorded) {
                    Spacer(Modifier.width(8.dp))
                    // 很小的来源标记，不抢正文。
                    Box(
                        Modifier
                            .size(3.dp)
                            .clip(WhaleShapes.pill)
                            .background(colors.accent.copy(alpha = WhaleAlpha.Glass)),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "她记下的",
                        style = styles.origin,
                        color = colors.accent.copy(alpha = WhaleAlpha.Glass),
                    )
                }
            }
        }
        var dotsCenter by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 2.dp, end = 2.dp)
                .onGloballyPositioned {
                    dotsCenter = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)
                }
                .size(40.dp)
                .clip(WhaleShapes.pill)
                .clickable { onShowMenu(dotsCenter) }
                .semantics { contentDescription = "记忆菜单" },
            contentAlignment = Alignment.Center,
        ) {
            WhaleIcon(WhaleIcons.More, tint = colors.textSecondary, modifier = Modifier.size(24.dp))
        }
    }
}

/** 空状态 = 组件库 EmptyState（C：之前是自绘的玻璃小卡）。[styles] 保留给调用方不改签名。 */
@Composable
internal fun MemoryEmptyState(
    title: String,
    subtitle: String,
    @Suppress("UNUSED_PARAMETER") styles: MemoryTextStyles,
    modifier: Modifier = Modifier,
    icon: ImageVector = WhaleIcons.Brain,
) {
    EmptyState(title = title, icon = icon, text = subtitle, modifier = modifier)
}
