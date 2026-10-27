package me.rerere.rikkahub.di

import android.content.Context
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.AIRequestInterceptor
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.remote.ModelsDevCatalogLoader
import me.rerere.rikkahub.data.remote.SettingsProxyAuthenticator
import me.rerere.rikkahub.data.remote.SettingsProxySelector
import me.rerere.rikkahub.data.remote.SettingsSocks5Authenticator
import okhttp3.OkHttpClient
import org.koin.dsl.module
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    single {
        val context: Context = get()
        AppDatabaseFactory.create(context)
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        val engine = PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
        // 消息模板按助手 ID 缓存，内容取自设置，设置变了就让已编译的模板失效
        val settingsStore = get<SettingsStore>()
        get<AppScope>().launch {
            settingsStore.settingsFlow.collect { engine.templateCache.invalidateAll() }
        }
        engine
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().usageRecordDao()
    }

    single {
        UsageRecorder(dao = get(), scope = get<AppScope>())
    }

    single {
        MessageFtsManager(get())
    }

    single<OkHttpClient> {
        val settingsStore: SettingsStore = get()
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        // N-007：代理密码在 SecretStore（运行时读 WhaleSecrets 缓存）；启动迁移完成前退回设置里的旧明文
        val proxyPassword = {
            whaleui.nightsky.ltd.whale.WhaleSecrets.proxyPassword
                ?: settingsStore.settingsFlow.value.networkSetting.proxyPassword
        }
        java.net.Authenticator.setDefault(SettingsSocks5Authenticator(settingsStore, proxyPassword))
        val initialNetworkSetting = settingsStore.settingsFlow.value.networkSetting
        val appliedProxySetting = AtomicReference(
            Triple(
                initialNetworkSetting.proxyUrl,
                initialNetworkSetting.proxyUsername,
                proxyPassword(),
            )
        )
        lateinit var client: OkHttpClient
        client = OkHttpClient.Builder()
            .proxySelector(SettingsProxySelector(settingsStore))
            .proxyAuthenticator(SettingsProxyAuthenticator(settingsStore, proxyPassword))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val networkSetting = settingsStore.settingsFlow.value.networkSetting
                val currentProxySetting = Triple(
                    networkSetting.proxyUrl,
                    networkSetting.proxyUsername,
                    proxyPassword(),
                )
                if (appliedProxySetting.getAndSet(currentProxySetting) != currentProxySetting) {
                    client.connectionPool.evictAll()
                }

                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader("Accept-Language", acceptLang)

                if (originalRequest.header("User-Agent") == null) {
                    val userAgent = settingsStore.settingsFlow.value.networkSetting.userAgent
                        .trim()
                        .ifEmpty { "whale-Android/${BuildConfig.VERSION_NAME}" }
                    requestBuilder.addHeader("User-Agent", userAgent)
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addInterceptor(AIRequestInterceptor())
            // 应用层：断网 / DNS 失败的请求也能记下来（高级设置 → 日志）
            .addInterceptor(RequestLoggingInterceptor())
            // logcat 请求日志只在 debug 挂（W-013），脱敏规则见 HttpLogging.kt
            .apply { buildHttpLoggingInterceptor(BuildConfig.DEBUG)?.let { addInterceptor(it) } }
            .build()
        // 她的 search_web / read_webpage 用 App 的客户端与 LRU Key 轮询（S9b 之前是 :search 的 SearchService.init）
        client.also { whaleui.nightsky.ltd.tools.deepseek.DeepSeekSearchService.init(it, get()) }
    }

    single {
        ModelsDevCatalogLoader(context = get())
    }

    single {
        ProviderManager(
            client = get(),
            context = get(),
        )
    }
}
