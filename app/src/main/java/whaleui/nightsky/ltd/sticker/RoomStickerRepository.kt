package whaleui.nightsky.ltd.sticker

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.BuildConfig
import whaleui.nightsky.ltd.data.db.StickerEntity
import whaleui.nightsky.ltd.data.db.StickerPackEntity
import whaleui.nightsky.ltd.data.db.WhaleStickerDao

/**
 * 表情库的真实实现：Room 两张表 + `filesDir/stickers` 下的文件。
 *
 * 文件的增删都在这里兜底：
 * - 导入（[savePack]）只写行，文件由 [StickerImporter] 复制；
 * - 删单张 / 删系列时把文件和缩略图一起删掉（任务书 3.1）；
 * - 判断「可用」时同时看系列开关和文件是否还在，避免把已经删掉的文件发给模型。
 */
class RoomStickerRepository(
    /** App 的 `filesDir`（贴图文件都在 `filesDir/stickers` 下）。 */
    private val filesDir: File,
    private val dao: WhaleStickerDao,
    /** 把一组写入放进同一个数据库事务（生产用 `database.withTransaction`；JVM 单测直接执行）。 */
    private val transaction: StickerTransactionRunner = StickerTransactionRunner { block -> block() },
) : StickerRepository {

    override fun observePacks(): Flow<List<StickerPack>> =
        dao.observePacks().map { list -> list.map { it.toModel() } }

    override fun observeStickers(): Flow<List<Sticker>> =
        dao.observeStickers().map { list -> list.map { it.toModel() } }

    override suspend fun packs(): List<StickerPack> = withContext(Dispatchers.IO) {
        dao.listPacks().map { it.toModel() }
    }

    override suspend fun stickers(): List<Sticker> = withContext(Dispatchers.IO) {
        dao.listStickers().map { it.toModel() }
    }

    override suspend fun pack(id: String): StickerPack? = withContext(Dispatchers.IO) {
        dao.getPack(id)?.toModel()
    }

    override suspend fun sticker(id: String): Sticker? = withContext(Dispatchers.IO) {
        dao.getSticker(id)?.toModel()
    }

    /**
     * 合并导入（W-006）：整个过程在一个事务里，系列行先查后 insert / update（不用 REPLACE，
     * 不会经外键级联删掉老贴图）；已有系列保留用户设过的「她能用」开关；同编号贴图保留发送统计；
     * 这次包里没有的老贴图不动。
     */
    override suspend fun savePack(pack: StickerPack, stickers: List<Sticker>) {
        withContext(Dispatchers.IO) { transaction.run { savePackInTransaction(pack, stickers) } }
    }

    private suspend fun savePackInTransaction(pack: StickerPack, stickers: List<Sticker>) {
        val existingPack = dao.getPack(pack.id)
        val existing = dao.listStickersOfPack(pack.id).associateBy { it.id }
        if (existingPack == null) {
            dao.insertPack(pack.toEntity())
        } else {
            dao.updatePack(pack.toEntity().copy(enabledForHer = existingPack.enabledForHer))
        }
        val merged = stickers.map { sticker ->
            // 合并导入：同编号保留她上次发的时间和次数（任务书 3.1）
            val old = existing[sticker.id]
            sticker.toEntity(
                lastSentByHerAt = old?.lastSentByHerAt,
                sentByHerCount = old?.sentByHerCount ?: 0,
            )
        }
        if (merged.isNotEmpty()) dao.upsertStickers(merged)
        if (BuildConfig.DEBUG) {
            // JVM 单测里 android.util.Log 没有实现，日志失败不能影响写库
            runCatching { Log.i(TAG, "savePack id=${pack.id} stickers=${merged.size} kept=${existing.size}") }
        }
    }

    override suspend fun setEnabledForHer(packId: String, enabled: Boolean) {
        withContext(Dispatchers.IO) { dao.setEnabledForHer(packId, enabled) }
    }

    override suspend fun markSentByHer(stickerId: String, sentAt: Long) {
        withContext(Dispatchers.IO) { dao.markSentByHer(stickerId, sentAt) }
    }

    override suspend fun deleteSticker(stickerId: String) {
        withContext(Dispatchers.IO) {
            val entity = dao.getSticker(stickerId)
            dao.deleteSticker(stickerId)
            entity?.let { deleteFiles(it.toModel()) }
        }
    }

    override suspend fun deletePack(packId: String) {
        withContext(Dispatchers.IO) {
            val stickers = dao.listStickersOfPack(packId).map { it.toModel() }
            dao.deletePack(packId)
            stickers.forEach(::deleteFiles)
            // 系列目录里只剩删掉的这些文件；目录本身一并清掉（必须在 filesDir/stickers 里面，W-014）
            val packDir = File(stickersRoot(filesDir), packId)
            if (isInsideDir(packDir, stickersRoot(filesDir)) && packDir.name != STICKER_THUMB_DIR_NAME) {
                runCatching { packDir.deleteRecursively() }
            }
        }
    }

    override suspend fun usableStickers(): List<StickerWithPack> = withContext(Dispatchers.IO) {
        val packs = dao.listPacks().map { it.toModel() }.associateBy { it.id }
        dao.listStickers()
            .asSequence()
            .map { entity -> entity.toModel() to packs[entity.packId] }
            .filter { (sticker, pack) -> pack != null && pack.enabledForHer && fileExists(sticker) }
            .map { (sticker, pack) -> StickerWithPack(sticker, pack!!) }
            .sortedWith(compareBy({ it.pack.sortOrder }, { it.sticker.id }))
            .toList()
    }

    override suspend fun hasUsableStickers(): Boolean = withContext(Dispatchers.IO) {
        usableStickers().isNotEmpty()
    }

    override suspend fun isUsable(stickerId: String): Boolean = withContext(Dispatchers.IO) {
        val entity = dao.getSticker(stickerId) ?: return@withContext false
        val pack = dao.getPack(entity.packId) ?: return@withContext false
        pack.enabledForHer && fileExists(entity.toModel())
    }

    private fun fileExists(sticker: Sticker): Boolean =
        stickerPathInRoot(filesDir, sticker.filePath)?.let { it.isFile && it.length() > 0L } ?: false

    /** 只删 `filesDir/stickers` 里面的文件；库里的路径指到外面（旧版本导入的恶意编号）就不删（W-014）。 */
    private fun deleteFiles(sticker: Sticker) {
        stickerPathInRoot(filesDir, sticker.filePath)?.let { runCatching { it.delete() } }
        stickerPathInRoot(filesDir, sticker.thumbPath)?.let { runCatching { it.delete() } }
    }

    private companion object {
        const val TAG = "WhaleSticker"
    }
}

