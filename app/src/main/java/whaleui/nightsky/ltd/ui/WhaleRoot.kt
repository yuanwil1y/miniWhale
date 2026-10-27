package whaleui.nightsky.ltd.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import org.koin.compose.koinInject
import whaleui.nightsky.ltd.domain.settings.HerProfile
import whaleui.nightsky.ltd.domain.settings.HerProfileStore
import whaleui.nightsky.ltd.ui.components.LocalHerProfile
import whaleui.nightsky.ltd.ui.profile.ProfileEditScreen
import whaleui.nightsky.ltd.ui.profile.ProfileEditViewModel
import whaleui.nightsky.ltd.ui.components.LocalBulletins
import whaleui.nightsky.ltd.ui.components.BulletinState
import whaleui.nightsky.ltd.ui.components.BulletinHost
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import whaleui.nightsky.ltd.ui.chat.ChatScreen
import whaleui.nightsky.ltd.ui.chat.ChatViewModel
import whaleui.nightsky.ltd.ui.drawer.ChatListViewModel
import whaleui.nightsky.ltd.ui.drawer.DrawerScreen
import whaleui.nightsky.ltd.ui.drawer.drawerDiaryPreview
import whaleui.nightsky.ltd.ui.memory.MemoryScreen
import whaleui.nightsky.ltd.ui.memory.MemoryViewModel
import whaleui.nightsky.ltd.ui.permissions.PermissionsScreen
import whaleui.nightsky.ltd.ui.permissions.PermissionsViewModel
import whaleui.nightsky.ltd.ui.chat.herPresence
import whaleui.nightsky.ltd.ui.profile.ProfileScreen
import whaleui.nightsky.ltd.ui.profile.ProfileTarget
import whaleui.nightsky.ltd.ui.profile.ProfileViewModel
import whaleui.nightsky.ltd.ui.profile.herRows
import whaleui.nightsky.ltd.ui.schedule.ScheduleScreen
import whaleui.nightsky.ltd.ui.schedule.ScheduleViewModel
import whaleui.nightsky.ltd.ui.settings.SettingsScreen
import whaleui.nightsky.ltd.ui.settings.SettingsViewModel
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.ui.theme.WhaleUITheme

/**
 * 小鲸鱼的全部页面组织：聊天页 + 抽屉、记忆与日记、定时任务、设置、权限。
 *
 * 从原来的 `MainActivity` 里整段搬出来，作为一个普通 Composable，
 * 由 `RouteActivity` 直接显示（S11 起原版导航图已删，这里就是全部界面）。
 * [openChatRequests]：点通知进来时发一次，切回聊天页并收起抽屉。
 *
 * 拆分（R1 / W-051）：页面 id 与切页动画在 WhaleNavigation.kt，权限跳转在 WhalePermissionIntents.kt，
 * 文件选择器在 WhaleFilePickers.kt，生命周期 / 事件总线订阅在 WhaleRootEffects.kt，
 * 调试广播命令只在 src/debug（release 是 src/nodebug 里的空实现）。
 *
 * 「高级设置」只从设置页底部那一行进（D2：聊天页长按名字的隐藏入口已在 A 删掉）。
 * 抽屉入口按 Q4 五项：她的资料、记忆与日记、定时任务、表情包、设置；下面是「她最近的日记」卡片（C）。
 */
