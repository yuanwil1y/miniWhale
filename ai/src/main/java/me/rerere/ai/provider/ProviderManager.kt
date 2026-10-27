package me.rerere.ai.provider

import android.content.Context
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.OkHttpClient

/**
 * Provider管理器，负责注册和获取Provider实例
 *
 * 小鲸鱼只用 DeepSeek（OpenAI 兼容协议）；MiniMax 走 app 自己的 WhaleMiniMaxClient，不经过这里。
 * Claude / Google / ChatGPT / Grok 的协议实现已删除（B2），但它们的 [ProviderSetting] 类型仍保留，
 * 旧设置里的这类供应商能照常读入、原样写回；只是不能用来发请求，[getProviderByType] 会抛出说明性的错误。
 */
class ProviderManager(
    client: OkHttpClient,
    // 只用来给多 Key 轮换落盘（KeyRoulette.lru）；为 null 时轮换记录只在内存里
    context: Context?,
) {
    // 存储已注册的Provider实例
    private val providers = mutableMapOf<String, Provider<*>>()

    init {
        registerProvider(OPENAI, OpenAIProvider(client, context))
    }

    /**
     * 注册Provider实例
     *
     * @param name Provider名称
     * @param provider Provider实例
     */
    fun registerProvider(name: String, provider: Provider<*>) {
        providers[name] = provider
    }

    /**
     * 获取Provider实例
     *
     * @param name Provider名称
     * @return Provider实例，不存在时抛 [IllegalArgumentException]
     */
    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     *
     * @param setting Provider设置
     * @return Provider实例；不是 OpenAI 兼容类型时抛 [UnsupportedProviderException]
     */
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider(OPENAI)
            else -> throw UnsupportedProviderException(setting)
        } as Provider<T>
    }

    private companion object {
        const val OPENAI = "openai"
    }
}

/** 旧设置里留下的、此版本已不再支持的供应商类型（Claude / Google / ChatGPT / Grok）。 */
class UnsupportedProviderException(setting: ProviderSetting) : IllegalArgumentException(
    "「${setting.name}」的接口类型此版本已不支持，请改用 OpenAI 兼容的供应商（如 DeepSeek）"
)
