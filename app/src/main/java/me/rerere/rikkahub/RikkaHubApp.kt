package me.rerere.rikkahub

import android.app.Application
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.remote.ModelsDevCatalogLoader
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import org.koin.android.ext.android.get
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import whaleui.nightsky.ltd.di.whaleModule
import java.io.File

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"

class RikkaHubApp : Application(), androidx.work.Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        // N-001：:ai 的日志默认关闭；debug 包打开，而且只打元信息（条数 / 长度 / 模型 / 耗时 / 状态码），不打正文
        me.rerere.ai.util.AiLog.enabled = BuildConfig.DEBUG
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            modules(appModule, dataSourceModule, repositoryModule, whaleModule)
        }
        val koin = getKoin()

        // W-009：闹钟冷启动进程时 bootstrap 还没跑完，先同步把上次记下的助手 / 固定对话 id 填进 WhaleGate
        koin.get<whaleui.nightsky.ltd.whale.WhaleRuntimeStore>().let { runtime ->
            whaleui.nightsky.ltd.whale.WhaleGate.restore(runtime.whaleAssistantId, runtime.fixedConversationId)
        }

        // 定时任务：启动时重排一次（Receiver / Worker 自己通过 KoinComponent 取调度器）
        val alarmScheduler = koin.get<whaleui.nightsky.ltd.alarm.WhaleAlarmScheduler>()
        val herAlarmScheduler = koin.get<whaleui.nightsky.ltd.alarm.WhaleHerAlarmScheduler>()
        get<AppScope>().launch {
            runCatching { alarmScheduler.scheduleAll() }
            runCatching { herAlarmScheduler.schedulePending() }
        }
        // 仅 debug：中文/emoji 没法用 adb input text 打进模拟器，用广播把文本注入输入框。
        // 实现在 src/debug；release 编译的是 src/nodebug 里的空实现（W-051）。
        whaleui.nightsky.ltd.debug.installWhaleDebugTools(this)
        // 首次启动初始化：补 deepseek-flash 模型、建「小鲸鱼」助手、关建议/标题、绑固定对话、同步 Key
        koin.get<whaleui.nightsky.ltd.whale.WhaleBootstrap>().let { bootstrap ->
            get<AppScope>().launch {
                runCatching { bootstrap.ensure() }
                // W-021：上次他发完消息进程就没了（待发批次只在内存），补一轮回复
                runCatching {
                    val conversationId = koin.get<whaleui.nightsky.ltd.whale.WhaleConversationProvider>()
                        .fixedConversation().id
                    get<me.rerere.rikkahub.service.ChatService>().resumeUnansweredReply(conversationId)
                }.onFailure { Log.w(TAG, "resumeUnansweredReply failed", it) }
            }
        }

        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // delete temp files
        deleteTempFiles()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // sync upload files to DB
        syncManagedFiles()

        // Extract builtin skills from assets after install/update
        extractBuiltinSkills()

        // 模型能力表（高级设置加模型 / 判断能否思考要用）：只读随包快照，不联网（W-066）
        loadModelsDevCatalog()

        // Increment launch count
        incrementLaunchCount()
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val count = get<SettingsStore>().incrementLaunchCount()
                Log.i(TAG, "incrementLaunchCount: $count")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun extractBuiltinSkills() {
        get<AppScope>().launch(Dispatchers.IO) {
            get<SkillManager>().ensureBuiltinSkillsExtracted()
        }
    }

    private fun loadModelsDevCatalog() {
        get<AppScope>().launch {
            get<ModelsDevCatalogLoader>().load()
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        // S11：原生记忆整理（MemoryDream）已删，清掉它留在系统里的通知渠道
        notificationManager.deleteNotificationChannel("memory_dream")
    }

    /**
     * WorkManager 的按需初始化——**唯一**的一套 WorkManager 配置（W-056）。
     *
     * manifest 里移除了默认的 `WorkManagerInitializer`，第一次调用 `WorkManager.getInstance(context)` 时
     * 由这里提供配置。以前 startKoin 里还有 Koin 的 `workManagerFactory()`：它在启动时就初始化 WorkManager，
     * 于是这里的配置从来没生效过；而且没有任何 Worker 注册在 Koin 里（[whaleui.nightsky.ltd.alarm.WhaleAlarmWorker]、
     * [me.rerere.rikkahub.service.ChatGenerationWorker] 用默认工厂按反射创建，自己通过 KoinComponent 取依赖），
     * 所以 B5 删掉了它和 koin-androidx-workmanager 依赖，只留这一套。
     */
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob() +
        Dispatchers.Main +
        CoroutineName("AppScope") +
        CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "AppScope exception", e)
        }
)
