package whaleui.nightsky.ltd.data

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.sticker.Sticker
import whaleui.nightsky.ltd.sticker.StickerPack
import whaleui.nightsky.ltd.sticker.StickerRepository
import whaleui.nightsky.ltd.sticker.StickerWithPack
import whaleui.nightsky.ltd.domain.memory.DiaryEntry
import whaleui.nightsky.ltd.domain.memory.DiaryRepository
import whaleui.nightsky.ltd.ui.memory.FakeDiaryRepository
import whaleui.nightsky.ltd.ui.memory.FakeMemoryRepository
import whaleui.nightsky.ltd.domain.memory.MemoryCategory
import whaleui.nightsky.ltd.domain.memory.MemoryOrigin
import whaleui.nightsky.ltd.domain.settings.PersonaDocument
import whaleui.nightsky.ltd.domain.settings.PersonaImportError
import whaleui.nightsky.ltd.domain.settings.PersonaImportResult
import whaleui.nightsky.ltd.domain.settings.PersonaKind
import whaleui.nightsky.ltd.domain.settings.PersonaStore

/** 「清空数据」只清 soul / user / 记忆 / 日记 / 表情包；不碰聊天。 */
class DataResetServiceTest {

    @Test
    fun `reset clears all five categories`() = runBlocking {
        val personas = FakePersonaStore().apply {
            write(PersonaKind.Soul, "旧的 soul")
            write(PersonaKind.User, "旧的 user")
        }
        val memories = FakeMemoryRepository(seed = emptyList())
        memories.add("一条测试记忆", MemoryCategory.AboutYou, MemoryOrigin.UserAdded)
        val diary = FakeDiaryRepository(
            seed = listOf(DiaryEntry("d1", java.time.LocalDate.of(2026, 10, 25), "", "测试日记")),
        )
        val stickers = FakeStickerRepository().apply {
            addPack(StickerPack(
                id = "01",
                name = "测试系列",
                titleOriginal = "测试系列",
                characters = emptyList(),
                animated = true,
                hasSoundOriginally = false,
                source = "",
                licenseNote = "",
                sortOrder = 0,
            ))
        }
        val service = DataResetService(
            personaStore = personas,
            memoryRepository = memories,
            diaryRepository = diary,
            stickerRepository = stickers,
        )

        service.resetAll()

        assertNull(personas.readText(PersonaKind.Soul))
        assertNull(personas.readText(PersonaKind.User))
        assertTrue(memories.observeAll().first().isEmpty())
        assertTrue(diary.observeAll().first().isEmpty())
        assertTrue(stickers.packs().isEmpty())
        assertEquals(listOf("01"), stickers.deletedPackIds)
    }

    private class FakePersonaStore : PersonaStore {
        private val documents = mutableMapOf<PersonaKind, String>()
        private val states = PersonaKind.entries.associateWith { MutableStateFlow<PersonaDocument?>(null) }

        fun write(kind: PersonaKind, text: String) {
            documents[kind] = text
            states.getValue(kind).value = PersonaDocument(kind, text.length.toLong(), 0L)
        }

        override fun observe(kind: PersonaKind): Flow<PersonaDocument?> = states.getValue(kind).asStateFlow()

        override suspend fun import(kind: PersonaKind, uri: Uri): PersonaImportResult =
            PersonaImportResult.Failure(PersonaImportError.NotReadable)

        override suspend fun readText(kind: PersonaKind): String? = documents[kind]

        override suspend fun remove(kind: PersonaKind) {
            documents.remove(kind)
            states.getValue(kind).value = null
        }

        override suspend fun restore(kind: PersonaKind, text: String): PersonaDocument? = null
    }

    private class FakeStickerRepository : StickerRepository {
        private val packs = linkedMapOf<String, StickerPack>()
        val deletedPackIds = mutableListOf<String>()

        fun addPack(pack: StickerPack) {
            packs[pack.id] = pack
        }

        override fun observePacks(): Flow<List<StickerPack>> =
            MutableStateFlow(packs.values.toList()).asStateFlow()

        override fun observeStickers(): Flow<List<Sticker>> =
            MutableStateFlow(emptyList<Sticker>()).asStateFlow()

        override suspend fun packs(): List<StickerPack> = packs.values.toList()

        override suspend fun stickers(): List<Sticker> = emptyList()

        override suspend fun pack(id: String): StickerPack? = packs[id]

        override suspend fun sticker(id: String): Sticker? = null

        override suspend fun savePack(pack: StickerPack, stickers: List<Sticker>) {
            packs[pack.id] = pack
        }

        override suspend fun setEnabledForHer(packId: String, enabled: Boolean) = Unit

        override suspend fun markSentByHer(stickerId: String, sentAt: Long) = Unit

        override suspend fun deleteSticker(stickerId: String) = Unit

        override suspend fun deletePack(packId: String) {
            packs.remove(packId)
            deletedPackIds += packId
        }

        override suspend fun usableStickers(): List<StickerWithPack> = emptyList()

        override suspend fun hasUsableStickers(): Boolean = false

        override suspend fun isUsable(stickerId: String): Boolean = false
    }
}
