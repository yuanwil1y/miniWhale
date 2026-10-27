package whaleui.nightsky.ltd.schedule

/**
 * 两个种子任务的**稳定 id**（[ScheduleMath.seedTasks] 里写死的那两个）。
 *
 * 判断「睡前那一轮」必须用它，不能匹配标题文字：用户把标题改了，功能不能跟着失效。
 */
object SeedTaskIds {
    const val MORNING: String = "schedule-seed-morning"
    const val NIGHT: String = "schedule-seed-night"
}