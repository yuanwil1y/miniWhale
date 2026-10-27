package whaleui.nightsky.ltd.ui.advanced

import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.components.WhaleDialogButton
import whaleui.nightsky.ltd.ui.components.WhaleDialog
import whaleui.nightsky.ltd.domain.settings.SecretProvider
import whaleui.nightsky.ltd.ui.components.WhaleButton
import whaleui.nightsky.ltd.ui.components.WhaleButtonStyle
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.rerere.ai.core.ReasoningLevel
import androidx.compose.ui.graphics.Color
import whaleui.nightsky.ltd.ui.components.CellDivider
import whaleui.nightsky.ltd.ui.components.InfoFootnote
import whaleui.nightsky.ltd.ui.components.SectionCard
import whaleui.nightsky.ltd.ui.components.SectionGap
import whaleui.nightsky.ltd.ui.settings.SettingsCellHeight
import whaleui.nightsky.ltd.ui.settings.SettingsFilledButton
import whaleui.nightsky.ltd.ui.settings.SettingsPageTopBar
import whaleui.nightsky.ltd.ui.settings.SettingsRowEnd
import whaleui.nightsky.ltd.ui.settings.SettingsRowStart
import whaleui.nightsky.ltd.ui.settings.SettingsSheet
import whaleui.nightsky.ltd.ui.settings.SettingsSpinner
import whaleui.nightsky.ltd.ui.settings.SettingsSwitch
import whaleui.nightsky.ltd.ui.settings.SettingsTextButton
import whaleui.nightsky.ltd.ui.settings.SettingsToastBar
import whaleui.nightsky.ltd.ui.settings.SettingsToastKind
import whaleui.nightsky.ltd.ui.settings.SettingsType
import whaleui.nightsky.ltd.ui.theme.PageBackgroundArgb
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.ui.theme.WhaleUITheme

// ---------------------------------------------------------------------------
// 高级设置（D2）：入口列表 + 四块（供应商 / 模型 / 代理 / 日志）。
//
// 视觉和设置页、权限页同一套：C 起改成卡片分组（SectionCard / CellDivider / InfoFootnote）；
// 组件全部来自 SettingsComponents（顶栏、分组标题、开关、底部弹层、提示条、按钮、字形）。
// 可编辑的值一律「点行 → 输入框对话框」；列表选择用底部弹层。
// 不依赖任何原版 RikkaHub 页面的 Composable。
// ---------------------------------------------------------------------------

private val FieldShape = RoundedCornerShape(WhaleRadius.iconTile)
private val Mono = FontFamily.Monospace

