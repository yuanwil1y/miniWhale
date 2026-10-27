package whaleui.nightsky.ltd.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration

// ---------------------------------------------------------------------------
// 主题入口（U0）
// - 配色只有 [WhaleColors] 一套（Q1 Dark Blue）；M3 的 colorScheme 只是兜底映射，
//   给偶尔用到的 M3 组件（TextField 光标、Slider 等）一个不跑偏的颜色。
// - 只做深色（Q2）；以后加浅色：传入另一套 WhaleColors 即可。
// ---------------------------------------------------------------------------

/** 设计令牌入口，组件里用 `WhaleTheme.colors` / `type` / `shapes` 取。 */
object WhaleTheme {
    val colors: WhaleColors
        @Composable @ReadOnlyComposable
        get() = LocalWhaleColors.current

    val type: WhaleType
        @Composable @ReadOnlyComposable
        get() = LocalWhaleType.current

    val shapes: WhaleShapes get() = WhaleShapes
}

/** M3 兜底映射：只映射 surface / primary / error 这几类。 */
internal fun WhaleColors.toMaterialColorScheme(): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = accentFill,
    onPrimaryContainer = onAccent,
    secondary = accentHeader,
    onSecondary = onAccent,
    secondaryContainer = surfaceInput,
    onSecondaryContainer = textPrimary,
    tertiary = link,
    onTertiary = onAccent,
    background = bgPage,
    onBackground = textPrimary,
    surface = surface,
    onSurface = textPrimary,
    surfaceVariant = surfaceInput,
    onSurfaceVariant = textSecondary,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = bgPage,
    surfaceContainerLow = surface,
    surfaceContainer = surfaceDialog,
    surfaceContainerHigh = surfaceDialog,
    surfaceContainerHighest = surfaceMenuSolid,
    outline = inputLine,
    outlineVariant = divider,
    scrim = Color.Black,
    error = danger,
    onError = onAccent,
    errorContainer = surfaceDialog,
    onErrorContainer = danger,
    inverseSurface = textPrimary,
    inverseOnSurface = bgPage,
    inversePrimary = accentFill,
)

@Composable
fun WhaleUITheme(
    colors: WhaleColors = WhaleDarkBlue,
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val adjustment = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) configuration.fontWeightAdjustment else 0
    val type = remember(adjustment) { WhaleTypeDefaults.withWeightAdjustment(adjustment) }
    val ripple = remember(colors) {
        // 列表按压：整行矩形 ripple，颜色 = listSelector（8%）；不加按压缩放（02 §6.7）
        RippleConfiguration(
            color = colors.ripple.copy(alpha = 1f),
            rippleAlpha = RippleAlpha(
                draggedAlpha = colors.ripple.alpha,
                focusedAlpha = colors.ripple.alpha,
                hoveredAlpha = colors.ripple.alpha,
                pressedAlpha = colors.ripple.alpha,
            ),
        )
    }
    val scheme = remember(colors) { colors.toMaterialColorScheme() }
    CompositionLocalProvider(
        LocalWhaleColors provides colors,
        LocalWhaleType provides type,
    ) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(LocalRippleConfiguration provides ripple, content = content)
        }
    }
}
