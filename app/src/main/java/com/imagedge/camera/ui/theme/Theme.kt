package com.imagedge.camera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/31
 *     desc   : 主题 v5.0（极简黑白）——浅灰底 + 近黑主调；深色近白主调。
 *              语义色（成功/警告/错误/信息）显式指定，状态「图标+文字」双保险。
 *     version: 5.0
 * </pre>
 */

private val ImagedgeLightScheme = lightColorScheme(
    primary = InkLight,
    onPrimary = OnInkLight,
    secondary = Info,
    onSecondary = SurfaceLight,
    tertiary = Success,
    background = BgLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onBackground = OnLight,
    onSurface = OnLight,
    onSurfaceVariant = OnLightVariant,
    outline = OutlineLight,
    outlineVariant = OutlineLight,
    error = Error,
    errorContainer = ErrorContainer,
    onError = SurfaceLight,
    onErrorContainer = OnErrorContainer
)

private val ImagedgeDarkScheme = darkColorScheme(
    primary = InkDark,
    onPrimary = OnInkDark,
    secondary = InfoDark,
    onSecondary = OnInfoContainer,
    tertiary = SuccessDark,
    background = BgDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onBackground = OnDark,
    onSurface = OnDark,
    onSurfaceVariant = OnDarkVariant,
    outline = OutlineDark,
    outlineVariant = OutlineDark,
    error = ErrorDark,
    errorContainer = ErrorContainerDark,
    onError = OnErrorDark,
    onErrorContainer = OnErrorContainerDark
)

/** Imagedge 主题入口（极简黑白：只剩深浅分支） */
@Composable
fun ImagedgeTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) ImagedgeDarkScheme else ImagedgeLightScheme,
        typography = ImagedgeTypography,
        shapes = ImagedgeShapes,
        content = content
    )
}
