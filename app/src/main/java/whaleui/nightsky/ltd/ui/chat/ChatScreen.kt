package whaleui.nightsky.ltd.ui.chat

import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInRoot
import whaleui.nightsky.ltd.ui.theme.WhaleUITheme
import whaleui.nightsky.ltd.ui.components.HerAvatar
import whaleui.nightsky.ltd.ui.components.LocalHerProfile
import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.components.LocalBulletins
import whaleui.nightsky.ltd.ui.components.BulletinObstruction
import whaleui.nightsky.ltd.ui.components.UndoBulletin
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.ui.theme.WhaleMotion
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleWeight
import whaleui.nightsky.ltd.ui.components.Cell
import whaleui.nightsky.ltd.ui.components.WhaleBottomSheet
import androidx.compose.foundation.Image
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import whaleui.nightsky.ltd.domain.chat.ChatMessage
import whaleui.nightsky.ltd.domain.chat.MessageStatus

/** 新消息进入方式：实时消息从底部上来，历史记录淡入+缩放。 */
internal enum class EnterMode {
    FromBottom,
    History,
}

private data class SendFlight(
    val text: String,
    val anchor: Rect,
    val time: String? = null,
    val status: MessageStatus = MessageStatus.Sending,
    val targetId: Long? = null,
    val target: Rect? = null,
)

private data class PendingAnchor(
    val key: String,
    val offset: Int,
    val messageCount: Int,
)

private data class UndoState(
    val message: ChatMessage,
    val index: Int,
)

