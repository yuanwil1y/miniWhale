package whaleui.nightsky.ltd.ui.memory

import whaleui.nightsky.ltd.ui.components.BulletinObstruction
import whaleui.nightsky.ltd.ui.components.UndoBulletin
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.WhaleMotion
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import whaleui.nightsky.ltd.ui.chat.rememberChatTextStyles
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.domain.memory.DiaryEntry
import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory

// ---------------------------------------------------------------------------
// 数值来源
//
// 这一页在 Telegram 里没有直接对应物，能对上的部分按 ref/Telegram-Android 的实现取数：
// - 标签指示条：ui/Components/ViewPagerFixed.java (TabsView)
//   点击 → 指示条 250ms cubic-bezier(0.25,0.1,0.25,1)（1805-1806）；
//   点击切页 540ms EASE_OUT_QUINT(.23,1,.32,1)（285-292）；
//   指示条高 4dp、上圆角 3dp、宽=文字宽、左右各 16dp、列表 padding 7dp（2055/1613/1334/1704）；
//   拖动时 p=1-|translationX|/pageWidth，1:1 跟手（675-678、2014-2019）；
//   选中/未选中文字是颜色 blend（1384-1388）。
// - 悬浮按钮：ui/Components/FragmentFloatingButton.java 380ms EASE_OUT_QUINT，
//   scale 0.4→1、alpha 0→1、translationY 40dp→0（38-42、122-136）；
//   滚动隐藏条件见 ui/DialogsActivity.java 4538-4547（顶部 item 位移 >1px 且向下 → 隐藏）。
// - 列表行按压：ui/Components/RecyclerListView.java 统一 ripple；夜间 0x0DFFFFFF（白 5.1%）。
//   Cells 下没有统一的按压缩放/时长。
// - 列表项动画：按需求沿用聊天页那套（WhaleMotion.chatList<Float>()：250ms ListItemEase）。
// ---------------------------------------------------------------------------

/** Bulletin 让出 FAB（56dp + 下边距 28dp + 间隔 12dp）的高度 */
internal val FabObstruction = 96.dp

internal val CardShape = RoundedCornerShape(WhaleRadius.l)
internal val CardBorder = 0.5.dp

/** 日记折叠行数：4 行（Telegram AboutLinkCell 是 3 行，我们的正文 15sp 更长，按排版取 4）。 */
internal const val CollapsedDiaryLines = 4

internal val TabRowHeight = 48.dp
internal val TabSidePadding = 7.dp
internal val TabIndicatorHeight = 4.dp
internal val TabIndicatorTopRadius = 3.dp

/** TabsView: tabsAnimator 250ms + CubicBezierInterpolator.DEFAULT。 */
private val TabIndicatorSpec = tween<Float>(WhaleDuration.Medium, easing = TgEasing.Default)

/** ViewPagerFixed: 点击标签切页 540ms + EASE_OUT_QUINT。 */
private val TabPageSpec = tween<Float>(WhaleDuration.TabPage, easing = TgEasing.EaseOutQuint)

/** FragmentFloatingButton: 380ms + EASE_OUT_QUINT。 */
internal val FabSpec = tween<Float>(WhaleDuration.Fab, easing = TgEasing.EaseOutQuint)

/** 列表条目的进入 / 离开 / 位置变化：聊天页的 ListItem 那套。 */
internal val ListFadeSpec = WhaleMotion.chatList<Float>()
internal val ListPlacementSpec =
    tween<IntOffset>(WhaleDuration.Medium, easing = TgEasing.ChatList)

/** 日记正文展开 / 收起的高度动画：CollapseTextCell(340ms EASE_OUT_QUINT) 那套节奏。 */
internal val DiaryExpandSpec = tween<IntSize>(WhaleDuration.Expand, easing = TgEasing.EaseOutQuint)



// ---------------------------------------------------------------------------
// 文本
// ---------------------------------------------------------------------------

@Immutable
internal data class MemoryTextStyles(
    val title: TextStyle,
    val tab: TextStyle,
    val chip: TextStyle,
    val memoryBody: TextStyle,
    val meta: TextStyle,
    val origin: TextStyle,
    val section: TextStyle,
    val diaryDate: TextStyle,
    val diaryMeta: TextStyle,
    val diaryBody: TextStyle,
    val action: TextStyle,
    val toastInfo: TextStyle,
    val toastAction: TextStyle,
    val emptyTitle: TextStyle,
    val emptyBody: TextStyle,
    val editorTitle: TextStyle,
    val field: TextStyle,
    val buttonLabel: TextStyle,
)

