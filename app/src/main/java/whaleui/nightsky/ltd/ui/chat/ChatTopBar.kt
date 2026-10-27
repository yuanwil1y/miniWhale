package whaleui.nightsky.ltd.ui.chat

import androidx.compose.runtime.getValue
import whaleui.nightsky.ltd.ui.components.HerAvatar
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import whaleui.nightsky.ltd.ui.theme.fixedFontScale
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import whaleui.nightsky.ltd.ui.theme.WhaleTheme

/** 顶栏状态行显示什么（u1 §3.3）：正在输入 > 没填密钥 > 在线。不做「在睡觉」。 */
internal sealed interface HerPresence {
    data object Online : HerPresence
    data object KeyMissing : HerPresence
    data class Typing(val label: String) : HerPresence
}

internal fun herPresence(isTyping: Boolean, typingLabel: String?, keyMissing: Boolean): HerPresence = when {
    isTyping -> HerPresence.Typing(typingLabel ?: MessagePacing.TYPING_TEXT)
    keyMissing -> HerPresence.KeyMissing
    else -> HerPresence.Online
}

/**
 * 聊天顶栏：保留悬浮玻璃布局（☰ 圆钮 + 名字胶囊），⋮ 已去掉（A）。
 * 胶囊里：42dp 头像、名字固定在上行、状态行一直显示（在线 / 正在输入… / 没填密钥）。
 * 点头像或名字 → [onNameClick]（她的资料页）。
 */
@Composable
internal fun ChatTopBar(
    title: String,
    presence: HerPresence,
    modifier: Modifier = Modifier,
    onMenuClick: () -> Unit,
    onNameClick: () -> Unit,
    hazeState: HazeState,
    inspection: Boolean,
    styles: ChatTextStyles,
) {
    val colors = WhaleTheme.colors
    val glassStyle = rememberGlassStyle()
    val statusText = presenceText(presence)
    // 名字 / 状态用不随系统字体缩放的同号字（README §2.8）：胶囊固定 48dp，放大字体后两行会被裁
    val density = androidx.compose.ui.platform.LocalDensity.current
    val nameStyle = remember(styles.name, density) { styles.name.fixedFontScale(density) }
    val statusStyle = remember(styles.status, density) { styles.status.fixedFontScale(density) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = WhaleSpacing.s, end = WhaleSpacing.s, top = 6.dp)
            .height(WhaleSpacing.actionBarHeight),
        horizontalArrangement = Arrangement.spacedBy(WhaleSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassRoundButton(
            onClick = onMenuClick,
            hazeState = hazeState,
            inspection = inspection,
            glassStyle = glassStyle,
            description = "打开菜单",
        ) {
            WhaleIcon(WhaleIcons.Menu, tint = colors.textPrimary, modifier = Modifier.size(WhaleSpacing.icon))
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .height(WhaleSpacing.touchTarget)
                .glass(
                    shape = WhaleShapes.pill,
                    state = hazeState,
                    style = glassStyle,
                    inspection = inspection,
                    fallback = colors.surfaceMenu,
                    stroke = colors.glassStrokeTop,
                )
                .clickable(onClick = onNameClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$title，$statusText，打开她的资料"
                }
                .padding(start = 3.dp, end = WhaleSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HerAvatar(size = WhaleSpacing.chatAvatar)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = nameStyle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PresenceLine(presence = presence, style = statusStyle)
            }
        }
    }
}

/** 状态行的文字：在线 / 没填密钥 / 正在输入…（聊天顶栏和资料页共用）。 */
internal fun presenceText(presence: HerPresence): String = when (presence) {
    HerPresence.Online -> "在线"
    HerPresence.KeyMissing -> "没填密钥"
    is HerPresence.Typing -> presence.label
}

/**
 * 状态行：文字切换时旧的淡出、新的淡入，同时 180ms；正在输入时左边画三点。
 * 聊天顶栏和她的资料页（B）共用；[keyMissingColor] 跟着底色换（顶栏玻璃上 / 页面底上）。
 */
@Composable
internal fun PresenceLine(
    presence: HerPresence,
    style: TextStyle,
    modifier: Modifier = Modifier,
    keyMissingColor: Color = WhaleTheme.colors.textSecondaryOnBar,
) {
    val colors = WhaleTheme.colors
    AnimatedContent(
        targetState = presence to presenceText(presence),
        transitionSpec = {
            fadeIn(tween(WhaleDuration.Short, easing = TgEasing.Default)) togetherWith
                fadeOut(tween(WhaleDuration.Short, easing = TgEasing.Default))
        },
        label = "presence",
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) { (state, label) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state is HerPresence.Typing) {
                TypingDots()
                Spacer(Modifier.width(WhaleSpacing.xs))
            }
            Text(
                text = label,
                style = style,
                color = if (state is HerPresence.KeyMissing) keyMissingColor else colors.accentOnline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun GlassRoundButton(
    onClick: () -> Unit,
    hazeState: HazeState,
    inspection: Boolean,
    glassStyle: dev.chrisbanes.haze.blur.HazeBlurStyle,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = WhaleTheme.colors
    Box(
        modifier = Modifier
            .size(WhaleSpacing.touchTarget)
            .glass(
                shape = CircleShape,
                state = hazeState,
                style = glassStyle,
                inspection = inspection,
                fallback = colors.surfaceMenu,
                stroke = colors.glassStrokeTop,
            )
            .clickable(onClick = onClick)
            .then(
                if (description != null) Modifier.semantics { contentDescription = description } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Telegram TypingDotsDrawable：3 个点，间距 6dp，半径 1.33→2.33dp，仅缩放。 */
@Composable
internal fun TypingDots() {
    val colors = WhaleTheme.colors
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = WhaleDuration.TypingDotPeriod, easing = LinearEasing)),
        label = "typingPhase",
    )

    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val period = WhaleDuration.TypingDotPeriod.toFloat()
            val half = WhaleDuration.TypingDotHalf.toFloat()
            val elapsed = (phase * period - index * WhaleDuration.TypingDotStagger + period) % period
            val scale = when {
                elapsed < half -> 1f + 0.75f * decelerate(elapsed / half)
                elapsed < half * 2 -> 1.75f - 0.75f * decelerate((elapsed - half) / half)
                else -> 1f
            }
            Box(
                Modifier
                    .size(3.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .background(colors.accentOnline),
            )
        }
    }
}

private fun decelerate(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return 1f - (1f - x) * (1f - x)
}
