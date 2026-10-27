package whaleui.nightsky.ltd.ui.chat

import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import androidx.compose.ui.graphics.RectangleShape
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import whaleui.nightsky.ltd.ui.components.EmptyState
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.domain.chat.ChatMessage

/**
 * 聊天里的表情消息 + 输入栏的表情面板（round5 3.5 / 3.6）。
 *
 * 布局参数来自对 Telegram Android 客户端界面的调研，只学参数、
 * 不抄代码：
 * - 表情消息无气泡、圆角 0、边长约 160dp（按图片比例缩放，不裁切）；
 * - 时间戳叠在右下角：半透明黑胶囊（24% 黑）+ 纯白小字，她发的带已读勾；
 * - 面板：顶部系列图标条 36dp（选中项 30dp 高、18% 高亮胶囊）、系列名行 27dp（15sp bold）、
 *   五列网格、格子 82dp（缩略图 66dp + 一行说明），面板总高 260dp。
 *
 * 动图只用 [AsyncImage]（Coil 3 的 `AnimatedImageDecoder`，API 28+）；离屏时 LazyColumn /
 * LazyVerticalGrid 会把节点从组合里移除，动画自然停止，因此不需要额外的可见性判断。
 */

/** 面板里一个系列。 */
data class StickerPanelPack(
    val id: String,
    val name: String,
    val stickers: List<StickerPanelItem>,
)

/** 面板里一张表情。 */
data class StickerPanelItem(
    val id: String,
    /** 静态缩略图（PNG，长边 256）的 `file://` 地址。 */
    val thumbUrl: String,
    /** 原图（动图）的 `file://` 地址，发出去时用这个。 */
    val fileUrl: String,
    val description: String,
    /** series.json 的 detail：请求里写成「(对方发来一个表情:<detail>,编号 <id>)」。 */
    val detail: String,
)

/**
 * 把表情库的两张表组装成输入栏面板要的数据（round5 3.5）。
 *
 * 只挑「文件还在」的贴图；缩略图用导入时生成的 PNG（static），点发送时用原图（动图）。
 */
internal fun buildStickerPanelPacks(
    context: android.content.Context,
    packs: List<whaleui.nightsky.ltd.sticker.StickerPack>,
    stickers: List<whaleui.nightsky.ltd.sticker.Sticker>,
): List<StickerPanelPack> {
    val filesDir = context.filesDir
    val byPack = stickers.groupBy { it.packId }
    return packs.sortedBy { it.sortOrder }.mapNotNull { pack ->
        val items = byPack[pack.id].orEmpty().mapNotNull { sticker ->
            val file = java.io.File(filesDir, sticker.filePath)
            if (!file.isFile) return@mapNotNull null
            StickerPanelItem(
                id = sticker.id,
                thumbUrl = "file://" + java.io.File(filesDir, sticker.thumbPath).absolutePath,
                fileUrl = "file://" + file.absolutePath,
                description = sticker.description,
                detail = sticker.detail,
            )
        }
        if (items.isEmpty()) null else StickerPanelPack(
            id = pack.id,
            // 面板标签显示官方原名（series.json 的 title_original，如「示例系列（原名）」）；
            // 没有原名才退回简体短名 name。
            name = pack.titleOriginal.ifBlank { pack.name },
            stickers = items,
        )
    }
}

/** 表情消息气泡：无背景，按图片比例缩放，右下角叠「时间」服务胶囊（u2 §2.8）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StickerBubble(
    message: ChatMessage,
    styles: ChatTextStyles,
    selected: Boolean,
    onLongPress: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    val sticker = message.sticker ?: return
    val haptics = LocalHapticFeedback.current
    // 选中（长按菜单打开）时叠一层 serviceBubbleSelected 圆角底（替换原来的白 8%）
    val highlight by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(WhaleDuration.Dismiss),
        label = "stickerSelected",
    )
    var downPosition by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .size(StickerMaxEdge)
            .clip(WhaleShapes.card)
            .background(colors.serviceBubbleSelected.copy(alpha = colors.serviceBubbleSelected.alpha * highlight))
            .pointerInput(message.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    downPosition = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress(downPosition)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (sticker.isDeleted) {
            // 库里的表情被删了：聊天里保留位置，显示占位（不崩）
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "表情已删除",
                    style = styles.status,
                    color = colors.textCaption,
                )
            }
        } else {
            AsyncImage(
                model = sticker.url,
                contentDescription = sticker.description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            // 右下角时间 + 已读勾（他发的）：服务胶囊底，r10，内边距 6 / 2
            MessageMeta(
                time = message.time,
                status = message.status,
                showStatus = !message.fromHer,
                color = colors.serviceText,
                checkColor = colors.serviceText,
                clockColor = colors.serviceText,
                styles = styles,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .clip(WhaleShapes.iconTile)
                    .background(colors.serviceBubble)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * 输入栏的表情面板（A，u2 §6.4）：整宽、贴底、不悬浮，底色 `emojiPanelBg`（由调用方铺）。
 * - 顶部系列条 48dp：每个系列一张 36dp 缩略图（r8，间距 8），选中的下面一条 2dp `accent` 指示线；
 *   最右固定一个齿轮（管理表情包）；
 * - 五列方格，点一下 = 直接发送，面板保持打开；格子里只有缩略图（Telegram 也不显示说明文字），描述留给读屏；
 * - 没有表情包：紧凑空状态 +「添加表情包」。
 */
