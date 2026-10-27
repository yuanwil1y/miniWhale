package whaleui.nightsky.ltd.minimax

/**
 * MiniMax 的语音模型 / 作图模型 / Base URL 的**运行时可配置值**（任务书第六节 6-1）。
 *
 * 昨天这三个是写死的常量。现在改成「可在设置里覆盖，默认值不变」：
 * 默认仍是 `speech-2.8-hd` / `image-01` / `https://api.minimax.cn`，
 * 覆盖值存在 SharedPreferences（[load] 由 App 启动时读一次），界面上暂时不加编辑入口。
 *
 * 之所以用可变单例而不是把设置对象一路透传：`WhaleMiniMaxClient` 是 Koin 单例，
 * 而这三个值要在**每次调用时**读（用户改完不用重启）。纯 JVM 单测里可以直接改这几个字段。
 */
object WhaleMiniMaxConfig {

    const val DEFAULT_BASE_URL: String = "https://api.minimax.cn"
    const val DEFAULT_SPEECH_MODEL: String = "speech-2.8-hd"
    const val DEFAULT_IMAGE_MODEL: String = "image-01"

    /** 三个值一起换的不可变快照（W-058：以前是三个 @Volatile 字段连写，读方可能读到新 baseUrl + 旧 model） */
    data class Values(
        val baseUrl: String = DEFAULT_BASE_URL,
        val speechModel: String = DEFAULT_SPEECH_MODEL,
        val imageModel: String = DEFAULT_IMAGE_MODEL,
    )

    /** 当前配置。一次请求要用到多个值时先取一次快照，再从快照里读。 */
    @Volatile
    var current: Values = Values()
        private set

    val baseUrl: String get() = current.baseUrl
    val speechModel: String get() = current.speechModel
    val imageModel: String get() = current.imageModel

    /** 全量覆盖一次（`null` / 空串 = 用默认值），一次原子替换。 */
    fun apply(baseUrl: String?, speechModel: String?, imageModel: String?) {
        current = Values(
            baseUrl = baseUrl?.trim()?.takeIf { it.isNotEmpty() }?.trimEnd('/') ?: DEFAULT_BASE_URL,
            speechModel = speechModel?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_SPEECH_MODEL,
            imageModel = imageModel?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_IMAGE_MODEL,
        )
    }

    fun reset() = apply(null, null, null)
}