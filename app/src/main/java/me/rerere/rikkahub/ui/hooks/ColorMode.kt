package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import me.rerere.rikkahub.ui.theme.ColorMode

private const val COLOR_MODE_KEY = "colorMode"

/** 原版设置里选过的深浅色（SharedPreferences，没选过就跟随系统）；S11 起没有地方再改它，只读。 */
@Composable
fun rememberCurrentColorMode(): ColorMode {
    val colorModeValue by rememberSharedPreferenceString(COLOR_MODE_KEY, ColorMode.SYSTEM.name)
    return colorModeValue.toColorMode()
}

private fun String?.toColorMode(): ColorMode {
    return ColorMode.entries.firstOrNull { it.name == this } ?: ColorMode.SYSTEM
}
