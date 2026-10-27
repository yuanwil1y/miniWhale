package whaleui.nightsky.ltd.voice

import android.util.Base64
import java.io.File

/**
 * 她自己的头像 → 作图的角色参考图（决定 7）。
 *
 * 头像是资料页「编辑」里设的那张（`HerProfile.avatarPath`，App 私有目录里的 JPG / PNG）。
 * 画她自己时把它转成 data URI 传给 MiniMax 的 `subject_reference[0].image_file`（字段格式照官方 CLI）。
 *
 * 没设自定义头像（默认白色）、读不到文件、或文件太大（>2MB）时返回 null：这时 `draw` 只是**标记**了 self，
 * 不带参考图，不会因为缺头像把作图整个弄失败。
 */
object AvatarReference {

    private const val MAX_BYTES = 2 * 1024 * 1024

    fun dataUriOf(avatarPath: String?): String? = runCatching {
        val file = avatarPath?.let(::File) ?: return null
        if (!file.isFile || file.length() == 0L || file.length() > MAX_BYTES) return null
        val mime = if (file.extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg"
        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        "data:$mime;base64,$encoded"
    }.getOrNull()
}
