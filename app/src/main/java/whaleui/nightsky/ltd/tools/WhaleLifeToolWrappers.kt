package whaleui.nightsky.ltd.tools

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

/**
 * 把 [WhaleLifeTools] 包成 RikkaHub 原生 [Tool]（`hot_list` / `weather` / `daily_poem`）。
 *
 * 工具名、描述、参数 schema 逐字照抄 Hermes `plugins/daily_life/__init__.py` 的 `register`；
 * execute 直接转发给 [WhaleLifeTools]，结果（Hermes 风格的 JSON 文本）包成一条 [UIMessagePart.Text]。
 */
class WhaleLifeToolWrappers(private val lifeTools: WhaleLifeTools) {

    fun create(): List<Tool> = listOf(hotListTool(), weatherTool(), dailyPoemTool())

    private fun hotListTool() = Tool(
        name = "hot_list",
        description = "看现在网上的热榜。source 可选 baidu（百度热搜）、zhihu（知乎热榜）、" +
            "bili_hot（B站热搜词）、bili_popular（B站热门视频）、ithome（IT之家科技新闻）。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
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
                },
                required = listOf("source"),
            )
        },
        execute = { args ->
            val obj = args as? JsonObject
            // Hermes 的 handler 缺省 source=baidu、count=10，schema 之外的兜底也照抄。
            val source = obj.stringOr("source") ?: "baidu"
            val count = obj.intOr("count") ?: 10
            listOf(UIMessagePart.Text(lifeTools.hotList(source, count)))
        },
    )

    private fun weatherTool() = Tool(
        name = "weather",
        description = "查某个地方现在的天气和今明两天预报（含日落时间）。place 填城市名，比如 北京、东京；" +
            "不填就查对方的常住城市（没填常住城市时会提示先问对方）。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "place",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "城市名，比如 北京、东京；不填就是对方的常住城市")
                        },
                    )
                },
                required = null,
            )
        },
        execute = { args ->
            // Hermes 原来不填地点时用一个写死的默认城市；round4 起不填就用「常住城市」，都没有就问用户
            val place = (args as? JsonObject).stringOr("place")?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: ""
            listOf(UIMessagePart.Text(lifeTools.weather(place)))
        },
    )

    private fun dailyPoemTool() = Tool(
        name = "daily_poem",
        description = "随机抽一句古诗词。",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {}, required = null)
        },
        execute = {
            listOf(UIMessagePart.Text(lifeTools.dailyPoem()))
        },
    )

    private fun JsonObject?.stringOr(key: String): String? {
        val primitive = this?.get(key) as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.contentOrNull
    }

    private fun JsonObject?.intOr(key: String): Int? =
        (this?.get(key) as? JsonPrimitive)?.intOrNull
}
