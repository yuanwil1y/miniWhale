package whaleui.nightsky.ltd.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.theme.WhaleMotion
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 弹出物（U0，02 §2.4 / §4.1 / §7）：对话框、底部弹层、弹出菜单。
// ---------------------------------------------------------------------------

/** 对话框按钮。 */
class WhaleDialogButton(
    val text: String,
    val onClick: () -> Unit,
    val danger: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * 对话框：r12、底 surfaceDialog、压暗 50%；标题 20 Medium、正文 16、左右 24；
 * 按钮在右下，40dp 胶囊 ripple、16 Medium 蓝，危险按钮红（AlertDialog.java）。
 * 进出用系统 Dialog 窗口动画（Telegram 同样没有自定义）。
 */
@Composable
fun WhaleDialog(
    onDismiss: () -> Unit,
    title: String? = null,
    text: String? = null,
    buttons: List<WhaleDialogButton> = emptyList(),
    dismissOnOutside: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = WhaleTheme.colors
    val type = WhaleTheme.type
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissOnOutside,
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = WhaleSpacing.xxl)
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .clip(WhaleShapes.dialog)
                .background(colors.surfaceDialog)
                .padding(top = WhaleSpacing.xl),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = type.dialogTitle,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = WhaleSpacing.dialogHorizontal),
                )
                Spacer(Modifier.height(WhaleSpacing.m))
            }
            if (text != null) {
                Text(
                    text = text,
                    style = type.dialogText,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = WhaleSpacing.dialogHorizontal),
                )
                Spacer(Modifier.height(WhaleSpacing.s))
            }
            if (content != null) {
                Column(Modifier.fillMaxWidth()) { content() }
            }
            if (buttons.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = WhaleSpacing.l, end = WhaleSpacing.l, top = WhaleSpacing.s, bottom = WhaleSpacing.s),
                    horizontalArrangement = Arrangement.End,
                ) {
                    buttons.forEach { button ->
                        WhaleButton(
                            text = button.text,
                            onClick = button.onClick,
                            style = WhaleButtonStyle.Text,
                            enabled = button.enabled,
                            textColor = if (button.danger) colors.danger else colors.accent,
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(WhaleSpacing.l))
            }
        }
    }
}

/** 底部弹层内容可用的操作：带动画关掉，然后执行 [then]（不传 = 当作用户取消，调 onDismiss）。 */
@Stable
class WhaleSheetScope internal constructor(private val close: ((() -> Unit)?) -> Unit) {
    fun dismiss(then: (() -> Unit)? = null) = close(then)
}

/**
 * 底部弹层（覆盖在当前页面上，不是独立窗口）：打开 400ms EaseOutQuint、关闭 180ms EaseOut；
 * 压暗 40%（specs README §4 裁定）；上圆角 16、底 surfaceDialog；顶部手柄下拉超过 96dp 或快甩关闭，否则回弹。
 * 内容超过 90% 屏高时滚动；跟随键盘上移。
 * [footer] 不为空时固定在弹层底部、不跟内容滚动（保存 / 取消这类按钮，键盘弹出时也不会被挤出去，u5 §4 #5）。
 */
