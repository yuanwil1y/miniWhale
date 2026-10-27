package whaleui.nightsky.ltd.ui.settings

import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.components.WhaleSwitch
import whaleui.nightsky.ltd.ui.components.WhaleDialogButton
import whaleui.nightsky.ltd.ui.components.WhaleDialog
import whaleui.nightsky.ltd.ui.components.WhaleButtonStyle
import whaleui.nightsky.ltd.ui.components.WhaleButton
import whaleui.nightsky.ltd.ui.components.WhaleBottomSheet
import whaleui.nightsky.ltd.ui.components.LocalBulletins
import whaleui.nightsky.ltd.ui.components.BulletinKind
import whaleui.nightsky.ltd.ui.components.BulletinData
import androidx.compose.runtime.rememberUpdatedState
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 设置页里所有"从已经验收的页面搬过来"的动效小件。
//
// 数值来源：
// - 开关：定时任务页 / Telegram Switch —— 200ms、默认 AccelerateDecelerate、
//   颜色随 progress 插值；轨道 46×30、滑块 23dp。
// - 时间滚轮：定时任务页 / Telegram NumberPicker —— 5 行 × 42dp = 210dp、
//   pitch 46dp、透视 scaleX=0.8+0.2p / scaleY=p / alpha、分隔线 2dp（Y=82/128dp）、
//   渐变遮罩 96dp、松手 300ms 吸附。
// - 底部弹层 / 撤销提示 / 确认对话框：记忆与日记页、定时任务页、抽屉页已做好的那套。
// ---------------------------------------------------------------------------














internal val SheetHandleHeight = 32.dp
internal val SheetRadius = WhaleShapes.sheet


// ---------------------------------------------------------------------------
// 文本样式（全部用系统默认无衬线 FontFamily.Default，与聊天页一致）
//
// Telegram 设置页的字号：单元格标题 16sp / 400，副标题 14sp（新版本 13sp），
// 分组标题 15sp / 700 强调色，说明小字 14sp 次级色。这里统一取 16 / 13 / 15 / 13，
// 行高按 1.35 倍，和聊天页正文同一节奏。
// ---------------------------------------------------------------------------

/**
 * 设置 / 权限 / 高级 / 表情包管理页用到的字体：全部是 [WhaleTheme.type] 的别名（U0），字号字重以 WhaleType 为准。
 */
internal object SettingsType {
    val pageTitle: TextStyle @Composable get() = WhaleTheme.type.pageTitle
    val header: TextStyle @Composable get() = WhaleTheme.type.header
    val cellTitle: TextStyle @Composable get() = WhaleTheme.type.cellTitle
    val cellSubtitle: TextStyle @Composable get() = WhaleTheme.type.cellSubtitle
    val cellValue: TextStyle @Composable get() = WhaleTheme.type.cellTitle
    val info: TextStyle @Composable get() = WhaleTheme.type.footnote
    val fieldText: TextStyle @Composable get() = WhaleTheme.type.cellTitle
    val fieldLabel: TextStyle @Composable get() = WhaleTheme.type.small
    val button: TextStyle @Composable get() = WhaleTheme.type.button
    val toastInfo: TextStyle @Composable get() = WhaleTheme.type.bulletin
    val toastAction: TextStyle @Composable get() = WhaleTheme.type.bulletinAction
    val wheel: TextStyle @Composable get() = WhaleTheme.type.wheel
    val sheetTitle: TextStyle @Composable get() = WhaleTheme.type.dialogTitle
    val listItemTitle: TextStyle @Composable get() = WhaleTheme.type.cellTitle
    val dialogTitle: TextStyle @Composable get() = WhaleTheme.type.dialogTitle
    val dialogBody: TextStyle @Composable get() = WhaleTheme.type.dialogText
    val preview: TextStyle @Composable get() = WhaleTheme.type.footnote
}

// ---------------------------------------------------------------------------
// 开关
// ---------------------------------------------------------------------------

/** 设置页开关 = 组件库 [WhaleSwitch]（U0）。 */
@Composable
internal fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    WhaleSwitch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
}

// ---------------------------------------------------------------------------
// 底部弹层骨架（沿用定时任务页编辑弹层的进入 / 退出 / 下拉关闭）
// ---------------------------------------------------------------------------

/** 设置页底部弹层 = 组件库 [WhaleBottomSheet]（U0）。 */
@Composable
internal fun SettingsSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    WhaleBottomSheet(onDismiss = onDismiss, modifier = modifier) { column -> with(column) { content() } }
}

