package whaleui.nightsky.ltd.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// 形状 token（U0，02-telegram-design.md §2）
// 圆角只有下面这些；页面里不再写 RoundedCornerShape(数字)。
// ---------------------------------------------------------------------------

object WhaleRadius {
    /** 波形条、细进度条的圆头 2dp（SeekBarWaveform 2dp 宽条） */
    val hairline = 2.dp
    /** 骨架条 r4（FlickerLoadingView） */
    val xs = 4.dp
    /** 气泡合并角 / 尾巴处小角 6dp（MessageDrawable） */
    val bubbleSmall = 6.dp
    /** 小缩略图、勾选框 8dp */
    val s = 8.dp
    /** 设置行彩色图标块 r10（SettingCell 28dp 图标） */
    val iconTile = 10.dp
    /** 日期胶囊 r11（ChatActionCell） */
    val service = 11.dp
    /** 菜单、对话框、输入框 12dp（ActionBarPopupWindow / AlertDialog） */
    val m = 12.dp
    /** 分组卡片、Bulletin、底部弹层上沿 16dp */
    val l = 16.dp
    /** 气泡大圆角 17dp（SharedConfig.bubbleRadius） */
    val bubble = 17.dp
    /** 36dp 搜索框 r18（SearchField） */
    val search = 18.dp
    /** 热力图格子 r2（GitHub 贡献图同款小圆角） */
    val heatCell = 2.dp
}

object WhaleShapes {
    val skeletonBar: Shape = RoundedCornerShape(WhaleRadius.xs)
    val thumbnail: Shape = RoundedCornerShape(WhaleRadius.s)
    val iconTile: Shape = RoundedCornerShape(WhaleRadius.iconTile)
    val service: Shape = RoundedCornerShape(WhaleRadius.service)
    /** 弹出菜单 / 长按菜单 */
    val menu: Shape = RoundedCornerShape(WhaleRadius.m)
    /** 对话框 */
    val dialog: Shape = RoundedCornerShape(WhaleRadius.m)
    /** 输入框、对话框里的输入区 */
    val field: Shape = RoundedCornerShape(WhaleRadius.m)
    /** 设置分组卡片、记忆卡、日记卡、任务卡 */
    val card: Shape = RoundedCornerShape(WhaleRadius.l)
    /** Bulletin */
    val bulletin: Shape = RoundedCornerShape(WhaleRadius.l)
    /** 底部弹层 / 表情面板：只圆上沿 */
    val sheet: Shape = RoundedCornerShape(topStart = WhaleRadius.l, topEnd = WhaleRadius.l)
    /** 图片 / 表情这类整块媒体（与气泡同圆角） */
    val media: Shape = RoundedCornerShape(WhaleRadius.bubble)
    /** 搜索框 */
    val search: Shape = RoundedCornerShape(WhaleRadius.search)
    /** 按钮、筛选标签、胶囊 */
    val pill: Shape = RoundedCornerShape(50)
    /** 小提示（热力图点格子）：r6，和气泡小角一致 */
    val hint: Shape = RoundedCornerShape(WhaleRadius.bubbleSmall)
    /** 头像、圆按钮、FAB */
    val circle: Shape = CircleShape
    /** 标签页指示条：上圆角 3dp（ViewPagerFixed.TabsView） */
    val tabIndicator: Shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
}
