package whaleui.nightsky.ltd.minimax

import android.content.Context

/**
 * MiniMax 那三个可配置值的**存盘**（任务书 6-1）。
 *
 * 界面暂时不加编辑入口，所以目前只有启动时读一次：存储里没有值就用默认值。
 * 存的是非敏感配置（模型 id / Base URL），Key 仍然只在 `SecretStore`。
 */
object WhaleMiniMaxPrefs {

    private const val PREFS = "whale_minimax"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_SPEECH_MODEL = "speech_model"
    private const val KEY_IMAGE_MODEL = "image_model"

    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
        load()
    }

    /** 读一次并应用到 [WhaleMiniMaxConfig]。 */
    fun load() {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        WhaleMiniMaxConfig.apply(
            baseUrl = prefs.getString(KEY_BASE_URL, null),
            speechModel = prefs.getString(KEY_SPEECH_MODEL, null),
            imageModel = prefs.getString(KEY_IMAGE_MODEL, null),
        )
    }

    fun save(baseUrl: String?, speechModel: String?, imageModel: String?) {
        WhaleMiniMaxConfig.apply(baseUrl, speechModel, imageModel)
        val saved = WhaleMiniMaxConfig.current
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.apply {
            putString(KEY_BASE_URL, saved.baseUrl)
            putString(KEY_SPEECH_MODEL, saved.speechModel)
            putString(KEY_IMAGE_MODEL, saved.imageModel)
        }?.apply()
    }
}