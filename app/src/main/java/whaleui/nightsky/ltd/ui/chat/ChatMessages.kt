package whaleui.nightsky.ltd.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import whaleui.nightsky.ltd.domain.chat.ChatMessage
import whaleui.nightsky.ltd.domain.chat.ReplyPreview
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.theme.WhaleMotion
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.theme.WhaleTheme

internal sealed interface ChatRowModel {
    val key: String

    data class Day(val label: String) : ChatRowModel {
        override val key: String = "day-$label"
    }

    data class Msg(
        val message: ChatMessage,
        val firstInGroup: Boolean,
        val lastInGroup: Boolean,
        val gapAbove: Dp,
    ) : ChatRowModel {
        override val key: String = "msg-${message.id}"
    }
}

/** 同一人相邻两条隔多久以内算一组（Telegram ChatActivity：`<= 5 * 60` 秒） */
internal const val GROUP_WINDOW_MS = 5 * 60_000L

/**
 * 相邻两条能不能合进一组：同一人、同一天、间隔不超过 [GROUP_WINDOW_MS]。
 * 任一条不知道时间（[ChatMessage.createdAtMillis] = 0，比如测试数据）时只看前两条。
 */
internal fun sameGroup(previous: ChatMessage, next: ChatMessage): Boolean {
    if (previous.fromHer != next.fromHer || previous.dayLabel != next.dayLabel) return false
    if (previous.createdAtMillis <= 0L || next.createdAtMillis <= 0L) return true
    return next.createdAtMillis - previous.createdAtMillis <= GROUP_WINDOW_MS
}

/** 按天插入日期胶囊，并算出气泡分组（组内合并角、只有组末带尾巴）。消息按时间正序传入。 */
internal fun buildChatRows(allMessages: List<ChatMessage>): List<ChatRowModel> {
    // 同一个 id 只保留一条：LazyColumn 的 key 就是 `msg-<id>`，出现重复 key 会直接崩
    // （真实模型 + 工具那几轮里踩到过：Key "msg-1089" was already used）。
    val messages = allMessages.distinctBy { it.id }
    val rows = ArrayList<ChatRowModel>(messages.size + 4)
    var lastDay: String? = null
    messages.forEachIndexed { index, message ->
        val newDay = message.dayLabel != lastDay
        if (newDay) {
            rows += ChatRowModel.Day(message.dayLabel)
            lastDay = message.dayLabel
        }
        val previous = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)
        val joinedBefore = previous != null && sameGroup(previous, message)
        val joinedAfter = next != null && sameGroup(message, next)
        rows += ChatRowModel.Msg(
            message = message,
            firstInGroup = !joinedBefore,
            lastInGroup = !joinedAfter,
            gapAbove = when {
                newDay -> 0.dp
                joinedBefore -> BubbleGapInGroup
                else -> BubbleGapBetweenGroups
            },
        )
    }
    return rows
}

