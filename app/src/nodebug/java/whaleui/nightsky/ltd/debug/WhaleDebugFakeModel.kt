package whaleui.nightsky.ltd.debug

// 非 debug 构建用的空实现：假模型永远关着，闹钟轮照常调真实模型。
// 真正的实现在 src/debug/…/WhaleDebugFakeModel.kt；ChatService 用到的成员两份必须一致。
object WhaleDebugFakeModel {

    enum class Mode { Fixed, Silent }

    val mode: Mode? get() = null

    val fixedText: String get() = ""

    val enabled: Boolean get() = false
}
