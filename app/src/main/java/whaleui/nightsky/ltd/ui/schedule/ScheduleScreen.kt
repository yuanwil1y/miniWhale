package whaleui.nightsky.ltd.ui.schedule

import whaleui.nightsky.ltd.ui.components.UndoBulletin
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.WhaleMotion
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.delay
import whaleui.nightsky.ltd.ui.chat.rememberChatTextStyles
import whaleui.nightsky.ltd.ui.theme.WhaleTheme

// ---------------------------------------------------------------------------
// 数值来源
//
// - 开关：ref 的 ui/Components/Switch.java —— 动画 200ms（默认 AccelerateDecelerate）、
//   颜色按 progress 逐通道插值、滑块行程按轨道宽与滑块内边距推导、按下只有 ripple。
//   轨道/滑块尺寸按设计图 46×30dp + 23dp 滑块（设计图比 Telegram 的 31×14 大）。
// - 滚轮：ui/Components/NumberPicker.java —— 单行标称 42dp、5 行高 210dp、
//   源码公式算得 pitch 46dp；靠透视 scaleX=0.8+0.2p / scaleY=p / alpha 淡出，
//   上下渐变遮罩长 (H-textSize)/2=96dp、强度 0.9；分隔线 2dp、选中带 46dp（Y=82/128dp）；
//   吸附 800ms Decelerate(2.5)、单格 300ms。这里简化为松手后 300ms 吸附。
// - 滚轮列配置：AlertsCreator 定时发送对话框 —— 小时 0..23、分钟 0..59（1 分钟步进）、24h 制。
// - 悬浮按钮 / 撤销提示 / 菜单 / 底部弹层：沿用记忆与日记页已做好的那套。
// - 时间线 Cell：ui/Cells 下未找到「左时间+连线+圆点」，沿用设计图的排版自绘。
// ---------------------------------------------------------------------------

internal val TimeColumnWidth = 44.dp
internal val TimelineX = 74.dp
internal val DotSize = 9.5.dp
internal val CardLeft = 92.5.dp
internal val CardShape = RoundedCornerShape(WhaleRadius.l)
internal val CardBorder = 0.5.dp
internal val RowGap = 10.dp













internal val SheetHandleHeight = 32.dp
internal val SheetRadius = WhaleShapes.sheet





private const val DeleteToastDelayMs = 150L


private val FabSpec = tween<Float>(WhaleDuration.Fab, easing = TgEasing.EaseOutQuint)
internal val ListFadeSpec = WhaleMotion.chatList<Float>()
internal val ListPlacementSpec =
    tween<IntOffset>(WhaleDuration.Medium, easing = TgEasing.ChatList)


// ---------------------------------------------------------------------------
// 文本
// ---------------------------------------------------------------------------

@Immutable
internal data class ScheduleTextStyles(
    val pageTitle: TextStyle,
    val summary: TextStyle,
    val cardTitle: TextStyle,
    val status: TextStyle,
    val time: TextStyle,
    val nowTime: TextStyle,
    val editorTitle: TextStyle,
    val field: TextStyle,
    val fieldLabel: TextStyle,
    val chip: TextStyle,
    val button: TextStyle,
    val wheel: TextStyle,
    val toastInfo: TextStyle,
    val toastAction: TextStyle,
    val emptyTitle: TextStyle,
    val emptyBody: TextStyle,
)

@Composable
private fun rememberScheduleTextStyles(): ScheduleTextStyles {
    val type = WhaleTheme.type
    return remember(type) {
        ScheduleTextStyles(
            pageTitle = type.pageTitle,
            summary = type.cellSubtitle,
            cardTitle = type.cellTitle,
            status = type.cellSubtitle,
            time = type.fileName,
            nowTime = type.cellSubtitle,
            editorTitle = type.dialogTitle,
            field = type.cellTitle,
            fieldLabel = type.small,
            chip = type.footnote,
            button = type.button,
            wheel = type.wheel,
            toastInfo = type.bulletin,
            toastAction = type.bulletinAction,
            emptyTitle = type.emptyTitle,
            emptyBody = type.emptyText,
        )
    }
}

// ---------------------------------------------------------------------------
// 页面
// ---------------------------------------------------------------------------

/**
 * 定时任务。无状态：任务/时间线/「现在」位置/编辑弹层/菜单/撤销都由 [state] 传入。
 */
