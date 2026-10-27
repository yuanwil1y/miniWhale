package whaleui.nightsky.ltd.tools.deepseek

import kotlin.uuid.Uuid

// S9b：从 :search 的 SearchService.kt 搬来的最小支撑类型，只保留 DeepSeek 搜索用得到的字段与默认值。

/** 原 `me.rerere.search.SearchCommonOptions`。 */
data class SearchCommonOptions(
    val resultSize: Int = 10,
)

/**
 * 原 `me.rerere.search.SearchServiceOptions.DeepSeekOptions`；[id] 只用作 Key 轮询的分组。
 * [baseUrl]：高级设置里 DeepSeek 的 Base URL，搜索 / 读网页走它的主机（N-007，见 [DeepSeekSearchService.toolsUrl]）。
 */
data class DeepSeekOptions(
    val id: Uuid = Uuid.random(),
    val apiKey: String = "",
    val baseUrl: String? = null,
)

/** 原 `me.rerere.search.SearchResult`。 */
data class SearchResult(
    val answer: String? = null,
    val items: List<SearchResultItem>,
    val images: List<String> = emptyList(),
    /** Local time at which this result was retrieved; not a publication date. */
    val retrievedAt: String? = null,
) {
    data class SearchResultItem(
        val title: String,
        val url: String,
        val text: String,
        /** Provider-supplied publication date. Null means that no date was supplied. */
        val publishedDate: String? = null,
        val highlights: List<String> = emptyList(),
    )
}

/** 原 `me.rerere.search.ScrapedResult`。 */
data class ScrapedResult(
    val urls: List<ScrapedResultUrl>,
    /** Local time at which this result was retrieved; not a publication date. */
    val retrievedAt: String? = null,
)

data class ScrapedResultUrl(
    val url: String,
    val content: String,
    val metadata: ScrapedResultMetadata? = null,
)

data class ScrapedResultMetadata(
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
    /** Provider-supplied publication date. Null means that no date was supplied. */
    val publishedDate: String? = null,
)
