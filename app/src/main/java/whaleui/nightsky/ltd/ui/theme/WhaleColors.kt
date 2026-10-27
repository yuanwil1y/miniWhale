package whaleui.nightsky.ltd.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// 配色 token（U0，取自 Telegram Android 的夜间主题配色数值）
//
// 只有这一套语义色。数值取 Telegram Android 默认深色主题「Dark Blue」（darkblue.attheme），
// 每个字段的 KDoc 写了对应的 Telegram 主题键；我们只借数值，不借代码。
//
// 浅色模式（Q2）：这一轮只做深色。以后加浅色只需要再写一个 `WhaleColors(...)` 实例
// （例如 `WhaleLightBlue`），在 [WhaleUITheme] 里按系统深浅色选一个，页面一行不用改。
//
// 规则：十六进制颜色只允许出现在 ui/theme/ 里；页面和组件一律 `WhaleTheme.colors.xxx`。
// ---------------------------------------------------------------------------

@Immutable
data class WhaleColors(
    /** 是否深色；状态栏图标、M3 兜底配色用。 */
    val isDark: Boolean,

    // ---------------- 底色 / 面板 ----------------
    /** 设置页、资料页、二级页的页面底 · `windowBackgroundGray` */
    val bgPage: Color,
    /** 卡片、行、普通页底 · `windowBackgroundWhite` */
    val surface: Color,
    /** 顶栏 / 输入栏实色（没有模糊时） · `chat_messagePanelBackground` / `chat_topPanelBackground` */
    val surfaceBar: Color,
    /** 顶栏 / 输入栏玻璃：surfaceBar × 76% */
    val surfaceBarGlass: Color,
    /** 玻璃上沿细线（白 16%） · BlurredBackgroundProviderImpl 深色描边 */
    val glassStrokeTop: Color,
    /** 玻璃下沿细线（白 8%） */
    val glassStrokeBottom: Color,
    /** 抽屉底 · `chats_menuBackground` */
    val surfaceDrawer: Color,
    /** 抽屉头部底 · `chats_menuTopBackgroundCats` */
    val surfaceDrawerHeader: Color,
    /** 弹出菜单 / 长按菜单玻璃（85%） · `actionBarDefaultSubmenuBackground` */
    val surfaceMenu: Color,
    /** 弹出菜单实色（没有模糊时） · `actionBarDefaultSubmenuBackground` */
    val surfaceMenuSolid: Color,
    /** 菜单分隔 · `actionBarDefaultSubmenuSeparator` */
    val menuSeparator: Color,
    /** 对话框、底部弹层 · `dialogBackground` */
    val surfaceDialog: Color,
    /** 弹层里的灰底分组 · `dialogBackgroundGray` */
    val surfaceSheetGray: Color,
    /** 输入框底（对话框里的输入区等） · `dialogSearchBackground` */
    val surfaceInput: Color,
    /** 36dp 搜索框底 · `dialogSearchBackground`（SearchField） */
    val searchField: Color,
    /** 玻璃上的图标 / 文字 · `glass_defaultIcon` / `glass_defaultText` */
    val glassIcon: Color,

    // ---------------- 聊天背景与气泡 ----------------
    /** 聊天四角渐变 1..4 · 默认 accent 的背景色（Theme.java 默认主题数组第 9 列） */
    val chatBg1: Color,
    val chatBg2: Color,
    val chatBg3: Color,
    val chatBg4: Color,
    /** 聊天底纹（海浪图案）线条 · 图案强度 38% 的近似 */
    val chatPattern: Color,
    /** 她的气泡 · `chat_inBubble` */
    val bubbleIn: Color,
    /** · `chat_inBubbleSelected` */
    val bubbleInSelected: Color,
    /** 我的气泡 · `chat_outBubble` */
    val bubbleOut: Color,
    /** · `chat_outBubbleSelected` */
    val bubbleOutSelected: Color,
    /** 气泡底部 1dp 阴影 · `chat_inBubbleShadow` */
    val bubbleShadow: Color,
    /** 正文 · `chat_messageTextIn` */
    val bubbleTextIn: Color,
    /** · `chat_messageTextOut` */
    val bubbleTextOut: Color,
    /** · `chat_messageLinkIn` */
    val bubbleLinkIn: Color,
    /** · `chat_messageLinkOut` */
    val bubbleLinkOut: Color,
    /** 时间 · `chat_inTimeText` */
    val bubbleTimeIn: Color,
    /** · `chat_outTimeText` */
    val bubbleTimeOut: Color,
    /** 已发送 / 已读勾 · `chat_outSentCheck` */
    val bubbleCheck: Color,
    /** · `chat_outSentCheckSelected` */
    val bubbleCheckSelected: Color,
    /** 发送中时钟 · `chat_outSentClock` */
    val bubbleClock: Color,
    /** 时间（选中） · `chat_inTimeSelectedText` / `chat_outTimeSelectedText` */
    val bubbleTimeInSelected: Color,
    val bubbleTimeOutSelected: Color,
    /** 语音播放钮底：她 · `chat_inLoader` / 我 · `chat_outLoader` */
    val voiceButtonIn: Color,
    val voiceButtonOut: Color,
    /** 文件气泡图标底 · `chat_inFileBackground` / `chat_outFileBackground` */
    val fileBgIn: Color,
    val fileBgOut: Color,
    /** 文件大小等副信息 · `chat_inFileInfoText` / `chat_outFileInfoText` */
    val fileInfoIn: Color,
    val fileInfoOut: Color,
    /** 引用条 · `chat_inReplyLine` / `chat_outReplyLine` */
    val replyLineIn: Color,
    val replyLineOut: Color,
    /** 日期胶囊 · `chat_serviceBackground`（51%） */
    val serviceBubble: Color,
    /** · `chat_serviceBackgroundSelected` */
    val serviceBubbleSelected: Color,
    /** · `chat_serviceText` */
    val serviceText: Color,
    /** 图片角上的时间胶囊底：**自定**（02 里没有 Dark Blue 取值），半透明黑 40% */
    val mediaTimeBg: Color,
    /** 图片还没解出来时的底 · `chat_inFileBackground` */
    val mediaPlaceholder: Color,

    // ---------------- 文字 ----------------
    /** 主文字 · `windowBackgroundWhiteBlackText` */
    val textPrimary: Color,
    /** 会话名 / 抽屉项文字 · `chats_name` */
    val textName: Color,
    /** 次文字 · `windowBackgroundWhiteGrayText` */
    val textSecondary: Color,
    /** 行内第二行 · `windowBackgroundWhiteGrayText2` */
    val textSubtitle: Color,
    /** 顶栏状态（离线） · `actionBarDefaultSubtitle` */
    val textSecondaryOnBar: Color,
    /** 输入提示 · `chat_messagePanelHint` */
    val textHint: Color,
    /** 组下说明、时间、弱文字 · `windowBackgroundWhiteGrayText4` */
    val textCaption: Color,
    /** 输入栏图标 · `chat_messagePanelIcons` */
    val iconOnBar: Color,
    /** 图标灰 · `windowBackgroundWhiteGrayIcon` */
    val icon: Color,

    // ---------------- 强调 / 状态 ----------------
    /** 强调蓝（按钮文字、值、勾、进度） · `windowBackgroundWhiteBlueText` */
    val accent: Color,
    /** 分组标题 · `windowBackgroundWhiteBlueHeader` */
    val accentHeader: Color,
    /** 右侧值 · `windowBackgroundWhiteValueText` */
    val accentValue: Color,
    /** 顶栏「在线 / 正在输入」 · `chat_status` */
    val accentOnline: Color,
    /** 发送按钮 · `chat_messagePanelSend` */
    val accentStrong: Color,
    /** 实心按钮 / FAB 底 · `chats_actionBackground` */
    val accentFill: Color,
    /** 实心底上的字 / 图标 · `chats_actionIcon` */
    val onAccent: Color,
    /** 链接 · `windowBackgroundWhiteLinkText` */
    val link: Color,
    /** 危险红 · `text_RedRegular` */
    val danger: Color,
    /** 成功绿 · `windowBackgroundWhiteGreenText` */
    val success: Color,

    // ---------------- 线 / 按压 ----------------
    /** 分隔线（叠在卡片上的 58% 黑） · `divider` */
    val divider: Color,
    /** 列表按压 · `listSelectorSDK21` */
    val ripple: Color,
    /** 顶栏图标按压 · `actionBarDefaultSelector` */
    val rippleBar: Color,
    /** 输入框下划线 / 激活 · `windowBackgroundWhiteInputField(Activated)` */
    val inputLine: Color,
    val inputLineActive: Color,

    // ---------------- 控件 ----------------
    /** · `switchTrack` */
    val switchTrackOff: Color,
    /** · `switchTrackChecked` */
    val switchTrackOn: Color,
    /** 拇指 = `windowBackgroundWhite` */
    val switchThumb: Color,
    /** 复选框 · `checkboxSquareBackground` */
    val checkbox: Color,
    /** 单选未选 · `dialogRadioBackground` */
    val radioOff: Color,
    /** 未读数 · `chats_unreadCounter` / `chats_unreadCounterMuted` */
    val badge: Color,
    val badgeMuted: Color,
    /** 进度 · `player_progress` / `player_progressBackground` */
    val progress: Color,
    val progressTrack: Color,

    // ---------------- 语音 ----------------
    /** · `chat_inVoiceSeekbar` / `chat_inVoiceSeekbarFill` */
    val waveIn: Color,
    val waveInPlayed: Color,
    /** · `chat_outVoiceSeekbar` / `chat_outVoiceSeekbarFill` */
    val waveOut: Color,
    val waveOutPlayed: Color,

    // ---------------- 面板 ----------------
    /** 表情面板 · `chat_emojiPanelBackground` / `chat_emojiPanelIcon(Selected)` */
    val emojiPanelBg: Color,
    val emojiPanelIcon: Color,
    val emojiPanelIconSelected: Color,
    /** 滚到底按钮 · `chat_goDownButton` */
    val goDownButton: Color,
    /** 头像蓝 · `avatar_backgroundInProfileBlue` */
    val avatarBlue: Color,
    /** 抽屉项图标 / 副标题 · `chats_menuItemIcon` / `chats_menuPhone` */
    val drawerIcon: Color,
    val drawerSubtitle: Color,

    // ---------------- Bulletin ----------------
    /** · `undo_background`（95%） */
    val bulletinBg: Color,
    /** · `undo_infoColor` */
    val bulletinText: Color,
    /** · `undo_cancelColor` */
    val bulletinAction: Color,

    // ---------------- 图表 ----------------
    /** 第 4 段配色：**自定**紫（Telegram 没有对应键；前三段用 accent / accentHeader / success） */
    val chart4: Color,
    /** 热力图空格底色：**自定**，比卡片底 surface 亮一档（GitHub 空格同样比底亮）；有用量的格子是 accent × [WhaleAlpha.Heat1]..[WhaleAlpha.Heat4] */
    val heatEmpty: Color,

    // ---------------- 遮罩 ----------------
    /** 长按菜单 / 底部弹层压暗：黑 20% */
    val scrim: Color,
    /** 底部弹层压暗：黑 40%（Telegram 是 20%，审计里看不出层级，specs/README §4 裁定统一 40%） */
    val scrimSheet: Color,
    /** 对话框压暗：黑 50%（AlertDialog dimAlpha） */
    val scrimDialog: Color,
    /** 抽屉 / 滑动返回下层页：黑最多 38%（120×0.8/255） */
    val scrimDrawer: Color,

    // ---------------- 骨架屏 ----------------
    /** 骨架底 = 菜单底；扫光 = 列表按压色（FlickerLoadingView） */
    val skeleton: Color,
    val skeletonHighlight: Color,
)

