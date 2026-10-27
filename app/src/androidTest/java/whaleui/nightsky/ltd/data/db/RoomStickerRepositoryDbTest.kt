package whaleui.nightsky.ltd.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import whaleui.nightsky.ltd.sticker.RoomStickerRepository
import whaleui.nightsky.ltd.sticker.Sticker
import whaleui.nightsky.ltd.sticker.StickerPack
import whaleui.nightsky.ltd.sticker.StickerTransactionRunner

/**
 * W-006 在真实 SQLite 上的验证：合并导入同一个系列时，老贴图行不能被 `REPLACE` + 外键 CASCADE 删掉，
 * 用户关掉的「她能用」开关保留。
 */
@RunWith(AndroidJUnit4::class)
class RoomStickerRepositoryDbTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: RoomStickerRepository
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory())
            .build()
        filesDir = File(context.cacheDir, "sticker-repo-test-${System.nanoTime()}").apply { mkdirs() }
        repository = RoomStickerRepository(
            filesDir = filesDir,
            dao = database.whaleStickerDao(),
            transaction = StickerTransactionRunner { block -> database.withTransaction { block() } },
        )
    }

    @After
    fun tearDown() {
        database.close()
        filesDir.deleteRecursively()
    }

    private fun pack() = StickerPack(
        id = "01",
        name = "测试 01",
        titleOriginal = "测试系列",
        characters = listOf("角色甲"),
        animated = true,
        hasSoundOriginally = false,
        source = "测试",
        licenseNote = "",
        sortOrder = 0,
        enabledForHer = true,
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
    fun mergeImportKeepsOldStickersAndHerSwitch() = runBlocking {
        repository.savePack(pack(), listOf(sticker("01-1001"), sticker("01-1002")))
        repository.setEnabledForHer("01", false)
        repository.markSentByHer("01-1001", 1_700_000_000_000L)

        repository.savePack(pack(), listOf(sticker("01-1001").copy(description = "新说明")))

        val rows = database.whaleStickerDao().listStickersOfPack("01")
        assertEquals(listOf("01-1001", "01-1002"), rows.map { it.id })
        assertEquals("新说明", rows.first().description)
        assertEquals(1, rows.first().sentByHerCount)
        assertFalse(database.whaleStickerDao().getPack("01")!!.enabledForHer)
    }

    @Test
    fun deletingThePackStillCascades() = runBlocking {
        repository.savePack(pack(), listOf(sticker("01-1001"), sticker("01-1002")))

        repository.deletePack("01")

        assertEquals(0, database.whaleStickerDao().listStickers().size)
    }
}