@Composable
fun WhaleBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    contentPadding: Dp = WhaleSpacing.xl,
    footer: (@Composable WhaleSheetScope.() -> Unit)? = null,
    content: @Composable WhaleSheetScope.(ColumnScope) -> Unit,
) {
    val colors = WhaleTheme.colors
    val inspection = LocalInspectionMode.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val progress = remember { Animatable(if (inspection) 1f else 0f) }
    val drag = remember { Animatable(0f) }

    // then == null：用户取消（点遮罩 / 返回 / 下拉），关完调 onDismiss；否则关完只调 then（例如「保存」）
    val close: ((() -> Unit)?) -> Unit = { then ->
        if (!closing) {
            closing = true
            scope.launch {
                progress.animateTo(0f, WhaleMotion.sheetClose())
                (then ?: onDismiss)()
            }
        }
    }
    val sheetScope = remember { WhaleSheetScope(close) }
    LaunchedEffect(Unit) {
        delay(20) // BottomSheet.java：打开延迟 20ms
        progress.animateTo(1f, WhaleMotion.sheetOpen())
    }
    BackHandler { close(null) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        val sheetTravel = with(density) { maxHeight.toPx() } * 0.6f
        val maxContent = maxHeight * 0.9f - WhaleSpacing.xxl
        // 整个弹层（含固定底栏）不顶进状态栏：键盘弹出后可用高度变小时也一样
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value }
                .background(colors.scrimSheet)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { close(null) },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = (1f - progress.value) * sheetTravel + drag.value
                }
                .heightIn(max = maxHeight - statusTop - WhaleSpacing.s)
                .clip(WhaleShapes.sheet)
                .background(colors.surfaceDialog)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .navigationBarsPadding(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WhaleSpacing.xxl)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (drag.value > 96.dp.toPx()) {
                                    close(null)
                                } else {
                                    scope.launch { drag.animateTo(0f, tween(WhaleDuration.Medium, easing = TgEasing.Default)) }
                                }
                            },
                            onDragCancel = { scope.launch { drag.animateTo(0f) } },
                            onVerticalDrag = { _, delta ->
                                scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = WhaleSpacing.xs)
                        .clip(WhaleShapes.pill)
                        .background(colors.textCaption.copy(alpha = WhaleAlpha.Disabled)),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxContent)
                    // 有固定底栏时内容区让出它的高度，自己滚
                    .weight(1f, fill = false)
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(
                        start = contentPadding,
                        end = contentPadding,
                        bottom = if (footer != null) WhaleSpacing.s else contentPadding,
                    ),
            ) {
                sheetScope.content(this)
            }
            if (footer != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = contentPadding, end = contentPadding, top = WhaleSpacing.s, bottom = WhaleSpacing.l),
                ) {
                    sheetScope.footer()
                }
            }
        }
    }
}

/** 弹出菜单的一项。 */
class PopupMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * 弹出菜单（长按菜单 / ⋮ 菜单）：最小宽 200、r12、内边距 8、项高 48、项左右 18、文字 16、图标后文字缩进 43；
 * 底 surfaceMenuSolid（不透明：菜单背后没有模糊，85% 半透明会让底下的字透出来抢读，U0 截图核对后改）+ 上亮下暗细线；背后压暗 20%（320ms EaseOutQuint），[cutout] 区域不压暗（被长按的元素保持原样）。
 * 出现：整体 alpha 150ms + scale 0.95→1 200ms Overshoot，锚点右上；每项按 16ms×序号 依次 alpha + 上移 6dp，180ms Decelerate。
 * 关闭：150ms 淡出 + 缩到 0.95，然后执行点中的项。
 * 位置：右边缘 = [touch].x − 28dp（Telegram），顶 = [touch].y；越界夹回屏内，底部让出 [bottomInset]。
 * 给了 [anchor]（被长按元素的矩形）时改成贴着它弹（u2 §4.2）：[anchorEnd] = false 左缘对齐锚点左缘 + 8dp，
 * true 右缘对齐锚点右缘 − 8dp（让开气泡尾巴区）；优先放锚点下方 8dp，放不下放上方 8dp，都放不下放在触点处；
 * 缩放原点取离锚点最近的那个角。
 */
