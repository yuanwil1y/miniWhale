package whaleui.nightsky.ltd.whale

import whaleui.nightsky.ltd.domain.settings.SecretProvider
import whaleui.nightsky.ltd.domain.settings.SecretStore
import whaleui.nightsky.ltd.domain.settings.SecretWriteResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * 密钥的内存缓存。
 *
 * 为什么需要：[SecretStore] 是 `suspend` 的（Keystore 解密要跑 IO），而
 * `WhaleMiniMaxClient` / 工具构造时只接受 `() -> String?` 这种同步取值。
 * 所以启动时和每次保存/清除密钥后刷新一次这里，运行期只读缓存；明文只在内存里，不落盘、不打印。
 */
object WhaleSecrets {

    @Volatile
    var deepSeek: String? = null
        private set

    @Volatile
    var miniMax: String? = null
        private set

    /** 代理密码（N-007）；代理鉴权器同步取值，没迁移完时由调用方退回设置里的旧明文 */
    @Volatile
    var proxyPassword: String? = null
        private set

    suspend fun refresh(store: SecretStore) {
        deepSeek = store.get(SecretProvider.DeepSeek)?.takeIf { it.isNotBlank() }
        miniMax = store.get(SecretProvider.MiniMax)?.takeIf { it.isNotBlank() }
        proxyPassword = store.get(SecretProvider.ProxyPassword)?.takeIf { it.isNotEmpty() }
    }

    /**
     * 写进 [store]，**真的存进去了**才更新缓存（W-045）：以前不管存没存进去都更新缓存、提示「已保存」，
     * 重启后 Key 就没了。实现抛出的异常（例如别的实现写盘出错）也折成失败，不让设置页的协程崩掉。
     */
    suspend fun save(store: SecretStore, provider: SecretProvider, value: String): SecretWriteResult {
        val result = writeCatching { store.set(provider, value) }
        if (result == SecretWriteResult.Saved) set(provider, value)
        return result
    }

    /** 从 [store] 清除，清掉了才清缓存；失败时同 [save] */
    suspend fun clear(store: SecretStore, provider: SecretProvider): SecretWriteResult {
        val result = writeCatching { store.clear(provider) }
        if (result == SecretWriteResult.Saved) set(provider, null)
        return result
    }

    private suspend fun writeCatching(write: suspend () -> SecretWriteResult): SecretWriteResult = try {
        write()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 不带 message：别的实现的异常信息里可能有路径之类的细节
        SecretWriteResult.Failed("写入失败（${e.javaClass.simpleName}）")
    }

    fun set(provider: SecretProvider, value: String?) {
        val normalized = value?.takeIf { it.isNotBlank() }
        when (provider) {
            SecretProvider.DeepSeek -> deepSeek = normalized
            SecretProvider.MiniMax -> miniMax = normalized
            // 密码可以有空格，只把空串当成没有
            SecretProvider.ProxyPassword -> proxyPassword = value?.takeIf { it.isNotEmpty() }
        }
    }
}