// ------------------------------------------------------------------ 实体 ↔ 模型

/** 事务执行器：生产实现见 WhaleModule（`AppDatabase.withTransaction`）。 */
fun interface StickerTransactionRunner {
    suspend fun run(block: suspend () -> Unit)
}

internal fun StickerPackEntity.toModel(): StickerPack = StickerPack(
    id = id,
    name = name,
    titleOriginal = titleOriginal,
    characters = splitList(characters),
    animated = animated,
    hasSoundOriginally = hasSoundOriginally,
    source = source,
    licenseNote = licenseNote,
    sortOrder = sortOrder,
    enabledForHer = enabledForHer,
    importedAt = importedAt,
)

internal fun StickerPack.toEntity(): StickerPackEntity = StickerPackEntity(
    id = id,
    name = name,
    titleOriginal = titleOriginal,
    characters = joinList(characters),
    animated = animated,
    hasSoundOriginally = hasSoundOriginally,
    source = source,
    licenseNote = licenseNote,
    sortOrder = sortOrder,
    enabledForHer = enabledForHer,
    importedAt = importedAt,
)

internal fun StickerEntity.toModel(): Sticker = Sticker(
    id = id,
    packId = packId,
    filePath = filePath,
    thumbPath = thumbPath,
    format = format,
    animated = animated,
    description = description,
    detail = detail,
    textInImage = textInImage,
    textMeaning = textMeaning,
    characters = splitList(characters),
    couple = couple,
    emotions = splitList(emotions),
    useWhen = useWhen,
    needsReview = needsReview,
    lastSentByHerAt = lastSentByHerAt,
    sentByHerCount = sentByHerCount,
)

internal fun Sticker.toEntity(
    lastSentByHerAt: Long? = this.lastSentByHerAt,
    sentByHerCount: Int = this.sentByHerCount,
): StickerEntity = StickerEntity(
    id = id,
    packId = packId,
    filePath = filePath,
    thumbPath = thumbPath,
    format = format,
    animated = animated,
    description = description,
    detail = detail,
    textInImage = textInImage,
    textMeaning = textMeaning,
    characters = joinList(characters),
    couple = couple,
    emotions = joinList(emotions),
    useWhen = useWhen,
    needsReview = needsReview,
    lastSentByHerAt = lastSentByHerAt,
    sentByHerCount = sentByHerCount,
)
