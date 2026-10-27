package whaleui.nightsky.ltd.whale

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import whaleui.nightsky.ltd.domain.settings.SecretProvider
import whaleui.nightsky.ltd.domain.settings.SecretStore
import whaleui.nightsky.ltd.domain.settings.SecretWriteResult

/**
 * DeepSeek Key 只存一份（W-028）：在 [SecretStore]（Keystore 加密）里。
 *
 * 以前 `WhaleKeyBridge` 会把它明文抄进 RikkaHub 设置（DataStore）里内置 DeepSeek 供应商的 `apiKey`，
 * 加密形同虚设。现在设置里的 `apiKey` 始终写空，发请求前由 [withRuntimeKey] 在内存里换上 SecretStore 的 Key：
 * 聊天（ChatRequestFactory）、OCR、高级设置拉模型列表、她的搜索工具都从这里取。
 *
 * 启动时 [migratePlaintextKey] 把旧版本留在设置里的明文搬进 SecretStore（SecretStore 已有值时以它为准），
 * 然后把明文清空。迁移完成前（启动初始化还在跑）发出的请求退回用设置里那份，不会因为时序报「没有 Key」。
 */
class WhaleProviderKeys(
    private val secretStore: SecretStore,
    private val settingsStore: SettingsStore,
) {

    /** 本次请求用的 DeepSeek Key；SecretStore 没有时退回 [providers] 里尚未迁移的明文，都没有返回 null。 */
    suspend fun deepSeekKey(providers: List<ProviderSetting>): String? =
        secretStore.get(SecretProvider.DeepSeek)?.takeIf { it.isNotBlank() }
            ?: providers.deepSeek()?.plainKey()

    /** 发请求前调用：内置 DeepSeek 供应商换上运行时 Key（只在内存里，不写回设置）；其它供应商原样返回。 */
    suspend fun withRuntimeKey(setting: ProviderSetting): ProviderSetting {
        if (setting.id != WhaleBootstrap.DEEPSEEK_PROVIDER_ID) return setting
        val key = deepSeekKey(listOf(setting)) ?: return setting
        return setting.withApiKey(key)
    }

    /**
     * 启动迁移：设置里残留的 DeepSeek 明文 → SecretStore（SecretStore 已有值就不覆盖），然后清空明文。
     * 幂等；没有残留时什么都不写。返回是否清掉了一份明文。
     */
    suspend fun migratePlaintextKey(): Boolean {
        val plain = settingsStore.awaitLoaded().providers.deepSeek()?.plainKey() ?: return false
        if (!secretStore.has(SecretProvider.DeepSeek) ||
            secretStore.get(SecretProvider.DeepSeek).isNullOrBlank()
        ) {
            // 没搬进去就不清明文（W-045）：留到下次启动再试，请求照旧用这份明文（deepSeekKey 的回退）
            // 缓存（WhaleSecrets）由启动流程随后 refresh，这里只管存储
            val result = try {
                secretStore.set(SecretProvider.DeepSeek, plain)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                SecretWriteResult.Failed(e.javaClass.simpleName)
            }
            if (result is SecretWriteResult.Failed) {
                runCatching { android.util.Log.w("WhaleProviderKeys", "plaintext key migration failed: ${result.reason}") }
                return false
            }
        }
        settingsStore.update { settings ->
            settings.copy(
                providers = settings.providers.map {
                    if (it.id == WhaleBootstrap.DEEPSEEK_PROVIDER_ID) it.withApiKey("") else it
                },
            )
        }
        return true
    }

    /**
     * 启动迁移（N-007）：设置里残留的代理密码明文 → SecretStore，然后清空明文；做法同 [migratePlaintextKey]：
     * SecretStore 已有值就以它为准；**搬不进去时不清明文**，代理鉴权照旧用设置里那份（见 DataSourceModule），下次启动再试。
     * 清明文之前先把密码放进 [WhaleSecrets] 缓存。返回是否清掉了一份明文。
     */
    suspend fun migratePlaintextProxyPassword(): Boolean {
        val plain = settingsStore.awaitLoaded().networkSetting.proxyPassword.takeIf { it.isNotEmpty() } ?: return false
        if (secretStore.get(SecretProvider.ProxyPassword).isNullOrEmpty()) {
            val result = try {
                secretStore.set(SecretProvider.ProxyPassword, plain)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                SecretWriteResult.Failed(e.javaClass.simpleName)
            }
            if (result is SecretWriteResult.Failed) {
                runCatching { android.util.Log.w("WhaleProviderKeys", "proxy password migration failed: ${result.reason}") }
                return false
            }
        }
        // 先放进缓存再清明文：两步之间发出的请求也拿得到密码
        WhaleSecrets.set(SecretProvider.ProxyPassword, secretStore.get(SecretProvider.ProxyPassword) ?: plain)
        settingsStore.update { settings ->
            settings.copy(networkSetting = settings.networkSetting.copy(proxyPassword = ""))
        }
        return true
    }

    private fun List<ProviderSetting>.deepSeek(): ProviderSetting? =
        firstOrNull { it.id == WhaleBootstrap.DEEPSEEK_PROVIDER_ID }

    private fun ProviderSetting.plainKey(): String? =
        (this as? ProviderSetting.ApiKey)?.apiKey?.takeIf { it.isNotBlank() }

    /** 只处理用 API Key 鉴权的提供商，账号登录那类不碰。 */
    private fun ProviderSetting.withApiKey(key: String): ProviderSetting = when (this) {
        is ProviderSetting.OpenAI -> copy(apiKey = key)
        is ProviderSetting.Google -> copy(apiKey = key)
        is ProviderSetting.Claude -> copy(apiKey = key)
        else -> this
    }
}
