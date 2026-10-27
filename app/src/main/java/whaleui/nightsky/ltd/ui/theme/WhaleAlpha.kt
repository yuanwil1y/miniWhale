package whaleui.nightsky.ltd.ui.theme

// ---------------------------------------------------------------------------
// 透明度 token（U0）。页面里不再写 `.copy(alpha = 0.xx)` / `alpha = 0.xx` 字面量。
// 颜色本身带的透明度（玻璃 76%、遮罩 20% 等）已经写在 WhaleColors 里，这里只放「对任意颜色打折」的档位。
// ---------------------------------------------------------------------------

object WhaleAlpha {
    /** 完全透明 / 不透明（动画起止点） */
    const val Hidden = 0f
    const val Visible = 1f
    /** 禁用态：文字、按钮、开关统一 50%（Telegram 禁用行 alpha 0.5） */
    const val Disabled = 0.5f
    /** 次要内容（Telegram 选择框未选等 0.6 档） */
    const val Muted = 0.6f
    /** 弱一档的正文（引用摘要、服务卡副标题 80%） */
    const val Soft = 0.8f
    /** 几乎不透明（顶部渐隐的起点等） */
    const val Solid = 0.95f
    /** 玻璃底不透明度（BlurredBackgroundProviderImpl） */
    const val Glass = 0.76f
    /** 菜单玻璃不透明度 */
    const val MenuGlass = 0.85f
    /** 拖动手柄、占位线这类很淡的装饰 */
    const val Faint = 0.3f
    /** 柔和高光 / 选中底（Telegram chat_selectedBackground 16%） */
    const val Tint = 0.16f
    /** 极淡的面（列表按压 8%） */
    const val Ghost = 0.08f
    /** 列表压暗、长按遮罩（20%） */
    const val Scrim = 0.2f
    /**
     * 资料页 token 热力图的 4 档（accent 打折，**自定**）。用户要求「容易亮」：第 1 档就要明显是蓝，取 45%；
     * 其余三档按观感等分到 100%。
     */
    const val Heat1 = 0.45f
    const val Heat2 = 0.63f
    const val Heat3 = 0.82f
    const val Heat4 = 1f
    /** 按下的按钮缩放（不是透明度，但同属「按压」档位） */
    const val PressedScale = 0.98f
    /** 气泡按压 / 卡片按压的缩放 */
    const val PressedScaleStrong = 0.9f
}
