package whaleui.nightsky.ltd.ui.settings

import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.components.WhaleButtonStyle
import whaleui.nightsky.ltd.ui.components.WhaleButton
import androidx.compose.ui.graphics.vector.ImageVector
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.ui.theme.WhaleTheme

// 从 SettingsScreen.kt 拆出来（R1 / W-051，只搬代码不改实现）

// ---------------------------------------------------------------------------
// 主动消息 / 数据 / 权限
// ---------------------------------------------------------------------------

/** 「导入日记 / 表情包」那一行：标题 + 说明 + 右边一个按钮（任务书 4.7 / round5 3.2）。 */
@Composable
internal fun SettingsActionRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
    /** 按钮里的小图标；默认是「导入」，表情包那行换成笑脸。 */
    actionIcon: ImageVector = WhaleIcons.Import,
    /** 危险操作（清空数据）：红色描边 */
    danger: Boolean = false,
) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CellDoubleHeight)
            .padding(start = RowStart, end = RowEnd, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = SettingsType.cellTitle, color = colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, style = SettingsType.cellSubtitle, color = colors.textCaption)
        }
        Spacer(Modifier.width(10.dp))
        WhaleButton(
            text = actionLabel,
            onClick = onClick,
            style = WhaleButtonStyle.Secondary,
            icon = actionIcon,
            textColor = if (danger) colors.danger else null,
            compact = true,
        )
    }
}

@Composable
/** 「常住城市」：手填，失焦或按 Done 时保存（round4 4.3）。 */
internal fun HomeCityCell(
    city: String,
    onCommit: (String) -> Unit,
) {
    val colors = WhaleTheme.colors
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var draft by remember(city) { mutableStateOf(city) }
    var hadFocus by remember(city) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CellDoubleHeight)
            .padding(start = RowStart, end = RowEnd, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = "常住城市", style = SettingsType.cellTitle, color = colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            hadFocus = true
                        } else if (hadFocus) {
                            hadFocus = false
                            if (draft.trim() != city.trim()) onCommit(draft)
                        }
                    },
                singleLine = true,
                textStyle = SettingsType.fieldText.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accentFill),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (draft.trim() != city.trim()) onCommit(draft)
                    },
                ),
                decorationBox = { inner ->
                    if (draft.isEmpty()) {
                        Text(
                            text = "填了她才知道你那边的天气",
                            style = SettingsType.fieldText.copy(color = colors.textCaption),
                        )
                    }
                    inner()
                },
            )
        }
    }
}

@Composable
internal fun SwitchCell(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CellDoubleHeight)
            .padding(start = RowStart, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = SettingsType.cellTitle, color = colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, style = SettingsType.cellSubtitle, color = colors.textCaption)
        }
        Spacer(Modifier.width(8.dp))
        SettingsSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun QuietHoursCell(
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = WhaleTheme.colors
    val alpha = if (enabled) WhaleAlpha.Visible else WhaleAlpha.Disabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CellDoubleHeight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = RowStart, end = RowEnd, top = 10.dp, bottom = 10.dp)
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = "免打扰时段", style = SettingsType.cellTitle, color = colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(text = "这段时间她不会主动发消息", style = SettingsType.cellSubtitle, color = colors.textCaption)
        }
        Text(text = value, style = SettingsType.cellValue, color = colors.accent)
        Spacer(Modifier.width(6.dp))
        RowChevron()
    }
}

@Composable
internal fun ExportCell(
    title: String,
    subtitle: String,
    inProgress: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CellDoubleHeight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = RowStart, end = RowEnd, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = SettingsType.cellTitle, color = colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (inProgress) "正在导出…" else subtitle,
                style = SettingsType.cellSubtitle,
                color = if (inProgress) colors.accent else colors.textCaption,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (inProgress) {
            SettingsSpinner(Modifier.size(18.dp), colors.accent)
        } else {
            WhaleIcon(WhaleIcons.Export, tint = colors.textSecondary, modifier = Modifier.size(22.dp))
        }
    }
}

