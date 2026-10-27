package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsFieldsTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `every settings property has exactly one field`() {
        val properties = Settings.serializer().descriptor.elementNames.sorted()

        assertEquals(properties, SETTINGS_FIELDS.map { it.property.name }.sorted())
    }

    @Test
    fun `settings survive a round trip through preferences`() {
        // 样本里和默认值相同的字段测不出读写是否对得上
        val defaults = emptyPreferences().toSettings()
        assertEquals(
            emptyList<String>(),
            SETTINGS_FIELDS.filterNot { it.changed(defaults, SAMPLE) }.map { it.property.name },
        )

        val preferences = mutablePreferencesOf().apply { putSettings(SAMPLE) }

        assertEquals(SAMPLE, preferences.toSettings())
    }

    // 这里的 key 名和取值就是用户盘上的格式，改动它们等于丢掉已有的设置
    @Test
    fun `stored format stays compatible with existing data`() {
        val stored = mutablePreferencesOf().apply { putSettings(SAMPLE) }.asMap().mapKeys { it.key.name }

        assertEquals(STORED_KEYS, stored.keys)
        assertEquals(false, stored["dynamic_color"])
        assertEquals(9090, stored["web_server_port"])
        assertEquals(1.5f, stored["default_tts_playback_speed"])
        assertEquals("title", stored["title_prompt"])
        assertEquals("00000000-0000-0000-0000-000000000001", stored["chat_model"])
        assertEquals("LOW", stored["fast_model_reasoning_level"])
        assertEquals("""[{"id":"00000000-0000-0000-0000-000000000007","name":"tag"}]""", stored["assistant_tags"])
        assertEquals("""{"resultSize":3}""", stored["search_common"])
    }

    // S7 删了 :mediagen：老版本写下的媒体生成供应商 JSON 要能读进来，再写回去一字不差
    @Test
    fun `media generation providers written by the old mediagen module survive a round trip`() {
        val preferences = mutablePreferencesOf(SettingsStore.MEDIA_GENERATION_PROVIDERS to OLD_MEDIA_GENERATION_PROVIDERS)

        val settings = preferences.toSettings()
        assertEquals(2, settings.mediaGenerationProviders.size)

        val written = mutablePreferencesOf().apply { putSettings(settings) }
        assertEquals(OLD_MEDIA_GENERATION_PROVIDERS, written[SettingsStore.MEDIA_GENERATION_PROVIDERS])
    }

    // S9b 删了 :search：老版本写下的搜索服务列表与通用选项要能读进来，再写回去一字不差
    @Test
    fun `search settings written by the old search module survive a round trip`() {
        val preferences = mutablePreferencesOf(
            SettingsStore.SEARCH_SERVICES to OLD_SEARCH_SERVICES,
            SettingsStore.SEARCH_COMMON to """{"resultSize":7}""",
            SettingsStore.SEARCH_SELECTED to 1,
        )

        val settings = preferences.toSettings().normalized()
        assertEquals(2, settings.searchServices.size)
        assertEquals(7, settings.searchCommonOptions.resultSize)
        assertEquals(1, settings.searchServiceSelected)

        val written = mutablePreferencesOf().apply { putSettings(settings) }
        assertEquals(OLD_SEARCH_SERVICES, written[SettingsStore.SEARCH_SERVICES])
        assertEquals("""{"resultSize":7}""", written[SettingsStore.SEARCH_COMMON])
        assertEquals(1, written[SettingsStore.SEARCH_SELECTED])
    }

    // S10 删了 :speech：老版本写下的 TTS / ASR 设置要能读进来（经过 normalized），再写回去一字不差
    @Test
    fun `speech settings written by the old speech module survive a round trip`() {
        val preferences = mutablePreferencesOf(
            SettingsStore.TTS_PROVIDERS to OLD_TTS_PROVIDERS,
            SettingsStore.SELECTED_TTS_PROVIDER to "e36b22ef-ca82-40ab-9e70-60cad861911c",
            SettingsStore.DEFAULT_TTS_PLAYBACK_SPEED to 1.25f,
            SettingsStore.ASR_PROVIDERS to OLD_ASR_PROVIDERS,
            SettingsStore.SELECTED_ASR_PROVIDER to "00000000-0000-0000-0000-000000000041",
        )

        val settings = preferences.toSettings().normalized()
        assertEquals(2, settings.ttsProviders.size)
        assertEquals(1, settings.asrProviders.size)
        assertEquals(Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"), settings.selectedTTSProviderId)
        assertEquals(Uuid.parse("00000000-0000-0000-0000-000000000041"), settings.selectedASRProviderId)

        val written = mutablePreferencesOf().apply { putSettings(settings) }
        assertEquals(OLD_TTS_PROVIDERS, written[SettingsStore.TTS_PROVIDERS])
        assertEquals("e36b22ef-ca82-40ab-9e70-60cad861911c", written[SettingsStore.SELECTED_TTS_PROVIDER])
        assertEquals(1.25f, written[SettingsStore.DEFAULT_TTS_PLAYBACK_SPEED])
        assertEquals(OLD_ASR_PROVIDERS, written[SettingsStore.ASR_PROVIDERS])
        assertEquals("00000000-0000-0000-0000-000000000041", written[SettingsStore.SELECTED_ASR_PROVIDER])
    }

    // S10 删了 MCP：老版本写下的 MCP 服务器列表要能读进来，再写回去一字不差；
    // 助手里勾选的服务器 id 照旧按列表里的 id 过滤（存在的保留，不存在的去掉）
    @Test
    fun `mcp servers written by the old mcp code survive a round trip`() {
        val kept = Uuid.parse("00000000-0000-0000-0000-000000000051")
        val gone = Uuid.parse("00000000-0000-0000-0000-000000000099")
        val assistant = Assistant(id = id(20), name = "Assistant", mcpServers = setOf(kept, gone))
        val preferences = mutablePreferencesOf(
            SettingsStore.MCP_SERVERS to OLD_MCP_SERVERS,
            SettingsStore.ASSISTANTS to JsonInstant.encodeToString(listOf(assistant)),
        )

        val settings = preferences.toSettings().normalized()
        assertEquals(2, settings.mcpServers.size)
        assertEquals(
            setOf(kept, Uuid.parse("00000000-0000-0000-0000-000000000052")),
            settings.mcpServerIds(),
        )
        assertEquals(setOf(kept), settings.assistants.first { it.id == id(20) }.mcpServers)

        val written = mutablePreferencesOf().apply { putSettings(settings) }
        assertEquals(OLD_MCP_SERVERS, written[SettingsStore.MCP_SERVERS])
    }

    // S11 删了原生 S3 / WebDAV 备份（D6）与 RemoteFileStore：老版本写下的三份配置和备份提醒
    // 要能读进来（经过 normalized），再写回去一字不差。样本取自模拟器上的真实设置，另加一份填了值的 S3
    @Test
    fun `backup settings written by the old sync code survive a round trip`() {
        val preferences = mutablePreferencesOf(
            SettingsStore.WEBDAV_CONFIG to OLD_WEBDAV_CONFIG,
            SettingsStore.S3_CONFIG to OLD_S3_CONFIG_FILLED,
            SettingsStore.UPLOAD_S3_CONFIG to OLD_S3_CONFIG_DEFAULT,
            SettingsStore.BACKUP_REMINDER_CONFIG to OLD_BACKUP_REMINDER_CONFIG,
        )

        val settings = preferences.toSettings().normalized()
        assertEquals("backup", settings.s3Config.bucket)
        assertEquals(listOf(S3Config.BackupItem.DATABASE), settings.s3Config.items)
        assertEquals("rikkahub_backups", settings.webDavConfig.path)

        val written = mutablePreferencesOf().apply { putSettings(settings) }
        assertEquals(OLD_WEBDAV_CONFIG, written[SettingsStore.WEBDAV_CONFIG])
        assertEquals(OLD_S3_CONFIG_FILLED, written[SettingsStore.S3_CONFIG])
        assertEquals(OLD_S3_CONFIG_DEFAULT, written[SettingsStore.UPLOAD_S3_CONFIG])
        assertEquals(OLD_BACKUP_REMINDER_CONFIG, written[SettingsStore.BACKUP_REMINDER_CONFIG])
    }

    // S11 删了原版的预设 / 自定义主题：老版本写下的主题 id 与自定义主题列表要能读进来，再写回去一字不差；
    // 没存过 theme_id 时仍是原来第一套预设 sakura
    @Test
    fun `theme settings written by the old theme pages survive a round trip`() {
        assertEquals("sakura", emptyPreferences().toSettings().themeId)
        val preferences = mutablePreferencesOf(
            SettingsStore.THEME_ID to "6a1f0c3e-0000-4000-8000-000000000001",
            SettingsStore.CUSTOM_THEMES to OLD_CUSTOM_THEMES,
        )

        val settings = preferences.toSettings().normalized()
        assertEquals(2, settings.customThemes.size)
        assertEquals(0xFF336699, settings.customThemes[1].primaryColorArgb)

        val written = mutablePreferencesOf().apply { putSettings(settings) }
        assertEquals("6a1f0c3e-0000-4000-8000-000000000001", written[SettingsStore.THEME_ID])
        assertEquals(OLD_CUSTOM_THEMES, written[SettingsStore.CUSTOM_THEMES])
    }

    @Test
    fun `missing keys read as first launch values`() {
        val settings = emptyPreferences().toSettings()

        assertEquals(DEFAULT_AUTO_MODEL_ID, settings.chatModelId)
        assertEquals(DEFAULT_AUTO_MODEL_ID, settings.fastModelId)
        assertEquals(DEFAULT_AUTO_MODEL_ID, settings.translateModeId)
        assertEquals(DEFAULT_MODE_INJECTIONS, settings.modeInjections)
        assertEquals(DEFAULT_ASSISTANT_ID, settings.assistantId)
        assertEquals(DEFAULT_SYSTEM_TTS_ID, settings.selectedTTSProviderId)
        assertEquals(null, settings.selectedASRProviderId)
        assertEquals(null, settings.memoryModelId)
        assertEquals(true, settings.dynamicColor)
        assertEquals(8080, settings.webServerPort)
    }

    @Test
    fun `unknown reasoning level reads as auto`() {
        val preferences = mutablePreferencesOf(SettingsStore.FAST_MODEL_REASONING_LEVEL to "REMOVED_LEVEL")

        assertEquals(ReasoningLevel.AUTO, preferences.toSettings().fastModelReasoningLevel)
    }

    @Test
    fun `clearing the asr provider removes its key`() {
        val preferences = mutablePreferencesOf().apply { putSettings(SAMPLE) }

        preferences.putSettings(SAMPLE.copy(selectedASRProviderId = null), persisted = SAMPLE)

        assertFalse(SettingsStore.SELECTED_ASR_PROVIDER in preferences)
        assertEquals(null, preferences.toSettings().selectedASRProviderId)
    }

    @Test
    fun `only changed fields are written`() {
        val preferences = mutablePreferencesOf().apply { putSettings(SAMPLE) }
        // 盘上的值故意和已落盘的那份不一致：没变的字段如果也被重写，它就会变回去
        preferences[SettingsStore.TITLE_PROMPT] = "on disk"

        preferences.putSettings(SAMPLE.copy(themeId = "changed"), persisted = SAMPLE)

        assertEquals("changed", preferences[SettingsStore.THEME_ID])
        assertEquals("on disk", preferences[SettingsStore.TITLE_PROMPT])
    }
}

