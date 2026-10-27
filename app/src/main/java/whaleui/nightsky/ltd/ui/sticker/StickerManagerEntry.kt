package whaleui.nightsky.ltd.ui.sticker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

// ---------------------------------------------------------------------------
// 设置 → 表情包管理的唯一入口（调用点由外界加）。
//
// ViewModel 由 Koin 构造注入（表情库、导入器都是 WhaleModule 里的单例）。
// ---------------------------------------------------------------------------

/** zip 选择器接受的 MIME：不同文件管理器给的类型不一致，多列几个。 */
private val ZipMimeTypes = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
)

/**
 * 表情包管理页入口。[onBack] 由外层导航提供，顶栏返回箭头和系统返回键都会回到列表页 / 上一页。
 */
@Composable
fun StickerManagerEntry(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: StickerManagerViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 从别的页面回来时收起上次留下的浮层；导入进行中则不打断。
    LaunchedEffect(viewModel) { viewModel.onEnterPage() }

    // 系统文件选择器：目录 / zip 两种导入。
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.importFolder(uri)
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importZip(uri)
    }

    StickerManagerScreen(
        state = state,
        actions = viewModel,
        onBack = onBack,
        onPickFolder = { folderPicker.launch(null) },
        onPickZip = { zipPicker.launch(ZipMimeTypes) },
        modifier = modifier,
    )
}
