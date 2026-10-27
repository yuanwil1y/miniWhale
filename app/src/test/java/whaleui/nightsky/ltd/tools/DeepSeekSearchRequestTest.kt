package whaleui.nightsky.ltd.tools

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart
import whaleui.nightsky.ltd.tools.deepseek.DeepSeekSearchService
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 钉住她的 `search_web` / `read_webpage` 发给 DeepSeek 的原始请求（改写到本地之前的那一份）：
 * 完整 URL、方法、请求头、Content-Type、请求体字节。S9b 把 DeepSeekSearchService 从 `:search` 搬进
 * whaleui 前后，这个测试必须原样通过。
 */
class DeepSeekSearchRequestTest {

    private lateinit var server: MockWebServer
    private val captured = mutableListOf<Request>()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        DeepSeekSearchService.init(
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .readTimeout(5, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val original = chain.request()
                    captured += original
                    val local = server.url("/")
                    val rewritten = original.url.newBuilder()
                        .scheme(local.scheme).host(local.host).port(local.port).build()
                    chain.proceed(original.newBuilder().url(rewritten).build())
                }
                .build()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        DeepSeekSearchService.init(
            OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        )
    }

    @Test
    fun searchRequestIsPinned() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[]}"""))
        execute("search_web", """{"query":"  示例 \"查询\" site:example.com  ","count":7}""")

        val request = captured.single()
        assertEquals("https://api.deepseek.com/v1/tools/search", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals(listOf("Authorization" to "Bearer test-key"), request.headers.toList())
        assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
        assertEquals("""{"query":"示例 \"查询\" site:example.com","count":7}""", bodyOf(request))
        // 到达服务器的头：OkHttp 自己补的之外没有别的
        val received = server.takeRequest()
        assertEquals("application/json; charset=utf-8", received.getHeader("Content-Type"))
        assertEquals("Bearer test-key", received.getHeader("Authorization"))
    }

    @Test
    fun fetchRequestIsPinned() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"title":"t","content":"c"}"""))
        execute("read_webpage", """{"url":"https://example.com/a?b=1&c=中"}""")

        val request = captured.single()
        assertEquals("https://api.deepseek.com/v1/tools/fetch", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals(listOf("Authorization" to "Bearer test-key"), request.headers.toList())
        assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
        assertEquals("""{"url":"https://example.com/a?b=1&c=中"}""", bodyOf(request))
    }

    @Test
    fun errorBodyMessageAndStatusAreParsedTheSameWay() = runTest {
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":{"message":"余额不足"}}"""))
        assertEquals("""{"error":"暂时搜不了：服务那边余额不足，这要对方去设置里处理"}""", execute("search_web", """{"query":"示例"}""").toString())
        server.enqueue(MockResponse().setResponseCode(429).setBody("not json"))
        assertEquals("""{"error":"网页暂时打不开，等会儿再试"}""", execute("read_webpage", """{"url":"https://example.com"}""").toString())
    }

    @Test
    fun defaultBaseUrlsKeepThePinnedAddress() = runTest {
        // 内置 DeepSeek 供应商的默认值，以及去掉 /v1、带结尾斜杠的写法：地址都与上面钉住的逐字相同
        for (base in listOf("https://api.deepseek.com/v1", "https://api.deepseek.com", "https://api.deepseek.com/v1/", "", "  ", "not a url")) {
            captured.clear()
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[]}"""))
            execute("search_web", """{"query":"示例"}""", baseUrl = base)
            assertEquals(base, "https://api.deepseek.com/v1/tools/search", captured.single().url.toString())
        }
        captured.clear()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"title":"t","content":"c"}"""))
        execute("read_webpage", """{"url":"https://example.com"}""", baseUrl = "https://api.deepseek.com/v1")
        assertEquals("https://api.deepseek.com/v1/tools/fetch", captured.single().url.toString())
    }

    @Test
    fun customBaseUrlMovesSearchToTheSameHost() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[]}"""))
        execute("search_web", """{"query":"示例"}""", baseUrl = "http://gateway.example.net:8080/deepseek/v1")
        assertEquals("http://gateway.example.net:8080/v1/tools/search", captured.single().url.toString())

        captured.clear()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"title":"t","content":"c"}"""))
        execute("read_webpage", """{"url":"https://example.com"}""", baseUrl = "https://ds.example.org")
        assertEquals("https://ds.example.org/v1/tools/fetch", captured.single().url.toString())
        // 请求头 / 请求体不随地址变
        assertEquals(listOf("Authorization" to "Bearer test-key"), captured.single().headers.toList())
    }

    private fun bodyOf(request: Request): String = Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    private suspend fun execute(toolName: String, args: String, baseUrl: String? = null): JsonObject {
        val tool = WhaleSearchTools(DeepSeekSearchService, WhaleDailyLimit(), deepSeekBaseUrl = { baseUrl })
            .create("test-key")
            .single { it.name == toolName }
        val parts = tool.execute(json.parseToJsonElement(args))
        return json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }
}
