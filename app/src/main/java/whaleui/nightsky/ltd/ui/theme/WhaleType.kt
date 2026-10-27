// FontVariation.Settings 在 Compose 1.10 里仍是实验 API；衬线子集是可变字体，要按字重取轴。
@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package whaleui.nightsky.ltd.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.R

// ---------------------------------------------------------------------------
// 字体 token（U0，02-telegram-design.md §3）
//
// - 字重只有两档：Regular(400) 和 Medium(500)。Telegram 的 `AndroidUtilities.bold()` 实际是 500，全 App 没有 700。
// - 字号表按 Telegram 13.1.1；行高不设（Unspecified = 字体自然行高，与 StaticLayout 1.0 倍一致），
//   只有长文阅读（记忆卡、日记正文）例外，见 [WhaleType.reading]。
// - 单位用 sp：Telegram 用不随系统缩放的 dp，我们保留系统字体缩放（无障碍优先），数值相同。
// - 衬线（Q3）：只有抽屉大标题和资料页名字两个样式，其余全部系统字体。
// ---------------------------------------------------------------------------

private fun scFont(resId: Int, weight: Int) = Font(
    resId = resId,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** 衬线（Noto Serif SC 子集，wght 400–600）：只给 [WhaleType.drawerTitle]、[WhaleType.profileName]。 */
val NotoSerifSc = FontFamily(
    scFont(R.font.noto_serif_sc_title, 400),
    scFont(R.font.noto_serif_sc_title, 500),
)

/** 两档字重。 */
object WhaleWeight {
    val Regular = FontWeight.W400
    val Medium = FontWeight.W500
}

private fun sans(size: TextUnit, medium: Boolean = false, lineHeight: TextUnit = TextUnit.Unspecified) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size,
    fontWeight = if (medium) WhaleWeight.Medium else WhaleWeight.Regular,
    lineHeight = lineHeight,
)

