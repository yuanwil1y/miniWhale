package whaleui.nightsky.ltd.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import whaleui.nightsky.ltd.tools.deepseek.DeepSeekOptions
import whaleui.nightsky.ltd.tools.deepseek.DeepSeekSearchService
import whaleui.nightsky.ltd.tools.deepseek.SearchCommonOptions
import whaleui.nightsky.ltd.tools.deepseek.SearchResult

/**
 * `search_web` / `read_webpage` 两个 RikkaHub 原生 [Tool]。
 *
 * HTTP 走 [DeepSeekSearchService]（S9b 从 RikkaHub `:search` 原样搬来；`POST /v1/tools/search`、
 * `POST /v1/tools/fetch`），这一层只做 Hermes `plugins/deepseek_web/__init__.py` 的语义：
 * 名称/描述/schema、query 截 128 字、count 1-10 默认 5、错误码中文、正文截 6000 字、每日上限。
 *
 * 说明：原生 [SearchResult.SearchResultItem] 没有把 DeepSeek 返回的 `site_name` 透出来，
 * 所以输出的 `site` 恒为 JSON null（Hermes 在接口没给 site_name 时也是 null）。
 * 每日上限用 [WhaleDailyLimit]（纯内存），超限直接返回中文提示、不发请求。
 */
