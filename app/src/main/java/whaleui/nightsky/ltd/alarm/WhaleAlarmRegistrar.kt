package whaleui.nightsky.ltd.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * 和 AlarmManager 打交道的唯一地方：定时任务、她自己定的一次性闹钟、遇忙顺延 / 看门狗重试都走这里。
 *
 * 有精确闹钟权限（或 App 在电池优化白名单里）时用 `setExactAndAllowWhileIdle`：准点、Doze 里也响，
 * 而且送达时系统给一个约 10 秒的前台服务启动豁免，Receiver 可以合法地拉起生成用的前台服务（W-007 / W-020）。
 * 没有权限时的兜底见 [registerInexact]。
 *
 * ## 闹钟的身份（W-063）
 * 每个闹钟用一个字符串 [key] 区分（定时任务是 taskId，顺延是 `retry:<taskId>`，她的闹钟是 `her:<id>` /
 * `her-retry:<id>`；非精确兜底的双保险再加 `#idle`）。系统按「requestCode + Intent.filterEquals（action、data、
 * 组件…，不含 extras）」认 PendingIntent。以前 requestCode 是 `key.hashCode()`、Intent 不带 data：两个 key 的
 * hashCode 撞上时，登记后一个会把前一个顶掉，取消一个会连另一个一起取消。
 *
 * 现在把 key 编进 Intent 的 data（`whale-alarm://alarm/<key>`），requestCode 固定为 [KEYED_REQUEST_CODE]：
 * 身份就是 key 本身，不同 key 永远是不同的 PendingIntent，不需要持久化分配 id，也不需要冲突检测。
 * 这样选的理由：持久化整数 id 要多一张表 / 一份存储，还要处理「id 表丢了」和开机重排时的一致性；
 * hash + 冲突检测仍然要在某处记住谁占了哪个码。data 方案是无状态的，Receiver 照旧只读 extras，不受影响。
 *
 * ## 升级兼容
 * 旧版本登记的闹钟是「requestCode = key.hashCode()（双保险 `"$key#idle".hashCode()`）+ 不带 data」。
 * [register] 和 [cancel] 每次都顺手按旧规则取消一遍同 key 的旧闹钟（[cancelLegacy]），所以：
 * - 启动时 `scheduleAll` / `schedulePending` 重排全部任务和她的闹钟，旧闹钟被逐个替换，不会响两次；
 * - 删任务 / 取消她的闹钟时，旧闹钟也一并取消；
 * - 升级时正好挂着的顺延闹钟（旧格式）照常送达，Receiver 只看 extras，处理方式不变；下一次顺延改用新格式并取消旧的。
 * 旧规则下撞码的两个 key，取消其中一个的旧闹钟时可能连带取消另一个的旧闹钟——这正是旧方案本身的毛病；
 * 启动重排会把它按新格式补回来（顺延闹钟除外，撞码概率约 2^-32，可以忽略）。
 */
internal object WhaleAlarmRegistrar {

    private const val TAG = "WhaleAlarmRegistrar"

    /** 新格式的 requestCode：身份在 Intent 的 data 里，requestCode 不再区分闹钟 */
    const val KEYED_REQUEST_CODE = 0

    private const val SCHEME = "whale-alarm"
    private const val AUTHORITY = "alarm"
    private const val TWIN_SUFFIX = "#idle"

    /** 这次登记用的是哪种闹钟。 */
    enum class Mode {
        /** 精确闹钟（准点，带前台服务豁免） */
        Exact,

        /** 没有精确闹钟权限，用了非精确兜底 */
        Inexact,

        /** AlarmManager 不可用或登记失败 */
        Failed,
    }

    /** 现在能不能登记精确闹钟（Android 12 以下总是可以）。 */
    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    /** key 编进 data 之后的 Intent（新格式）；[intent] 本身不改 */
    fun keyedIntent(key: String, intent: Intent): Intent =
        Intent(intent).setData(Uri.Builder().scheme(SCHEME).authority(AUTHORITY).appendPath(key).build())

    /** 旧版本用的 requestCode（只用来取消旧闹钟） */
    fun legacyRequestCode(key: String): Int = key.hashCode()

    fun register(context: Context, key: String, triggerAtMillis: Long, intent: Intent): Mode {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return Mode.Failed
        // 同 key 的旧格式闹钟先取消，免得升级后同一次触发响两遍
        cancelLegacy(context, alarmManager, key, intent)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            KEYED_REQUEST_CODE,
            keyedIntent(key, intent),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return Mode.Failed
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            // 以前没权限时登记过的双保险用不着了
            cancelPending(context, alarmManager, KEYED_REQUEST_CODE, keyedIntent(key + TWIN_SUFFIX, intent))
            return Mode.Exact
        } catch (_: SecurityException) {
            // 没有精确闹钟权限：只会发生在 Android 12 / 12L（SCHEDULE_EXACT_ALARM 被用户在系统设置里关掉）；
            // 13+ 声明了 USE_EXACT_ALARM，安装即授予、不能撤销（F5）
        }
        return try {
            val twin = PendingIntent.getBroadcast(
                context,
                KEYED_REQUEST_CODE,
                keyedIntent(key + TWIN_SUFFIX, intent),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            registerInexact(alarmManager, triggerAtMillis, pendingIntent, twin)
            Mode.Inexact
        } catch (e: Exception) {
            Log.w(TAG, "register $key failed: ${e.javaClass.simpleName}")
            Mode.Failed
        }
    }

    /**
     * 取消 [key] 对应的闹钟（连同双保险，以及旧格式登记的同 key 闹钟；幂等）。
     * [intent] 只用来匹配 PendingIntent（action、组件），extras 不参与匹配。
     */
    fun cancel(context: Context, key: String, intent: Intent) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        cancelPending(context, alarmManager, KEYED_REQUEST_CODE, keyedIntent(key, intent))
        cancelPending(context, alarmManager, KEYED_REQUEST_CODE, keyedIntent(key + TWIN_SUFFIX, intent))
        cancelLegacy(context, alarmManager, key, intent)
    }

    /** 旧格式：requestCode = hashCode、Intent 不带 data */
    private fun cancelLegacy(context: Context, alarmManager: AlarmManager, key: String, intent: Intent) {
        val legacy = Intent(intent).setData(null)
        cancelPending(context, alarmManager, legacyRequestCode(key), legacy)
        cancelPending(context, alarmManager, legacyRequestCode(key + TWIN_SUFFIX), legacy)
    }

    private fun cancelPending(context: Context, alarmManager: AlarmManager, requestCode: Int, intent: Intent) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    /**
     * 没有精确闹钟权限时的兜底（W-029）。
     *
     * `setAlarmClock` 在 Android 12+ 同样要求精确闹钟权限（没有就抛 SecurityException），用不了；
     * 所以登记两个闹钟，先到的那个生效，另一个由调度层去重：
     * - [primary]：`setWindow`，窗口 10 分钟——平时最多晚 10 分钟；但它不是 allow-while-idle，深度 Doze 里会被推迟；
     * - [twin]：`setAndAllowWhileIdle`——Doze 里也会响，最坏晚 1 小时。
     */
    private fun registerInexact(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        primary: PendingIntent,
        twin: PendingIntent,
    ) {
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAtMillis, FALLBACK_WINDOW_MS, primary)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, twin)
    }

    /** 非精确兜底的窗口长度。 */
    const val FALLBACK_WINDOW_MS: Long = 10 * 60_000L
}
