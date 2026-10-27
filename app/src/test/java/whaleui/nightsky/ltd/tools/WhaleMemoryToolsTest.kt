package whaleui.nightsky.ltd.tools

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory
import whaleui.nightsky.ltd.domain.memory.MemoryOrigin
import whaleui.nightsky.ltd.memory.MemoryTag
import whaleui.nightsky.ltd.ui.memory.FakeMemoryRepository

/** remember / update_memory / forget_memory（提示词第 8 批）：分类名统一、返回 m 编号、带标记的不能改删。 */
class WhaleMemoryToolsTest {

    private val userAdded = Memory(
        id = "fake-user-added",
        content = "测试用的对方让记的事",
        category = MemoryCategory.AboutYou,
        createdAt = 1L,
        updatedAt = 1L,
        origin = MemoryOrigin.UserAdded,
    )
    private val repository = FakeMemoryRepository(listOf(userAdded))
    private val tools = WhaleTools.Testing.memoryOnly(repository)

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun args(text: String): JsonObject = json(text)

    @Test
    fun `remember returns the m tag and the prompt category name`() = runBlocking {
        val result = json(tools.remember(args("""{"content":"测试约好的事","category":"约定"}""")))
        val saved = repository.observeAll().first().first { it.content == "测试约好的事" }
        assertEquals("m" + MemoryTag.tagOf(saved.id), result["id"]!!.jsonPrimitive.content)
        assertEquals("约定", result["category"]!!.jsonPrimitive.content)
        assertEquals(MemoryCategory.Agreement, saved.category)
    }

    @Test
    fun `categories accept the new names and the old ones`() = runBlocking {
        tools.remember(args("""{"content":"一","category":"你自己的"}"""))
        tools.remember(args("""{"content":"二","category":"她的"}"""))
        tools.remember(args("""{"content":"三","category":"关于他"}"""))
        tools.remember(args("""{"content":"四"}"""))
        val byContent = repository.observeAll().first().associate { it.content to it.category }
        assertEquals(MemoryCategory.Hers, byContent["一"])
        assertEquals(MemoryCategory.Hers, byContent["二"])
        assertEquals(MemoryCategory.AboutYou, byContent["三"])
        assertEquals(MemoryCategory.AboutYou, byContent["四"])
        val result = json(tools.remember(args("""{"content":"五"}""")))
        assertEquals("关于对方", result["category"]!!.jsonPrimitive.content)
    }

    @Test
    fun `user added memories cannot be changed and the error says how to add instead`() = runBlocking {
        val tag = "m" + MemoryTag.tagOf(userAdded.id)
        val update = json(tools.updateMemory(args("""{"id":"$tag","content":"改掉"}""")))["error"]!!.jsonPrimitive.content
        val forget = json(tools.forgetMemory(args("""{"id":"$tag"}""")))["error"]!!.jsonPrimitive.content
        assertEquals("这条是对方让记的。带（对方让记的）标记的不能改也不能删，想补充就另记一条。", update)
        assertEquals(update, forget)
        assertEquals(userAdded.content, repository.observeAll().first().single { it.id == userAdded.id }.content)
    }

    @Test
    fun `tool schemas use neutral wording and the unified enum`() {
        val all = tools.create()
        all.forEach { tool ->
            assertFalse(tool.name, tool.description.contains("他"))
            assertFalse(tool.name, tool.description.contains("她"))
        }
        val remember = all.single { it.name == "remember" }
        val schema = remember.parameters() as InputSchema.Obj
        val enum = schema.properties.getValue("category").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("关于对方", "约定", "你自己的"), enum)
        assertTrue(all.single { it.name == "update_memory" }.description.contains("带（对方让记的）标记的不能改也不能删"))
        // 日记工具的参数都有说明
        listOf("write_diary" to "text", "read_diary" to "days", "read_diary" to "date").forEach { (name, param) ->
            val params = all.single { it.name == name }.parameters() as InputSchema.Obj
            assertTrue("$name.$param", params.properties.getValue(param).jsonObject.containsKey("description"))
        }
    }
}
