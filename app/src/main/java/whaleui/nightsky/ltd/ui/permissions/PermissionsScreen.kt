package whaleui.nightsky.ltd.ui.permissions

import whaleui.nightsky.ltd.ui.components.CellDivider
import whaleui.nightsky.ltd.ui.components.SectionCard
import whaleui.nightsky.ltd.ui.components.SectionGap
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing

import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.domain.settings.PermissionState
import whaleui.nightsky.ltd.domain.settings.RequiredPermission
import whaleui.nightsky.ltd.ui.settings.SettingsCellHeight
import whaleui.nightsky.ltd.ui.settings.SettingsFilledButton
import whaleui.nightsky.ltd.ui.settings.SettingsPageTopBar
import whaleui.nightsky.ltd.ui.settings.SettingsRowEnd
import whaleui.nightsky.ltd.ui.settings.SettingsRowStart
import whaleui.nightsky.ltd.ui.settings.SettingsSwitch
import whaleui.nightsky.ltd.ui.settings.SettingsTextButton
import whaleui.nightsky.ltd.ui.settings.SettingsToastBar
import whaleui.nightsky.ltd.ui.settings.SettingsType
import whaleui.nightsky.ltd.ui.theme.PageBackgroundArgb
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.ui.theme.WhaleUITheme

// ---------------------------------------------------------------------------
// 权限与后台常驻。
//
// 视觉和设置页同一套：C 起和设置页一起改成卡片分组（组件库 SectionCard / CellDivider），
// 行内控件复用 SettingsComponents 里的 SettingsType / SettingsSwitch / 胶囊按钮 / 顶栏。
//
// 动效：
// - 顶部提示条在「有 N 项没开」和「都开好了」之间切换（220ms 淡入淡出）；
// - 提示条里的 N 用 320ms 整数动画，不会瞬变；
// - 每行右侧状态（已开启 / 去开启 / 去检查 / 无需授权）切换时 180ms 淡入 + 缩放入场。
// ---------------------------------------------------------------------------

/**
 * 权限与后台常驻页。无状态：状态来自 [PermissionsUiState]，所有动作从回调走。
 */
