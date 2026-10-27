package whaleui.nightsky.ltd.di

import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import androidx.room.withTransaction
import whaleui.nightsky.ltd.data.RikkaChatListRepository
import whaleui.nightsky.ltd.ai.WhaleGuardTransformer
import whaleui.nightsky.ltd.alarm.AlarmTurnRunner
import whaleui.nightsky.ltd.alarm.WhaleAlarmScheduler
import whaleui.nightsky.ltd.alarm.WhaleHerAlarmScheduler
import whaleui.nightsky.ltd.alarm.WhaleAlarmTurnRunner
import whaleui.nightsky.ltd.alarm.WhaleScheduleEngine
import whaleui.nightsky.ltd.data.HerAlarmRepository
import whaleui.nightsky.ltd.data.MiniMaxVoiceCatalog
import whaleui.nightsky.ltd.domain.settings.PersonaStore
import whaleui.nightsky.ltd.domain.settings.VoiceCatalog
import whaleui.nightsky.ltd.data.RoomScheduleRepository
import whaleui.nightsky.ltd.domain.settings.SettingsRepository
import whaleui.nightsky.ltd.domain.settings.SecretStore
import whaleui.nightsky.ltd.data.ChatServiceBackend
import whaleui.nightsky.ltd.data.RikkaChatRepository
import whaleui.nightsky.ltd.data.RoomDiaryRepository
import whaleui.nightsky.ltd.data.RoomMemoryRepository
import whaleui.nightsky.ltd.prompt.WhaleSystemPrompt
import whaleui.nightsky.ltd.domain.memory.DiaryRepository
import whaleui.nightsky.ltd.domain.schedule.ScheduleEngine
import whaleui.nightsky.ltd.domain.schedule.ScheduleRepository
import whaleui.nightsky.ltd.domain.memory.MemoryRepository
import whaleui.nightsky.ltd.minimax.WhaleMiniMaxClient
import whaleui.nightsky.ltd.tools.WhaleAlarmTools
import whaleui.nightsky.ltd.tools.WhaleDailyLimit
import whaleui.nightsky.ltd.tools.WhaleDailyLimitStore
import whaleui.nightsky.ltd.tools.WhaleLifeTools
import whaleui.nightsky.ltd.tools.WhaleLifeToolWrappers
import whaleui.nightsky.ltd.tools.WhaleSearchTools
import whaleui.nightsky.ltd.tools.WhaleToolset
import whaleui.nightsky.ltd.tools.WhaleTools
import whaleui.nightsky.ltd.voice.WhaleVoiceTools
import whaleui.nightsky.ltd.whale.WhaleSecrets
import whaleui.nightsky.ltd.whale.WhaleVoicePrefs
import whaleui.nightsky.ltd.domain.chat.ChatRepository
import whaleui.nightsky.ltd.ui.chat.ChatViewModel
import whaleui.nightsky.ltd.domain.chat.ChatListRepository
import whaleui.nightsky.ltd.ui.drawer.ChatListViewModel
import whaleui.nightsky.ltd.whale.WhaleBootstrap
import whaleui.nightsky.ltd.whale.WhaleConversationProvider
import whaleui.nightsky.ltd.whale.WhaleProviderKeys
import whaleui.nightsky.ltd.whale.WhaleRuntimeStore

/**
 * 我们自己的 Koin 模块（`whaleui.nightsky.ltd`）。
 *
 * 依赖只从 Koin 来（W-043）：这里注册小鲸鱼的全部仓库、工具、调度器和 ViewModel，
 * 界面用 `koinViewModel()` 取，Receiver / Worker 用 `KoinComponent` 取；没有别的组装点。
 * 我们的定义通过 `get()` 取 RikkaHub 模块里的 `ChatService` / `SettingsStore` /
 * `ConversationRepository`；反过来 RikkaHub 的 `ChatService` 也会取这里的 `WhaleBatchScheduler`。
 *
 * 注册点在 `RikkaHubApp.onCreate` 的 `startKoin { modules(...) }` 里（加一个 `whaleModule`）。
 */
