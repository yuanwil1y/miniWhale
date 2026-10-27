package whaleui.nightsky.ltd.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * `sticker_pack` / `sticker` 两张表的读写入口（任务书 3.1）。
 *
 * 导入采用「upsert + 保留老行」的合并语义：同编号覆盖说明、不清掉这次包里没有的行；
 * 系列号重复时由导入器决定是合并还是覆盖（默认合并）。
 *
 * **系列行不能用 `REPLACE`**：SQLite 的 REPLACE 是「先删旧行再插入」，删 `sticker_pack` 会经外键
 * `ON DELETE CASCADE` 把该系列所有贴图一起删掉（W-006）。所以系列分成 [insertPack] / [updatePack]，
 * 由 `RoomStickerRepository.savePack` 在一个事务里先查后写。
 */
@Dao
interface WhaleStickerDao {

    // ---------------------------------------------------------------- 系列

    @Query("SELECT * FROM sticker_pack ORDER BY sort_order ASC, id ASC")
    fun observePacks(): Flow<List<StickerPackEntity>>

    @Query("SELECT * FROM sticker_pack ORDER BY sort_order ASC, id ASC")
    suspend fun listPacks(): List<StickerPackEntity>

    @Query("SELECT * FROM sticker_pack WHERE id = :id LIMIT 1")
    suspend fun getPack(id: String): StickerPackEntity?

    /** 新系列。已存在时抛异常（不会删旧行）。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPack(pack: StickerPackEntity)

    /** 已有系列原地更新（UPDATE，不触发外键级联）。 */
    @Update
    suspend fun updatePack(pack: StickerPackEntity)

    @Query("UPDATE sticker_pack SET enabled_for_her = :enabled WHERE id = :id")
    suspend fun setEnabledForHer(id: String, enabled: Boolean)

    @Query("DELETE FROM sticker_pack WHERE id = :id")
    suspend fun deletePack(id: String)

    // ---------------------------------------------------------------- 贴图

    @Query("SELECT * FROM sticker ORDER BY pack_id ASC, id ASC")
    fun observeStickers(): Flow<List<StickerEntity>>

    @Query("SELECT * FROM sticker ORDER BY pack_id ASC, id ASC")
    suspend fun listStickers(): List<StickerEntity>

    @Query("SELECT * FROM sticker WHERE pack_id = :packId ORDER BY id ASC")
    suspend fun listStickersOfPack(packId: String): List<StickerEntity>

    @Query("SELECT * FROM sticker WHERE id = :id LIMIT 1")
    suspend fun getSticker(id: String): StickerEntity?

    /** 一次写多张（导入时一个系列一批）。真正的 upsert：已有行原地更新，不先删。 */
    @Upsert
    suspend fun upsertStickers(stickers: List<StickerEntity>)

    @Query("DELETE FROM sticker WHERE id = :id")
    suspend fun deleteSticker(id: String)

    /** 她发出去之后更新统计。 */
    @Query(
        "UPDATE sticker SET last_sent_by_her_at = :sentAt, " +
            "sent_by_her_count = sent_by_her_count + 1 WHERE id = :id"
    )
    suspend fun markSentByHer(id: String, sentAt: Long)
}
