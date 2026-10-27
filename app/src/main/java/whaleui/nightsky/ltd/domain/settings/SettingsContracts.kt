package whaleui.nightsky.ltd.domain.settings

import android.net.Uri
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import java.time.LocalTime

// ---------------------------------------------------------------------------
// 设置页的数据层契约。
//
// 这一层只描述界面需要的动作和数据形状，真实实现（加密存储 / DataStore /
// 文件 / 导出）都在同包下面，以后接真实后端时替换实现即可，界面和 ViewModel 不动。
// ---------------------------------------------------------------------------

// ------------------------------ 密钥 ---------------------------------------

/**
 * 加密存储里的各项。id 用于持久化，不能改名（改名会让已存的值读不到）。
 *
 * [ProxyPassword]（N-007）：高级设置里的代理密码，以前明文存在设置 DataStore 里。它不是 API Key，
 * 设置页 / 高级设置的「Key」列表只列 [ApiKeys]。
 */
enum class SecretProvider(val id: String, val title: String) {
    DeepSeek("deepseek", "DeepSeek"),
    MiniMax("minimax", "MiniMax"),
    ProxyPassword("proxy_password", "代理密码"),
    ;

    companion object {
        /** 设置页、高级设置里作为「Key」展示和编辑的两项 */
        val ApiKeys: List<SecretProvider> = listOf(DeepSeek, MiniMax)
    }
}

/**
 * 本机加密存储的 API 密钥（和代理密码）。
 *
 * 约束：
 * - 只存 [SecretProvider] 里的几项，不存别的东西；
 * - 实现必须走 Android Keystore + AES-GCM（或等价的 Tink 方案），明文不落盘；
 * - 值不允许出现在日志、崩溃信息、导出文件里。
 */
interface SecretStore {
    /** 读出明文；没有设置过返回 null。 */
    suspend fun get(provider: SecretProvider): String?

    /**
     * 保存（覆盖）。空串视同清除。
     * 存不进去（Keystore 不可用、写盘失败）时返回 [SecretWriteResult.Failed]，不抛异常，原来的值不变（W-045）。
     */
    suspend fun set(provider: SecretProvider, value: String): SecretWriteResult

    /** 清除这一项；失败时同 [set]。 */
    suspend fun clear(provider: SecretProvider): SecretWriteResult

    /** 是否已经设置过（不需要解密，界面进页面时只问这个）。 */
    suspend fun has(provider: SecretProvider): Boolean
}

/** 写密钥的结果（W-045）：界面据此提示「已保存」或「保存失败，原因…」 */
sealed interface SecretWriteResult {
    data object Saved : SecretWriteResult

    /** [reason] 给用户看的原因，不含密钥内容 */
    data class Failed(val reason: String) : SecretWriteResult
}

// ------------------------------ 非敏感设置 ----------------------------------

/** 语速的可选范围与步进，界面和存储共用一份。 */
object SpeechRateRange {
    const val Min = 0.5f
    const val Max = 2.0f
    const val Step = 0.1f
    const val Default = 1.0f

    /** 夹到 [Min, Max] 并量化到 0.1 的整数倍，避免浮点尾数写进存储。 */
    fun normalize(value: Float): Float {
        val clamped = value.coerceIn(Min, Max)
        return (clamped * 10f).roundToIntSafe() / 10f
    }

    private fun Float.roundToIntSafe(): Int = kotlin.math.round(this).toInt()
}

/**
 * 设置页里需要持久化的非敏感项。密钥不在这里。
 *
 * [quietStart] / [quietEnd] 允许跨零点：结束时间早于开始时间表示跨天。
 */
@Immutable
data class AppSettings(
    val voiceId: String = "warm-female",
    val speechRate: Float = SpeechRateRange.Default,
    val proactiveEnabled: Boolean = true,
    val quietStart: LocalTime = LocalTime.of(23, 30),
    val quietEnd: LocalTime = LocalTime.of(7, 30),
    /** 「后台常驻」开关：是否让前台服务一直挂着。默认关。 */
    val keepAliveEnabled: Boolean = false,
    /**
     * 他的常住城市（任务书 round4 4.3）。
     *
     * 天气工具的默认地点、以及让她「聊天时也知道」都要用它。默认空：空的时候天气不猜城市、
     * 只回一句「不知道对方在哪个城市，先问问对方」。
     */
    val homeCity: String = "",
)

/** 非敏感设置。真实实现用 DataStore(Preferences)，以 Flow 暴露。 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setVoiceId(voiceId: String)
    suspend fun setSpeechRate(rate: Float)
    suspend fun setProactiveEnabled(enabled: Boolean)
    suspend fun setQuietHours(start: LocalTime, end: LocalTime)
    suspend fun setKeepAliveEnabled(enabled: Boolean)

    /** 保存常住城市（空串表示还没填）。 */
    suspend fun setHomeCity(city: String)
}

// ------------------------------ 人设文件 ------------------------------------

/** 两份人设文件。文件名固定，界面上不可改。 */
enum class PersonaKind(val fileName: String, val title: String) {
    Soul("soul.md", "Soul.md"),
    User("user.md", "User.md"),
}

/** 已导入文件的元信息（不含正文，正文按需读）。 */
@Immutable
data class PersonaDocument(
    val kind: PersonaKind,
    val sizeBytes: Long,
    val importedAt: Long,
)

/** 导入失败的原因，界面据此给出明确文案。 */
enum class PersonaImportError {
    /** 选择器返回的 Uri 打不开（没有权限、文件被删）。 */
    NotReadable,

    /** MIME 不是文本/markdown。 */
    NotText,

    /** 内容不是合法 UTF-8。 */
    InvalidEncoding,