/**
 * 字体统一走聊天页的无衬线：聊天页实际用系统字体 16sp / 400，
 * 这里从 rememberChatTextStyles() 派生（同样抵掉系统粗体 adjustment），只改字号/行高。
 * 设计图里的衬线只当排版参考，没有照搬。
 */
@Composable
internal fun rememberMemoryTextStyles(): MemoryTextStyles {
    val type = WhaleTheme.type
    return remember(type) {
        MemoryTextStyles(
            title = type.pageTitle,
            tab = type.cellTitleMedium,
            chip = type.footnote,
            memoryBody = type.reading,
            meta = type.cellSubtitle,
            origin = type.small,
            section = type.small,
            diaryDate = type.cellTitleMedium,
            diaryMeta = type.cellSubtitle,
            diaryBody = type.reading,
            action = type.menuItem,
            toastInfo = type.bulletin,
            toastAction = type.bulletinAction,
            emptyTitle = type.emptyTitle,
            emptyBody = type.emptyText,
            editorTitle = type.dialogTitle,
            field = type.cellTitle,
            buttonLabel = type.button,
        )
    }
}

// ---------------------------------------------------------------------------
// 页面
// ---------------------------------------------------------------------------

/**
 * 记忆与日记。无状态：页面上的一切（标签、筛选、列表、弹层、撤销）都由
 * [state] 传入，交互通过回调交给 MemoryViewModel。
 */
