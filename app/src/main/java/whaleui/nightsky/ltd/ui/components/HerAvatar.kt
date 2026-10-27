package whaleui.nightsky.ltd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import whaleui.nightsky.ltd.domain.settings.HerProfile
import whaleui.nightsky.ltd.ui.theme.WhaleShapes

/** 当前的名字和头像。WhaleRoot 从 [whaleui.nightsky.ltd.domain.settings.HerProfileStore] 收集后提供，改了实时生效。 */
val LocalHerProfile = staticCompositionLocalOf { HerProfile() }

/** 默认头像：纯白圆形，不画任何图案。 */
val HerDefaultAvatarColor: Color = Color.White

/**
 * 她的头像：自定义了就显示那张图（圆形裁切），否则是纯白圆。
 * 文件读不出来（被清理了）时也退回白圆，不显示破图。
 */
@Composable
fun HerAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    avatarPath: String? = LocalHerProfile.current.avatarPath,
    contentDescription: String? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(WhaleShapes.circle)
            .background(HerDefaultAvatarColor)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        if (avatarPath != null) {
            var failed by remember(avatarPath) { mutableStateOf(false) }
            if (!failed) {
                AsyncImage(
                    model = "file://$avatarPath",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.size(size),
                )
            }
        }
    }
}