@Composable
fun WhaleRoot(openChatRequests: Flow<Unit> = emptyFlow()) {
    WhaleUITheme {
    // 全局 Bulletin（U0：替代系统 Toast 和各页自己的撤销条）
    val bulletins = remember { BulletinState() }
    // 她的名字和头像（资料页「编辑」里改）：所有显示她名字 / 头像的地方都从这里读，改了实时生效
    val herProfileStore: HerProfileStore = koinInject()
    val herProfile by herProfileStore.profile.collectAsStateWithLifecycle(HerProfile())
    CompositionLocalProvider(LocalBulletins provides bulletins, LocalHerProfile provides herProfile) {
    Box(Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var screen by rememberSaveable { mutableStateOf(ScreenChat) }

    val chatViewModel: ChatViewModel = koinViewModel()
    val chatState by chatViewModel.uiState.collectAsStateWithLifecycle()
    // 表情面板数据（round5 3.5）：系列 + 静态缩略图，由 ViewModel 从表情库读
    val stickerPacks by chatViewModel.stickerPacks.collectAsStateWithLifecycle()
    val chatListViewModel: ChatListViewModel = koinViewModel()
    val chatListState by chatListViewModel.uiState.collectAsStateWithLifecycle()
    // 记忆仓库和导出共用 Koin 里同一个单例，导出的内容才和页面一致。
    val memoryViewModel: MemoryViewModel = koinViewModel()
    val memoryState by memoryViewModel.uiState.collectAsStateWithLifecycle()
    val scheduleViewModel: ScheduleViewModel = koinViewModel()
    val scheduleState by scheduleViewModel.uiState.collectAsStateWithLifecycle()
    val settingsViewModel: SettingsViewModel = koinViewModel()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val permissionsViewModel: PermissionsViewModel = koinViewModel()
    val advancedViewModel: whaleui.nightsky.ltd.ui.advanced.AdvancedSettingsViewModel = koinViewModel()
    val advancedState by advancedViewModel.uiState.collectAsStateWithLifecycle()
    // 高级设置从哪里进来就回哪里（现在只有设置页底部一个入口，D2）
    var advancedReturn by rememberSaveable { mutableStateOf(ScreenChat) }
    val permissionsState by permissionsViewModel.uiState.collectAsStateWithLifecycle()
    val profileViewModel: ProfileViewModel = koinViewModel()
    val profileState by profileViewModel.uiState.collectAsStateWithLifecycle()
    val profileEditViewModel: ProfileEditViewModel = koinViewModel()
    val profileEditState by profileEditViewModel.uiState.collectAsStateWithLifecycle()
    // 编辑页「从相册选择」：系统照片选择器，只要图片
    val herAvatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) profileEditViewModel.pickAvatar(uri.toString())
    }
    // 设置页 / 权限页从哪里进来就回哪里（资料页的「人设」「Proactive Agent」两行会跳过去）
    var settingsReturn by rememberSaveable { mutableStateOf(ScreenChat) }
    var permissionsReturn by rememberSaveable { mutableStateOf(ScreenSettings) }
    // 表情包管理页同理（抽屉 / 表情面板空状态 / 设置「导入与数据」都能进）
    var stickersReturn by rememberSaveable { mutableStateOf(ScreenChat) }

    // 「一条一条发」的打断规则 + 主动消息补显（任务书第三点五节）
    WhaleChatSyncEffects(chatViewModel)
    // 仅 debug：广播命令通道（模拟器没法输入中文/emoji）；release 里是空实现
    whaleui.nightsky.ltd.debug.WhaleDebugRootEffect(chatViewModel, onShowChat = { screen = ScreenChat })

    // 抽屉里选中的对话 id 是唯一来源；聊天页只按它加载自己的消息。
    LaunchedEffect(chatListState.currentId) {
        chatListState.currentId?.let(chatViewModel::openConversation)
    }

    val chatPickers = rememberChatPickers(chatViewModel)
    ChatNoticeToast(chatState.notice, onConsumed = chatViewModel::consumeNotice)
    val settingsPickers = rememberSettingsPickers(settingsViewModel, chatViewModel)

    val offsetPx = with(density) { 48.dp.toPx().toInt() }

    // 权限与后台常驻：系统设置跳转、通知权限申请（W-019 首次自动申请也在里面）
    val permissionActions = rememberWhalePermissionActions(permissionsViewModel, screen)

    fun navigateTo(target: String) {
        keyboardController?.hide()
        scope.launch {
            drawerState.close()
            screen = target
        }
    }

    LaunchedEffect(openChatRequests) {
        openChatRequests.collect { navigateTo(ScreenChat) }
    }

    fun openAdvanced(from: String) {
        advancedViewModel.enterPage()
        advancedReturn = from
        navigateTo(ScreenAdvanced)
    }

    fun leaveAdvanced() {
        // 高级设置里可能改了 Key，回设置页时刷新「已设置」状态
        if (advancedReturn == ScreenSettings) settingsViewModel.refreshSecretStatus()
        screen = advancedReturn
    }

    fun openSettings(from: String) {
        settingsViewModel.enterPage()
        settingsReturn = from
        navigateTo(ScreenSettings)
    }

    fun openPermissions(from: String) {
        permissionsViewModel.enterPage()
        permissionsReturn = from
        navigateTo(ScreenPermissions)
    }

    fun openStickers(from: String) {
        stickersReturn = from
        navigateTo(ScreenStickers)
    }

    fun openProfile() {
        profileViewModel.enterPage()
        navigateTo(ScreenProfile)
    }

    BackHandler(enabled = screen != ScreenChat) {
        if (screen == ScreenAdvanced) {
            leaveAdvanced()
            return@BackHandler
        }
        screen = when (screen) {
            ScreenSettings -> settingsReturn
            ScreenPermissions -> permissionsReturn
            ScreenStickers -> stickersReturn
            else -> backTargetOf(screen)
        }
    }
    BackHandler(enabled = screen == ScreenChat && drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = { whalePageTransition(offsetPx) },
        label = "pageTransition",
    ) { target ->
        when (target) {
            ScreenAdvanced -> whaleui.nightsky.ltd.ui.advanced.AdvancedSettingsScreen(
                state = advancedState,
                actions = advancedViewModel,
                onBack = { leaveAdvanced() },
            )

            ScreenProfile -> {
                // 状态行和聊天顶栏同一个来源（herPresence + 密钥内存缓存）
                val keyMissing = whaleui.nightsky.ltd.whale.WhaleSecrets.deepSeek == null
                ProfileScreen(
                    state = profileState,
                    title = herProfile.nickname,
                    presence = herPresence(chatState.isHerTyping, chatState.typingLabel, keyMissing),
                    rows = herRows(
                        profileState,
                        keyReady = !keyMissing,
                        assistantReady = whaleui.nightsky.ltd.whale.WhaleGate.assistantId != null,
                    ),
                    onBack = { screen = ScreenChat },
                    onOpenTarget = { target ->
                        when (target) {
                            ProfileTarget.Settings -> openSettings(ScreenProfile)
                            ProfileTarget.Permissions -> openPermissions(ScreenProfile)
                        }
                    },
                    onEdit = {
                        profileEditViewModel.start()
                        navigateTo(ScreenProfileEdit)
                    },
                )
            }

            ScreenProfileEdit -> ProfileEditScreen(
                state = profileEditState,
                onBack = { profileEditViewModel.requestBack { screen = ScreenProfile } },
                onSave = { profileEditViewModel.save { navigateTo(ScreenProfile) } },
                onNicknameChange = profileEditViewModel::setNickname,
                onOpenAvatarMenu = profileEditViewModel::openAvatarMenu,
                onCloseAvatarMenu = profileEditViewModel::closeAvatarMenu,
                onPickFromGallery = {
                    herAvatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onResetAvatar = profileEditViewModel::resetAvatar,
                onConfirmDiscard = { profileEditViewModel.discard { screen = ScreenProfile } },
                onDismissDiscard = profileEditViewModel::dismissDiscard,
                onNoticeShown = profileEditViewModel::consumeNotice,
            )

            ScreenStickers -> whaleui.nightsky.ltd.ui.sticker.StickerManagerEntry(
                onBack = { screen = stickersReturn },
            )

            ScreenMemory -> MemoryScreen(
                state = memoryState,
                onTabChange = memoryViewModel::selectTab,
                onFilterChange = memoryViewModel::selectCategory,
                onBack = { screen = ScreenChat },
                onAddMemory = memoryViewModel::openCreateEditor,
                onOpenMemory = memoryViewModel::openEditor,
                onShowMenu = memoryViewModel::showMenu,
                onDismissMenu = memoryViewModel::dismissMenu,
                onEditMemory = memoryViewModel::openEditor,
                onChangeCategory = memoryViewModel::requestCategoryChange,
                onDeleteMemory = memoryViewModel::deleteMemory,
                onDismissCategoryPicker = memoryViewModel::cancelCategoryChange,
                onSelectCategory = memoryViewModel::changeCategory,
                onShowDiaryMenu = memoryViewModel::showDiaryMenu,
                onDismissDiaryMenu = memoryViewModel::dismissDiaryMenu,
                onDeleteDiary = memoryViewModel::deleteDiary,
                onToggleDiary = memoryViewModel::toggleDiary,
                onEditorContentChange = memoryViewModel::setEditorContent,
                onEditorCategoryChange = memoryViewModel::setEditorCategory,
                onSaveEditor = memoryViewModel::saveEditor,
                onCloseEditor = memoryViewModel::closeEditor,
                onUndo = memoryViewModel::undoDelete,
                onUndoExpired = memoryViewModel::clearUndo,
            )

            ScreenSchedule -> ScheduleScreen(
                state = scheduleState,
                onBack = { screen = ScreenChat },
                onToggle = scheduleViewModel::toggleEnabled,
                onAddTask = scheduleViewModel::openCreateEditor,
                onOpenTask = scheduleViewModel::openEditor,
                onShowMenu = scheduleViewModel::showMenu,
                onDismissMenu = scheduleViewModel::dismissMenu,
                onEditFromMenu = scheduleViewModel::openEditor,
                onDeleteTask = scheduleViewModel::deleteTask,
                onEditorChange = scheduleViewModel::setEditor,
                onSaveEditor = scheduleViewModel::saveEditor,
                onCloseEditor = scheduleViewModel::closeEditor,
                onUndo = scheduleViewModel::undoDelete,
                onUndoExpired = scheduleViewModel::clearUndo,
            )

            ScreenSettings -> SettingsScreen(
                state = settingsState,
                onBack = { screen = settingsReturn },
                onScrolled = settingsViewModel::onScrolled,
                onOpenVoiceSheet = settingsViewModel::openVoiceSheet,
                onCloseOverlay = settingsViewModel::closeOverlay,
                onSelectVoice = settingsViewModel::selectVoice,
                onToggleVoicePreview = settingsViewModel::toggleVoicePreview,
                onSpeechRateChange = settingsViewModel::setSpeechRate,
                onImportPersona = settingsPickers.importPersona,
                onImportDiary = settingsPickers.importDiary,
                onImportMemory = settingsPickers.importMemory,
                onImportChat = settingsPickers.importChat,
                onOpenStickerPacks = { openStickers(ScreenSettings) },
                onHomeCityChange = settingsViewModel::setHomeCity,
                onOpenPersonaSheet = settingsViewModel::openPersonaSheet,
                onRequestRemovePersona = settingsViewModel::requestRemovePersona,
                onDismissRemovePersona = settingsViewModel::dismissRemovePersona,
                onConfirmRemovePersona = settingsViewModel::confirmRemovePersona,
                onUndoRemovePersona = settingsViewModel::undoRemovePersona,
                onProactiveChange = settingsViewModel::setProactiveEnabled,
                onOpenQuietHours = settingsViewModel::openQuietHoursSheet,
                onQuietStartChange = settingsViewModel::setQuietDraftStart,
                onQuietEndChange = settingsViewModel::setQuietDraftEnd,
                onSaveQuietHours = settingsViewModel::saveQuietHours,
                onExportAll = settingsPickers.exportAll,
                onResetClick = settingsViewModel::requestReset,
                onDismissReset = settingsViewModel::dismissReset,
                onConfirmReset = settingsViewModel::confirmReset,
                onPermissionsClick = {
                    permissionsViewModel.enterPage()
                    permissionsReturn = ScreenSettings
                    screen = ScreenPermissions
                },
                onOpenAdvanced = { openAdvanced(ScreenSettings) },
                onDismissToast = settingsViewModel::dismissToast,
                onOpenImportSheet = settingsViewModel::openImportSheet,
            )

            ScreenPermissions -> PermissionsScreen(
                state = permissionsState,
                onBack = { screen = permissionsReturn },
                onRequestPermission = { permission -> permissionActions.handle(permission) },
                onOpenBatterySettings = { permissionActions.openBatterySettings() },
                onKeepAliveChange = permissionsViewModel::setKeepAlive,
                onDismissToast = permissionsViewModel::dismissToast,
            )

            else -> Box(Modifier.fillMaxSize()) {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    scrimColor = WhaleTheme.colors.scrimDrawer,
                    drawerContent = {
                        val keyMissing = whaleui.nightsky.ltd.whale.WhaleSecrets.deepSeek == null
                        DrawerScreen(
                            title = herProfile.nickname,
                            presence = herPresence(chatState.isHerTyping, chatState.typingLabel, keyMissing),
                            diary = remember(memoryState.diary) { drawerDiaryPreview(memoryState.diary) },
                            onProfileClick = { openProfile() },
                            onMemoryClick = {
                                memoryViewModel.enterPage()
                                navigateTo(ScreenMemory)
                            },
                            onScheduleClick = {
                                scheduleViewModel.enterPage()
                                navigateTo(ScreenSchedule)
                            },
                            onStickersClick = { openStickers(ScreenChat) },
                            onSettingsClick = { openSettings(ScreenChat) },
                            onDiaryClick = { preview ->
                                memoryViewModel.enterDiary(preview.entryId)
                                navigateTo(ScreenMemory)
                            },
                        )
                    },
                ) {
                    key(chatListState.currentId) {
                        ChatScreen(
                            state = chatState,
                            onDraftChange = chatViewModel::onDraftChange,
                            onSend = chatViewModel::send,
                            onPickImages = chatPickers.pickImages,
                            onPickFiles = chatPickers.pickFiles,
                            onRemoveAttachment = chatViewModel::removeAttachment,
                            onLoadOlder = chatViewModel::loadOlder,
                            onPlayVoice = chatViewModel::toggleVoice,
                            onMenuClick = {
                                keyboardController?.hide()
                                scope.launch { drawerState.open() }
                            },
                            onOpenProfile = {
                                keyboardController?.hide()
                                openProfile()
                            },
                            // 现有状态：启动时 / 保存或清除密钥时刷新的内存缓存
                            keyMissing = whaleui.nightsky.ltd.whale.WhaleSecrets.deepSeek == null,
                            onReply = chatViewModel::replyTo,
                            onCancelReply = chatViewModel::cancelReply,
                            onDelete = chatViewModel::delete,
                            onUndoDelete = chatViewModel::undoDelete,
                            stickerPacks = stickerPacks,
                            onSendSticker = chatViewModel::sendSticker,
                            onOpenStickerSettings = { openStickers(ScreenChat) },
                        )
                    }
                }
            }
        }
    }
    BulletinHost(bulletins)
    }
    }
    }
}
