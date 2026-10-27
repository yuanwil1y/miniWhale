package whaleui.nightsky.ltd.tools

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * WhaleLifeTools 的 JVM 单测：请求全部打到本地 MockWebServer，响应全部来自
 * `app/src/test/resources/whale/` 的样例文件，不发任何真实网络请求、不用任何 API Key。
 */
class WhaleLifeToolsTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    /** 改写前（也就是真实接口地址）的请求 URL，用来核对是否和 Hermes 逐字一致。 */
    private val requestedUrls = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        requestedUrls.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---------------- 热榜 ----------------

    @Test
    fun baiduHotParsesTitlesAndSkipsTopItem() = runTest {
        server.enqueue(jsonResponse(sample("baidu-hot.json")))
        val root = parse(tools().hotList("baidu", 10))
        assertEquals("百度热搜", text(root["source"]))
        val items = items(root)
        assertEquals(listOf("示例标题一", "示例标题二", "示例标题三"), items.map { text(it["title"]) })

        val request = server.takeRequest()
        assertEquals(UA, request.getHeader("User-Agent"))
        assertEquals("application/json, text/xml, */*", request.getHeader("Accept"))
        assertEquals("/api/board", request.requestUrl!!.encodedPath)
        assertEquals("wise", request.requestUrl!!.queryParameter("platform"))
        assertEquals("realtime", request.requestUrl!!.queryParameter("tab"))
    }

    @Test
    fun zhihuHotParsesFieldsAndTruncatesSummary() = runTest {
        server.enqueue(jsonResponse(sample("zhihu-hot.json")))
        val items = items(parse(tools().hotList("zhihu", 10)))
        assertEquals(2, items.size)
        assertEquals("示例标题一", text(items[0]["title"]))
        assertEquals("热度 123 万", text(items[0]["heat"]))
        assertEquals("示例摘要一", text(items[0]["summary"]))
        assertEquals("示例标题二", text(items[1]["title"]))
        assertEquals("热度 98 万", text(items[1]["heat"]))
        // Hermes 里摘要截 120 字：样例第二段是 130 字的占位文本。
        assertEquals("示例摘要二".repeat(24), text(items[1]["summary"]))
        assertEquals("10", server.takeRequest().requestUrl!!.queryParameter("limit"))
    }

    @Test
    fun biliHotFallsBackToKeywordWhenShowNameIsEmpty() = runTest {
        server.enqueue(jsonResponse(sample("bili-hot.json")))
        val items = items(parse(tools().hotList("bili_hot", 10)))
        assertEquals(
            listOf("示例热搜词一", "示例关键词二", "示例关键词三"),
            items.map { text(it["title"]) }
        )
        val request = server.takeRequest()
        assertEquals("/x/web-interface/search/square", request.requestUrl!!.encodedPath)
        assertEquals("10", request.requestUrl!!.queryParameter("limit"))
    }

    @Test
    fun biliPopularParsesFieldsAndTruncatesSummary() = runTest {
        server.enqueue(jsonResponse(sample("bili-popular.json")))
        val items = items(parse(tools().hotList("bili_popular", 10)))
        assertEquals(2, items.size)
        val first = items[0]
        assertEquals("示例视频标题一", text(first["title"]))
        assertEquals("示例UP主一", text(first["up"]))
        assertEquals("示例分区一", text(first["area"]))
        assertEquals("示例视频简介一", text(first["summary"]))
        assertEquals("https://www.bilibili.com/video/BV1xx411c7XX", text(first["url"]))
        // Hermes 里简介截 100 字：样例第二段是 140 字的占位文本。
        assertEquals("示例视频简介二".repeat(14) + "示例", text(items[1]["summary"]))
        assertEquals("https://www.bilibili.com/video/BV1yy411c7YY", text(items[1]["url"]))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/x/web-interface/popular", url.encodedPath)
        assertEquals("10", url.queryParameter("ps"))
        assertEquals("1", url.queryParameter("pn"))
    }

    @Test
    fun ithomeParsesRssAndStripsHtmlTags() = runTest {
        server.enqueue(jsonResponse(sample("ithome.xml")))
        val items = items(parse(tools().hotList("ithome", 20)))
        assertEquals(3, items.size)
        assertEquals("示例新闻标题一", text(items[0]["title"]))
        assertEquals("示例新闻摘要一", text(items[0]["summary"]))
        assertEquals("https://www.ithome.com/0/000/001.htm", text(items[0]["url"]))
        assertEquals("示例新闻摘要二 与示例补充说明", text(items[1]["summary"]))
        // description 里的 HTML 标签被去掉后再截 120 字：样例第三段是 140 字。
        assertEquals("示例新闻摘要三".repeat(17) + "示", text(items[2]["summary"]))
        assertEquals("/rss/", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun countIsClampedToFirstAndLastAndDefaultsToTen() = runTest {
        val tools = tools()

        server.enqueue(jsonResponse(sample("zhihu-hot.json")))
        assertEquals(1, items(parse(tools.hotList("zhihu", 1))).size)
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("limit"))

        server.enqueue(jsonResponse(sample("zhihu-hot.json")))
        assertEquals(2, items(parse(tools.hotList("zhihu"))).size)
        assertEquals("10", server.takeRequest().requestUrl!!.queryParameter("limit"))

        server.enqueue(jsonResponse(sample("zhihu-hot.json")))
        assertEquals(2, items(parse(tools.hotList("zhihu", 20))).size)
        assertEquals("20", server.takeRequest().requestUrl!!.queryParameter("limit"))

        server.enqueue(jsonResponse(sample("zhihu-hot.json")))
        assertEquals(1, items(parse(tools.hotList("zhihu", 0))).size)
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("limit"))

        server.enqueue(jsonResponse(sample("zhihu-hot.json")))
        assertEquals(2, items(parse(tools.hotList("zhihu", 21))).size)
        assertEquals("20", server.takeRequest().requestUrl!!.queryParameter("limit"))
    }

    @Test
    fun unknownSourceReturnsExactErrorWithoutNetwork() = runTest {
        val message = errorMessage(tools().hotList("weibo", 10))
        assertEquals("没有这个榜单，可选：baidu, zhihu, bili_hot, bili_popular, ithome", message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun hotListHttpErrorUsesExactMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertEquals(
            "百度热搜现在打不开，换一个榜单看看",
            errorMessage(tools().hotList("baidu", 10))
        )
    }

    // ---------------- 天气 ----------------

    @Test
    fun weatherWithoutPlaceUsesHomeCity() = runTest {
        server.enqueue(jsonResponse(sample("open-meteo-forecast.json")))
        val root = parse(tools(homeCity = "北京").weather())
        assertEquals(1, server.requestCount)
        val url = server.takeRequest().requestUrl!!
        assertEquals("39.904", url.queryParameter("latitude"))
        assertEquals("116.407", url.queryParameter("longitude"))
        assertEquals("北京", text(root["place"]))
    }

    @Test
    fun weatherWithoutPlaceAndWithoutHomeCityAsksForCity() = runTest {
        val message = errorMessage(tools().weather())
        assertEquals("不知道对方在哪个城市，先问问对方", message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun weatherFromCityTableParsesAllFieldsWithoutGeocoding() = runTest {
        server.enqueue(jsonResponse(sample("open-meteo-forecast.json")))
        val root = parse(tools().weather("北京"))
        assertEquals(1, server.requestCount)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/v1/forecast", url.encodedPath)
        assertEquals("39.904", url.queryParameter("latitude"))
        assertEquals("116.407", url.queryParameter("longitude"))
        assertEquals(
            "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m",
            url.queryParameter("current")
        )
        assertEquals(
            "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset",
            url.queryParameter("daily")
        )
        assertEquals("auto", url.queryParameter("timezone"))
        assertEquals("2", url.queryParameter("forecast_days"))

        assertEquals("北京", text(root["place"]))
        assertEquals("2026-10-26 05:40", text(root["local_time"]))
        val now = root["now"]!!.jsonObject
        assertEquals("阴", text(now["weather"]))
        assertEquals(12.3, now["temp"]!!.jsonPrimitive.double, 0.0001)
        assertEquals(11.0, now["feels_like"]!!.jsonPrimitive.double, 0.0001)
        assertEquals(66, now["humidity"]!!.jsonPrimitive.int)
        assertEquals(8.4, now["wind_kmh"]!!.jsonPrimitive.double, 0.0001)

        val forecast = root["forecast"]!!.jsonArray
        assertEquals(2, forecast.size)
        val today = forecast[0].jsonObject
        assertEquals("今天", text(today["day"]))
        assertEquals("阴", text(today["weather"]))
        assertEquals(8.0, today["low"]!!.jsonPrimitive.double, 0.0001)
        assertEquals(15.2, today["high"]!!.jsonPrimitive.double, 0.0001)
        assertEquals(10, today["rain_chance"]!!.jsonPrimitive.int)
        assertEquals("17:42", text(today["sunset"]))
        val tomorrow = forecast[1].jsonObject
        assertEquals("明天", text(tomorrow["day"]))
        assertEquals("小雨", text(tomorrow["weather"]))
        assertEquals(7.5, tomorrow["low"]!!.jsonPrimitive.double, 0.0001)
        assertEquals(14.1, tomorrow["high"]!!.jsonPrimitive.double, 0.0001)
        assertEquals(60, tomorrow["rain_chance"]!!.jsonPrimitive.int)
        assertEquals("17:41", text(tomorrow["sunset"]))
    }

    @Test
    fun weatherGeocodingUsesChineseResultFirstAndPicksLargestPopulation() = runTest {
        server.enqueue(jsonResponse(sample("open-meteo-geocode.json")))
        server.enqueue(jsonResponse(sample("open-meteo-forecast.json")))
        val root = parse(tools().weather("示例城市"))

        assertEquals("示例大城", text(root["place"]))
        assertEquals(2, server.requestCount)

        val search = server.takeRequest().requestUrl!!
        assertEquals("/v1/search", search.encodedPath)
        assertEquals("示例城市", search.queryParameter("name"))
        assertEquals("zh", search.queryParameter("language"))
        assertEquals("10", search.queryParameter("count"))

        val forecast = server.takeRequest().requestUrl!!
        assertEquals("/v1/forecast", forecast.encodedPath)
        assertEquals("22.54554", forecast.queryParameter("latitude"))
        assertEquals("114.0683", forecast.queryParameter("longitude"))
    }

    @Test
    fun weatherGeocodingFallsBackToEnglishWhenChineseHasNoResult() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                return when {
                    url.encodedPath == "/v1/search" && url.queryParameter("language") == "zh" ->
                        jsonResponse("""{"results":[]}""")
                    url.encodedPath == "/v1/search" ->
                        jsonResponse(sample("open-meteo-geocode.json"))
                    url.encodedPath == "/v1/forecast" ->
                        jsonResponse(sample("open-meteo-forecast.json"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val root = parse(tools().weather("示例城市"))
        assertEquals("示例大城", text(root["place"]))

        assertEquals(
            "zh",
            server.takeRequest().requestUrl!!.queryParameter("language")
        )
        assertEquals(
            "en",
            server.takeRequest().requestUrl!!.queryParameter("language")
        )
        assertEquals(
            "/v1/forecast",
            server.takeRequest().requestUrl!!.encodedPath
        )
        assertEquals(3, server.requestCount)
    }

    @Test
    fun weatherUnknownPlaceReturnsExactNotFoundMessage() = runTest {
        server.enqueue(jsonResponse("""{"results":[]}"""))
        server.enqueue(jsonResponse("""{"results":[]}"""))
        assertEquals(
            "找不到「示例城市」在哪，换个说法试试，比如城市名",
            errorMessage(tools().weather("示例城市"))
        )
        assertEquals(2, server.requestCount)
    }

    @Test
    fun weatherHttpErrorUsesExactMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertEquals(
            "天气没查到，等会儿再试",
            errorMessage(tools().weather("示例城市"))
        )
    }

    @Test
    fun weatherTimeoutUsesExactMessage() = runTest {
        server.enqueue(
            jsonResponse(sample("open-meteo-forecast.json")).setBodyDelay(2, TimeUnit.SECONDS)
        )
        assertEquals(
            "天气没查到，等会儿再试",
            errorMessage(tools(readTimeoutMs = 300).weather("北京"))
        )
    }

    @Test
    fun unknownWmoCodeFallsBackToUnknown() = runTest {
        val body = """
            {
              "current": {
                "time": "2026-10-26T05:40",
                "temperature_2m": 1.0,
                "apparent_temperature": 1.0,
                "weather_code": 999,
                "wind_speed_10m": 0.0,
                "relative_humidity_2m": 50
              },
              "daily": {
                "weather_code": [123, 61],
                "temperature_2m_max": [1.0, 2.0],
                "temperature_2m_min": [0.0, 1.0],
                "precipitation_probability_max": [0, 0],
                "sunrise": ["2026-10-26T06:12", "2026-10-27T06:13"],
                "sunset": ["2026-10-26T17:42", "2026-10-27T17:41"]
              }
            }
        """.trimIndent()
        server.enqueue(jsonResponse(sample("open-meteo-geocode.json")))
        server.enqueue(jsonResponse(body))
        val root = parse(tools().weather("示例城市"))
        assertEquals("未知", text(root["now"]!!.jsonObject["weather"]))
        val forecast = root["forecast"]!!.jsonArray
        assertEquals("未知", text(forecast[0].jsonObject["weather"]))
        assertEquals("小雨", text(forecast[1].jsonObject["weather"]))
    }

    // ---------------- 每日一诗 ----------------

    @Test
    fun dailyPoemParsesFieldsAndSendsHermesHeaders() = runTest {
        server.enqueue(jsonResponse(sample("jinrishici.json")))
        val root = parse(tools().dailyPoem())
        assertEquals("示例诗句一，示例诗句下半句。", text(root["line"]))
        assertEquals("示例出处一", text(root["title"]))
        assertEquals("示例作者一", text(root["author"]))

        val request = server.takeRequest()
        assertEquals("/all.json", request.requestUrl!!.encodedPath)
        assertEquals("https://v1.jinrishici.com/all.json", requestedUrls.single())
        assertEquals(UA, request.getHeader("User-Agent"))
        assertEquals("application/json, text/xml, */*", request.getHeader("Accept"))
    }

    @Test
    fun requestsHitTheExactHermesUrls() = runTest {
        server.enqueue(jsonResponse(sample("baidu-hot.json")))
        server.enqueue(jsonResponse(sample("zhihu-hot.json")))
        server.enqueue(jsonResponse(sample("bili-hot.json")))
        server.enqueue(jsonResponse(sample("bili-popular.json")))
        server.enqueue(jsonResponse(sample("ithome.xml")))
        server.enqueue(jsonResponse(sample("open-meteo-forecast.json")))
        server.enqueue(jsonResponse(sample("jinrishici.json")))
        val tools = tools()
        tools.hotList("baidu", 10)
        tools.hotList("zhihu", 10)
        tools.hotList("bili_hot", 10)
        tools.hotList("bili_popular", 10)
        tools.hotList("ithome", 10)
        tools.weather("北京")
        tools.dailyPoem()
        assertEquals(
            listOf(
                "https://top.baidu.com/api/board?platform=wise&tab=realtime",
                "https://api.zhihu.com/topstory/hot-lists/total?limit=10",
                "https://api.bilibili.com/x/web-interface/search/square?limit=10",
                "https://api.bilibili.com/x/web-interface/popular?ps=10&pn=1",
                "https://www.ithome.com/rss/",
                "https://api.open-meteo.com/v1/forecast?latitude=39.904&longitude=116.407" +
                    "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
                    "&timezone=auto&forecast_days=2",
                "https://v1.jinrishici.com/all.json",
            ),
            requestedUrls.toList()
        )
    }

    @Test
    fun dailyPoemHttpErrorUsesExactMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertEquals("今天的诗没取到，等会儿再试", errorMessage(tools().dailyPoem()))
    }

    @Test
    fun dailyPoemTimeoutUsesExactMessage() = runTest {
        server.enqueue(jsonResponse(sample("jinrishici.json")).setBodyDelay(2, TimeUnit.SECONDS))
        assertEquals(
            "今天的诗没取到，等会儿再试",
            errorMessage(tools(readTimeoutMs = 300).dailyPoem())
        )
    }

    // ---------------- 测试小工具 ----------------

    private fun tools(readTimeoutMs: Long = 5_000L, homeCity: String = ""): WhaleLifeTools =
        WhaleLifeTools(newClient(readTimeoutMs), json, homeCityProvider = { homeCity })

    /** 把所有请求的 host 改写到本地 MockWebServer，端口/路径/query 原样保留。 */
    private fun newClient(readTimeoutMs: Long): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                requestedUrls += original.url.toString()
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
            WhaleLifeToolsTest::class.java.classLoader?.getResourceAsStream("whale/$name")
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

    private fun parse(jsonText: String): JsonObject = json.parseToJsonElement(jsonText).jsonObject

    private fun items(root: JsonObject): List<JsonObject> =
        root["items"]!!.jsonArray.map { it.jsonObject }

    private fun text(element: JsonElement?): String = element!!.jsonPrimitive.content

    private fun errorMessage(jsonText: String): String = text(parse(jsonText)["error"])

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    }
}
