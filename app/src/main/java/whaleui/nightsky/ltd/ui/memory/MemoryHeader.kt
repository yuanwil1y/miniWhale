package whaleui.nightsky.ltd.ui.memory

import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// 从 MemoryScreen.kt 拆出来（R1 / W-051，只搬代码不改实现）

// ---------------------------------------------------------------------------
// 顶栏
// ---------------------------------------------------------------------------

/** 顶栏：返回 + 标题（C：记忆搜索已删）。 */
@Composable
internal fun MemoryHeader(
    title: String,
    styles: MemoryTextStyles,
    onBack: () -> Unit,
) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 和设置 / 定时等页的顶栏同高（上留 8dp）
            .padding(start = 8.dp, end = 13.dp, top = 8.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemoryIconButton(
            description = "返回",
            size = 44.dp,
            onClick = onBack,
        ) {
            WhaleIcon(WhaleIcons.Back, tint = colors.textPrimary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = title,
            style = styles.title,
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// 标签栏（ViewPagerFixed.TabsView）
// ---------------------------------------------------------------------------

@Composable
internal fun MemoryTabs(
    position: Float,
    onTabClick: (Int) -> Unit,
) {
    val colors = WhaleTheme.colors
    val styles = rememberMemoryTextStyles()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labels = listOf("记忆", "日记")
    val textWidthsPx = remember(measurer, styles) {
        labels.map { label ->
            measurer.measure(
                text = AnnotatedString(label),
                style = styles.tab,
                maxLines = 1,
            ).size.width.toFloat()
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(TabRowHeight),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val sidePx = with(density) { TabSidePadding.toPx() }
        val slotPx = (widthPx - sidePx * 2f) / 2f
        // Telegram: 下划线宽度 = 文字宽（不包括 16dp 的 tabMargin）。
        val indicatorWidthPx = textWidthsPx.min()
        val clamped = position.coerceIn(0f, 1f)
        val from = floor(clamped).toInt().coerceIn(0, 1)
        val to = (from + 1).coerceAtMost(1)
        val fraction = clamped - from
        fun centerOf(index: Int): Float = sidePx + slotPx * index + slotPx / 2f
        val centerPx = if (from == to) {
            centerOf(from)
        } else {
            centerOf(from) + (centerOf(to) - centerOf(from)) * fraction
        }
        val indicatorX = centerPx - indicatorWidthPx / 2f

        Row(
            Modifier
                .fillMaxSize()
                .padding(start = TabSidePadding, end = TabSidePadding),
        ) {
            labels.forEachIndexed { index, label ->
                val weight = (1f - abs(clamped - index)).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onTabClick(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = styles.tab,
                        color = lerp(colors.textSecondary, colors.textPrimary, weight),
                    )
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset { IntOffset(indicatorX.roundToInt(), 0) }
                .width(with(density) { indicatorWidthPx.toDp() })
                .height(TabIndicatorHeight)
                .clip(
                    RoundedCornerShape(
                        topStart = TabIndicatorTopRadius,
                        topEnd = TabIndicatorTopRadius,
                    ),
                )
                .background(colors.accentFill),
        )
    }
}