/**
 * 聊天页。数据、发送、分页、播放都在 [ChatViewModel] 里；这里只读状态、发事件。
 * 临时动画状态（发送飞行、菜单、查看器、滑动）留在 Compose 内。
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onLoadOlder: () -> Unit,
    onPlayVoice: (ChatMessage) -> Unit,
    onMenuClick: () -> Unit,
    onReply: (ChatMessage) -> Unit,
    onCancelReply: () -> Unit,
    onDelete: (ChatMessage) -> Unit,
    onUndoDelete: (ChatMessage, Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 点顶栏头像 / 名字：进她的资料页（B 阶段接上） */
    onOpenProfile: () -> Unit = {},
    title: String = LocalHerProfile.current.nickname,
    /** 没填 DeepSeek 密钥：顶栏状态行显示「没填密钥」 */
    keyMissing: Boolean = false,
    // ---- 表情面板（round5 3.5）：数据由 ViewModel 从表情库读出来 ----
    stickerPacks: List<StickerPanelPack> = emptyList(),
    onSendSticker: (StickerPanelItem) -> Unit = {},
    onOpenStickerSettings: () -> Unit = {},
) {
    val colors = WhaleTheme.colors
    val styles = rememberChatTextStyles()
    val bulletins = LocalBulletins.current
    val hazeState = rememberHazeState()
    val inspection = LocalInspectionMode.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val navigationBottom = with(density) {
        WindowInsets.navigationBars.getBottom(density).toDp()
    }
    val imeBottom = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val bottomInset = maxOf(navigationBottom, imeBottom)
    val rows = remember(state.messages) { buildChatRows(state.messages) }
    val displayRows = remember(rows) { rows.asReversed() }
    val dayByKey = remember(rows) {
        rows.associate {
            when (it) {
                is ChatRowModel.Day -> it.key to it.label
                is ChatRowModel.Msg -> it.key to it.message.dayLabel
            }
        }
    }

    var inputHeightPx by remember { mutableIntStateOf(0) }
    var inputTopPx by remember { mutableIntStateOf(0) }
    var rootHeightPx by remember { mutableIntStateOf(0) }
    val inputHeight = with(density) { inputHeightPx.toDp() }
    val barHeight = if (rootHeightPx > 0 && inputTopPx > 0) {
        with(density) { (rootHeightPx - inputTopPx).toDp() }
    } else {
        8.dp + inputHeight + bottomInset
    }
    val listBottom = barHeight + 8.dp
    val scrollButtonBottom = barHeight + 10.dp

    var bootstrapped by remember { mutableStateOf(false) }
    var seenIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var entering by remember { mutableStateOf<Map<Long, EnterMode>>(emptyMap()) }
    var shownOrder by remember { mutableStateOf<List<Long>>(emptyList()) }
    var deleting by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var bubbleRects by remember { mutableStateOf<Map<Long, Rect>>(emptyMap()) }
    var sendFlight by remember { mutableStateOf<SendFlight?>(null) }
    val flightProgress = remember { Animatable(0f) }
    var menu by remember { mutableStateOf<MessageMenuState?>(null) }
    // 表情面板（round5 3.5）：点表情按钮展开，点输入框 / 返回键收起
    var stickerPanelOpen by remember { mutableStateOf(false) }
    var attachSheetOpen by remember { mutableStateOf(false) }
    var keyboardRequestToken by remember { mutableIntStateOf(0) }
    var viewerOpen by remember { mutableStateOf(false) }
    var viewerSource by remember { mutableStateOf<String?>(null) }
    var unread by remember { mutableIntStateOf(0) }
    var followBottom by remember { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }
    var floatingVisible by remember { mutableStateOf(false) }
    var pendingAnchor by remember { mutableStateOf<PendingAnchor?>(null) }
    var undo by remember { mutableStateOf<UndoState?>(null) }

    val isAtBottom by remember {
        derivedStateOf {
            // reverseLayout 下，底部就是不能再往后滚的位置。
            // 只比较 firstVisibleItemIndex 会被 contentPadding 的 offset 误判。
            !listState.canScrollBackward
        }
    }

    BackHandler(enabled = viewerOpen || menu != null || stickerPanelOpen) {
        when {
            // 系统返回键先收面板（round5 3.5）
            stickerPanelOpen -> stickerPanelOpen = false
            viewerOpen -> viewerOpen = false
            menu != null -> menu = null
        }
    }

    // 用户往上翻时不要抢滚动；回到最底部后又恢复跟随。
    // 仅监听用户/动画滚动，消息插入本身不会改这个标记。
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.isScrollInProgress to listState.canScrollBackward
        }
            .distinctUntilChanged()
            .collect { (scrolling, canScrollBack) ->
                if (scrolling && !programmaticScroll) {
                    followBottom = !canScrollBack
                }
            }
    }

    // 首次加载：记为已见，不做进入动画（打开聊天、滚动时进入视野的旧消息都不动）。
    // 之后：新消息轻微上滑 + 淡入（她的、我发的图片 / 文件 / 表情；我发的文字走发送飞入）；
    // 分页历史淡入 + 缩放；同时把发送飞行和消息 id 对上。只看「哪些 id 是新的」，不碰节奏器。
    LaunchedEffect(state.messages) {
        val ids = state.messages.mapTo(HashSet()) { it.id }
        val previousOrder = shownOrder
        shownOrder = state.messages.map { it.id }
        if (!bootstrapped) {
            if (state.messages.isNotEmpty()) {
                seenIds = ids
                bootstrapped = true
            }
            return@LaunchedEffect
        }
        val seen = seenIds
        seenIds = ids
        val newMessages = state.messages.filter { it.id !in seen }
        if (newMessages.isEmpty()) return@LaunchedEffect

        val lastSeenIndex = state.messages.indexOfLast { it.id in seen }
        val appended = newMessages.filter { message ->
            state.messages.indexOfFirst { it.id == message.id } > lastSeenIndex
        }
        val outgoing = appended.lastOrNull { !it.fromHer && it.status == MessageStatus.Sending }
        if (outgoing != null && sendFlight?.targetId == null) {
            sendFlight = sendFlight?.copy(
                targetId = outgoing.id,
                time = outgoing.time,
                status = outgoing.status,
            )
        }
        val flightId = sendFlight?.targetId
        // 入库换 id（乐观 id → 库 id）的同一个气泡：原位置上的旧 id 没了，不当新消息再淡入一次
        val renamed = state.messages.withIndex().filter { (index, message) ->
            message.id !in seen && previousOrder.getOrNull(index)?.let { it !in ids } == true
        }.mapTo(HashSet()) { it.value.id }
        val enterMap = appended.filter { it.id != flightId && it.id !in renamed }.associate {
            it.id to EnterMode.FromBottom
        } + newMessages.filter { it.fromHer && it !in appended }.associate {
            it.id to EnterMode.History
        }
        if (appended.any { it.fromHer } && !followBottom) {
            unread += appended.count { it.fromHer }
        }
        if (enterMap.isNotEmpty()) {
            entering = entering + enterMap
            // 不跟这个 effect 一起被取消：动画播完一定要清掉，否则滚出去再滚回来会重播
            scope.launch {
                delay(WhaleDuration.Medium.toLong() + WhaleDuration.Press)
                entering = entering - enterMap.keys
            }
        }
    }

    LaunchedEffect(state.messages.lastOrNull()?.id) {
        val last = state.messages.lastOrNull() ?: return@LaunchedEffect
        if (!bootstrapped) return@LaunchedEffect
        when {
            !last.fromHer -> {
                unread = 0
                followBottom = true
                programmaticScroll = true
                listState.animateScrollToItem(0)
                programmaticScroll = false
            }

            followBottom -> {
                programmaticScroll = true
                listState.animateScrollToItem(0)
                programmaticScroll = false
            }
        }
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) unread = 0
    }

    // 发送飞行：等目标气泡量好位置后开始，250ms 线性时钟，位置用 Telegram 的曲线。
    val flightTarget = sendFlight?.targetId?.let { bubbleRects[it] }
    LaunchedEffect(sendFlight?.targetId, flightTarget != null) {
        val flight = sendFlight ?: return@LaunchedEffect
        val target = flightTarget ?: return@LaunchedEffect
        flightProgress.snapTo(0f)
        sendFlight = flight.copy(target = target)
        flightProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(WhaleDuration.Medium, easing = LinearEasing),
        )
        sendFlight = null
    }

    LaunchedEffect(sendFlight?.targetId) {
        if (sendFlight?.targetId != null && sendFlight?.target == null) {
            delay(1_200)
            if (sendFlight?.target == null) sendFlight = null
        }
    }

    // 滚到底部按钮 / 浮动日期
    val dayRowIndexByLabel = remember(displayRows) {
        displayRows.mapIndexedNotNull { index, row ->
            (row as? ChatRowModel.Day)?.let { it.label to index }
        }.toMap()
    }
    val topVisibleDay by remember {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo
            val top = info.maxByOrNull { it.offset + it.size }
            val key = top?.key as? String
            val row = displayRows.getOrNull(top?.index ?: -1)
            if (row is ChatRowModel.Msg) dayByKey[key] else null
        }
    }
    val topDateCellVisible by remember {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo
            val top = info.maxByOrNull { it.offset + it.size }
            val row = displayRows.getOrNull(top?.index ?: -1) as? ChatRowModel.Msg
                ?: return@derivedStateOf true
            val dayIndex = dayRowIndexByLabel[row.message.dayLabel] ?: return@derivedStateOf true
            info.any { it.index == dayIndex }
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            floatingVisible = true
        } else {
            delay(500)
            floatingVisible = false
        }
    }
    // 顶到历史就自动加载下一页。
    LaunchedEffect(displayRows.size, state.hasOlder, state.isLoadingOlder) {
        if (displayRows.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
        }
            .distinctUntilChanged()
            .collect { maxIndex ->
                if (maxIndex >= displayRows.lastIndex - 1 &&
                    state.hasOlder &&
                    !state.isLoadingOlder
                ) {
                    val anchorRow = displayRows.getOrNull(listState.firstVisibleItemIndex)
                    if (anchorRow != null) {
                        pendingAnchor = PendingAnchor(
                            key = anchorRow.key,
                            offset = listState.firstVisibleItemScrollOffset,
                            messageCount = state.messages.size,
                        )
                    }
                    onLoadOlder()
                }
            }
    }

    // 分页插入到顶部后，按 key 滚回原来的锚点，视口不跳。
    LaunchedEffect(state.messages.size, state.isLoadingOlder, state.hasOlder) {
        val anchor = pendingAnchor ?: return@LaunchedEffect
        if (state.isLoadingOlder) return@LaunchedEffect
        if (state.messages.size <= anchor.messageCount) {
            if (!state.hasOlder) pendingAnchor = null
            return@LaunchedEffect
        }
        val index = displayRows.indexOfFirst { it.key == anchor.key }
        if (index >= 0) {
            listState.scrollToItem(index, anchor.offset)
        }
        pendingAnchor = null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { rootHeightPx = it.size.height }
            .background(colors.surface),
    ) {
        // 背景和消息列表作为 Haze 的源，顶栏/输入栏浮在上面。
        Box(
            Modifier
                .fillMaxSize()
                .then(if (inspection) Modifier else Modifier.hazeSource(hazeState)),
        ) {
            ChatBackground()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                ChatMessageList(
                    rows = rows,
                    listState = listState,
                    maxTextWidth = maxWidth - BubbleTextInset,
                    topPadding = statusTop + 70.dp,
                    bottomPadding = listBottom,
                    voice = state.voice,
                    entering = entering,
                    deletingIds = deleting,
                    hiddenForFlightId = sendFlight?.targetId,
                    selectedId = menu?.message?.id,
                    isLoadingOlder = state.isLoadingOlder,
                    onBubbleRect = { id, rect ->
                        if (bubbleRects[id] != rect) {
                            bubbleRects = bubbleRects + (id to rect)
                        }
                    },
                    onLongPress = { message, offset ->
                        val rect = bubbleRects[message.id] ?: Rect.Zero
                        menu = MessageMenuState(
                            message = message,
                            anchor = rect,
                            touch = rect.topLeft + offset,
                        )
                    },
                    onReply = onReply,
                    onImageClick = { source ->
                        keyboardController?.hide()
                        viewerSource = source
                        viewerOpen = true
                    },
                    onVoiceClick = onPlayVoice,
                    styles = styles,
                )
            }
        }

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(statusTop + 70.dp)
                .background(
                    Brush.verticalGradient(
                        0f to colors.chatBg2.copy(alpha = WhaleAlpha.Solid),
                        0.72f to colors.chatBg2.copy(alpha = WhaleAlpha.MenuGlass),
                        1f to colors.chatBg2.copy(alpha = WhaleAlpha.Hidden),
                    ),
                ),
        )

        ChatTopBar(
            title = title,
            presence = herPresence(state.isHerTyping, state.typingLabel, keyMissing),
            onMenuClick = onMenuClick,
            onNameClick = onOpenProfile,
            hazeState = hazeState,
            inspection = inspection,
            styles = styles,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        AnimatedVisibility(
            visible = floatingVisible && topVisibleDay != null && !topDateCellVisible && menu == null,
            enter = fadeIn(tween(WhaleDuration.Fast)) +
                slideInVertically(tween(WhaleDuration.Fast), initialOffsetY = { -it / 2 }),
            exit = fadeOut(tween(WhaleDuration.Fast)) +
                slideOutVertically(tween(WhaleDuration.Fast), targetOffsetY = { -it / 2 }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = statusTop + 70.dp + 4.dp),
        ) {
            FloatingDate(label = topVisibleDay ?: "", styles = styles)
        }

        if (state.messages.isEmpty() && !state.isInitialLoading) {
            // 有键盘 / 面板时跟着输入栏上移，不被盖住
            EmptyChat(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = statusTop + 70.dp, bottom = barHeight),
                styles = styles,
            )
        }

        ScrollToBottomButton(
            visible = !isAtBottom && state.messages.isNotEmpty() && menu == null,
            unread = unread,
            onClick = {
                unread = 0
                followBottom = true
                programmaticScroll = true
                scope.launch {
                    listState.animateScrollToItem(0)
                    programmaticScroll = false
                }
            },
            hazeState = hazeState,
            inspection = inspection,
            bottom = scrollButtonBottom,
            modifier = Modifier.align(Alignment.BottomEnd),
        )

        ChatInputBar(
            draft = state.draft,
            draftAlpha = if (sendFlight != null) 0f else 1f,
            sendBlocked = sendFlight != null,
            attachments = state.pendingAttachments,
            replyTo = state.replyTo,
            onDraftChange = onDraftChange,
            onSend = { anchor ->
                val text = state.draft
                if (text.isNotBlank() || state.pendingAttachments.isNotEmpty()) {
                    sendFlight = if (text.isNotBlank() && state.pendingAttachments.isEmpty()) {
                        SendFlight(text = text, anchor = anchor)
                    } else {
                        null
                    }
                    onSend()
                }
            },
            onAttachClick = {
                keyboardController?.hide()
                attachSheetOpen = true
            },
            onRemoveAttachment = onRemoveAttachment,
            onCancelReply = onCancelReply,
            onHeightChanged = { inputHeightPx = it },
            onTopChanged = { inputTopPx = it },
            hazeState = hazeState,
            inspection = inspection,
            styles = styles,
            modifier = Modifier.align(Alignment.BottomCenter),
            stickerPanelOpen = stickerPanelOpen,
            onToggleStickerPanel = {
                if (stickerPanelOpen) {
                    stickerPanelOpen = false
                    // 再点同一按钮：切回键盘（输入框拿焦点 + 弹键盘）
                    keyboardRequestToken += 1
                } else {
                    keyboardController?.hide()
                    stickerPanelOpen = true
                }
            },
            onInputFocused = { if (stickerPanelOpen) stickerPanelOpen = false },
            stickerPacks = stickerPacks,
            onSendSticker = onSendSticker,
            onOpenStickerSettings = {
                stickerPanelOpen = false
                onOpenStickerSettings()
            },
            keyboardRequestToken = keyboardRequestToken,
        )

        // 输入框文字 → 气泡的飞行副本
        val flight = sendFlight
        val target = flight?.target
        if (flight != null && target != null) {
            SendFlightOverlay(
                flight = flight,
                target = target,
                progress = flightProgress.value,
                styles = styles,
            )
        }

        menu?.let { menuState ->
            MessageActionMenu(
                state = menuState,
                bottomInset = barHeight + 8.dp,
                onDismiss = { menu = null },
                onCopy = {
                    val text = menuState.message.text
                    if (!text.isNullOrBlank()) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("消息", text))
                        bulletins.show("已复制")
                    }
                    menu = null
                },
                onReply = {
                    onReply(menuState.message)
                    menu = null
                },
                onDelete = {
                    val id = menuState.message.id
                    val index = state.messages.indexOfFirst { it.id == id }
                    deleting = deleting + id
                    menu = null
                    scope.launch {
                        delay(130)
                        onDelete(menuState.message)
                        deleting = deleting - id
                        undo = UndoState(menuState.message, index.coerceAtLeast(0))
                    }
                },
            )
        }

        // 附件：底部弹层两行（u2 §6.3），选完关掉弹层再打开系统选择器
        if (attachSheetOpen) {
            WhaleBottomSheet(onDismiss = { attachSheetOpen = false }, contentPadding = WhaleSpacing.s) {
                Cell(
                    title = "照片",
                    subtitle = "从相册里选，可以多选",
                    icon = WhaleIcons.Image,
                    iconTint = colors.accent,
                    onClick = { dismiss { attachSheetOpen = false; onPickImages() } },
                )
                Cell(
                    title = "文件",
                    subtitle = "PDF、文档、表格…",
                    icon = WhaleIcons.File,
                    iconTint = colors.success,
                    onClick = { dismiss { attachSheetOpen = false; onPickFiles() } },
                )
            }
        }

        ImageViewerOverlay(
            open = viewerOpen,
            source = viewerSource,
            onDismiss = { viewerOpen = false },
        )
        LaunchedEffect(viewerOpen) {
            if (!viewerOpen) {
                delay(240)
                viewerSource = null
            }
        }

        // 撤销 = 全局 Bulletin（5 秒；ViewModel 的落库窗口 5.5 秒，撤销按钮在时绝不会提前落库）
        val pendingUndo = undo
        UndoBulletin(
            key = pendingUndo?.message?.id,
            text = "已删除消息",
            onUndo = {
                pendingUndo?.let { onUndoDelete(it.message, it.index) }
                undo = null
            },
            onExpired = { if (undo?.message?.id == pendingUndo?.message?.id) undo = null },
        )
        // Bulletin 放在输入栏上方
        BulletinObstruction(barHeight + WhaleSpacing.s)
    }
}

