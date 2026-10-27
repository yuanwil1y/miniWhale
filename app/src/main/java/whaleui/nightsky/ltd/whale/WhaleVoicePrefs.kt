package whaleui.nightsky.ltd.whale

/**
 * 语音合成用的音色 / 语速内存缓存。
 *
 * `SettingsRepository.settings` 是 `Flow`，而 `WhaleVoiceTools` 的音色/语速提供者是同步的 `() -> T`。
 * 所以启动时把设置读一次放进这里，设置页改动时同步更新；真正合成时只读缓存。
 */
object WhaleVoicePrefs {

    @Volatile
    var voiceId: String = DEFAULT_VOICE_ID
        private set

    @Volatile
    var speed: Float = 1.0f
        private set

    fun update(voiceId: String?, speed: Float?) {
        voiceId?.takeIf { it.isNotBlank() }?.let { this.voiceId = it }
        speed?.let { this.speed = it }
    }

    private const val DEFAULT_VOICE_ID = "female-shaonv"
}