package whaleui.nightsky.ltd.ui.memory

import whaleui.nightsky.ltd.ui.components.WhaleBottomSheet
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.TgEasing
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.domain.memory.MemoryCategory

// 从 MemoryScreen.kt 拆出来（R1 / W-051，只搬代码不改实现）

// ---------------------------------------------------------------------------
// 编辑 / 新建弹层
// ---------------------------------------------------------------------------

@Composable
internal fun MemoryEditorSheet(
    editor: MemoryEditor,
    styles: MemoryTextStyles,
    onContentChange: (String) -> Unit,
    onCategoryChange: (MemoryCategory) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = WhaleTheme.colors
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    // 底部弹层 = 组件库 WhaleBottomSheet（U0）
    WhaleBottomSheet(onDismiss = onCancel, scrollable = false) { _ ->
            Text(
                text = if (editor.isNew) "添加记忆" else "编辑记忆",
                style = styles.editorTitle,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "分类",
                style = styles.meta,
                color = colors.textCaption,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MemoryCategory.entries.forEach { category ->
                    MemoryFilterChip(
                        label = category.label,
                        selected = editor.category == category,
                        styles = styles,
                        onClick = { onCategoryChange(category) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp)
                    .clip(RoundedCornerShape(WhaleRadius.m))
                    .background(colors.surfaceInput)
                    .padding(14.dp),
            ) {
                if (editor.content.isEmpty()) {
                    Text(
                        text = "记下关于你的事…",
                        style = styles.field,
                        color = colors.textCaption,
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = editor.content,
                    onValueChange = onContentChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 104.dp)
                        .focusRequester(focusRequester),
                    textStyle = styles.field.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accentFill),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MemoryTextButton(
                    label = "取消",
                    color = colors.textSecondary,
                    styles = styles,
                    onClick = { dismiss() },
                )
                Spacer(Modifier.width(8.dp))
                MemoryFilledButton(
                    label = "保存",
                    enabled = editor.content.isNotBlank(),
                    styles = styles,
                    onClick = { dismiss(onSave) },
                )
            }
    }
}
