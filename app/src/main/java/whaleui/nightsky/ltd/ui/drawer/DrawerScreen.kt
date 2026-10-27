package whaleui.nightsky.ltd.ui.drawer

import whaleui.nightsky.ltd.ui.components.HerAvatar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.ui.chat.HerPresence
import whaleui.nightsky.ltd.ui.chat.PresenceLine
import whaleui.nightsky.ltd.ui.chat.presenceText
import whaleui.nightsky.ltd.ui.components.Cell
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.theme.WhaleTheme

// ---------------------------------------------------------------------------
// 抽屉面板（C，u1 §2 的样式；入口按 Q4 五项）
// ---------------------------------------------------------------------------

/**
 * 抽屉页，无状态。头部：她的头像、衬线名字、状态行（和聊天顶栏同一个 [HerPresence]），点头部进她的资料页；
 * 下面五行入口；再下面是「她最近的日记」小卡片（[diary] 为 null 时不显示）。
 * 宽 min(屏宽 − 56dp, 320dp)，底 surfaceDrawer，头部 surfaceDrawerHeader 画进状态栏。
 */
@Composable
internal fun DrawerScreen(
    title: String,
    presence: HerPresence,
    diary: DrawerDiaryPreview?,
    onProfileClick: () -> Unit,
    onMemoryClick: () -> Unit,
    onScheduleClick: () -> Unit,
    onStickersClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDiaryClick: (DrawerDiaryPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val width = (screenWidth - WhaleSpacing.drawerEdgeGap).coerceAtMost(WhaleSpacing.drawerMaxWidth)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .background(colors.surfaceDrawer)
            .verticalScroll(rememberScrollState()),
    ) {
        DrawerHeader(title = title, presence = presence, onClick = onProfileClick)
        Spacer(Modifier.height(WhaleSpacing.s))
        Cell(title = "她的资料", icon = WhaleIcons.User, onClick = onProfileClick)
        Cell(title = "记忆与日记", icon = WhaleIcons.Book, onClick = onMemoryClick)
        Cell(title = "定时任务", icon = WhaleIcons.Alarm, onClick = onScheduleClick)
        Cell(title = "表情包", icon = WhaleIcons.Sticker, onClick = onStickersClick)
        Spacer(Modifier.height(WhaleSpacing.s))
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.divider),
        )
        Spacer(Modifier.height(WhaleSpacing.s))
        Cell(title = "设置", icon = WhaleIcons.Settings, onClick = onSettingsClick)
        if (diary != null) {
            Spacer(Modifier.height(WhaleSpacing.l))
            DrawerDiaryCard(diary = diary, onClick = { onDiaryClick(diary) })
        }
        Spacer(Modifier.height(WhaleSpacing.l))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** 头部：64dp 头像 → 12dp → 衬线名字 22 → 2dp → 状态 14；整块可点（进她的资料页）。 */
@Composable
private fun DrawerHeader(title: String, presence: HerPresence, onClick: () -> Unit) {
    val colors = WhaleTheme.colors
    val type = WhaleTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceDrawerHeader)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title，${presenceText(presence)}，打开她的资料"
            }
            .statusBarsPadding()
            .padding(
                start = WhaleSpacing.xl,
                end = WhaleSpacing.xl,
                top = WhaleSpacing.xl,
                bottom = WhaleSpacing.l,
            ),
    ) {
        HerAvatar(size = WhaleSpacing.drawerAvatar)
        Spacer(Modifier.height(WhaleSpacing.m))
        Text(
            text = title,
            style = type.drawerTitle,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(WhaleSpacing.xxs))
        PresenceLine(presence = presence, style = type.chatStatus, keyMissingColor = colors.textSecondaryOnBar)
    }
}

/** 「📔 她最近的日记」+ 日期，下面正文开头 2 行（超出省略）。底 surfaceDrawerHeader、r16，左右 12dp。 */
@Composable
private fun DrawerDiaryCard(diary: DrawerDiaryPreview, onClick: () -> Unit) {
    val colors = WhaleTheme.colors
    val type = WhaleTheme.type
    Column(
        Modifier
            .padding(horizontal = WhaleSpacing.cardOuter)
            .fillMaxWidth()
            .clip(WhaleShapes.card)
            .background(colors.surfaceDrawerHeader)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "她最近的日记，${diary.dateLabel}，${diary.excerpt}"
            }
            .padding(horizontal = WhaleSpacing.l, vertical = WhaleSpacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "📔 她最近的日记",
                style = type.header,
                color = colors.accentHeader,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(WhaleSpacing.s))
            Text(text = diary.dateLabel, style = type.cellSubtitle, color = colors.textSecondary, maxLines = 1)
        }
        Spacer(Modifier.height(WhaleSpacing.xs))
        Text(
            text = diary.excerpt,
            style = type.footnote,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
