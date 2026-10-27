package whaleui.nightsky.ltd.whale

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import kotlinx.coroutines.flow.first
import whaleui.nightsky.ltd.domain.settings.SecretStore
import whaleui.nightsky.ltd.domain.settings.SettingsRepository
import whaleui.nightsky.ltd.schedule.ScheduleMath
import whaleui.nightsky.ltd.domain.schedule.ScheduleRepository
import java.io.File
import kotlin.uuid.Uuid

/**
 * 首次启动初始化（任务书 2.3），**幂等**：只在缺失或与期望不一致时写，重复调用不会重复创建。
 *
 * 负责四件事：
 * 1. 保证内置 DeepSeek 供应商下有 `deepseek-flash` 这个模型（勾 TOOL / REASONING / IMAGE 输入）；
 * 2. 保证存在一个配置好的「小鲸鱼」Assistant；
 * 3. 关掉全局的「生成建议」和「自动标题」（只留一个固定对话，省掉每轮额外的模型调用）；
 * 4. 把我们的固定对话绑到小鲸鱼助手上；把旧版本留在原生供应商设置里的 DeepSeek 明文 Key 迁进 [SecretStore]（W-028）。
 *
 * 所有字段都是「按 `WhaleGate`/设置里的实际值判断后再写」，不做无脑覆盖。
 */