private fun id(n: Int) = Uuid.parse("00000000-0000-0000-0000-%012d".format(n))

// 每个字段都取了和默认值不同的值
private val SAMPLE = Settings(
    dynamicColor = false,
    themeId = "custom",
    customThemes = listOf(CustomTheme(id = "theme", name = "Theme")),
    developerMode = true,
    displaySetting = DisplaySetting(userNickname = "me"),
    networkSetting = NetworkSetting(userAgent = "agent"),
    favoriteModels = listOf(id(30)),
    chatModelId = id(1),
    fastModelId = id(2),
    fastModelReasoningLevel = ReasoningLevel.LOW,
    memoryModelId = id(6),
    memoryModelReasoningLevel = ReasoningLevel.HIGH,
    imageGenerationModelId = id(3),
    enableAutoTitle = false,
    titlePrompt = "title",
    translateModeId = id(4),
    translatePrompt = "translate",
    translateThinkingBudget = 1024,
    translateTargetLanguage = "ja",
    enableSuggestion = false,
    suggestionPrompt = "suggestion",
    ocrModelId = id(5),
    ocrPrompt = "ocr",
    assistantId = id(20),
    providers = listOf(ProviderSetting.OpenAI(id = id(31), name = "Provider")),
    assistants = listOf(Assistant(id = id(20), name = "Assistant")),
    assistantTags = listOf(Tag(id = id(7), name = "tag")),
    searchServices = listOf(
        JsonObject(mapOf("type" to JsonPrimitive("bing_local"), "id" to JsonPrimitive(id(21).toString()))),
        JsonObject(mapOf("type" to JsonPrimitive("bing_local"), "id" to JsonPrimitive(id(22).toString()))),
    ),
    searchCommonOptions = SearchCommonOptions(resultSize = 3),
    searchServiceSelected = 1,
    mcpServers = listOf(
        JsonObject(mapOf("type" to JsonPrimitive("sse"), "id" to JsonPrimitive(id(8).toString()))),
    ),
    webDavConfig = WebDavConfig(url = "https://dav.example.com"),
    s3Config = S3Config(bucket = "backup"),
    uploadS3Config = S3Config(bucket = "upload"),
    mediaGenerationProviders = listOf(
        JsonObject(mapOf("type" to JsonPrimitive("openai"), "id" to JsonPrimitive(id(9).toString()))),
    ),
    ttsProviders = listOf(
        JsonObject(mapOf("type" to JsonPrimitive("system"), "id" to JsonPrimitive(id(10).toString()))),
    ),
    selectedTTSProviderId = id(10),
    defaultTTSPlaybackSpeed = 1.5f,
    asrProviders = listOf(
        JsonObject(mapOf("type" to JsonPrimitive("openai_realtime"), "id" to JsonPrimitive(id(11).toString()))),
    ),
    selectedASRProviderId = id(11),
    modeInjections = listOf(PromptInjection.ModeInjection(id = id(14), name = "mode")),
    lorebooks = listOf(Lorebook(id = id(12), name = "lorebook")),
    quickMessages = listOf(QuickMessage(id = id(13), title = "hi")),
    webServerEnabled = true,
    webServerPort = 9090,
    webServerJwtEnabled = true,
    webServerAccessPassword = "secret",
    webServerLocalhostOnly = true,
    backupReminderConfig = BackupReminderConfig(enabled = true),
    sponsorAlertDismissedAt = 7,
)