/** 「📥 导入与数据 → 导入」：六个导入合成这一行，点开导入面板（u4 §3.6）。 */
@Composable
internal fun ImportEntryCell(onClick: () -> Unit) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CellDoubleHeight)
            .clickable(onClick = onClick)
            .padding(start = RowStart, end = RowEnd, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = "导入", style = SettingsType.cellTitle, color = colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "人设、日记、记忆、聊天记录、表情包",
                style = SettingsType.cellSubtitle,
                color = colors.textCaption,
            )
        }
        Spacer(Modifier.width(10.dp))
        RowChevron()
    }
}

@Composable
internal fun PermissionsCell(missing: Int, onClick: () -> Unit) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CellDoubleHeight)
            .clickable(onClick = onClick)
            .padding(start = RowStart, end = RowEnd, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = "检查权限", style = SettingsType.cellTitle, color = colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "通知、闹钟和提醒、电池优化、开机自启、后台常驻",
                style = SettingsType.cellSubtitle,
                color = colors.textCaption,
            )
        }
        Text(
            text = if (missing > 0) "$missing 项未开启" else "全部已开启",
            style = SettingsType.cellValue,
            color = if (missing > 0) colors.danger else colors.success,
        )
        Spacer(Modifier.width(6.dp))
        RowChevron()
    }
}

/**
 * 「关于」组里的版本与许可证说明（D8）。
 *
 * S11 删了原版的「关于」页，许可证说明搬到这里：本 App 是 RikkaHub（AGPL-3.0）的衍生作品，
 * 仓库根目录的 LICENSE / NOTICE.md 保留。
 * D：下面加「源代码」（AGPL 的源码提供）和「作者」两行，点了用浏览器打开 GitHub。
 */
@Composable
internal fun AboutCell() {
    val uriHandler = LocalUriHandler.current
    // 没有浏览器时 openUri 会抛 ActivityNotFoundException：点了没反应就好，不崩
    val open: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }
    whaleui.nightsky.ltd.ui.components.DetailCell(
        value = "miniWhale ${me.rerere.rikkahub.BuildConfig.VERSION_NAME}（${me.rerere.rikkahub.BuildConfig.VERSION_CODE}）",
        label = "基于 RikkaHub（AGPL-3.0）",
    )
    whaleui.nightsky.ltd.ui.components.CellDivider()
    whaleui.nightsky.ltd.ui.components.DetailCell(
        value = "源代码",
        label = "github.com/yuanwil1y/miniWhale",
        onClick = { open(SOURCE_URL) },
    )
    whaleui.nightsky.ltd.ui.components.CellDivider()
    whaleui.nightsky.ltd.ui.components.DetailCell(
        value = "作者",
        label = "YuanWil1y · Claude（Anthropic）· DeepSeek",
        onClick = { open(AUTHOR_URL) },
    )
}

private const val SOURCE_URL = "https://github.com/yuanwil1y/miniWhale"
private const val AUTHOR_URL = "https://github.com/yuanwil1y"

/**
 * 「高级设置」入口（S9a）：取代原来长按名字进的原版设置。
 * 密钥只在 高级设置 → 供应商 里填（D），DeepSeek 没填时右边红字「没填密钥」，第一次用的人能找到地方。
 */
@Composable
internal fun AdvancedCell(keyMissing: Boolean, onClick: () -> Unit) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(start = RowStart, end = RowEnd, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 分组标题已是「⚙️ 高级」，行名不再重复；「连接」= 供应商与代理。
        Text(
            text = "连接、模型与日志",
            style = SettingsType.cellTitle,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (keyMissing) {
            Text(text = "没填密钥", style = SettingsType.cellValue, color = colors.danger)
            Spacer(Modifier.width(6.dp))
        }
        RowChevron()
    }
}
