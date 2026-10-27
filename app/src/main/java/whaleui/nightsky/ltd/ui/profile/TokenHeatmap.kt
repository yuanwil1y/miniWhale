package whaleui.nightsky.ltd.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay
import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import java.time.LocalDate

/** 左侧只写一、三、五（README §5 B），对应第 0 / 2 / 4 行。 */
private val WeekdayLabels = mapOf(0 to "一", 2 to "三", 4 to "五")

/**
 * GitHub 式 token 热力图（B）：7 行（周一在上）× N 列（周），N 按可用宽度定（格子约 12dp、间距 3dp，
 * 放下整数列后把格子略微拉宽填满）。上方月份，左侧一 / 三 / 五，下方「近 N 周共 … token」。
 *
 * 点格子：格子描一圈白边，上方弹小提示「10月27日 · 1.2 万 token」，2.75 秒后自己收起；再点同一格也收起。
 * [daily] 为 null（第一次还没算完）时画空格子，下方那行先占位不显示。
 */
@Composable
internal fun TokenHeatmap(
    daily: Map<LocalDate, Long>?,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    val type = WhaleTheme.type
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = WhaleSpacing.heatGap
        // 标签按实际字宽排（系统字体放大时不裁、不挤）：左列取「一 / 三 / 五」里最宽的 + 4dp，至少 16dp
        val measurer = rememberTextMeasurer()
        val weekdayWidth = with(density) {
            WeekdayLabels.values.maxOf { measurer.measure(it, type.small).size.width }.toDp()
        }
        val labelColumn = maxOf(WhaleSpacing.heatWeekdayColumn, weekdayWidth + WhaleSpacing.xs)
        val gridWidth = maxWidth - labelColumn - gap
        val weeks = TokenHeatmapMath.weeksFor(gridWidth.value, WhaleSpacing.heatCell.value, gap.value)
        val cell: Dp = (gridWidth - gap * (weeks - 1)) / weeks
        val gridHeight = cell * 7 + gap * 6
        val grid = remember(daily, today, weeks) { TokenHeatmapMath.grid(daily.orEmpty(), today, weeks) }
        var selected by remember(grid) { mutableStateOf<Pair<Int, Int>?>(null) }

        LaunchedEffect(selected) {
            if (selected != null) {
                delay(WhaleDuration.BulletinLong)
                selected = null
            }
        }

        Column {
            // 月份
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = labelColumn + gap),
            ) {
                monthPlacements(grid.monthLabels, cell, gap, gridWidth) { measurer.measure(it, type.small).size.width }
                    .forEach { (label, x) ->
                        Text(
                            text = label,
                            style = type.small,
                            color = colors.textSecondary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.offset(x = x),
                        )
                    }
            }
            Spacer(Modifier.height(WhaleSpacing.xs))
            Row {
                // 一 / 三 / 五
                Box(Modifier.width(labelColumn).height(gridHeight)) {
                    WeekdayLabels.forEach { (row, label) ->
                        Box(
                            Modifier
                                .offset(y = (cell + gap) * row)
                                .size(labelColumn, cell),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            // 系统字体放大时字比格子高：不裁，按格子中线居中溢出
                            Text(
                                text = label,
                                style = type.small,
                                color = colors.textSecondary,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.wrapContentHeight(unbounded = true),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(gap))
                Box(
                    Modifier
                        .width(gridWidth)
                        .height(gridHeight),
                ) {
                    val cellPx = with(density) { cell.toPx() }
                    val gapPx = with(density) { gap.toPx() }
                    Canvas(
                        Modifier
                            .width(gridWidth)
                            .height(gridHeight)
                            .semantics { contentDescription = "token 热力图，" + TokenHeatmapMath.totalLine(grid) }
                            .pointerInput(grid, cellPx, gapPx) {
                                detectTapGestures { pos ->
                                    val col = (pos.x / (cellPx + gapPx)).toInt()
                                    val row = (pos.y / (cellPx + gapPx)).toInt()
                                    val hit = grid.columns.getOrNull(col)?.getOrNull(row)
                                    selected = when {
                                        hit == null -> null
                                        selected == (col to row) -> null
                                        else -> col to row
                                    }
                                }
                            },
                    ) {
                        val radius = CornerRadius(WhaleRadius.heatCell.toPx())
                        val ring = 1.dp.toPx()
                        grid.columns.forEachIndexed { col, days ->
                            days.forEachIndexed { row, day ->
                                if (day == null) return@forEachIndexed
                                val topLeft = Offset(col * (cellPx + gapPx), row * (cellPx + gapPx))
                                drawRoundRect(
                                    color = if (day.level == 0) colors.heatEmpty else colors.accent.copy(alpha = heatAlpha(day.level)),
                                    topLeft = topLeft,
                                    size = Size(cellPx, cellPx),
                                    cornerRadius = radius,
                                )
                                if (selected == (col to row)) {
                                    drawRoundRect(
                                        color = colors.textPrimary,
                                        topLeft = topLeft + Offset(ring / 2, ring / 2),
                                        size = Size(cellPx - ring, cellPx - ring),
                                        cornerRadius = radius,
                                        style = Stroke(ring),
                                    )
                                }
                            }
                        }
                    }
                    val current = selected
                    val day = current?.let { (col, row) -> grid.columns.getOrNull(col)?.getOrNull(row) }
                    if (current != null && day != null) {
                        val offsetPx = with(density) { WhaleSpacing.heatHintOffset.roundToPx() }
                        val edgePx = with(density) { WhaleSpacing.cardOuter.roundToPx() }
                        val cellRect = IntRect(
                            left = (current.first * (cellPx + gapPx)).toInt(),
                            top = (current.second * (cellPx + gapPx)).toInt(),
                            right = (current.first * (cellPx + gapPx) + cellPx).toInt(),
                            bottom = (current.second * (cellPx + gapPx) + cellPx).toInt(),
                        )
                        Popup(popupPositionProvider = remember(cellRect, offsetPx, edgePx) { HintPosition(cellRect, offsetPx, edgePx) }) {
                            Text(
                                text = TokenHeatmapMath.cellHint(day, today),
                                style = type.cellSubtitle,
                                color = colors.bulletinText,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(WhaleShapes.hint)
                                    .background(colors.bulletinBg)
                                    .padding(horizontal = WhaleSpacing.heatHintHorizontal, vertical = WhaleSpacing.heatHintVertical)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(WhaleSpacing.s))
            Text(
                text = TokenHeatmapMath.totalLine(grid),
                style = type.cellSubtitle,
                color = colors.textSecondary,
                modifier = Modifier.alpha(if (daily == null) WhaleAlpha.Hidden else WhaleAlpha.Visible),
            )
        }
    }
}

/**
 * 月份标签的位置：按实际字宽居中在它那一列上方，夹在网格左右边以内；
 * 和前一个标签间隔不到一个格距时这个就不写，避免重叠。
 */
@Composable
private fun monthPlacements(
    labels: List<String?>,
    cell: Dp,
    gap: Dp,
    gridWidth: Dp,
    measureWidthPx: (String) -> Int,
): List<Pair<String, Dp>> {
    val density = LocalDensity.current
    return remember(labels, cell, gap, gridWidth, density) {
        val placed = ArrayList<Pair<String, Dp>>()
        var prevEnd = Dp.Hairline - gap
        labels.forEachIndexed { index, label ->
            if (label == null) return@forEachIndexed
            val width = with(density) { measureWidthPx(label).toDp() }
            val center = (cell + gap) * index + cell / 2
            val x = (center - width / 2).coerceIn(Dp.Hairline, (gridWidth - width).coerceAtLeast(Dp.Hairline))
            if (x >= prevEnd + gap) {
                placed += label to x
                prevEnd = x + width
            }
        }
        placed
    }
}

private fun heatAlpha(level: Int): Float = when (level) {
    1 -> WhaleAlpha.Heat1
    2 -> WhaleAlpha.Heat2
    3 -> WhaleAlpha.Heat3
    else -> WhaleAlpha.Heat4
}

/** 小提示放在格子正上方居中；上面放不下就放下面；左右离屏幕边至少 [edge]（和卡片外边距一致）。 */
private class HintPosition(private val cell: IntRect, private val offset: Int, private val edge: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val centerX = anchorBounds.left + cell.left + cell.width / 2
        val x = (centerX - popupContentSize.width / 2)
            .coerceIn(edge, (windowSize.width - popupContentSize.width - edge).coerceAtLeast(edge))
        val above = anchorBounds.top + cell.top - offset - popupContentSize.height
        val y = if (above >= 0) above else anchorBounds.top + cell.bottom + offset
        return IntOffset(x, y)
    }
}
