package whaleui.nightsky.ltd.ui

import whaleui.nightsky.ltd.ui.components.LocalBulletins
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import org.koin.compose.koinInject
import whaleui.nightsky.ltd.ui.chat.ChatViewModel
import whaleui.nightsky.ltd.whale.WhaleGate

// ---------------------------------------------------------------------------
// 根界面上的订阅：生命周期、事件总线、一次性提示（R1 / W-051 从 WhaleRoot 拆出来）
// ---------------------------------------------------------------------------

/**
 * 「一条一条发」的打断规则 + 主动消息补显（任务书第三点五节）：
 * 1) App 退到后台 / 离开聊天页：剩下的立即算作已显示，回来不重播；
 * 2) 闹钟轮不经过 ChatRepository 的事件流：生成结束（[AppEvent.ChatGenerationEnded]）时把库里新出现的消息补进列表
 *    （前台按节奏一条条显示，后台回来直接全部可见）。
 */
@Composable
internal fun WhaleChatSyncEffects(chatViewModel: ChatViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                chatViewModel.onAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val eventBus = koinInject<AppEventBus>()
    LaunchedEffect(Unit) {
        eventBus.events.collect { event ->
            if (event is AppEvent.ChatGenerationEnded) {
                val ourConversation = WhaleGate.conversationId
                val endedConversation = event.conversationId
                if (ourConversation != null && endedConversation == ourConversation) {
                    val foreground = lifecycleOwner.lifecycle.currentState
                        .isAtLeast(Lifecycle.State.RESUMED)
                    chatViewModel.syncNewMessages(animated = foreground)
                }
            }
        }
    }
}

/** 聊天页的一次性提示（附件太大、格式不支持……）：发一条 Bulletin 后清掉。 */
@Composable
internal fun ChatNoticeToast(notice: String?, onConsumed: () -> Unit) {
    val bulletins = LocalBulletins.current
    LaunchedEffect(notice) {
        if (notice != null) {
            bulletins.show(notice)
            onConsumed()
        }
    }
}
