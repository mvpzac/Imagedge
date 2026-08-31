package com.imagedge.camera.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/29
 *     desc   : 主题 v4.0（Material 3 Expressive）
 *              - 主色紫罗兰 #5B50E6，其余角色由 Material 3 从主色调色板派生
 *              - 支持 Material You 动态取色（Android 12+），由设置页开关控制，默认关闭
 *              - 语义色（成功/警告/错误/信息）显式指定，保证状态可辨识
 *     version: 4.0
 * </pre>
 */

private val ImagedgeLightScheme = lightColorScheme(
    primary = BrandViolet,
    secondary = Info,
    tertiary = Success,
    background = BgLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onPrimary = SurfaceLight,
    onSecondary = SurfaceLight,
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
    primary = BrandVioletDark,
    secondary = InfoDark,
    tertiary = SuccessDark,
    background = BgDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onPrimary = Color(0xFF1A1240),
    onSecondary = Color(0xFF0E2A6B),
    onBackground = OnDark,
    onSurface = OnDark,
    onSurfaceVariant = OnDarkVariant,
    outline = OutlineDark,
    outlineVariant = OutlineDark,
    error = ErrorDark,
    errorContainer = ErrorContainerDark,
    onError = Color(0xFF4A0F0F),
    onErrorContainer = OnErrorContainerDark
)

/**
 * Imagedge 主题入口
 * @param darkTheme 是否深色模式
 * @param dynamicColor 是否启用 Material You 动态取色（Android 12+ 才生效，默认关闭）
 */
@Composable
fun ImagedgeTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    brandColor: BrandColor = BrandColor.VIOLET,
    content: @Composable () -> Unit
) {
    // 动态取色优先；否则按品牌色档位覆盖 base scheme 的 primary
    // （primaryContainer 等未用到的派生色保持 base 不动）
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ImagedgeDarkScheme.copy(primary = brandColor.darkPrimary)
        else -> ImagedgeLightScheme.copy(primary = brandColor.lightPrimary)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ImagedgeTypography,
        shapes = ImagedgeShapes,
        content = content
    )
}
