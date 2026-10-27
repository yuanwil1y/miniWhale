package whaleui.nightsky.ltd.ui.settings

import whaleui.nightsky.ltd.ui.components.WhaleButton
import whaleui.nightsky.ltd.ui.components.WhaleButtonStyle
import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.domain.settings.PersonaDocument
import whaleui.nightsky.ltd.domain.settings.PersonaKind
import whaleui.nightsky.ltd.domain.settings.SecretProvider
import whaleui.nightsky.ltd.ui.theme.WhaleTheme

// 从 SettingsScreen.kt 拆出来（R1 / W-051，只搬代码不改实现）

// ---------------------------------------------------------------------------
// 顶栏 / 分组
// ---------------------------------------------------------------------------

@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 8.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            WhaleIcon(WhaleIcons.Back, tint = colors.textPrimary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = title,
            style = SettingsType.pageTitle,
            color = colors.textPrimary,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 音色 / 语速
// ---------------------------------------------------------------------------

/** 音色行：点开音色弹层选；试听只在弹层每一行（D6，不在这里重复放按钮）。 */
@Composable
internal fun VoiceCell(
    state: SettingsUiState,
    onClick: () -> Unit,
) {
    val colors = WhaleTheme.colors
    val voice = state.voice
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CellDoubleHeight)
            .clickable(onClick = onClick)
            .padding(start = RowStart, end = RowEnd, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = "音色", style = SettingsType.cellTitle, color = colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                text = voice?.displayName() ?: "还没有选音色",
                style = SettingsType.cellSubtitle,
                color = colors.textCaption,
            )
        }
        RowChevron()
    }
}

/** 行尾「›」：跳到别处的行统一用它。 */
@Composable
internal fun RowChevron() {
    WhaleIcon(WhaleIcons.ChevronRight, tint = WhaleTheme.colors.textCaption, modifier = Modifier.size(17.dp))
}

@Composable
internal fun SpeechRateCell(
    rate: Float,
    onRateChange: (Float) -> Unit,
) {
    val colors = WhaleTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = RowStart, end = RowEnd, top = 12.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "语速", style = SettingsType.cellTitle, color = colors.textPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                text = "%.1f×".format(rate),
                style = SettingsType.cellValue,
                color = colors.accent,
            )
        }
        Spacer(Modifier.height(2.dp))
        SpeechRateSlider(value = rate, onValueChange = onRateChange)
        // 「正常」对着 1.0× 在滑条上的位置（0.5–2.0 的 1/3 处），不放正中。
        Box(Modifier.fillMaxWidth()) {
            Text(
                text = "慢 0.5×",
                style = SettingsType.info,
                color = colors.textCaption,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                text = "正常",
                style = SettingsType.info,
                color = colors.textCaption,
                modifier = Modifier.align(BiasAlignment(horizontalBias = -1f / 3f, verticalBias = 0f)),
            )
            Text(
                text = "快 2×",
                style = SettingsType.info,
                color = colors.textCaption,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