val whaleModule = module {

    single { WhaleRuntimeStore(androidContext()) }

    // 密钥的唯一入口：Keystore 加密实现
    single<SecretStore> { whaleui.nightsky.ltd.data.EncryptedSecretStore(androidContext()) }

    // 人设文件（soul.md / user.md）
    single<PersonaStore> { whaleui.nightsky.ltd.data.FilePersonaStore(androidContext()) }

    // 非敏感设置（音色 / 语速 / 免打扰 / 主动消息开关）
    single<SettingsRepository> { whaleui.nightsky.ltd.data.DataStoreSettingsRepository(androidContext()) }

    // 她的名字和头像（资料页「编辑」）：同一个 whale_settings DataStore；头像文件在 files/whale/her_avatar/
    single<whaleui.nightsky.ltd.domain.settings.HerProfileStore> { whaleui.nightsky.ltd.data.dataStoreHerProfileStore(androidContext()) }
    single<whaleui.nightsky.ltd.domain.settings.HerAvatarStorage> {
        whaleui.nightsky.ltd.data.HerAvatarFiles(
            filesDir = androidContext().filesDir,
            source = whaleui.nightsky.ltd.data.uploads.AndroidUploadSource(androidContext().applicationContext),
        )
    }

    // 权限与后台常驻状态（真实系统检查）：设置页的「N 项未开启」和权限页共用这一个实例
    single<whaleui.nightsky.ltd.domain.settings.PermissionStatusProvider> {
        whaleui.nightsky.ltd.data.SystemPermissionStatusProvider(androidContext(), get())
    }

    // 导出全部数据 / 一键清空：和界面用的是同一批仓库实例
    single<whaleui.nightsky.ltd.domain.settings.ExportService> {
        whaleui.nightsky.ltd.data.FileExportService(
            context = androidContext(),
            memoryRepository = get(),
            diaryRepository = get(),
            personaStore = get(),
            chatListRepository = get(),
            chatRepository = get(),
            stickerRepository = get(),
        )
    }
    single {
        whaleui.nightsky.ltd.data.DataResetService(
            personaStore = get(),
            memoryRepository = get(),
            diaryRepository = get(),
            stickerRepository = get(),
        )
    }

    // 系统提示词拼装：SOUL + 记忆 + 常驻技能（有表情时再加 sticker 技能与情绪分类行）
    single { WhaleSystemPrompt(androidContext(), get(), get(), get()) }

    // 每轮注入（报时 / REMINDER / 语音提醒 / 表情提醒 / 防复读；闹钟轮走另一条分支）
    single {
        WhaleGuardTransformer(
            readPrompt = whaleui.nightsky.ltd.prompt.WhaleGuard.assetsPromptReader(androidContext()),
            stickerAvailability = get(),
        )
    }

    // 决定 5：只带最近 40–50 轮（按块滑动，保前缀缓存）
    single { whaleui.nightsky.ltd.ai.WhaleHistoryLimiter() }

    single { WhaleConversationProvider(get(), get(), get()) }

    // 导入聊天记录（导出包 chat/*.md → 固定对话）；设置页触发
    single {
        whaleui.nightsky.ltd.data.ChatBundleImporter(
            conversations = get(),
            conversationRepository = get(),
            chatService = get(),
        )
    }

    single { WhaleProviderKeys(secretStore = get(), settingsStore = get()) }

    single { WhaleBootstrap(androidContext(), get(), get(), get(), get(), get(), get(), get()) }

    single<ChatListRepository> { RikkaChatListRepository(get()) }

    // 记忆与日记：真实 Room 仓库（阶段 3.1）。界面只认接口，写入口是 RoomDiaryRepository.appendSegment。
    single { RoomMemoryRepository(get()) }
    single<MemoryRepository> { get<RoomMemoryRepository>() }
    single { RoomDiaryRepository(get()) }
    single<DiaryRepository> { get<RoomDiaryRepository>() }

    // ---- 她的全部工具（阶段 3/5/7）----
    single { WhaleTools(get(), get()) }
    single { WhaleDailyLimit(store = WhaleDailyLimitStore(androidContext())) }
    single {
        val settingsStore = get<me.rerere.rikkahub.data.datastore.SettingsStore>()
        WhaleSearchTools(
            whaleui.nightsky.ltd.tools.deepseek.DeepSeekSearchService,
            get(),
            // N-007：搜索 / 读网页跟随高级设置里 DeepSeek 的 Base URL（只取主机）
            deepSeekBaseUrl = {
                (settingsStore.settingsFlow.value.providers
                    .firstOrNull { it.id == whaleui.nightsky.ltd.whale.WhaleBootstrap.DEEPSEEK_PROVIDER_ID }
                    as? me.rerere.ai.provider.ProviderSetting.OpenAI)?.baseUrl
            },
        )
    }
    single { WhaleLifeToolWrappers(WhaleLifeTools(get(), get(), homeCityProvider = { whaleui.nightsky.ltd.whale.WhaleHomeCity.value })) }
    single {
        WhaleMiniMaxClient(
            apiKeyProvider = { WhaleSecrets.miniMax },
            okHttpClient = get(),
        )
    }
    // 真实音色目录（任务书 7.5）：静态内置 + 可选在线刷新，试听走真实合成
    single<VoiceCatalog> {
        MiniMaxVoiceCatalog(
            context = androidContext(),
            client = get(),
            voiceIdProvider = { WhaleVoicePrefs.voiceId },
            speedProvider = { WhaleVoicePrefs.speed },
        )
    }
    single {
        WhaleVoiceTools(
            client = get(),
            filesDir = androidContext().filesDir,
            voiceIdProvider = { WhaleVoicePrefs.voiceId },
            speedProvider = { WhaleVoicePrefs.speed },
            // 决定 7：画她自己时把她的头像当角色参考图（data URI）；没设自定义头像时不带参考图
            avatarDataUriProvider = {
                val path = get<whaleui.nightsky.ltd.domain.settings.HerProfileStore>().profile.first().avatarPath
                whaleui.nightsky.ltd.voice.AvatarReference.dataUriOf(path)
            },
        )
    }
    // ---- 定时任务与她自己定的闹钟（阶段 6）----
    single { RoomScheduleRepository(get()) }
    single<ScheduleRepository> { get<RoomScheduleRepository>() }
    single { HerAlarmRepository(get<me.rerere.rikkahub.data.db.AppDatabase>().whaleHerAlarmDao()) }
    single {
        WhaleHerAlarmScheduler(
            context = androidContext(),
            herAlarms = get(),
            settings = get<SettingsRepository>(),
            runner = get<AlarmTurnRunner>(),
        )
    }
    single {
        WhaleAlarmTools(
            herAlarms = get(),
            onScheduled = { get<WhaleHerAlarmScheduler>().schedulePending() },
        )
    }

    // 「助手发起一轮」的执行入口（调度层只认这个接口）
    single<AlarmTurnRunner> { WhaleAlarmTurnRunner(get(), get(), get()) }

    // 精确闹钟调度层（任务书 6.3）
    single {
        WhaleAlarmScheduler(
            context = androidContext(),
            repository = get<RoomScheduleRepository>(),
            settings = get<SettingsRepository>(),
            runner = get<AlarmTurnRunner>(),
        )
    }
    single { WhaleScheduleEngine(get(), get()) }
    single<ScheduleEngine> { get<WhaleScheduleEngine>() }

    // ---- 表情包（round5）----
    // 两张表 + filesDir/stickers 下的文件；导入、删除、可用性判断都在这一层
    single {
        val database = get<me.rerere.rikkahub.data.db.AppDatabase>()
        whaleui.nightsky.ltd.sticker.RoomStickerRepository(
            filesDir = androidContext().filesDir,
            dao = database.whaleStickerDao(),
            transaction = whaleui.nightsky.ltd.sticker.StickerTransactionRunner { block ->
                database.withTransaction { block() }
            },
        )
    }
    single<whaleui.nightsky.ltd.sticker.StickerRepository> {
        get<whaleui.nightsky.ltd.sticker.RoomStickerRepository>()
    }
    single<whaleui.nightsky.ltd.sticker.StickerThumbnailer> {
        whaleui.nightsky.ltd.sticker.AndroidStickerThumbnailer(androidContext().filesDir)
    }
    single {
        whaleui.nightsky.ltd.sticker.StickerImporter(
            filesDir = androidContext().filesDir,
            repository = get(),
            thumbnailer = get(),
        )
    }
    single { whaleui.nightsky.ltd.sticker.StickerAvailability(get()) }
    single { whaleui.nightsky.ltd.sticker.StickerTools(get(), androidContext().filesDir) }

    // 连发合并的计时器（round5 3.7）：待发批次本身由 ChatService 用它和自己的 isGenerating 组装，
    // 这样批次不用反过来向 Koin 要 ChatService（原来靠 GlobalContext 打破的循环依赖就没有了）
    single<whaleui.nightsky.ltd.whale.WhaleBatchScheduler> {
        whaleui.nightsky.ltd.whale.CoroutineWhaleBatchScheduler(scope = get<me.rerere.rikkahub.AppScope>())
    }

    // 闹钟重排入口（开机 / 时区 / 权限变化 / 权限页回来）
    single { whaleui.nightsky.ltd.alarm.WhaleAlarmReschedule(get(), get()) }

    // ChatToolFactory 和 debug 的工具自检命令都从这里取工具列表
    single { WhaleToolset(get(), get(), get(), get(), get(), get(), get()) }

    single<ChatRepository> {
        val toolset = get<WhaleToolset>()
        RikkaChatRepository(
            ChatServiceBackend(get(), get()),
            // 调试命令：src/debug 里是真的，其它构建类型是空实现（R2）
            debugCommands = whaleui.nightsky.ltd.debug.whaleDebugChatCommands(androidContext()) { name ->
                toolset.find(name, WhaleSecrets.deepSeek)
            },
            uploads = get(),
        )
    }

    // 附件复制进私有目录（F5）：发送页选附件和仓库兜底复制共用一个
    single { whaleui.nightsky.ltd.data.uploads.androidChatUploads(androidContext()) }

    viewModel {
        ChatViewModel(
            androidApplication(),
            repository = get(),
            stickerRepository = get(),
            uploads = get(),
        )
    }

    viewModel { ChatListViewModel(get()) }

    viewModel { whaleui.nightsky.ltd.ui.memory.MemoryViewModel(get(), get()) }

    viewModel {
        val context = androidContext()
        whaleui.nightsky.ltd.ui.schedule.ScheduleViewModel(
            repository = get(),
            engine = get(),
            // 她自己定的一次性闹钟（任务书 4.5）：只读显示在「今天的时间线」里
            herAlarms = get(),
            canScheduleExact = { whaleui.nightsky.ltd.alarm.WhaleAlarmRegistrar.canScheduleExact(context) },
        )
    }

    viewModel {
        whaleui.nightsky.ltd.ui.settings.SettingsViewModel(
            application = androidApplication(),
            secretStore = get(),
            settingsRepository = get(),
            personaStore = get(),
            voiceCatalog = get(),
            exportService = get(),
            memoryRepository = get(),
            resetService = get(),
            permissionStatusProvider = get(),
            diaryRepository = get(),
            chatBundleImporter = get(),
        )
    }

    viewModel {
        whaleui.nightsky.ltd.ui.permissions.PermissionsViewModel(
            application = androidApplication(),
            provider = get(),
            settingsRepository = get(),
            alarmReschedule = get(),
            appScope = get<me.rerere.rikkahub.AppScope>(),
        )
    }

    // 她的资料页（B）：只读状态 + token 热力图（读现成的消息节点，不加表）
    viewModel {
        val context = androidContext()
        whaleui.nightsky.ltd.ui.profile.ProfileViewModel(
            personaStore = get(),
            settingsRepository = get(),
            permissionStatusProvider = get(),
            tokenSource = whaleui.nightsky.ltd.data.WhaleTokenEntries(
                conversations = get(),
                runtimeStore = get(),
            ),
            skillInstalled = {
                "goutoujunshi" in whaleui.nightsky.ltd.whale.WhaleBootstrap.ENABLED_SKILLS &&
                    context.assets.open("builtin_skills/goutoujunshi/SKILL.md").bufferedReader().use { it.readText() }.isNotBlank()
            },
        )
    }

    viewModel { whaleui.nightsky.ltd.ui.profile.ProfileEditViewModel(store = get(), avatars = get()) }

    viewModel {
        whaleui.nightsky.ltd.ui.sticker.StickerManagerViewModel(
            appContext = androidContext(),
            repository = get(),
            importer = get(),
        )
    }

    // 高级设置（S9a / D2）：供应商、模型、代理、日志。ChatService 等到真的要改模型时再取。
    viewModel {
        val scope = this
        whaleui.nightsky.ltd.ui.advanced.AdvancedSettingsViewModel(
            settingsStore = get(),
            secretStore = get(),
            miniMaxConfig = whaleui.nightsky.ltd.ui.advanced.WhaleMiniMaxConfigStore,
            remoteModels = whaleui.nightsky.ltd.ui.advanced.ProviderRemoteModelLister(providerManager = get(), providerKeys = get()),
            chatTarget = whaleui.nightsky.ltd.ui.advanced.ChatServiceWhaleChatTarget(
                runtimeStore = get(),
                conversations = get(),
                chatService = { scope.get<me.rerere.rikkahub.service.ChatService>() },
            ),
        )
    }
}
