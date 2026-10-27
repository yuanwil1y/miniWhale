package whaleui.nightsky.ltd.ui.sticker

import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.components.WhaleButtonStyle
import whaleui.nightsky.ltd.ui.components.WhaleButton
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.components.WhaleDialogButton
import whaleui.nightsky.ltd.ui.components.WhaleDialog
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import java.io.File
import whaleui.nightsky.ltd.sticker.Sticker
import whaleui.nightsky.ltd.sticker.StickerPack
import whaleui.nightsky.ltd.ui.settings.SettingsConfirmDialog
import whaleui.nightsky.ltd.ui.settings.SettingsFilledButton
import whaleui.nightsky.ltd.ui.components.CellDivider
import whaleui.nightsky.ltd.ui.components.EmptyState
import whaleui.nightsky.ltd.ui.components.SectionCard
import whaleui.nightsky.ltd.ui.components.SectionGap
import whaleui.nightsky.ltd.ui.settings.SettingsPageTopBar
import whaleui.nightsky.ltd.ui.settings.SettingsRowEnd
import whaleui.nightsky.ltd.ui.settings.SettingsRowStart
import whaleui.nightsky.ltd.ui.settings.SettingsSheet
import whaleui.nightsky.ltd.ui.settings.SettingsSpinner
import whaleui.nightsky.ltd.ui.settings.SettingsSwitch
import whaleui.nightsky.ltd.ui.settings.SettingsTextButton
import whaleui.nightsky.ltd.ui.settings.SettingsToastBar
import whaleui.nightsky.ltd.ui.settings.SettingsType
import whaleui.nightsky.ltd.ui.theme.PageBackgroundArgb
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.ui.theme.WhaleUITheme

// ---------------------------------------------------------------------------
// 设置 → 表情包管理（任务书 3.2，Telegram 设置页风格）。
//
// 页面结构：
// - 顶栏（复用设置页的 SettingsPageTopBar）；
// - 系列列表：封面 + 名称 + 张数 + 「她能用」开关 + 删除；点行进入系列网格；
// - 系列网格：点一张弹底部弹层，只能「只删除这一张」；
// - 「关于」：每个系列的 source / licenseNote；
// - 底部固定一行版权提示。
//
// 数值参考 Telegram Android 的列表行：
// 行高 58dp、缩略图 40dp、标题 16sp bold、副标题 13sp、文本缩进 71dp
// （71 = SettingsRowStart 21 + 缩略图 40 + 间距 10，正好和设置页左对齐）。
// ---------------------------------------------------------------------------

private val PackRowHeight = 58.dp
private val PackThumbSize = 40.dp
private val PackTextIndent = SettingsRowStart + PackThumbSize + 10.dp
private val ThumbCorner = RoundedCornerShape(WhaleRadius.s)
private val TileCorner = RoundedCornerShape(WhaleRadius.iconTile)
private val DialogCardRadius = RoundedCornerShape(WhaleRadius.l)
private val DialogCardWidth = 320.dp
private val StorageHintText = "表情包只存在这台手机上，仅供个人使用，不要分发。"

/**
 * 表情包管理页。无状态：数据来自 [StickerManagerUiState]，动作走 [StickerManagerActions]。
 */
