package whaleui.nightsky.ltd.sticker

import kotlinx.coroutines.flow.Flow

/**
 * 表情库的读写入口（任务书 3.1）。
 *
 * 界面、工具、提示词都只认这个接口；生产实现是 `RoomStickerRepository`，
 * 单测用内存实现（只在测试源集里，不放进生产代码）。
 */
interface StickerRepository {

    fun observePacks(): Flow<List<StickerPack>>

    fun observeStickers(): Flow<List<Sticker>>

    suspend fun packs(): List<StickerPack>

    suspend fun stickers(): List<Sticker>

    suspend fun pack(id: String): StickerPack?

    suspend fun sticker(id: String): Sticker?

    /**
     * 写入一个系列及其贴图（导入用）。同编号的贴图更新说明，**保留**上次发送时间和次数；
     * 这次包里没有的老贴图不删（合并导入语义）。
     */
    suspend fun savePack(pack: StickerPack, stickers: List<Sticker>)

    suspend fun setEnabledForHer(packId: String, enabled: Boolean)

    suspend fun markSentByHer(stickerId: String, sentAt: Long)

    /** 删一张：贴图行 + 文件 + 缩略图。已经发在聊天里的消息不受影响。 */
    suspend fun deleteSticker(stickerId: String)

    /** 删整个系列：贴图行 + 整个目录 + 缩略图。 */
    suspend fun deletePack(packId: String)

    /** 她能用（系列开了开关）且文件还在的候选，按稳定顺序（系列 sort_order、编号）返回。 */
    suspend fun usableStickers(): List<StickerWithPack>

    /** 有没有任何可用表情；没有时技能、分类行、每轮提醒、两个工具都不出现。 */
    suspend fun hasUsableStickers(): Boolean

    /** 某个编号当前还能不能用（`send_sticker` 校验用）。 */
    suspend fun isUsable(stickerId: String): Boolean
}

/** 表情根目录 `filesDir/stickers`。 */
internal fun stickersRoot(filesDir: java.io.File): java.io.File = java.io.File(filesDir, STICKER_DIR_NAME)

/**
 * [file] 规范化（解开 `..`、符号链接）之后是否严格在 [dir] 里面。
 * 所有落盘 / 删除表情文件之前都要过这一关，防止编号或路径里带 `../` 写到 / 删到别处。
 */
internal fun isInsideDir(file: java.io.File, dir: java.io.File): Boolean = runCatching {
    file.canonicalPath.startsWith(dir.canonicalPath + java.io.File.separator)
}.getOrDefault(false)

/** 相对 `filesDir` 的表情路径 → 文件；规范化后不在 `filesDir/stickers` 里就返回 null。 */
internal fun stickerPathInRoot(filesDir: java.io.File, relativePath: String): java.io.File? =
    java.io.File(filesDir, relativePath).takeIf { isInsideDir(it, stickersRoot(filesDir)) }
