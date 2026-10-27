package whaleui.nightsky.ltd.ui.schedule

import whaleui.nightsky.ltd.ui.components.WhaleButtonStyle
import whaleui.nightsky.ltd.ui.components.WhaleButton
import whaleui.nightsky.ltd.ui.components.PopupMenuItem
import whaleui.nightsky.ltd.ui.components.PopupMenu
import androidx.compose.ui.graphics.vector.ImageVector
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.TgEasing
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import whaleui.nightsky.ltd.ui.theme.WhaleTheme

// 从 ScheduleScreen.kt 拆出来（R1 / W-051，只搬代码不改实现）

// ---------------------------------------------------------------------------
// 菜单 / 按钮
// ---------------------------------------------------------------------------

internal class ScheduleMenuAction(
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false,
    val onSelect: () -> Unit,
)

/** 长按任务卡的菜单 = 组件库 [PopupMenu]（U0）。 */
@Composable
internal fun SchedulePopupMenu(
    touch: Offset,
    @Suppress("UNUSED_PARAMETER") styles: ScheduleTextStyles,
    actions: List<ScheduleMenuAction>,
    onDismiss: () -> Unit,
) {
    PopupMenu(
        touch = touch,
        items = actions.map { PopupMenuItem(it.label, it.icon, danger = it.danger, onClick = it.onSelect) },
        onDismiss = onDismiss,
    )
}


@Composable
internal fun ScheduleChip(
    label: String,
    selected: Boolean,
    styles: ScheduleTextStyles,
    onClick: () -> Unit,
) {
    val colors = WhaleTheme.colors
    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(WhaleTheme.shapes.pill)
            .background(if (selected) WhaleTheme.colors.accentFill else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else WhaleTheme.colors.divider,
                shape = WhaleTheme.shapes.pill,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = styles.chip,
            color = if (selected) WhaleTheme.colors.onAccent else WhaleTheme.colors.textSecondary,
        )
    }
}

@Composable
internal fun ScheduleDayChip(
    label: String,
    selected: Boolean,
    styles: ScheduleTextStyles,
    onClick: () -> Unit,
) {
    val colors = WhaleTheme.colors
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (selected) WhaleTheme.colors.accentFill else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else WhaleTheme.colors.divider,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = styles.chip,
            color = if (selected) WhaleTheme.colors.onAccent else WhaleTheme.colors.textSecondary,
        )
    }
}

@Composable
internal fun ScheduleTextButton(
    label: String,
    color: Color,
    @Suppress("UNUSED_PARAMETER") styles: ScheduleTextStyles,
    onClick: () -> Unit,
) {
    WhaleButton(text = label, onClick = onClick, style = WhaleButtonStyle.Text, textColor = color)
}

@Composable
internal fun ScheduleFilledButton(
    label: String,
    enabled: Boolean,
    @Suppress("UNUSED_PARAMETER") styles: ScheduleTextStyles,
    onClick: () -> Unit,
) {
    WhaleButton(text = label, onClick = onClick, enabled = enabled, compact = true)
}

// ---------------------------------------------------------------------------
// 撤销提示
// ---------------------------------------------------------------------------


