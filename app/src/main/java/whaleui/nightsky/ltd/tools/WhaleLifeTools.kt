package whaleui.nightsky.ltd.tools

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.w3c.dom.Element
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Hermes 插件 `daily_life` 的 Kotlin 重写：热榜 / 天气 / 每日一诗。
 *
 * URL、UA、字段取法、城市坐标表、WMO 天气码表、错误文案全部对齐
 * 原 Python 插件 `daily_life`，不依赖 RikkaHub 的 Tool 接口，
 * 只做只读公开接口请求，返回值就是给模型看的 JSON 文本。
 */
class WhaleLifeTools(
    private val httpClient: OkHttpClient,
    private val json: Json,
    /** 「常住城市」提供者（设置项）；默认空表示没填。 */
    private val homeCityProvider: () -> String = { "" },
) {

    // ---------------- 热榜 ----------------

    /**
     * 看现在网上的热榜。source 支持 baidu / zhihu / bili_hot / bili_popular / ithome，
     * count 取 1..20（默认 10）。返回 {"source": "<榜单中文名>", "items": [...]}。
     */
    suspend fun hotList(source: String, count: Int = DEFAULT_COUNT): String {
        val n = count.coerceIn(1, MAX_COUNT)
        val name = SOURCE_NAMES[source]
            ?: return errorJson("没有这个榜单，可选：${SOURCE_NAMES.keys.joinToString(", ")}")
        return try {
            val items = when (source) {
                "baidu" -> fetchBaidu(n)
                "zhihu" -> fetchZhihu(n)
                "bili_hot" -> fetchBiliHot(n)
                "bili_popular" -> fetchBiliPopular(n)
                else -> fetchIthome(n)
            }
            buildJsonObject {
                put("source", name)
                put("items", items)
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson("${name}现在打不开，换一个榜单看看")
        }
    }

    /** 百度热搜：data.cards[0].content[0].content，跳过 isTop 置顶项。 */
    private suspend fun fetchBaidu(n: Int): JsonArray {
        val root = json.parseToJsonElement(getText(BAIDU_URL)).jsonObject
        val entries = root.objectAt("data")
            ?.arrayAt("cards")?.firstOrNull().asObjectOrNull()
            ?.arrayAt("content")?.firstOrNull().asObjectOrNull()
            ?.arrayAt("content")
            ?: JsonArray(emptyList())
        return buildJsonArray {
            var added = 0
            for (element in entries) {
                if (added >= n) break
                val item = element as? JsonObject ?: continue
                if ((item["isTop"] as? JsonPrimitive)?.booleanOrNull == true) continue
                add(buildJsonObject { put("title", item.stringOrNull("word") ?: "") })
                added++
            }
        }
    }

    /** 知乎热榜：data[].target.title / detail_text / target.excerpt（截 120 字）。 */
    private suspend fun fetchZhihu(n: Int): JsonArray {
        val root = json.parseToJsonElement(getText(zhihuUrl(n))).jsonObject
        val data = root.arrayAt("data") ?: JsonArray(emptyList())
        return buildJsonArray {
            var added = 0
            for (element in data) {
                if (added >= n) break
                val item = element as? JsonObject ?: continue
                val target = item.objectAt("target")
                add(buildJsonObject {
                    put("title", target?.stringOrNull("title") ?: "")
                    put("heat", item.stringOrNull("detail_text") ?: "")
                    put("summary", (target?.stringOrNull("excerpt") ?: "").take(120))
                })
                added++
            }
        }
    }

    /** B 站热搜词：data.trending.list[].show_name，为空时回落到 keyword。 */
    private suspend fun fetchBiliHot(n: Int): JsonArray {
        val root = json.parseToJsonElement(getText(biliHotUrl(n))).jsonObject
        val list = root.objectAt("data")?.objectAt("trending")?.arrayAt("list")
            ?: JsonArray(emptyList())
        return buildJsonArray {
            var added = 0
            for (element in list) {
                if (added >= n) break
                val item = element as? JsonObject ?: continue
                val showName = item.stringOrNull("show_name")
                add(buildJsonObject {
                    put(
                        "title",
                        if (showName.isNullOrEmpty()) item.stringOrNull("keyword") ?: "" else showName
                    )
                })
                added++
            }
        }
    }

    /** B 站热门视频：title / owner.name / tname / desc（截 100 字）/ bvid 拼视频地址。 */
    private suspend fun fetchBiliPopular(n: Int): JsonArray {
        val root = json.parseToJsonElement(getText(biliPopularUrl(n))).jsonObject
        val list = root.objectAt("data")?.arrayAt("list") ?: JsonArray(emptyList())
        return buildJsonArray {
            var added = 0
            for (element in list) {
                if (added >= n) break
                val item = element as? JsonObject ?: continue
                add(buildJsonObject {
                    put("title", item.stringOrNull("title") ?: "")
                    put("up", item.objectAt("owner")?.stringOrNull("name") ?: "")
                    put("area", item.stringOrNull("tname") ?: "")
                    put("summary", (item.stringOrNull("desc") ?: "").take(100))
                    put("url", "https://www.bilibili.com/video/${item.stringOrNull("bvid") ?: ""}")
                })
                added++
            }
        }
    }

    /** IT 之家 RSS：正则去掉 description 里的 HTML 标签，摘要截 120 字。 */
    private suspend fun fetchIthome(n: Int): JsonArray {
        val xml = getText(ITHOME_RSS_URL)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // Android/JDK 的 JAXP 实现不一定支持这个特性，支持就顺手关掉 DOCTYPE（RSS 里用不到）。
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        val items = document.getElementsByTagName("item")
        return buildJsonArray {
            var added = 0
            var index = 0
            while (index < items.length && added < n) {
                val item = items.item(index) as? Element
                index++
                if (item == null) continue
                val description = HTML_TAG_REGEX.replace(item.childText("description"), "").trim()
                add(buildJsonObject {
                    put("title", item.childText("title"))
                    put("summary", description.take(120))
                    put("url", item.childText("link"))
                })
                added++
            }
        }
    }

    // ---------------- 天气 ----------------

    /**
     * 查某个地方现在的天气和今明两天预报（含日落时间）。
     * 先在 Hermes 的内置城市表里找，找不到再走 open-meteo geocoding（先 zh 后 en，取 population 最大的）。
     */
    suspend fun weather(place: String = ""): String {
        // 任务书 round4 4.3：place 不填就用「常住城市」；都没有就问清楚，绝不默认任何城市。
        val target = place.trim().ifEmpty { homeCityProvider().trim() }
        if (target.isEmpty()) {
            return errorJson("不知道对方在哪个城市，先问问对方")
        }
        return try {
            val (latitude, longitude, name) = if (target in CITIES) {
                val (lat, lon) = CITIES.getValue(target)
                Triple(lat.toString(), lon.toString(), target)
            } else {
                geocode(target) ?: return errorJson("找不到「$target」在哪，换个说法试试，比如城市名")
            }
            val data = json.parseToJsonElement(getText(forecastUrl(latitude, longitude))).jsonObject
            val current = data.objectAt("current") ?: throw NoSuchElementException("current")
            val daily = data.objectAt("daily") ?: throw NoSuchElementException("daily")
            val weatherCodes = daily.arrayAt("weather_code") ?: throw NoSuchElementException("daily.weather_code")
            val lows = daily.arrayAt("temperature_2m_min") ?: throw NoSuchElementException("daily.temperature_2m_min")
            val highs = daily.arrayAt("temperature_2m_max") ?: throw NoSuchElementException("daily.temperature_2m_max")
            val rainChances = daily.arrayAt("precipitation_probability_max")
                ?: throw NoSuchElementException("daily.precipitation_probability_max")
            val sunsets = daily.arrayAt("sunset") ?: throw NoSuchElementException("daily.sunset")
            val forecast = buildJsonArray {
                for (i in DAY_LABELS.indices) {
                    add(buildJsonObject {
                        put("day", DAY_LABELS[i])
                        put("weather", weatherName(weatherCodes.requiredAt(i)))
                        put("low", lows.requiredAt(i))
                        put("high", highs.requiredAt(i))
                        put("rain_chance", rainChances.requiredAt(i))
                        put("sunset", sunsets.requiredAt(i).stringContentOrEmpty().takeLast(5))
                    })
                }
            }
            buildJsonObject {
                put("place", name)
                put("local_time", current.requiredString("time").replace("T", " "))
                put("now", buildJsonObject {
                    put("weather", weatherName(current.requiredElement("weather_code")))
                    put("temp", current.requiredElement("temperature_2m"))
                    put("feels_like", current.requiredElement("apparent_temperature"))
                    put("humidity", current.requiredElement("relative_humidity_2m"))
                    put("wind_kmh", current.requiredElement("wind_speed_10m"))
                })
                put("forecast", forecast)
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson("天气没查到，等会儿再试")
        }
    }

    /** open-meteo geocoding：先 zh 后 en，取 population 最大的那条结果。 */
    private suspend fun geocode(place: String): Triple<String, String, String>? {
        val quoted = URLEncoder.encode(place, "UTF-8")
        for (language in GEOCODE_LANGUAGES) {
            val url = "https://geocoding-api.open-meteo.com/v1/search" +
                "?name=$quoted&count=10&language=$language"
            val root = json.parseToJsonElement(getText(url)).jsonObject
            val results = root.arrayAt("results") ?: JsonArray(emptyList())
            val best = results.mapNotNull { it as? JsonObject }
                .maxByOrNull { it.population() }
                ?: continue
            val latitude = best.numberOrNull("latitude") ?: continue
            val longitude = best.numberOrNull("longitude") ?: continue
            return Triple(latitude.toString(), longitude.toString(), best.stringOrNull("name") ?: place)
        }
        return null
    }

    private fun JsonObject.population(): Double =
        (this["population"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0

    private fun forecastUrl(latitude: String, longitude: String): String =
        "https://api.open-meteo.com/v1/forecast?" +
            "latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
            "&timezone=auto&forecast_days=2"

    private fun weatherName(code: JsonElement?): String {
        val value = (code as? JsonPrimitive)?.intOrNull ?: return UNKNOWN_WEATHER
        return WMO[value] ?: UNKNOWN_WEATHER
    }

    // ---------------- 每日一诗 ----------------

    /** 随机抽一句古诗词，字段照抄 Hermes：line=content / title=origin / author=author。 */
    suspend fun dailyPoem(): String {
        return try {
            val data = json.parseToJsonElement(getText(POEM_URL)).jsonObject
            buildJsonObject {
                put("line", data.stringOrNull("content") ?: "")
                put("title", data.stringOrNull("origin") ?: "")
                put("author", data.stringOrNull("author") ?: "")
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson("今天的诗没取到，等会儿再试")
        }
    }

    // ---------------- HTTP / JSON / XML 小工具 ----------------

    /** 只读 GET，带 Hermes 的 UA 和 Accept；非 2xx 抛 [HttpStatusException]。 */
    private suspend fun getText(url: String): String =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/xml, */*")
                .get()
                .build()
            val call = httpClient.newCall(request)
            call.timeout().timeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // 在 OkHttp 自己的线程上读完 body，别把阻塞读带回调用方的 dispatcher。
                    response.use { result ->
                        val body = try {
                            result.body.string()
                        } catch (e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                            return
                        }
                        if (!continuation.isActive) return
                        if (result.isSuccessful) {
                            continuation.resume(body)
                        } else {
                            continuation.resumeWithException(HttpStatusException(result.code))
                        }
                    }
                }
            })
        }

    private fun errorJson(message: String): String = buildJsonObject {
        put("error", message)
    }.toString()

    private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.objectAt(key: String): JsonObject? = this[key].asObjectOrNull()

    private fun JsonObject.arrayAt(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.requiredString(key: String): String =
        stringOrNull(key) ?: throw NoSuchElementException("missing field: $key")

    private fun JsonObject.requiredElement(key: String): JsonElement =
        this[key]?.takeIf { it !is JsonNull } ?: throw NoSuchElementException("missing field: $key")

    private fun JsonObject.numberOrNull(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()

    private fun JsonArray.requiredAt(index: Int): JsonElement =
        if (index in 0 until size) this[index] else throw IndexOutOfBoundsException("index $index")

    private fun JsonElement.stringContentOrEmpty(): String =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""

    private fun zhihuUrl(n: Int) = "https://api.zhihu.com/topstory/hot-lists/total?limit=$n"

    private fun biliHotUrl(n: Int) = "https://api.bilibili.com/x/web-interface/search/square?limit=$n"

    private fun biliPopularUrl(n: Int) = "https://api.bilibili.com/x/web-interface/popular?ps=$n&pn=1"

    /** XML 里取直接子元素的文本，等价于 Hermes 的 `it.findtext(tag)`。 */
    private fun Element.childText(tag: String): String {
        val children = childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == tag) return node.textContent ?: ""
        }
        return ""
    }

    companion object {
        /** Hermes `daily_life` 的 UA，逐字照抄。 */
        const val UA: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

        private const val HTTP_TIMEOUT_SECONDS = 12L
        private const val DEFAULT_COUNT = 10
        private const val MAX_COUNT = 20
        private const val UNKNOWN_WEATHER = "未知"

        private const val BAIDU_URL = "https://top.baidu.com/api/board?platform=wise&tab=realtime"
        private const val ITHOME_RSS_URL = "https://www.ithome.com/rss/"
        private const val POEM_URL = "https://v1.jinrishici.com/all.json"

        private val DAY_LABELS = listOf("今天", "明天")
        private val GEOCODE_LANGUAGES = listOf("zh", "en")

        private val HTML_TAG_REGEX = Regex("<[^>]+>")

        /** source 到榜单中文名，顺序就是报错文案里的可选列表顺序。 */
        private val SOURCE_NAMES = linkedMapOf(
            "baidu" to "百度热搜",
            "zhihu" to "知乎热榜",
            "bili_hot" to "B站热搜词",
            "bili_popular" to "B站热门视频",
            "ithome" to "IT之家新闻",
        )

        /**
         * 常见大城市的坐标加速表（任务书 round4 4.3）：
         * 只保留通用的大城市，**不再放任何个人相关的具体地点**；其它地名走 open-meteo geocoding。
         */
        private val CITIES = mapOf(
            "东京" to (35.676 to 139.650),
            "北京" to (39.904 to 116.407),
            "上海" to (31.230 to 121.474),
            "香港" to (22.319 to 114.169),
            "首尔" to (37.566 to 126.978),
            "新加坡" to (1.352 to 103.820),
            "澳门" to (22.199 to 113.544),
            "奥克兰" to (-36.848 to 174.763),
            "惠灵顿" to (-41.286 to 174.776),
            "基督城" to (-43.532 to 172.636),
            "悉尼" to (-33.869 to 151.209),
            "墨尔本" to (-37.814 to 144.963),
            "布里斯班" to (-27.470 to 153.026),
            "休斯顿" to (29.760 to -95.370),
        )

        /** WMO 天气码表，逐字照抄。 */
        private val WMO = mapOf(
            0 to "晴",
            1 to "大致晴",
            2 to "多云",
            3 to "阴",
            45 to "雾",
            48 to "雾凇",
            51 to "毛毛雨",
            53 to "毛毛雨",
            55 to "毛毛雨",
            61 to "小雨",
            63 to "中雨",
            65 to "大雨",
            66 to "冻雨",
            67 to "冻雨",
            71 to "小雪",
            73 to "中雪",
            75 to "大雪",
            77 to "雪粒",
            80 to "阵雨",
            81 to "阵雨",
            82 to "强阵雨",
            85 to "阵雪",
            86 to "阵雪",
            95 to "雷雨",
            96 to "雷雨夹冰雹",
            99 to "雷雨夹冰雹",
        )
    }
}

/**
 * 公开接口返回非 2xx 时抛出，对应 Hermes 里 `urllib.error.HTTPError` 的位置。
 */
internal class HttpStatusException(val statusCode: Int) : IOException("HTTP $statusCode")
