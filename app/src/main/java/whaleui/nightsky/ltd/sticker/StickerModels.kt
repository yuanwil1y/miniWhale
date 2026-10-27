package whaleui.nightsky.ltd.sticker

/**
 * 表情包格式 v2（说明见 `docs/IMPORT_FORMAT.md`）的固定常量与数据模型。
 *
 * 这一层完全不依赖 Android：解析、校验、查找、排序的纯逻辑都在这里，
 * JVM 单测可以直接跑；真正的文件复制、缩略图生成在 `StickerImporter` 里（需要 Context/Bitmap）。
 */

/** 单张表情文件大小上限：2 MB（任务书 3.1）。 */
const val STICKER_MAX_BYTES: Long = 2L * 1024 * 1024

/** `series.json` / `index.json` 文本上限：1 MB（超过的当作读不出来，不整读进内存）。 */
const val STICKER_TEXT_MAX_BYTES: Int = 1024 * 1024

/** 表情编号白名单（编号会拼进缩略图文件名，不能带 `/`、`..` 之类）。真实编号形如 `01-1000001`。 */
val STICKER_ID_PATTERN: Regex = Regex("^[0-9A-Za-z_-]+$")

/** 缩略图长边（首帧，PNG，垫透明背景）。 */
const val STICKER_THUMB_MAX_EDGE: Int = 256

/** `format` 字段固定值（只收 WebP）。 */
const val STICKER_FORMAT_WEBP: String = "webp"

/** 私有目录下的表情根目录（`filesDir/stickers`）。 */
const val STICKER_DIR_NAME: String = "stickers"

/** 缩略图子目录（`filesDir/stickers/_thumbs`）。 */
const val STICKER_THUMB_DIR_NAME: String = "_thumbs"

/** 表情库根目录的清单文件名（可选）。 */
const val STICKER_INDEX_FILE: String = "index.json"

/** 一个系列一个的说明文件名（必需）。 */
const val STICKER_SERIES_FILE: String = "series.json"

/** 系列说明文件的 format 字段固定值。 */
const val STICKER_SERIES_FORMAT: String = "whale-sticker-series"

/** 系列说明文件的 version 字段固定值。 */
const val STICKER_SERIES_VERSION: Int = 2

/**
 * 情绪词表（42 个，2026-10-26 重建版）。
 *
 * 顺序按类别分组，类别内的顺序就是下面列出的顺序。
 * `series.json` 里的 `emotions` 只能取这里的词；不认识的词丢弃并记警告，不整条拒绝。
 */
object StickerEmotions {
    val ALL: List<String> = listOf(
        // 正向（6）
        "开心", "大笑", "兴奋", "惊喜", "得意", "感动",
        // 亲密 / 关系（7）
        "爱你", "亲亲", "抱抱", "撒娇", "害羞", "想你", "担心",
        // 低落 / 负面（12）
        "委屈", "难过", "哭", "生气", "假凶", "嫌弃", "无语", "无奈", "尴尬", "紧张", "害怕", "震惊",
        // 状态（9）
        "困", "心累", "馋", "饿", "摸鱼", "装傻", "发呆", "好奇", "偷看",
        // 意图 / 场景（8）
        "谢谢", "道歉", "拜托", "好的", "加油", "早安", "晚安", "庆祝",
    )

    private val SET: Set<String> = ALL.toSet()

    fun isKnown(word: String): Boolean = word.trim() in SET

    /** 词表顺序的序号，用来给情绪分类排序（词表里没有的词排最后）。 */
    fun orderOf(word: String): Int = ALL.indexOf(word).takeIf { it >= 0 } ?: Int.MAX_VALUE
}

/** 一个表情系列（`sticker_pack` 表）。 */
data class StickerPack(
    /** 系列号，如 `01`（对外编号的前半段）。 */
    val id: String,
    /** 面板上的标签名，≤ 8 字。 */
    val name: String,
    /** 文件夹名里的原标题，原样保留。 */
    val titleOriginal: String,
    val characters: List<String>,
    val animated: Boolean,
    /** 原本是有声贴图（声音在 webp 里已经没有了）——管理页给个提示。 */
    val hasSoundOriginally: Boolean,
    val source: String,
    val licenseNote: String,
    val sortOrder: Int,
    /** 她能不能用。关掉后 find_sticker/send_sticker 都排除这个系列。 */
    val enabledForHer: Boolean = true,
    val importedAt: Long = 0L,
) {
    fun charactersText(): String = characters.joinToString(CHARACTER_SEPARATOR)
}

/** 一张表情（`sticker` 表）。对外编号 `id` 形如 `01-1000001`。 */
data class Sticker(
    val id: String,
    /** 所属系列号（`sticker_pack.id`）。 */
    val packId: String,
    /** 相对 `filesDir` 的路径，如 `stickers/01/1000001.webp`。 */
    val filePath: String,
    /** 相对 `filesDir` 的缩略图路径，如 `stickers/_thumbs/01-1000001.png`。 */
    val thumbPath: String,
    val format: String = STICKER_FORMAT_WEBP,
    val animated: Boolean = true,
    /** ≤ 12 字，界面说明、请求里写成「(你发了一个表情:<description>)」。 */
    val description: String,
    /** 30–90 字，她挑表情时主要看这一条。 */
    val detail: String,
    val textInImage: String = "",
    val textMeaning: String = "",
    val characters: List<String> = emptyList(),
    /** 两个角色有亲密互动（抱、亲、牵手、送心）。 */
    val couple: Boolean = false,
    val emotions: List<String> = emptyList(),
    val useWhen: String = "",
    val needsReview: Boolean = false,
    val lastSentByHerAt: Long? = null,
    val sentByHerCount: Int = 0,
) {
    fun charactersText(): String = characters.joinToString(CHARACTER_SEPARATOR)
}

/** 查找结果要带上系列名，方便拼一行说明。 */
data class StickerWithPack(
    val sticker: Sticker,
    val pack: StickerPack,
)

internal const val CHARACTER_SEPARATOR: String = ","

/** 逗号分隔文本 ↔ 列表（空串 → 空列表，去掉空白项）。 */
internal fun splitList(text: String): List<String> =
    text.split(CHARACTER_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

internal fun joinList(items: List<String>): String =
    items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(CHARACTER_SEPARATOR)

// ---------------------------------------------------------------- 请求里的文字

/**
 * 她发的表情在请求里的文字（任务书 3.3，逐字固定）。
 *
 * `(你发了一个表情:<description>,编号 <id>)`——ASCII 括号、ASCII 冒号/逗号，别改。
 */
fun herStickerLine(description: String, id: String): String =
    "(你发了一个表情:$description,编号 $id)"

/**
 * 他从面板发的表情在请求里的文字（任务书 3.3，逐字固定）。
 *
 * `(对方发来一个表情:<detail>,编号 <id>)`
 */
fun hisStickerLine(detail: String, id: String): String =
    "(对方发来一个表情:$detail,编号 $id)"

/** 表情已经被删掉（文件/记录都不在）时请求里的占位。 */
const val DELETED_STICKER_LINE: String = "(一个已删除的表情)"

/** 界面 / 会话列表 / 通知里的表情消息预览文字（任务书 3.6）。 */
fun stickerPreviewText(description: String): String =
    if (description.isBlank()) "[表情]" else "[表情] $description"