@Composable
internal fun ChatMessageList(
    rows: List<ChatRowModel>,
    listState: LazyListState,
    maxTextWidth: Dp,
    topPadding: Dp,
    bottomPadding: Dp,
    voice: VoicePlayback,
    entering: Map<Long, EnterMode>,
    deletingIds: Set<Long>,
    hiddenForFlightId: Long?,
    selectedId: Long?,
    isLoadingOlder: Boolean,
    onBubbleRect: (Long, Rect) -> Unit,
    onLongPress: (ChatMessage, Offset) -> Unit,
    onReply: (ChatMessage) -> Unit,
    onImageClick: (String) -> Unit,
    onVoiceClick: (ChatMessage) -> Unit,
    styles: ChatTextStyles,
    modifier: Modifier = Modifier,
) {
    val displayRows = remember(rows) { rows.asReversed() }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
    ) {
        items(
            items = displayRows,
            key = { it.key },
            contentType = { if (it is ChatRowModel.Day) "day" else "message" },
        ) { row ->
            // 只做补位动画；新消息的淡入上滑在 MessageRow 里按 entering 单独做，
            // 这样首屏和滚动时出现的旧消息不会动（LazyColumn 的 fadeIn 会连它们一起淡入）。
            val placement = Modifier.animateItem(
                fadeInSpec = null,
                placementSpec = WhaleMotion.chatList(),
                fadeOutSpec = null,
            )
            when (row) {
                is ChatRowModel.Day -> DayPill(
                    label = row.label,
                    styles = styles,
                    modifier = placement,
                )

                is ChatRowModel.Msg -> MessageRow(
                    row = row,
                    maxTextWidth = maxTextWidth,
                    voice = voice,
                    styles = styles,
                    enterMode = entering[row.message.id],
                    deleting = row.message.id in deletingIds,
                    hiddenForFlight = row.message.id == hiddenForFlightId,
                    selected = row.message.id == selectedId,
                    onBubbleRect = onBubbleRect,
                    onLongPress = onLongPress,
                    onReply = onReply,
                    onImageClick = onImageClick,
                    onVoiceClick = onVoiceClick,
                    modifier = placement,
                )
            }
        }
        if (isLoadingOlder) {
            item(key = "older-loading") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = WhaleSpacing.s),
                    contentAlignment = Alignment.Center,
                ) {
                    // 加载更早：同款胶囊里放一个小转圈（u2 §3）
                    Box(
                        modifier = Modifier
                            .clip(WhaleShapes.service)
                            .background(WhaleTheme.colors.serviceBubble)
                            .padding(horizontal = WhaleSpacing.s, vertical = WhaleSpacing.xs),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(WhaleSpacing.l),
                            color = WhaleTheme.colors.serviceText,
                            strokeWidth = WhaleSpacing.xxs,
                        )
                    }
                }
            }
        }
    }
}

/** 日期胶囊（列表内 / 顶部悬浮共用）：14 Medium 白字、`serviceBubble` 底、r11，无描边。 */
@Composable
internal fun ServicePill(label: String, styles: ChatTextStyles, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(WhaleShapes.service)
            .background(WhaleTheme.colors.serviceBubble)
            .padding(horizontal = ServicePillHorizontal, vertical = ServicePillVertical),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = styles.date,
            color = WhaleTheme.colors.serviceText,
            maxLines = 1,
        )
    }
}

@Composable
private fun DayPill(label: String, styles: ChatTextStyles, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = WhaleSpacing.s)
            .semantics { heading() },
        contentAlignment = Alignment.Center,
    ) {
        ServicePill(label = label, styles = styles)
    }
}

