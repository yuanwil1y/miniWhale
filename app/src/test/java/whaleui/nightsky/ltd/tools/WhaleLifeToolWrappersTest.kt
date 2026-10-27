package whaleui.nightsky.ltd.tools

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [WhaleLifeToolWrappers] 单测：schema/描述与 Hermes `daily_life` 一致，
 * execute 会把参数转发给 [WhaleLifeTools]（请求打到 MockWebServer，读本地样例）。
 */
class WhaleLifeToolWrappersTest {

    private lateinit var server: MockWebServer
    private lateinit var wrappers: WhaleLifeToolWrappers
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        wrappers = WhaleLifeToolWrappers(WhaleLifeTools(client(), Json { ignoreUnknownKeys = true }))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------ schema / 描述

    @Test
    fun toolsUseHermesNamesAndDescriptions() {
        val tools = wrappers.create()
        assertEquals(listOf("hot_list", "weather", "daily_poem"), tools.map(Tool::name))
        assertEquals(
            "看现在网上的热榜。source 可选 baidu（百度热搜）、zhihu（知乎热榜）、" +
                "bili_hot（B站热搜词）、bili_popular（B站热门视频）、ithome（IT之家科技新闻）。",
            tools.single { it.name == "hot_list" }.description,
        )
        assertEquals(
            "查某个地方现在的天气和今明两天预报（含日落时间）。place 填城市名，比如 北京、东京；不填就查对方的常住城市（没填常住城市时会提示先问对方）。",
            tools.single { it.name == "weather" }.description,
        )
        assertEquals("随机抽一句古诗词。", tools.single { it.name == "daily_poem" }.description)
    }

    @Test
    fun hotListSchemaMatchesHermesRegister() {
        val tool = wrappers.create().single { it.name == "hot_list" }
        val schema = tool.parameters() as InputSchema.Obj
        assertEquals(listOf("source"), schema.required)
        val expected = buildJsonObject {
            put(
                "source",
                buildJsonObject {
                    put("type", "string")
                    put("description", "看哪个榜")
                    put(
                        "enum",
                        buildJsonArray {
                            add(JsonPrimitive("baidu"))
                            add(JsonPrimitive("zhihu"))
                            add(JsonPrimitive("bili_hot"))
                            add(JsonPrimitive("bili_popular"))
                            add(JsonPrimitive("ithome"))
                        },
                    )
                },
            )
            put(
                "count",
                buildJsonObject {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 20)
                    put("default", 10)
                    put("description", "看前几条，1-20，默认 10")
                },
            )
        }
        assertEquals(expected, schema.properties)
    }

    @Test
    fun weatherAndPoemSchemasMatchHermesRegister() {
        val tools = wrappers.create()
        val weather = tools.single { it.name == "weather" }.parameters() as InputSchema.Obj
        assertEquals(null, weather.required)
        assertEquals(
            buildJsonObject {
                put(
                    "place",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "城市名，比如 北京、东京；不填就是对方的常住城市")
                    },
                )
            },
            weather.properties,
        )

        val poem = tools.single { it.name == "daily_poem" }.parameters() as InputSchema.Obj
        assertEquals(null, poem.required)
        assertTrue(poem.properties.isEmpty())
    }

    // ------------------------------------------------------------ execute 转发

    @Test
    fun hotListForwardsSourceAndCount() = runTest {
        server.enqueue(jsonResponse(sample("baidu-hot.json")))
        val result = execute("hot_list", """{"source":"baidu","count":2}""")
        assertEquals("百度热搜", text(result["source"]))
        assertEquals(2, result["items"]!!.jsonArray.size)
        val request = server.takeRequest()
        assertEquals("/api/board", request.requestUrl!!.encodedPath)
        assertEquals("wise", request.requestUrl!!.queryParameter("platform"))
    }

    @Test
    fun hotListDefaultsToBaiduWhenSourceIsMissing() = runTest {
        server.enqueue(jsonResponse(sample("baidu-hot.json")))
        val result = execute("hot_list", "{}")
        assertEquals("百度热搜", text(result["source"]))
        // Hermes handler 缺省 count=10；样例只有 3 条非置顶，全部返回。
        assertEquals(3, result["items"]!!.jsonArray.size)
    }

    @Test
    fun weatherForwardsPlace() = runTest {
        server.enqueue(jsonResponse(sample("open-meteo-forecast.json")))
        val result = execute("weather", """{"place":"北京"}""")
        assertEquals("北京", text(result["place"]))
        val request = server.takeRequest()
        assertEquals("/v1/forecast", request.requestUrl!!.encodedPath)
        assertEquals("39.904", request.requestUrl!!.queryParameter("latitude"))
    }

    @Test
    fun weatherWithoutPlaceAndWithoutHomeCityAsksForCity() = runTest {
        // round4 4.3：不填 place、也没填常住城市 -> 明确要求先问他，不再默认任何城市
        assertEquals("不知道对方在哪个城市，先问问对方", text(execute("weather", """{"place":"   "}""")["error"]))
        assertEquals("不知道对方在哪个城市，先问问对方", text(execute("weather", "{}")["error"]))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun weatherWithoutPlaceUsesConfiguredHomeCity() = runTest {
        server.enqueue(jsonResponse(sample("open-meteo-forecast.json")))
        val cityWrappers = WhaleLifeToolWrappers(
            WhaleLifeTools(client(), json, homeCityProvider = { "北京" }),
        )
        val tool = cityWrappers.create().single { it.name == "weather" }
        val parts = tool.execute(json.parseToJsonElement("{}"))
        val result = json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("北京", text(result["place"]))
        val request = server.takeRequest()
        assertEquals("39.904", request.requestUrl!!.queryParameter("latitude"))
    }

    @Test
    fun dailyPoemForwardsEvenWithJsonNullArgs() = runTest {
        server.enqueue(jsonResponse(sample("jinrishici.json")))
        val result = execute("daily_poem", JsonNull)
        assertEquals("示例诗句一，示例诗句下半句。", text(result["line"]))
        assertEquals("示例出处一", text(result["title"]))
        assertEquals("示例作者一", text(result["author"]))
        assertEquals("/all.json", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun executeWrapsResultInASingleTextPart() = runTest {
        server.enqueue(jsonResponse(sample("jinrishici.json")))
        val tool = wrappers.create().single { it.name == "daily_poem" }
        val parts = tool.execute(JsonNull)
        assertEquals(1, parts.size)
        val part = parts.single()
        assertTrue(part is UIMessagePart.Text)
        val parsed = json.parseToJsonElement((part as UIMessagePart.Text).text).jsonObject
        assertEquals("示例诗句一，示例诗句下半句。", text(parsed["line"]))
    }

    // ------------------------------------------------------------ 小工具

    private suspend fun execute(name: String, args: JsonElement): JsonObject {
        val tool = wrappers.create().single { it.name == name }
        val parts = tool.execute(args)
        assertEquals(1, parts.size)
        return json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }

    private suspend fun execute(name: String, args: String): JsonObject =
        execute(name, json.parseToJsonElement(args))

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
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

    private fun sample(name: String): String {
        val stream = checkNotNull(
            WhaleLifeToolWrappersTest::class.java.classLoader?.getResourceAsStream("whale/$name")
        ) {
            "测试样例不存在：whale/$name"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)

    private fun text(element: JsonElement?): String = element!!.jsonPrimitive.content
}
