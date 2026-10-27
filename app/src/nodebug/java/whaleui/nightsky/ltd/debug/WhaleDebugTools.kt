package whaleui.nightsky.ltd.debug

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import whaleui.nightsky.ltd.ui.chat.ChatViewModel

// ---------------------------------------------------------------------------
// 非 debug 构建（release、baselineprofile 的 nonMinifiedRelease / benchmarkRelease 等）用的空实现。
// 真正的调试入口在 src/debug/…/WhaleDebugTools.kt；两份签名必须一致。
// 这个目录由 app/build.gradle.kts 的 androidComponents.onVariants 加给所有非 debug 变体。
// ---------------------------------------------------------------------------

@Suppress("UNUSED_PARAMETER")
fun installWhaleDebugTools(app: Application) = Unit

@Suppress("UNUSED_PARAMETER")
@Composable
fun WhaleDebugRootEffect(chatViewModel: ChatViewModel, onShowChat: () -> Unit) = Unit

@Suppress("UNUSED_PARAMETER")
@Composable
fun WhaleDebugBanner(modifier: Modifier = Modifier) = Unit