@Composable
internal fun StickerManagerScreen(
    state: StickerManagerUiState,
    actions: StickerManagerActions,
    onBack: () -> Unit,
    onPickFolder: () -> Unit,
    onPickZip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    val openPack = state.openPack
    val importBusy = state.importUi != null

    // 返回键先关页面自己的浮层；没有浮层时不拦截，交给外层导航。
    BackHandler(enabled = state.hasOverlay) { actions.handleBack() }

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
                title = openPack?.pack?.displayName() ?: "表情包",
                onBack = { if (openPack != null) actions.closePack() else onBack() },
            )
            if (openPack != null) {
                PackDetail(
                    pack = openPack,
                    actions = actions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                PackList(
                    state = state,
                    actions = actions,
                    onPickFolder = onPickFolder,
                    onPickZip = onPickZip,
                    importEnabled = !importBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
            StorageHint()
        }

        // ---- 导入流程浮层 ----
        state.importUi?.let { importUi ->
            when (importUi.stage) {
                StickerImportStage.Inspecting -> InspectWorkingDialog()
                StickerImportStage.ConfirmingConflicts -> ImportConflictDialog(
                    conflicts = importUi.conflicts,
                    onCancel = actions::cancelImportConfirm,
                    onContinue = actions::confirmImport,
                )

                StickerImportStage.Importing -> ImportProgressDialog(
                    state = importUi,
                    onCancel = actions::cancelImport,
                )

                StickerImportStage.Result -> ImportResultDialog(
                    state = importUi,
                    onClose = actions::dismissImportResult,
                )
            }
        }

        // ---- 单张的弹层 + 删除确认 ----
        state.stickerSheet?.let { sticker ->
            StickerSheet(sticker = sticker, actions = actions)
        }
        state.pendingDeletePack?.let { pack ->
            SettingsConfirmDialog(
                title = "删除这个系列？",
                message = "会删掉「${pack.displayName()}」的 ${state.pendingDeletePackCount} 张表情和本机文件，" +
                    "不能恢复。已经发在聊天里的表情会显示「表情已删除」。",
                confirmLabel = "删除",
                onCancel = actions::cancelDelete,
                onConfirm = actions::confirmDelete,
            )
        }
        if (state.pendingDeleteSticker != null) {
            SettingsConfirmDialog(
                title = "删除这张表情？",
                message = "删掉后不能恢复，本机的图片和缩略图都会删除。已经发在聊天里的这条会显示「表情已删除」。",
                confirmLabel = "删除",
                onCancel = actions::cancelDelete,
                onConfirm = actions::confirmDelete,
            )
        }

        // ---- 底部提示条 ----
        state.toast?.let { toast ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                SettingsToastBar(
                    toast = toast,
                    onAction = actions::dismissToast,
                    onExpired = actions::dismissToast,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 系列列表
// ---------------------------------------------------------------------------

@Composable
private fun PackList(
    state: StickerManagerUiState,
    actions: StickerManagerActions,
    onPickFolder: () -> Unit,
    onPickZip: () -> Unit,
    importEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Spacer(Modifier.height(WhaleSpacing.s))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = WhaleSpacing.cardOuter),
            horizontalArrangement = Arrangement.spacedBy(WhaleSpacing.s),
        ) {
            ImportCapsuleButton(
                label = "导入文件夹",
                onClick = onPickFolder,
                enabled = importEnabled,
                modifier = Modifier.weight(1f),
            )
            ImportCapsuleButton(
                label = "导入 ZIP",
                onClick = onPickZip,
                enabled = importEnabled,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(WhaleSpacing.sectionGap))

        when {
            state.isLoading -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                SettingsSpinner(Modifier.size(22.dp), colors.accent)
            }

            // 空状态 = 组件库 EmptyState（之前是一行纯文字，01 §4）
            state.packs.isEmpty() -> EmptyState(
                title = "还没有表情包",
                icon = WhaleIcons.Sticker,
                text = "点上面的按钮导入",
                modifier = Modifier.padding(vertical = WhaleSpacing.xxl),
            )

            else -> {
                SectionCard(header = "已导入") {
                    state.packs.forEachIndexed { index, item ->
                        PackRow(
                            item = item,
                            onOpen = { actions.openPack(item.pack.id) },
                            onToggleEnabledForHer = { enabled -> actions.setEnabledForHer(item.pack.id, enabled) },
                            onDelete = { actions.requestDeletePack(item.pack) },
                        )
                        if (index != state.packs.lastIndex) PackDivider()
                    }
                }
                SectionGap()
                SectionCard(header = "关于") {
                    state.packs.forEachIndexed { index, item ->
                        AboutPackRow(item.pack)
                        if (index != state.packs.lastIndex) CellDivider()
                    }
                }
            }
        }
    }
}

/** 一个系列（58dp 行，缩略图 40dp，文本缩进 71dp）。 */
@Composable
private fun PackRow(
    item: StickerPackUi,
    onOpen: () -> Unit,
    onToggleEnabledForHer: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = WhaleTheme.colors
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PackRowHeight)
            .clickable(onClick = onOpen)
            .padding(start = SettingsRowStart, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StickerThumb(
            sticker = item.cover,
            context = context,
            size = PackThumbSize,
            corner = ThumbCorner,
            contentDescription = item.pack.displayName(),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.pack.displayName(),
                style = WhaleTheme.type.cellTitleMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${item.stickerCount} 张 · 系列 ${item.pack.id}",
                style = SettingsType.cellSubtitle,
                color = colors.textCaption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.pack.hasSoundOriginally) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "原本是有声贴图，声音已不在",
                    style = SettingsType.cellSubtitle,
                    color = WhaleTheme.colors.danger,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "她能用",
            style = SettingsType.cellSubtitle,
            color = colors.textCaption,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        SettingsSwitch(
            checked = item.pack.enabledForHer,
            onCheckedChange = onToggleEnabledForHer,
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete)
                .semantics { contentDescription = "删除系列 ${item.pack.displayName()}" },
            contentAlignment = Alignment.Center,
        ) {
            WhaleIcon(WhaleIcons.Trash, tint = colors.textCaption, modifier = Modifier.size(20.dp))
        }
    }
}

