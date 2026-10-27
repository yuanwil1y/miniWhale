package whaleui.nightsky.ltd.ui.memory

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import whaleui.nightsky.ltd.domain.memory.DiaryEntry
import whaleui.nightsky.ltd.domain.memory.DiaryRepository
import whaleui.nightsky.ltd.domain.memory.MemoryCategory
import whaleui.nightsky.ltd.domain.memory.MemoryRepository

/**
 * 记忆与日记页的数据 / 交互状态机。
 *
 * 界面只读 [uiState]，所有动作都从这里走；以后接真实存储时只替换两个 Repository 实现。
 * 删除的「先移除动画、再弹撤销」节奏也放在这里：仓库先删，等一次 item 动画的
 * 入场时间后再把撤销提示放到状态里。
 */
class MemoryViewModel(
    private val memoryRepository: MemoryRepository,
    private val diaryRepository: DiaryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                memoryRepository.observeAll(),
                diaryRepository.observeAll(),
            ) { memories, diary -> memories to diary }
                .collect { (memories, diary) ->
                    _uiState.update {
                        it.copy(allMemories = memories, diary = diary, isLoading = false)
                    }
                }
        }
    }

    // -----------------------------------------------------------------------
    // 页面 / 标签 / 筛选
    // -----------------------------------------------------------------------

    /**
     * 从抽屉进入页面。需求：默认落在「记忆」标签。
     * 弹层一起收起，避免上次离开时的临时状态被带进来。
     */
    fun enterPage() {
        _uiState.update {
            it.copy(
                tab = MemoryTab.Memory,
                editor = null,
                menu = null,
                diaryMenu = null,
                categoryTargetId = null,
            )
        }
    }

    /** 从抽屉的「她最近的日记」卡片进入：落在日记标签，并把那一篇展开（最新一篇本来就在最上面）。 */
    fun enterDiary(entryId: String) {
        enterPage()
        _uiState.update { it.copy(tab = MemoryTab.Diary, expandedDiaryIds = it.expandedDiaryIds + entryId) }
    }

    fun selectTab(tab: MemoryTab) {
        if (_uiState.value.tab == tab) return
        _uiState.update {
            it.copy(
                tab = tab,
                menu = null,
                diaryMenu = null,
            )
        }
    }

    fun selectCategory(category: MemoryCategory?) {
        _uiState.update { it.copy(category = category, menu = null, diaryMenu = null) }
    }

    // -----------------------------------------------------------------------
    // 编辑 / 新建
    // -----------------------------------------------------------------------

    fun openCreateEditor() {
        _uiState.update {
            it.copy(
                editor = MemoryEditor(),
                menu = null,
                diaryMenu = null,
                categoryTargetId = null,
            )
        }
    }

    fun openEditor(memoryId: String) {
        val memory = _uiState.value.memory(memoryId) ?: return
        _uiState.update {
            it.copy(
                editor = MemoryEditor(
                    memoryId = memory.id,
                    content = memory.content,
                    category = memory.category,
                ),
                menu = null,
                diaryMenu = null,
                categoryTargetId = null,
            )
        }
    }

    fun setEditorContent(content: String) {
        _uiState.update { state ->
            val editor = state.editor ?: return@update state
            state.copy(editor = editor.copy(content = content))
        }
    }

    fun setEditorCategory(category: MemoryCategory) {
        _uiState.update { state ->
            val editor = state.editor ?: return@update state
            state.copy(editor = editor.copy(category = category))
        }
    }

    fun closeEditor() {
        _uiState.update { it.copy(editor = null) }
    }

    fun saveEditor() {
        val state = _uiState.value
        val editor = state.editor ?: return
        val content = editor.content.trim()
        if (content.isEmpty()) return
        val id = editor.memoryId
        _uiState.update { it.copy(editor = null) }
        viewModelScope.launch {
            if (id == null) {
                memoryRepository.add(content = content, category = editor.category)
                _uiState.update { it.copy(scrollToTopTick = it.scrollToTopTick + 1) }
            } else {
                memoryRepository.update(id = id, content = content, category = editor.category)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 菜单 / 改分类 / 删除 / 撤销
    // -----------------------------------------------------------------------

    fun showMenu(memoryId: String, touch: Offset) {
        if (_uiState.value.memory(memoryId) == null) return
        _uiState.update {
            it.copy(
                menu = MemoryMenu(memoryId = memoryId, touch = touch),
                diaryMenu = null,
                editor = null,
            )
        }
    }

    fun dismissMenu() {
        _uiState.update { it.copy(menu = null) }
    }

    fun showDiaryMenu(entryId: String, touch: Offset) {
        if (_uiState.value.diary.none { it.id == entryId }) return
        _uiState.update {
            it.copy(
                diaryMenu = DiaryMenu(entryId = entryId, touch = touch),
                menu = null,
                editor = null,
            )
        }
    }

    fun dismissDiaryMenu() {
        _uiState.update { it.copy(diaryMenu = null) }
    }

    fun requestCategoryChange(memoryId: String) {
        if (_uiState.value.memory(memoryId) == null) return
        _uiState.update { it.copy(menu = null, categoryTargetId = memoryId) }
    }    fun cancelCategoryChange() {
        _uiState.update { it.copy(categoryTargetId = null) }
    }

    fun changeCategory(category: MemoryCategory) {
        val state = _uiState.value
        val id = state.categoryTargetId ?: return
        val target = state.memory(id) ?: return
        _uiState.update { it.copy(categoryTargetId = null) }
        if (target.category == category) return
        viewModelScope.launch {
            memoryRepository.update(id = id, content = target.content, category = category)
        }
    }

    /** 删除记忆：仓库先移掉（列表走 item 的移除动画），150ms 后再放撤销提示。 */
    fun deleteMemory(memoryId: String) {
        val state = _uiState.value
        val index = state.allMemories.indexOfFirst { it.id == memoryId }
        if (index < 0) return
        val memory = state.allMemories[index]
        _uiState.update { it.copy(menu = null) }
        viewModelScope.launch {
            memoryRepository.delete(memoryId)
            delay(DeleteToastDelayMs)
            if (_uiState.value.allMemories.none { it.id == memoryId }) {
                _uiState.update { it.copy(undo = MemoryUndo.MemoryRemoved(memory, index)) }
            }
        }
    }

    /** 删除日记：同上，先移除再给撤销提示。 */
    fun deleteDiary(entry: DiaryEntry) {
        _uiState.update { it.copy(diaryMenu = null) }
        viewModelScope.launch {
            diaryRepository.delete(entry.id)
            delay(DeleteToastDelayMs)
            if (_uiState.value.diary.none { it.id == entry.id }) {
                _uiState.update { it.copy(undo = MemoryUndo.DiaryRemoved(entry)) }
            }
        }
    }

    fun undoDelete() {
        val undo = _uiState.value.undo ?: return
        _uiState.update { it.copy(undo = null) }
        viewModelScope.launch {
            when (undo) {
                is MemoryUndo.MemoryRemoved ->
                    memoryRepository.restore(undo.memory, undo.index)

                is MemoryUndo.DiaryRemoved ->
                    diaryRepository.restore(undo.entry)
            }
        }
    }

    /** 4 秒到了，收起撤销提示（由界面计时后回调）。 */
    fun clearUndo() {
        _uiState.update { it.copy(undo = null) }
    }

    // -----------------------------------------------------------------------
    // 日记展开 / 收起
    // -----------------------------------------------------------------------

    fun toggleDiary(id: String) {
        _uiState.update { state ->
            val expanded = state.expandedDiaryIds
            state.copy(
                expandedDiaryIds = if (id in expanded) expanded - id else expanded + id,
            )
        }
    }

    private companion object {
        /** 让移除动画先跑起来，再弹撤销提示。聊天页删消息用的是同一个节奏。 */
        const val DeleteToastDelayMs = 150L
    }
}
