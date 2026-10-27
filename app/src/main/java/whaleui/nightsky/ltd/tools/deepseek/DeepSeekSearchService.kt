package whaleui.nightsky.ltd.tools.deepseek

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.util.KeyRoulette
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import whaleui.nightsky.ltd.util.runCatchingCancellable

/**
 * DeepSeek 的联网搜索 / 读网页接口（`POST /v1/tools/search`、`POST /v1/tools/fetch`）。
 *
 * S9b 从 RikkaHub 的 `:search` 模块（`me.rerere.search.DeepSeekSearchService` 及 `SearchService` 伴生对象里的
 * httpClient / keyRoulette / json、`Call.await`）原样搬过来，去掉了设置页用的 Compose 描述和工具 schema：
 * 请求地址、方法、请求头、请求体、错误文案与结果映射逐字不变（`DeepSeekSearchRequestTest` 钉住）。
 */
object DeepSeekSearchService {
    private const val DEFAULT_ORIGIN = "https://api.deepseek.com"

    /**
     * 搜索 / 读网页的地址（N-007）：**主机**跟随高级设置里 DeepSeek 的 Base URL（协议 + 主机 + 端口），
     * 路径固定是 `/v1/tools/<tool>`。
     *
     * 为什么只取主机、不在 Base URL 后面拼：聊天用的 Base URL 写成 `https://api.deepseek.com` 或
     * `https://api.deepseek.com/v1` DeepSeek 都认，直接拼会得到 `/tools/search`（少 /v1）或 `/v1/v1/...`；
     * 而工具接口在 DeepSeek 的 API 主机上永远是 `/v1/tools/<tool>`。换成兼容服务 / 反代时通常只换主机，路径照旧。
     * Base URL 为空或不是合法的 http(s) 地址时用官方主机；默认值下得到的地址与以前逐字相同
     * （`DeepSeekSearchRequestTest` 钉住）。
     */
    internal fun toolsUrl(baseUrl: String?, tool: String): String {
        val base = baseUrl?.trim()?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()
            ?: DEFAULT_ORIGIN.toHttpUrlOrNull()!!
        return HttpUrl.Builder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .addPathSegments("v1/tools/$tool")
            .build()
            .toString()
    }

    // 生产环境由 DataSourceModule 在创建 OkHttpClient 时调用 [init]，换成 App 的客户端（UA、代理、日志脱敏）与 LRU 轮询
    @Volatile
    internal var httpClient: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    internal var keyRoulette: KeyRoulette = KeyRoulette.default()

    fun init(client: OkHttpClient, context: Context? = null) {
        httpClient = client
        keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
    }

    internal val json by lazy {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }

    suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: DeepSeekOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val body = buildJsonObject {
                put("query", query)
                put("count", commonOptions.resultSize.coerceIn(1, 50))
            }

            val request = Request.Builder()
                .url(toolsUrl(serviceOptions.baseUrl, "search"))
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = httpClient.newCall(request).await()
            val bodyRaw = response.body.string()
            if (!response.isSuccessful) {
                error("DeepSeek search failed: ${response.errorMessage(bodyRaw)}")
            }

            val searchResponse = json.decodeFromString<DeepSeekSearchResponse>(bodyRaw)
            SearchResult(
                items = searchResponse.results.map { result ->
                    SearchResult.SearchResultItem(
                        title = result.title,
                        url = result.url,
                        text = result.summary.ifBlank { result.snippet },
                        publishedDate = result.date,
                    )
                }
            )
        }
    }

    suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: DeepSeekOptions,
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val body = buildJsonObject {
                put("url", url)
            }

            val request = Request.Builder()
                .url(toolsUrl(serviceOptions.baseUrl, "fetch"))
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = httpClient.newCall(request).await()
            val bodyRaw = response.body.string()
            if (!response.isSuccessful) {
                error("DeepSeek fetch failed: ${response.errorMessage(bodyRaw)}")
            }

            val fetchResponse = json.decodeFromString<DeepSeekFetchResponse>(bodyRaw)
            ScrapedResult(
                urls = listOf(
                    ScrapedResultUrl(
                        url = url,
                        content = fetchResponse.content,
                        metadata = ScrapedResultMetadata(
                            title = fetchResponse.title.ifBlank { null },
                        )
                    )
                )
            )
        }
    }

    private fun Response.errorMessage(bodyRaw: String): String {
        val message = runCatching {
            json.decodeFromString<DeepSeekErrorResponse>(bodyRaw).error.message
        }.getOrNull() ?: bodyRaw
        return "#$code $message"
    }

    @Serializable
    data class DeepSeekSearchResponse(
        val results: List<DeepSeekSearchResultItem> = emptyList(),
    )

    @Serializable
    data class DeepSeekSearchResultItem(
        val title: String,
        val url: String,
        val snippet: String = "",
        val summary: String = "",
        val content: String = "",
        @SerialName("site_name")
        val siteName: String? = null,
        val date: String? = null,
        @SerialName("rerank_score")
        val rerankScore: Double? = null,
        val authority: Int? = null,
    )

    @Serializable
    data class DeepSeekFetchResponse(
        val title: String = "",
        val content: String = "",
    )

    @Serializable
    data class DeepSeekErrorResponse(
        val error: DeepSeekError,
    )

    @Serializable
    data class DeepSeekError(
        val message: String,
    )
}

private suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { cause, _, _ ->
                    response.closeQuietly()
                }
            }
        })
    }
}
