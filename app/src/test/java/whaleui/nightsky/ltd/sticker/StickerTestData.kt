package whaleui.nightsky.ltd.sticker

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 表情模块 JVM 单测的公共素材。
 *
 * 所有图片都是**测试里自己生成的假 WebP**（只有 `RIFF....WEBP` 文件头 + 填充字节）：
 * 解析器只按文件头判断格式、导入器只复制字节，不需要真的解码；缩略图生成器在单测里换成假的。
 * 这样就不碰用户的真实素材，也不依赖 BitmapFactory。
 */
object StickerTestData {

    /** 假 WebP：`RIFF` + 小端长度 + `WEBP` + `VP8L` + 填充。 */
    fun webpBytes(payload: Int = 32): ByteArray {
        val total = 12 + 8 + payload
        val bytes = ByteArray(total)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        // 长度字段随便写（解析器不看）
        bytes[4] = (total - 8).toByte()
        bytes[5] = ((total - 8) shr 8).toByte()
        bytes[6] = ((total - 8) shr 16).toByte()
        bytes[7] = ((total - 8) shr 24).toByte()
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        "VP8L".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        for (index in 20 until total) bytes[index] = (index % 251).toByte()
        return bytes
    }

    /** 假 PNG / GIF / APNG 等内容（用来测「扩展名是 .webp 但内容不是」）。 */
    fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
    )

    fun gifBytes(): ByteArray = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(16)

    /** 在 [root] 下写一个系列文件夹。 */
    fun writeSeries(
        root: File,
        seriesId: String,
        folderName: String = "${seriesId}_3620_测试系列 $seriesId",
        stickerCount: Int = 2,
        description: ((Int) -> String) = { index -> "第 $index 张" },
        emotions: List<String> = listOf("开心"),
        includeIndex: Boolean = true,
        seriesJsonOverride: ((String) -> String)? = null,
    ): File {
        val folder = File(root, folderName).apply { mkdirs() }
        val stickers = (1..stickerCount).map { index ->
            val fileBase = "29233$index${seriesId}${index.toString().padStart(2, '0')}"
            if (stickerCount > 0) {
                File(folder, "$fileBase.webp").writeBytes(webpBytes())
            }
            """
            {
              "id": "$seriesId-$fileBase",
              "file": "$fileBase.webp",
              "animated": true,
              "description": "${description(index)}",
              "detail": "第 $index 张的详细说明：两个角色站在一起，第三个画面里笑了。",
              "text_in_image": "${if (index == 1) "HELLO!" else ""}",
              "text_meaning": "${if (index == 1) "你好！" else ""}",
              "characters": ["角色甲"],
              "couple": ${index % 2 == 0},
              "emotions": ${toJsonArray(emotions)},
              "use_when": "想他的时候",
              "needs_review": false
            }
            """.trimIndent()
        }.joinToString(",\n")
        val json = seriesJsonOverride?.invoke(stickers) ?: """
        {
          "format": "whale-sticker-series",
          "version": 2,
          "series_id": "$seriesId",
          "line_product_id": "3620",
          "title_original": "测试系列",
          "name": "测试 $seriesId",
          "characters": ["角色甲", "角色乙"],
          "animated": true,
          "has_sound_originally": false,
          "source": "示例贴图(测试)",
          "license_note": "仅个人使用,不得分发",
          "stickers": [
$stickers
          ]
        }
        """.trimIndent()
        File(folder, "series.json").writeText(json)
        if (includeIndex) writeIndex(root, seriesId, folderName, stickerCount)
        return folder
    }

    fun writeIndex(root: File, seriesId: String, folderName: String, count: Int) {
        val existing = File(root, "index.json")
        val entries = existing.takeIf { it.isFile }
            ?.readText()
            ?.let { parseIndexEntries(it) }
            .orEmpty()
            .toMutableList()
        entries.removeAll { it.first == seriesId }
        entries.add(seriesId to folderName)
        val json = buildString {
            appendLine("{")
            appendLine("  \"format\": \"whale-sticker-library\",")
            appendLine("  \"version\": 2,")
            appendLine("  \"series\": [")
            entries.forEachIndexed { index, (id, folder) ->
                val comma = if (index == entries.lastIndex) "" else ","
                appendLine("    { \"series_id\": \"$id\", \"folder\": \"$folder\", \"name\": \"测试 $id\", \"count\": $count }$comma")
            }
            appendLine("  ]")
            appendLine("}")
        }
        existing.writeText(json)
    }

    private fun parseIndexEntries(text: String): List<Pair<String, String>> {
        val regex = Regex("\"series_id\":\\s*\"([^\"]+)\",\\s*\"folder\":\\s*\"([^\"]+)\"")
        return regex.findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()
    }

    fun toJsonArray(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    /** 把一个目录打成 zip（测 zip 导入）。 */
    fun zipDirectory(sourceDir: File, zipFile: File) {
        ZipOutputStream(zipFile.outputStream().buffered()).use { out ->
            sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(sourceDir).invariantSeparatorsPath
                out.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    /** 假缩略图生成器：直接写一个字节，返回成功。 */
    fun fakeThumbnailer(failFor: Set<String> = emptySet()): StickerThumbnailer =
        StickerThumbnailer { _, dest ->
            if (dest.nameWithoutExtension in failFor) {
                false
            } else {
                dest.parentFile?.mkdirs()
                dest.writeBytes(pngBytes())
                true
            }
        }
}

/**
 * 单测用的内存仓库：只实现测试关心的方法，行为和 `RoomStickerRepository` 的合并语义一致
 * （同编号保留上次发送统计）。
 */
class InMemoryStickerRepository : StickerRepository {

    private val packMap = linkedMapOf<String, StickerPack>()
    private val stickerMap = linkedMapOf<String, Sticker>()
    private val deletions = mutableListOf<String>()

    private val packsFlow = MutableStateFlow<List<StickerPack>>(emptyList())
    private val stickersFlow = MutableStateFlow<List<Sticker>>(emptyList())

    /** 记录删过哪些 id（测试断言用）。 */
    fun deletedIds(): List<String> = deletions.toList()

    override fun observePacks(): Flow<List<StickerPack>> = packsFlow

    override fun observeStickers(): Flow<List<Sticker>> = stickersFlow

    override suspend fun packs(): List<StickerPack> = packMap.values.sortedBy { it.sortOrder }

    override suspend fun stickers(): List<Sticker> = stickerMap.values.toList()

    override suspend fun pack(id: String): StickerPack? = packMap[id]

    override suspend fun sticker(id: String): Sticker? = stickerMap[id]

    override suspend fun savePack(pack: StickerPack, stickers: List<Sticker>) {
        packMap[pack.id] = pack
        stickers.forEach { incoming ->
            val old = stickerMap[incoming.id]
            stickerMap[incoming.id] = incoming.copy(
                lastSentByHerAt = old?.lastSentByHerAt ?: incoming.lastSentByHerAt,
                sentByHerCount = old?.sentByHerCount ?: incoming.sentByHerCount,
            )
        }
        publish()
    }

    override suspend fun setEnabledForHer(packId: String, enabled: Boolean) {
        packMap[packId]?.let { packMap[packId] = it.copy(enabledForHer = enabled) }
        publish()
    }

    override suspend fun markSentByHer(stickerId: String, sentAt: Long) {
        val sticker = stickerMap[stickerId] ?: return
        stickerMap[stickerId] = sticker.copy(
            lastSentByHerAt = sentAt,
            sentByHerCount = sticker.sentByHerCount + 1,
        )
        publish()
    }

    override suspend fun deleteSticker(stickerId: String) {
        deletions += stickerId
        stickerMap.remove(stickerId)
        publish()
    }

    override suspend fun deletePack(packId: String) {
        deletions += packId
        packMap.remove(packId)
        stickerMap.values.filter { it.packId == packId }.forEach { stickerMap.remove(it.id) }
        publish()
    }

    /** 测试里没有真实文件，可用性只看开关 + 是否在库里；需要验文件时单独传 fileExists。 */
    var fileExists: (Sticker) -> Boolean = { true }

    override suspend fun usableStickers(): List<StickerWithPack> =
        stickerMap.values
            .mapNotNull { sticker ->
                val pack = packMap[sticker.packId] ?: return@mapNotNull null
                if (!pack.enabledForHer || !fileExists(sticker)) return@mapNotNull null
                StickerWithPack(sticker, pack)
            }
            .sortedWith(compareBy({ it.pack.sortOrder }, { it.sticker.id }))

    override suspend fun hasUsableStickers(): Boolean = usableStickers().isNotEmpty()

    override suspend fun isUsable(stickerId: String): Boolean =
        usableStickers().any { it.sticker.id == stickerId }

    private fun publish() {
        packsFlow.value = packMap.values.sortedBy { it.sortOrder }
        stickersFlow.value = stickerMap.values.toList()
    }
}
