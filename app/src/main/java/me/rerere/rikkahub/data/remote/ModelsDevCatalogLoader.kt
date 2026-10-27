package me.rerere.rikkahub.data.remote

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.registry.ModelsDevCatalog
import java.io.File

private const val TAG = "ModelsDevCatalog"
private const val ASSET_NAME = "models_dev.json"

/** 旧版本从 models.dev 下载的缓存目录（W-066 之后不再使用，启动时删掉） */
private const val LEGACY_CACHE_DIR = "models_dev"

/**
 * 给 [ModelRegistry] 装上模型能力表（输入 / 输出模态、是否支持思考等）。
 *
 * 高级设置添加新模型、模型列表标「可思考」时还要用到它，所以表留着；但只读 assets 里随包带的快照，
 * 启动时不再联网拉 models.dev（W-066：启动联网会把使用时间和 IP 暴露给第三方）。快照随版本更新。
 * 以前下载到 filesDir 的缓存不再读取（会一直停在下载那天、盖过更新的快照），这里顺手删掉。
 */
class ModelsDevCatalogLoader(private val context: Context) {

    suspend fun load() = withContext(Dispatchers.IO) {
        runCatching { File(context.filesDir, LEGACY_CACHE_DIR).deleteRecursively() }
        runCatching { context.assets.open(ASSET_NAME).use { ModelsDevCatalog.decode(it) } }
            .onSuccess { ModelRegistry.installCatalog(it) }
            .onFailure { Log.e(TAG, "failed to load bundled catalog", it) }
    }
}
