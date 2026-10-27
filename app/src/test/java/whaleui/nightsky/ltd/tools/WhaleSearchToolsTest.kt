package whaleui.nightsky.ltd.tools

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import whaleui.nightsky.ltd.tools.deepseek.DeepSeekSearchService
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * [WhaleSearchTools] 单测。
 *
 * HTTP 走的是 [DeepSeekSearchService]（`DeepSeekSearchService.init` 注入的 OkHttp 会被
 * 这里的拦截器改写到本地 [MockWebServer]），所以全程没有真实网络请求、也不用真实 Key。
 */
class WhaleSearchToolsTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        DeepSeekSearchService.init(testClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
        // 还原成 DeepSeekSearchService 的默认形态，避免污染同一 JVM 里的其它测试。
        DeepSeekSearchService.init(
            OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        )
    }

    // ------------------------------------------------------------ search_web

    @Test
    fun searchMapsNativeResultToHermesFields() = runTest {
        server.enqueue(jsonResponse(SEARCH_SUCCESS_BODY))
        val result = execute("search_web", """{"query":"示例查询"}""")

        val item = result["results"]!!.jsonArray.single().jsonObject
        assertEquals("示例标题一", text(item["title"]))
        assertEquals("https://example.com/a", text(item["url"]))
        // 恒为 null 的 site 不再发给她（提示词第 9 批）
        assertFalse(item.containsKey("site"))
        assertEquals("2026-10-20", text(item["date"]))
        assertEquals("示例摘要一", text(item["summary"]))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/tools/search", request.requestUrl!!.encodedPath)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("示例查询", text(body["query"]))
        assertEquals(5, body["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun searchFallsBackToSnippetAndKeepsNullDate() = runTest {
        server.enqueue(
            jsonResponse(
                """{"results":[{"title":"示例标题二","url":"https://example.com/b","snippet":"示例片段二","summary":""}]}"""
            )
        )
        val item = execute("search_web", """{"query":"示例"}""")["results"]!!.jsonArray.single().jsonObject
        assertEquals("示例片段二", text(item["summary"]))
        assertEquals(JsonNull, item["date"])
    }

    @Test
    fun searchTrimsAndTruncatesQueryAndClampsCount() = runTest {
        server.enqueue(jsonResponse(SEARCH_SUCCESS_BODY))
        execute("search_web", buildJsonObject { put("query", "问".repeat(200)); put("count", 99) }.toString())
        var body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(128, text(body["query"]).length)
        assertEquals(10, body["count"]!!.jsonPrimitive.int)

        server.enqueue(jsonResponse(SEARCH_SUCCESS_BODY))
        execute("search_web", """{"query":"  示例查询  ","count":0}""")
        body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("示例查询", text(body["query"]))
        assertEquals(1, body["count"]!!.jsonPrimitive.int)

        server.enqueue(jsonResponse(SEARCH_SUCCESS_BODY))
        execute("search_web", """{"query":"示例","count":"abc"}""")
        body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(5, body["count"]!!.jsonPrimitive.int)

        server.enqueue(jsonResponse(SEARCH_SUCCESS_BODY))
        execute("search_web", """{"query":"示例","count":"3"}""")
        body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(3, body["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun searchEmptyQueryReturnsErrorWithoutRequest() = runTest {
        val limit = WhaleDailyLimit()
        val result = execute("search_web", """{"query":"   "}""", limit = limit)
        assertEquals("query 不能为空", text(result["error"]))
        assertEquals(0, server.requestCount)
        assertEquals(100, limit.remaining(WhaleDailyLimit.TOOL_SEARCH_WEB))
    }

    @Test
    fun missingKeyReturnsExactErrorWithoutRequest() = runTest {
        assertEquals(
            listOf("search_web", "read_webpage"),
            WhaleSearchTools(DeepSeekSearchService, WhaleDailyLimit()).create(null).map { it.name },
        )
        assertEquals(
            "暂时搜不了：还没设置 DeepSeek 的 Key，这要对方去设置里处理",
            text(execute("search_web", """{"query":"示例"}""", apiKey = null)["error"]),
        )
        assertEquals(
            "网页暂时打不开：还没设置 DeepSeek 的 Key，这要对方去设置里处理",
            text(execute("read_webpage", """{"url":"https://example.com"}""", apiKey = null)["error"]),
        )
        assertEquals(
            "暂时搜不了：还没设置 DeepSeek 的 Key，这要对方去设置里处理",
            text(execute("search_web", """{"query":"示例"}""", apiKey = "   ")["error"]),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun searchMapsAllHermesHttpErrorCodesToChinese() = runTest {
        for ((code, message) in HERMES_ERRORS) {
            server.enqueue(
                MockResponse().setResponseCode(code).setBody("""{"error":{"message":"mock"}}""")
            )
            val result = execute("search_web", """{"query":"示例"}""")
            assertEquals("HTTP $code 应翻成「$message」", message, text(result["error"]))
        }
        assertEquals(HERMES_ERRORS.size, server.requestCount)
    }

    @Test
    fun searchUsesHttpCodeForUnmappedStatus() = runTest {
        server.enqueue(MockResponse().setResponseCode(418).setBody("""{"error":{"message":"teapot"}}"""))
        assertEquals("暂时搜不了，等会儿再试", text(execute("search_web", """{"query":"示例"}""")["error"]))
    }

    @Test
    fun searchTimeoutUsesRequestFailedWithExceptionName() = runTest {
        DeepSeekSearchService.init(testClient(readTimeoutMs = 300))
        server.enqueue(jsonResponse(SEARCH_SUCCESS_BODY).setBodyDelay(2, TimeUnit.SECONDS))
        val result = execute("search_web", """{"query":"示例"}""")
        // 不带异常类名
        assertEquals("暂时搜不了，等会儿再试", text(result["error"]))
    }

    @Test
    fun searchStopsAfterDailyLimitWithoutRequest() = runTest {
        val limit = WhaleDailyLimit(searchLimit = 2, fetchLimit = 1)
        repeat(2) { index ->
            server.enqueue(jsonResponse(SEARCH_SUCCESS_BODY))
            execute("search_web", """{"query":"示例$index"}""", limit = limit)
        }
        val blocked = execute("search_web", """{"query":"示例"}""", limit = limit)
        assertEquals("今天的搜索次数用完了，明天再试", text(blocked["error"]))
        assertEquals(2, server.requestCount)
        assertEquals(0, limit.remaining(WhaleDailyLimit.TOOL_SEARCH_WEB))
    }

    @Test
    fun searchSchemaMatchesHermes() {
        val tool = WhaleSearchTools(DeepSeekSearchService, WhaleDailyLimit())
            .create("test-key").single { it.name == "search_web" }
        assertEquals(
            "搜索网页，返回相关结果的标题、链接、发布日期和摘要。支持 site: 和引号精确搜索。",
            tool.description,
        )
        val schema = tool.parameters() as InputSchema.Obj
        assertEquals(listOf("query"), schema.required)
        assertEquals(
            buildJsonObject {
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
            schema.properties,
        )
    }

    // ------------------------------------------------------------ read_webpage

    @Test
    fun readWebpageTruncatesContentTo6000Chars() = runTest {
        val longContent = "字".repeat(6001)
        server.enqueue(jsonResponse(fetchBody("示例网页标题", longContent)))
        val result = execute("read_webpage", """{"url":"https://example.com/page"}""")

        assertEquals("示例网页标题", text(result["title"]))
        assertEquals(6000, text(result["content"]).length)
        assertEquals(true, result["truncated"]!!.jsonPrimitive.content.toBoolean())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/tools/fetch", request.requestUrl!!.encodedPath)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("https://example.com/page", text(body["url"]))
    }

    @Test
    fun readWebpageKeepsShortContentUntruncated() = runTest {
        server.enqueue(jsonResponse(fetchBody("示例网页标题", "示例正文")))
        val result = execute("read_webpage", """{"url":"http://example.com/short"}""")
        assertEquals("示例正文", text(result["content"]))
        assertEquals(false, result["truncated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun readWebpageRejectsNonHttpUrlsWithoutRequest() = runTest {
        for (badUrl in listOf("file:///etc/passwd", "example.com", "")) {
            val result = execute("read_webpage", buildJsonObject { put("url", badUrl) }.toString())
            assertEquals("url 必须以 http:// 或 https:// 开头", text(result["error"]))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun readWebpageMapsHttpErrorCodesToChinese() = runTest {
        for (code in listOf(403, 404, 500, 504)) {
            server.enqueue(
                MockResponse().setResponseCode(code).setBody("""{"error":{"message":"mock"}}""")
            )
            val result = execute("read_webpage", """{"url":"https://example.com/page"}""")
            assertEquals(WEBPAGE_ERRORS.getValue(code), text(result["error"]))
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun readWebpageStopsAfterDailyLimitWithoutRequest() = runTest {
        val limit = WhaleDailyLimit(searchLimit = 5, fetchLimit = 1)
        server.enqueue(jsonResponse(fetchBody("示例网页标题", "示例正文")))
        execute("read_webpage", """{"url":"https://example.com/1"}""", limit = limit)
        val blocked = execute("read_webpage", """{"url":"https://example.com/2"}""", limit = limit)
        assertEquals("今天的网页阅读次数用完了，明天再试", text(blocked["error"]))
        assertEquals(1, server.requestCount)
        assertEquals(0, limit.remaining(WhaleDailyLimit.TOOL_READ_WEBPAGE))
        // 读网页用完不影响搜索额度。
        assertEquals(5, limit.remaining(WhaleDailyLimit.TOOL_SEARCH_WEB))
    }

    @Test
    fun readWebpageSchemaMatchesHermes() {
        val tool = WhaleSearchTools(DeepSeekSearchService, WhaleDailyLimit())
            .create("test-key").single { it.name == "read_webpage" }
        assertEquals("打开一个网页，返回标题和正文（Markdown）。", tool.description)
        val schema = tool.parameters() as InputSchema.Obj
        assertEquals(listOf("url"), schema.required)
        assertEquals(
            buildJsonObject {
                put(
                    "url",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "网页地址，http 或 https")
                    },
                )
            },
            schema.properties,
        )
    }

    // ------------------------------------------------------------ 小工具

    private suspend fun execute(
        toolName: String,
        args: JsonElement,
        apiKey: String? = "test-key",
        limit: WhaleDailyLimit = WhaleDailyLimit(),
    ): JsonObject {
        val tool = WhaleSearchTools(DeepSeekSearchService, limit)
            .create(apiKey)
            .single { it.name == toolName }
        val parts = tool.execute(args)
        assertEquals(1, parts.size)
        return json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }

    private suspend fun execute(
        toolName: String,
        args: String,
        apiKey: String? = "test-key",
        limit: WhaleDailyLimit = WhaleDailyLimit(),
    ): JsonObject = execute(toolName, json.parseToJsonElement(args), apiKey, limit)

    /** 测试用 OkHttp：把原生服务对 api.deepseek.com 的请求改写到本地 MockWebServer。 */
    private fun testClient(readTimeoutMs: Long = 5_000L): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            // 原生服务默认会重试 408/503，这里关掉，否则 MockWebServer 的 408 会被重试到超时。
            .retryOnConnectionFailure(false)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val local = server.url("/")
                val rewritten = original.url.newBuilder()
                    .scheme(local.scheme)
                    .host(local.host)
                    .port(local.port)
                    .build()
                chain.proceed(original.newBuilder().url(rewritten).build())
            }
            .build()

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)

    private fun fetchBody(title: String, content: String): String = buildJsonObject {
        put("title", title)
        put("content", content)
    }.toString()

    private fun text(element: JsonElement?): String = element!!.jsonPrimitive.content

    private companion object {
        const val SEARCH_SUCCESS_BODY: String =
            """{"results":[{"title":"示例标题一","url":"https://example.com/a",""" +
                """"snippet":"示例摘要一","summary":"","site_name":"example.com","date":"2026-10-20"}]}"""

        /** search_web 的错误码 → 她视角的说法（提示词第 10 批方案 A），用来断言错误码翻译。 */
        val HERMES_ERRORS: Map<Int, String> = mapOf(
            400 to "这次没成功，换个说法再试试",
            401 to "暂时搜不了：DeepSeek 的 Key 不对，这要对方去设置里处理",
            402 to "暂时搜不了：服务那边余额不足，这要对方去设置里处理",
            403 to "这个网页不让抓取，换一个看看",
            404 to "这个网页抓不到可用的内容，换一个看看",
            408 to "暂时搜不了，等会儿再试",
            413 to "内容太长了，换个短一点的",
            429 to "暂时搜不了，等会儿再试",
            500 to "暂时搜不了，等会儿再试",
            504 to "暂时搜不了，等会儿再试",
        )

        /** read_webpage 的错误码 → 她视角的说法。 */
        val WEBPAGE_ERRORS: Map<Int, String> = mapOf(
            403 to "这个网页不让抓取，换一个看看",
            404 to "这个网页抓不到可用的内容，换一个看看",
            500 to "网页暂时打不开，等会儿再试",
            504 to "网页暂时打不开，等会儿再试",
        )
    }
}