    /** 超过 [PersonaStore.MaxBytes]。 */
    TooLarge,
}

sealed interface PersonaImportResult {
    data class Success(val document: PersonaDocument) : PersonaImportResult
    data class Failure(val error: PersonaImportError) : PersonaImportResult
}

/**
 * soul.md / user.md 两份人设文件的存取。
 * 文件落在应用私有目录，不参与备份（见 AndroidManifest 的备份排除规则）。
 */
interface PersonaStore {
    /** 是否已导入 + 大小 + 导入时间；未导入时发 null。 */
    fun observe(kind: PersonaKind): Flow<PersonaDocument?>

    /**
     * 从系统选择器返回的 [uri] 读取、校验、保存。校验失败不改变原有文件。
     * 读取在 IO 线程，调用方在协程里等结果即可。
     */
    suspend fun import(kind: PersonaKind, uri: Uri): PersonaImportResult

    /** 读正文用于只读预览；未导入返回 null。 */
    suspend fun readText(kind: PersonaKind): String?

    suspend fun remove(kind: PersonaKind)

    /**
     * 撤销移除：把之前 [readText] 读到的正文原样写回。
     * 不经过系统选择器，大小/编码按已校验过的正文处理；写回失败返回 null。
     */
    suspend fun restore(kind: PersonaKind, text: String): PersonaDocument?

    companion object {
        /** 单份人设文件的大小上限：1 MB。 */
        const val MaxBytes: Long = 1_000_000L
    }
}

// ------------------------------ 音色 ---------------------------------------

/** 可选的音色。[style] 是设计图上的那行小标签，[traits] 留给以后的详情。 */
@Immutable
data class VoiceOption(
    val id: String,
    val name: String,
    val style: String,
    val traits: List<String> = emptyList(),
)

/**
 * 音色列表 + 试听。真实实现接 MiniMax TTS；这一轮用占位音频。
 *
 * 试听同一时间只允许一个，由调用方（ViewModel）负责先 [stopPreview]；
 * 实现自身也要保证重复调用 [preview] 时不会叠音。
 */
interface VoiceCatalog {
    /** 固定顺序的音色列表；界面直接消费。 */
    val voices: List<VoiceOption>

    fun voice(id: String): VoiceOption?

    /** 播放 [voiceId] 的试听片段，播完或被 [stopPreview] 打断后返回。 */
    suspend fun preview(voiceId: String)

    /** 立刻停掉正在播放的试听（没有则在返回）。 */
    fun stopPreview()
}

// ------------------------------ 导出 ---------------------------------------

enum class ExportError {
    /** 没有可导出的数据。 */
    NoData,

    /** 目标 Uri 写不进去（空间不足、权限被撤回）。 */
    WriteFailed,

    /** 读数据时出错。 */
    ReadFailed,
}

sealed interface ExportResult {
    /** [bytes] 是最终写入的字节数，[fileCount] 是压缩包里的文件个数。 */
    data class Success(val bytes: Long, val fileCount: Int) : ExportResult
    data class Failure(val error: ExportError) : ExportResult
}

/**
 * 导出。所有 IO 在 IO 线程，调用方只等结果。
 * 导出内容里不允许出现任何密钥。
 */
interface ExportService {
    /**
     * 导出一份完整数据包（zip）：`soul.md`、`user.md`、记忆（Markdown + JSON）、
     * 日记、聊天记录、表情库。布局和导入端一致，换设备后可以直接导回。
     */
    suspend fun exportAll(destination: Uri): ExportResult
}

// ------------------------------ 权限与后台常驻 ------------------------------

/**
 * 权限与后台常驻页的每一行。
 *
 * 顺序就是页面上的顺序：前三项是真正的系统权限，[BootStart] 没有公开检测接口，
 * [KeepAlive] 不是权限而是「后台常驻」开关，[MediaFiles] 永远不需要授权。
 */
enum class RequiredPermission(val title: String) {
    Notifications("通知"),
    ExactAlarm("闹钟和提醒"),
    BatteryOptimization("忽略电池优化"),
    BootStart("开机自启"),
    KeepAlive("后台常驻"),
    MediaFiles("相册和文件"),
}

/** 一项权限的当前状态。 */
enum class PermissionState {
    /** 已开启。 */
    Granted,

    /** 没开，需要用户去开启。 */
    Denied,

    /** 系统没有公开接口能检测（例如开机自启）。 */
    Unknown,

    /** 不需要授权（例如相册走系统选择器）。 */
    NotRequired,
}

/**
 * 权限与后台常驻状态的唯一来源。
 *
 * [statusUpdates] 订阅时立刻发一次，之后：
 * - 每次 Activity 回到前台（ON_RESUME，包括从系统设置页返回）重新检查；
 * - 设置里的「后台常驻」开关变化时重新检查。
 * [refresh] 供权限申请 / 系统页面返回后主动要求重算（系统弹窗不一定触发 ON_RESUME）。
 * [missing] 只算 [PermissionState.Denied]，Unknown / NotRequired 不计入。
 */
interface PermissionStatusProvider {
    /** 同步读一遍当前状态（系统接口 + 已保存的开关），最近一次检查的值。 */
    fun statuses(): Map<RequiredPermission, PermissionState>

    /** 只有没开的项；设置页上的「N 项未开启」用它。 */
    fun missing(): List<RequiredPermission>

    /** 状态流：订阅立刻发一次，之后在回到前台 / 开关变化 / [refresh] 时重发。 */
    val statusUpdates: Flow<Map<RequiredPermission, PermissionState>>

    /** 主动要求重新检查（权限申请结果回来后调用）。 */
    fun refresh()
}