@Immutable
data class WhaleType(
    /** 普通页标题（ActionBar）20 Medium */
    val pageTitle: TextStyle = sans(20.sp, medium = true),
    /** 聊天顶栏名字 18 Medium */
    val chatName: TextStyle = sans(18.sp, medium = true),
    /** 聊天顶栏状态 14 */
    val chatStatus: TextStyle = sans(14.sp),
    /** 消息正文 16 */
    val message: TextStyle = sans(16.sp),
    /** 消息时间 12 */
    val messageTime: TextStyle = sans(12.sp),
    /** 气泡里的回复名 14 Medium */
    val messageName: TextStyle = sans(14.sp, medium = true),
    /** 气泡里的回复文字 14 */
    val messageReply: TextStyle = sans(14.sp),
    /** 日期胶囊 / 服务消息 14 Medium */
    val service: TextStyle = sans(14.sp, medium = true),
    /** 文件名 15 Medium */
    val fileName: TextStyle = sans(15.sp, medium = true),
    /** 文件大小、语音时长等副信息 13 */
    val fileInfo: TextStyle = sans(13.sp),
    /** 输入框文字 18 */
    val input: TextStyle = sans(18.sp),
    /** 列表行标题 / 值 16 */
    val cellTitle: TextStyle = sans(16.sp),
    /** 列表行强调（标签页、抽屉项） 16 Medium */
    val cellTitleMedium: TextStyle = sans(16.sp, medium = true),
    /** 列表行副标题 13 */
    val cellSubtitle: TextStyle = sans(13.sp),
    /** 分组标题 14 Medium（蓝） */
    val header: TextStyle = sans(14.sp, medium = true),
    /** 组下说明 14 */
    val footnote: TextStyle = sans(14.sp),
    /** 菜单项 16 */
    val menuItem: TextStyle = sans(16.sp),
    /** 菜单副文字 13 */
    val menuSubtitle: TextStyle = sans(13.sp),
    /** 对话框标题 20 Medium */
    val dialogTitle: TextStyle = sans(20.sp, medium = true),
    /** 对话框正文 16 */
    val dialogText: TextStyle = sans(16.sp),
    /** 按钮 16 Medium（对话框按钮、主按钮） */
    val button: TextStyle = sans(16.sp, medium = true),
    /** 行尾小按钮、筛选标签、撤销 14 Medium */
    val buttonSmall: TextStyle = sans(14.sp, medium = true),
    /** Bulletin 单行 15 */
    val bulletin: TextStyle = sans(15.sp),
    /** Bulletin 标题 14 Medium */
    val bulletinTitle: TextStyle = sans(14.sp, medium = true),
    /** Bulletin 副标题 13 */
    val bulletinSubtitle: TextStyle = sans(13.sp),
    /** Bulletin 撤销按钮 14 Medium */
    val bulletinAction: TextStyle = sans(14.sp, medium = true),
    /** 空状态标题 20 Medium */
    val emptyTitle: TextStyle = sans(20.sp, medium = true),
    /** 空状态副标题 14 */
    val emptyText: TextStyle = sans(14.sp),
    /** 统计大数字 17 Medium */
    val statNumber: TextStyle = sans(17.sp, medium = true),
    /** 统计标签 13 */
    val statLabel: TextStyle = sans(13.sp),
    /** 最小号：角标、来源标记、滚轮旁的单位 12 */
    val small: TextStyle = sans(12.sp),
    /**
     * 长文阅读（记忆卡、日记正文）16，行高 24。
     * 和 Telegram 不同（它不设行高）：这里是多段中文长文，1.5 倍行高读起来不挤；只此一处例外。
     */
    val reading: TextStyle = sans(16.sp, lineHeight = 24.sp),
    /** 时间滚轮中间行 20 Medium（Telegram NumberPicker 选中行的观感） */
    val wheel: TextStyle = sans(20.sp, medium = true),
    /** 模型 id 等机器字符串 14 等宽 */
    val mono: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = WhaleWeight.Regular),
    /** 衬线 · 抽屉头部她的名字 22 Medium（Q3；C 起头部改成头像 + 名字 + 状态，u1 §2.2） */
    val drawerTitle: TextStyle = TextStyle(
        fontFamily = NotoSerifSc,
        fontSize = 22.sp,
        fontWeight = WhaleWeight.Medium,
    ),
    /** 衬线 · 资料页名字 22 Medium（Q3） */
    val profileName: TextStyle = TextStyle(
        fontFamily = NotoSerifSc,
        fontSize = 22.sp,
        fontWeight = WhaleWeight.Medium,
    ),
) {
    /**
     * 系统「粗体文字」打开时 Android 12+ 会给所有字重加 [adjustment]（通常 300）；
     * Telegram 用固定字体文件不受影响。这里把它抵掉，保持只有 400 / 500 两档。
     */
    fun withWeightAdjustment(adjustment: Int): WhaleType {
        if (adjustment == 0) return this
        fun TextStyle.fix(): TextStyle {
            val w = fontWeight?.weight ?: return this
            return copy(fontWeight = FontWeight((w - adjustment).coerceIn(1, 1000)))
        }
        return WhaleType(
            pageTitle.fix(), chatName.fix(), chatStatus.fix(), message.fix(), messageTime.fix(),
            messageName.fix(), messageReply.fix(), service.fix(), fileName.fix(), fileInfo.fix(),
            input.fix(), cellTitle.fix(), cellTitleMedium.fix(), cellSubtitle.fix(), header.fix(),
            footnote.fix(), menuItem.fix(), menuSubtitle.fix(), dialogTitle.fix(), dialogText.fix(),
            button.fix(), buttonSmall.fix(), bulletin.fix(), bulletinTitle.fix(), bulletinSubtitle.fix(),
            bulletinAction.fix(), emptyTitle.fix(), emptyText.fix(), statNumber.fix(), statLabel.fix(),
            small.fix(), reading.fix(), wheel.fix(), mono.fix(), drawerTitle.fix(), profileName.fix(),
        )
    }
}

/**
 * 不随系统字体缩放的同号字：把 sp 当 dp 用（README §2.8：顶栏名字 / 状态、时间戳等用 dp 等价的固定字号）。
 * 走 [Density.toSp]，Android 14 的非线性字体缩放也能正确抵掉。
 */
fun TextStyle.fixedFontScale(density: Density): TextStyle =
    copy(fontSize = with(density) { fontSize.value.dp.toSp() })

val WhaleTypeDefaults = WhaleType()

val LocalWhaleType = staticCompositionLocalOf { WhaleTypeDefaults }