// ---------------------------------------------------------------------------
// 背景 / 空状态 / 浮动日期 / 回到底部
// ---------------------------------------------------------------------------

@Composable
private fun ChatBackground() {
    val colors = WhaleTheme.colors
    val resources = androidx.compose.ui.platform.LocalResources.current
    val density = LocalDensity.current
    val tilePaint = remember(resources, density.density) {
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.chat_bg_tile)
        val scale = with(density) { 480.dp.toPx() } / bitmap.width
        val shader = BitmapShader(
            bitmap,
            Shader.TileMode.REPEAT,
            Shader.TileMode.REPEAT,
        ).apply {
            setLocalMatrix(Matrix().apply { setScale(scale, scale) })
        }
        android.graphics.Paint().apply {
            this.shader = shader
            isAntiAlias = true
            isFilterBitmap = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                // 底色：chatBg1..4 四角渐变的近似（两条对角渐变叠起来），纹样不变（u2 §1）
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(colors.chatBg2, colors.chatBg1),
                        start = Offset(size.width, 0f),
                        end = Offset(0f, size.height),
                    ),
                )
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(colors.chatBg4.copy(alpha = WhaleAlpha.Disabled), colors.chatBg3.copy(alpha = WhaleAlpha.Hidden)),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                    ),
                )
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawRect(
                        0f,
                        0f,
                        size.width,
                        size.height,
                        tilePaint,
                    )
                }
            },
    )
}

