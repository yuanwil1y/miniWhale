package whaleui.nightsky.ltd.ui.advanced

import androidx.compose.runtime.Immutable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.remote.toProxyOrNull
import whaleui.nightsky.ltd.ui.settings.SettingsToast
import java.net.URI
import kotlin.uuid.Uuid

/** 高级设置的四块。null = 入口列表。 */
enum class AdvancedSection(val title: String) {
    Providers("供应商"),
    Model("模型"),
    Proxy("代理"),
    Logs("日志"),
}

/** Key 的显示状态：只有「已填写 / 未填写」，原文的任何一位都不进界面状态（D：连后 4 位也不显示）。 */
@Immutable
data class KeyStatus(
    val saved: Boolean = false,
) {
    val label: String
        get() = if (saved) "已填写" else "未填写"

    companion object {
        fun of(value: String?): KeyStatus = KeyStatus(saved = !value.isNullOrBlank())
    }
}

/** 所有「点一下弹输入框」的字段。 */
enum class AdvancedField(
    val title: String,
    /** 对话框标题下的说明；空串 = 不显示。 */
    val hint: String,
    val secret: Boolean = false,
    val numeric: Boolean = false,
    /** 输入框为空时的占位字。 */
    val placeholder: String? = null,
) {
    DeepSeekBaseUrl("DeepSeek 接口地址", "留空恢复默认 ${AdvancedDefaults.DEEPSEEK_BASE_URL}"),
    DeepSeekKey("DeepSeek 密钥", "", secret = true, placeholder = KEY_PLACEHOLDER),
    MiniMaxBaseUrl("MiniMax 接口地址", "留空恢复默认"),
    MiniMaxKey("MiniMax 密钥", "", secret = true, placeholder = KEY_PLACEHOLDER),
    MiniMaxSpeechModel("语音模型", "留空恢复默认"),
    MiniMaxImageModel("作图模型", "留空恢复默认"),
    CustomModelId("手动填写模型 ID", "例如 deepseek-chat"),
    ProxyHost("代理地址", "例如 127.0.0.1"),
    ProxyPort("代理端口", "1–65535", numeric = true),
    ProxyUsername("代理用户名", "不需要认证就留空"),
    ProxyPassword("代理密码", "不需要认证就留空", secret = true),
    ;

    /** 密钥字段对应的 [SecretProvider]（不是密钥字段时为 null）。 */
    val keyProvider: whaleui.nightsky.ltd.domain.settings.SecretProvider?
        get() = when (this) {
            DeepSeekKey -> whaleui.nightsky.ltd.domain.settings.SecretProvider.DeepSeek
            MiniMaxKey -> whaleui.nightsky.ltd.domain.settings.SecretProvider.MiniMax
            else -> null
        }
}

private const val KEY_PLACEHOLDER = "粘贴新的密钥，留空保持不变"

object AdvancedDefaults {
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"
}

/** 弹层（底部列表）。 */
enum class AdvancedSheet { ModelPicker, ReasoningPicker, ProxyType }

@Immutable
data class AdvancedEditor(val field: AdvancedField, val initial: String)

@Immutable
data class ModelOption(
    val modelId: String,
    /** 已经在 DeepSeek 供应商的模型列表里时有值；只在在线列表里的为 null（选中时再加进去）。 */
    val uuid: Uuid?,
    val supportsReasoning: Boolean,
) {
    /** 已加进供应商、又不是内置 deepseek-flash 的，可以从列表里移除（正在用的除外）。 */
    val removable: Boolean get() = uuid != null && modelId != whaleui.nightsky.ltd.whale.WhaleBootstrap.DEEPSEEK_FLASH_MODEL_ID
}

enum class ProxyType(val scheme: String, val label: String) {
    Http("http", "HTTP"),
    Socks("socks5", "SOCKS5"),
}

/**
 * 代理的编辑状态。存储层（`NetworkSetting`）只有一个 `proxyUrl` 字符串，没有单独的开关：
 * 关掉 = 写空串。地址和端口在关掉后仍留在本页内存里，再打开时不用重填。
 */
@Immutable
data class ProxyDraft(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.Http,
    val host: String = "",
    val port: String = "",
    val username: String = "",
    val passwordSet: Boolean = false,
) {
    /** 要写进 `proxyUrl` 的值；没开或者不完整时是空串（等于不走代理）。 */
    fun toProxyUrl(): String {
        if (!enabled) return ""
        return candidateUrl()?.takeIf { it.toProxyOrNull() != null }.orEmpty()
    }

    /** 开着但地址 / 端口不完整或不合法。 */
    val incomplete: Boolean get() = enabled && toProxyUrl().isEmpty()

    val summary: String
        get() = when {
            !enabled -> "未开启"
            incomplete -> "未生效：地址或端口不完整"
            else -> "${type.label} ${hostForUrl()}:${port.trim()}"
        }

    private fun candidateUrl(): String? {
        val h = host.trim()
        val p = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (h.isEmpty() || h.any { it.isWhitespace() } || "/" in h || "@" in h) return null
        return "${type.scheme}://${hostForUrl()}:$p"
    }

    private fun hostForUrl(): String {
        val h = host.trim()
        return if (":" in h && !h.startsWith("[")) "[$h]" else h
    }

    companion object {
        /** 从已保存的设置还原（`proxyUrl` 为空 = 没开）。 */
        fun from(proxyUrl: String, username: String, password: String): ProxyDraft {
            val base = ProxyDraft(username = username, passwordSet = password.isNotEmpty())
            if (proxyUrl.isBlank() || proxyUrl.toProxyOrNull() == null) return base
            val normalized = if ("://" in proxyUrl) proxyUrl.trim() else "http://${proxyUrl.trim()}"
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return base
            val type = if (uri.scheme.equals("socks5", ignoreCase = true)) ProxyType.Socks else ProxyType.Http
            return base.copy(
                enabled = true,
                type = type,
                host = uri.host.orEmpty().removeSurrounding("[", "]"),
                port = uri.port.toString(),
            )
        }
    }
}

