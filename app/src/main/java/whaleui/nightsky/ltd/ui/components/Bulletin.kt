package whaleui.nightsky.ltd.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.theme.WhaleMotion
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import kotlin.math.abs

// ---------------------------------------------------------------------------
// Bulletin（U0，02 §2.4 / §4.1 / §6.5；specs README §2.4）
// 替代系统 Toast 和原来的 4 套撤销条：屏幕底部、外边距 16/8、r16、底 undo_background；
// 弹簧进出（0.8 / 400）；按压缩到 0.98；左右滑走；停留时长按文字长度（<20 字 1.5s、否则 2.75s、带按钮 5s）。
// 同时只显示一条，新的顶掉旧的。
// ---------------------------------------------------------------------------

enum class BulletinKind { Info, Success, Error }

@Stable
class BulletinData(
    val text: String,
    val kind: BulletinKind = BulletinKind.Info,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    /** 自然消失（超时或滑走）时回调，撤销类用它「落库」。点了按钮不调。 */
    val onTimeout: (() -> Unit)? = null,
    durationMs: Long? = null,
    val icon: ImageVector? = if (kind == BulletinKind.Error) WhaleIcons.Alert else null,
) {
    val durationMs: Long = durationMs ?: when {
        actionLabel != null -> WhaleDuration.BulletinUndo
        text.length < 20 -> WhaleDuration.BulletinShort
        else -> WhaleDuration.BulletinLong
    }
    internal val id: Long = nextId++

    private companion object {
        var nextId = 0L
    }
}

/** 页面持有的 Bulletin 状态；一般用 [LocalBulletins] 拿全局那一个。 */
@Stable
class BulletinState {
    var current: BulletinData? by mutableStateOf(null)
        private set

    /** 底部被占用的高度（输入栏、FAB、底部按钮），Bulletin 放在它上方 8dp。页面用 [BulletinObstruction] 设。 */
    val bottomObstruction: Dp get() = obstructions.lastOrNull()?.height ?: 0.dp

    /** 按登记顺序排；最后登记（最上层页面）的那个生效。 */
    internal val obstructions = mutableStateListOf<Obstruction>()

    internal class Obstruction(height: Dp) {
        var height: Dp by mutableStateOf(height)
    }

    fun show(data: BulletinData) {
        val previous = current
        current = data
        // 被顶掉的撤销条：等同于超时（删除照常生效）
        if (previous != null && previous.id != data.id) previous.onTimeout?.invoke()
    }

    fun show(
        text: String,
        kind: BulletinKind = BulletinKind.Info,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        onTimeout: (() -> Unit)? = null,
    ) = show(BulletinData(text, kind, actionLabel, onAction, onTimeout))

    internal fun dismiss(data: BulletinData, timedOut: Boolean) {
        if (current?.id != data.id) return
        current = null
        if (timedOut) data.onTimeout?.invoke()
    }
}

/**
 * ViewModel / 数据层发 Bulletin 的通道（它们拿不到 Composition）。[BulletinHost] 收集并显示。
 */
object BulletinBus {
    private val _events = MutableSharedFlow<BulletinData>(extraBufferCapacity = 8)
    val events: SharedFlow<BulletinData> = _events.asSharedFlow()

    fun post(text: String, kind: BulletinKind = BulletinKind.Info) {
        _events.tryEmit(BulletinData(text, kind))
    }

    fun post(data: BulletinData) {
        _events.tryEmit(data)
    }
}

val LocalBulletins = staticCompositionLocalOf { BulletinState() }

/** 声明当前页面底部被占用的高度，离开页面时复原。 */
@Composable
fun BulletinObstruction(height: Dp) {
    val state = LocalBulletins.current
    val entry = remember(state) { BulletinState.Obstruction(height) }
    SideEffect { entry.height = height }
    DisposableEffect(state, entry) {
        state.obstructions.add(entry)
        onDispose { state.obstructions.remove(entry) }
    }
}

/** 放在根布局最上层（BoxScope 里），全 App 只有一个。 */
@Composable
fun BoxScope.BulletinHost(state: BulletinState, modifier: Modifier = Modifier) {
    LaunchedEffect(state) {
        BulletinBus.events.collect { state.show(it) }
    }
    val data = state.current ?: return
    // key：新的一条重新走一遍进场
    androidx.compose.runtime.key(data.id) {
        BulletinItem(
            data = data,
            onDismiss = { timedOut -> state.dismiss(data, timedOut) },
            modifier = modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = state.bottomObstruction),
        )
    }
}