/** 空聊天：列表中部一张服务卡（u2 §8）——头像 + 「还没有消息」+「跟她打个招呼吧」，出现时轻微放大淡入。 */
@Composable
private fun EmptyChat(modifier: Modifier = Modifier, styles: ChatTextStyles) {
    val colors = WhaleTheme.colors
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, WhaleMotion.fade()) }
    Column(
        modifier = modifier
            .graphicsLayer {
                alpha = appear.value
                scaleX = 0.8f + 0.2f * appear.value
                scaleY = 0.8f + 0.2f * appear.value
            }
            .width(EmptyCardWidth)
            .clip(WhaleShapes.card)
            .background(colors.serviceBubble)
            .padding(WhaleSpacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HerAvatar(size = EmptyAvatar)
        Spacer(Modifier.height(WhaleSpacing.m))
        Text(
            text = "还没有消息",
            style = WhaleTheme.type.fileName,
            color = colors.serviceText,
        )
        Spacer(Modifier.height(WhaleSpacing.xs))
        Text(
            text = "跟她打个招呼吧",
            style = styles.status,
            color = colors.serviceText.copy(alpha = WhaleAlpha.Soft),
        )
    }
}


@Composable
private fun FloatingDate(label: String, styles: ChatTextStyles) {
    if (label.isEmpty()) return
    // 和列表里的日期胶囊同一款（u2 §3）
    ServicePill(label = label, styles = styles)
}