@Composable
private fun MessageRow(
    row: ChatRowModel.Msg,
    maxTextWidth: Dp,
    voice: VoicePlayback,
    styles: ChatTextStyles,
    enterMode: EnterMode?,
    deleting: Boolean,
    hiddenForFlight: Boolean,
    selected: Boolean,
    onBubbleRect: (Long, Rect) -> Unit,
    onLongPress: (ChatMessage, Offset) -> Unit,
    onReply: (ChatMessage) -> Unit,
    onImageClick: (String) -> Unit,
    onVoiceClick: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = row.message
    val colors = WhaleTheme.colors
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val maxSwipe = with(density) { 80.dp.toPx() }
    val trigger = with(density) { 50.dp.toPx() }
    val hintStart = with(density) { 20.dp.toPx() }
    val hintRange = with(density) { 30.dp.toPx() }
    val lift = with(density) { MessageEnterLift.toPx() }
    val swipe = remember(message.id) { Animatable(0f) }
    var hapticFired by remember { mutableStateOf(false) }

    // 新消息（简单版）：轻微上滑 + 淡入；分页加载出来的历史：淡入 + 0.9→1 缩放。
    // 首屏和滚动时进入视野的旧消息 enterMode 为 null，不动。
    val enterAlpha = remember(enterMode) {
        Animatable(if (enterMode == null) WhaleAlpha.Visible else WhaleAlpha.Hidden)
    }
    val enterOffset = remember(enterMode) { Animatable(if (enterMode == EnterMode.FromBottom) lift else 0f) }
    val enterScale = remember(enterMode) {
        Animatable(if (enterMode == EnterMode.History) 0.9f else 1f)
    }
    LaunchedEffect(enterMode) {
        when (enterMode) {
            EnterMode.FromBottom -> {
                launch { enterOffset.animateTo(0f, WhaleMotion.chatList()) }
                enterAlpha.animateTo(WhaleAlpha.Visible, WhaleMotion.chatList())
            }

            EnterMode.History -> {
                launch { enterScale.animateTo(1f, WhaleMotion.chatList()) }
                enterAlpha.animateTo(WhaleAlpha.Visible, WhaleMotion.chatList())
            }

            null -> {
                enterAlpha.snapTo(WhaleAlpha.Visible)
                enterOffset.snapTo(0f)
                enterScale.snapTo(1f)
            }
        }
    }
    val deleteAlpha by animateFloatAsState(
        targetValue = if (deleting) WhaleAlpha.Hidden else WhaleAlpha.Visible,
        animationSpec = WhaleMotion.dismiss(),
        label = "deleteAlpha",
    )
    val longPress: (Offset) -> Unit = { offset ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onLongPress(message, offset)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = row.gapAbove,
                start = if (message.fromHer) BubbleEdgeIn else 0.dp,
            )
            .graphicsLayer {
                alpha = enterAlpha.value * deleteAlpha * if (hiddenForFlight) 0f else 1f
                translationY = enterOffset.value
                scaleX = enterScale.value
                scaleY = enterScale.value
            },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (message.fromHer) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            // 右侧滑出来的回复提示。Telegram：20dp 后才显示，20→50dp 填满；
            // 最大位移 80dp，松手 50dp 触发，回位 180ms 减速。
            val hintProgress = ((swipe.value - hintStart) / hintRange).coerceIn(0f, 1f)
            if (hintProgress > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 10.dp)
                        .size(32.dp)
                        .graphicsLayer {
                            alpha = hintProgress
                            scaleX = hintProgress
                            scaleY = hintProgress
                        }
                        .clip(CircleShape)
                        .background(colors.serviceBubble),
                    contentAlignment = Alignment.Center,
                ) {
                    WhaleIcon(WhaleIcons.Reply, tint = colors.serviceText, modifier = Modifier.size(18.dp))
                }
            }

            Box(
                modifier = Modifier
                    .graphicsLayer { translationX = -swipe.value }
                    .pointerInput(message.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (swipe.value >= trigger) {
                                        swipe.animateTo(maxSwipe, tween(WhaleDuration.Press, easing = TgEasing.EaseOut))
                                        onReply(message)
                                    }
                                    swipe.animateTo(
                                        0f,
                                        tween(WhaleDuration.Short, easing = TgEasing.Decelerate),
                                    )
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    swipe.animateTo(
                                        0f,
                                        tween(WhaleDuration.Short, easing = TgEasing.Decelerate),
                                    )
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    val next = (swipe.value - dragAmount * 0.92f)
                                        .coerceIn(0f, maxSwipe)
                                    if (next >= trigger && !hapticFired) {
                                        hapticFired = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } else if (next < trigger) {
                                        hapticFired = false
                                    }
                                    swipe.snapTo(next)
                                }
                            },
                        )
                    }
                    .onGloballyPositioned { onBubbleRect(message.id, it.boundsInRoot()) },
            ) {
                when {
                    // 表情消息：无气泡，距屏幕边 11dp（没有尾巴区）
                    message.isSticker -> StickerBubble(
                        message = message,
                        styles = styles,
                        selected = selected,
                        onLongPress = longPress,
                        modifier = Modifier.padding(
                            start = if (message.fromHer) StickerEdge - BubbleEdgeIn else 0.dp,
                            end = if (message.fromHer) 0.dp else StickerEdge,
                        ),
                    )

                    message.isImage -> ImageBubble(
                        message = message,
                        row = row,
                        maxTextWidth = maxTextWidth,
                        styles = styles,
                        selected = selected,
                        onClick = { message.imageUris.firstOrNull()?.let(onImageClick) },
                        onLongPress = longPress,
                    )

                    message.isVoice -> VoiceBubble(
                        message = message,
                        row = row,
                        maxTextWidth = maxTextWidth,
                        playback = voice,
                        styles = styles,
                        selected = selected,
                        onPlay = { onVoiceClick(message) },
                        onLongPress = longPress,
                    )

                    message.isDocument -> DocumentBubble(
                        message = message,
                        row = row,
                        styles = styles,
                        selected = selected,
                        onLongPress = longPress,
                    )

                    else -> TextBubble(
                        message = message,
                        row = row,
                        maxTextWidth = maxTextWidth,
                        styles = styles,
                        selected = selected,
                        onLongPress = longPress,
                    )
                }
            }
        }
    }
}

