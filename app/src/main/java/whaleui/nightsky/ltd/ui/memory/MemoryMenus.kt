package whaleui.nightsky.ltd.ui.memory

import whaleui.nightsky.ltd.ui.components.WhaleDialogButton
import whaleui.nightsky.ltd.ui.components.WhaleDialog
import whaleui.nightsky.ltd.ui.components.PopupMenuItem
import whaleui.nightsky.ltd.ui.components.PopupMenu
import whaleui.nightsky.ltd.ui.components.Cell
import androidx.compose.ui.graphics.vector.ImageVector
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import kotlin.math.roundToInt
import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory

// 从 MemoryScreen.kt 拆出来（R1 / W-051，只搬代码不改实现）

// ---------------------------------------------------------------------------
// 一行一行的菜单 / 改分类对话框
// ---------------------------------------------------------------------------


internal class MemoryMenuAction(
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false,
    val onSelect: () -> Unit,
)

/** 记忆卡 / 日记卡的菜单 = 组件库 [PopupMenu]（U0）。 */
@Composable
internal fun MemoryPopupMenu(
    touch: Offset,
    @Suppress("UNUSED_PARAMETER") styles: MemoryTextStyles,
    actions: List<MemoryMenuAction>,
    onDismiss: () -> Unit,
) {
    PopupMenu(
        touch = touch,
        items = actions.map { PopupMenuItem(it.label, it.icon, danger = it.danger, onClick = it.onSelect) },
        onDismiss = onDismiss,
    )
}


/** 改分类 = [WhaleDialog]：三行单选，当前分类右侧打勾，点一下即生效。 */
@Composable
internal fun CategoryPickerDialog(
    target: Memory,
    @Suppress("UNUSED_PARAMETER") styles: MemoryTextStyles,
    onSelect: (MemoryCategory) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = WhaleTheme.colors
    WhaleDialog(
        onDismiss = onDismiss,
        title = "改分类",
        buttons = listOf(WhaleDialogButton("取消", onDismiss)),
    ) {
        MemoryCategory.entries.forEach { category ->
            Cell(
                title = category.label,
                onClick = { onSelect(category) },
                trailing = if (category == target.category) {
                    { WhaleIcon(WhaleIcons.Check, tint = colors.accent) }
                } else {
                    null
                },
            )
        }
    }
}
