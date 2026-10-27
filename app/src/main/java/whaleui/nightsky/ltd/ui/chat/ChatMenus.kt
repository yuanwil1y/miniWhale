package whaleui.nightsky.ltd.ui.chat

import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.components.PopupMenuItem
import whaleui.nightsky.ltd.ui.components.PopupMenu
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import whaleui.nightsky.ltd.domain.chat.ChatMessage

internal data class MessageMenuState(
    val message: ChatMessage,
    val anchor: Rect,
    val touch: Offset,
)

/**
 * 长按消息的菜单 = 组件库 [PopupMenu] 的锚点版（A，u2 §4）：贴着被长按的气泡弹出——她的靠左、我的靠右，
 * 优先在气泡下方，放不下翻到上方；背后整屏压暗 20%（不挖开气泡，§5 定稿）。
 * 菜单项顺序固定：回复 → 复制 → 删除，不适用的项不显示（没有文字就没有「复制」）；表情消息只有「删除」。
 */
@Composable
internal fun MessageActionMenu(
    state: MessageMenuState,
    bottomInset: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
) {
    val stickerOnly = state.message.isSticker
    val items = buildList {
        if (!stickerOnly) {
            add(PopupMenuItem("回复", WhaleIcons.Reply, onClick = onReply))
            if (!state.message.text.isNullOrBlank()) {
                add(PopupMenuItem("复制", WhaleIcons.Copy, onClick = onCopy))
            }
        }
        add(PopupMenuItem("删除", WhaleIcons.Trash, danger = true, onClick = onDelete))
    }
    PopupMenu(
        touch = state.touch,
        items = items,
        onDismiss = onDismiss,
        bottomInset = bottomInset,
        anchor = state.anchor,
        anchorEnd = !state.message.fromHer,
    )
}


@Composable
internal fun ImageViewerOverlay(
    open: Boolean,
    source: String?,
    onDismiss: () -> Unit,
) {
    var visible by remember(open) { mutableStateOf(open) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    LaunchedEffect(open) {
        if (open) {
            visible = true
            dragY = 0f
            scale = 1f
            offset = Offset.Zero
        } else {
            visible = false
        }
    }

    val view = LocalView.current
    DisposableEffect(open) {
        val activity = view.context.findActivity()
        val window = activity?.window
        if (open && window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            if (window != null) {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    if (source != null) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(WhaleDuration.Toggle)) + scaleIn(
                initialScale = 0.9f,
                animationSpec = tween(WhaleDuration.Toggle),
            ),
            exit = fadeOut(tween(WhaleDuration.Toggle)) + scaleOut(
                targetScale = 0.9f,
                animationSpec = tween(WhaleDuration.Toggle),
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
        BoxWithConstraints(
            Modifier.fillMaxSize(),
        ) {
            val viewHeight = constraints.maxHeight.toFloat()
            val backgroundAlpha = (1f - (abs(dragY) / (viewHeight * 1.4f)))
                .coerceIn(0.5f, 1f)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = backgroundAlpha)),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = offset.x
                        translationY = offset.y + dragY
                        scaleX = scale
                        scaleY = scale
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2.5f
                                offset = Offset.Zero
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var zoomed = false
                            var keepGoing = true
                            while (keepGoing) {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                if (pressedCount >= 2) {
                                    zoomed = true
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    scale = (scale * zoom).coerceIn(1f, 4f)
                                    offset += pan
                                    event.changes.forEach { if (it.pressed) it.consume() }
                                } else {
                                    val change = event.changes.firstOrNull()
                                    if (change != null) {
                                        val pan = event.calculatePan()
                                        if (scale <= 1.01f) {
                                            dragY += pan.y
                                        } else {
                                            offset += pan
                                        }
                                        if (abs(pan.x) > 0.4f || abs(pan.y) > 0.4f) {
                                            change.consume()
                                        }
                                    }
                                }
                                keepGoing = event.changes.any { it.pressed }
                            }
                            if (!zoomed) {
                                if (abs(dragY) > viewHeight / 6f) {
                                    onDismiss()
                                } else {
                                    scope.launch {
                                        val start = dragY
                                        val frames = 16
                                        repeat(frames) { index ->
                                            dragY = start * (1f - (index + 1f) / frames)
                                            delay(16)
                                        }
                                        dragY = 0f
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f),
                ) {
                    ChatImageContent(
                        source = source,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 12.dp)
                    .size(44.dp)
                    .clip(WhaleShapes.circle)
                    .background(Color.Black.copy(alpha = WhaleAlpha.Disabled))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                WhaleIcon(WhaleIcons.Close, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
