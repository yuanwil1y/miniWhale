package whaleui.nightsky.ltd.domain.schedule

import kotlinx.coroutines.flow.Flow

// 定时任务仓库与调度引擎接口（R1 / W-050 从 ui.schedule 搬来）

interface ScheduleRepository {

    /** 全部任务。顺序只用于撤销删除的“原位置”，时间线由 ViewModel 排序。 */
    fun observeAll(): Flow<List<ScheduleTask>>

    /** 新增。id 由调用方生成；返回落地后的任务。 */
    suspend fun add(task: ScheduleTask): ScheduleTask

    /** 整条更新；id 不存在时返回 null。 */
    suspend fun update(task: ScheduleTask): ScheduleTask?

    suspend fun delete(id: String)

    /** 撤销删除：按 index 插回原位置。 */
    suspend fun restore(task: ScheduleTask, index: Int)

    /** 开关变化，只改 enabled。 */
    suspend fun setEnabled(id: String, enabled: Boolean)
}

/** 代表“把这些任务注册给系统调度”。真实实现里做 WorkManager / AlarmManager 的注册。 */
interface ScheduleEngine {

    fun reschedule(tasks: List<ScheduleTask>)
}