@Composable
internal fun StickerPanel(
    packs: List<StickerPanelPack>,
    onSend: (StickerPanelItem) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WhaleTheme.colors
    var selectedPackIndex by remember(packs.size) { mutableIntStateOf(0) }
    val selected = packs.getOrNull(selectedPackIndex.coerceIn(0, (packs.size - 1).coerceAtLeast(0)))

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(StickerPanelHeight),
    ) {
        if (packs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "还没有表情包",
                    icon = WhaleIcons.Sticker,
                    actionLabel = "添加表情包",
                    onAction = onOpenSettings,
                )
            }
            return@Column
        }

        // 1) 顶部系列条 + 右侧齿轮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(StickerTabsHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = WhaleSpacing.s),
                horizontalArrangement = Arrangement.spacedBy(WhaleSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(packs, key = { it.id }) { pack ->
                    val isSelected = pack.id == selected?.id
                    Column(
                        modifier = Modifier
                            .height(StickerTabsHeight)
                            .clip(WhaleShapes.thumbnail)
                            .clickable { selectedPackIndex = packs.indexOf(pack) }
                            .semantics { contentDescription = pack.name },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(StickerTabThumb)
                                .clip(WhaleShapes.thumbnail),
                            contentAlignment = Alignment.Center,
                        ) {
                            val cover = pack.stickers.firstOrNull()
                            if (cover != null) {
                                AsyncImage(
                                    model = cover.thumbUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                // 缩略图缺失时用通用「表情」占位图标，不留空按钮（round6 第三节第 6 条）
                                WhaleIcon(WhaleIcons.Smile, tint = colors.emojiPanelIcon, modifier = Modifier.size(WhaleSpacing.icon))
                            }
                        }
                        Spacer(Modifier.height(WhaleSpacing.xxs))
                        Box(
                            Modifier
                                .size(width = StickerTabThumb - WhaleSpacing.s, height = WhaleSpacing.xxs)
                                .clip(WhaleShapes.pill)
                                .background(if (isSelected) colors.accent else Color.Transparent),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(WhaleSpacing.touchTarget)
                    .clickable(onClick = onOpenSettings)
                    .semantics { contentDescription = "管理表情包" },
                contentAlignment = Alignment.Center,
            ) {
                WhaleIcon(WhaleIcons.Settings, tint = colors.textSecondary, modifier = Modifier.size(WhaleSpacing.icon))
            }
        }

        // 2) 五列方格：点一下 = 直接发送
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = WhaleSpacing.xs, vertical = WhaleSpacing.xs),
        ) {
            items(selected?.stickers.orEmpty(), key = { it.id }) { item ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(WhaleShapes.thumbnail)
                        .clickable { onSend(item) }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = item.thumbUrl,
                        // 描述不显示在界面上（Telegram 也不显示），留给读屏
                        contentDescription = "表情：${item.description}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 尺寸常量

/** 聊天里的表情边长（约 160dp，比例靠 ContentScale.Fit 保持，不裁切）。 */
internal val StickerMaxEdge: Dp = 160.dp

/**
 * 面板高度：Telegram 跟随上次键盘高度；我们没有记录键盘高的地方（不加新数据），取 260dp（自定，u2 §6.4 的兜底值）。
 */
internal val StickerPanelHeight: Dp = 260.dp

private val StickerTabsHeight: Dp = 48.dp
private val StickerTabThumb: Dp = 36.dp
