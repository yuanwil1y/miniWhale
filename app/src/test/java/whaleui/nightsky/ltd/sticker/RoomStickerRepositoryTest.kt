package whaleui.nightsky.ltd.sticker

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.data.db.StickerEntity
import whaleui.nightsky.ltd.data.db.StickerPackEntity
import whaleui.nightsky.ltd.data.db.WhaleStickerDao

/**
 * [RoomStickerRepository] 的合并导入语义（W-006）与删除路径校验（W-014），JVM 上跑。
 *
 * JVM 单测里没有真实 SQLite（没有 Robolectric / bundled driver），所以这里用一个**按 SQLite 语义**
 * 模拟的 DAO：`REPLACE` 冲突 = 先删旧行再插入，`sticker.pack_id` 外键 `ON DELETE CASCADE`
 * 会把该系列的贴图一起删掉。真实数据库上的同一场景见 androidTest `RoomStickerRepositoryDbTest`。
 */
class RoomStickerRepositoryTest {

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("whale-sticker-repo").toFile().apply { deleteOnExit() }

    private fun pack(enabled: Boolean = true) = StickerPack(
        id = "01",
        name = "测试 01",
        titleOriginal = "测试系列",
        characters = listOf("角色甲"),
        animated = true,
        hasSoundOriginally = false,
        source = "测试",
        licenseNote = "",
        sortOrder = 0,
        enabledForHer = enabled,
        importedAt = 1L,
    )

    private fun sticker(id: String) = Sticker(
        id = id,
        packId = "01",
        filePath = "stickers/01/${id.removePrefix("01-")}.webp",
        thumbPath = "stickers/_thumbs/$id.png",
        description = "说明 $id",
        detail = "详细说明 $id",
    )

    @Test
    fun `merge import keeps stickers that are not in the new pack`() = runBlocking {
        val dao = FakeStickerDao()
        val repository = RoomStickerRepository(tempDir(), dao)
        repository.savePack(pack(), listOf(sticker("01-1001"), sticker("01-1002")))

        // 合并导入一个只含 1 张的新版本
        repository.savePack(pack(), listOf(sticker("01-1001").copy(description = "新说明")))

        val rows = dao.listStickersOfPack("01")
        assertEquals("老包里有、新包里没有的贴图不能被删", listOf("01-1001", "01-1002"), rows.map { it.id })
        assertEquals("新说明", rows.first().description)
    }

    @Test
    fun `merge import keeps her-can-use switch and send statistics`() = runBlocking {
        val dao = FakeStickerDao()
        val repository = RoomStickerRepository(tempDir(), dao)
        repository.savePack(pack(), listOf(sticker("01-1001")))
        repository.setEnabledForHer("01", false)
        repository.markSentByHer("01-1001", 1_700_000_000_000L)

        // 导入器总是带 enabledForHer = true 进来
        repository.savePack(pack(enabled = true), listOf(sticker("01-1001")))

        assertFalse("用户关掉的「她能用」不能被合并导入改回打开", dao.getPack("01")!!.enabledForHer)
        assertEquals(1, dao.getSticker("01-1001")!!.sentByHerCount)
        assertEquals(1_700_000_000_000L, dao.getSticker("01-1001")!!.lastSentByHerAt)
    }

    @Test
    fun `save pack writes everything inside one transaction`() = runBlocking {
        val dao = FakeStickerDao()
        var inTransaction = false
        var transactions = 0
        dao.onWrite = { assertTrue("写库必须在事务里", inTransaction) }
        val repository = RoomStickerRepository(
            filesDir = tempDir(),
            dao = dao,
            transaction = StickerTransactionRunner { block ->
                transactions++
                inTransaction = true
                try {
                    block()
                } finally {
                    inTransaction = false
                }
            },
        )
        repository.savePack(pack(), listOf(sticker("01-1001"), sticker("01-1002")))
        assertEquals(1, transactions)
        assertEquals(2, dao.listStickersOfPack("01").size)
    }