// ---------------------------------------------------------------------------
// 顶部提示条（撤销 / 导出结果），沿用定时任务页的玻璃条
// ---------------------------------------------------------------------------

/**
 * 设置 / 权限 / 高级页的提示 = 全局 [Bulletin]（U0：替换原来的顶部玻璃条）。
 * 每条 [SettingsToast] 只发一次；Bulletin 自然消失时回调 [onExpired]，点了按钮回调 [onAction]。
 */
@Composable
internal fun SettingsToastBar(
    toast: SettingsToast,
    onAction: () -> Unit,
    onExpired: () -> Unit,
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
) {
    val bulletins = LocalBulletins.current
    val latestAction by rememberUpdatedState(onAction)
    val latestExpired by rememberUpdatedState(onExpired)
    LaunchedEffect(toast.id) {
        bulletins.show(
            BulletinData(
                text = toast.text,
                kind = when (toast.kind) {
                    SettingsToastKind.Info -> BulletinKind.Info
                    SettingsToastKind.Success -> BulletinKind.Success
                    SettingsToastKind.Error -> BulletinKind.Error
                },
                actionLabel = toast.actionLabel,
                onAction = { latestAction() },
                onTimeout = { latestExpired() },
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// 确认对话框（沿用抽屉页的 312dp 卡片）
// ---------------------------------------------------------------------------

/** 确认对话框 = 组件库 [WhaleDialog]，确认按钮是危险红（U0）。 */
@Composable
internal fun SettingsConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    WhaleDialog(
        onDismiss = onCancel,
        title = title,
        text = message,
        buttons = listOf(
            WhaleDialogButton("取消", onCancel),
            WhaleDialogButton(confirmLabel, onConfirm, danger = true),
        ),
    )
}

/** 文字按钮 = [WhaleButton] Text 样式。 */
@Composable
internal fun SettingsTextButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WhaleButton(text = label, onClick = onClick, style = WhaleButtonStyle.Text, textColor = color, modifier = modifier)
}

/** 实心按钮 = [WhaleButton]（40dp 紧凑款）；[container] 是危险红时用 Danger 样式。 */
@Composable
internal fun SettingsFilledButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    container: Color = WhaleTheme.colors.accentFill,
    @Suppress("UNUSED_PARAMETER") content: Color = WhaleTheme.colors.onAccent,
) {
    WhaleButton(
        text = label,
        onClick = onClick,
        enabled = enabled,
        compact = true,
        style = if (container == WhaleTheme.colors.danger) WhaleButtonStyle.Danger else WhaleButtonStyle.Primary,
    )
}

// ---------------------------------------------------------------------------
// 页面骨架（顶栏 / 分组标题 / 分隔线 / 分区间隔条）
//
// 权限与后台常驻页和设置页是同一套结构，所以把这四件放到组件库里共用。
// 数值与设置页里的那份私有实现完全一致（Telegram HeaderCell / TextSettingsCell /
// ShadowSectionCell 的值），设置页本身保持不动。
// ---------------------------------------------------------------------------

internal val SettingsRowStart = 21.dp
internal val SettingsRowEnd = 16.dp
internal val SettingsCellHeight = 64.dp

@Composable
internal fun SettingsPageTopBar(title: String, onBack: () -> Unit) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 8.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .semantics { contentDescription = "返回" },
            contentAlignment = Alignment.Center,
        ) {
            WhaleIcon(WhaleIcons.Back, tint = colors.textPrimary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = title,
            style = SettingsType.pageTitle,
            color = colors.textPrimary,
            maxLines = 1,
        )
    }
}

@Composable
internal fun SettingsGroupHeader(text: String) {
    val colors = WhaleTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(start = SettingsRowStart, end = SettingsRowEnd),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text = text,
            style = SettingsType.header,
            color = colors.accent,
            modifier = Modifier.padding(bottom = 7.dp),
        )
    }
}

@Composable
internal fun SettingsRowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(start = 20.dp)
            .background(WhaleTheme.colors.divider),
    )
}

/** 分组之间的 12dp 隔条（ShadowSectionCell）：比页面底色深一点。 */
@Composable
internal fun SettingsSectionShadow() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .background(WhaleTheme.colors.scrim),
    )
}