class WhaleBootstrap(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val runtimeStore: WhaleRuntimeStore,
    private val conversations: WhaleConversationProvider,
    private val secretStore: SecretStore,
    private val settingsRepository: SettingsRepository,
    private val scheduleRepository: ScheduleRepository,
    private val providerKeys: WhaleProviderKeys,
) {

    /** [ensure] 跑完（成功或失败）后完成；闹钟轮开始前先等它，SOUL / Key 缓存都就绪了再组装请求（W-009）。 */
    private val ready = CompletableDeferred<Unit>()

    /** 等 [ensure] 跑完，最多等 [timeoutMs]；超时返回 false（照常继续，不卡住闹钟）。 */
    suspend fun awaitReady(timeoutMs: Long = READY_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) { ready.await() } != null

    suspend fun ensure() {
        try {
            ensureAll()
        } finally {
            ready.complete(Unit)
        }
    }

    private suspend fun ensureAll() {
        // 最先做：之后的设置写入都基于已经没有明文 Key 的那份
        providerKeys.migratePlaintextKey()
        providerKeys.migratePlaintextProxyPassword()
        val modelId = ensureDeepSeekFlashModel()
        val assistantId = ensureWhaleAssistant(modelId)
        WhaleGate.assistantId = assistantId
        runtimeStore.whaleAssistantId = assistantId
        ensureGlobalFlags()
        conversations.rebindAssistant(assistantId)
        ensureSeedTasks()
        // 常住城市进缓存（天气工具和系统提示词都要同步拿）
        runCatching { whaleui.nightsky.ltd.whale.WhaleHomeCity.update(settingsRepository.settings.first().homeCity) }
        // MiniMax 的模型 id / Base URL（可配置，默认值不变）
        whaleui.nightsky.ltd.minimax.WhaleMiniMaxPrefs.attach(context)
        // 工具用的同步取值缓存（DeepSeek 搜索 Key、MiniMax Key、代理密码）
        WhaleSecrets.refresh(secretStore)
        // 语音音色/语速缓存（设置页改动时会再同步）
        runCatching {
            val appSettings = settingsRepository.settings.first()
            WhaleVoicePrefs.update(appSettings.voiceId, appSettings.speechRate)
        }
    }

    // ------------------------------------------------------------ 模型

    private suspend fun ensureDeepSeekFlashModel(): Uuid? {
        var result: Uuid? = null
        settingsStore.update { settings ->
            val provider = settings.providers.find { it.id == DEEPSEEK_PROVIDER_ID }
            if (provider == null) {
                return@update settings
            }
            val existing = provider.models.find { it.modelId == DEEPSEEK_FLASH_MODEL_ID }
            if (existing != null) {
                result = existing.id
                // 补齐能力位：老配置里可能没有 IMAGE 输入
                val desired = existing.copy(
                    inputModalities = existing.inputModalities.withImageInput(),
                    abilities = existing.abilities.withToolAndReasoning(),
                )
                if (desired != existing) {
                    settings.copy(
                        providers = settings.providers.map {
                            if (it.id == provider.id) it.editModel(desired) else it
                        },
                    )
                } else {
                    settings
                }
            } else {
                val model = Model(
                    modelId = DEEPSEEK_FLASH_MODEL_ID,
                    displayName = DEEPSEEK_FLASH_DISPLAY_NAME,
                    type = ModelType.CHAT,
                    inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                    outputModalities = listOf(Modality.TEXT),
                    abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
                )
                result = model.id
                settings.copy(
                    providers = settings.providers.map {
                        if (it.id == provider.id) it.addModel(model) else it
                    },
                )
            }
        }
        return result
    }

    private fun List<Modality>.withImageInput(): List<Modality> =
        if (contains(Modality.IMAGE)) this else this + Modality.IMAGE

    private fun List<ModelAbility>.withToolAndReasoning(): List<ModelAbility> =
        (this + ModelAbility.TOOL + ModelAbility.REASONING).distinct()

    // ------------------------------------------------------------ 助手

    private suspend fun ensureWhaleAssistant(modelId: Uuid?): Uuid {
        removeLegacyAvatarFile()
        var whaleId = runtimeStore.whaleAssistantId
            ?: settingsStore.awaitLoaded().assistants.find { it.name == ASSISTANT_NAME }?.id

        settingsStore.update { settings ->
            val existing = whaleId?.let { id -> settings.assistants.find { it.id == id } }
                ?: settings.assistants.find { it.name == ASSISTANT_NAME }

            val desired = (existing ?: Assistant(id = Uuid.random())).let { base ->
                base.copy(
                    name = ASSISTANT_NAME,
                    // 界面上她的头像来自资料页「编辑」（HerProfileStore），助手本身不再带头像
                    avatar = Avatar.Dummy,
                    useAssistantAvatar = true,
                    // 模型和思考强度只在新建时给默认值；之后以高级设置里的选择为准（S9a），
                    // 只有选中的模型被删掉了才退回 deepseek-flash。
                    chatModelId = whaleChatModelId(existing, modelId, settings),
                    systemPrompt = "",
                    streamOutput = true,
                    reasoningLevel = if (existing == null) ReasoningLevel.LOW else base.reasoningLevel,
                    enableMemory = false,
                    enableWebSearch = false,
                    enableTimeReminder = false,
                    localTools = emptyList(),
                    enabledSkills = ENABLED_SKILLS,
                )
            }
            whaleId = desired.id
            if (existing == desired) {
                return@update settings
            }
            val assistants = if (existing == null) {
                settings.assistants + desired
            } else {
                settings.assistants.map { if (it.id == desired.id) desired else it }
            }
            settings.copy(
                assistants = assistants,
                // 让隐藏入口里的原版页面也默认选中小鲸鱼
                assistantId = desired.id,
                chatModelId = modelId ?: settings.chatModelId,
            )
        }
        return whaleId ?: error("whale assistant id not resolved")
    }

    /** 老版本把内置头像落到 files/whale/avatar_her.png 当助手头像；现在不用了，删掉。 */
    private suspend fun removeLegacyAvatarFile() = withContext(Dispatchers.IO) {
        runCatching { File(File(context.filesDir, "whale"), LEGACY_AVATAR_FILE_NAME).delete() }
    }

    // ------------------------------------------------------------ 全局开关

    private suspend fun ensureGlobalFlags() {
        settingsStore.update { settings ->
            val display = settings.displaySetting.copy(
                // 生成完成通知开着，主动消息才有提醒
                enableNotificationOnMessageGeneration = true,
            )
            if (!settings.enableSuggestion && !settings.enableAutoTitle && display == settings.displaySetting) {
                settings
            } else {
                settings.copy(
                    // 只有一个固定对话，不需要标题和建议，省掉每轮额外的模型调用
                    enableSuggestion = false,
                    enableAutoTitle = false,
                    displaySetting = display,
                )
            }
        }
    }

    /**
     * 两个种子任务（任务书 6.4）：每天 08:30「早上醒来」、每天 23:30「睡前」。
     * 只在「一条任务都没有」时创建，用户改过之后不会被覆盖。
     */
    private suspend fun ensureSeedTasks() {
        val existing = scheduleRepository.observeAll().first()
        if (existing.isNotEmpty()) return
        val seeds = ScheduleMath.seedTasks(
            now = java.time.ZonedDateTime.now(),
            readPrompt = { path ->
                runCatching {
                    context.assets.open(path).bufferedReader().use { it.readText() }
                }.getOrDefault("")
            },
        )
        seeds.forEach { scheduleRepository.add(it) }
    }

    companion object {
        /**
         * 小鲸鱼助手该用的聊天模型：已有助手且它选的模型还在 → 保留（高级设置里选的）；
         * 否则用 deepseek-flash（[flashModelId]）。
         */
        internal fun whaleChatModelId(
            existing: Assistant?,
            flashModelId: Uuid?,
            settings: me.rerere.rikkahub.data.datastore.Settings,
        ): Uuid? {
            val chosen = existing?.chatModelId
            if (chosen != null && settings.providers.any { provider -> provider.models.any { it.id == chosen } }) {
                return chosen
            }
            return flashModelId ?: chosen
        }

        /** 闹钟轮等首次初始化的上限 */
        const val READY_TIMEOUT_MS: Long = 15_000L

        val DEEPSEEK_PROVIDER_ID: Uuid = Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b")
        const val DEEPSEEK_FLASH_MODEL_ID = "deepseek-flash"
        const val DEEPSEEK_FLASH_DISPLAY_NAME = "deepseek-flash"
        const val ASSISTANT_NAME = "小鲸鱼"
        const val LEGACY_AVATAR_FILE_NAME = "avatar_her.png"

        /** 4 个内置技能里的 3 个（voice-message 是常驻正文，不靠 use_skill 打开）。 */
        val ENABLED_SKILLS = setOf("my-day", "drawing", "goutoujunshi")
    }
}
