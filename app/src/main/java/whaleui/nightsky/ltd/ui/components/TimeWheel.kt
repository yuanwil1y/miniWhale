package whaleui.nightsky.ltd.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.ui.theme.rememberWhaleHaptics
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 统一时间滚轮（U0，合并 W-054 的三份实现：定时任务编辑、免打扰弹层、设置组件）。
// Telegram NumberPicker 的观感：可见 5 行、行距 46dp、循环滚动、透视缩放（CubicBezier(0,0.5,0.5,1)）、
// 上下 96dp 渐隐、选中带上下两条 2dp 分隔线；松手按速度投射后吸附（300ms Decelerate）；跨格轻震一下。
// ---------------------------------------------------------------------------

private val WheelPitch = 46.dp
private const val WheelRows = 5
private val WheelHeight = 210.dp
private val WheelFadeLength = 96.dp
private val WheelDividerTop = 82.dp
private val WheelDividerBottom = 128.dp
private val WheelColumnWidth = 64.dp
private val WheelColonWidth = 20.dp
private val WheelPerspectiveEase = CubicBezierEasing(0f, 0.5f, 0.5f, 1f)

/** 「时 : 分」一组滚轮。[fade] 是渐隐要融进去的底色（滚轮放在哪种底上就传哪种）。 */
@Composable
fun TimeWheel(
    time: LocalTime,
    onTimeChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    fade: Color = WhaleTheme.colors.surfaceDialog,
    minuteStep: Int = 1,
) {
    val colors = WhaleTheme.colors
    Box(
        modifier = modifier
            .width(WheelColumnWidth * 2 + WheelColonWidth)
            .height(WheelHeight)
            .semantics { contentDescription = "%02d:%02d".format(time.hour, time.minute) },
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelColumn(
                values = (0..23).toList(),
                selected = time.hour,
                fade = fade,
                onSelected = { onTimeChange(time.withHour(it)) },
            )
            Box(Modifier.width(WheelColonWidth), contentAlignment = Alignment.Center) {
                Text(text = ":", style = WhaleTheme.type.wheel, color = colors.textCaption)
            }
            WheelColumn(
                values = (0 until 60 step minuteStep).toList(),
                selected = time.minute - time.minute % minuteStep,
                fade = fade,
                onSelected = { onTimeChange(time.withMinute(it)) },
            )
        }
        // 选中框两条线画在最上层：放在列下面会被列的渐隐盖暗，只有冒号那一段亮（U0 截图核对后改）
        listOf(WheelDividerTop, WheelDividerBottom).forEach { y ->
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .offset(y = y)
                    .height(2.dp)
                    .background(colors.accent),
            )
        }
    }
}

/** 单列循环滚轮。 */
@Composable
fun WheelColumn(
    values: List<Int>,
    selected: Int,
    onSelected: (Int) -> Unit,
    fade: Color = WhaleTheme.colors.surfaceDialog,
    label: (Int) -> String = { it.toString().padStart(2, '0') },
) {
    val colors = WhaleTheme.colors
    val haptics = rememberWhaleHaptics()
    val density = LocalDensity.current
    val pitchPx = with(density) { WheelPitch.toPx() }
    val size = values.size.coerceAtLeast(1)
    val initialIndex = values.indexOf(selected).let { if (it < 0) 0 else it }
    val offset = remember(values.size) { Animatable(initialIndex * pitchPx) }
    val scope = rememberCoroutineScope()

    // 外部改了值（例如切换任务）时跟上
    LaunchedEffect(selected, values.size) {
        val current = Math.floorMod((offset.value / pitchPx).roundToInt(), size)
        val target = values.indexOf(selected)
        if (target >= 0 && target != current && !offset.isRunning) {
            offset.snapTo(target * pitchPx)
        }
    }

    fun snapTo(targetIndex: Int) {
        onSelected(values[Math.floorMod(targetIndex, size)])
        scope.launch {
            offset.animateTo(targetIndex * pitchPx, tween(WhaleDuration.Drawer, easing = TgEasing.Decelerate))
        }
    }

    Box(
        modifier = Modifier
            .width(WheelColumnWidth)
            .height(WheelHeight)
            .pointerInput(values.size, pitchPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dragValue = offset.value
                    var lastIndex = (dragValue / pitchPx).roundToInt()
                    val pointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange().y
                        if (delta != 0f) {
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            dragValue -= delta
                            val idx = (dragValue / pitchPx).roundToInt()
                            if (idx != lastIndex) {
                                lastIndex = idx
                                haptics.tap()
                            }
                            scope.launch { offset.snapTo(dragValue) }
                        }
                    }
                    val velocity = -tracker.calculateVelocity().y
                    val projected = dragValue + velocity * 0.10f
                    snapTo((projected / pitchPx).roundToInt())
                }
            },
    ) {
        val center = offset.value / pitchPx
        val firstVirtual = floor(center).toInt() - (WheelRows / 2 + 1)
        for (virtual in firstVirtual..firstVirtual + WheelRows + 2) {
            val value = values[Math.floorMod(virtual, size)]
            val distance = abs(virtual - center)
            if (distance > WheelRows * 0.5f + 1.2f) continue
            val raw = (1f - distance / (WheelRows / 2f + 0.5f)).coerceIn(0f, 1f)
            val p = WheelPerspectiveEase.transform(raw)
            val rowAlpha = if (p < 0.1f) p / 0.1f else 1f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WheelPitch)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (WheelHeight / 2f - WheelPitch / 2f + WheelPitch * (virtual - center)).roundToPx(),
                        )
                    }
                    .graphicsLayer {
                        scaleX = 0.8f + 0.2f * p
                        scaleY = p
                        alpha = rowAlpha
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = label(value), style = WhaleTheme.type.wheel, color = colors.textPrimary)
            }
        }
        // 上下渐隐（NumberPicker：96dp、强度 0.9）
        val edge = fade.copy(alpha = fade.alpha * 0.9f)
        val clear = fade.copy(alpha = 0f)
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(WheelFadeLength)
                .background(Brush.verticalGradient(listOf(edge, clear))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(WheelFadeLength)
                .background(Brush.verticalGradient(listOf(clear, edge))),
        )
    }
}
