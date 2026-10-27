package whaleui.nightsky.ltd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import whaleui.nightsky.ltd.domain.settings.SettingsRepository

/**
 * 开机广播：如果用户开过「后台常驻」，把前台服务拉起来。
 *
 * 只处理两个广播：
 * - [Intent.ACTION_BOOT_COMPLETED]：开机完成（解密后，不是 LOCKED_BOOT_COMPLETED）；
 * - [Intent.ACTION_MY_PACKAGE_REPLACED]：应用升级 / 重装后进程被换掉，同样需要恢复常驻服务。
 *
 * 注意：用户在系统设置里「强行停止」应用之后，系统不会再给它发开机广播，
 * 直到用户手动打开一次应用——这是 Android 的既定行为，不是这里能绕过的。
 *
 * 广播里不能做耗时工作，所以用 [goAsync] + IO 协程：读完设置、按开关决定是否启动服务，
 * 然后 [android.content.BroadcastReceiver.PendingResult.finish]。任何异常都必须吞掉，
 * 开机广播里崩溃会连累系统启动流程。
 */
class BootReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 进程即使是被开机广播拉起来的，Application.onCreate 也已经先跑完、Koin 已经起来了
                val settings = get<SettingsRepository>().settings.first()
                if (settings.keepAliveEnabled) {
                    KeepAliveService.start(context)
                }
            } catch (e: Exception) {
                // 开机广播里不能崩：读设置失败 / 系统禁止启动服务都按「这次跳过」处理。
            } finally {
                pending.finish()
            }
        }
    }
}