@Composable
fun PermissionsScreen(
    state: PermissionsUiState,
    onBack: () -> Unit,
    onRequestPermission: (RequiredPermission) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onKeepAliveChange: (Boolean) -> Unit,
    onDismissToast: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "权限与后台常驻",
) {
    val colors = WhaleTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgPage),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            SettingsPageTopBar(title = title, onBack = onBack)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(6.dp))
                // 第一次检查回来之前不画提示条，避免闪一下「有 0 项没开」。
                if (state.statuses.isNotEmpty()) {
                    PermissionBanner(state)
                }
                state.groups.forEach { group ->
                    SectionGap()
                    SectionCard(header = group.title) {
                        group.rows.forEachIndexed { rowIndex, row ->
                            if (rowIndex > 0) CellDivider()
                            PermissionRow(
                                row = row,
                                keepAliveChecked = state.keepAliveChecked,
                                keepAliveBusy = state.keepAliveBusy,
                                onGrant = { onRequestPermission(row.permission) },
                                onOpenBatterySettings = onOpenBatterySettings,
                                onKeepAliveChange = onKeepAliveChange,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(WhaleSpacing.xxl))
            }
        }

        state.toast?.let { toast ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                SettingsToastBar(
                    toast = toast,
                    onAction = onDismissToast,
                    onExpired = onDismissToast,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 顶部提示条
// ---------------------------------------------------------------------------

@Composable
private fun PermissionBanner(state: PermissionsUiState) {
    val colors = WhaleTheme.colors
    val animatedCount by animateIntAsState(
        targetValue = state.deniedCount,
        animationSpec = tween(durationMillis = WhaleDuration.Scrim, easing = TgEasing.Decelerate),
        label = "permissionDeniedCount",
    )
    AnimatedContent(
        targetState = state.allGranted,
        transitionSpec = {
            fadeIn(tween(WhaleDuration.Send, easing = TgEasing.Decelerate)) togetherWith
                fadeOut(tween(WhaleDuration.Fast, easing = TgEasing.Decelerate))
        },
        label = "permissionBanner",
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WhaleSpacing.cardOuter),
    ) { allGranted ->
        val borderColor = if (allGranted) colors.success.copy(alpha = WhaleAlpha.Disabled) else colors.divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(WhaleShapes.card)
                .background(colors.surface)
                .border(0.5.dp, borderColor, WhaleShapes.card)
                .padding(horizontal = WhaleSpacing.cellHorizontal, vertical = WhaleSpacing.l),
        ) {
            Text(
                text = if (allGranted) {
                    PermissionBannerAllGood
                } else {
                    permissionBannerDeniedText(animatedCount)
                },
                style = SettingsType.info,
                color = if (allGranted) colors.success else colors.textSecondary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 行
// ---------------------------------------------------------------------------

@Composable
private fun PermissionRow(
    row: PermissionRowUi,
    keepAliveChecked: Boolean,
    keepAliveBusy: Boolean,
    onGrant: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onKeepAliveChange: (Boolean) -> Unit,
) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsCellHeight)
            .padding(start = SettingsRowStart, end = SettingsRowEnd, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = row.title, style = SettingsType.cellTitle, color = colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(text = row.subtitle, style = SettingsType.cellSubtitle, color = colors.textCaption)
            row.note?.let { note ->
                Spacer(Modifier.height(3.dp))
                Text(
                    text = note,
                    style = SettingsType.cellSubtitle,
                    color = colors.textCaption.copy(alpha = WhaleAlpha.Glass),
                )
            }
            if (row.permission == RequiredPermission.BatteryOptimization) {
                Spacer(Modifier.height(2.dp))
                // 文字按钮自带左右内距：往回挪，让字和上面的说明左对齐
                SettingsTextButton(
                    label = "去检查",
                    color = colors.accent,
                    onClick = onOpenBatterySettings,
                    modifier = Modifier.offset(x = -WhaleSpacing.m),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        when (row.permission) {
            RequiredPermission.KeepAlive -> SettingsSwitch(
                checked = keepAliveChecked,
                onCheckedChange = onKeepAliveChange,
                enabled = !keepAliveBusy,
            )

            else -> PermissionStateControl(state = row.state, onGrant = onGrant)
        }
    }
}

/** 右侧状态：绿「已开启」/ 蓝胶囊「去开启」/ 蓝字「去检查」/ 灰「无需授权」。 */
@Composable
private fun PermissionStateControl(state: PermissionState, onGrant: () -> Unit) {
    val colors = WhaleTheme.colors
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            (
                fadeIn(tween(WhaleDuration.Short, easing = TgEasing.Decelerate)) +
                    scaleIn(
                        initialScale = 0.92f,
                        animationSpec = tween(WhaleDuration.Short, easing = TgEasing.Decelerate),
                    )
                ) togetherWith (
                fadeOut(tween(WhaleDuration.Dismiss, easing = TgEasing.Decelerate)) +
                    scaleOut(
                        targetScale = 0.92f,
                        animationSpec = tween(WhaleDuration.Dismiss, easing = TgEasing.Decelerate),
                    )
                )
        },
        label = "permissionStateControl",
    ) { target ->
        when (target) {
            PermissionState.Granted -> Text(
                text = "已开启",
                style = SettingsType.cellValue,
                color = colors.success,
            )

            PermissionState.Denied -> SettingsFilledButton(
                label = "去开启",
                enabled = true,
                onClick = onGrant,
            )

            // §1.5 不再用「无法检测」：系统查不到状态时只给入口
            PermissionState.Unknown -> SettingsTextButton(
                label = "去检查",
                color = colors.accent,
                onClick = onGrant,
                // 字的右边和「已开启」等右值对齐
                modifier = Modifier.offset(x = WhaleSpacing.m),
            )

            PermissionState.NotRequired -> Text(
                text = "无需授权",
                style = SettingsType.cellValue,
                color = colors.textCaption,
            )
        }
    }
}