/** 一个气泡共用的配色（她 / 我 × 普通 / 选中）。 */
private class BubbleColors(
    val background: Color,
    val text: Color,
    val time: Color,
    val check: Color,
    val clock: Color,
    val link: Color,
)

@Composable
private fun bubbleColors(outgoing: Boolean, selected: Boolean): BubbleColors {
    val c = WhaleTheme.colors
    return if (outgoing) {
        BubbleColors(
            background = if (selected) c.bubbleOutSelected else c.bubbleOut,
            text = c.bubbleTextOut,
            time = if (selected) c.bubbleTimeOutSelected else c.bubbleTimeOut,
            check = if (selected) c.bubbleCheckSelected else c.bubbleCheck,
            clock = if (selected) c.bubbleCheckSelected else c.bubbleClock,
            link = c.bubbleLinkOut,
        )
    } else {
        BubbleColors(
            background = if (selected) c.bubbleInSelected else c.bubbleIn,
            text = c.bubbleTextIn,
            time = if (selected) c.bubbleTimeInSelected else c.bubbleTimeIn,
            check = c.bubbleCheck,
            clock = c.bubbleClock,
            link = c.bubbleLinkIn,
        )
    }
}

/** 记下按下的位置（长按菜单用），不吃掉手势。 */
private fun Modifier.trackDown(key: Any, onDown: (Offset) -> Unit): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onDown(down.position)
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
        }
    }
}

/** 文字类气泡的外壳：形状 + 阴影 + 按压 / 选中色 + 长按 + 31dp 内边距。 */
@Composable
private fun BubbleFrame(
    message: ChatMessage,
    row: ChatRowModel.Msg,
    selected: Boolean,
    onLongPress: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    content: @Composable (BubbleColors) -> Unit,
) {
    val outgoing = !message.fromHer
    val shape = remember(outgoing, row.firstInGroup, row.lastInGroup) {
        BubbleShape(
            outgoing = outgoing,
            topNear = !row.firstInGroup,
            bottomNear = !row.lastInGroup,
            tail = row.lastInGroup,
        )
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var downPosition by remember { mutableStateOf(Offset.Zero) }
    val palette = bubbleColors(outgoing, pressed || selected)
    Box(
        modifier = modifier
            .bubbleBackground(shape, palette.background, WhaleTheme.colors.bubbleShadow)
            .trackDown(message.id) { downPosition = it }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = { onLongPress(downPosition) },
            )
            .padding(
                start = if (outgoing) BubblePadOutStart else BubblePadInStart,
                end = if (outgoing) BubblePadOutEnd else BubblePadInEnd,
                top = BubblePadTop,
                bottom = BubblePadBottom,
            ),
    ) {
        content(palette)
    }
}

@Composable
private fun TextBubble(
    message: ChatMessage,
    row: ChatRowModel.Msg,
    maxTextWidth: Dp,
    styles: ChatTextStyles,
    selected: Boolean,
    onLongPress: (Offset) -> Unit,
) {
    val outgoing = !message.fromHer
    BubbleFrame(
        message = message,
        row = row,
        selected = selected,
        onLongPress = onLongPress,
        modifier = Modifier.widthIn(max = maxTextWidth + BubbleExtraWidth),
    ) { palette ->
        Column(if (message.replyTo != null) Modifier.width(IntrinsicSize.Max) else Modifier) {
            message.replyTo?.let {
                QuoteBlock(reply = it, outgoing = outgoing)
            }
            BubbleText(
                text = message.text.orEmpty(),
                maxTextWidth = maxTextWidth,
                styles = styles,
                palette = palette,
            ) {
                MessageMeta(
                    time = message.time,
                    status = message.status,
                    showStatus = outgoing,
                    color = palette.time,
                    checkColor = palette.check,
                    clockColor = palette.clock,
                    styles = styles,
                )
            }
        }
    }
}

