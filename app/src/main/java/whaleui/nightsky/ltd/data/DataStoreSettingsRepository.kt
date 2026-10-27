package whaleui.nightsky.ltd.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.LocalTime
import whaleui.nightsky.ltd.domain.settings.AppSettings
import whaleui.nightsky.ltd.domain.settings.HerProfile
import whaleui.nightsky.ltd.domain.settings.HerProfileStore
import whaleui.nightsky.ltd.domain.settings.SettingsRepository
import whaleui.nightsky.ltd.domain.settings.SpeechRateRange

/**
 * DataStore(Preferences) 的文件名。
 *
 * 用 [preferencesDataStore] 委托保证整个进程只有一个实例；名字一旦定下，
 * 改名会让已保存的设置全部读不到。
 */
private const val DataStoreName = "whale_settings"

/** 进程内单例的 Preferences DataStore。 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = DataStoreName)

/**
 * 非敏感设置的真实实现。
 *
 * 全部字段落在 `DataStore(Preferences)` 里，以 [Flow] 暴露给界面；读写都在
 * DataStore 自己的 IO 线程上，调用方只需要在协程里等结果。
 *
 * 存储约定：
 * - 语速保存前先按 [SpeechRateRange.normalize] 夹紧并量化，避免浮点尾数落盘；
 * - 免打扰时间存「当天第几分钟」（0..1439），读出时换回 [LocalTime]，
 *   跨零点由「结束早于开始」表达，界面自己判断，不做额外约束。
 *
 * @param context 用于拿到 DataStore 文件目录，内部只使用 applicationContext。
 */
class DataStoreSettingsRepository(context: Context) : SettingsRepository {

    private val dataStore: DataStore<Preferences> = context.applicationContext.settingsDataStore

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { throwable ->
            // 文件损坏时先给出默认值，界面不至于整页起不来。
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { preferences -> preferences.toAppSettings() }

    override suspend fun setVoiceId(voiceId: String) {
        dataStore.edit { preferences ->
            preferences[Keys.VoiceId] = voiceId
        }
    }

    override suspend fun setSpeechRate(rate: Float) {
        dataStore.edit { preferences ->
            preferences[Keys.SpeechRate] = SpeechRateRange.normalize(rate)
        }
    }

    override suspend fun setProactiveEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.ProactiveEnabled] = enabled
        }
    }

    override suspend fun setQuietHours(start: LocalTime, end: LocalTime) {
        // 两个字段必须一次写完，否则界面会读到一半新一半旧的区间。
        dataStore.edit { preferences ->
            preferences[Keys.QuietStart] = start.toMinutesOfDay()
            preferences[Keys.QuietEnd] = end.toMinutesOfDay()
        }
    }

    override suspend fun setKeepAliveEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.KeepAliveEnabled] = enabled
        }
    }

    /** 读出偏好并补齐默认值；坏值（越界分钟数、越界语速）在这里就地修正。 */
    override suspend fun setHomeCity(city: String) {
        dataStore.edit { it[Keys.HomeCity] = city.trim() }
    }
    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            voiceId = this[Keys.VoiceId]?.takeIf { it.isNotBlank() } ?: defaults.voiceId,
            speechRate = SpeechRateRange.normalize(
                this[Keys.SpeechRate] ?: SpeechRateRange.Default,
            ),
            proactiveEnabled = this[Keys.ProactiveEnabled] ?: defaults.proactiveEnabled,
            quietStart = minutesToLocalTime(this[Keys.QuietStart] ?: DefaultQuietStartMinutes),
            quietEnd = minutesToLocalTime(this[Keys.QuietEnd] ?: DefaultQuietEndMinutes),
            keepAliveEnabled = this[Keys.KeepAliveEnabled] ?: defaults.keepAliveEnabled,
            homeCity = this[Keys.HomeCity] ?: defaults.homeCity,
        )
    }

    private fun minutesToLocalTime(minutes: Int): LocalTime {
        val safe = minutes.coerceIn(0, MinutesPerDay - 1)
        return LocalTime.of(safe / 60, safe % 60)
    }

    private fun LocalTime.toMinutesOfDay(): Int = hour * 60 + minute

    /** 只在这里集中存放 key 名，方便排查存储内容。 */
    private object Keys {
        val VoiceId = stringPreferencesKey("voiceId")
        val SpeechRate = floatPreferencesKey("speechRate")
        val ProactiveEnabled = booleanPreferencesKey("proactiveEnabled")
        val QuietStart = intPreferencesKey("quietStart")
        val QuietEnd = intPreferencesKey("quietEnd")

        /** 「后台常驻」开关；默认关，对应 [AppSettings.keepAliveEnabled]。 */
        val KeepAliveEnabled = booleanPreferencesKey("keepAliveEnabled")

        /** 常住城市（任务书 round4 4.3）；空串 = 没填。 */
        val HomeCity = stringPreferencesKey("homeCity")
    }

    private companion object {
        const val MinutesPerDay = 24 * 60

        /** 与 [AppSettings] 的默认值保持一致：23:30 与 07:30。 */
        const val DefaultQuietStartMinutes = 23 * 60 + 30
        const val DefaultQuietEndMinutes = 7 * 60 + 30
    }
}

/** 她的名字和头像：和其它非敏感设置同一个 DataStore 文件（[DataStoreName]）。 */
fun dataStoreHerProfileStore(context: Context): HerProfileStore =
    DataStoreHerProfileStore(context.applicationContext.settingsDataStore)

/**
 * [HerProfileStore] 的 DataStore 实现。两个 key 都不存在时（老版本升级上来、或从没改过）
 * 读出默认值：名字 `default`、白色头像。
 */
class DataStoreHerProfileStore(private val dataStore: DataStore<Preferences>) : HerProfileStore {

    override val profile: Flow<HerProfile> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences ->
            HerProfile(
                // 盘上的值也过一遍规整：手改过文件或以后放宽规则时不至于显示出空名字
                nickname = HerProfile.normalizeNickname(preferences[Nickname] ?: HerProfile.DEFAULT_NICKNAME),
                avatarPath = preferences[AvatarPath]?.takeIf { it.isNotBlank() },
            )
        }

    override suspend fun save(nickname: String, avatarPath: String?) {
        dataStore.edit { preferences ->
            preferences[Nickname] = HerProfile.normalizeNickname(nickname)
            if (avatarPath.isNullOrBlank()) preferences.remove(AvatarPath) else preferences[AvatarPath] = avatarPath
        }
    }

    private companion object {
        val Nickname = stringPreferencesKey("herNickname")
        val AvatarPath = stringPreferencesKey("herAvatarPath")
    }
}
