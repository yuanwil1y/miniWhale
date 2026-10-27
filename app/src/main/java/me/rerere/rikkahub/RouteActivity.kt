package me.rerere.rikkahub

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker
import me.rerere.rikkahub.data.db.MigrationState
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.ui.activity.SafeModeActivity
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.CrashHandler
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import whaleui.nightsky.ltd.ui.WhaleRoot

private const val EXTRA_CONVERSATION_ID = "conversationId"

/**
 * 通知 / 前台服务点进来用的 Intent。
 *
 * S11 起原版聊天页已删，不管是哪个会话的通知，一律回到小鲸鱼的聊天页；[conversationId] 只用来让 extra 非空。
 */
fun conversationIntent(context: Context, conversationId: String): Intent =
    Intent(context, RouteActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(EXTRA_CONVERSATION_ID, conversationId)
    }

/**
 * 唯一的界面入口。
 *
 * S11：RikkaHub 原版的导航图（原版聊天页、历史、助手、原版设置等 40 多个页面）全部删除，
 * 这里只显示我们自己的 [WhaleRoot]（聊天 + 抽屉 + 记忆 + 定时 + 设置 + 高级设置 + 权限，页面切换在它内部完成）。
 */
class RouteActivity : ComponentActivity() {
    private val okHttpClient by inject<OkHttpClient>()

    // 通知点进来时让 WhaleRoot 切回聊天页；CONFLATED：Compose 还没开始收集时先存着
    private val openChatRequests = Channel<Unit>(Channel.CONFLATED)
    private val openChatFlow = openChatRequests.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 小鲸鱼的界面始终是深色（WhaleUITheme 只有深色一套），系统栏图标不管系统深浅色都用浅色；
        // 否则系统浅色模式下状态栏是深色图标，压在深色背景上看不清
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        disableNavigationBarContrast()
        super.onCreate(savedInstanceState)
        if (CrashHandler.hasCrashed(this)) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        setContent {
            // 外层主题固定深色：它会按深浅色改系统栏图标颜色，跟随系统的话会把上面的浅色图标改回深色
            RikkahubTheme(colorMode = ColorMode.DARK) {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .crossfade(true)
                        // round5：表情动图同屏可能很多，内存缓存给个明确上限，避免 OOM
                        .memoryCache {
                            coil3.memory.MemoryCache.Builder()
                                .maxSizePercent(context, 0.15)
                                .build()
                        }
                        .components {
                            add(
                                OkHttpNetworkFetcherFactory(
                                    callFactory = { okHttpClient },
                                    cacheStrategy = { CacheControlCacheStrategy() },
                                )
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                add(AnimatedImageDecoder.Factory())
                            } else {
                                add(GifDecoder.Factory())
                            }
                            add(SvgDecoder.Factory(scaleToDensity = true))
                        }
                        .build()
                }
                AppContent()
            }
        }
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        // 只剩通知 / 前台服务这一种带会话 id 的 Intent（分享、选中文字等外部入口在 S3 / S7 已删）。
        // S11 之前非她的会话会打开原版聊天页；现在原版页面都删了，统一进她的聊天页。
        if (intent.getStringExtra(EXTRA_CONVERSATION_ID) != null) {
            openChatRequests.trySend(Unit)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun AppContent() {
        val eventBus = koinInject<AppEventBus>()
        LaunchedEffect(Unit) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ChatGenerationEnded -> Unit // 由 ChatNotificationManager 消费
                }
            }
        }
        val migrationState by DatabaseMigrationTracker.state.collectAsStateWithLifecycle()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .background(MaterialTheme.colorScheme.background)
        ) {
            WhaleRoot(openChatRequests = openChatFlow)
            // debug 包顶部的标记（实现只在 src/debug，其它构建是空实现）
            whaleui.nightsky.ltd.debug.WhaleDebugBanner(Modifier.align(Alignment.TopCenter))
            AnimatedVisibility(
                visible = migrationState is MigrationState.Migrating,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                val state = migrationState as? MigrationState.Migrating
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.db_migrating),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (state != null) {
                            Text(
                                text = "v${state.from} → v${state.to}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
