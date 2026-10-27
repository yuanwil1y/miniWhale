package whaleui.nightsky.ltd.whale

/**
 * 「常住城市」的进程内缓存（任务书 round4 4.3）。
 *
 * 为什么需要缓存：天气工具和系统提示词都是同步取值，而设置是 Flow；
 * 由 Bootstrap 和设置页保存时刷新一次，读的时候直接拿。
 */
object WhaleHomeCity {

    @Volatile
    var value: String = ""
        private set

    fun update(city: String) {
        value = city.trim()
    }
}