/** 系列行之间的分割线：从文本缩进处开始（Telegram 的 71dp），右到卡片边。 */
@Composable
private fun PackDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = PackTextIndent)
            .height(0.5.dp)
            .background(WhaleTheme.colors.divider),
    )
}

/** 「关于」里的一行：系列名 + 来源 + 版权说明。 */
@Composable
private fun AboutPackRow(pack: StickerPack) {
    val colors = WhaleTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = SettingsRowStart, end = SettingsRowEnd, top = 10.dp, bottom = 10.dp),
    ) {
        Text(
            text = pack.displayName(),
            style = SettingsType.cellTitle,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "来源：${pack.source.ifBlank { "未注明" }}",
            style = SettingsType.cellSubtitle,
            color = colors.textCaption,
        )
        if (pack.licenseNote.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "版权：${pack.licenseNote}",
                style = SettingsType.cellSubtitle,
                color = colors.textCaption.copy(alpha = WhaleAlpha.MenuGlass),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 系列网格
// ---------------------------------------------------------------------------

@Composable
private fun PackDetail(
    pack: StickerPackUi,
    actions: StickerManagerActions,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    val context = LocalContext.current
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SettingsRowStart, end = SettingsRowEnd, top = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${pack.stickerCount} 张 · 系列 ${pack.pack.id}",
                style = SettingsType.cellSubtitle,
                color = colors.textCaption,
                modifier = Modifier.weight(1f),
            )
            if (!pack.pack.enabledForHer) {
                Text(
                    text = "她不能用",
                    style = SettingsType.cellSubtitle,
                    color = colors.textCaption,
                )
            }
        }
        if (pack.pack.hasSoundOriginally) {
            Text(
                text = "原本是有声贴图，声音已不在",
                style = SettingsType.cellSubtitle,
                color = WhaleTheme.colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = SettingsRowStart, end = SettingsRowEnd, bottom = 8.dp),
            )
        }
        if (pack.stickers.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "这个系列里没有表情",
                    style = SettingsType.info,
                    color = colors.textCaption,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(pack.stickers, key = { it.id }) { sticker ->
                    StickerTile(
                        sticker = sticker,
                        context = context,
                        onClick = { actions.openSticker(sticker) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StickerTile(
    sticker: Sticker,
    context: Context,
    onClick: () -> Unit,
) {
    val colors = WhaleTheme.colors
    val model = rememberStickerFile(context, sticker, preferThumb = true)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(TileCorner)
            .background(colors.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        StickerImageOrPlaceholder(
            model = model,
            contentDescription = sticker.description,
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 单张的底部弹层（只允许删除）
// ---------------------------------------------------------------------------

@Composable
private fun StickerSheet(
    sticker: Sticker,
    actions: StickerManagerActions,
) {
    val colors = WhaleTheme.colors
    val context = LocalContext.current
    val model = rememberStickerFile(context, sticker, preferThumb = true)
    SettingsSheet(onDismiss = actions::closeSticker) {
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 200.dp),
            contentAlignment = Alignment.Center,
        ) {
            StickerImageOrPlaceholder(
                model = model,
                contentDescription = sticker.description,
                modifier = Modifier.size(140.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = sticker.description.ifBlank { "表情" },
            style = SettingsType.sheetTitle,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (sticker.detail.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = sticker.detail,
                style = SettingsType.dialogBody,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (sticker.textInImage.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "图里的字：${sticker.textInImage}",
                style = SettingsType.cellSubtitle,
                color = colors.textCaption,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "编号 ${sticker.id}",
                style = SettingsType.cellSubtitle,
                color = colors.textCaption,
            )
            if (sticker.sentByHerCount > 0) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "她发过 ${sticker.sentByHerCount} 次",
                    style = SettingsType.cellSubtitle,
                    color = colors.textCaption,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            SettingsFilledButton(
                label = "只删除这一张",
                enabled = true,
                container = WhaleTheme.colors.danger,
                onClick = { actions.requestDeleteSticker(sticker) },
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

// ---------------------------------------------------------------------------
// 导入的四个弹窗
// ---------------------------------------------------------------------------

@Composable
private fun InspectWorkingDialog() {
    WhaleDialog(onDismiss = {}, dismissOnOutside = false) {
        Row(
            modifier = Modifier.padding(horizontal = WhaleSpacing.dialogHorizontal, vertical = WhaleSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsSpinner(Modifier.size(20.dp), WhaleTheme.colors.accent)
            Spacer(Modifier.width(WhaleSpacing.m))
            Text(
                text = "正在检查表情包…",
                style = SettingsType.dialogBody,
                color = WhaleTheme.colors.textPrimary,
            )
        }
    }
}

@Composable
private fun ImportConflictDialog(
    conflicts: List<String>,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
) {
    WhaleDialog(
        onDismiss = onCancel,
        title = "确认导入",
        text = "这些系列已经导入过：${conflicts.joinToString("、")}。\n" +
            "继续会合并：同编号更新说明，不会删掉库里已有的。",
        buttons = listOf(WhaleDialogButton("取消", onCancel), WhaleDialogButton("继续", onContinue)),
    )
}

@Composable
private fun ImportProgressDialog(
    state: StickerImportUiState,
    onCancel: () -> Unit,
) {
    WhaleDialog(
        onDismiss = {},
        dismissOnOutside = false,
        title = "正在导入表情",
        text = "已处理 ${state.done}/${state.total}",
        buttons = listOf(WhaleDialogButton("取消", onCancel)),
    ) {
        Box(Modifier.padding(horizontal = WhaleSpacing.dialogHorizontal, vertical = WhaleSpacing.s)) {
            ImportProgressBar(fraction = state.fraction)
        }
    }
}

@Composable
private fun ImportResultDialog(
    state: StickerImportUiState,
    onClose: () -> Unit,
) {
    val colors = WhaleTheme.colors
    WhaleDialog(
        onDismiss = onClose,
        title = if (state.cancelled) "导入已取消" else "导入结果",
        text = state.summary,
        buttons = listOf(WhaleDialogButton("关闭", onClose)),
    ) {
        if (state.details.isNotEmpty()) {
            Column(
                Modifier
                    .padding(horizontal = WhaleSpacing.dialogHorizontal)
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                state.details.forEach { line ->
                    Text(
                        text = "· $line",
                        style = SettingsType.cellSubtitle,
                        color = colors.textCaption,
                        modifier = Modifier.padding(vertical = WhaleSpacing.xxs),
                    )
                }
            }
        }
    }
}



@Composable
private fun ImportProgressBar(fraction: Float) {
    val colors = WhaleTheme.colors
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = WhaleDuration.Short),
        label = "stickerImportProgress",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(WhaleShapes.pill)
            .background(colors.divider),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(4.dp)
                .clip(WhaleShapes.pill)
                .background(colors.accentFill),
        )
    }
}

// ---------------------------------------------------------------------------
// 公共小件
// ---------------------------------------------------------------------------

/** 底部固定的一行版权提示。 */
@Composable
private fun StorageHint() {
    val colors = WhaleTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider),
        )
        Text(
            text = StorageHintText,
            style = SettingsType.cellSubtitle,
            color = colors.textCaption,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** 「导入文件夹 / 导入 zip」= [WhaleButton] 次按钮（描边胶囊，U0：统一按钮形状）。 */
@Composable
private fun ImportCapsuleButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    WhaleButton(
        text = label,
        onClick = onClick,
        enabled = enabled,
        style = WhaleButtonStyle.Secondary,
        icon = WhaleIcons.Import,
        compact = true,
        modifier = modifier,
    )
}

/** 40dp 圆角方形缩略图（封面）；没有封面时画占位。 */
@Composable
private fun StickerThumb(
    sticker: Sticker?,
    context: Context,
    size: Dp,
    corner: Shape,
    contentDescription: String,
) {
    val model = sticker?.let { rememberStickerFile(context, it, preferThumb = true) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(corner),
        contentAlignment = Alignment.Center,
    ) {
        StickerImageOrPlaceholder(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Coil 加载缩略图；文件不存在 / Preview 里画占位块，避免空白。 */
@Composable
private fun StickerImageOrPlaceholder(
    model: File?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    val inspection = LocalInspectionMode.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(WhaleRadius.s))
            .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (model == null || inspection) {
            PlaceholderDot()
        } else {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 缩略图缺失时的通用「表情」占位图标（round6 第三节第 5/6 条）：不留空白方块。 */
@Composable
private fun PlaceholderDot() {
    val colors = WhaleTheme.colors
    Box(contentAlignment = Alignment.Center) {
        WhaleIcon(WhaleIcons.Smile, tint = colors.textCaption, modifier = Modifier.size(24.dp))
    }
}

/**
 * 贴图的本地文件：优先缩略图（静态 PNG，网格 / 弹层都用它，避免解动图），
 * 缩略图缺失时退回原图。
 */
@Composable
private fun rememberStickerFile(context: Context, sticker: Sticker, preferThumb: Boolean): File? =
    remember(context, sticker.id, sticker.thumbPath, sticker.filePath, preferThumb) {
        val thumb = if (sticker.thumbPath.isBlank()) null else File(context.filesDir, sticker.thumbPath)
        val full = if (sticker.filePath.isBlank()) null else File(context.filesDir, sticker.filePath)
        when {
            preferThumb && thumb?.isFile == true -> thumb
            full?.isFile == true -> full
            else -> null
        }
    }
