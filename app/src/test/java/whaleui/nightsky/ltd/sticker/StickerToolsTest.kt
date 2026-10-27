package whaleui.nightsky.ltd.sticker

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `find_sticker` / `send_sticker`（任务书 3.3 + 第四节）。 */
class StickerToolsTest {

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("whale-sticker-tools").toFile().apply { deleteOnExit() }

    private suspend fun repositoryWith(
        filesDir: File,
        ids: List<String> = listOf("01-1000001"),
        enabled: Boolean = true,
    ): InMemoryStickerRepository {
        val repository = InMemoryStickerRepository()
        repository.savePack(
            StickerPack(
                id = "01",
                name = "冬季约会",
                titleOriginal = "冬季约会",
                characters = listOf("角色甲"),
                animated = true,
                hasSoundOriginally = false,
                source = "示例贴图(测试)",
                licenseNote = "仅个人使用",
                sortOrder = 0,
                enabledForHer = enabled,
            ),
            ids.map { id ->
                val file = File(filesDir, "stickers/01/${id.substringAfter('-')}.webp")
                file.parentFile?.mkdirs()
                file.writeBytes(StickerTestData.webpBytes())
                Sticker(
                    id = id,
                    packId = "01",
                    filePath = "stickers/01/${id.substringAfter('-')}.webp",
                    thumbPath = "stickers/_thumbs/$id.png",
                    description = "抱在一起",
                    detail = "测试表情的详细说明。",
                    emotions = listOf("抱抱", "开心"),
                    couple = true,
                    useWhen = "测试场景",
                    textInImage = "LOVE",
                    textMeaning = "爱",
                )
            },
        )
        return repository
    }

    private fun tools(filesDir: File, repository: StickerRepository) =
        StickerTools(repository = repository, filesDir = filesDir, clock = { 1_700_000_000_000L })

    @Test
    fun `find returns rows with id pack detail and last sent`() = runBlocking {
        val filesDir = tempDir()
        val repository = repositoryWith(filesDir)
        val text = tools(filesDir, repository).findSticker(buildJsonObject { put("emotion", JsonPrimitive("抱抱")) })
        assertTrue(text.contains("01-1000001"))
        assertTrue(text.contains("冬季约会"))
        assertTrue(text.contains("测试表情的详细说明"))
        assertTrue(text.contains("上次发:没发过"))
    }

    @Test
    fun `find with an unknown emotion explains and lists available emotions`() = runBlocking {
        val filesDir = tempDir()
        val repository = repositoryWith(filesDir)
        val text = tools(filesDir, repository).findSticker(buildJsonObject { put("emotion", JsonPrimitive("吃醋")) })
        assertTrue(text.contains("不认识的情绪词"))
        assertTrue(text.contains("吃醋"))
        assertTrue(text.contains("抱抱"))
        assertTrue(text.contains("开心"))
    }

    @Test
    fun `find with no matching emotion returns the empty hint`() = runBlocking {
        val filesDir = tempDir()
        val repository = repositoryWith(filesDir)
        val text = tools(filesDir, repository).findSticker(buildJsonObject { put("emotion", JsonPrimitive("庆祝")) })
        assertTrue(text.contains("没有找到合适的"))
    }

    @Test
    fun `find with an empty library says so`() = runBlocking {
        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val text = tools(filesDir, repository).findSticker(buildJsonObject { })
        assertTrue(text.contains("现在没有可用的表情"))
    }

    @Test
    fun `send returns ok text plus a sticker image part and updates stats`() = runBlocking {
        val filesDir = tempDir()
        val repository = repositoryWith(filesDir)
        val flag = AtomicBoolean(false)
        val parts = tools(filesDir, repository).sendSticker("01-1000001", flag)

        assertEquals(2, parts.size)
        assertEquals("""{"sent":true,"id":"01-1000001"}""", (parts[0] as UIMessagePart.Text).text)
        val image = parts[1] as UIMessagePart.Image
        assertTrue(image.url.startsWith("file://"))
        assertEquals(true, (image.metadata?.get("sticker") as? JsonPrimitive)?.content?.toBoolean())
        assertEquals("01-1000001", (image.metadata?.get("stickerId") as? JsonPrimitive)?.content)
        assertEquals("抱在一起", (image.metadata?.get("description") as? JsonPrimitive)?.content)

        val stored = repository.sticker("01-1000001")!!
        assertEquals(1, stored.sentByHerCount)
        assertEquals(1_700_000_000_000L, stored.lastSentByHerAt)
    }

    @Test
    fun `second send in the same turn is rejected`() = runBlocking {
        val filesDir = tempDir()
        val repository = repositoryWith(filesDir, ids = listOf("01-1", "01-2"))
        val flag = AtomicBoolean(false)
        val tools = tools(filesDir, repository)
        tools.sendSticker("01-1", flag)
        val second = tools.sendSticker("01-2", flag)
        assertEquals(1, second.size)
        val text = (second.single() as UIMessagePart.Text).text
        assertTrue(text.contains("这一轮已经发过一张了"))
        // 第二张没有被记为发过
        assertEquals(0, repository.sticker("01-2")!!.sentByHerCount)
    }

