package whaleui.nightsky.ltd.ui.memory

import whaleui.nightsky.ltd.ui.components.WhaleButtonStyle
import whaleui.nightsky.ltd.ui.components.WhaleButton
import whaleui.nightsky.ltd.ui.components.BulletinBus
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import kotlin.math.abs

// 从 MemoryScreen.kt 拆出来（R1 / W-051，只搬代码不改实现）

// ---------------------------------------------------------------------------
// 撤销提示（聊天页 UndoToast 那套样式 + Telegram UndoView 的进出时长）
// ---------------------------------------------------------------------------


// ---------------------------------------------------------------------------
// 通用小件
// ---------------------------------------------------------------------------

@Composable
internal fun MemoryFilterChip(
    label: String,
    selected: Boolean,
    styles: MemoryTextStyles,
    onClick: () -> Unit,
) {
    val colors = WhaleTheme.colors
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(WhaleTheme.shapes.pill)
            .background(if (selected) colors.accentFill else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else colors.divider,
                shape = WhaleTheme.shapes.pill,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = styles.chip,
            color = if (selected) colors.onAccent else colors.textSecondary,
        )
    }
}

@Composable
internal fun MemoryIconButton(
    description: String,
    size: Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(WhaleShapes.pill)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun MemoryTextButton(
    label: String,
    color: Color,
    @Suppress("UNUSED_PARAMETER") styles: MemoryTextStyles,
    onClick: () -> Unit,
) {
    WhaleButton(text = label, onClick = onClick, style = WhaleButtonStyle.Text, textColor = color)
}

@Composable
internal fun MemoryFilledButton(
    label: String,
    enabled: Boolean,
    @Suppress("UNUSED_PARAMETER") styles: MemoryTextStyles,
    onClick: () -> Unit,
) {
    WhaleButton(text = label, onClick = onClick, enabled = enabled, compact = true)
}

internal fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    BulletinBus.post("已复制")
}

/**
 * 悬浮按钮的滚动显隐：Telegram DialogsActivity 4538-4547 —
 * 顶部可见 item 位移 >1px 且向下 → 隐藏；向上 → 显示；首次滚动只记录不动作。
 */
@Composable
internal fun rememberFabVisible(listState: LazyListState): Boolean {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var prevIndex = listState.firstVisibleItemIndex
        var prevOffset = listState.firstVisibleItemScrollOffset
        var scrollUpdated = false
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            val goingDown = if (index == prevIndex) offset > prevOffset else index > prevIndex
            val changed = if (index == prevIndex) abs(offset - prevOffset) > 1 else true
            if (changed) {
                if (scrollUpdated) visible = !goingDown
                scrollUpdated = true
            }
            if (index == 0 && offset == 0) visible = true
            prevIndex = index
            prevOffset = offset
        }
    }
    return visible
}
