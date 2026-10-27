package whaleui.nightsky.ltd.ui.advanced

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import whaleui.nightsky.ltd.domain.settings.SecretProvider
import whaleui.nightsky.ltd.domain.settings.SecretStore
import whaleui.nightsky.ltd.domain.settings.SecretWriteResult
import whaleui.nightsky.ltd.ui.settings.SettingsToast
import whaleui.nightsky.ltd.ui.settings.SettingsToastKind
import whaleui.nightsky.ltd.whale.WhaleBootstrap
import whaleui.nightsky.ltd.whale.WhaleSecrets
import java.util.concurrent.atomic.AtomicLong
import whaleui.nightsky.ltd.util.runCatchingCancellable

/**
 * 高级设置（D2）：供应商、模型、代理、日志。取代原版 RikkaHub 设置入口。
 *
 * 读写路径：
 * - **Key**：只读写 [SecretStore]（W-028：Key 只存这一份，原生供应商设置里的 apiKey 始终为空，
 *   请求时由 WhaleProviderKeys 现取）；保存后更新工具用的 [WhaleSecrets] 缓存。
 *   全 App 只有这里（供应商页的「密钥」行）能填 / 清除密钥（D，用户定）。
 * - **DeepSeek 接口地址 / 模型列表**：[SettingsStore] 里内置 DeepSeek 供应商（id 固定）。
 * - **聊天模型 / 思考强度**：写回小鲸鱼助手，并同步到固定对话（对话开始后模型是固定在对话上的）。
 * - **MiniMax 地址与模型**：[MiniMaxConfigStore]。
 * - **代理**：[SettingsStore] 的 `networkSetting`；OkHttp 每次请求现读，改完下一次请求就生效。
 * - **日志**：[AppLogSource]（进程内最近 100 条），展示和复制前全部脱敏。
 */