    @Test
    fun `a new tool list means a new turn and a new budget`() = runBlocking {
        val filesDir = tempDir()
        val repository = repositoryWith(filesDir)
        val tools = tools(filesDir, repository)
        val firstTurnSend = tools.create().first { it.name == "send_sticker" }
        firstTurnSend.execute(buildJsonObject { put("id", JsonPrimitive("01-1000001")) })
        val secondTurnSend = tools.create().first { it.name == "send_sticker" }
        val result = secondTurnSend.execute(buildJsonObject { put("id", JsonPrimitive("01-1000001")) })
        // 第二轮可以再发一张（这是闹钟轮/工具回环都会新建 create() 的同一套行为）
        assertTrue((result.first() as UIMessagePart.Text).text.contains("\"sent\":true"))
    }

    @Test
    fun `send with an unknown id fails`() = runBlocking {
        val filesDir = tempDir()
        val repository = repositoryWith(filesDir)
        val parts = tools(filesDir, repository).sendSticker("99-404", AtomicBoolean(false))
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("没有这个编号"))
    }

    @Test
    fun `send with an empty id fails`() = runBlocking {
        val filesDir = tempDir()
        val repository = repositoryWith(filesDir)
        val parts = tools(filesDir, repository).sendSticker("  ", AtomicBoolean(false))
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("要发哪一张"))
    }

    @Test
    fun `send from a disabled series fails`() = runBlocking {
        val filesDir = tempDir()
        val repository = repositoryWith(filesDir, enabled = false)
        val parts = tools(filesDir, repository).sendSticker("01-1000001", AtomicBoolean(false))
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("不能用"))
    }

    @Test
    fun `send when the file is gone fails`() = runBlocking {
        val filesDir = tempDir()
        val repository = repositoryWith(filesDir)
        File(filesDir, "stickers/01/1000001.webp").delete()
        val parts = tools(filesDir, repository).sendSticker("01-1000001", AtomicBoolean(false))
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("文件不在了"))
    }

    @Test
    fun `send refuses a sticker whose file path points outside the stickers dir`() = runBlocking {
        val filesDir = tempDir()
        val repository = repositoryWith(filesDir)
        // 库里的路径被改成 stickers 目录外面的一个真实文件（附 4：发送时只读，但也不该读到别处）
        File(filesDir, "secret.webp").writeBytes(StickerTestData.webpBytes())
        val original = repository.sticker("01-1000001")!!
        repository.savePack(repository.pack("01")!!, listOf(original.copy(filePath = "stickers/../secret.webp")))

        val parts = tools(filesDir, repository).sendSticker("01-1000001", AtomicBoolean(false))

        assertTrue(parts.none { it is UIMessagePart.Image })
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("文件不在了"))
        assertEquals(0, repository.sticker("01-1000001")!!.sentByHerCount)
    }

    @Test
    fun `usable candidates skip a disabled pack or a missing file`() = runBlocking {
        val filesDir = tempDir()
        val repository = repositoryWith(filesDir)
        assertTrue(repository.usableStickers().isNotEmpty())
        File(filesDir, "stickers/01/1000001.webp").delete()
        repository.fileExists = { File(filesDir, it.filePath).isFile }
        assertTrue(repository.usableStickers().isEmpty())
    }

    @Test
    fun `a disabled pack is invisible to find and to usable candidates`() = runBlocking {
        // W-062：上面那条只测了「文件没了」；这里补「系列被关掉」——同一情绪词，只找得到开着的系列
        val filesDir = tempDir()
        val repository = repositoryWith(filesDir)
        val hidden = File(filesDir, "stickers/02/9990001.webp").apply {
            parentFile?.mkdirs()
            writeBytes(StickerTestData.webpBytes())
        }
        repository.savePack(
            repository.pack("01")!!.copy(id = "02", name = "关掉的系列", sortOrder = 1, enabledForHer = false),
            listOf(
                repository.sticker("01-1000001")!!.copy(
                    id = "02-9990001",
                    packId = "02",
                    filePath = "stickers/02/${hidden.name}",
                    thumbPath = "stickers/_thumbs/02-9990001.png",
                    sentByHerCount = 0,
                    lastSentByHerAt = null,
                ),
            ),
        )

        assertEquals(listOf("01-1000001"), repository.usableStickers().map { it.sticker.id })
        val text = tools(filesDir, repository).findSticker(buildJsonObject { put("emotion", JsonPrimitive("抱抱")) })
        assertTrue(text.contains("01-1000001"))
        assertTrue("关掉的系列不能出现在候选里", !text.contains("02-9990001"))
        assertTrue(!text.contains("关掉的系列"))

        // 把开着的那个也关掉：一张都没有
        repository.setEnabledForHer("01", false)
        assertTrue(repository.usableStickers().isEmpty())
    }

    @Test
    fun `sticker metadata helpers read back what the tool wrote`() = runBlocking {
        val filesDir = tempDir()
        val repository = repositoryWith(filesDir)
        val image = tools(filesDir, repository)
            .sendSticker("01-1000001", AtomicBoolean(false))
            .filterIsInstance<UIMessagePart.Image>()
            .single()
        assertTrue(image.isStickerPart())
        assertEquals("01-1000001", image.stickerIdOrNull())
        assertEquals("抱在一起", image.stickerDescriptionOrNull())
        assertEquals("测试表情的详细说明。", image.stickerDetailOrNull())
        // 非表情图片不算
        assertTrue(!UIMessagePart.Image("file:///x.png").isStickerPart())
    }
}