@Composable
fun PopupMenu(
    touch: Offset,
    items: List<PopupMenuItem>,
    onDismiss: () -> Unit,
    cutout: Rect? = null,
    bottomInset: Dp = WhaleSpacing.touchTarget,
    scrim: Boolean = true,
    anchor: Rect? = null,
    anchorEnd: Boolean = false,
) {
    val colors = WhaleTheme.colors
    val type = WhaleTheme.type
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible && !closing) 1f else 0f,
        animationSpec = if (closing) WhaleMotion.fade() else WhaleMotion.scrim(),
        label = "popupScrim",
    )
    val appear by animateFloatAsState(
        targetValue = if (visible && !closing) 1f else 0f,
        animationSpec = tween(
            if (closing) WhaleDuration.Fast else WhaleDuration.Toggle,
            easing = if (closing) TgEasing.EaseOut else TgEasing.overshoot(),
        ),
        label = "popupAppear",
    )
    LaunchedEffect(Unit) { visible = true }
    fun close(action: (() -> Unit)?) {
        if (closing) return
        pending = action
        closing = true
    }
    LaunchedEffect(closing) {
        if (closing) {
            delay(WhaleDuration.Fast.toLong())
            pending?.invoke()
            onDismiss()
        }
    }
    BackHandler { close(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { WhaleSpacing.menuMinWidth.toPx() }
        val heightPx = with(density) { (WhaleSpacing.menuItemHeight * items.size + WhaleSpacing.menuPadding * 2).toPx() }
        val margin = with(density) { WhaleSpacing.l.toPx() }
        val top = with(density) { WhaleSpacing.xxl.toPx() }
        val bottom = with(density) { bottomInset.toPx() }
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val gap = with(density) { WhaleSpacing.s.toPx() }
        val edge = with(density) { WhaleSpacing.s.toPx() }
        var originX = 1f
        var originY = 0f
        val x: Float
        val y: Float
        if (anchor == null) {
            x = (touch.x - widthPx - with(density) { 28.dp.toPx() }).coerceIn(margin, maxOf(margin, maxW - widthPx - margin))
            y = touch.y.coerceIn(top, maxOf(top, maxH - heightPx - bottom))
        } else {
            originX = if (anchorEnd) 1f else 0f
            val rawX = if (anchorEnd) anchor.right - gap - widthPx else anchor.left + gap
            x = rawX.coerceIn(edge, maxOf(edge, maxW - widthPx - edge))
            val below = anchor.bottom + gap
            val above = anchor.top - gap - heightPx
            y = when {
                below + heightPx <= maxH - bottom -> below
                above >= top -> above.also { originY = 1f }
                else -> touch.y.coerceIn(top, maxOf(top, maxH - heightPx - bottom))
            }
        }
        val dim = colors.scrim.copy(alpha = colors.scrim.alpha * scrimAlpha)
        val dismissClick = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { close(null) }

        if (scrim && cutout != null) {
            // 四块拼出「挖掉 cutout」的遮罩
            val l = cutout.left.coerceIn(0f, maxW)
            val t = cutout.top.coerceIn(0f, maxH)
            val r = cutout.right.coerceIn(0f, maxW)
            val b = cutout.bottom.coerceIn(0f, maxH)
            with(density) {
                Box(Modifier.fillMaxWidth().height(t.toDp()).background(dim).then(dismissClick))
                Box(Modifier.offset { IntOffset(0, b.roundToInt()) }.fillMaxWidth().height((maxH - b).coerceAtLeast(0f).toDp()).background(dim).then(dismissClick))
                Box(Modifier.offset { IntOffset(0, t.roundToInt()) }.width(l.toDp()).height((b - t).coerceAtLeast(0f).toDp()).background(dim).then(dismissClick))
                Box(Modifier.offset { IntOffset(r.roundToInt(), t.roundToInt()) }.width((maxW - r).coerceAtLeast(0f).toDp()).height((b - t).coerceAtLeast(0f).toDp()).background(dim).then(dismissClick))
            }
        } else {
            Box(Modifier.fillMaxSize().background(if (scrim) dim else Color.Transparent).then(dismissClick))
        }

        Column(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(WhaleSpacing.menuMinWidth)
                .graphicsLayer {
                    alpha = appear.coerceIn(0f, 1f)
                    scaleX = 0.95f + 0.05f * appear
                    scaleY = 0.95f + 0.05f * appear
                    transformOrigin = TransformOrigin(originX, originY)
                }
                .clip(WhaleShapes.menu)
                .background(colors.surfaceMenuSolid)
                .border(0.5.dp, colors.glassStrokeBottom, WhaleShapes.menu)
                .padding(vertical = WhaleSpacing.menuPadding),
        ) {
            items.forEachIndexed { index, item ->
                PopupMenuRow(item = item, index = index, visible = visible, onClick = { close(item.onClick) })
            }
        }
    }
}

@Composable
private fun PopupMenuRow(item: PopupMenuItem, index: Int, visible: Boolean, onClick: () -> Unit) {
    val colors = WhaleTheme.colors
    val density = LocalDensity.current
    val appear = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(WhaleMotion.menuItemDelayMs(index).toLong())
            appear.animateTo(1f, WhaleMotion.menuItem())
        }
    }
    val tint = when {
        item.danger -> colors.danger
        else -> colors.textPrimary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(WhaleSpacing.menuItemHeight)
            .clickable(enabled = item.enabled, onClick = onClick)
            .graphicsLayer {
                alpha = appear.value * if (item.enabled) 1f else WhaleAlpha.Disabled
                translationY = (1f - appear.value) * with(density) { 6.dp.toPx() }
            }
            .padding(horizontal = WhaleSpacing.menuItemHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.icon != null) {
            Icon(item.icon, contentDescription = null, tint = if (item.danger) colors.danger else colors.icon, modifier = Modifier.size(WhaleSpacing.icon))
            Spacer(Modifier.width(43.dp - WhaleSpacing.icon))
        }
        Text(text = item.label, style = WhaleTheme.type.menuItem, color = tint, maxLines = 1)
    }
}
