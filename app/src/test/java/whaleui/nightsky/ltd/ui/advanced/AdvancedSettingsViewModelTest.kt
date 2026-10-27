package whaleui.nightsky.ltd.ui.advanced

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.viewModelScope
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.android.LogEntry
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import whaleui.nightsky.ltd.domain.settings.SecretProvider
import whaleui.nightsky.ltd.domain.settings.SecretStore
import whaleui.nightsky.ltd.domain.settings.SecretWriteResult
import whaleui.nightsky.ltd.whale.WhaleBootstrap
import whaleui.nightsky.ltd.whale.WhaleSecrets
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

/**
 * 高级设置（S9a）的 ViewModel：用真实的 [SettingsStore]（临时目录里的 DataStore），
 * SecretStore / MiniMax 配置 / 对话同步 / 在线模型列表 / 日志源换成内存假实现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedSettingsViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private lateinit var scope: CoroutineScope
    private lateinit var store: SettingsStore
    private val secrets = MemorySecretStore()
    private val chatTarget = RecordingChatTarget()
    private val miniMax = MemoryMiniMaxConfig()
    private val logs = MemoryLogSource()
    private var remoteIds: List<String> = emptyList()

    private val flash = Model(
        modelId = WhaleBootstrap.DEEPSEEK_FLASH_MODEL_ID,
        displayName = WhaleBootstrap.DEEPSEEK_FLASH_MODEL_ID,
        abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
    )
    private val plain = Model(modelId = "deepseek-plain", displayName = "deepseek-plain", abilities = listOf(ModelAbility.TOOL))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = File(temp.root, "settings.preferences_pb")
        val disk = PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.toOkioPath() }),
            scope = scope,
        )
        store = SettingsStore(disk, scope)
        runBlocking {
            store.awaitLoaded()
            store.update { settings ->
                settings.copy(
                    providers = settings.providers.map {
                        if (it.id == WhaleBootstrap.DEEPSEEK_PROVIDER_ID) {
                            // 原生供应商里那份明文：高级设置不能拿它当 Key 的来源（W-028）
                            (it as ProviderSetting.OpenAI).copy(apiKey = "sk-native-copy-zzzz9999", models = listOf(flash, plain))
                        } else {
                            it
                        }
                    },
                    assistants = settings.assistants + Assistant(
                        id = chatTarget.id,
                        name = WhaleBootstrap.ASSISTANT_NAME,
                        chatModelId = flash.id,
                        reasoningLevel = ReasoningLevel.LOW,
                    ),
                )
            }
        }
    }

    private val created = mutableListOf<AdvancedSettingsViewModel>()

    @After
    fun tearDown() {
        // 先等 ViewModel 里还在写盘的协程收尾（它们会切回 Main），再拆 Main，免得异常漏到下一个测试类
        runBlocking {
            created.forEach { vm -> withTimeout(10_000) { vm.viewModelScope.coroutineContext.job.cancelAndJoin() } }
            scope.coroutineContext.job.cancelAndJoin()
        }
        Dispatchers.resetMain()
        WhaleSecrets.set(SecretProvider.DeepSeek, null)
        WhaleSecrets.set(SecretProvider.MiniMax, null)
        WhaleSecrets.set(SecretProvider.ProxyPassword, null)
    }

    private fun viewModel() = AdvancedSettingsViewModel(
        settingsStore = store,
        secretStore = secrets,
        miniMaxConfig = miniMax,
        remoteModels = { remoteIds },
        chatTarget = chatTarget,
        logSource = logs,
    ).also { created += it }

    private fun settings(): Settings = store.settingsFlow.value
    private fun deepSeek(): ProviderSetting.OpenAI =
        settings().providers.first { it.id == WhaleBootstrap.DEEPSEEK_PROVIDER_ID } as ProviderSetting.OpenAI
    private fun whale(): Assistant = settings().assistants.first { it.id == chatTarget.id }

    // ------------------------------------------------------------------ Key

    @Test
    fun `key status comes from SecretStore only, never from the native provider copy`() {
        val vm = viewModel()
        eventually { vm.uiState.value.loaded }
        assertFalse("SecretStore 是空的，原生供应商里那份明文不算", vm.uiState.value.deepSeekKey.saved)
        assertFalse(vm.uiState.value.toString().contains("zzzz9999"))
    }

    @Test
    fun `saving a key writes SecretStore only, never the native provider, and shows no part of it`() {
        val vm = viewModel()
        val key = "sk-test-0123456789abcd"
        vm.openEditor(AdvancedField.DeepSeekKey)
        assertEquals("密钥输入框永远从空白开始", "", vm.uiState.value.editor?.initial)
        vm.submitEditor("  $key  ")

        eventually { secrets.values[SecretProvider.DeepSeek] == key }
        assertEquals("原生供应商那份不跟着写（W-028）", "sk-native-copy-zzzz9999", deepSeek().apiKey)
        assertEquals(key, WhaleSecrets.deepSeek)
        eventually { vm.uiState.value.deepSeekKey == KeyStatus(saved = true) }
        assertFalse("界面状态里不能有 Key 原文", vm.uiState.value.toString().contains(key))
        assertFalse("连后 4 位也不能有", vm.uiState.value.toString().contains("abcd"))
    }

    @Test
    fun `blank key input leaves the stored key alone and clear removes it everywhere`() {
        secrets.values[SecretProvider.MiniMax] = "mm-key-0000-1111"
        val vm = viewModel()
        eventually { vm.uiState.value.miniMaxKey.saved }

        vm.openEditor(AdvancedField.MiniMaxKey)
        vm.submitEditor("   ")
        assertEquals("mm-key-0000-1111", secrets.values[SecretProvider.MiniMax])

        vm.clearSecret(SecretProvider.MiniMax)
        eventually { !vm.uiState.value.miniMaxKey.saved }
        assertNull(secrets.values[SecretProvider.MiniMax])
        assertNull(WhaleSecrets.miniMax)
    }

    @Test
    fun `clearing a key asks first, cancel keeps it, confirm removes it`() {
        secrets.values[SecretProvider.DeepSeek] = "sk-test-0123456789abcd"
        val vm = viewModel()
        eventually { vm.uiState.value.deepSeekKey.saved }

        vm.openEditor(AdvancedField.DeepSeekKey)
        assertEquals(SecretProvider.DeepSeek, AdvancedField.DeepSeekKey.keyProvider)
        assertEquals("粘贴新的密钥，留空保持不变", AdvancedField.DeepSeekKey.placeholder)
        vm.requestClearSecret(SecretProvider.DeepSeek)
        assertEquals(SecretProvider.DeepSeek, vm.uiState.value.clearKeyConfirm)
        assertNotNull("确认框盖在密钥对话框上，对话框还在", vm.uiState.value.editor)

        vm.cancelClearSecret()
        assertNull(vm.uiState.value.clearKeyConfirm)
        assertNotNull(vm.uiState.value.editor)
        assertEquals("取消后密钥不动", "sk-test-0123456789abcd", secrets.values[SecretProvider.DeepSeek])

        vm.requestClearSecret(SecretProvider.DeepSeek)
        vm.clearSecret(SecretProvider.DeepSeek)
        assertNull(vm.uiState.value.clearKeyConfirm)
        assertNull(vm.uiState.value.editor)
        eventually { !vm.uiState.value.deepSeekKey.saved }
        assertNull(secrets.values[SecretProvider.DeepSeek])
    }

    @Test
    fun `no clear confirmation for a key that is not filled`() {
        val vm = viewModel()
        eventually { vm.uiState.value.loaded }
        vm.requestClearSecret(SecretProvider.MiniMax)
        assertNull(vm.uiState.value.clearKeyConfirm)
    }

    @Test
    fun `failing SecretStore write reports an error instead of success`() {
        secrets.failWrites = true
        val vm = viewModel()
        vm.saveSecret(SecretProvider.DeepSeek, "sk-test-0123456789abcd")
        eventually { vm.uiState.value.toast != null }
        assertTrue(vm.uiState.value.toast!!.text.contains("失败"))
        assertFalse(vm.uiState.value.deepSeekKey.saved)
    }

    @Test
    fun `a key the store could not write is reported as a failure and never cached`() {
        secrets.dropWrites = true
        val vm = viewModel()
        eventually { vm.uiState.value.loaded }
        vm.saveSecret(SecretProvider.DeepSeek, "sk-test-0123456789abcd")
        eventually { vm.uiState.value.toast != null }
        assertTrue(vm.uiState.value.toast!!.text, vm.uiState.value.toast!!.text.contains("失败"))
        assertFalse(vm.uiState.value.deepSeekKey.saved)
        assertNull("没存进去就不能进缓存，否则重启前能用、重启后消失", WhaleSecrets.deepSeek)
    }

    @Test
    fun `a failing clear keeps showing the key as saved`() {
        secrets.values[SecretProvider.MiniMax] = "mm-key-0000-1111"
        val vm = viewModel()
        eventually { vm.uiState.value.miniMaxKey.saved }
        secrets.failWrites = true
        vm.clearSecret(SecretProvider.MiniMax)
        eventually { vm.uiState.value.toast != null }
        assertTrue(vm.uiState.value.toast!!.text.contains("失败"))
        assertTrue(vm.uiState.value.miniMaxKey.saved)
    }

    // ------------------------------------------------------------------ 供应商

    @Test
    fun `deepseek base url is written to the provider, blank restores default, bad url rejected`() {
        val vm = viewModel()
        eventually { vm.uiState.value.loaded }

        vm.openEditor(AdvancedField.DeepSeekBaseUrl)
        vm.submitEditor("https://example.invalid/v1/")
        eventually { deepSeek().baseUrl == "https://example.invalid/v1" }
        eventually { vm.uiState.value.deepSeekBaseUrl == "https://example.invalid/v1" }

        vm.openEditor(AdvancedField.DeepSeekBaseUrl)
        vm.submitEditor("ftp://nope")
        assertEquals("https://example.invalid/v1", deepSeek().baseUrl)

        vm.openEditor(AdvancedField.DeepSeekBaseUrl)
        vm.submitEditor("")
        eventually { deepSeek().baseUrl == AdvancedDefaults.DEEPSEEK_BASE_URL }
    }

    @Test
    fun `minimax fields go to the MiniMax config store`() {
        val vm = viewModel()
        vm.openEditor(AdvancedField.MiniMaxSpeechModel)
        vm.submitEditor("speech-test")
        vm.openEditor(AdvancedField.MiniMaxBaseUrl)
        vm.submitEditor("https://minimax.example")
        assertEquals(MiniMaxConfigValues("https://minimax.example", "speech-test", "image-01"), miniMax.saved)
        assertEquals("speech-test", vm.uiState.value.miniMax.speechModel)
    }

    // ------------------------------------------------------------------ 模型

    @Test
    fun `selecting an existing model writes the assistant and syncs the conversation`() {
        val vm = viewModel()
        eventually { vm.uiState.value.currentModelId == flash.modelId }
        assertTrue(vm.uiState.value.reasoningSupported)

        vm.selectModel(plain.modelId)
        eventually { whale().chatModelId == plain.id }
        eventually { chatTarget.applied.lastOrNull() == (plain.id to ReasoningLevel.LOW) }
        eventually { vm.uiState.value.currentModelId == plain.modelId }
        assertFalse("deepseek-plain 没有 REASONING", vm.uiState.value.reasoningSupported)
    }

    @Test
    fun `a manual model id is added to the deepseek provider with tools enabled`() {
        val vm = viewModel()
        eventually { vm.uiState.value.loaded }
        vm.openEditor(AdvancedField.CustomModelId)
        vm.submitEditor("deepseek-manual-x")

        eventually { deepSeek().models.any { it.modelId == "deepseek-manual-x" } }
        val added = deepSeek().models.first { it.modelId == "deepseek-manual-x" }
        assertTrue(ModelAbility.TOOL in added.abilities)
        eventually { whale().chatModelId == added.id }
        eventually { chatTarget.applied.lastOrNull()?.first == added.id }
    }

    @Test
    fun `remove model drops an unused model but never the current one or flash`() {
        val vm = viewModel()
        eventually { vm.uiState.value.currentModelId == flash.modelId }
        vm.removeModel(flash.modelId)
        vm.removeModel(plain.modelId)
        eventually { deepSeek().models.none { it.modelId == plain.modelId } }
        assertTrue(deepSeek().models.any { it.modelId == flash.modelId })
    }

    @Test
    fun `reasoning switch and level are written back to the assistant`() {
        val vm = viewModel()
        eventually { vm.uiState.value.loaded }

        vm.setReasoningEnabled(false)
        eventually { whale().reasoningLevel == ReasoningLevel.OFF }
        eventually { chatTarget.applied.lastOrNull() == (flash.id to ReasoningLevel.OFF) }

        vm.setReasoningLevel(ReasoningLevel.HIGH)
        eventually { whale().reasoningLevel == ReasoningLevel.HIGH }
        eventually { vm.uiState.value.reasoningLevel == ReasoningLevel.HIGH }
        eventually { chatTarget.applied.lastOrNull() == (flash.id to ReasoningLevel.HIGH) }
    }

    @Test
    fun `refreshed online models are offered but not written until selected`() {
        remoteIds = listOf("deepseek-online-a", flash.modelId)
        val vm = viewModel()
        eventually { vm.uiState.value.loaded }
        vm.refreshModels()
        eventually { vm.uiState.value.models.any { it.modelId == "deepseek-online-a" } }
        val online = vm.uiState.value.models.first { it.modelId == "deepseek-online-a" }
        assertNull(online.uuid)
        assertEquals("已有的不重复", 1, vm.uiState.value.models.count { it.modelId == flash.modelId })
        assertFalse(deepSeek().models.any { it.modelId == "deepseek-online-a" })
    }

    // ------------------------------------------------------------------ 代理

    @Test
    fun `proxy edits are written to the network setting`() {
        val vm = viewModel()
        eventually { vm.uiState.value.loaded }

        vm.setProxyEnabled(true)
        vm.openEditor(AdvancedField.ProxyHost)
        vm.submitEditor("127.0.0.1")
        assertEquals("端口还没填，不能生效", "", settings().networkSetting.proxyUrl)
        assertTrue(vm.uiState.value.proxy.incomplete)

        vm.openEditor(AdvancedField.ProxyPort)
        vm.submitEditor("7890")
        eventually { settings().networkSetting.proxyUrl == "http://127.0.0.1:7890" }

        vm.setProxyType(ProxyType.Socks)
        eventually { settings().networkSetting.proxyUrl == "socks5://127.0.0.1:7890" }

        vm.openEditor(AdvancedField.ProxyUsername)
        vm.submitEditor("me")
        vm.openEditor(AdvancedField.ProxyPassword)
        vm.submitEditor("p w")
        // N-007：密码进 SecretStore 和缓存，设置里不留明文
        eventually { settings().networkSetting.proxyUsername == "me" && secrets.values[SecretProvider.ProxyPassword] == "p w" }
        assertEquals("", settings().networkSetting.proxyPassword)
        assertEquals("p w", WhaleSecrets.proxyPassword)
        eventually { vm.uiState.value.proxy.passwordSet }

        vm.setProxyEnabled(false)
        eventually { settings().networkSetting.proxyUrl == "" }
        assertEquals("关掉后地址仍留在本页", "127.0.0.1", vm.uiState.value.proxy.host)
    }

    @Test
    fun `saved proxy is restored into the page`() {
        runBlocking {
            store.update {
                it.copy(networkSetting = it.networkSetting.copy(proxyUrl = "socks5://10.0.0.2:1080", proxyPassword = "x"))
            }
        }
        val vm = viewModel()
        eventually { vm.uiState.value.loaded }
        val proxy = vm.uiState.value.proxy
        assertEquals(ProxyDraft(enabled = true, type = ProxyType.Socks, host = "10.0.0.2", port = "1080", passwordSet = true), proxy)
    }

    @Test
    fun `proxy password kept in SecretStore shows as set`() {
        secrets.values[SecretProvider.ProxyPassword] = "secret"
        runBlocking {
            store.update { it.copy(networkSetting = it.networkSetting.copy(proxyUrl = "http://10.0.0.2:8080")) }
        }
        val vm = viewModel()
        eventually { vm.uiState.value.loaded && vm.uiState.value.proxy.passwordSet }
    }

    @Test
    fun `proxy password that cannot be stored is not reported as saved and old plaintext stays`() {
        runBlocking {
            store.update { it.copy(networkSetting = it.networkSetting.copy(proxyPassword = "old")) }
        }
        secrets.dropWrites = true
        val vm = viewModel()
        eventually { vm.uiState.value.loaded }
        vm.openEditor(AdvancedField.ProxyPassword)
        vm.submitEditor("new")
        eventually { vm.uiState.value.toast?.text?.startsWith("代理密码保存失败") == true }
        assertEquals("存不进去时不动旧明文", "old", settings().networkSetting.proxyPassword)
        assertEquals(null, secrets.values[SecretProvider.ProxyPassword])
        assertEquals(null, WhaleSecrets.proxyPassword)
    }

    // ------------------------------------------------------------------ 日志

    @Test
    fun `log list and copied text never contain the key`() {
        val key = "sk-live-key-abcdef123456"
        secrets.values[SecretProvider.DeepSeek] = key
        logs.entries += LogEntry.RequestLog(
            tag = "HTTP",
            url = "https://example.invalid/v1/chat/completions",
            method = "POST",
            requestHeaders = mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
            requestBody = """{"model":"deepseek-flash","note":"$key"}""",
            error = "Unable to resolve host",
        )
        logs.entries += LogEntry.TextLog(tag = "ChatService", message = "runTurn failed with key=$key")
        val vm = viewModel()
        vm.openSection(AdvancedSection.Logs)
        eventually { vm.uiState.value.logs.size == 2 }
        assertFalse(vm.uiState.value.logs.toString().contains(key))
        val request = vm.uiState.value.logs.first { it.isRequest }
        assertEquals("deepseek-flash", request.model)
        assertTrue(request.failed)

        val copied = runBlocking { vm.copyText(request.id) }
        assertNotNull(copied)
        assertFalse(copied!!.contains(key))
        assertTrue(copied.contains("https://example.invalid/v1/chat/completions"))

        vm.setRequestLogging(true)
        assertTrue(logs.enabled)
        vm.clearLogs()
        assertTrue(logs.entries.isEmpty())
        assertTrue(vm.uiState.value.logs.isEmpty())
    }

    // ------------------------------------------------------------------ 工具

    private fun eventually(timeoutMs: Long = 10_000, condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("条件在 ${timeoutMs}ms 内没有满足")
            delay(10)
        }
    }
}

private class MemorySecretStore : SecretStore {
    val values = java.util.concurrent.ConcurrentHashMap<SecretProvider, String>()

    @Volatile
    var failWrites = false

    /** Keystore 加密不了：什么都不写（旧实现还不报错，W-045） */
    @Volatile
    var dropWrites = false

    override suspend fun get(provider: SecretProvider): String? = values[provider]
    override suspend fun set(provider: SecretProvider, value: String): SecretWriteResult {
        if (failWrites) throw java.io.IOException("disk full")
        if (dropWrites) return SecretWriteResult.Failed("系统密钥库暂时不可用，无法加密")
        values[provider] = value
        return SecretWriteResult.Saved
    }

    override suspend fun clear(provider: SecretProvider): SecretWriteResult {
        if (failWrites) return SecretWriteResult.Failed("写入本机存储失败（无法写入密钥文件）")
        values.remove(provider)
        return SecretWriteResult.Saved
    }

    override suspend fun has(provider: SecretProvider): Boolean = values.containsKey(provider)
}

private class RecordingChatTarget : WhaleChatTarget {
    val id: Uuid = Uuid.random()
    val applied = CopyOnWriteArrayList<Pair<Uuid, ReasoningLevel>>()
    override fun assistantId(): Uuid = id
    override suspend fun applyToConversation(chatModelId: Uuid, reasoningLevel: ReasoningLevel) {
        applied += chatModelId to reasoningLevel
    }
}

private class MemoryMiniMaxConfig : MiniMaxConfigStore {
    var saved = MiniMaxConfigValues("https://api.minimax.cn", "speech-2.8-hd", "image-01")
    override fun current(): MiniMaxConfigValues = saved
    override fun save(values: MiniMaxConfigValues) {
        saved = values
    }
}

private class MemoryLogSource : AppLogSource {
    val entries = CopyOnWriteArrayList<LogEntry>()
    var enabled = false
    override fun recent(): List<LogEntry> = entries.toList()
    override fun clear() = entries.clear()
    override fun isRequestLoggingEnabled(): Boolean = enabled
    override fun setRequestLoggingEnabled(enabled: Boolean) {
        this.enabled = enabled
    }
}
