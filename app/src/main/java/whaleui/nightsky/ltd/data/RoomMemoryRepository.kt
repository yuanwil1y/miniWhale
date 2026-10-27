package whaleui.nightsky.ltd.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.AppDatabase
import whaleui.nightsky.ltd.data.db.MemoryItemEntity
import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory
import whaleui.nightsky.ltd.domain.memory.MemoryOrigin
import whaleui.nightsky.ltd.domain.memory.MemoryRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * 记忆仓库的 Room 实现，接口契约见 `_contracts/memory-diary-repositories.md`。
 *
 * 顺序语义：[MemoryItemEntity.sortIndex] 越大越靠前。
 * - [add]：新条目取「当前最大值 + 1」，所以一定排在最前；
 * - [restore]：按删除时的下标插回后整表重排 sort_index，严格回到原位置；
 * - [observeAll]：`ORDER BY sort_index DESC` 就是界面顺序。
 *
 * 所有「读 + 写」组合都放在 [withTransaction] 里，避免中途被别的写入插队。
 */
class RoomMemoryRepository(
    private val database: AppDatabase,
) : MemoryRepository {

    private val dao get() = database.whaleMemoryDao()

    override fun observeAll(): Flow<List<Memory>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun add(
        content: String,
        category: MemoryCategory,
        origin: MemoryOrigin,
    ): Memory = database.withTransaction {
        val now = System.currentTimeMillis()
        val entity = MemoryItemEntity(
            id = UUID.randomUUID().toString(),
            content = content,
            category = category.name,
            origin = origin.name,
            createdAt = now,
            updatedAt = now,
            sortIndex = dao.maxSortIndex() + 1,
        )
        dao.upsert(entity)
        entity.toModel()
    }

    override suspend fun update(
        id: String,
        content: String,
        category: MemoryCategory,
    ): Memory? = database.withTransaction {
        val existing = dao.getById(id) ?: return@withTransaction null
        val updatedAt = System.currentTimeMillis()
        dao.updateContent(
            id = id,
            content = content,
            category = category.name,
            updatedAt = updatedAt,
        )
        existing.copy(
            content = content,
            category = category.name,
            updatedAt = updatedAt,
        ).toModel()
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    override suspend fun restore(memory: Memory, index: Int) {
        database.withTransaction {
            // 已经在列表里（比如连点两次撤销）就什么都不做。
            if (dao.getById(memory.id) != null) return@withTransaction
            val ordered = dao.listAll().toMutableList()
            ordered.add(index.coerceIn(0, ordered.size), memory.toEntity(sortIndex = 0))
            // 列表越靠前 sort_index 越大：size, size-1, ..., 1。
            val size = ordered.size
            dao.upsertAll(
                ordered.mapIndexed { position, entity ->
                    entity.copy(sortIndex = (size - position).toLong())
                }
            )
        }
    }

    override suspend fun clearAll() {
        dao.deleteAll()
    }

    override suspend fun exportAll(): String {
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)
        val memories = dao.listAll().map { it.toModel() }
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

// ---------------------------------------------------------------------------
// Entity ↔ 模型
// ---------------------------------------------------------------------------

private fun MemoryItemEntity.toModel(): Memory = Memory(
    id = id,
    content = content,
    category = category.toMemoryCategory(),
    createdAt = createdAt,
    updatedAt = updatedAt,
    origin = origin.toMemoryOrigin(),
)

private fun Memory.toEntity(sortIndex: Long): MemoryItemEntity = MemoryItemEntity(
    id = id,
    content = content,
    category = category.name,
    origin = origin.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sortIndex = sortIndex,
)

/** 枚举名读不出来时回退到默认值，避免以后枚举改名字把整页拖垮。 */
private fun String.toMemoryCategory(): MemoryCategory =
    MemoryCategory.entries.firstOrNull { it.name == this } ?: MemoryCategory.AboutYou

private fun String.toMemoryOrigin(): MemoryOrigin =
    MemoryOrigin.entries.firstOrNull { it.name == this } ?: MemoryOrigin.UserAdded

private fun Long.toLocalDate(): java.time.LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