private const val OLD_WEBDAV_CONFIG =
    """{"url":"","username":"","password":"","path":"rikkahub_backups","items":["DATABASE","FILES"]}"""
private const val OLD_S3_CONFIG_DEFAULT =
    """{"endpoint":"","accessKeyId":"","secretAccessKey":"","bucket":"","region":"auto","pathStyle":true,"items":["DATABASE","FILES"]}"""
private const val OLD_S3_CONFIG_FILLED =
    """{"endpoint":"https://s3.example.com","accessKeyId":"ak","secretAccessKey":"sk","bucket":"backup","region":"us-east-1","pathStyle":false,"items":["DATABASE"]}"""
private const val OLD_BACKUP_REMINDER_CONFIG =
    """{"enabled":true,"intervalDays":3,"lastBackupTime":1730000000000}"""

private const val OLD_CUSTOM_THEMES =
    """[{"id":"6a1f0c3e-0000-4000-8000-000000000001","name":"海","primaryColorArgb":4284960932,"secondaryColorArgb":null,"tertiaryColorArgb":null},""" +
        """{"id":"6a1f0c3e-0000-4000-8000-000000000002","name":"","primaryColorArgb":4281558681,"secondaryColorArgb":4294901760,"tertiaryColorArgb":4278255360}]"""