class WhaleSearchTools(
    private val deepSeek: DeepSeekSearchService = DeepSeekSearchService,
    private val dailyLimit: WhaleDailyLimit = WhaleDailyLimit(),
    /** 高级设置里 DeepSeek 的 Base URL（执行时读，改了立即生效）；null / 空 = 官方地址（N-007） */
    private val deepSeekBaseUrl: () -> String? = { null },
) {

    /** 返回 `search_web` 与 `read_webpage` 两个工具；[apiKey] 为空时工具照常存在，执行时返回「没有配置 DeepSeek key」。 */
    fun create(apiKey: String?): List<Tool> {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        return listOf(searchWebTool(key), readWebpageTool(key))
    }

    // ------------------------------------------------------------ search_web

    private fun searchWebTool(apiKey: String?) = Tool(
        name = "search_web",
        description = SEARCH_DESCRIPTION,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "搜索词，不超过 128 个字")
                        },
                    )
                    put(
                        "count",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "返回条数，1-10，默认 5")
                        },
                    )
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            listOf(UIMessagePart.Text(search(apiKey, args)))
        },
    )

    private suspend fun search(apiKey: String?, args: JsonElement): String {
        val obj = args as? JsonObject
        val query = obj.stringOr("query")?.trim()?.take(MAX_QUERY_CHARS) ?: ""
        if (query.isEmpty()) return errorJson("query 不能为空")
        val count = obj.intOr("count")?.coerceIn(MIN_COUNT, MAX_COUNT) ?: DEFAULT_COUNT
        if (apiKey == null) return errorJson(WhaleToolErrors.Search.noKey("DeepSeek"))
        if (!dailyLimit.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB)) {
            return errorJson(SEARCH_LIMIT_MESSAGE)
        }
        return try {
            deepSeek.search(
                params = buildJsonObject {
                    put("query", query)
                    put("count", count)
                },
                commonOptions = SearchCommonOptions(resultSize = count),
                serviceOptions = DeepSeekOptions(apiKey = apiKey, baseUrl = deepSeekBaseUrl()),
            ).fold(
                onSuccess = { result -> searchResultJson(result) },
                onFailure = { error -> errorJson(errorMessage(error, WhaleToolErrors.Search)) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson(errorMessage(e, WhaleToolErrors.Search))
        }
    }

    /** results[]：title/url/date/summary（原生已把 summary 为空的情况回落到 snippet；恒为 null 的 site 不发）。 */
    private fun searchResultJson(result: SearchResult): String = buildJsonObject {
        put(
            "results",
            buildJsonArray {
                result.items.forEach { item ->
                    add(
                        buildJsonObject {
                            put("title", item.title)
                            put("url", item.url)
                            put("date", item.publishedDate?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("summary", item.text)
                        },
                    )
                }
            },
        )
    }.toString()

    // ------------------------------------------------------------ read_webpage

    private fun readWebpageTool(apiKey: String?) = Tool(
        name = "read_webpage",
        description = FETCH_DESCRIPTION,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "url",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "网页地址，http 或 https")
                        },
                    )
                },
                required = listOf("url"),
            )
        },
        execute = { args ->
            listOf(UIMessagePart.Text(readWebpage(apiKey, args)))
        },
    )

    private suspend fun readWebpage(apiKey: String?, args: JsonElement): String {
        val url = (args as? JsonObject).stringOr("url")?.trim() ?: ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return errorJson("url 必须以 http:// 或 https:// 开头")
        }
        if (apiKey == null) return errorJson(WhaleToolErrors.Webpage.noKey("DeepSeek"))
        if (!dailyLimit.tryConsume(WhaleDailyLimit.TOOL_READ_WEBPAGE)) {
            return errorJson(FETCH_LIMIT_MESSAGE)
        }
        return try {
            deepSeek.scrape(
                params = buildJsonObject { put("url", url) },
                commonOptions = SearchCommonOptions(),
                serviceOptions = DeepSeekOptions(apiKey = apiKey, baseUrl = deepSeekBaseUrl()),
            ).fold(
                onSuccess = { scraped ->
                    val page = scraped.urls.firstOrNull()
                    val content = page?.content ?: ""
                    buildJsonObject {
                        put("title", page?.metadata?.title ?: "")
                        put("content", content.take(FETCH_MAX_CHARS))
                        put("truncated", content.length > FETCH_MAX_CHARS)
                    }.toString()
                },
                onFailure = { error -> errorJson(errorMessage(error, WhaleToolErrors.Webpage)) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson(errorMessage(e, WhaleToolErrors.Webpage))
        }
    }

    // ------------------------------------------------------------ 小工具

    /**
     * 原生服务把 HTTP 非 2xx 折成 `IllegalStateException("DeepSeek search failed: #401 ...")`，
     * 这里把 `#code` 翻成她视角的说法（提示词第 9 / 10 批）：Key 不对、余额不足要对方去设置里处理；
     * 网页本身的问题让她换一个；其它（超时、网络、服务出错）一律「等会儿再试」，不带异常类名。
     */
    private fun errorMessage(error: Throwable, errors: WhaleToolErrors): String {
        val code = HTTP_STATUS_REGEX.find(error.message.orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: return errors.temporary()
        return when (code) {
            401 -> errors.badKey("DeepSeek")
            402 -> errors.noBalance()
            else -> ERRORS[code] ?: errors.temporary()
        }
    }

    private fun errorJson(message: String): String =
        buildJsonObject { put("error", message) }.toString()

    private fun JsonObject?.stringOr(key: String): String? {
        val primitive = this?.get(key) as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.contentOrNull
    }

    private fun JsonObject?.intOr(key: String): Int? =
        (this?.get(key) as? JsonPrimitive)?.intOrNull

    private companion object {
        const val SEARCH_DESCRIPTION: String =
            "搜索网页，返回相关结果的标题、链接、发布日期和摘要。支持 site: 和引号精确搜索。"
        const val FETCH_DESCRIPTION: String = "打开一个网页，返回标题和正文（Markdown）。"

        const val SEARCH_LIMIT_MESSAGE: String = "今天的搜索次数用完了，明天再试"
        const val FETCH_LIMIT_MESSAGE: String = "今天的网页阅读次数用完了，明天再试"

        const val MAX_QUERY_CHARS: Int = 128
        const val MIN_COUNT: Int = 1
        const val MAX_COUNT: Int = 10
        const val DEFAULT_COUNT: Int = 5
        const val FETCH_MAX_CHARS: Int = 6000

        val HTTP_STATUS_REGEX: Regex = Regex("#(\\d{3})")

        /** 和请求内容有关、换一下就可能好的错误（401 / 402 / 临时问题在 [errorMessage] 里单独处理）。 */
        val ERRORS: Map<Int, String> = mapOf(
            400 to "这次没成功，换个说法再试试",
            403 to "这个网页不让抓取，换一个看看",
            404 to "这个网页抓不到可用的内容，换一个看看",
            413 to "内容太长了，换个短一点的",
        )
    }
}
