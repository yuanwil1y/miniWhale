package whaleui.nightsky.ltd.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.domain.chat.MessageStatus

/**
 * 气泡外形（A，02 §2.1 的几何，路径自己写）：
 * - 大圆角 [BubbleRadius] 17dp；同组相邻一侧的外侧角合并成 [GroupedRadius] 6dp，内侧两角始终 17dp；
 * - [tailArea]：外侧留 [TailGutter] 8dp 尾巴区（文字类气泡都留，同组气泡才能左右对齐）；
 * - [tail]：只有组末画尾巴，从外侧边线底部弯出去，尖在尾巴区的外下角；
 * - 圆角不超过高度一半。
 * 只按「我发的」画一遍，她的气泡左右镜像。
 */
internal class BubbleShape(
    private val outgoing: Boolean,
    private val topNear: Boolean,
    private val bottomNear: Boolean,
    private val tail: Boolean,
    private val tailArea: Boolean = true,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val gutter = if (tailArea) with(density) { TailGutter.toPx() } else 0f
        val big = with(density) { BubbleRadius.toPx() }.coerceAtMost(h / 2f)
        val small = with(density) { GroupedRadius.toPx() }.coerceAtMost(h / 2f)
        val tailHeight = with(density) { BubbleTailHeight.toPx() }.coerceAtMost(h / 2f)
        val body = w - gutter
        val outerTop = if (topNear) small else big
        val outerBottom = if (bottomNear) small else big
        val drawTail = tail && tailArea && !bottomNear

        val path = Path()
        path.moveTo(big, 0f)
        path.lineTo(body - outerTop, 0f)
        path.arcTo(Rect(body - 2 * outerTop, 0f, body, 2 * outerTop), 270f, 90f, false)
        if (drawTail) {
            // 外侧边线直下到尾巴根部，再向外下方弯到尖上，底边直接收回来
            path.lineTo(body, h - tailHeight)
            path.cubicTo(body, h - tailHeight * 0.35f, w - gutter * 0.45f, h, w, h)
        } else {
            path.lineTo(body, h - outerBottom)
            path.arcTo(Rect(body - 2 * outerBottom, h - 2 * outerBottom, body, h), 0f, 90f, false)
        }
        path.lineTo(big, h)
        path.arcTo(Rect(0f, h - 2 * big, 2 * big, h), 90f, 90f, false)
        path.lineTo(0f, big)
        path.arcTo(Rect(0f, 0f, 2 * big, 2 * big), 180f, 90f, false)
        path.close()
        if (!outgoing) {
            // 镜像：x → w − x（列主序 4×4，平移在第 12 位）
            path.transform(
                Matrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f,
                        0f, 0f, 1f, 0f,
                        w, 0f, 0f, 1f,
                    ),
                ),
            )
        }
        return Outline.Generic(path)
    }
}

/** 气泡底：同形路径下移 1dp 画阴影（不用 elevation），再画不透明底色。 */
internal fun Modifier.bubbleBackground(shape: Shape, color: Color, shadow: Color): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    translate(top = BubbleShadowOffset.toPx()) { drawOutline(outline, shadow) }
    drawOutline(outline, color)
}

/** 气泡右下角的「时间 + 勾」。勾只在我发的消息上，状态有就画，不新增状态。 */
@Composable
internal fun MessageMeta(
    time: String,
    status: MessageStatus,
    showStatus: Boolean,
    color: Color,
    checkColor: Color,
    clockColor: Color,
    styles: ChatTextStyles,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = time,
            style = styles.time,
            color = color,
            maxLines = 1,
        )
        if (showStatus) {
            Spacer(Modifier.width(3.dp))
            MessageStatusGlyph(
                status = status,
                color = if (status == MessageStatus.Sending) clockColor else checkColor,
                modifier = Modifier.size(width = 16.dp, height = 12.dp),
            )
        }
    }
}
