package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.QuotaQuery
import kotlin.uuid.Uuid

val DEFAULT_AUTO_MODEL_ID = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08")

/**
 * 新装时的内置供应商：只有 DeepSeek。
 *
 * 小鲸鱼的聊天只走 DeepSeek（语音、画图走 App 自己的 MiniMax 客户端，不在这张表里）。
 * 上游 RikkaHub 在这里还内置了 OpenAI、AiHubMix、硅基流动、OpenRouter 等十几家（其中几家是上游的
 * 合作 / 推广渠道），开源版不再预置。
 *
 * 老设置不受影响：规整化（`SettingsNormalization.withBuiltInDefaults`）只补缺失的内置项、不删多出来的，
 * 以前写进盘里的那些条目（包括用户填过的 Key、加过的模型）读进来、写回去都原样保留（SettingsStoreTest 钉住）。
 * B5 去掉的 Google 类型「Gemini」（6ab18148-…）和 Claude 类型「MiniMax」（b4deabea-…）同理。
 */
val DEFAULT_PROVIDERS = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b"),
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "",
        builtIn = true,
        quotaQuery = QuotaQuery(
            enabled = true,
            url = "{{baseUrl}}/user/balance",
            resultPath = "balance_infos[0].total_balance"
        )
    ),
)
