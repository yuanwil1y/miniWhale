package whaleui.nightsky.ltd.tools

import me.rerere.ai.core.Tool
import whaleui.nightsky.ltd.sticker.StickerAvailability
import whaleui.nightsky.ltd.sticker.StickerTools
import whaleui.nightsky.ltd.voice.WhaleVoiceTools

/**
 * 小鲸鱼**全部工具**的汇总入口。
 *
 * 只有一个地方列举「她能用哪些工具」，避免三处（ChatToolFactory、debug 的工具自检、以后的调试面板）
 * 各列一遍、慢慢跑偏：
 *
 * | 工具 | 来源 |
 * |---|---|
 * | `write_diary` / `read_diary` / `remember` | [WhaleTools]（阶段 3，自建表） |
 * | `search_web` / `read_webpage` | [WhaleSearchTools]（阶段 5；`DeepSeekSearchService` 在 S9b 从原生 :search 搬到 tools/deepseek） |
 * | `hot_list` / `weather` / `daily_poem` | [WhaleLifeToolWrappers]（阶段 5，自建 Kotlin 工具） |
 * | `text_to_speech` / `draw` / `check_voice_quota` | [WhaleVoiceTools]（阶段 7，自建 MiniMax 客户端） |
 * | `find_sticker` / `send_sticker` | [StickerTools]（round5，只在有可用表情时注册） |
 *
 * 注意：`draw` / `check_voice_quota` 这类依赖 MiniMax Key 的工具在没有 Key 时**依然存在**，
 * 执行时返回中文错误 JSON，这样她不会因为「工具不存在」而换一种说法。
 *
 * 表情是例外：**没有任何可用表情时这两个工具根本不注册**，她就不知道有表情这回事（任务书 3.4）。
 *
 * `create` 是 suspend 的：表情是否可用要读一次数据库。每轮只调一次，代价可以忽略。
 */
class WhaleToolset(
    private val whaleTools: WhaleTools,
    private val searchTools: WhaleSearchTools?,
    private val lifeWrappers: WhaleLifeToolWrappers?,
    private val voiceTools: WhaleVoiceTools?,
    private val alarmTools: WhaleAlarmTools? = null,
    private val stickerTools: StickerTools? = null,
    private val stickerAvailability: StickerAvailability? = null,
) {

    /** [deepSeekKey] 与对话用的是同一个 Key（任务书 5.1）。 */
    suspend fun create(deepSeekKey: String?): List<Tool> = buildList {
        addAll(whaleTools.create())
        alarmTools?.let { addAll(it.create()) }
        lifeWrappers?.let { addAll(it.create()) }
        searchTools?.let { addAll(it.create(deepSeekKey)) }
        voiceTools?.let { addAll(it.create()) }
        // round5 3.4：没有可用表情时不注册 find_sticker / send_sticker
        val stickersAvailable = stickerAvailability?.get()?.available ?: true
        if (stickerTools != null && stickersAvailable) {
            addAll(stickerTools.create())
        }
    }

    /** 只用来给 debug 的工具自检找名字。 */
    suspend fun find(name: String, deepSeekKey: String?): Tool? =
        create(deepSeekKey).find { it.name == name }
}
