package whaleui.nightsky.ltd.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.AppDatabase
import whaleui.nightsky.ltd.data.db.DiaryEntryEntity
import whaleui.nightsky.ltd.domain.memory.DiaryEntry
import whaleui.nightsky.ltd.domain.memory.DiaryRepository
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * 日记仓库的 Room 实现，接口契约见 `_contracts/memory-diary-repositories.md`。
 *
 * 每天一行（`date` 唯一索引），[observeAll] 按日期倒序；删除 / 撤销删除都交给
 * 日期排序摆回原位（不是按删除时的下标）。
 *
 * [appendSegment] 不在 [DiaryRepository] 接口里：界面只读日记，写入入口是阶段 3
 * 的 `write_diary` 工具，它拿到的是这个具体类型。
 */
class RoomDiaryRepository(
    private val database: AppDatabase,
) : DiaryRepository {

    private val dao get() = database.whaleDiaryDao()

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)

    override fun observeAll(): Flow<List<DiaryEntry>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    override suspend fun restore(entry: DiaryEntry) {
        database.withTransaction {
            // 已经在了（重复撤销）就不动；按日期排序由查询保证，不需要记位置。
            if (dao.getById(entry.id) != null) return@withTransaction
            dao.upsert(entry.toEntity())
        }
    }

    override suspend fun clearAll() {
        dao.deleteAll()
    }

    /**
     * 把一段 `[HH:mm] 文本` 追加进 [date] 当天的日记；当天还没有日记就新建一行。
     *
     * 供阶段 3 的 `write_diary` 工具调用。[time] 接受 `HH:mm` / `HH:mm:ss`，
     * 也容忍带方括号的写法，落库前统一成 `HH:mm`。
     */
    /**
     * 直接覆盖某天的整篇内容（导入日记用：合并逻辑在外面算好，这里只负责落库）。
     * 传空串相当于清空内容（不删行）。
     */
    suspend fun setContent(date: LocalDate, content: String) {
        val dateKey = date.format(dateFormatter)
        database.withTransaction {
            val now = System.currentTimeMillis()
            val existing = dao.getByDate(dateKey)
            if (existing == null) {
                dao.upsert(
                    DiaryEntryEntity(
                        id = UUID.randomUUID().toString(),
                        date = dateKey,
                        content = content,
                        weather = "",
                        createdAt = date.startOfDayMillis(),
                        updatedAt = now,
                    )
                )
            } else {
                dao.updateContent(id = existing.id, content = content, weather = existing.weather, updatedAt = now)
            }
        }
    }

    suspend fun appendSegment(date: LocalDate, time: String, text: String) {
        val dateKey = date.format(dateFormatter)
        val segment = "[${normalizeTime(time)}] $text"
        database.withTransaction {
            val now = System.currentTimeMillis()
            val existing = dao.getByDate(dateKey)
            if (existing == null) {
                dao.upsert(
                    DiaryEntryEntity(
                        id = UUID.randomUUID().toString(),
                        date = dateKey,
                        content = segment,
                        weather = "",
                        createdAt = date.startOfDayMillis(),
                        updatedAt = now,
                    )
                )
            } else {
                val content = if (existing.content.isBlank()) {
                    segment
                } else {
                    existing.content.trimEnd() + "\n" + segment
                }
                dao.updateContent(
                    id = existing.id,
                    content = content,
                    weather = existing.weather,
                    updatedAt = now,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Entity ↔ 模型
// ---------------------------------------------------------------------------

private fun DiaryEntryEntity.toModel(): DiaryEntry = DiaryEntry(
    id = id,
    date = LocalDate.parse(date, DATE_FORMATTER),
    weather = weather,
    content = content,
)

private fun DiaryEntry.toEntity(): DiaryEntryEntity = DiaryEntryEntity(
    id = id,
    date = date.format(DATE_FORMATTER),
    content = content,
    weather = weather,
    // 模型里没有创建时间：日记的时间点由 date 决定，取当天 0 点。
    createdAt = date.startOfDayMillis(),
    updatedAt = System.currentTimeMillis(),
)

private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)

private fun LocalDate.startOfDayMillis(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun normalizeTime(time: String): String {
    val cleaned = time.trim().removePrefix("[").removeSuffix("]").trim()
    return runCatching {
        LocalTime.parse(cleaned).format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault(cleaned)
}
