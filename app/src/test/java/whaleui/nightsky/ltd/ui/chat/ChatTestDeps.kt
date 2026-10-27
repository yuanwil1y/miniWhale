package whaleui.nightsky.ltd.ui.chat

import android.app.Application
import whaleui.nightsky.ltd.data.uploads.ChatUploads
import whaleui.nightsky.ltd.data.uploads.UploadSource
import whaleui.nightsky.ltd.domain.chat.ChatRepository
import whaleui.nightsky.ltd.sticker.InMemoryStickerRepository

/**
 * 测试里用不到附件复制时的 [ChatUploads]：真要读文件就失败（测试不该走到这里）。
 * R2 起表情库、上传都是必传依赖，不再有 null 分支。
 */
fun unusedUploads(): ChatUploads = ChatUploads(
    dir = java.nio.file.Files.createTempDirectory("whale-test-uploads").toFile(),
    source = object : UploadSource {
        override fun describe(uri: String): whaleui.nightsky.ltd.data.uploads.UploadInfo = error("测试没有准备附件：$uri")
        override fun open(uri: String): java.io.InputStream = error("测试没有准备附件：$uri")
    },
    shrinker = { _, _, _ -> null },
)

/** 表情库为空、不复制附件的 [ChatViewModel] */
fun chatViewModel(repository: ChatRepository): ChatViewModel =
    ChatViewModel(Application(), repository, InMemoryStickerRepository(), unusedUploads())
