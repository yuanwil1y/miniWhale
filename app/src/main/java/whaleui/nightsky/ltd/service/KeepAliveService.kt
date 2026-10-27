package whaleui.nightsky.ltd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.R

/**
 * 「后台常驻」用的最小前台服务。
 *
 * 这一轮不做任何业务：只负责挂一条常驻通知，让系统别在息屏 / 内存紧张时太快回收进程，
 * 提醒和主动消息的调度以后再接。没有 START_STICKY 之外的重启逻辑，也不处理
 * onTaskRemoved（默认行为：用户从最近任务划掉后服务继续存活）。
 *
 * 前台服务类型用 specialUse（见 [startForegroundCompat] 的说明）：
 * 它既不是媒体播放也不是定位 / 数据同步，属于「保证应用形态可用」的用途，
 * Android 14+ 要求显式声明类型，specialUse 是唯一贴切的选项。
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
        startForegroundCompat(buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被系统杀掉后尽量重启；重启时 intent 可能为 null，不需要额外处理。
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        // 服务和常驻通知一起结束；重复调用无害。
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 14（API 34）起启动前台服务必须带类型，否则 startForeground 会抛
     * [android.app.MissingForegroundServiceTypeException]；更低版本用普通的 startForeground。
     * （这里直接调平台方法，不依赖 ServiceCompat 的版本，避免 core 版本差异。）
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NotificationId, notification)
        }
    }

    /** 通知渠道：低重要性，不出声、不弹横幅、不在桌面显示角标。 */
    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ChannelId) != null) return
        val channel = NotificationChannel(
            ChannelId,
            ChannelName,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = ChannelDescription
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // 点通知回到聊天页：复用已有任务，避免叠出第二个任务。
        val contentIntent = PendingIntent.getActivity(
            this,
            ContentIntentRequestCode,
            Intent(this, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_keepalive)
            .setContentTitle(NotificationTitle)
            .setContentText(NotificationText)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        /** 渠道 id，定下就不要改：改了会出现第二个渠道，旧渠道的设置也带不过去。 */
        const val ChannelId = "whale_keepalive"

        /** 通知 id，固定值保证重复展示时覆盖同一条。 */
        const val NotificationId = 20261026

        // 文案放在 Kotlin 里和现有代码保持一致（这个项目的中文串都在代码里）。
        private const val ChannelName = "后台常驻"
        private const val ChannelDescription = "让提醒和陪伴消息更及时；常驻通知随时可以点回应用"
        private const val NotificationTitle = "miniWhale 正在后台运行"
        private const val NotificationText = "点一下回到聊天"
        private const val ContentIntentRequestCode = 0

        /** 当前服务实例，只用于 stop 时同步移除常驻通知；onDestroy 里会置空，不泄漏。 */
        @Volatile
        private var instance: KeepAliveService? = null

        /**
         * 启动常驻服务。允许在后台广播（开机）里调用：调用方自己保证场景合法，
         * 系统拒绝时返回 false，不能因此崩溃。
         */
        fun start(context: Context): Boolean {
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, KeepAliveService::class.java),
                )
                true
            } catch (e: Exception) {
                // 例如省电模式 / 后台限制禁止启动前台服务；保持静默，界面继续可用。
                false
            }
        }

        /** 停止常驻服务并立刻移除常驻通知（stopService 之前先摘通知，界面不残留）。 */
        fun stop(context: Context) {
            instance?.let { service ->
                try {
                    service.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
                } catch (e: Exception) {
                    // 服务已经不在前台；忽略。
                }
            }
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (e: Exception) {
                // 已经停了；忽略。
            }
        }
    }
}