/** 滚到底按钮（u2 §7）：44dp 玻璃圆 + 向下箭头；未读数徽标在圆上方。 */
@Composable
private fun ScrollToBottomButton(
    visible: Boolean,
    unread: Int,
    onClick: () -> Unit,
    hazeState: HazeState,
    inspection: Boolean,
    bottom: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    val glassStyle = rememberGlassStyle()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(WhaleDuration.ScrollButton, easing = TgEasing.Decelerate)) +
            scaleIn(
                initialScale = 0.7f,
                animationSpec = tween(WhaleDuration.ScrollButton, easing = TgEasing.Decelerate),
            ) + slideInVertically(
            animationSpec = tween(WhaleDuration.ScrollButton, easing = TgEasing.Decelerate),
            initialOffsetY = { it },
        ),
        exit = fadeOut(tween(WhaleDuration.Short)) +
            scaleOut(tween(WhaleDuration.Short), targetScale = 0.7f) +
            slideOutVertically(tween(WhaleDuration.Short), targetOffsetY = { it }),
        modifier = modifier.padding(end = WhaleSpacing.s, bottom = bottom),
    ) {
        Box(
            modifier = Modifier.semantics {
                contentDescription = if (unread > 0) "回到最新消息，$unread 条未读" else "回到最新消息"
            },
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = if (unread > 0) WhaleSpacing.m else 0.dp)
                    .size(WhaleSpacing.inputBarHeight)
                    .glass(
                        shape = CircleShape,
                        state = hazeState,
                        style = glassStyle,
                        inspection = inspection,
                        fallback = colors.surfaceMenu,
                        stroke = colors.glassStrokeTop,
                    )
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                WhaleIcon(WhaleIcons.ChevronDown, tint = colors.textPrimary, modifier = Modifier.size(WhaleSpacing.icon))
            }
            if (unread > 0) {
                Box(
                    modifier = Modifier
                        .height(22.dp)
                        .widthIn(min = 22.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (unread > 99) "99+" else unread.toString(),
                        style = WhaleTheme.type.small.copy(fontWeight = WhaleWeight.Medium),
                        color = colors.onAccent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}


// ---------------------------------------------------------------------------
// 发送飞行副本
// ---------------------------------------------------------------------------

@Composable
private fun SendFlightOverlay(
    flight: SendFlight,
    target: Rect,
    progress: Float,
    styles: ChatTextStyles,
) {
    val colors = WhaleTheme.colors
    val density = LocalDensity.current
    val pX = TgEasing.flight(progress)
    val alphaProgress = ChatMotion.flightAlpha(progress)
    val left = flight.anchor.left + (target.left - flight.anchor.left) * pX
    val right = flight.anchor.right + (target.right - flight.anchor.right) * pX
    val top = flight.anchor.top + (target.top - flight.anchor.top) * progress
    val bottom = flight.anchor.bottom + (target.bottom - flight.anchor.bottom) * progress
    val width = (right - left).coerceAtLeast(1f)
    val height = (bottom - top).coerceAtLeast(1f)
    val corner = 22f + (17f - 22f) * pX
    val fontSize = 18f + (16f - 18f) * pX
    val lineHeight = 24f + (19.2f - 24f) * pX
    val bubbleColor = androidx.compose.ui.graphics.lerp(
        colors.surfaceMenuSolid,
        colors.bubbleOut,
        alphaProgress,
    )
    val textColor = androidx.compose.ui.graphics.lerp(
        colors.textPrimary,
        colors.bubbleTextOut,
        alphaProgress,
    )

    Box(
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(
                width = with(density) { width.toDp() },
                height = with(density) { height.toDp() },
            )
            .clip(RoundedCornerShape(corner.dp))
            .background(bubbleColor)
            .padding(
                start = 16.dp + (BubblePadOutStart - 16.dp) * pX,
                end = BubblePadOutEnd,
                top = 12.dp + (BubblePadTop - 12.dp) * pX,
                bottom = BubblePadBottom,
            ),
    ) {
        Text(
            text = flight.text,
            style = styles.message.copy(fontSize = fontSize.sp, lineHeight = lineHeight.sp),
            color = textColor,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
        )
        if (flight.time != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .graphicsLayer { alpha = alphaProgress },
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = flight.time,
                    style = styles.time,
                    color = textColor.copy(alpha = WhaleAlpha.Muted),
                    maxLines = 1,
                )
                Spacer(Modifier.width(3.dp))
                MessageStatusGlyph(
                    status = flight.status,
                    color = textColor.copy(alpha = WhaleAlpha.Muted),
                    modifier = Modifier.size(width = 15.dp, height = 10.dp),
                )
            }
        }
    }
}