/** 思考强度的可选项（DeepSeek：中、高都按 high 发；最高 = max）。 */
val ReasoningChoices: List<ReasoningLevel> = listOf(
    ReasoningLevel.AUTO,
    ReasoningLevel.LOW,
    ReasoningLevel.MEDIUM,
    ReasoningLevel.HIGH,
    ReasoningLevel.MAX,
)

val ReasoningLevel.label: String
    get() = when (this) {
        ReasoningLevel.OFF -> "关闭"
        ReasoningLevel.AUTO -> "自动"
        ReasoningLevel.LOW -> "低"
        ReasoningLevel.MEDIUM -> "中"
        ReasoningLevel.HIGH -> "高"
        ReasoningLevel.XHIGH -> "很高"
        ReasoningLevel.MAX -> "最高"
    }

@Immutable
data class AdvancedUiState(
    val section: AdvancedSection? = null,
    val loaded: Boolean = false,
    // 供应商
    val deepSeekBaseUrl: String = "",
    val deepSeekProviderMissing: Boolean = false,
    val deepSeekKey: KeyStatus = KeyStatus(),
    val miniMaxKey: KeyStatus = KeyStatus(),
    val miniMax: MiniMaxConfigValues = MiniMaxConfigValues("", "", ""),
    // 模型
    val currentModelId: String? = null,
    val models: List<ModelOption> = emptyList(),
    val modelsRefreshing: Boolean = false,
    val reasoningSupported: Boolean = false,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    // 代理
    val proxy: ProxyDraft = ProxyDraft(),
    // 日志
    val requestLoggingEnabled: Boolean = false,
    val logs: List<AdvancedLogItem> = emptyList(),
    val logDetail: AdvancedLogDetail? = null,
    // 浮层
    val editor: AdvancedEditor? = null,
    /** 「清除」密钥的二次确认框（盖在密钥对话框上）。 */
    val clearKeyConfirm: whaleui.nightsky.ltd.domain.settings.SecretProvider? = null,
    val sheet: AdvancedSheet? = null,
    val toast: SettingsToast? = null,
) {
    fun keyStatus(provider: whaleui.nightsky.ltd.domain.settings.SecretProvider): KeyStatus = when (provider) {
        whaleui.nightsky.ltd.domain.settings.SecretProvider.DeepSeek -> deepSeekKey
        whaleui.nightsky.ltd.domain.settings.SecretProvider.MiniMax -> miniMaxKey
        whaleui.nightsky.ltd.domain.settings.SecretProvider.ProxyPassword -> KeyStatus(saved = proxy.passwordSet)
    }

    val modelSummary: String
        get() {
            val model = currentModelId ?: return "未选择"
            return if (reasoningSupported) "$model · 思考 ${reasoningLevel.label}" else model
        }
}

@Immutable
data class AdvancedLogDetail(val id: String, val title: String, val text: String)

/** 界面能做的所有动作（[AdvancedSettingsViewModel] 实现；预览里用空实现）。 */
interface AdvancedActions {
    fun enterPage()
    fun openSection(section: AdvancedSection)
    fun closeSection()
    fun openEditor(field: AdvancedField)
    fun dismissEditor()
    fun submitEditor(value: String)
    /** 密钥对话框里点「清除」：先弹确认框。 */
    fun requestClearSecret(provider: whaleui.nightsky.ltd.domain.settings.SecretProvider)
    fun cancelClearSecret()
    /** 确认清除（确认框里的「清除」）。 */
    fun clearSecret(provider: whaleui.nightsky.ltd.domain.settings.SecretProvider)
    fun openSheet(sheet: AdvancedSheet)
    fun closeSheet()
    fun refreshModels()
    fun selectModel(modelId: String)
    fun removeModel(modelId: String)
    fun setReasoningEnabled(enabled: Boolean)
    fun setReasoningLevel(level: ReasoningLevel)
    fun setProxyEnabled(enabled: Boolean)
    fun setProxyType(type: ProxyType)
    fun refreshLogs()
    fun setRequestLogging(enabled: Boolean)
    fun clearLogs()
    fun openLogDetail(id: String)
    fun closeLogDetail()

    /** 单条日志脱敏后的全文（复制用）。 */
    suspend fun copyText(id: String): String?
    fun showToast(text: String, kind: whaleui.nightsky.ltd.ui.settings.SettingsToastKind = whaleui.nightsky.ltd.ui.settings.SettingsToastKind.Info)
    fun dismissToast()
}
