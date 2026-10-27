package whaleui.nightsky.ltd.ui.drawer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import whaleui.nightsky.ltd.domain.chat.ChatListRepository

/**
 * 启动时选出要打开的对话（现在只有和她的一个）。界面只读 [uiState]。
 * 抽屉的对话列表交互已删（C），这里不再有搜索 / 菜单 / 改名 / 删除状态。
 */
class ChatListViewModel(
    private val repository: ChatListRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatListUiState(isLoading = true))
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            var items = repository.loadConversations()
            if (items.isEmpty()) {
                items = listOf(repository.createConversation("新对话"))
            }
            val sorted = sortConversations(items)
            _uiState.value = ChatListUiState(
                conversations = sorted,
                currentId = sorted.firstOrNull { it.id == 1L }?.id ?: sorted.firstOrNull()?.id,
                isLoading = false,
            )
        }
    }
}