@Composable
fun AdvancedSettingsScreen(
    state: AdvancedUiState,
    actions: AdvancedActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    BackHandler(enabled = state.sheet != null) { actions.closeSheet() }
    BackHandler(enabled = state.sheet == null && state.logDetail != null) { actions.closeLogDetail() }
    BackHandler(enabled = state.sheet == null && state.logDetail == null && state.section != null) {
        actions.closeSection()
    }

    val copyLog: (String) -> Unit = { id ->
        scope.launch {
            val text = actions.copyText(id)
            if (text == null) {
                actions.showToast("这条日志已经没有了", SettingsToastKind.Error)
            } else if (copyToClipboard(context, text)) {
                actions.showToast("已复制（密钥已抹掉）", SettingsToastKind.Success)
            } else {
                actions.showToast("复制失败：内容太大", SettingsToastKind.Error)
            }
        }
    }

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
            SettingsPageTopBar(
                title = state.section?.title ?: "高级设置",
                onBack = { if (state.section != null) actions.closeSection() else onBack() },
            )
            AnimatedContent(
                targetState = state.section,
                transitionSpec = {
                    fadeIn(tween(WhaleDuration.Fast, easing = TgEasing.Decelerate)) togetherWith
                        fadeOut(tween(WhaleDuration.Dismiss, easing = TgEasing.Decelerate))
                },
                label = "advancedSection",
                modifier = Modifier.weight(1f),
            ) { section ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.height(6.dp))
                    when (section) {
                        null -> HubContent(state, actions)
                        AdvancedSection.Providers -> ProvidersContent(state, actions)
                        AdvancedSection.Model -> ModelContent(state, actions)
                        AdvancedSection.Proxy -> ProxyContent(state, actions)
                        AdvancedSection.Logs -> LogsContent(state, actions, copyLog)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // ------------------------------ 弹层 ------------------------------
        when (state.sheet) {
            AdvancedSheet.ModelPicker -> ModelPickerSheet(state, actions)
            AdvancedSheet.ReasoningPicker -> ChoiceSheet(
                title = "思考强度",
                note = "DeepSeek 上「中」和「高」都按 high 发送；「自动」由模型自己决定。",
                options = ReasoningChoices.map { it.label to (it == state.reasoningLevel) },
                onSelect = { index -> actions.setReasoningLevel(ReasoningChoices[index]) },
                onDismiss = actions::closeSheet,
            )

            AdvancedSheet.ProxyType -> ChoiceSheet(
                title = "代理类型",
                note = null,
                options = ProxyType.entries.map { it.label to (it == state.proxy.type) },
                onSelect = { index -> actions.setProxyType(ProxyType.entries[index]) },
                onDismiss = actions::closeSheet,
            )

            null -> Unit
        }
        state.logDetail?.let { detail ->
            if (state.sheet == null) {
                LogDetailSheet(detail = detail, onCopy = { copyLog(detail.id) }, onDismiss = actions::closeLogDetail)
            }
        }
        state.editor?.let { editor ->
            // 密钥已填写时对话框里多一个「清除」（先确认）；没填就没有
            val keyProvider = editor.field.keyProvider
            InputDialog(
                editor = editor,
                onDismiss = actions::dismissEditor,
                onSubmit = actions::submitEditor,
                onClear = keyProvider
                    ?.takeIf { state.keyStatus(it).saved }
                    ?.let { provider -> { actions.requestClearSecret(provider) } },
            )
        }
        state.clearKeyConfirm?.let { provider ->
            WhaleDialog(
                onDismiss = actions::cancelClearSecret,
                title = "清除 ${provider.title} 密钥？",
                text = if (provider == SecretProvider.DeepSeek) {
                    "清除后她就回不了消息，要重新填写才行。"
                } else {
                    "清除后语音和作图就用不了了，要重新填写才行。"
                },
                buttons = listOf(
                    WhaleDialogButton("取消", actions::cancelClearSecret),
                    WhaleDialogButton("清除", { actions.clearSecret(provider) }, danger = true),
                ),
            )
        }

        // ------------------------------ 提示条 ------------------------------
        state.toast?.let { toast ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                SettingsToastBar(toast = toast, onAction = actions::dismissToast, onExpired = actions::dismissToast)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 入口列表
// ---------------------------------------------------------------------------

@Composable
private fun HubContent(state: AdvancedUiState, actions: AdvancedActions) {
    InfoText("这里改的是她连接模型的方式。改错了她可能回不了消息，改之前先记下原来的值。")
    SectionCard(header = "连接") {
        NavRow(
            title = "供应商",
            subtitle = "DeepSeek · MiniMax 的接口地址与密钥",
            trailing = if (state.deepSeekKey.saved) null else "没填密钥",
            trailingWarning = !state.deepSeekKey.saved,
            onClick = { actions.openSection(AdvancedSection.Providers) },
        )
        CellDivider()
        NavRow(
            title = "模型",
            subtitle = state.modelSummary,
            onClick = { actions.openSection(AdvancedSection.Model) },
        )
        CellDivider()
        NavRow(
            title = "代理",
            subtitle = state.proxy.summary,
            trailingWarning = state.proxy.incomplete,
            onClick = { actions.openSection(AdvancedSection.Proxy) },
        )
    }
    SectionGap()
    SectionCard(header = "排查") {
        NavRow(
            title = "日志",
            subtitle = "最近的请求与应用日志（只在内存里）",
            onClick = { actions.openSection(AdvancedSection.Logs) },
        )
    }
}

// ---------------------------------------------------------------------------
// 供应商
// ---------------------------------------------------------------------------

@Composable
private fun ProvidersContent(state: AdvancedUiState, actions: AdvancedActions) {
    // 全 App 唯一填密钥的地方（D，用户定）：点「密钥」行弹密钥对话框
    SectionCard(header = "DeepSeek · 聊天") {
        if (state.deepSeekProviderMissing) {
            InfoText("找不到内置的 DeepSeek 供应商。", warning = true)
        }
        FieldRow(
            title = "接口地址",
            value = state.deepSeekBaseUrl.ifEmpty { "—" },
            mono = true,
            onClick = { actions.openEditor(AdvancedField.DeepSeekBaseUrl) },
        )
        CellDivider()
        // 没填时她回不了消息 → danger（README §1.5）；只显示已填写 / 未填写，不露任何一位
        NavRow(
            title = "密钥",
            subtitle = null,
            trailing = state.deepSeekKey.label,
            trailingWarning = !state.deepSeekKey.saved,
            trailingColor = WhaleTheme.colors.textSecondary,
            onClick = { actions.openEditor(AdvancedField.DeepSeekKey) },
        )
    }
    InfoText("密钥（API Key）只加密保存在这台手机上。改地址后，下一条消息起生效。")
    SectionCard(header = "MiniMax · 语音与作图") {
        FieldRow(
            title = "接口地址",
            value = state.miniMax.baseUrl,
            mono = true,
            onClick = { actions.openEditor(AdvancedField.MiniMaxBaseUrl) },
        )
        CellDivider()
        // 没填只是没有语音和作图，不标红
        NavRow(
            title = "密钥",
            subtitle = null,
            trailing = state.miniMaxKey.label,
            trailingColor = WhaleTheme.colors.textSecondary,
            onClick = { actions.openEditor(AdvancedField.MiniMaxKey) },
        )
        CellDivider()
        FieldRow(
            title = "语音模型",
            value = state.miniMax.speechModel,
            mono = true,
            onClick = { actions.openEditor(AdvancedField.MiniMaxSpeechModel) },
        )
        CellDivider()
        FieldRow(
            title = "作图模型",
            value = state.miniMax.imageModel,
            mono = true,
            onClick = { actions.openEditor(AdvancedField.MiniMaxImageModel) },
        )
    }
    InfoText("每次调用都现读，改完立即生效。留空保存 = 恢复默认值。")
}

// ---------------------------------------------------------------------------
// 模型
// ---------------------------------------------------------------------------

@Composable
private fun ModelContent(state: AdvancedUiState, actions: AdvancedActions) {
    SectionCard(header = "她用的聊天模型") {
        NavRow(
            title = "聊天模型",
            subtitle = "DeepSeek 供应商下的模型；刷新列表、手动填写都在这里面",
            trailing = state.currentModelId ?: "未选择",
            trailingMono = true,
            onClick = { actions.openSheet(AdvancedSheet.ModelPicker) },
        )
    }
    SectionGap()

    SectionCard(header = "思考") {
        SwitchRow(
            title = "思考",
            subtitle = if (state.reasoningSupported) "回复前先想一想，会慢一点" else "当前模型不支持思考",
            checked = state.reasoningSupported && state.reasoningLevel.isEnabled,
            enabled = state.reasoningSupported,
            onCheckedChange = actions::setReasoningEnabled,
        )
        CellDivider()
        NavRow(
            title = "思考强度",
            subtitle = null,
            trailing = if (state.reasoningLevel.isEnabled) state.reasoningLevel.label else "已关闭",
            enabled = state.reasoningSupported && state.reasoningLevel.isEnabled,
            onClick = { actions.openSheet(AdvancedSheet.ReasoningPicker) },
        )
    }
    InfoText("改动会写回她的助手设置，并同步到你们的对话，下一条消息起生效。")
}

@Composable
private fun ModelPickerSheet(state: AdvancedUiState, actions: AdvancedActions) {
    val colors = WhaleTheme.colors
    SettingsSheet(onDismiss = actions::closeSheet) {
        Text(text = "选择聊天模型", style = SettingsType.sheetTitle, color = colors.textPrimary)
        Spacer(Modifier.height(12.dp))
        if (state.models.isEmpty()) {
            Text(text = "列表是空的，刷新或手动填写。", style = SettingsType.info, color = colors.textCaption)
        }
        state.models.forEach { option ->
            val selected = option.modelId == state.currentModelId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(WhaleRadius.iconTile))
                    .clickable { actions.selectModel(option.modelId) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = option.modelId,
                        style = WhaleTheme.type.mono,
                        color = if (selected) colors.accent else colors.textPrimary,
                    )
                    val tags = buildList {
                        if (option.supportsReasoning) add("支持思考")
                        if (option.uuid == null) add("在线列表 · 选中后加入")
                    }
                    if (tags.isNotEmpty()) {
                        Text(text = tags.joinToString(" · "), style = SettingsType.cellSubtitle, color = colors.textCaption)
                    }
                }
                if (selected) {
                    WhaleIcon(WhaleIcons.Check, tint = colors.accent, modifier = Modifier.size(22.dp))
                } else if (option.removable) {
                    SettingsTextButton(
                        label = "移除",
                        color = WhaleTheme.colors.danger,
                        onClick = { actions.removeModel(option.modelId) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsTextButton(
                label = if (state.modelsRefreshing) "刷新中…" else "刷新在线列表",
                color = colors.accent,
                onClick = actions::refreshModels,
            )
            Spacer(Modifier.weight(1f))
            SettingsTextButton(
                label = "手动填写",
                color = colors.accent,
                onClick = { actions.openEditor(AdvancedField.CustomModelId) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 代理
// ---------------------------------------------------------------------------

@Composable
private fun ProxyContent(state: AdvancedUiState, actions: AdvancedActions) {
    val proxy = state.proxy
    SectionCard(header = "代理") {
        SwitchRow(
            title = "使用代理",
            subtitle = proxy.summary,
            subtitleWarning = proxy.incomplete,
            checked = proxy.enabled,
            onCheckedChange = actions::setProxyEnabled,
        )
        CellDivider()
        NavRow(
            title = "类型",
            subtitle = null,
            trailing = proxy.type.label,
            onClick = { actions.openSheet(AdvancedSheet.ProxyType) },
        )
        CellDivider()
        FieldRow(
            title = "地址",
            value = proxy.host.ifEmpty { "未填写" },
            mono = proxy.host.isNotEmpty(),
            onClick = { actions.openEditor(AdvancedField.ProxyHost) },
        )
        CellDivider()
        FieldRow(
            title = "端口",
            value = proxy.port.ifEmpty { "未填写" },
            mono = proxy.port.isNotEmpty(),
            onClick = { actions.openEditor(AdvancedField.ProxyPort) },
        )
    }
    SectionGap()
    SectionCard(header = "认证（可选）") {
        FieldRow(
            title = "用户名",
            value = proxy.username.ifEmpty { "未填写" },
            mono = false,
            onClick = { actions.openEditor(AdvancedField.ProxyUsername) },
        )
        CellDivider()
        NavRow(
            title = "密码",
            subtitle = null,
            trailing = if (proxy.passwordSet) "已填写" else "未填写",
            onClick = { actions.openEditor(AdvancedField.ProxyPassword) },
        )
    }
    InfoText(
        "改完立即生效：下一次请求就走新设置，不用重启。聊天、搜索、语音、作图都走这个代理。" +
            "关掉开关时地址会保留在本页，退出 App 后需要重填。",
    )
}

// ---------------------------------------------------------------------------
// 日志
// ---------------------------------------------------------------------------

@Composable
private fun LogsContent(state: AdvancedUiState, actions: AdvancedActions, onCopy: (String) -> Unit) {
    val colors = WhaleTheme.colors
    SectionCard {
        SwitchRow(
            title = "记录请求",
            subtitle = "记下每次请求的地址、请求体和结果；只存在内存里，重启后自动关闭",
            checked = state.requestLoggingEnabled,
            onCheckedChange = actions::setRequestLogging,
        )
        CellDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SettingsRowStart - 14.dp, end = SettingsRowEnd, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsTextButton(label = "刷新", color = colors.accent, onClick = actions::refreshLogs)
            SettingsTextButton(label = "清空", color = WhaleTheme.colors.danger, onClick = actions::clearLogs)
            Spacer(Modifier.weight(1f))
            Text(text = "${state.logs.size} 条", style = SettingsType.cellSubtitle, color = colors.textCaption)
        }
    }
    SectionGap()
    if (state.logs.isEmpty()) {
        InfoText(
            if (state.requestLoggingEnabled) {
                "还没有日志。发一条消息后点「刷新」。"
            } else {
                "还没有日志。要看请求，先打开「记录请求」再发一条消息。"
            },
        )
    }
    if (state.logs.isNotEmpty()) {
        SectionCard {
            state.logs.forEachIndexed { index, item ->
                if (index > 0) CellDivider()
                LogRow(item = item, onOpen = { actions.openLogDetail(item.id) }, onCopy = { onCopy(item.id) })
            }
        }
    }
}

@Composable
private fun LogRow(item: AdvancedLogItem, onOpen: () -> Unit, onCopy: () -> Unit) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = SettingsRowStart, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (item.isRequest) "请求" else "应用",
                    style = SettingsType.fieldLabel,
                    color = colors.accent,
                )
                Spacer(Modifier.width(8.dp))
                Text(text = item.time, style = SettingsType.fieldLabel, color = colors.textCaption)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = item.title,
                style = SettingsType.cellSubtitle.copy(fontFamily = Mono),
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.model?.let { model ->
                Text(
                    text = "模型 $model",
                    style = SettingsType.cellSubtitle.copy(fontFamily = Mono),
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
            item.status?.let { status ->
                Text(
                    text = status,
                    style = SettingsType.cellSubtitle,
                    color = if (item.failed) WhaleTheme.colors.danger else colors.textCaption,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SettingsTextButton(label = "复制", color = colors.accent, onClick = onCopy)
    }
}

@Composable
private fun LogDetailSheet(detail: AdvancedLogDetail, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val colors = WhaleTheme.colors
    SettingsSheet(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "日志详情",
                style = SettingsType.sheetTitle,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            SettingsTextButton(label = "复制", color = colors.accent, onClick = onCopy)
        }
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Text(
                text = detail.text,
                style = WhaleTheme.type.mono,
                color = colors.textSecondary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 行
// ---------------------------------------------------------------------------

/** 标题 + 副标题，右边一个值和箭头。 */
@Composable
private fun NavRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    trailing: String? = null,
    trailingWarning: Boolean = false,
    trailingMono: Boolean = false,
    enabled: Boolean = true,
    /** 非警告时值的颜色；默认 accent（可点的值），只读状态用 textSecondary */
    trailingColor: Color? = null,
) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (subtitle == null) 52.dp else SettingsCellHeight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = SettingsRowStart, end = SettingsRowEnd, top = 10.dp, bottom = 10.dp)
            .alpha(if (enabled) WhaleAlpha.Visible else WhaleAlpha.Disabled),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = SettingsType.cellTitle, color = colors.textPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = SettingsType.cellSubtitle,
                    color = if (trailingWarning && trailing == null) WhaleTheme.colors.danger else colors.textCaption,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = trailing,
                style = if (trailingMono) WhaleTheme.type.mono else SettingsType.cellValue,
                color = if (trailingWarning) WhaleTheme.colors.danger else trailingColor ?: colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 190.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        WhaleIcon(WhaleIcons.ChevronRight, tint = colors.textCaption, modifier = Modifier.size(17.dp))
    }
}

/** 值比较长的字段（地址、模型名）：值放在标题下面一整行。 */
@Composable
private fun FieldRow(title: String, value: String, mono: Boolean, onClick: () -> Unit) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsCellHeight)
            .clickable(onClick = onClick)
            .padding(start = SettingsRowStart, end = SettingsRowEnd, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = SettingsType.cellTitle, color = colors.textPrimary)
            Spacer(Modifier.height(3.dp))
            Text(
                text = value,
                style = if (mono) SettingsType.cellSubtitle.copy(fontFamily = Mono) else SettingsType.cellSubtitle,
                color = colors.accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        WhaleIcon(WhaleIcons.ChevronRight, tint = colors.textCaption, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    subtitleWarning: Boolean = false,
) {
    val colors = WhaleTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsCellHeight)
            .padding(start = SettingsRowStart, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(if (enabled) WhaleAlpha.Visible else WhaleAlpha.Disabled)) {
            Text(text = title, style = SettingsType.cellTitle, color = colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = SettingsType.cellSubtitle,
                color = if (subtitleWarning) WhaleTheme.colors.danger else colors.textCaption,
            )
        }
        Spacer(Modifier.width(8.dp))
        SettingsSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** 组下说明 = 组件库 [InfoFootnote]；[warning] 用 danger 色。 */
@Composable
private fun InfoText(text: String, warning: Boolean = false) {
    InfoFootnote(text = text, color = if (warning) WhaleTheme.colors.danger else WhaleTheme.colors.textCaption)
}

@Composable
private fun ChoiceSheet(
    title: String,
    note: String?,
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = WhaleTheme.colors
    SettingsSheet(onDismiss = onDismiss) {
        Text(text = title, style = SettingsType.sheetTitle, color = colors.textPrimary)
        if (note != null) {
            Spacer(Modifier.height(6.dp))
            Text(text = note, style = SettingsType.cellSubtitle, color = colors.textCaption)
        }
        Spacer(Modifier.height(10.dp))
        options.forEachIndexed { index, (label, selected) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(WhaleRadius.iconTile))
                    .clickable { onSelect(index) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = SettingsType.listItemTitle,
                    color = if (selected) colors.accent else colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (selected) WhaleIcon(WhaleIcons.Check, tint = colors.accent, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** 改一个字段 = 组件库 [WhaleDialog] + 输入框（U0）。 */
@Composable
private fun InputDialog(
    editor: AdvancedEditor,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onClear: (() -> Unit)?,
) {
    val colors = WhaleTheme.colors
    val field = editor.field
    var text by remember(editor) { mutableStateOf(editor.initial) }
    var revealed by remember(editor) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    WhaleDialog(
        onDismiss = onDismiss,
        title = field.title,
        text = field.hint.ifEmpty { null },
        buttons = buildList {
            if (onClear != null) add(WhaleDialogButton("清除", onClear, danger = true))
            add(WhaleDialogButton("取消", onDismiss))
            add(WhaleDialogButton("保存", { onSubmit(text) }))
        },
    ) {
        Spacer(Modifier.height(WhaleSpacing.s))
        Row(
            modifier = Modifier
                .padding(horizontal = WhaleSpacing.dialogHorizontal)
                .fillMaxWidth()
                .height(46.dp)
                .clip(FieldShape)
                .background(colors.surfaceInput)
                .border(1.dp, colors.inputLineActive, FieldShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty() && field.placeholder != null) {
                    Text(
                        text = field.placeholder,
                        style = SettingsType.fieldText,
                        color = colors.textCaption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = SettingsType.fieldText.copy(
                        color = colors.textPrimary,
                        fontFamily = if (field.secret) FontFamily.Default else Mono,
                    ),
                    cursorBrush = SolidColor(colors.accentFill),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when {
                            field.numeric -> KeyboardType.Number
                            field.secret -> KeyboardType.Password
                            else -> KeyboardType.Uri
                        },
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit(text) }),
                    visualTransformation = if (field.secret && !revealed) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                )
            }
            if (field.secret) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { revealed = !revealed }
                        .semantics { contentDescription = if (revealed) "隐藏" else "显示" },
                    contentAlignment = Alignment.Center,
                ) {
                    WhaleIcon(if (revealed) WhaleIcons.Eye else WhaleIcons.EyeOff, tint = if (revealed) colors.accent else colors.textSecondary, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
    LaunchedEffect(editor) { runCatching { focusRequester.requestFocus() } }
}

private fun copyToClipboard(context: Context, text: String): Boolean = try {
    val manager = context.getSystemService(ClipboardManager::class.java)
    manager?.setPrimaryClip(ClipData.newPlainText("miniWhale 日志", text.take(MAX_COPY_CHARS)))
    manager != null
} catch (e: RuntimeException) {
    // 内容过大时 Binder 抛 TransactionTooLargeException（包成 RuntimeException）
    false
}

private const val MAX_COPY_CHARS = 100_000
