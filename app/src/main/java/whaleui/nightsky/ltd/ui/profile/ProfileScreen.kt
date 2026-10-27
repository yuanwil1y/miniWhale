package whaleui.nightsky.ltd.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import whaleui.nightsky.ltd.ui.components.HerAvatar
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import whaleui.nightsky.ltd.ui.chat.HerPresence
import whaleui.nightsky.ltd.ui.chat.PresenceLine
import whaleui.nightsky.ltd.ui.components.Cell
import whaleui.nightsky.ltd.ui.components.SectionCard
import whaleui.nightsky.ltd.ui.components.SectionGap
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIconButton
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import java.time.LocalDate

/**
 * 她的资料页（B，README §5 B；样式参考 u3 §1–2 / Telegram ProfileActivity）。
 *
 * 顺序：头部（96dp 头像、衬线名字、状态行）→ token 热力图 → 「她」分组（只读状态）。
 * 顶栏左边返回键、右边铅笔（进「编辑」改她的名字和头像）；列表滚过名字后顶栏变实色底、标题位淡入名字（420ms EaseOutQuint，u3 §2）。
 * 不做下拉放大、数字卡、悄悄话卡；没有开关，「人设」「Proactive Agent」两行点了跳现成的设置页 / 权限页。
 */
@Composable
internal fun ProfileScreen(
    state: ProfileUiState,
    title: String,
    presence: HerPresence,
    rows: List<HerRow>,
    onBack: () -> Unit,
    onOpenTarget: (ProfileTarget) -> Unit,
    modifier: Modifier = Modifier,
    /** 顶栏右侧铅笔：编辑她的名字和头像 */
    onEdit: () -> Unit = {},
) {
    val colors = WhaleTheme.colors
    val type = WhaleTheme.type
    val scroll = rememberScrollState()
    val today = remember { LocalDate.now() }
    // 名字底边（屏幕坐标）滚到顶栏底边以上时，顶栏才出名字
    var nameBottomPx by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var barBottomPx by remember { mutableFloatStateOf(0f) }
    val collapsed = nameBottomPx <= barBottomPx
    val barProgress by animateFloatAsState(
        targetValue = if (collapsed) WhaleAlpha.Visible else WhaleAlpha.Hidden,
        animationSpec = tween(WhaleDuration.Title, easing = TgEasing.EaseOutQuint),
        label = "profileBar",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(colors.bgPage),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(WhaleSpacing.actionBarHeight))
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HerAvatar(size = WhaleSpacing.profileAvatar, contentDescription = "她的头像")
                Spacer(Modifier.height(WhaleSpacing.m))
                Text(
                    text = title,
                    style = type.profileName,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = WhaleSpacing.xxl)
                        .semantics { heading() }
                        .onGloballyPositioned { coords -> nameBottomPx = coords.positionInRoot().y + coords.size.height },
                )
                Spacer(Modifier.height(WhaleSpacing.xxs))
                PresenceLine(
                    presence = presence,
                    style = type.chatStatus,
                    keyMissingColor = colors.textSecondary,
                )
            }
            Spacer(Modifier.height(WhaleSpacing.xl))

            SectionCard(header = "Token") {
                TokenHeatmap(
                    daily = state.dailyTokens,
                    today = today,
                    modifier = Modifier.padding(
                        start = WhaleSpacing.cellHorizontal,
                        end = WhaleSpacing.cellHorizontal,
                        top = WhaleSpacing.xs,
                        bottom = WhaleSpacing.l,
                    ),
                )
            }
            SectionGap()
            SectionCard(header = "她") {
                rows.forEach { row ->
                    Cell(
                        title = row.title,
                        value = row.value,
                        valueColor = if (row.active) colors.accent else colors.textSecondary,
                        onClick = row.target?.let { target -> { onOpenTarget(target) } },
                    )
                }
            }
            Spacer(Modifier.height(WhaleSpacing.xxl))
            Spacer(Modifier.navigationBarsPadding())
        }

        ProfileTopBar(
            title = title,
            progress = barProgress,
            onBack = onBack,
            onEdit = onEdit,
            modifier = Modifier.onGloballyPositioned { coords -> barBottomPx = coords.positionInRoot().y + coords.size.height },
        )
    }
}

/** 顶栏：返回键和铅笔常显；[progress] 0→1 时底从透明变 surfaceBar、名字淡入。 */
@Composable
private fun ProfileTopBar(
    title: String,
    progress: Float,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .alpha(progress)
                // 不透明底（Telegram 资料页收起后的顶栏也是实色）：没有模糊源时 76% 玻璃会透出底下的名字
                .background(colors.surfaceBar),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(WhaleSpacing.actionBarHeight)
                .padding(start = WhaleSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(WhaleSpacing.touchTarget)
                    .clip(WhaleShapes.circle)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = "返回" },
                contentAlignment = Alignment.Center,
            ) {
                WhaleIcon(WhaleIcons.Back, tint = colors.textPrimary, modifier = Modifier.size(WhaleSpacing.icon))
            }
            Spacer(Modifier.width(WhaleSpacing.m))
            Text(
                text = title,
                style = WhaleTheme.type.pageTitle,
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .alpha(progress),
            )
            WhaleIconButton(icon = WhaleIcons.Pencil, contentDescription = "编辑", onClick = onEdit)
            Spacer(Modifier.width(WhaleSpacing.xs))
        }
    }
}
