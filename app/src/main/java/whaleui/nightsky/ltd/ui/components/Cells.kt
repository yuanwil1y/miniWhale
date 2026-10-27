package whaleui.nightsky.ltd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.ui.theme.rememberWhaleHaptics

// ---------------------------------------------------------------------------
// 列表行（U0，02 §2.3 / §2.5；Telegram TextSettingsCell / TextCell / TextDetailCell /
// HeaderCell / TextInfoPrivacyCell / TextCheckCell2 的数值，自己实现）
// ---------------------------------------------------------------------------

/**
 * 分组卡片：左右 12dp 外边距、r16、底 surface；卡片内行之间不画分隔线（Telegram 13 卡片式设置）。
 * [header] 不为空时在卡片上方画 [HeaderCell]（卡片外，和 Telegram 13 一致放在卡内第一行也可，这里放卡内）。
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    header: String? = null,
    footnote: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(horizontal = WhaleSpacing.cardOuter)
                .fillMaxWidth()
                .clip(WhaleShapes.card)
                .background(WhaleTheme.colors.surface),
        ) {
            if (header != null) HeaderCell(header)
            content()
        }
        if (footnote != null) InfoFootnote(footnote)
    }
}

/** 分组标题：40dp、14 Medium、accentHeader，左右 21dp，文字距顶 15dp。 */
@Composable
fun HeaderCell(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(WhaleSpacing.headerHeight)
            .padding(horizontal = WhaleSpacing.cellHorizontal),
    ) {
        Text(
            text = text,
            style = WhaleTheme.type.header,
            color = WhaleTheme.colors.accentHeader,
            modifier = Modifier.padding(top = WhaleSpacing.headerTop),
            maxLines = 1,
        )
    }
}

/** 组下说明：14、textCaption，左右 24、上 10、下 17。 */
@Composable
fun InfoFootnote(text: String, modifier: Modifier = Modifier, color: Color = WhaleTheme.colors.textCaption) {
    Text(
        text = text,
        style = WhaleTheme.type.footnote,
        color = color,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = WhaleSpacing.footnoteHorizontal,
                end = WhaleSpacing.footnoteHorizontal,
                top = WhaleSpacing.footnoteTop,
                bottom = WhaleSpacing.footnoteBottom,
            ),
    )
}

/** 行内分隔线：1px divider，左缩进对齐文字（有图标 58dp，否则 21dp），右到卡片边。 */
@Composable
fun CellDivider(withIcon: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = if (withIcon) WhaleSpacing.cellTextIndentWithIcon else WhaleSpacing.cellHorizontal)
            .height(0.5.dp)
            .background(WhaleTheme.colors.divider),
    )
}

/**
 * 基本行：可选左图标（24dp、icon 灰，文字起点 58dp）+ 标题（16）+ 可选副标题（13）+ 右侧值（16 accentValue）或自定义 [trailing]。
 * 单行 50dp、带副标题 64dp。整行 ripple；禁用 50%。
 */
@Composable
fun Cell(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = WhaleTheme.colors.icon,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = WhaleTheme.colors.accentValue,
    titleColor: Color = WhaleTheme.colors.textPrimary,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = WhaleTheme.colors
    val type = WhaleTheme.type
    val haptics = rememberWhaleHaptics()
    val click = when {
        onClick == null && onLongClick == null -> Modifier
        else -> Modifier.combinedClickable(
            enabled = enabled,
            role = Role.Button,
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick?.let { long -> { haptics.longPress(); long() } },
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (subtitle != null) WhaleSpacing.cellHeightTwoLine else WhaleSpacing.cellHeight)
            .then(click)
            .alpha(if (enabled) WhaleAlpha.Visible else WhaleAlpha.Disabled)
            .padding(
                start = if (icon != null) WhaleSpacing.cellIconStart else WhaleSpacing.cellHorizontal,
                end = WhaleSpacing.cellHorizontal,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(WhaleSpacing.icon))
            Spacer(Modifier.width(WhaleSpacing.cellTextIndentWithIcon - WhaleSpacing.cellIconStart - WhaleSpacing.icon))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = WhaleSpacing.s),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = title, style = type.cellTitle, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(WhaleSpacing.xxs))
                Text(text = subtitle, style = type.cellSubtitle, color = colors.textSecondary)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(WhaleSpacing.m))
            Text(text = value, style = type.cellTitle, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(WhaleSpacing.s))
            trailing()
        }
    }
}

/** 信息行：值在上（16）、标签在下（13 textSecondary），60dp；右侧可放一个 48dp 图标按钮。只读状态用它，不用开关。 */
@Composable
fun DetailCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = WhaleTheme.colors.textPrimary,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = WhaleSpacing.detailCellHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = WhaleSpacing.cellHorizontal, end = if (trailing != null) WhaleSpacing.s else WhaleSpacing.cellHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = WhaleSpacing.s),
        ) {
            Text(text = value, style = WhaleTheme.type.cellTitle, color = valueColor)
            Spacer(Modifier.height(WhaleSpacing.xxs))
            Text(text = label, style = WhaleTheme.type.cellSubtitle, color = WhaleTheme.colors.textSecondary)
        }
        trailing?.invoke()
    }
}

/** 开关行：整行可点，右侧 [WhaleSwitch]；单行 50dp、带副标题 64dp。 */
@Composable
fun SwitchCell(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberWhaleHaptics()
    Cell(
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch) {
            haptics.toggle(it)
            onCheckedChange(it)
        },
        trailing = { WhaleSwitch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** 卡片间距：12dp。 */
@Composable
fun SectionGap(height: Dp = WhaleSpacing.sectionGap) {
    Spacer(Modifier.height(height))
}
