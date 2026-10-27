package whaleui.nightsky.ltd.ui.advanced

import me.rerere.common.android.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志页的脱敏与格式化（纯函数，便于单测）。
 *
 * 数据源是 `Logging` 的进程内列表：
 * - **请求日志**（`RequestLog`）：`RequestLoggingInterceptor` 记的 URL / 头 / 请求体 / 状态码 / 错误，
 *   只有打开「记录请求」后才记；
 * - **应用日志**（`TextLog`）：ChatService 生成失败、文件处理失败等。
 *
 * Key 防线有三层：拦截器记录时已经抹掉鉴权头（`RequestLoggingInterceptor`）；这里展示和复制前
 * 再按头名、URL 查询参数、`Bearer …` / `sk-…` 形态抹一遍；最后把 SecretStore 里当前的两个 Key
 * 原文（[knownSecrets]）在全文里替换掉——即使某个服务把 Key 放在了别的地方也不会露出来。
 */
class AdvancedLogFormat(knownSecrets: Collection<String> = emptyList()) {

    /** 太短的串不当 Key 替换，免得把正常文字抹掉。 */
    private val secrets: List<String> = knownSecrets
        .map { it.trim() }
        .filter { it.length >= MIN_SECRET_LENGTH }
        .distinct()
        .sortedByDescending { it.length }

    fun redactHeader(name: String, value: String): String =
        if (name.lowercase(Locale.ROOT) in SENSITIVE_HEADERS) MASK else redactText(value)

    fun redactUrl(url: String): String {
        val withoutQueryKeys = QUERY_SECRET.replace(url) { match -> match.groupValues[1] + MASK }
        return redactText(withoutQueryKeys)
    }

    fun redactText(text: String): String {
        var result = text
        secrets.forEach { secret -> result = result.replace(secret, MASK) }
        result = BEARER.replace(result) { it.groupValues[1] + MASK }
        result = JSON_SECRET.replace(result) { it.groupValues[1] + MASK + "\"" }
        result = SK_KEY.replace(result) { "sk-$MASK" }
        return result
    }

    /** 列表里一行要显示的东西；正文、头这些大块只在点开 / 复制时再算。 */
    fun item(entry: LogEntry): AdvancedLogItem = when (entry) {
        is LogEntry.RequestLog -> AdvancedLogItem(
            id = entry.id.toString(),
            isRequest = true,
            time = timeFormat().format(Date(entry.timestamp)),
            title = "${entry.method} ${redactUrl(entry.url)}",
            model = entry.requestBody?.let(::modelOf),
            status = when {
                entry.error != null -> "失败：" + redactText(entry.error.orEmpty()).take(STATUS_CHARS)
                entry.responseCode != null -> buildString {
                    append(entry.responseCode)
                    entry.durationMs?.let { append(" · ").append(it).append(" ms") }
                }

                else -> null
            },
            failed = entry.error != null || (entry.responseCode != null && entry.responseCode !in 200..299),
        )

        is LogEntry.TextLog -> AdvancedLogItem(
            id = entry.id.toString(),
            isRequest = false,
            time = timeFormat().format(Date(entry.timestamp)),
            title = entry.tag,
            model = null,
            status = redactText(entry.message).take(PREVIEW_CHARS),
            failed = false,
        )
    }

    /** 单条的完整文字（详情 / 复制用），全部脱敏；请求体太长时截断。 */
    fun detail(entry: LogEntry): String = when (entry) {
        is LogEntry.RequestLog -> buildString {
            appendLine("时间：" + fullTimeFormat().format(Date(entry.timestamp)))
            appendLine("请求：${entry.method} ${redactUrl(entry.url)}")
            entry.responseCode?.let { appendLine("状态：$it") }
            entry.durationMs?.let { appendLine("耗时：$it ms") }
            entry.error?.let { appendLine("错误：" + redactText(it)) }
            if (entry.requestHeaders.isNotEmpty()) {
                appendLine()
                appendLine("请求头：")
                entry.requestHeaders.forEach { (name, value) -> appendLine("  $name: ${redactHeader(name, value)}") }
            }
            entry.requestBody?.let { body ->
                appendLine()
                appendLine("请求体：")
                val redacted = redactText(body)
                if (redacted.length > MAX_BODY_CHARS) {
                    appendLine(redacted.take(MAX_BODY_CHARS))
                    appendLine("…（共 ${redacted.length} 字，只显示前 $MAX_BODY_CHARS 字）")
                } else {
                    appendLine(redacted)
                }
            }
            if (entry.responseHeaders.isNotEmpty()) {
                appendLine()
                appendLine("响应头：")
                entry.responseHeaders.forEach { (name, value) -> appendLine("  $name: ${redactHeader(name, value)}") }
            }
        }.trimEnd()

        is LogEntry.TextLog -> buildString {
            appendLine("时间：" + fullTimeFormat().format(Date(entry.timestamp)))
            appendLine("标签：${entry.tag}")
            appendLine()
            append(redactText(entry.message))
        }.trimEnd()
    }

    /** 从请求体 JSON 里取 `"model": "…"`（第一处），不完整解析整个正文。 */
    internal fun modelOf(body: String): String? =
        MODEL_FIELD.find(body.take(MODEL_SCAN_CHARS))?.groupValues?.get(1)

    private fun timeFormat() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private fun fullTimeFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    companion object {
        const val MASK = "██"
        private const val MIN_SECRET_LENGTH = 8
        private const val STATUS_CHARS = 120
        private const val PREVIEW_CHARS = 160
        const val MAX_BODY_CHARS = 20_000
        private const val MODEL_SCAN_CHARS = 200_000

        val SENSITIVE_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "x-api-key",
            "api-key",
            "x-goog-api-key",
            "cookie",
            "set-cookie",
        )

        private val QUERY_SECRET = Regex("""(?i)([?&](?:key|api_key|apikey|access_token|token)=)[^&#\s]*""")
        private val BEARER = Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=\-]+""")
        private val JSON_SECRET = Regex(
            """(?i)("(?:api_?key|apikey|access_token|authorization|password)"\s*:\s*")[^"]*"""",
        )
        private val SK_KEY = Regex("""sk-[A-Za-z0-9_\-]{8,}""")
        private val MODEL_FIELD = Regex(""""model"\s*:\s*"([^"]{1,200})"""")
    }
}

/** 日志列表里的一行（不含正文，正文点开时再算）。 */
data class AdvancedLogItem(
    val id: String,
    val isRequest: Boolean,
    val time: String,
    val title: String,
    val model: String?,
    val status: String?,
    val failed: Boolean,
)