@Composable
private fun BulletinItem(
    data: BulletinData,
    onDismiss: (timedOut: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    val type = WhaleTheme.type
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var heightPx by remember { mutableStateOf(with(density) { 120.dp.toPx() }) }
    val offsetY = remember { Animatable(heightPx) }
    val offsetX = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }

    fun leave(timedOut: Boolean) {
        if (leaving) return
        leaving = true
        scope.launch {
            offsetY.animateTo(heightPx, WhaleMotion.bulletin())
            onDismiss(timedOut)
        }
    }

    LaunchedEffect(Unit) {
        offsetY.animateTo(0f, WhaleMotion.bulletin())
    }
    LaunchedEffect(Unit) {
        delay(data.durationMs)
        leave(timedOut = true)
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) WhaleAlpha.PressedScale else 1f,
        animationSpec = WhaleMotion.bulletin(),
        label = "bulletinPress",
    )
    val swipeThreshold = with(density) { 120.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = WhaleSpacing.bulletinHorizontal, vertical = WhaleSpacing.bulletinVertical)
            .onSizeChanged { heightPx = it.height.toFloat() + with(density) { 24.dp.toPx() } }
            .graphicsLayer {
                translationY = offsetY.value
                translationX = offsetX.value
                alpha = (1f - abs(offsetX.value) / (swipeThreshold * 2)).coerceIn(0f, 1f)
                scaleX = pressScale
                scaleY = pressScale
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> scope.launch { offsetX.snapTo(offsetX.value + delta) } },
                onDragStopped = {
                    if (abs(offsetX.value) > swipeThreshold / 2) {
                        scope.launch {
                            offsetX.animateTo(
                                if (offsetX.value > 0) swipeThreshold * 3 else -swipeThreshold * 3,
                                androidx.compose.animation.core.tween(WhaleDuration.Toggle),
                            )
                            if (!leaving) {
                                leaving = true
                                onDismiss(true)
                            }
                        }
                    } else {
                        scope.launch { offsetX.animateTo(0f, WhaleMotion.bulletin()) }
                    }
                },
            )
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(min = WhaleSpacing.bulletinMinHeight)
                .clip(WhaleShapes.bulletin)
                .background(colors.bulletinBg)
                .border(0.5.dp, colors.glassStrokeBottom, WhaleShapes.bulletin)
                .clickable(interactionSource = interaction, indication = null) {}
                .padding(start = WhaleSpacing.l, end = if (data.actionLabel != null) WhaleSpacing.xs else WhaleSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (data.icon != null) {
                Icon(
                    imageVector = data.icon,
                    contentDescription = null,
                    tint = if (data.kind == BulletinKind.Error) colors.danger else colors.bulletinText,
                    modifier = Modifier.size(WhaleSpacing.icon),
                )
                Spacer(Modifier.width(WhaleSpacing.m))
            }
            Text(
                text = data.text,
                style = type.bulletin,
                color = colors.bulletinText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = WhaleSpacing.m),
            )
            val label = data.actionLabel
            if (label != null) {
                Box(
                    modifier = Modifier
                        .heightIn(min = WhaleSpacing.touchTarget)
                        .clip(WhaleShapes.pill)
                        .clickable {
                            data.onAction?.invoke()
                            leave(timedOut = false)
                        }
                        .padding(horizontal = WhaleSpacing.m),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = label, style = type.bulletinAction, color = colors.bulletinAction)
                }
            }
        }
    }
}

/**
 * 「已删除 xx · 撤销」：[key] 从 null 变成非 null 时发一条 5 秒的撤销 Bulletin。
 * 点撤销 → [onUndo]；自然消失 / 被新 Bulletin 顶掉 → [onExpired]（删除照常生效）。
 */
@Composable
fun UndoBulletin(
    key: Any?,
    text: String,
    onUndo: () -> Unit,
    onExpired: () -> Unit,
) {
    val bulletins = LocalBulletins.current
    val undo by androidx.compose.runtime.rememberUpdatedState(onUndo)
    val expired by androidx.compose.runtime.rememberUpdatedState(onExpired)
    LaunchedEffect(key) {
        if (key != null) {
            bulletins.show(
                BulletinData(
                    text = text,
                    actionLabel = "撤销",
                    onAction = { undo() },
                    onTimeout = { expired() },
                ),
            )
        }
    }
}