private val STORED_KEYS = setOf(
    "dynamic_color",
    "theme_id",
    "custom_themes",
    "display_setting",
    "network_setting",
    "developer_mode",
    "favorite_models",
    "chat_model",
    "fast_model",
    "fast_model_reasoning_level",
    "memory_model",
    "memory_model_reasoning_level",
    "translate_model",
    "enable_suggestion",
    "enable_auto_title",
    "image_generation_model",
    "title_prompt",
    "translation_prompt",
    "translate_thinking_budget",
    "translate_target_language",
    "suggestion_prompt",
    "ocr_model",
    "ocr_prompt",
    "providers",
    "select_assistant",
    "assistants",
    "assistant_tags",
    "search_services",
    "search_common",
    "search_selected",
    "mcp_servers",
    "webdav_config",
    "s3_config",
    "upload_s3_config",
    "media_generation_providers",
    "tts_providers",
    "selected_tts_provider",
    "default_tts_playback_speed",
    "asr_providers",
    "selected_asr_provider",
    "web_server_enabled",
    "web_server_port",
    "web_server_jwt_enabled",
    "web_server_access_password",
    "web_server_localhost_only",
    "mode_injections",
    "lorebooks",
    "quick_messages",
    "backup_reminder_config",
    "sponsor_alert_dismissed_at",
)

