package whaleui.nightsky.ltd.data

import whaleui.nightsky.ltd.ui.components.BulletinKind
import whaleui.nightsky.ltd.ui.components.BulletinBus
import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import whaleui.nightsky.ltd.minimax.WhaleMiniMaxClient
import java.io.File
import kotlin.coroutines.resume
import whaleui.nightsky.ltd.domain.settings.VoiceCatalog
import whaleui.nightsky.ltd.domain.settings.VoiceOption

/**
 * 真实的音色目录（任务书 7.5）：**静态内置的常用中文音色 + 可选在线刷新**。
 *
 * - 列表：先用 MiniMax 官方的系统音色 ID 写死一份（顺序固定，`id` 要持久化进设置，不能随便改）；
 *   没有 Key 时也能正常显示，只是试听会提示先填 Key。
 * - 刷新：[refresh] 调 `get_voice` 拿服务端音色；成功就合并（保留内置的常用项在前，新音色追加在后）。
 * - 试听：[preview] 用当前音色 + 设置里的语速**真实合成一句短话**再播；播完才返回。
 *   没 Key / 合成失败时弹一条提示（「先在设置里填 MiniMax Key」），不抛异常、不挂住界面。
 * - 旧 id 兜底：设置里存的 id 不在列表里时，`voice(id)` 返回 null，界面回退到第一个（`SettingsViewModel` 已有兜底）。
 */
class MiniMaxVoiceCatalog(
    context: Context,
    private val client: WhaleMiniMaxClient,
    private val voiceIdProvider: () -> String = { "female-shaonv" },
    private val speedProvider: () -> Float = { 1.0f },
) : VoiceCatalog {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var refreshed: List<VoiceOption> = emptyList()

    @Volatile
    private var active: PreviewSession? = null

    override val voices: List<VoiceOption>
        get() = (BuiltinVoices + refreshed).distinctBy { it.id }

    override fun voice(id: String): VoiceOption? = voices.firstOrNull { it.id == id }

    /** 在线刷新（`get_voice`）。失败时保持内置列表，返回 false。 */
    suspend fun refresh(): Boolean {
        val result = client.listVoices().getOrNull().orEmpty()
        if (result.isEmpty()) return false
        refreshed = result.map { voice ->
            VoiceOption(
                id = voice.id,
                name = voice.name.ifBlank { voice.id },
                style = "MiniMax",
                traits = listOf("在线"),
            )
        }
        return true
    }

    override suspend fun preview(voiceId: String) {
        val option = voice(voiceId) ?: return
        val text = "你好，这是音色试听。"
        val audio = client.synthesize(
            text = text,
            voiceId = option.id,
            speed = speedProvider(),
        ).getOrElse { error ->
            // U0：全局 Bulletin（不再用系统 Toast）；BulletinBus 线程安全，不用切主线程
            BulletinBus.post(
                if (error is IllegalStateException) "先在设置里填 MiniMax Key" else "试听失败了：${error.message ?: "未知原因"}",
                BulletinKind.Error,
            )
            return
        }

        val dir = File(appContext.cacheDir, "whale_voice_preview").apply { mkdirs() }
        val file = File(dir, "preview_${option.id}.mp3")
        runCatching { file.writeBytes(audio.mp3) }
            .onFailure { return }

        withContext(Dispatchers.Main.immediate) {
            stopPreviewInternal()
            val player = runCatching {
                MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                }
            }.getOrNull() ?: return@withContext

            val session = PreviewSession(player)
            synchronized(lock) { active = session }
            suspendCancellableCoroutine<Unit> { continuation ->
                session.continuation = continuation
                continuation.invokeOnCancellation { mainHandler.post { releaseSession(session) } }
                player.setOnCompletionListener { completeSession(session) }
                player.setOnErrorListener { _, _, _ ->
                    completeSession(session)
                    true
                }
                runCatching { player.start() }
                    .onFailure { completeSession(session) }
            }
        }
    }

    override fun stopPreview() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopPreviewInternal()
        } else {
            mainHandler.post { stopPreviewInternal() }
        }
    }

    // ------------------------------------------------------------------ 播放

    private class PreviewSession(val player: MediaPlayer) {
        var continuation: CancellableContinuation<Unit>? = null
    }

    private fun stopPreviewInternal() {
        val session = synchronized(lock) { active.also { active = null } } ?: return
        releaseSession(session)
    }

    private fun completeSession(session: PreviewSession) {
        synchronized(lock) { if (active === session) active = null }
        releasePlayer(session.player)
        resumeWaiter(session)
    }

    private fun releaseSession(session: PreviewSession) {
        synchronized(lock) { if (active === session) active = null }
        releasePlayer(session.player)
        resumeWaiter(session)
    }

    private fun resumeWaiter(session: PreviewSession) {
        val continuation = session.continuation ?: return
        session.continuation = null
        if (continuation.isActive) runCatching { continuation.resume(Unit) }
    }

    private fun releasePlayer(player: MediaPlayer) {
        runCatching { player.setOnCompletionListener(null) }
        runCatching { player.setOnErrorListener(null) }
        runCatching { if (player.isPlaying) player.stop() }
        runCatching { player.reset() }
        runCatching { player.release() }
    }

    private companion object {
        /**
         * MiniMax 官方系统音色里常用的中文女声/男声。
         * 顺序固定；`id` 会存进设置，改名可以，改 id 会让老设置失效（有兜底）。
         */
        val BuiltinVoices: List<VoiceOption> = listOf(
            VoiceOption("female-shaonv", "少女音色", "清亮", listOf("中文", "日常")),
            VoiceOption("female-yujie", "御姐音色", "成熟", listOf("中文", "日常")),
            VoiceOption("female-chengshu", "成熟女性", "稳重", listOf("中文", "讲述")),
            VoiceOption("female-tianmei", "甜美女性", "甜", listOf("中文", "撒娇")),
            VoiceOption("male-qn-qingse", "青涩青年", "年轻", listOf("中文", "日常")),
            VoiceOption("male-qn-jingying", "精英青年", "干净", listOf("中文", "播报")),
            VoiceOption("male-qn-badao", "霸道青年", "低沉", listOf("中文", "深夜")),
            VoiceOption("male-qn-daxuesheng", "青年大学生", "清朗", listOf("中文", "日常")),
        )
    }
}
