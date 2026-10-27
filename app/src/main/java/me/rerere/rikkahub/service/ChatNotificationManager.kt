package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.sendNotification
import whaleui.nightsky.ltd.domain.settings.HerProfileStore
import kotlin.uuid.Uuid

/**
 * 订阅 [AppEventBus] 上的聊天生成结束事件，App 在后台时发生成完成通知。
 *
 * S11：只服务原版设置开关（默认关闭）的 Live Update 进度通知已删，POST_PROMOTED_NOTIFICATIONS 一并去掉；
 * 生成期间的常驻通知由 [ChatGenerationForegroundService] 自己负责。
 */
class ChatNotificationManager(
    private val context: Application,
    appScope: AppScope,
    eventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    /** 她的名字和头像（资料页「编辑」）：通知标题用她的昵称，大图标用她的头像 */
    private val herProfile: HerProfileStore,
) {
    private val isForeground = MutableStateFlow(false)

    init {
        // ProcessLifecycleOwner 要求在主线程注册观察者
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> isForeground.value = true
                        Lifecycle.Event.ON_STOP -> isForeground.value = false
                        else -> {}
                    }
                }
            )
        }
        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ChatGenerationEnded -> handleGenerationEnded(event)
                    else -> {}
                }
            }
        }
    }

    private suspend fun handleGenerationEnded(event: AppEvent.ChatGenerationEnded) {
        val contentPreview = event.contentPreview ?: return
        if (isForeground.value) return
        if (!settingsStore.settingsFlow.value.displaySetting.enableNotificationOnMessageGeneration) return
        // App 只有她一个对话：标题用界面上的昵称（不是助手内部的名字），头像设了就当大图标
        val her = herProfile.profile.first()
        sendGenerationDoneNotification(event.conversationId, her.nickname, her.avatarPath, contentPreview)
    }

    private fun sendGenerationDoneNotification(
        conversationId: Uuid,
        senderName: String,
        avatarPath: String?,
        contentPreview: String
    ) {
        val avatar = avatarPath?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            largeIcon = avatar
            content = contentPreview
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            conversationIntent(context, conversationId.toString()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
