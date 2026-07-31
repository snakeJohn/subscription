package com.substat.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/* 与 Web 端 base.css 的令牌对齐：纸白 / 墨黑 / 朱红 */
object Ink {
    val paper = Color(0xFFF2EFE9)
    val paper2 = Color(0xFFE8E4DC)
    val card = Color(0xFFFFFEFB)
    val ink = Color(0xFF14110F)
    val ink2 = Color(0xFF3D3833)
    val ink3 = Color(0xFF6E665E)
    val ink4 = Color(0xFFA39A90)
    val hair = Color(0xFFD6D0C6)
    val red = Color(0xFFD8412F)
    val ok = Color(0xFF1F7A4D)
    val warn = Color(0xFFB4761A)
    val bad = Color(0xFFC0392B)
    val nsfw = Color(0xFF8E2D5F)

    /* 深色 */
    val dPaper = Color(0xFF14120F)
    val dPaper2 = Color(0xFF1C1916)
    val dCard = Color(0xFF1A1714)
    val dInk = Color(0xFFF4F0E8)
    val dInk2 = Color(0xFFD8D2C6)
    val dInk3 = Color(0xFF9A9288)
    val dInk4 = Color(0xFF6B645B)
    val dHair = Color(0xFF332E28)
    val dRed = Color(0xFFF05A44)
    val dOk = Color(0xFF4EC98A)
    val dWarn = Color(0xFFE0A94A)
    val dBad = Color(0xFFF0705C)

    /* 图表色板：低饱和土色系 */
    val chart = listOf(
        Color(0xFFD8412F), Color(0xFF1F4E79), Color(0xFFC98A12), Color(0xFF2F6B4F),
        Color(0xFF7D4F9C), Color(0xFFA8552B), Color(0xFF3D7D8C), Color(0xFF8E2D5F),
        Color(0xFF5C6B3F), Color(0xFF9C6B3F), Color(0xFF4A4E8C), Color(0xFF7A3D3D),
    )
    val chartDark = listOf(
        Color(0xFFF05A44), Color(0xFF5B9BD5), Color(0xFFE0B04A), Color(0xFF57B085),
        Color(0xFFA87FC4), Color(0xFFD18A5E), Color(0xFF6CB0BF), Color(0xFFC4759C),
        Color(0xFF93A86B), Color(0xFFC49569), Color(0xFF8188C4), Color(0xFFC47A7A),
    )
}

/** 供 Composable 读取的语义色，随深浅切换 */
data class Palette(
    val paper: Color, val paper2: Color, val card: Color,
    val ink: Color, val ink2: Color, val ink3: Color, val ink4: Color,
    val hair: Color, val red: Color, val ok: Color, val warn: Color, val bad: Color,
    val chart: List<Color>, val dark: Boolean,
)

val LocalPalette = androidx.compose.runtime.staticCompositionLocalOf {
    Palette(
        Ink.paper, Ink.paper2, Ink.card, Ink.ink, Ink.ink2, Ink.ink3, Ink.ink4,
        Ink.hair, Ink.red, Ink.ok, Ink.warn, Ink.bad, Ink.chart, false,
    )
}

private val lightScheme = lightColorScheme(
    primary = Ink.red, onPrimary = Color.White,
    background = Ink.paper, onBackground = Ink.ink,
    surface = Ink.card, onSurface = Ink.ink,
    surfaceVariant = Ink.paper2, onSurfaceVariant = Ink.ink3,
    outline = Ink.hair, error = Ink.bad,
)
private val darkScheme = darkColorScheme(
    primary = Ink.dRed, onPrimary = Color.White,
    background = Ink.dPaper, onBackground = Ink.dInk,
    surface = Ink.dCard, onSurface = Ink.dInk,
    surfaceVariant = Ink.dPaper2, onSurfaceVariant = Ink.dInk3,
    outline = Ink.dHair, error = Ink.dBad,
)

/* 数字统一用等宽，衬线用于大标题 —— 与 Web 端排印一致 */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace)
val SerifStyle = TextStyle(fontFamily = FontFamily.Serif)

private val typo = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
        fontSize = 56.sp, lineHeight = 58.sp, letterSpacing = (-2).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
)

@Composable
fun SubStatTheme(
    themePref: String = "system",
    content: @Composable () -> Unit,
) {
    val dark = when (themePref) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val palette = if (dark) Palette(
        Ink.dPaper, Ink.dPaper2, Ink.dCard, Ink.dInk, Ink.dInk2, Ink.dInk3, Ink.dInk4,
        Ink.dHair, Ink.dRed, Ink.dOk, Ink.dWarn, Ink.dBad, Ink.chartDark, true,
    ) else Palette(
        Ink.paper, Ink.paper2, Ink.card, Ink.ink, Ink.ink2, Ink.ink3, Ink.ink4,
        Ink.hair, Ink.red, Ink.ok, Ink.warn, Ink.bad, Ink.chart, false,
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val win = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(win, view).isAppearanceLightStatusBars = !dark
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme else lightScheme,
            typography = typo,
            content = content,
        )
    }
}
