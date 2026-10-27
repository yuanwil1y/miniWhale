package whaleui.nightsky.ltd.whale

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import whaleui.nightsky.ltd.domain.settings.SecretProvider
import whaleui.nightsky.ltd.domain.settings.SecretStore
import whaleui.nightsky.ltd.domain.settings.SecretWriteResult
import java.io.File

/**
 * W-028：DeepSeek Key 只存在 SecretStore。用真实的 [SettingsStore]（临时目录里的 DataStore 文件），
 * 检查启动迁移、请求时取到的 Key、以及设置文件里不再有明文。
 */
class WhaleProviderKeysTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var store: SettingsStore
    private val secrets = MemorySecretStore()
    private lateinit var keys: WhaleProviderKeys

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        file = File(temp.root, "settings.preferences_pb")
        val disk = PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.toOkioPath() }),
            scope = scope,
        )
        store = SettingsStore(disk, scope)
        keys = WhaleProviderKeys(secrets, store)
    }

    @After
    fun tearDown() = runBlocking {
        WhaleSecrets.set(SecretProvider.ProxyPassword, null)
        scope.coroutineContext.job.cancelAndJoin()
    }

    /** 模拟旧版本：WhaleKeyBridge 把 Key 明文写进了内置 DeepSeek 供应商 */
    private fun writePlaintext(key: String) = runBlocking {
        store.awaitLoaded()
        store.update { settings ->
            settings.copy(providers = settings.providers.map {
                if (it.id == WhaleBootstrap.DEEPSEEK_PROVIDER_ID) (it as ProviderSetting.OpenAI).copy(apiKey = key) else it
            })
        }
    }

    private fun deepSeek(): ProviderSetting.OpenAI =
        store.settingsFlow.value.providers.first { it.id == WhaleBootstrap.DEEPSEEK_PROVIDER_ID } as ProviderSetting.OpenAI

    private fun diskText(): String = if (file.exists()) String(file.readBytes(), Charsets.ISO_8859_1) else ""

    @Test
    fun `plaintext key moves into SecretStore and is wiped from the settings file`() = runBlocking {
        val plain = "sk-old-plaintext-7777"
        writePlaintext(plain)
        assertTrue("前提：旧版本的设置文件里有明文", diskText().contains(plain))

        assertTrue(keys.migratePlaintextKey())

        assertEquals(plain, secrets.values[SecretProvider.DeepSeek])
        assertEquals("", deepSeek().apiKey)
        assertFalse("设置文件里不能再有明文", diskText().contains(plain))
        assertFalse("再跑一次没有可迁移的", keys.migratePlaintextKey())
    }

    @Test
    fun `migration keeps the plaintext when SecretStore cannot store the key`() = runBlocking {
        val plain = "sk-old-plaintext-8888"
        writePlaintext(plain)
        secrets.cannotStore = true

        assertFalse("没搬成功不算迁移完成", keys.migratePlaintextKey())

        assertEquals("明文要留着，下次启动再试；清掉的话 Key 就彻底丢了", plain, deepSeek().apiKey)
        assertEquals(plain, keys.deepSeekKey(store.settingsFlow.value.providers))
    }

    @Test
    fun `SecretStore wins when both have a key`() = runBlocking {
        secrets.values[SecretProvider.DeepSeek] = "sk-secret-store-1111"
        writePlaintext("sk-stale-plaintext-2222")

        keys.migratePlaintextKey()

        assertEquals("sk-secret-store-1111", secrets.values[SecretProvider.DeepSeek])
        assertEquals("", deepSeek().apiKey)
        assertFalse(diskText().contains("sk-stale-plaintext-2222"))
    }

    private fun writeProxyPassword(password: String) = runBlocking {
        store.awaitLoaded()
        store.update { it.copy(networkSetting = it.networkSetting.copy(proxyPassword = password)) }
    }

    @Test
    fun `plaintext proxy password moves into SecretStore and is wiped from the settings file`() = runBlocking {
        writeProxyPassword("proxy-old-pass-6161")
        assertTrue("前提：旧版本的设置文件里有明文", diskText().contains("proxy-old-pass-6161"))

        assertTrue(keys.migratePlaintextProxyPassword())

        assertEquals("proxy-old-pass-6161", secrets.values[SecretProvider.ProxyPassword])
        assertEquals("清明文前已放进缓存，代理鉴权不断档", "proxy-old-pass-6161", WhaleSecrets.proxyPassword)
        assertEquals("", store.settingsFlow.value.networkSetting.proxyPassword)
        assertFalse(diskText().contains("proxy-old-pass-6161"))
        assertFalse("再跑一次没有可迁移的", keys.migratePlaintextProxyPassword())
    }

    @Test
    fun `proxy password plaintext stays when SecretStore cannot store it`() = runBlocking {
        writeProxyPassword("proxy-old-pass-6262")
        secrets.cannotStore = true

        assertFalse(keys.migratePlaintextProxyPassword())

        assertEquals("proxy-old-pass-6262", store.settingsFlow.value.networkSetting.proxyPassword)
        assertEquals(null, secrets.values[SecretProvider.ProxyPassword])
        assertEquals(null, WhaleSecrets.proxyPassword)
    }

    @Test
    fun `stored proxy password wins over stale plaintext`() = runBlocking {
        secrets.values[SecretProvider.ProxyPassword] = "proxy-in-store-6363"
        writeProxyPassword("proxy-stale-6464")

        assertTrue(keys.migratePlaintextProxyPassword())

        assertEquals("proxy-in-store-6363", secrets.values[SecretProvider.ProxyPassword])
        assertEquals("proxy-in-store-6363", WhaleSecrets.proxyPassword)
        assertFalse(diskText().contains("proxy-stale-6464"))
    }

    @Test
    fun `nothing to migrate leaves SecretStore untouched`() = runBlocking {
        store.awaitLoaded()
        assertFalse(keys.migratePlaintextKey())
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun `request setting gets the SecretStore key in memory only`() = runBlocking {
        store.awaitLoaded()
        secrets.values[SecretProvider.DeepSeek] = "sk-runtime-3333"
        val withKey = keys.withRuntimeKey(deepSeek()) as ProviderSetting.OpenAI
        assertEquals("sk-runtime-3333", withKey.apiKey)
        assertEquals("设置本身不变", "", deepSeek().apiKey)
        assertFalse(diskText().contains("sk-runtime-3333"))

        val other = ProviderSetting.OpenAI(name = "其它", baseUrl = "https://example.com/v1")
        assertSame("其它供应商原样返回", other, keys.withRuntimeKey(other))
    }

    @Test
    fun `before migration a request falls back to the leftover plaintext`() = runBlocking {
        writePlaintext("sk-not-yet-migrated-4444")
        assertEquals("sk-not-yet-migrated-4444", keys.deepSeekKey(store.settingsFlow.value.providers))
        assertEquals("sk-not-yet-migrated-4444", (keys.withRuntimeKey(deepSeek()) as ProviderSetting.OpenAI).apiKey)
    }

    @Test
    fun `outgoing request carries the SecretStore key`() = runBlocking {
        store.awaitLoaded()
        secrets.values[SecretProvider.DeepSeek] = "sk-on-the-wire-5555"
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"deepseek-flash"}]}"""))
        server.start()
        try {
            val setting = deepSeek().copy(baseUrl = server.url("/v1").toString().trimEnd('/'))
            val ids = OpenAIProvider(OkHttpClient()).listModels(keys.withRuntimeKey(setting) as ProviderSetting.OpenAI)
            assertEquals(listOf("deepseek-flash"), ids.map { it.modelId })
            assertEquals("Bearer sk-on-the-wire-5555", server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}

private class MemorySecretStore : SecretStore {
    val values = java.util.concurrent.ConcurrentHashMap<SecretProvider, String>()

    /** 存不进去（Keystore 不可用 / 写盘失败，W-045） */
    @Volatile
    var cannotStore = false
    override suspend fun get(provider: SecretProvider): String? = values[provider]
    override suspend fun set(provider: SecretProvider, value: String): SecretWriteResult {
        if (cannotStore) return SecretWriteResult.Failed("系统密钥库暂时不可用，无法加密")
        values[provider] = value
        return SecretWriteResult.Saved
    }

    override suspend fun clear(provider: SecretProvider): SecretWriteResult {
        values.remove(provider)
        return SecretWriteResult.Saved
    }

    override suspend fun has(provider: SecretProvider): Boolean = values.containsKey(provider)
}