/**
 * 正文 + 右下角时间（Q7 / 02 §2.1）：最后一行放得下「空隙 + 时间」就和最后一行同一行、靠右；
 * 放不下就另起一行靠右。最后一行的宽度用同样的样式和最大宽量一遍得到。
 */
@Composable
private fun BubbleText(
    text: String,
    maxTextWidth: Dp,
    styles: ChatTextStyles,
    palette: BubbleColors,
    meta: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val maxWidthPx = with(density) { maxTextWidth.roundToPx() }
    val lastLineWidth = remember(text, styles.message, maxWidthPx) {
        val layout = measurer.measure(
            text = AnnotatedString(text),
            style = styles.message,
            constraints = Constraints(maxWidth = maxWidthPx),
        )
        val last = layout.lineCount - 1
        if (last < 0) 0f else layout.getLineRight(last) - layout.getLineLeft(last)
    }
    val gapPx = with(density) { BubbleTimeGap.toPx() }
    val dropPx = with(density) { BubbleTimeDrop.roundToPx() }

    Layout(
        content = {
            Text(text = text, style = styles.message, color = palette.text)
            meta()
        },
    ) { measurables, constraints ->
        val limit = minOf(maxWidthPx, constraints.maxWidth)
        val body = measurables[0].measure(Constraints(maxWidth = limit))
        val stamp = measurables[1].measure(Constraints())
        val inline = lastLineWidth + gapPx + stamp.width <= limit
        val width = if (inline) {
            maxOf(body.width, (lastLineWidth + gapPx).roundToInt() + stamp.width)
        } else {
            maxOf(body.width, stamp.width)
        }.coerceIn(constraints.minWidth, maxOf(constraints.minWidth, constraints.maxWidth))
        val height = if (inline) body.height else body.height + stamp.height
        layout(width, height) {
            body.place(0, 0)
            val y = if (inline) body.height - stamp.height + dropPx else body.height
            stamp.place(width - stamp.width, y)
        }
    }
}

@Composable
private fun QuoteBlock(reply: ReplyPreview, outgoing: Boolean) {
    val colors = WhaleTheme.colors
    val accent = if (outgoing) colors.bubbleLinkOut else colors.accentHeader
    val body = if (outgoing) colors.bubbleTextOut else colors.bubbleTextIn
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = WhaleSpacing.xs)
            .clip(RoundedCornerShape(WhaleRadius.bubbleSmall))
            .background(accent.copy(alpha = WhaleAlpha.Ghost))
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = WhaleSpacing.s, vertical = 6.dp),
        ) {
            Text(
                text = reply.author,
                style = WhaleTheme.type.messageName,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = reply.text,
                style = WhaleTheme.type.messageReply,
                color = body.copy(alpha = WhaleAlpha.Soft),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 图片 / 语音 / 文件
// ---------------------------------------------------------------------------

/**
 * 图片气泡：图片本身就是气泡（同组合并角，无尾巴、无内边距、无描边），外侧仍空出尾巴区和别的气泡对齐。
 * 宽 = min(300dp, 文字最大宽 + 31 − 8)；高按原图比例，限制在宽 × [0.5, 1.33]（自定），超出居中裁切。
 */
@Composable
private fun ImageBubble(
    message: ChatMessage,
    row: ChatRowModel.Msg,
    maxTextWidth: Dp,
    styles: ChatTextStyles,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: (Offset) -> Unit,
) {
    val colors = WhaleTheme.colors
    val outgoing = !message.fromHer
    val shape = remember(outgoing, row.firstInGroup, row.lastInGroup) {
        BubbleShape(
            outgoing = outgoing,
            topNear = !row.firstInGroup,
            bottomNear = !row.lastInGroup,
            tail = false,
            tailArea = false,
        )
    }
    val width = minOf(ImageMaxWidth, maxTextWidth + BubbleExtraWidth - TailGutter)
    val source = message.imageUris.firstOrNull().orEmpty()
    val bitmap = rememberChatImage(source)
    val ratio = bitmap?.let { it.height.toFloat() / it.width.coerceAtLeast(1) }
        ?.coerceIn(ImageMinRatio, ImageMaxRatio) ?: ImageDefaultRatio
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var downPosition by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .padding(start = if (outgoing) 0.dp else TailGutter, end = if (outgoing) TailGutter else 0.dp)
            .size(width = width, height = width * ratio)
            .bubbleBackground(shape, colors.mediaPlaceholder, colors.bubbleShadow)
            .clip(shape)
            .trackDown(message.id) { downPosition = it }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = { onLongPress(downPosition) },
            ),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            SyntheticChatImage(source = source, modifier = Modifier.fillMaxSize())
        }
        if (pressed || selected) {
            Box(Modifier.fillMaxSize().background(colors.scrim))
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .clip(WhaleShapes.iconTile)
                .background(colors.mediaTimeBg)
                .padding(horizontal = 6.dp, vertical = WhaleSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MessageMeta(
                time = message.time,
                status = message.status,
                showStatus = outgoing,
                color = colors.onAccent,
                checkColor = colors.onAccent,
                clockColor = colors.onAccent,
                styles = styles,
            )
        }
    }
}