/** Telegram Android「Dark Blue」（Q1）。 */
val WhaleDarkBlue = WhaleColors(
    isDark = true,
    bgPage = Color(0xFF151E27),
    surface = Color(0xFF1D2733),
    surfaceBar = Color(0xFF212D3B),
    surfaceBarGlass = Color(0xC2212D3B),
    glassStrokeTop = Color(0x28FFFFFF),
    glassStrokeBottom = Color(0x14FFFFFF),
    surfaceDrawer = Color(0xFF1B242F),
    surfaceDrawerHeader = Color(0xFF23303F),
    surfaceMenu = Color(0xD9283848),
    surfaceMenuSolid = Color(0xFF283848),
    menuSeparator = Color(0xFF1F2E3F),
    surfaceDialog = Color(0xFF212D3B),
    surfaceSheetGray = Color(0xFF1C2631),
    surfaceInput = Color(0xFF2A394B),
    searchField = Color(0xFF2A394B),
    glassIcon = Color(0xA0FFFFFF),

    chatBg1 = Color(0xFF0E141A),
    chatBg2 = Color(0xFF172431),
    chatBg3 = Color(0xFF0F171E),
    chatBg4 = Color(0xFF141C2B),
    chatPattern = Color(0x1A9ACDFF),
    bubbleIn = Color(0xFF232E3B),
    bubbleInSelected = Color(0xFF314A61),
    bubbleOut = Color(0xFF3E618A),
    bubbleOutSelected = Color(0xFF4B7DAF),
    bubbleShadow = Color(0x32000000),
    bubbleTextIn = Color(0xFFFAFAFA),
    bubbleTextOut = Color(0xFFFAFAFA),
    bubbleLinkIn = Color(0xFF79C4FC),
    bubbleLinkOut = Color(0xFF9ACDFF),
    bubbleTimeIn = Color(0xD98091A0),
    bubbleTimeOut = Color(0xFF8FBCDF),
    bubbleCheck = Color(0xFF87C7FF),
    bubbleCheckSelected = Color(0xFFBEDFFA),
    bubbleClock = Color(0xFF82ABCB),
    bubbleTimeInSelected = Color(0xFF8DB2D3),
    bubbleTimeOutSelected = Color(0xFFBEDFFA),
    voiceButtonIn = Color(0xFF609DD2),
    voiceButtonOut = Color(0xFF68A3D7),
    fileBgIn = Color(0xFF2F3C4A),
    fileBgOut = Color(0xFF47709A),
    fileInfoIn = Color(0xFF798997),
    fileInfoOut = Color(0xFF8FBCDF),
    replyLineIn = Color(0xFF79C4FC),
    replyLineOut = Color(0xFF9ACDFF),
    serviceBubble = Color(0x82354251),
    serviceBubbleSelected = Color(0x96477BAE),
    serviceText = Color(0xFFFFFFFF),
    mediaTimeBg = Color(0x66000000),
    mediaPlaceholder = Color(0xFF2F3C4A),

    textPrimary = Color(0xFFFFFFFF),
    textName = Color(0xFFE9EEF4),
    textSecondary = Color(0xFF7D8B99),
    textSubtitle = Color(0x6FF1FDFF),
    textSecondaryOnBar = Color(0x7DDBF2FF),
    textHint = Color(0x6EDBEFFF),
    textCaption = Color(0xC87D8B99),
    iconOnBar = Color(0x6EE0F0FF),
    icon = Color(0xFF7D8B99),

    accent = Color(0xFF64B5EF),
    accentHeader = Color(0xFF79C4FC),
    accentValue = Color(0xE079C4FC),
    accentOnline = Color(0xFF73BBF7),
    accentStrong = Color(0xFF229AF0),
    accentFill = Color(0xFF5FA3DE),
    onAccent = Color(0xFFFFFFFF),
    link = Color(0xFF5EABE1),
    danger = Color(0xFFEE686F),
    success = Color(0xFF61BD67),

    divider = Color(0x95000000),
    ripple = Color(0x14E6F7FF),
    rippleBar = Color(0x1ECFE8FF),
    inputLine = Color(0xFF485B6F),
    inputLineActive = Color(0xFF6EB2EF),

    switchTrackOff = Color(0xFF5D7484),
    switchTrackOn = Color(0xFF61A3D7),
    switchThumb = Color(0xFF1D2733),
    checkbox = Color(0xFF64B5E5),
    radioOff = Color(0xFF546679),
    badge = Color(0xFF64B5EF),
    badgeMuted = Color(0xFF3E5263),
    progress = Color(0xFF64B5EF),
    progressTrack = Color(0xFF394758),

    waveIn = Color(0xFF5D6F80),
    waveInPlayed = Color(0xFF64B5EF),
    waveOut = Color(0xE66591C3),
    waveOutPlayed = Color(0xFF86C4FB),

    emojiPanelBg = Color(0xFF1D2733),
    emojiPanelIcon = Color(0xFF677687),
    emojiPanelIconSelected = Color(0xFF64B5EF),
    goDownButton = Color(0xFF212D3B),
    avatarBlue = Color(0xFF549CDD),
    drawerIcon = Color(0xFF7E8D9A),
    drawerSubtitle = Color(0x93C0D0DD),

    bulletinBg = Color(0xF22C3B4D),
    bulletinText = Color(0xFFFFFFFF),
    bulletinAction = Color(0xFF85CAFF),

    chart4 = Color(0xFFB58AE0),
    heatEmpty = Color(0xFF2A3644),

    scrim = Color(0x33000000),
    scrimSheet = Color(0x66000000),
    scrimDialog = Color(0x80000000),
    scrimDrawer = Color(0x61000000),

    skeleton = Color(0xFF283848),
    skeletonHighlight = Color(0x14E6F7FF),
)

/** 页面底（= [WhaleDarkBlue].bgPage）的 ARGB：给 `@Preview(backgroundColor = …)` 这类只收常量的地方用。 */
const val PageBackgroundArgb: Long = 0xFF151E27

val LocalWhaleColors = staticCompositionLocalOf { WhaleDarkBlue }