    @Test
    fun `deleting a sticker never deletes files outside the stickers folder`() = runBlocking {
        val filesDir = tempDir()
        val victim = File(filesDir, "whale/avatar_her.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val dao = FakeStickerDao()
        val repository = RoomStickerRepository(filesDir, dao)
        // 旧版本导入时可能已经落库的恶意路径
        repository.savePack(
            pack(),
            listOf(
                sticker("01-1001").copy(
                    filePath = "stickers/01/../../whale/avatar_her.png",
                    thumbPath = "stickers/_thumbs/../../whale/avatar_her.png",
                ),
            ),
        )

        repository.deleteSticker("01-1001")

        assertTrue("stickers 目录以外的文件不能被删：${victim.path}", victim.isFile)
        assertTrue(dao.listStickersOfPack("01").isEmpty())
    }

    @Test
    fun `deleting a pack with a hostile id never deletes outside the stickers folder`() = runBlocking {
        val filesDir = tempDir()
        val victim = File(filesDir, "whale/keep.txt").apply {
            parentFile?.mkdirs()
            writeText("keep")
        }
        val dao = FakeStickerDao()
        val repository = RoomStickerRepository(filesDir, dao)
        repository.savePack(pack().copy(id = "../whale"), emptyList())

        repository.deletePack("../whale")

        assertTrue("stickers 目录以外的目录不能被删：${victim.path}", victim.isFile)
    }
}

/**
 * 按 SQLite 语义模拟的 [WhaleStickerDao]：
 * - `@Insert(REPLACE)`：主键冲突时先删旧行再插入（删除会触发外键 CASCADE）；
 * - `@Insert(ABORT)`：主键冲突时抛异常；
 * - `@Update` / `@Upsert`：原地更新，不删行。
 */
class FakeStickerDao : WhaleStickerDao {

    val packs = linkedMapOf<String, StickerPackEntity>()
    val stickers = linkedMapOf<String, StickerEntity>()

    /** 每次写库前回调（测试断言「在事务里」用）。 */
    var onWrite: () -> Unit = {}

    private val packsFlow = MutableStateFlow<List<StickerPackEntity>>(emptyList())
    private val stickersFlow = MutableStateFlow<List<StickerEntity>>(emptyList())

    override fun observePacks(): Flow<List<StickerPackEntity>> = packsFlow

    override suspend fun listPacks(): List<StickerPackEntity> = packs.values.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    override suspend fun getPack(id: String): StickerPackEntity? = packs[id]

    /** 修复前 DAO 上的 `@Insert(REPLACE) upsertPack` 在 SQLite 里的真实效果（留作对照，修复后不再被调用）。 */
    @Suppress("unused")
    suspend fun replacePack(pack: StickerPackEntity) {
        onWrite()
        // REPLACE：先删（CASCADE 删掉该系列所有贴图）再插
        if (packs.containsKey(pack.id)) deletePackRow(pack.id)
        packs[pack.id] = pack
        publish()
    }

    override suspend fun insertPack(pack: StickerPackEntity) {
        onWrite()
        check(!packs.containsKey(pack.id)) { "UNIQUE constraint failed: sticker_pack.id" }
        packs[pack.id] = pack
        publish()
    }

    override suspend fun updatePack(pack: StickerPackEntity) {
        onWrite()
        if (packs.containsKey(pack.id)) packs[pack.id] = pack
        publish()
    }

    override suspend fun setEnabledForHer(id: String, enabled: Boolean) {
        onWrite()
        packs[id]?.let { packs[id] = it.copy(enabledForHer = enabled) }
        publish()
    }

    override suspend fun deletePack(id: String) {
        onWrite()
        deletePackRow(id)
        publish()
    }

    override fun observeStickers(): Flow<List<StickerEntity>> = stickersFlow

    override suspend fun listStickers(): List<StickerEntity> =
        stickers.values.sortedWith(compareBy({ it.packId }, { it.id }))

    override suspend fun listStickersOfPack(packId: String): List<StickerEntity> =
        stickers.values.filter { it.packId == packId }.sortedBy { it.id }

    override suspend fun getSticker(id: String): StickerEntity? = stickers[id]

    override suspend fun upsertStickers(stickers: List<StickerEntity>) {
        onWrite()
        stickers.forEach { sticker ->
            require(packs.containsKey(sticker.packId)) { "FOREIGN KEY constraint failed" }
            this.stickers[sticker.id] = sticker
        }
        publish()
    }

    override suspend fun deleteSticker(id: String) {
        onWrite()
        stickers.remove(id)
        publish()
    }

    override suspend fun markSentByHer(id: String, sentAt: Long) {
        onWrite()
        stickers[id]?.let { stickers[id] = it.copy(lastSentByHerAt = sentAt, sentByHerCount = it.sentByHerCount + 1) }
        publish()
    }

    private fun deletePackRow(id: String) {
        packs.remove(id)
        stickers.values.filter { it.packId == id }.map { it.id }.forEach { stickers.remove(it) }
    }

    private fun publish() {
        packsFlow.value = packs.values.toList()
        stickersFlow.value = stickers.values.toList()
    }
}
