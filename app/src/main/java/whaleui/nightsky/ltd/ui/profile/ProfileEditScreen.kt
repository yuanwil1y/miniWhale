package whaleui.nightsky.ltd.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.domain.settings.HerProfile
import whaleui.nightsky.ltd.ui.components.Cell
import whaleui.nightsky.ltd.ui.components.HerAvatar
import whaleui.nightsky.ltd.ui.components.LocalBulletins
import whaleui.nightsky.ltd.ui.components.WhaleBottomSheet
import whaleui.nightsky.ltd.ui.components.WhaleDialog
import whaleui.nightsky.ltd.ui.components.WhaleDialogButton
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIconButton
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.theme.WhaleTheme

/** 头像右下角相机角标的直径 / 图标大小（Telegram 编辑资料页的相机角标比例）。 */
private val CameraBadge = 30.dp
private val CameraBadgeIcon = 16.dp

/** 下划线：平时 1dp，聚焦 2dp（Telegram 的 EditTextBoldCursor）。 */
private val LineThin = 1.dp
private val LineFocused = 2.dp

/**
 * 编辑她的名字和头像（Telegram「编辑资料」的样子）：
 * 顶栏 返回 · 「编辑」 · ✓；居中大头像（右下相机角标）+ 主色文字按钮「设置新头像」；
 * 下面一个带下划线的「昵称」输入框，快到 20 字时显示剩余字数。
 *
 * 点头像或「设置新头像」弹出选项：从相册选择；自定义了头像时多一项「恢复默认」。
 * 只有点 ✓ 才保存；有改动时返回会先问「放弃修改？」。
 */
@Composable
internal fun ProfileEditScreen(
    state: ProfileEditUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onNicknameChange: (String) -> Unit,
    onOpenAvatarMenu: () -> Unit,
    onCloseAvatarMenu: () -> Unit,
    onPickFromGallery: () -> Unit,
    onResetAvatar: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissDiscard: () -> Unit,
    onNoticeShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    val type = WhaleTheme.type
    BackHandler(onBack = onBack)

    val bulletins = LocalBulletins.current
    LaunchedEffect(state.notice) {
        state.notice?.let {
            bulletins.show(it)
            onNoticeShown()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(colors.bgPage),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(WhaleSpacing.actionBarHeight))
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = WhaleSpacing.l),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 头像 + 右下角相机角标（带一圈页面底色描边），整块可点
                Box(
                    Modifier
                        .clickable(role = Role.Button, enabled = !state.busy, onClick = onOpenAvatarMenu)
                        .semantics { contentDescription = "她的头像，设置新头像" },
                ) {
                    HerAvatar(size = WhaleSpacing.profileAvatar, avatarPath = state.avatarPath)
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(CameraBadge)
                            .clip(WhaleShapes.circle)
                            .background(colors.accentFill)
                            .border(LineFocused, colors.bgPage, WhaleShapes.circle),
                        contentAlignment = Alignment.Center,
                    ) {
                        WhaleIcon(WhaleIcons.Camera, tint = colors.onAccent, size = CameraBadgeIcon)
                    }
                }
                Spacer(Modifier.height(WhaleSpacing.s))
                Text(
                    text = if (state.busy) "正在处理图片…" else "设置新头像",
                    style = type.button,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(WhaleShapes.pill)
                        .clickable(role = Role.Button, enabled = !state.busy, onClick = onOpenAvatarMenu)
                        .padding(horizontal = WhaleSpacing.l, vertical = WhaleSpacing.s),
                )
            }
            Spacer(Modifier.height(WhaleSpacing.xl))
            NicknameField(
                value = state.nickname,
                remaining = state.remaining,
                onValueChange = onNicknameChange,
                modifier = Modifier.padding(horizontal = WhaleSpacing.cellHorizontal),
            )
            Spacer(Modifier.height(WhaleSpacing.xxl))
            Spacer(Modifier.navigationBarsPadding())
        }

        EditTopBar(onBack = onBack, onSave = onSave, saveEnabled = state.loaded && !state.busy)

        if (state.avatarMenuOpen) {
            WhaleBottomSheet(onDismiss = onCloseAvatarMenu, contentPadding = WhaleSpacing.s) {
                Cell(
                    title = "从相册选择",
                    icon = WhaleIcons.Image,
                    iconTint = colors.accent,
                    onClick = {
                        dismiss {
                            onCloseAvatarMenu()
                            onPickFromGallery()
                        }
                    },
                )
                if (state.avatarPath != null) {
                    Cell(
                        title = "恢复默认",
                        icon = WhaleIcons.Undo,
                        iconTint = colors.danger,
                        titleColor = colors.danger,
                        onClick = {
                            dismiss {
                                onCloseAvatarMenu()
                                onResetAvatar()
                            }
                        },
                    )
                }
            }
        }

        if (state.confirmDiscard) {
            WhaleDialog(
                onDismiss = onDismissDiscard,
                title = "放弃修改？",
                text = "改过的名字和头像不会保存。",
                buttons = listOf(
                    WhaleDialogButton("继续编辑", onDismissDiscard),
                    WhaleDialogButton("放弃", onConfirmDiscard, danger = true),
                ),
            )
        }
    }
}

/** 顶栏：返回 · 「编辑」 · ✓（实色底，和资料页收起后的顶栏一致）。 */
@Composable
private fun EditTopBar(onBack: () -> Unit, onSave: () -> Unit, saveEnabled: Boolean) {
    val colors = WhaleTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceBar)
            .statusBarsPadding()
            .height(WhaleSpacing.actionBarHeight)
            .padding(horizontal = WhaleSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WhaleIconButton(icon = WhaleIcons.Back, contentDescription = "返回", onClick = onBack)
        Spacer(Modifier.width(WhaleSpacing.m))
        Text(
            text = "编辑",
            style = WhaleTheme.type.pageTitle,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        WhaleIconButton(
            icon = WhaleIcons.Check,
            contentDescription = "保存",
            onClick = onSave,
            enabled = saveEnabled,
            tint = colors.textPrimary,
        )
    }
}

/**
 * Telegram 式的单行输入：上面一行小字标签（聚焦时主色），下面文字 + 一条下划线（聚焦时主色加粗）。
 * 空的时候显示占位 `default`（保存时空名字就存成它）；剩余字数 [remaining] 不为 null 时显示在右侧。
 */
@Composable
private fun NicknameField(
    value: String,
    remaining: Int?,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    val type = WhaleTheme.type
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier.fillMaxWidth()) {
        Text(
            text = "昵称",
            style = type.cellSubtitle,
            color = if (focused) colors.accent else colors.textCaption,
        )
        Spacer(Modifier.height(WhaleSpacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(text = HerProfile.DEFAULT_NICKNAME, style = type.input, color = colors.textHint)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = type.input.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accentFill),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    interactionSource = interaction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "昵称" },
                )
            }
            if (remaining != null) {
                Spacer(Modifier.width(WhaleSpacing.s))
                Text(
                    text = remaining.toString(),
                    style = type.small,
                    color = if (remaining <= 0) colors.danger else colors.textCaption,
                )
            }
        }
        Spacer(Modifier.height(WhaleSpacing.s))
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused) LineFocused else LineThin)
                .background(if (focused) colors.inputLineActive else colors.inputLine),
        )
    }
}
