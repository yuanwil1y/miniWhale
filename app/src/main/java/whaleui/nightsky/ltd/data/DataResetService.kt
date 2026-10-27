package whaleui.nightsky.ltd.data

import whaleui.nightsky.ltd.sticker.StickerRepository
import whaleui.nightsky.ltd.domain.memory.DiaryRepository
import whaleui.nightsky.ltd.domain.memory.MemoryRepository
import whaleui.nightsky.ltd.domain.settings.PersonaKind
import whaleui.nightsky.ltd.domain.settings.PersonaStore

/**
 * 设置页「清空数据」的实现：删掉 soul.md、user.md、全部记忆、全部日记、全部表情包。
 *
 * 范围故意**不含**聊天记录、设置项和 API 密钥——聊天记录只能在聊天页逐条删除，
 * 密钥清空有各自的「清除」按钮。所有删除都是不可撤销的，调用方必须先弹确认框。
 */
class DataResetService(
    private val personaStore: PersonaStore,
    private val memoryRepository: MemoryRepository,
    private val diaryRepository: DiaryRepository,
    private val stickerRepository: StickerRepository,
) {

    suspend fun resetAll() {
        PersonaKind.entries.forEach { kind ->
            personaStore.remove(kind)
        }
        memoryRepository.clearAll()
        diaryRepository.clearAll()
        // 表情没有「一键清空」接口：按系列删（会同时删行、webp 和缩略图）。
        stickerRepository.packs().forEach { pack ->
            stickerRepository.deletePack(pack.id)
        }
    }
}
