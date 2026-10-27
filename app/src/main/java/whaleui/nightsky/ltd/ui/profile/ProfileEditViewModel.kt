package whaleui.nightsky.ltd.ui.profile

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import whaleui.nightsky.ltd.domain.settings.AvatarImport
import whaleui.nightsky.ltd.domain.settings.HerAvatarStorage
import whaleui.nightsky.ltd.domain.settings.HerProfile
import whaleui.nightsky.ltd.domain.settings.HerProfileStore

/** 资料页「编辑」的界面状态：草稿只在内存里，点 ✓ 才写盘。 */
@Immutable
data class ProfileEditUiState(
    /** 已经保存的值（进页面时读一次） */
    val saved: HerProfile = HerProfile(),
    /** 输入框里的文字；名字还是默认值时留空，占位文字显示 `default` */
    val nickname: String = "",
    /** 草稿头像；null = 默认白色 */
    val avatarPath: String? = null,
    val loaded: Boolean = false,
    /** 正在处理选中的图片 */
    val busy: Boolean = false,
    /** 「放弃修改？」对话框 */
    val confirmDiscard: Boolean = false,
    /** 「设置新头像」的选项弹层 */
    val avatarMenuOpen: Boolean = false,
    /** 一次性提示（图片读不出来之类），显示完清掉 */
    val notice: String? = null,
) {
    val dirty: Boolean
        get() = loaded && (HerProfile.normalizeNickname(nickname) != saved.nickname || avatarPath != saved.avatarPath)

    /** 剩余字数；离上限还远时为 null（不显示计数）。 */
    val remaining: Int?
        get() = (HerProfile.MAX_NICKNAME_LENGTH - HerProfile.lengthOf(nickname))
            .takeIf { it <= HerProfile.COUNTER_THRESHOLD }
}

/**
 * 她的名字和头像的编辑页（资料页顶栏的铅笔进来，Telegram「编辑资料」的样子）。
 *
 * - 进页面 [start]：读当前保存的值做草稿；
 * - 改名字、换头像、恢复默认都只改草稿；
 * - [save]（顶栏 ✓）一次写进 [HerProfileStore]，然后清掉没用上的头像文件；
 * - 返回时有改动先问「放弃修改？」，放弃就删掉这次选了但没保存的图。
 */
class ProfileEditViewModel(
    private val store: HerProfileStore,
    private val avatars: HerAvatarStorage,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileEditUiState())
    val uiState: StateFlow<ProfileEditUiState> = _uiState.asStateFlow()

    fun start() {
        viewModelScope.launch {
            val current = store.profile.first()
            _uiState.value = ProfileEditUiState(
                saved = current,
                nickname = current.nickname.takeUnless { it == HerProfile.DEFAULT_NICKNAME }.orEmpty(),
                avatarPath = current.avatarPath,
                loaded = true,
            )
        }
    }

    fun setNickname(text: String) {
        // 单行：粘贴进来的换行当空格；超过上限的部分直接截掉
        val oneLine = text.replace('\n', ' ').replace('\r', ' ')
        _uiState.update { it.copy(nickname = HerProfile.clampInput(oneLine)) }
    }

    fun openAvatarMenu() = _uiState.update { it.copy(avatarMenuOpen = true) }

    fun closeAvatarMenu() = _uiState.update { it.copy(avatarMenuOpen = false) }

    fun pickAvatar(uri: String) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = withContext(io) { runCatching { avatars.import(uri) }.getOrElse { AvatarImport.Rejected("这张图读不出来") } }
            _uiState.update { state ->
                when (result) {
                    is AvatarImport.Ok -> state.copy(avatarPath = result.path, busy = false)
                    is AvatarImport.Rejected -> state.copy(notice = result.reason, busy = false)
                }
            }
        }
    }

    fun resetAvatar() = _uiState.update { it.copy(avatarPath = null) }

    fun consumeNotice() = _uiState.update { it.copy(notice = null) }

    /** 顶栏 ✓：写盘，清掉没用上的图片，然后 [onDone]（回资料页）。 */
    fun save(onDone: () -> Unit) {
        val state = _uiState.value
        if (!state.loaded || state.busy) return
        viewModelScope.launch {
            store.save(state.nickname, state.avatarPath)
            withContext(io) { avatars.retainOnly(state.avatarPath) }
            _uiState.value = ProfileEditUiState()
            onDone()
        }
    }

    /** 返回键 / 顶栏返回：没改过直接走，改过先问。 */
    fun requestBack(onLeave: () -> Unit) {
        if (_uiState.value.dirty) {
            _uiState.update { it.copy(confirmDiscard = true) }
        } else {
            discard(onLeave)
        }
    }

    fun dismissDiscard() = _uiState.update { it.copy(confirmDiscard = false) }

    /** 放弃修改：删掉这次选了但没保存的图，然后 [onLeave]。 */
    fun discard(onLeave: () -> Unit) {
        val saved = _uiState.value.saved
        val loaded = _uiState.value.loaded
        viewModelScope.launch {
            if (loaded) withContext(io) { avatars.retainOnly(saved.avatarPath) }
            _uiState.value = ProfileEditUiState()
            onLeave()
        }
    }
}
