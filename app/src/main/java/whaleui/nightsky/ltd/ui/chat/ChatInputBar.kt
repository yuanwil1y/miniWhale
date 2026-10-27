package whaleui.nightsky.ltd.ui.chat

import whaleui.nightsky.ltd.ui.components.LocalHerProfile
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import kotlin.math.roundToInt
import whaleui.nightsky.ltd.domain.chat.PendingAttachment
import whaleui.nightsky.ltd.domain.chat.ReplyPreview

/**
 * 输入栏（A，u2 §6）：一个玻璃胶囊里放「表情 / 键盘」钮、输入框、附件钮；有字或有附件时胶囊右边长出 44dp 发送圆钮。
 * 表情面板整宽贴底、不悬浮，打开时占在键盘的位置。附件选择是外面的底部弹层（[onAttachClick]）。
 */
@Composable
internal fun ChatInputBar(
    draft: String,
    draftAlpha: Float,
    sendBlocked: Boolean,
    attachments: List<PendingAttachment>,
    replyTo: ReplyPreview?,
    onDraftChange: (String) -> Unit,
    onSend: (Rect) -> Unit,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onCancelReply: () -> Unit,
    onHeightChanged: (Int) -> Unit,
    onTopChanged: (Int) -> Unit,
    hazeState: HazeState,
    inspection: Boolean,
    styles: ChatTextStyles,
    modifier: Modifier = Modifier,
    // ---- 表情面板（round5 3.5）----
    stickerPanelOpen: Boolean = false,
    onToggleStickerPanel: () -> Unit = {},
    onInputFocused: () -> Unit = {},
    stickerPacks: List<StickerPanelPack> = emptyList(),
    onSendSticker: (StickerPanelItem) -> Unit = {},
    onOpenStickerSettings: () -> Unit = {},
    /** 递增一次 = 请求输入框拿焦点并弹键盘（面板切回键盘用）。 */
    keyboardRequestToken: Int = 0,
) {
    val colors = WhaleTheme.colors
    val glassStyle = rememberGlassStyle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var pillRect by remember { mutableStateOf(Rect.Zero) }
    var removing by remember { mutableStateOf(emptySet<String>()) }
    val canSend = (draft.isNotBlank() || attachments.isNotEmpty()) && !sendBlocked
    // 发送钮不在时胶囊占满；出现 / 消失时胶囊宽度跟着动，不跳（修 聊天-14）
    val sendSlotWidth by animateDpAsState(
        targetValue = if (canSend) WhaleSpacing.inputBarHeight + 6.dp else 0.dp,
        animationSpec = tween(WhaleDuration.Fast, easing = TgEasing.Default),
        label = "sendSlotWidth",
    )

    LaunchedEffect(replyTo?.messageId) {
        if (replyTo != null) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    // 面板切回键盘（round5 3.5）：输入框拿焦点，键盘会自己弹出来
    LaunchedEffect(keyboardRequestToken) {
        if (keyboardRequestToken > 0) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { onTopChanged(it.positionInRoot().y.roundToInt()) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 面板开着时导航栏让给面板（面板底色一直铺到屏幕底）
                .then(
                    if (stickerPanelOpen) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
                )
                .padding(start = WhaleSpacing.s, end = WhaleSpacing.s, bottom = WhaleSpacing.s)
                .animateContentSize(
                    animationSpec = tween(WhaleDuration.Fast, easing = TgEasing.Default),
                )
                .onSizeChanged { onHeightChanged(it.height) },
        ) {
            AnimatedVisibility(
                visible = replyTo != null,
                enter = expandVertically(tween(WhaleDuration.Fast, easing = TgEasing.Default)) +
                    fadeIn(tween(WhaleDuration.Fast)),
                exit = shrinkVertically(tween(WhaleDuration.Fast, easing = TgEasing.Default)) +
                    fadeOut(tween(WhaleDuration.Dismiss)),
            ) {
                replyTo?.let {
                    ReplyPreviewBar(
                        reply = it,
                        onCancel = onCancelReply,
                        hazeState = hazeState,
                        inspection = inspection,
                    )
                }
            }

            AnimatedVisibility(
                visible = attachments.isNotEmpty(),
                enter = expandVertically(tween(WhaleDuration.Fast, easing = TgEasing.EaseOut)) +
                    fadeIn(tween(WhaleDuration.Fast)),
                exit = shrinkVertically(tween(WhaleDuration.Fast)) + fadeOut(tween(WhaleDuration.Dismiss)),
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .padding(bottom = WhaleSpacing.s),
                    horizontalArrangement = Arrangement.spacedBy(WhaleSpacing.s),
                ) {
                    items(attachments, key = { it.id }) { attachment ->
                        val isRemoving = attachment.id in removing
                        AnimatedVisibility(
                            visible = !isRemoving,
                            exit = fadeOut(tween(WhaleDuration.Dismiss)) + scaleOut(tween(WhaleDuration.Fast), targetScale = 0.8f),
                            modifier = Modifier.animateItem(),
                        ) {
                            PendingThumbnail(
                                attachment = attachment,
                                onRemove = {
                                    removing = removing + attachment.id
                                },
                            )
                        }
                        if (isRemoving) {
                            LaunchedEffect(attachment.id) {
                                delay(WhaleDuration.Fast.toLong())
                                onRemoveAttachment(attachment.id)
                                removing = removing - attachment.id
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = WhaleSpacing.inputBarHeight)
                        .glass(
                            shape = WhaleShapes.pill,
                            state = hazeState,
                            style = glassStyle,
                            inspection = inspection,
                            fallback = colors.surfaceMenu,
                            stroke = colors.glassStrokeTop,
                        )
                        .onGloballyPositioned { pillRect = it.boundsInRoot() },
                    verticalAlignment = Alignment.Bottom,
                ) {
                    // 表情按钮（round5 3.5）：面板打开时变成键盘图标，点一下切回键盘
                    StickerToggleButton(
                        panelOpen = stickerPanelOpen,
                        onClick = onToggleStickerPanel,
                    )
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onFocusChanged { focus ->
                                // 点输入框 → 表情面板收起（round5 3.5）
                                if (focus.isFocused && stickerPanelOpen) onInputFocused()
                            }
                            .padding(start = WhaleSpacing.xs, top = 11.dp, bottom = 11.dp),
                        textStyle = styles.input.copy(
                            color = colors.textPrimary.copy(alpha = draftAlpha),
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        keyboardActions = KeyboardActions(),
                        decorationBox = { innerTextField ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                // 占位字不参与量高（matchParentSize）：高度只由真正的输入框决定，
                                // 否则空着时胶囊被占位字撑高，两侧图标不居中（修 A 截图核对）
                                if (draft.isEmpty()) {
                                    Text(
                                        text = "说点什么",
                                        style = styles.input,
                                        color = colors.textHint,
                                        maxLines = 1,
                                        modifier = Modifier.matchParentSize(),
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    InputIconButton(
                        icon = WhaleIcons.Paperclip,
                        description = "添加附件",
                        onClick = onAttachClick,
                    )
                }

                Box(
                    modifier = Modifier
                        .width(sendSlotWidth)
                        .height(WhaleSpacing.inputBarHeight),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    SendButtonSlot(
                        canSend = canSend,
                        onClick = { onSend(pillRect) },
                    )
                }
            }
        }

        // 表情面板：整宽贴底，占在键盘的位置（u2 §6.4）
        AnimatedVisibility(
            visible = stickerPanelOpen,
            enter = expandVertically(tween(WhaleDuration.Medium, easing = TgEasing.ChatList)),
            exit = shrinkVertically(tween(WhaleDuration.Medium, easing = TgEasing.ChatList)),
        ) {
            StickerPanel(
                packs = stickerPacks,
                onSend = onSendSticker,
                onOpenSettings = onOpenStickerSettings,
                modifier = Modifier
                    .background(colors.emojiPanelBg)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            )
        }
    }
}

/** 胶囊里的 44dp 图标钮：图标 26dp `glassIcon`，圆形按压（触控区仍有 44dp 高，胶囊外围补足到 48）。 */
@Composable
internal fun InputIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val colors = WhaleTheme.colors
    Box(
        modifier = Modifier
            .size(WhaleSpacing.inputBarHeight)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        WhaleIcon(icon, tint = colors.glassIcon, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun SendButtonSlot(canSend: Boolean, onClick: () -> Unit) {
    val colors = WhaleTheme.colors
    AnimatedVisibility(
        visible = canSend,
        enter = fadeIn(
            tween(WhaleDuration.Send, easing = TgEasing.EaseOutQuint),
        ) + scaleIn(
            initialScale = 0f,
            animationSpec = tween(
                WhaleDuration.Send,
                easing = TgEasing.EaseOutQuint,
            ),
        ),
        exit = fadeOut(tween(WhaleDuration.Fast)) + scaleOut(
            targetScale = 0f,
            animationSpec = tween(WhaleDuration.Fast, easing = TgEasing.EaseOut),
        ),
    ) {
        Box(
            modifier = Modifier
                .size(WhaleSpacing.inputBarHeight)
                .clip(CircleShape)
                .background(colors.accentStrong)
                .clickable(onClick = onClick)
                .semantics { contentDescription = "发送" },
            contentAlignment = Alignment.Center,
        ) {
            WhaleIcon(WhaleIcons.Send, tint = colors.onAccent, modifier = Modifier.size(WhaleSpacing.icon))
        }
    }
}

/** 回复条（u2 §6.2）：玻璃 r12、高 48，左回复图标，「回复 <她的昵称> / 回复 你」+ 摘要，右边 ✕。 */
@Composable
private fun ReplyPreviewBar(
    reply: ReplyPreview,
    onCancel: () -> Unit,
    hazeState: HazeState,
    inspection: Boolean,
) {
    val colors = WhaleTheme.colors
    val herName = LocalHerProfile.current.nickname
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = WhaleSpacing.s)
            .glass(
                shape = WhaleShapes.menu,
                state = hazeState,
                style = rememberGlassStyle(),
                inspection = inspection,
                fallback = colors.surfaceMenu,
                stroke = colors.glassStrokeTop,
            )
            .heightIn(min = WhaleSpacing.touchTarget)
            .padding(start = WhaleSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WhaleIcon(WhaleIcons.Reply, tint = colors.accent, modifier = Modifier.size(WhaleSpacing.icon))
        Spacer(Modifier.width(WhaleSpacing.m))
        Column(Modifier.weight(1f)) {
            Text(
                text = "回复 " + if (reply.author == "我") "你" else herName,
                style = WhaleTheme.type.messageName,
                color = colors.accent,
                maxLines = 1,
            )
            Text(
                text = reply.text,
                style = WhaleTheme.type.messageReply,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(WhaleSpacing.touchTarget)
                .clickable(onClick = onCancel)
                .semantics { contentDescription = "取消回复" },
            contentAlignment = Alignment.Center,
        ) {
            WhaleIcon(WhaleIcons.Close, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PendingThumbnail(
    attachment: PendingAttachment,
    onRemove: () -> Unit,
) {
    val colors = WhaleTheme.colors
    Box(Modifier.size(72.dp)) {
        if (attachment.isImage) {
            ChatImageContent(
                source = attachment.uri,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(WhaleShapes.thumbnail),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(WhaleShapes.thumbnail)
                    .background(colors.fileBgOut),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WhaleIcon(WhaleIcons.File, tint = colors.onAccent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(WhaleSpacing.xs))
                    Text(
                        text = attachment.name,
                        style = WhaleTheme.type.small,
                        color = colors.onAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = WhaleSpacing.xs),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(44.dp)
                .clickable(onClick = onRemove)
                .semantics { contentDescription = "移除这个附件" },
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                modifier = Modifier
                    .padding(WhaleSpacing.xs)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceBar),
                contentAlignment = Alignment.Center,
            ) {
                WhaleIcon(WhaleIcons.Close, tint = colors.onAccent, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** 键盘 / 表情图标切换：150ms 交叉缩放 0.5↔1。 */
@Composable
internal fun StickerToggleButton(
    panelOpen: Boolean,
    onClick: () -> Unit,
) {
    val colors = WhaleTheme.colors
    Box(
        modifier = Modifier
            .size(WhaleSpacing.inputBarHeight)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (panelOpen) "键盘" else "表情" },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = panelOpen,
            transitionSpec = {
                (fadeIn(tween(WhaleDuration.Fast)) + scaleIn(tween(WhaleDuration.Fast), initialScale = 0.5f)) togetherWith
                    (fadeOut(tween(WhaleDuration.Fast)) + scaleOut(tween(WhaleDuration.Fast), targetScale = 0.5f))
            },
            label = "stickerToggle",
        ) { open ->
            WhaleIcon(
                if (open) WhaleIcons.Keyboard else WhaleIcons.Smile,
                tint = colors.glassIcon,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