@Composable
fun MemoryScreen(
    state: MemoryUiState,
    onTabChange: (MemoryTab) -> Unit,
    onFilterChange: (MemoryCategory?) -> Unit,
    onBack: () -> Unit,
    onAddMemory: () -> Unit,
    onOpenMemory: (String) -> Unit,
    onShowMenu: (String, Offset) -> Unit,
    onDismissMenu: () -> Unit,
    onEditMemory: (String) -> Unit,
    onChangeCategory: (String) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onDismissCategoryPicker: () -> Unit,
    onSelectCategory: (MemoryCategory) -> Unit,
    onShowDiaryMenu: (String, Offset) -> Unit,
    onDismissDiaryMenu: () -> Unit,
    onDeleteDiary: (DiaryEntry) -> Unit,
    onToggleDiary: (String) -> Unit,
    onEditorContentChange: (String) -> Unit,
    onEditorCategoryChange: (MemoryCategory) -> Unit,
    onSaveEditor: () -> Unit,
    onCloseEditor: () -> Unit,
    onUndo: () -> Unit,
    onUndoExpired: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "记忆与日记",
) {
    val colors = WhaleTheme.colors
    val styles = rememberMemoryTextStyles()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(initialPage = state.tab.ordinal) {
        MemoryTab.entries.size
    }
    // 指示条：拖动时 1:1 跟手；点击时独立走 250ms DEFAULT，页面本身走 540ms QUINT（Telegram 两套时长）。
    val indicator = remember { Animatable(state.tab.ordinal.toFloat()) }
    var tabClickTarget by remember { mutableStateOf<Int?>(null) }
    val memoryListState = rememberLazyListState()
    val diaryListState = rememberLazyListState()

    LaunchedEffect(pagerState) {
        snapshotFlow {
            Triple(
                pagerState.currentPage,
                pagerState.currentPageOffsetFraction,
                pagerState.isScrollInProgress,
            )
        }.collect { (page, offsetFraction, scrolling) ->
            if (tabClickTarget != null) return@collect
            val target = (page + offsetFraction).coerceIn(0f, 1f)
            if (scrolling) {
                indicator.snapTo(target)
            } else {
                indicator.animateTo(target, TabIndicatorSpec)
            }
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        onTabChange(MemoryTab.entries[pagerState.currentPage])
    }
    LaunchedEffect(state.category) {
        if (memoryListState.firstVisibleItemIndex > 0) memoryListState.scrollToItem(0)
    }
    // 新增记忆插在最前面；带 key 的 LazyColumn 会把旧首项固定在视口，需主动滚回顶部。
    LaunchedEffect(state.scrollToTopTick) {
        if (state.scrollToTopTick > 0) memoryListState.animateScrollToItem(0)
    }

    // 返回键：先关菜单，最后才交给外层退出页面。编辑弹层自己处理返回。
    BackHandler(
        enabled = state.editor == null && (state.menu != null || state.diaryMenu != null),
    ) {
        when {
            state.menu != null -> onDismissMenu()
            state.diaryMenu != null -> onDismissDiaryMenu()
        }
    }

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
            MemoryHeader(
                title = title,
                styles = styles,
                onBack = onBack,
            )
            Spacer(Modifier.height(8.dp))
            MemoryTabs(
                position = indicator.value,
                onTabClick = { index ->
                    if (index == pagerState.currentPage && !pagerState.isScrollInProgress) {
                        return@MemoryTabs
                    }
                    tabClickTarget = index
                    scope.launch {
                        try {
                            coroutineScope {
                                launch {
                                    indicator.animateTo(index.toFloat(), TabIndicatorSpec)
                                }
                                launch {
                                    pagerState.animateScrollToPage(
                                        index,
                                        animationSpec = TabPageSpec,
                                    )
                                }
                            }
                        } finally {
                            tabClickTarget = null
                        }
                    }
                },
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    // ViewPagerFixed: 1/3 页或 3500px/s 才翻页；settle 150–600ms quint-out。
                    snapPositionalThreshold = 1f / 3f,
                    snapAnimationSpec = tween(WhaleDuration.Drawer, easing = TgEasing.EaseOutQuint),
                ),
            ) { page ->
                when (page) {
                    0 -> MemoryListPage(
                        state = state,
                        styles = styles,
                        listState = memoryListState,
                        onFilterChange = onFilterChange,
                        onAddMemory = onAddMemory,
                        onOpenMemory = onOpenMemory,
                        onShowMenu = onShowMenu,
                    )

                    else -> DiaryListPage(
                        state = state,
                        styles = styles,
                        listState = diaryListState,
                        onToggleDiary = onToggleDiary,
                        onShowMenu = onShowDiaryMenu,
                    )
                }
            }
        }

        // ---- 菜单 ----
        val memoryMenuTarget = state.menuTarget
        val memoryMenu = state.menu
        if (memoryMenuTarget != null && memoryMenu != null) {
            val target = memoryMenuTarget
            MemoryPopupMenu(
                touch = memoryMenu.touch,
                styles = styles,
                actions = listOf(
                    MemoryMenuAction(
                        label = "编辑",
                        icon = WhaleIcons.Pencil,
                        onSelect = { onEditMemory(target.id) },
                    ),
                    MemoryMenuAction(
                        label = "改分类",
                        icon = WhaleIcons.Tag,
                        onSelect = { onChangeCategory(target.id) },
                    ),
                    MemoryMenuAction(
                        label = "删除",
                        icon = WhaleIcons.Trash,
                        danger = true,
                        onSelect = { onDeleteMemory(target.id) },
                    ),
                ),
                onDismiss = onDismissMenu,
            )
        }

        val diaryMenuTarget = state.diaryMenuTarget
        val diaryMenu = state.diaryMenu
        if (diaryMenuTarget != null && diaryMenu != null) {
            val target = diaryMenuTarget
            MemoryPopupMenu(
                touch = diaryMenu.touch,
                styles = styles,
                actions = listOf(
                    MemoryMenuAction(
                        label = "复制",
                        icon = WhaleIcons.Copy,
                        onSelect = { copyToClipboard(context, "日记", target.content) },
                    ),
                    MemoryMenuAction(
                        label = "删除",
                        icon = WhaleIcons.Trash,
                        danger = true,
                        onSelect = { onDeleteDiary(target) },
                    ),
                ),
                onDismiss = onDismissDiaryMenu,
            )
        }

        // ---- 改分类 ----
        state.categoryTarget?.let { target ->
            CategoryPickerDialog(
                target = target,
                styles = styles,
                onSelect = onSelectCategory,
                onDismiss = onDismissCategoryPicker,
            )
        }

        // ---- 编辑 / 新建弹层 ----
        state.editor?.let { editor ->
            MemoryEditorSheet(
                editor = editor,
                styles = styles,
                onContentChange = onEditorContentChange,
                onCategoryChange = onEditorCategoryChange,
                onSave = onSaveEditor,
                onCancel = onCloseEditor,
            )
        }

        // ---- 撤销提示 ----
        UndoBulletin(key = state.undo, text = state.undo?.message.orEmpty(), onUndo = onUndo, onExpired = onUndoExpired)
        // FAB 在右下：Bulletin 放在它上方，不再被压住（01 记忆-4）
        BulletinObstruction(FabObstruction)
    }
}
