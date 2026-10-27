package whaleui.nightsky.ltd.ui.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import whaleui.nightsky.ltd.domain.memory.DiaryEntry
import whaleui.nightsky.ltd.domain.memory.DiaryRepository
import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory
import whaleui.nightsky.ltd.domain.memory.MemoryOrigin
import whaleui.nightsky.ltd.domain.memory.MemoryRepository

// 测试用的内存版仓库（R1 / W-043 从 main 挪到 test 源集）

/**
 * 内存版记忆仓库。
 *
 * 用 [MutableStateFlow] 保存列表，新增放最前、删除按 id 过滤、撤销按原下标插回。
 */
class FakeMemoryRepository(
    seed: List<Memory> = emptyList(),
) : MemoryRepository {

    private val state = MutableStateFlow(seed)

    override fun observeAll(): Flow<List<Memory>> = state.asStateFlow()

    override suspend fun add(
        content: String,
        category: MemoryCategory,
        origin: MemoryOrigin,
    ): Memory {
        val now = System.currentTimeMillis()
        val memory = Memory(
            id = UUID.randomUUID().toString(),
            content = content,
            category = category,
            createdAt = now,
            updatedAt = now,
            origin = origin,
        )
        state.update { listOf(memory) + it }
        return memory
    }

    override suspend fun update(
        id: String,
        content: String,
        category: MemoryCategory,
    ): Memory? {
        var updated: Memory? = null
        state.update { list ->
            list.map { memory ->
                if (memory.id != id) {
                    memory
                } else {
                    memory.copy(
                        content = content,
                        category = category,
                        updatedAt = System.currentTimeMillis(),
                    ).also { updated = it }
                }
            }
        }
        return updated
    }

    override suspend fun delete(id: String) {
        state.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun restore(memory: Memory, index: Int) {
        state.update { list ->
            if (list.any { it.id == memory.id }) {
                list
            } else {
                list.toMutableList().apply { add(index.coerceIn(0, size), memory) }
            }
        }
    }

    override suspend fun clearAll() {
        state.value = emptyList()
    }

    override suspend fun exportAll(): String {
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)
        val memories = state.value
        val text = buildString {
            append("# 记忆\n")
            MemoryCategory.entries.forEach { category ->
                val items = memories.filter { it.category == category }
                if (items.isEmpty()) return@forEach
                append("\n## ").append(category.label).append('\n')
                items.forEach { memory ->
                    append("\n- ")
                    append(memory.content.replace('\n', ' '))
                    append("（")
                    append(memory.createdAt.toLocalDate().format(dateFormatter))
                    append("）")
                    if (memory.origin == MemoryOrigin.HerRecorded) {
                        append(" · ").append(memory.origin.label)
                    }
                    append('\n')
                }
            }
        }
        return text.trimEnd() + "\n"
    }
}

/** 内存版日记仓库：按日期倒序订阅，删除 / 撤销删除。 */
class FakeDiaryRepository(
    seed: List<DiaryEntry> = emptyList(),
) : DiaryRepository {

    private val state = MutableStateFlow(seed)

    override fun observeAll(): Flow<List<DiaryEntry>> = state.map { list ->
        list.sortedWith(
            compareByDescending<DiaryEntry> { it.date }.thenByDescending { it.id },
        )
    }

    override suspend fun delete(id: String) {
        state.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun restore(entry: DiaryEntry) {
        state.update { list -> if (list.any { it.id == entry.id }) list else list + entry }
    }

    override suspend fun clearAll() {
        state.value = emptyList()
    }
}