/** 语音气泡（只有她发）：宽 = min(文字最大宽, 120dp + 秒数 × 6dp)，最小 180dp（自定）。 */
@Composable
private fun VoiceBubble(
    message: ChatMessage,
    row: ChatRowModel.Msg,
    maxTextWidth: Dp,
    playback: VoicePlayback,
    styles: ChatTextStyles,
    selected: Boolean,
    onPlay: () -> Unit,
    onLongPress: (Offset) -> Unit,
) {
    val colors = WhaleTheme.colors
    val outgoing = !message.fromHer
    val progress = playback.progressFor(message)
    val playing = playback.messageId == message.id && playback.isPlaying
    val iconProgress by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = tween(WhaleDuration.Fast, easing = TgEasing.Default),
        label = "playPause",
    )
    val remaining = if (playback.messageId == message.id && playback.positionMs > 0) {
        (((playback.durationMs - playback.positionMs).coerceAtLeast(0L)) / 1000L).toInt()
    } else {
        message.voiceSeconds ?: 0
    }
    val seconds = message.voiceSeconds ?: 0
    val contentWidth = (VoiceBaseWidth + VoicePerSecond * seconds)
        .coerceIn(VoiceMinWidth, maxOf(VoiceMinWidth, maxTextWidth))

    BubbleFrame(
        message = message,
        row = row,
        selected = selected,
        onLongPress = onLongPress,
        onClick = onPlay,
    ) { palette ->
        Column(Modifier.width(contentWidth)) {
            message.replyTo?.let {
                QuoteBlock(reply = it, outgoing = outgoing)
            }
            if (message.voiceLost) {
                // 语音文件读不出来（D5 / W-042）：显示转语音之前的那段文字，不再播内置示例音频
                message.voiceText?.takeIf { it.isNotBlank() }?.let { spoken ->
                    Text(text = spoken, style = styles.message, color = palette.text)
                    Spacer(Modifier.height(WhaleSpacing.xs))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "语音已丢失",
                        style = styles.duration,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                    MessageMeta(
                        time = message.time,
                        status = message.status,
                        showStatus = outgoing,
                        color = palette.time,
                        checkColor = palette.check,
                        clockColor = palette.clock,
                        styles = styles,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(VoiceButton)
                            .clip(CircleShape)
                            .background(if (outgoing) colors.voiceButtonOut else colors.voiceButtonIn)
                            .clickable(onClick = onPlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        PlayPauseGlyph(
                            modifier = Modifier.size(18.dp),
                            color = colors.onAccent,
                            progress = iconProgress,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        VoiceWaveform(
                            waveform = message.waveform,
                            progress = progress,
                            playedColor = if (outgoing) colors.waveOutPlayed else colors.waveInPlayed,
                            unplayedColor = if (outgoing) colors.waveOut else colors.waveIn,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(14.dp),
                        )
                        Spacer(Modifier.height(WhaleSpacing.xs))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatDuration(remaining),
                                style = styles.duration,
                                color = palette.time,
                                maxLines = 1,
                            )
                            MessageMeta(
                                time = message.time,
                                status = message.status,
                                showStatus = outgoing,
                                color = palette.time,
                                checkColor = palette.check,
                                clockColor = palette.clock,
                                styles = styles,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentBubble(
    message: ChatMessage,
    row: ChatRowModel.Msg,
    styles: ChatTextStyles,
    selected: Boolean,
    onLongPress: (Offset) -> Unit,
) {
    val colors = WhaleTheme.colors
    val outgoing = !message.fromHer
    BubbleFrame(
        message = message,
        row = row,
        selected = selected,
        onLongPress = onLongPress,
    ) { palette ->
        Column(Modifier.width(DocumentWidth)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(VoiceButton)
                        .clip(CircleShape)
                        .background(if (outgoing) colors.fileBgOut else colors.fileBgIn),
                    contentAlignment = Alignment.Center,
                ) {
                    WhaleIcon(WhaleIcons.File, tint = colors.onAccent, modifier = Modifier.size(WhaleSpacing.icon))
                }
                Spacer(Modifier.width(WhaleSpacing.m))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = message.documentName.orEmpty(),
                        style = WhaleTheme.type.fileName,
                        color = palette.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    message.documentSize?.let { size ->
                        Spacer(Modifier.height(WhaleSpacing.xxs))
                        Text(
                            text = formatFileSize(size),
                            style = WhaleTheme.type.fileInfo,
                            color = if (outgoing) colors.fileInfoOut else colors.fileInfoIn,
                        )
                    }
                }
            }
            // 附件和文字一起发时，文字跟在文件下面（F5：以前文字被文件气泡吞掉，看不到）
            message.text?.takeIf { it.isNotBlank() }?.let { caption ->
                Spacer(Modifier.height(WhaleSpacing.s))
                Text(text = caption, style = styles.message, color = palette.text)
            }
            Spacer(Modifier.height(WhaleSpacing.xs))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                MessageMeta(
                    time = message.time,
                    status = message.status,
                    showStatus = outgoing,
                    color = palette.time,
                    checkColor = palette.check,
                    clockColor = palette.clock,
                    styles = styles,
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}

// ---------------------------------------------------------------------------
// 波形
// ---------------------------------------------------------------------------

/** 语音波形：竖条宽 2dp、步距 3dp、圆头、总高 14dp（SeekBarWaveform）。 */
@Composable
private fun VoiceWaveform(
    waveform: List<Float>,
    progress: Float,
    playedColor: Color,
    unplayedColor: Color,
    modifier: Modifier = Modifier,
) {
    val samples = waveform.ifEmpty { List(32) { 0.25f } }
    androidx.compose.foundation.Canvas(modifier) {
        val barWidth = 2.dp.toPx()
        val step = 3.dp.toPx()
        val count = ((size.width + step - barWidth) / step).toInt().coerceAtLeast(1)
        val playedBars = (count * progress).roundToInt()

        repeat(count) { index ->
            val sampleIndex = (index.toLong() * samples.size / count)
                .toInt()
                .coerceIn(0, samples.lastIndex)
            val amplitude = samples[sampleIndex].coerceIn(0f, 1f)
            val barHeight = (size.height * (0.12f + 0.88f * amplitude))
                .coerceAtLeast(barWidth)
            val left = index * step
            drawLine(
                color = if (index < playedBars) playedColor else unplayedColor,
                start = Offset(left + barWidth / 2f, (size.height - barHeight) / 2f + barWidth / 2f),
                end = Offset(left + barWidth / 2f, (size.height + barHeight) / 2f - barWidth / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
