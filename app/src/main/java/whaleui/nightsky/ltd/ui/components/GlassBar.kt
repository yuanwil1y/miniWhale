package whaleui.nightsky.ltd.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.theme.WhaleTheme

// ---------------------------------------------------------------------------
// 玻璃（U0，02 §1.4）：底色 surfaceBar × 76% + 模糊；深色下不画阴影，只画上 16% 白 / 下 8% 白两条 0.5dp 细线。
// - API 31+ 且给了 HazeState：Haze 模糊背后内容（现有依赖，不新增库）；
// - API 31+ 没有 HazeState：76% 半透明 + 细线；
// - API < 31：100% 不透明（Telegram 关模糊时同样退回不透明）。
// ---------------------------------------------------------------------------

/** Haze 的玻璃样式：模糊 20dp、无噪点、叠 surfaceBarGlass；不支持模糊时退成 surfaceBar 实色。 */
@Composable
fun rememberWhaleGlassStyle(): HazeBlurStyle {
    val colors = WhaleTheme.colors
    return remember(colors) {
        HazeBlurStyle {
            blurRadius(20.dp)
            noiseFactor(0f)
            colorEffects(listOf(HazeColorEffect.tint(colors.surfaceBarGlass)))
            fallbackColorEffect(HazeColorEffect.tint(colors.surfaceBar))
        }
    }
}

/** 给任意面板加玻璃底 + 上下细线。 */
fun Modifier.glassBackground(
    hazeState: HazeState? = null,
    shape: Shape = RectangleShape,
    topLine: Boolean = true,
    bottomLine: Boolean = true,
): Modifier = composed {
    val colors = WhaleTheme.colors
    val inspection = LocalInspectionMode.current
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val base = when {
        hazeState != null && canBlur && !inspection ->
            Modifier.clip(shape).hazeBlur(input = HazeInput.Sources(hazeState), style = rememberWhaleGlassStyle())
        canBlur -> Modifier.clip(shape).background(colors.surfaceBarGlass)
        else -> Modifier.clip(shape).background(colors.surfaceBar)
    }
    this
        .then(base)
        .drawWithContent {
            drawContent()
            val stroke = 0.5.dp.toPx()
            if (topLine) {
                drawLine(colors.glassStrokeTop, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
            }
            if (bottomLine) {
                drawLine(colors.glassStrokeBottom, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
            }
        }
}

/** 整宽玻璃栏（顶栏 56dp / 底栏），内容是一行。 */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    height: androidx.compose.ui.unit.Dp = WhaleSpacing.actionBarHeight,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassBackground(hazeState)
            .height(height),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