/** 没有精确闹钟权限时的一行提示（W-029）。 */
internal const val EXACT_ALARM_HINT = "没开「闹钟和提醒」权限，她可能晚几分钟才来找你。去「设置 → 权限与后台常驻」打开。"

@Composable
fun ScheduleScreen(
    state: ScheduleUiState,
    onBack: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onAddTask: () -> Unit,
    onOpenTask: (String) -> Unit,
    onShowMenu: (String, Offset) -> Unit,
    onDismissMenu: () -> Unit,
    onEditFromMenu: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onEditorChange: (ScheduleEditor) -> Unit,
    onSaveEditor: () -> Unit,
    onCloseEditor: () -> Unit,
    onUndo: () -> Unit,
    onUndoExpired: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "定时任务",
    nowLabel: String = "现在",
) {
    val colors = WhaleTheme.colors
    val styles = rememberScheduleTextStyles()
    val listState = rememberLazyListState()
    val fabVisible = rememberFabVisible(listState)
    val fabProgress by animateFloatAsState(
        targetValue = if (fabVisible) 1f else 0f,
        animationSpec = FabSpec,
        label = "scheduleFabProgress",
    )

    BackHandler(enabled = state.editor == null && state.menu != null) {
        onDismissMenu()
    }

    // 进页面时滚到「现在」附近（不是顶部）。
    LaunchedEffect(state.scrollToNowTick, state.isLoading) {
        if (!state.isLoading) {
            listState.scrollToItem((state.markerIndex - 1).coerceAtLeast(0))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WhaleTheme.colors.bgPage),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 16.dp, top = 8.dp)
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButtonBox(
                    description = "返回",
                    size = 44.dp,
                    onClick = onBack,
                ) {
                    WhaleIcon(WhaleIcons.Back, tint = WhaleTheme.colors.textPrimary, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    text = title,
                    style = styles.pageTitle,
                    color = WhaleTheme.colors.textPrimary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.summary,
                style = styles.summary,
                color = WhaleTheme.colors.textCaption,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (!state.exactAlarmAllowed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = EXACT_ALARM_HINT,
                    style = styles.summary,
                    color = WhaleTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Spacer(Modifier.height(6.dp))

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (state.timeline.isEmpty() && state.upcoming.isEmpty() && !state.isLoading) {
                    ScheduleEmptyState(
                        title = "今天没有任务",
                        subtitle = "添加一条，让她在某个时间来找你",
                        styles = styles,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    ScheduleTimeline(
                        state = state,
                        listState = listState,
                        styles = styles,
                        nowLabel = nowLabel,
                        onToggle = onToggle,
                        onOpenTask = onOpenTask,
                        onShowMenu = onShowMenu,
                    )
                }

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
                            ambientColor = colors.bubbleShadow,
                            spotColor = colors.bubbleShadow,
                        )
                        .clip(WhaleTheme.shapes.pill)
                        .background(WhaleTheme.colors.accentFill)
                        .clickable(enabled = fabProgress > 0.99f, onClick = onAddTask)
                        .height(56.dp)
                        .padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WhaleIcon(WhaleIcons.Plus, tint = WhaleTheme.colors.onAccent, size = 22.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "添加任务",
                            style = styles.button,
                            color = WhaleTheme.colors.onAccent,
                        )
                    }
                }
            }
        }

        val menu = state.menu
        val menuTarget = state.menuTarget()
        if (menu != null && menuTarget != null) {
            val target = menuTarget
            SchedulePopupMenu(
                touch = menu.touch,
                styles = styles,
                actions = listOf(
                    ScheduleMenuAction(
                        label = "编辑",
                        icon = WhaleIcons.Pencil,
                        onSelect = { onEditFromMenu(target.id) },
                    ),
                    ScheduleMenuAction(
                        label = "删除",
                        icon = WhaleIcons.Trash,
                        danger = true,
                        onSelect = { onDeleteTask(target.id) },
                    ),
                ),
                onDismiss = onDismissMenu,
            )
        }

        state.editor?.let { editor ->
            ScheduleEditorSheet(
                editor = editor,
                styles = styles,
                onChange = onEditorChange,
                onSave = onSaveEditor,
                onCancel = onCloseEditor,
            )
        }

        UndoBulletin(key = state.undo, text = "已删除任务", onUndo = onUndo, onExpired = onUndoExpired)
    }
}
