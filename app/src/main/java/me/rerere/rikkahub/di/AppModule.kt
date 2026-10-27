package me.rerere.rikkahub.di

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.ChatRequestFactory
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.ChatNotificationManager
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        AppScope()
    }



    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
            herProfile = get(),
        )
    }

    single {
        ChatToolFactory(
            context = get(),
            memoryRepository = get(),
            conversationRepository = get(),
            skillManager = get(),
            whaleToolset = get(),
        )
    }

    single {
        ChatRequestFactory(
            context = get(),
            providerManager = get(),
            usageRecorder = get(),
            chatToolFactory = get(),
            memoryRepository = get(),
            templateTransformer = get(),
            whalePrompt = get(),
            whaleGuard = get(),
            whaleHistory = get(),
            providerKeys = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            requestFactory = get(),
            filesManager = get(),
            whaleBatchScheduler = get(),
        )
    }


}
