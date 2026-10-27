package whaleui.nightsky.ltd.ui.chat

import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import androidx.compose.ui.Alignment
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import whaleui.nightsky.ltd.ui.theme.WhaleTheme

/** 本机 Uri 或 Preview 的占位 key；不是 Uri 就画内置渐变。 */
private fun isLocalUri(source: String): Boolean =
    source.startsWith("content://") ||
        source.startsWith("file://") ||
        source.startsWith("android.resource://") ||
        source.startsWith("/")

@Composable
fun rememberChatImage(source: String): ImageBitmap? {
    val context = LocalContext.current
    val uri = source.takeIf(::isLocalUri) ?: return null
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            decodeSampledBitmap(context, uri)?.asImageBitmap()
        }
    }
    return bitmap
}

private fun decodeSampledBitmap(
    context: android.content.Context,
    uriString: String,
): Bitmap? {
    return runCatching {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()
}

/** 图片内容：有本机图就画图，否则画内置的深海占位图。 */
@Composable
fun ChatImageContent(
    source: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap = rememberChatImage(source)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        SyntheticChatImage(source = source, modifier = modifier)
    }
}

/** 图片还没解出来 / 不是本机图时的占位：平底 + 图片图标（U0：去掉原型期手画的插画和 9 个专用颜色）。 */
@Composable
fun SyntheticChatImage(@Suppress("UNUSED_PARAMETER") source: String, modifier: Modifier = Modifier) {
    val colors = WhaleTheme.colors
    Box(modifier.background(colors.mediaPlaceholder), contentAlignment = Alignment.Center) {
        WhaleIcon(WhaleIcons.Image, tint = colors.textSecondary, size = WhaleSpacing.touchTarget)
    }
}