class AdvancedSettingsViewModel(
    private val settingsStore: SettingsStore,
    private val secretStore: SecretStore,
    private val miniMaxConfig: MiniMaxConfigStore,
    private val remoteModels: RemoteModelLister,
    private val chatTarget: WhaleChatTarget,
    private val logSource: AppLogSource = CommonAppLogSource,
) : ViewModel(), AdvancedActions {

    private val _uiState = MutableStateFlow(AdvancedUiState(miniMax = miniMaxConfig.current()))
    val uiState: StateFlow<AdvancedUiState> = _uiState.asStateFlow()

    /** 在线拉到的模型 id（只在内存里，没选中的不写进设置）。 */
    private var remoteModelIds: List<String> = emptyList()

    /** 代理的地址 / 端口只在第一次读设置时还原一次，之后以本页编辑为准（关掉后仍保留）。 */
    private var proxyRestored = false

    /** SecretStore 里有没有代理密码（N-007）；设置里旧版本留下的明文也算「已填写」 */
    @Volatile
    private var proxyPasswordStored = false

    private val toastIds = AtomicLong()
    private var toastJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                if (!settings.init) applySettings(settings)
            }
        }
        refreshKeys()
    }

    // -----------------------------------------------------------------------
    // 页面
    // -----------------------------------------------------------------------

    /** 每次从外面进来：回到入口列表，收起浮层，刷新 Key 状态和 MiniMax 配置。 */
    override fun enterPage() {
        _uiState.update {
            it.copy(
                section = null,
                editor = null,
                clearKeyConfirm = null,
                sheet = null,
                logDetail = null,
                toast = null,
                miniMax = miniMaxConfig.current(),
            )
        }
        refreshKeys()
    }

    override fun openSection(section: AdvancedSection) {
        _uiState.update { it.copy(section = section, editor = null, clearKeyConfirm = null, sheet = null, logDetail = null) }
        if (section == AdvancedSection.Logs) refreshLogs()
    }

    override fun closeSection() {
        _uiState.update { it.copy(section = null, editor = null, clearKeyConfirm = null, sheet = null, logDetail = null) }
    }

    // -----------------------------------------------------------------------
    // 输入框
    // -----------------------------------------------------------------------

    override fun openEditor(field: AdvancedField) {
        val state = _uiState.value
        val initial = when (field) {
            AdvancedField.DeepSeekBaseUrl -> state.deepSeekBaseUrl
            AdvancedField.MiniMaxBaseUrl -> state.miniMax.baseUrl
            AdvancedField.MiniMaxSpeechModel -> state.miniMax.speechModel
            AdvancedField.MiniMaxImageModel -> state.miniMax.imageModel
            AdvancedField.ProxyHost -> state.proxy.host
            AdvancedField.ProxyPort -> state.proxy.port
            AdvancedField.ProxyUsername -> state.proxy.username
            // 密钥类字段永远从空白开始，不把原文放进输入框
            AdvancedField.DeepSeekKey,
            AdvancedField.MiniMaxKey,
            AdvancedField.ProxyPassword,
            AdvancedField.CustomModelId,
            -> ""
        }
        _uiState.update { it.copy(editor = AdvancedEditor(field, initial), sheet = null) }
    }

    override fun dismissEditor() {
        _uiState.update { it.copy(editor = null, clearKeyConfirm = null) }
    }

    /** 输入框点「保存」。 */
    override fun submitEditor(value: String) {
        val editor = _uiState.value.editor ?: return
        _uiState.update { it.copy(editor = null) }
        val trimmed = value.trim()
        when (editor.field) {
            AdvancedField.DeepSeekBaseUrl -> saveDeepSeekBaseUrl(trimmed)
            AdvancedField.DeepSeekKey -> saveSecret(SecretProvider.DeepSeek, trimmed)
            AdvancedField.MiniMaxKey -> saveSecret(SecretProvider.MiniMax, trimmed)
            AdvancedField.MiniMaxBaseUrl -> saveMiniMax { it.copy(baseUrl = trimmed) }
            AdvancedField.MiniMaxSpeechModel -> saveMiniMax { it.copy(speechModel = trimmed) }
            AdvancedField.MiniMaxImageModel -> saveMiniMax { it.copy(imageModel = trimmed) }
            AdvancedField.CustomModelId -> if (trimmed.isNotEmpty()) selectModel(trimmed)
            AdvancedField.ProxyHost -> updateProxy { it.copy(host = trimmed) }
            AdvancedField.ProxyPort -> updateProxy { it.copy(port = trimmed) }
            AdvancedField.ProxyUsername -> updateProxy { it.copy(username = trimmed) }
            // 密码不 trim：空格也可能是密码的一部分；空串 = 清除
            AdvancedField.ProxyPassword -> saveProxyPassword(value)
        }
    }

    // -----------------------------------------------------------------------
    // 供应商
    // -----------------------------------------------------------------------

    private fun saveDeepSeekBaseUrl(input: String) {
        val url = input.ifEmpty { AdvancedDefaults.DEEPSEEK_BASE_URL }.trimEnd('/')
        if (!url.isHttpUrl()) {
            showToast("地址要以 http:// 或 https:// 开头", SettingsToastKind.Error)
            return
        }
        viewModelScope.launch {
            var found = false
            settingsStore.update { settings ->
                val provider = settings.deepSeekProvider() ?: return@update settings
                found = true
                if (provider.baseUrl == url) return@update settings
                settings.copy(
                    providers = settings.providers.map { if (it.id == provider.id) provider.copy(baseUrl = url) else it },
                )
            }
            if (found) {
                showToast("DeepSeek 接口地址已保存，下一条消息起生效", SettingsToastKind.Success)
            } else {
                showToast("找不到内置的 DeepSeek 供应商", SettingsToastKind.Error)
            }
        }
    }

    private fun saveMiniMax(change: (MiniMaxConfigValues) -> MiniMaxConfigValues) {
        val next = change(miniMaxConfig.current())
        if (next.baseUrl.isNotEmpty() && !next.baseUrl.isHttpUrl()) {
            showToast("地址要以 http:// 或 https:// 开头", SettingsToastKind.Error)
            return
        }
        miniMaxConfig.save(next)
        _uiState.update { it.copy(miniMax = miniMaxConfig.current()) }
        showToast("MiniMax 设置已保存", SettingsToastKind.Success)
    }

    /** 保存 Key：只写 SecretStore（唯一来源），然后同步缓存。空输入不改动。 */
    fun saveSecret(provider: SecretProvider, value: String) {
        val key = value.trim()
        if (key.isEmpty()) return
        viewModelScope.launch {
            // 真的存进去了才更新缓存和状态（W-045）；存不进去时原来的 Key 不变
            when (val result = WhaleSecrets.save(secretStore, provider, key)) {
                SecretWriteResult.Saved -> {
                    setKeyStatus(provider, KeyStatus.of(key))
                    showToast("${provider.title} 密钥已保存", SettingsToastKind.Success)
                }
                is SecretWriteResult.Failed ->
                    showToast("${provider.title} 密钥保存失败：${result.reason}", SettingsToastKind.Error)
            }
        }
    }

    override fun requestClearSecret(provider: SecretProvider) {
        // 没填的不用清；确认框盖在密钥对话框上，取消后回到对话框
        if (!_uiState.value.keyStatus(provider).saved) return
        _uiState.update { it.copy(clearKeyConfirm = provider) }
    }

    override fun cancelClearSecret() {
        _uiState.update { it.copy(clearKeyConfirm = null) }
    }

    override fun clearSecret(provider: SecretProvider) {
        _uiState.update { it.copy(editor = null, clearKeyConfirm = null) }
        viewModelScope.launch {
            when (val result = WhaleSecrets.clear(secretStore, provider)) {
                SecretWriteResult.Saved -> {
                    setKeyStatus(provider, KeyStatus())
                    showToast("${provider.title} 密钥已清除", SettingsToastKind.Info)
                }
                is SecretWriteResult.Failed ->
                    showToast("${provider.title} 密钥清除失败：${result.reason}", SettingsToastKind.Error)
            }
        }
    }

    private fun refreshKeys() {
        viewModelScope.launch {
            SecretProvider.ApiKeys.forEach { provider ->
                val status = runCatchingCancellable { KeyStatus.of(secretStore.get(provider)) }.getOrDefault(KeyStatus())
                setKeyStatus(provider, status)
            }
            proxyPasswordStored = runCatchingCancellable { secretStore.has(SecretProvider.ProxyPassword) }.getOrDefault(false)
            val legacy = settingsStore.settingsFlow.value.networkSetting.proxyPassword.isNotEmpty()
            _uiState.update { it.copy(proxy = it.proxy.copy(passwordSet = proxyPasswordStored || legacy)) }
        }
    }

    private fun setKeyStatus(provider: SecretProvider, status: KeyStatus) {
        _uiState.update {
            when (provider) {
                SecretProvider.DeepSeek -> it.copy(deepSeekKey = status)
                SecretProvider.MiniMax -> it.copy(miniMaxKey = status)
                SecretProvider.ProxyPassword -> it
            }
        }
    }

    // -----------------------------------------------------------------------
    // 模型
    // -----------------------------------------------------------------------

    override fun openSheet(sheet: AdvancedSheet) {
        _uiState.update { it.copy(sheet = sheet, editor = null) }
    }

    override fun closeSheet() {
        _uiState.update { it.copy(sheet = null) }
    }

    /** 向 DeepSeek 拉在线模型列表；失败（没网 / 没 Key）就提示，可以手动填 id。 */
    override fun refreshModels() {
        if (_uiState.value.modelsRefreshing) return
        val provider = settingsStore.settingsFlow.value.deepSeekProvider() ?: return
        _uiState.update { it.copy(modelsRefreshing = true) }
        viewModelScope.launch {
            val result = try {
                Result.success(remoteModels.list(provider))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            _uiState.update { it.copy(modelsRefreshing = false) }
            result.onSuccess { ids ->
                remoteModelIds = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
                applySettings(settingsStore.settingsFlow.value)
                showToast("在线列表里有 ${remoteModelIds.size} 个模型", SettingsToastKind.Success)
            }.onFailure {
                showToast("拉不到模型列表，可以手动填写模型 ID", SettingsToastKind.Error)
            }
        }
    }

    /** 选一个聊天模型（已有的直接用；新的先加进 DeepSeek 供应商），写回助手并同步到对话。 */
    override fun selectModel(modelId: String) {
        val id = modelId.trim()
        if (id.isEmpty()) return
        _uiState.update { it.copy(sheet = null) }
        viewModelScope.launch {
            var chosen: Model? = null
            var reasoning: ReasoningLevel = ReasoningLevel.AUTO
            val assistantId = chatTarget.assistantId()
            settingsStore.update { settings ->
                val provider = settings.deepSeekProvider() ?: return@update settings
                val existing = provider.models.firstOrNull { it.modelId == id }
                val model = existing ?: newChatModel(id)
                chosen = model
                val providers = if (existing == null) {
                    settings.providers.map { if (it.id == provider.id) it.addModel(model) else it }
                } else {
                    settings.providers
                }
                val assistant = assistantId?.let { aid -> settings.assistants.firstOrNull { it.id == aid } }
                reasoning = assistant?.reasoningLevel ?: ReasoningLevel.AUTO
                settings.copy(
                    providers = providers,
                    assistants = if (assistant == null) {
                        settings.assistants
                    } else {
                        settings.assistants.map { if (it.id == assistant.id) it.copy(chatModelId = model.id) else it }
                    },
                )
            }
            val model = chosen
            if (model == null) {
                showToast("找不到内置的 DeepSeek 供应商", SettingsToastKind.Error)
                return@launch
            }
            if (assistantId == null) {
                showToast("她还没初始化好，稍后再试", SettingsToastKind.Error)
                return@launch
            }
            syncConversation(model.id, reasoning, "已换成 ${model.modelId}")
        }
    }

    /**
     * 从 DeepSeek 供应商里移除一个模型（手动填错的 id 等）。正在用的和内置的 deepseek-flash 不能移除；
     * 只在在线列表里、还没加进来的，什么都不用做。
     */
    override fun removeModel(modelId: String) {
        val current = _uiState.value.currentModelId
        if (modelId == current || modelId == WhaleBootstrap.DEEPSEEK_FLASH_MODEL_ID) return
        viewModelScope.launch {
            settingsStore.update { settings ->
                val provider = settings.deepSeekProvider() ?: return@update settings
                val model = provider.models.firstOrNull { it.modelId == modelId } ?: return@update settings
                settings.copy(providers = settings.providers.map { if (it.id == provider.id) it.delModel(model) else it })
            }
            showToast("已移除 $modelId", SettingsToastKind.Info)
        }
    }

    override fun setReasoningEnabled(enabled: Boolean) {
        val current = _uiState.value.reasoningLevel
        setReasoningLevel(
            when {
                !enabled -> ReasoningLevel.OFF
                current.isEnabled -> current
                else -> ReasoningLevel.LOW
            },
        )
    }

    override fun setReasoningLevel(level: ReasoningLevel) {
        _uiState.update { it.copy(sheet = null) }
        val assistantId = chatTarget.assistantId()
        if (assistantId == null) {
            showToast("她还没初始化好，稍后再试", SettingsToastKind.Error)
            return
        }
        viewModelScope.launch {
            var modelId: kotlin.uuid.Uuid? = null
            settingsStore.update { settings ->
                val assistant = settings.assistants.firstOrNull { it.id == assistantId } ?: return@update settings
                modelId = assistant.chatModelId
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistantId) it.copy(reasoningLevel = level) else it
                    },
                )
            }
            val target = modelId ?: return@launch
            syncConversation(target, level, if (level.isEnabled) "思考：${level.label}" else "思考已关闭")
        }
    }

    private suspend fun syncConversation(modelId: kotlin.uuid.Uuid, level: ReasoningLevel, success: String) {
        try {
            chatTarget.applyToConversation(modelId, level)
            showToast(success, SettingsToastKind.Success)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showToast("已写回助手，但同步到对话失败：${e.message ?: e::class.simpleName}", SettingsToastKind.Error)
        }
    }

    private fun newChatModel(modelId: String): Model = Model(
        modelId = modelId,
        displayName = modelId,
        type = ModelType.CHAT,
        inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId),
        outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId),
        // 她的工具全靠函数调用，新加的模型一律打开 TOOL
        abilities = (ModelRegistry.MODEL_ABILITIES.getData(modelId) + ModelAbility.TOOL).distinct(),
    )

    // -----------------------------------------------------------------------
    // 代理
    // -----------------------------------------------------------------------

    override fun setProxyEnabled(enabled: Boolean) = updateProxy { it.copy(enabled = enabled) }

    override fun setProxyType(type: ProxyType) {
        _uiState.update { it.copy(sheet = null) }
        updateProxy { it.copy(type = type) }
    }

    private fun updateProxy(change: (ProxyDraft) -> ProxyDraft) {
        val next = change(_uiState.value.proxy)
        _uiState.update { it.copy(proxy = next) }
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    networkSetting = settings.networkSetting.copy(
                        proxyUrl = next.toProxyUrl(),
                        proxyUsername = next.username,
                    ),
                )
            }
        }
    }

    /**
     * 代理密码只写 SecretStore（N-007），存进去了才更新缓存和状态；存不进去时原来的密码不变（同 Key，W-045）。
     * 成功后顺手清掉设置里旧版本留下的明文。空输入（或全是空白）= 清除。
     */
    private fun saveProxyPassword(password: String) {
        val clearing = password.isBlank()
        viewModelScope.launch {
            val result = if (clearing) {
                WhaleSecrets.clear(secretStore, SecretProvider.ProxyPassword)
            } else {
                WhaleSecrets.save(secretStore, SecretProvider.ProxyPassword, password)
            }
            when (result) {
                SecretWriteResult.Saved -> {
                    proxyPasswordStored = !clearing
                    settingsStore.update { settings ->
                        if (settings.networkSetting.proxyPassword.isEmpty()) {
                            settings
                        } else {
                            settings.copy(networkSetting = settings.networkSetting.copy(proxyPassword = ""))
                        }
                    }
                    _uiState.update { it.copy(proxy = it.proxy.copy(passwordSet = !clearing)) }
                    showToast(if (clearing) "代理密码已清除" else "代理密码已保存", SettingsToastKind.Success)
                }
                is SecretWriteResult.Failed ->
                    showToast("代理密码保存失败：${result.reason}", SettingsToastKind.Error)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 日志
    // -----------------------------------------------------------------------

    override fun refreshLogs() {
        viewModelScope.launch {
            val format = logFormat()
            _uiState.update {
                it.copy(
                    requestLoggingEnabled = logSource.isRequestLoggingEnabled(),
                    logs = logSource.recent().sortedByDescending { entry -> entry.timestamp }.map(format::item),
                )
            }
        }
    }

    override fun setRequestLogging(enabled: Boolean) {
        logSource.setRequestLoggingEnabled(enabled)
        _uiState.update { it.copy(requestLoggingEnabled = enabled) }
    }

    override fun clearLogs() {
        logSource.clear()
        _uiState.update { it.copy(logs = emptyList(), logDetail = null) }
    }

    override fun openLogDetail(id: String) {
        viewModelScope.launch {
            val entry = logSource.recent().firstOrNull { it.id.toString() == id } ?: return@launch
            val format = logFormat()
            val item = format.item(entry)
            _uiState.update { it.copy(logDetail = AdvancedLogDetail(id, item.title, format.detail(entry))) }
        }
    }

    override fun closeLogDetail() {
        _uiState.update { it.copy(logDetail = null) }
    }

    /** 复制单条：返回脱敏后的全文（找不到时 null）。 */
    override suspend fun copyText(id: String): String? {
        val entry = logSource.recent().firstOrNull { it.id.toString() == id } ?: return null
        return logFormat().detail(entry)
    }

    /** 当前两个 Key 的原文只用来在日志里做替换，不进界面状态。 */
    private suspend fun logFormat(): AdvancedLogFormat {
        val secrets = SecretProvider.entries.mapNotNull { runCatchingCancellable { secretStore.get(it) }.getOrNull() }
        return AdvancedLogFormat(secrets)
    }

    // -----------------------------------------------------------------------
    // 设置 → 界面状态
    // -----------------------------------------------------------------------

    private fun applySettings(settings: Settings) {
        val provider = settings.deepSeekProvider()
        val assistant = chatTarget.assistantId()?.let { aid -> settings.assistants.firstOrNull { it.id == aid } }
        val current = assistant?.chatModelId?.let { settings.findModelById(it) }
        val providerModels = provider?.models.orEmpty().filter { it.type == ModelType.CHAT }
        val options = providerModels.map {
            ModelOption(it.modelId, it.id, ModelAbility.REASONING in it.abilities)
        } + remoteModelIds
            .filter { id -> providerModels.none { it.modelId == id } }
            .map { id ->
                ModelOption(id, null, ModelAbility.REASONING in ModelRegistry.MODEL_ABILITIES.getData(id))
            }
        val network = settings.networkSetting
        _uiState.update { state ->
            state.copy(
                loaded = true,
                deepSeekBaseUrl = provider?.baseUrl.orEmpty(),
                deepSeekProviderMissing = provider == null,
                currentModelId = current?.modelId,
                models = options,
                reasoningSupported = current != null && ModelAbility.REASONING in current.abilities,
                reasoningLevel = assistant?.reasoningLevel ?: ReasoningLevel.AUTO,
                proxy = if (proxyRestored) {
                    state.proxy.copy(passwordSet = proxyPasswordStored || network.proxyPassword.isNotEmpty())
                } else {
                    ProxyDraft.from(network.proxyUrl, network.proxyUsername, network.proxyPassword)
                        .let { draft -> if (proxyPasswordStored) draft.copy(passwordSet = true) else draft }
                },
            )
        }
        proxyRestored = true
    }

    private fun Settings.deepSeekProvider(): ProviderSetting.OpenAI? =
        providers.firstOrNull { it.id == WhaleBootstrap.DEEPSEEK_PROVIDER_ID } as? ProviderSetting.OpenAI

    private fun String.isHttpUrl(): Boolean =
        (startsWith("http://") || startsWith("https://")) && length > "https://".length && none { it.isWhitespace() }

    // -----------------------------------------------------------------------
    // 提示条
    // -----------------------------------------------------------------------

    override fun showToast(text: String, kind: SettingsToastKind) {
        val toast = SettingsToast(id = toastIds.incrementAndGet(), text = text, kind = kind)
        _uiState.update { it.copy(toast = toast) }
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(4_000)
            if (_uiState.value.toast?.id == toast.id) _uiState.update { it.copy(toast = null) }
        }
    }

    override fun dismissToast() {
        toastJob?.cancel()
        _uiState.update { it.copy(toast = null) }
    }
}
