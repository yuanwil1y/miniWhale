package whaleui.nightsky.ltd.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import whaleui.nightsky.ltd.domain.settings.PersonaKind
import whaleui.nightsky.ltd.ui.chat.ChatViewModel
import whaleui.nightsky.ltd.ui.settings.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// 系统文件选择器（R1 / W-051 从 WhaleRoot 拆出来）：聊天页选图 / 选文件，设置页的导入导出。
// 读文件、解压、写库都在各自的 ViewModel 里，这里只负责拉起选择器、把结果交过去。
// ---------------------------------------------------------------------------

internal class ChatPickers(
    val pickImages: () -> Unit,
    val pickFiles: () -> Unit,
)

@Composable
internal fun rememberChatPickers(chatViewModel: ChatViewModel): ChatPickers {
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris ->
        // 选中就复制进私有目录（图片压缩），太大 / 不支持的会通过 notice 提示（F5）
        chatViewModel.attach(uris.map { it.toString() })
    }
    val chatFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) chatViewModel.attach(listOf(uri.toString()))
    }
    return ChatPickers(
        pickImages = {
            imagePicker.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        },
        pickFiles = { chatFilePicker.launch(arrayOf("*/*")) },
    )
}

internal class SettingsPickers(
    val importPersona: (PersonaKind) -> Unit,
    val importDiary: () -> Unit,
    val importMemory: () -> Unit,
    val importChat: () -> Unit,
    val exportAll: () -> Unit,
)

@Composable
internal fun rememberSettingsPickers(
    settingsViewModel: SettingsViewModel,
    chatViewModel: ChatViewModel,
): SettingsPickers {
    // 人设导入：系统文件选择器，只允许文本 / Markdown。
    var pendingPersonaKind by remember { mutableStateOf(PersonaKind.Soul) }
    val personaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) settingsViewModel.importPersona(pendingPersonaKind, uri)
    }
    // 导入日记（任务书 4.7）：可以选多个 md，也可以选一个 zip；只合并不覆盖。
    // 读文件、解压、写库都在 SettingsViewModel 里（IO 线程、有上限），不挂在界面的协程上。
    val diaryImportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        settingsViewModel.importDiary(uris)
    }
    val diaryImportTypes = arrayOf("*/*")
    val personaTypes = arrayOf("*/*")

    // 导入记忆：选导出包（memory/memories.md）或单个同格式 md
    val memoryImportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) settingsViewModel.importMemory(uri)
    }

    // 导入聊天记录：选导出的 zip（chat/*.md），合并进固定对话；同样交给 SettingsViewModel
    val chatImportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 新节点是直接写库的，聊天页要重读一遍才会出现。
        settingsViewModel.importChat(uri) { chatViewModel.reload() }
    }

    // 导出全部数据：一个 zip（人设 / 记忆 / 日记 / 聊天记录 / 表情包）
    val exportAllPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) settingsViewModel.exportAll(uri)
    }

    return SettingsPickers(
        importPersona = { kind ->
            pendingPersonaKind = kind
            personaPicker.launch(personaTypes)
        },
        importDiary = { diaryImportPicker.launch(diaryImportTypes) },
        importMemory = { memoryImportPicker.launch(arrayOf("*/*")) },
        importChat = { chatImportPicker.launch(arrayOf("*/*")) },
        exportAll = { exportAllPicker.launch("miniWhale-${exportDateStamp()}.zip") },
    )
}

private fun exportDateStamp(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