// 删 :mediagen 之前 MediaGenerationProviderSetting 列表在盘上的样子（JsonInstant：紧凑、带 type 判别字段、encodeDefaults）
private const val OLD_MEDIA_GENERATION_PROVIDERS =
    """[{"type":"openai","id":"00000000-0000-0000-0000-000000000009","name":"OpenAI","apiKey":"sk-test",""" +
        """"baseUrl":"https://api.openai.com/v1","models":[{"modelId":"gpt-image-2.5-sunburst","kind":"image"}]},""" +
        """{"type":"minimax","id":"00000000-0000-0000-0000-000000000019","name":"MiniMax","apiKey":"",""" +
        """"baseUrl":"https://api.minimaxi.com/v1","models":[]}]"""

// 删 :search 之前 SearchServiceOptions 列表在盘上的样子（JsonInstant：紧凑、带 type 判别字段、encodeDefaults）
private const val OLD_SEARCH_SERVICES =
    """[{"type":"deepseek","id":"00000000-0000-0000-0000-000000000021","apiKey":"sk-test"},""" +
        """{"type":"custom_js","id":"00000000-0000-0000-0000-000000000022","name":"mine",""" +
        """"searchScript":"function search(q){return {items:[]}}","scrapeScript":""}]"""

// 删 :speech 之前 TTSProviderSetting 列表在盘上的样子：取自模拟器上真实的 settings.preferences_pb（内置的两项）
private const val OLD_TTS_PROVIDERS =
    """[{"type":"system","id":"026a01a2-c3a0-4fd5-8075-80e03bdef200","name":"","speechRate":1.0,"pitch":1.0},""" +
        """{"type":"openai","id":"e36b22ef-ca82-40ab-9e70-60cad861911c","name":"AiHubMix","apiKey":"",""" +
        """"baseUrl":"https://aihubmix.com/v1","model":"gpt-4o-mini-tts","voice":"alloy"}]"""

// 删 :speech 之前 ASRProviderSetting 列表在盘上的样子（按 OpenAIRealtime 的字段与默认值手写，JsonInstant 格式）
private const val OLD_ASR_PROVIDERS =
    """[{"type":"openai_realtime","id":"00000000-0000-0000-0000-000000000041","name":"OpenAI Realtime ASR",""" +
        """"apiKey":"sk-test","websocketUrl":"wss://api.openai.com/v1/realtime?intent=transcription",""" +
        """"model":"gpt-4o-transcribe","language":"","prompt":"","sampleRate":24000,"vadThreshold":0.5,""" +
        """"prefixPaddingMs":300,"silenceDurationMs":500}]"""

// 删 MCP 之前 McpServerConfig 列表在盘上的样子（按 McpConfig.kt 的字段与默认值手写，JsonInstant：紧凑、带 type 判别字段、
// encodeDefaults；headers 是 Pair 列表，OAuth 状态带令牌）
private const val OLD_MCP_SERVERS =
    """[{"type":"sse","id":"00000000-0000-0000-0000-000000000051","commonOptions":{"enable":true,"name":"demo",""" +
        """"headers":[{"first":"X-Token","second":"abc"}],"tools":[{"enable":true,"name":"search","description":"web",""" +
        """"inputSchema":null,"needsApproval":false}],"oauth":null},"url":"https://example.com/sse"},""" +
        """{"type":"streamable_http","id":"00000000-0000-0000-0000-000000000052","commonOptions":{"enable":false,""" +
        """"name":"remote","headers":[],"tools":[],"oauth":{"enabled":true,"clientId":"cid","clientSecret":null,""" +
        """"authorizationEndpoint":"https://auth.example.com/authorize","tokenEndpoint":"https://auth.example.com/token",""" +
        """"registrationEndpoint":null,"redirectUri":"http://127.0.0.1:1455/callback","scope":null,""" +
        """"accessToken":"at","refreshToken":"rt","expiresAt":0}},"url":"https://example.com/mcp"}]"""
