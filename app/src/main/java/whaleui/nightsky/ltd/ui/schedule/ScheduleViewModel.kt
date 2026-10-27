package whaleui.nightsky.ltd.ui.schedule

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import whaleui.nightsky.ltd.domain.schedule.ScheduleEngine
import whaleui.nightsky.ltd.domain.schedule.ScheduleRepeat
import whaleui.nightsky.ltd.domain.schedule.ScheduleRepository
import whaleui.nightsky.ltd.domain.schedule.ScheduleTask
import whaleui.nightsky.ltd.domain.schedule.ScheduleTiming
import whaleui.nightsky.ltd.util.runCatchingCancellable

/**
 * 定时任务页的状态机。
 *
 * 时间线（今天哪些任务、各自状态、排序、「现在」标记位置、顶部副标题）全部在这里推导，
 * Repository 只存任务。任务有任何增删改、开关变化后都会调用 [ScheduleEngine.reschedule]。
 */
class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val engine: ScheduleEngine,
    /** 她自己定的一次性闹钟（只读展示用，决定 8 / 任务书 4.5） */
    private val herAlarms: whaleui.nightsky.ltd.data.HerAlarmRepository,
    /** 现在能不能登记精确闹钟（进页面 / 每分钟刷新一次）。 */
    private val canScheduleExact: () -> Boolean = { true },
) : ViewModel() {

    /** 今天她给自己定的闹钟：时间 + note 前 20 字 + 是否已发送。 */
    private suspend fun herAlarmRows(): List<HerAlarmRow> = runCatchingCancellable {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        herAlarms.observeAll().first()
            .filter { it.fireAt in start until end }
            .sortedBy { it.fireAt }
            .map { alarm ->
                val time = java.time.Instant.ofEpochMilli(alarm.fireAt).atZone(zone).toLocalTime()
                HerAlarmRow(
                    id = alarm.id,
                    timeLabel = "%02d:%02d".format(time.hour, time.minute),
                    sortTime = time,
                    notePreview = alarm.note.trim().take(20),
                    sent = alarm.fired,
                )
            }
    }.getOrDefault(emptyList())

    /** 今天以后她给自己定的闹钟（「接下来」分组）：「明天」+「09:00」+ note 前 20 字。 */
    private suspend fun upcomingRows(): List<HerAlarmRow> = runCatchingCancellable {
        val zone = java.time.ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        upcomingHerAlarmRows(herAlarms.pendingAfterToday(now), now, zone)
    }.getOrDefault(emptyList())

    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { tasks ->
                _uiState.update { it.copy(tasks = tasks, isLoading = false) }
                recompute()
                // 需求：任何增删改 / 开关变化后都要重新注册调度。
                engine.reschedule(tasks)
            }
        }
        viewModelScope.launch {
            // 「现在」每分钟更新一次。
            while (isActive) {
                val millisIntoMinute = System.currentTimeMillis() % 60_000L
                delay(60_000L - millisIntoMinute)
                recompute()
            }
        }
    }

    // -----------------------------------------------------------------------
    // 时间线推导
    // -----------------------------------------------------------------------

    private fun recompute() {
        val exact = runCatching(canScheduleExact).getOrDefault(true)
        if (_uiState.value.exactAlarmAllowed != exact) _uiState.update { it.copy(exactAlarmAllowed = exact) }
        recomputeNow()
        viewModelScope.launch { refreshHerAlarms() }
    }

    /** 她自己定的闹钟是另一张表，单独刷一次（只读）；刷完把合并时间线和「现在」线一起重算。 */
    private suspend fun refreshHerAlarms() {
        val rows = herAlarmRows()
        val upcoming = upcomingRows()
        _uiState.update { state ->
            val slots = buildTimelineSlots(state.timeline, rows)
            state.copy(
                herAlarms = rows,
                upcoming = upcoming,
                slots = slots,
                markerIndex = markerIndexFor(slots, state.now),
            )
        }
    }

    private fun recomputeNow() {
        val state = _uiState.value
        val zone = ZoneId.systemDefault()
        val nowDateTime = LocalDateTime.now(zone).truncatedToMinute()
        val nowTime = nowDateTime.toLocalTime()
        val today = nowDateTime.toLocalDate()
        val entries: List<ScheduleEntry> = state.tasks
            .mapIndexedNotNull { index, task ->
                if (!task.repeat.matches(today.dayOfWeek)) return@mapIndexedNotNull null
                index to toEntry(task, nowTime, today, zone)
            }
            .sortedWith(
                compareBy<Pair<Int, ScheduleEntry>> { it.second.sortTime }
                    .thenBy { it.first },
            )
            .map { it.second }

        // 任务和她自己定的闹钟合成同一条时间线（她那条也要有圆点 + 卡片，并按时间插对位置）
        val slots = buildTimelineSlots(entries, state.herAlarms)
        val summary = "她今天的一天 · ${weekdayLabel(today.dayOfWeek)} · 共 ${entries.size} 件"

        _uiState.update {
            it.copy(
                timeline = entries,
                slots = slots,
                now = nowTime,
                markerIndex = markerIndexFor(slots, nowTime),
                summary = summary,
                isLoading = false,
            )
        }
    }

    /** 「现在」线插在第一个比现在晚的格子之前；全过了就放到末尾。 */
    private fun markerIndexFor(slots: List<TimelineSlot>, nowTime: LocalTime): Int =
        slots.indexOfFirst { it.sortTime > nowTime }.let { if (it < 0) slots.size else it }

    private fun toEntry(
        task: ScheduleTask,
        nowTime: LocalTime,
        today: LocalDate,
        zone: ZoneId,
    ): ScheduleEntry {
        val sortTime = when (val timing = task.timing) {
            is ScheduleTiming.Fixed -> timing.time
            is ScheduleTiming.Random -> task.todayPickedTime ?: timing.windowEnd
        }
        val sentToday = task.lastSentAt?.toLocalDate(zone) == today

        val status = when {
            !task.enabled -> ScheduleStatus.Closed
            sentToday -> ScheduleStatus.Sent
            nowTime > sortTime -> ScheduleStatus.Missed
            else -> ScheduleStatus.Waiting
        }

        val timeLabel = when (val timing = task.timing) {
            is ScheduleTiming.Fixed -> formatTime(timing.time)
            is ScheduleTiming.Random ->
                task.todayPickedTime?.let(::formatTime) ?: randomWindowLabel(timing.windowStart)
        }

        return ScheduleEntry(
            task = task,
            status = status,
            timeLabel = timeLabel,
            sortTime = sortTime,
            kindLabel = if (task.timing is ScheduleTiming.Random) "随机" else "固定",
        )
    }

    // -----------------------------------------------------------------------
    // 页面进入 / 返回
    // -----------------------------------------------------------------------

    /** 从抽屉进入：刷新「现在」、收起临时层，并让界面滚到「现在」附近。 */
    fun enterPage() {
        _uiState.update {
            it.copy(
                editor = null,
                menu = null,
                undo = null,
                scrollToNowTick = it.scrollToNowTick + 1,
            )
        }
        recompute()
    }

    // -----------------------------------------------------------------------
    // 开关 / 菜单
    // -----------------------------------------------------------------------

    fun toggleEnabled(taskId: String, enabled: Boolean) {
        if (_uiState.value.tasks.none { it.id == taskId }) return
        viewModelScope.launch { repository.setEnabled(taskId, enabled) }
    }

    fun showMenu(taskId: String, touch: Offset) {
        if (_uiState.value.tasks.none { it.id == taskId }) return
        _uiState.update { it.copy(menu = ScheduleMenu(taskId, touch), editor = null) }
    }

    fun dismissMenu() {
        _uiState.update { it.copy(menu = null) }
    }

    // -----------------------------------------------------------------------
    // 编辑 / 新建
    // -----------------------------------------------------------------------

    fun openEditor(taskId: String) {
        if (_uiState.value.tasks.none { it.id == taskId }) return
        _uiState.update { it.copy(menu = null) }
        val task = _uiState.value.tasks.first { it.id == taskId }
        val timing = task.timing
        val repeat = task.repeat
        _uiState.update {
            it.copy(
                editor = ScheduleEditor(
                    taskId = task.id,
                    title = task.title,
                    hint = task.hint.orEmpty(),
                    timingKind = when (timing) {
                        is ScheduleTiming.Fixed -> ScheduleTimingKind.Fixed
                        is ScheduleTiming.Random -> ScheduleTimingKind.Random
                    },
                    hour = (timing as? ScheduleTiming.Fixed)?.time?.hour ?: 9,
                    minute = (timing as? ScheduleTiming.Fixed)?.time?.minute ?: 0,
                    windowStartHour = (timing as? ScheduleTiming.Random)?.windowStart?.hour ?: 9,
                    windowStartMinute = (timing as? ScheduleTiming.Random)?.windowStart?.minute ?: 0,
                    windowEndHour = (timing as? ScheduleTiming.Random)?.windowEnd?.hour ?: 11,
                    windowEndMinute = (timing as? ScheduleTiming.Random)?.windowEnd?.minute ?: 0,
                    repeatKind = when (repeat) {
                        ScheduleRepeat.Daily -> ScheduleRepeatKind.Daily
                        ScheduleRepeat.Weekdays -> ScheduleRepeatKind.Weekdays
                        is ScheduleRepeat.Custom -> ScheduleRepeatKind.Custom
                    },
                    customDays = (repeat as? ScheduleRepeat.Custom)?.days ?: emptySet(),
                ),
            )
        }
    }

    fun openCreateEditor() {
        val next = nextWholeHour()
        _uiState.update {
            it.copy(
                menu = null,
                editor = ScheduleEditor(
                    taskId = null,
                    title = "",
                    hint = "",
                    timingKind = ScheduleTimingKind.Fixed,
                    hour = next.hour,
                    minute = 0,
                    windowStartHour = next.hour,
                    windowStartMinute = 0,
                    windowEndHour = ((next.hour + 2) % 24),
                    windowEndMinute = 0,
                    repeatKind = ScheduleRepeatKind.Daily,
                ),
            )
        }
    }

    fun setEditor(editor: ScheduleEditor) {
        _uiState.update { it.copy(editor = editor) }
    }

    fun closeEditor() {
        _uiState.update { it.copy(editor = null) }
    }

    fun saveEditor() {
        val state = _uiState.value
        val editor = state.editor ?: return
        if (!editor.canSave) return
        val existing = editor.taskId?.let { id -> state.tasks.firstOrNull { it.id == id } }

        val timing: ScheduleTiming = when (editor.timingKind) {
            ScheduleTimingKind.Fixed -> ScheduleTiming.Fixed(editor.selectedTime)
            ScheduleTimingKind.Random -> ScheduleTiming.Random(editor.windowStart, editor.windowEnd)
        }
        val repeat: ScheduleRepeat = when (editor.repeatKind) {
            ScheduleRepeatKind.Daily -> ScheduleRepeat.Daily
            ScheduleRepeatKind.Weekdays -> ScheduleRepeat.Weekdays
            ScheduleRepeatKind.Custom -> ScheduleRepeat.Custom(editor.customDays)
        }
        val title = editor.title.trim()
        val hint = editor.hint.trim().ifEmpty { null }

        _uiState.update { it.copy(editor = null) }
        viewModelScope.launch {
            if (existing == null) {
                repository.add(
                    ScheduleTask(
                        id = newScheduleTaskId(),
                        title = title,
                        hint = hint,
                        timing = timing,
                        repeat = repeat,
                        enabled = true,
                        lastSentAt = null,
                        todayPickedTime = null,
                    ),
                )
            } else {
                repository.update(
                    existing.copy(
                        title = title,
                        hint = hint,
                        timing = timing,
                        repeat = repeat,
                        // 随机任务换回固定时间后，今天挑好的时间没有意义。
                        todayPickedTime = if (timing is ScheduleTiming.Random) {
                            existing.todayPickedTime
                        } else {
                            null
                        },
                    ),
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // 删除 / 撤销
    // -----------------------------------------------------------------------

    /** 删除：仓库先移掉（时间线走 item 移除动画），150ms 后再放撤销提示。 */
    fun deleteTask(taskId: String) {
        val state = _uiState.value
        val index = state.tasks.indexOfFirst { it.id == taskId }
        if (index < 0) return
        val task = state.tasks[index]
        _uiState.update { it.copy(menu = null) }
        viewModelScope.launch {
            repository.delete(taskId)
            delay(DeleteToastDelayMs)
            if (_uiState.value.tasks.none { it.id == taskId }) {
                _uiState.update { it.copy(undo = ScheduleUndo(task, index)) }
            }
        }
    }

    fun undoDelete() {
        val undo = _uiState.value.undo ?: return
        _uiState.update { it.copy(undo = null) }
        viewModelScope.launch { repository.restore(undo.task, undo.index) }
    }

    /** 4 秒到了，收起撤销提示（界面计时后回调）。 */
    fun clearUndo() {
        _uiState.update { it.copy(undo = null) }
    }

    private companion object {
        const val DeleteToastDelayMs = 150L
    }
}