// ---------------------------------------------------------------------------
// 时间滚轮（单列 / 时+分一组）
// ---------------------------------------------------------------------------






// ---------------------------------------------------------------------------
// 语速滑杆（Telegram SeekBarView 的做法，数值等研究结果后微调）
// ---------------------------------------------------------------------------

/**
 * 0.5–2.0，步进 0.1。数值全部取 SeekBarView：
 * - 轨道 3dp，左右各留 16dp（selectorWidth 32dp / 2）；
 * - 滑块半径 6dp，按下时变 8dp（每帧 1dp、约 60ms，EaseOut）；
 * - 按下时滑块外一圈 32dp 的半透明光圈（alpha 40/255 ≈ 0.16）；
 * - 进度色 / 底色分别对应强调色、次级灰。
 */
@Composable
internal fun SpeechRateSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    val fraction = ((value - 0.5f) / 1.5f).coerceIn(0f, 1f)
    var pressed by remember { mutableStateOf(false) }
    val thumbRadiusDp by animateFloatAsState(
        targetValue = if (pressed) 8f else 6f,
        animationSpec = tween(WhaleDuration.Press, easing = TgEasing.EaseOut),
        label = "speechRateThumb",
    )
    val haloAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.16f else 0f,
        animationSpec = tween(WhaleDuration.Dismiss, easing = TgEasing.EaseOut),
        label = "speechRateHalo",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pressed = true
                    val inset = 16.dp.toPx()
                    val span = (size.width - inset * 2).coerceAtLeast(1f)
                    fun apply(x: Float) {
                        val f = ((x - inset) / span).coerceIn(0f, 1f)
                        onValueChange(((0.5f + f * 1.5f) * 10f).roundToInt() / 10f)
                    }
                    apply(down.position.x)
                    val pointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!change.pressed) break
                        change.consume()
                        apply(change.position.x)
                    }
                    pressed = false
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val trackY = size.height / 2f
            val inset = 16.dp.toPx()
            val span = size.width - inset * 2
            val thumbX = inset + span * fraction
            drawLine(
                color = colors.textCaption.copy(alpha = WhaleAlpha.Disabled),
                start = Offset(inset, trackY),
                end = Offset(size.width - inset, trackY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = colors.accentFill,
                start = Offset(inset, trackY),
                end = Offset(thumbX, trackY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            if (haloAlpha > 0f) {
                drawCircle(
                    color = colors.accentFill.copy(alpha = haloAlpha),
                    radius = 16.dp.toPx(),
                    center = Offset(thumbX, trackY),
                )
            }
            drawCircle(
                color = colors.accentFill,
                radius = thumbRadiusDp.dp.toPx(),
                center = Offset(thumbX, trackY),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 线稿图标（全部手绘，1.5–1.8dp 线宽，和聊天页 / 记忆页同一套）
// ---------------------------------------------------------------------------

















/** 小转圈，导出时行上显示进度。 */
@Composable
internal fun SettingsSpinner(modifier: Modifier, color: Color) {
    val colors = WhaleTheme.colors
    val rotation = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        rotation.animateTo(
            targetValue = 360f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = tween(WhaleDuration.Loop, easing = androidx.compose.animation.core.LinearEasing),
            ),
        )
    }
    Canvas(modifier.graphicsLayer { rotationZ = rotation.value }) {
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 280f,
            useCenter = false,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** 播放中的三条跳动竖线（试听按钮的播放态）。 */
@Composable
internal fun PlayingBarsGlyph(modifier: Modifier, color: Color) {
    val phases = remember { listOf(0f, 0.33f, 0.66f) }
    val time = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        time.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = tween(WhaleDuration.Loop, easing = androidx.compose.animation.core.LinearEasing),
            ),
        )
    }
    Canvas(modifier) {
        val barWidth = size.width / 5f
        phases.forEachIndexed { index, phase ->
            val t = (time.value + phase) % 1f
            val wave = abs(1f - 2f * t)
            val h = size.height * (0.35f + 0.6f * wave)
            val x = barWidth * (1f + 2f * index) + barWidth / 2f
            drawLine(
                color = color,
                start = Offset(x, size.height / 2f - h / 2f),
                end = Offset(x, size.height / 2f + h / 2f),
                strokeWidth = barWidth * 0.8f,
                cap = StrokeCap.Round,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 颜色补充（深海蓝里没有的语义色，沿用已验收页面里已有的值，不新增令牌）
// ---------------------------------------------------------------------------


