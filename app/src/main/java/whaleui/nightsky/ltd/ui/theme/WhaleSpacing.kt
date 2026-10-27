package whaleui.nightsky.ltd.ui.theme

import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// 间距 / 尺寸 token（U0，02-telegram-design.md §2.3–2.5）
// 通用档位 + Telegram 列表、弹出物的固定尺寸。新代码一律用这里；
// 老页面的 dp 字面量这一阶段不全量替换（布局不改），只在换成组件时顺带收掉。
// ---------------------------------------------------------------------------

object WhaleSpacing {
    // 通用档位
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp

    // 列表 / 设置（TextSettingsCell / HeaderCell / TextInfoPrivacyCell）
    /** 行左右内边距 21dp */
    val cellHorizontal = 21.dp
    /** 有图标时文字起点 58dp（相对行左缘） */
    val cellTextIndentWithIcon = 58.dp
    /** 图标左边距 18dp */
    val cellIconStart = 18.dp
    /** 单行高 50dp */
    val cellHeight = 50.dp
    /** 带副标题行高 64dp（TextCheckCell2） */
    val cellHeightTwoLine = 64.dp
    /** 信息行（值在上、标签在下）60dp（TextDetailCell） */
    val detailCellHeight = 60.dp
    /** 分组标题行 40dp */
    val headerHeight = 40.dp
    /** 分组标题文字距顶 15dp */
    val headerTop = 15.dp
    /** 组下说明：左右 24、上 10、下 17 */
    val footnoteHorizontal = 24.dp
    val footnoteTop = 10.dp
    val footnoteBottom = 17.dp
    /** 分组卡片距屏幕边 12dp、组间 12dp */
    val cardOuter = 12.dp
    val sectionGap = 12.dp
    /** 行图标 24dp、设置彩色图标块 28dp */
    val icon = 24.dp
    val iconTile = 28.dp

    // 顶栏 / 输入栏
    val actionBarHeight = 56.dp
    val inputBarHeight = 44.dp
    val chatAvatar = 42.dp
    /** 可点区域最小 48dp */
    val touchTarget = 48.dp

    // 弹出物
    /** 菜单：内边距 8、项高 48、最小宽 200、项左右 18 */
    val menuPadding = 8.dp
    val menuItemHeight = 48.dp
    val menuMinWidth = 200.dp
    val menuItemHorizontal = 18.dp
    /** 对话框：左右 24、按钮高 40、按钮区 padding 16/0/16/4 */
    val dialogHorizontal = 24.dp
    val dialogButtonHeight = 40.dp
    /** Bulletin：外边距左右 16 / 上下 8，最小高 48 */
    val bulletinHorizontal = 16.dp
    val bulletinVertical = 8.dp
    val bulletinMinHeight = 48.dp
    /** FAB 48dp，右 20、下 14（这里取 16 与底部 Bulletin 对齐） */
    val fab = 48.dp
    /** 主按钮高 48（StickerEmptyView 按钮） */
    val buttonHeight = 48.dp
    /** 空状态插图 117dp → 取 96（我们没有动态贴纸，只放图标） */
    val emptyIllustration = 96.dp

    // 抽屉（C，u1 §2.2）
    /** 抽屉宽 = min(屏宽 − 56dp, 320dp)（Telegram 13 无抽屉数值，u1 自定） */
    val drawerMaxWidth = 320.dp
    val drawerEdgeGap = 56.dp
    /** 头部头像 64dp */
    val drawerAvatar = 64.dp

    // 她的资料页（B）
    /** 头部大头像 96dp（u3 §2） */
    val profileAvatar = 96.dp
    /** 热力图格子约 12dp（按屏宽拉伸到整列）、间距 3dp（README §5 B） */
    val heatCell = 12.dp
    val heatGap = 3.dp
    /** 热力图左侧「一 / 三 / 五」那一列的宽度（**自定**） */
    val heatWeekdayColumn = 16.dp
    /** 热力图点格子的小提示：内边距、和格子的距离（**自定**） */
    val heatHintHorizontal = 10.dp
    val heatHintVertical = 6.dp
    val heatHintOffset = 6.dp
}
