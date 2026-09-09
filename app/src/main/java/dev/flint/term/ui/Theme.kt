package dev.flint.term.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flint.term.App
import dev.flint.term.R
import dev.flint.term.data.AppTheme

// ---------------------------------------------------------------------------
// Palette: a deep, slightly blue-tinted dark theme (think Tokyo Night / Warp)
// with one saturated accent. Light is the same hues on paper-white.
// ---------------------------------------------------------------------------

object Tone {
    val Ink = Color(0xFF0C0F16)          // page background (dark)
    val Surface0 = Color(0xFF12161F)     // base surface
    val Surface1 = Color(0xFF181D28)     // cards
    val Surface2 = Color(0xFF1F2532)     // raised / hovered
    val Surface3 = Color(0xFF283041)     // chips, key caps
    val Outline = Color(0xFF2E3648)
    val OutlineSoft = Color(0xFF232A3A)
    val Text = Color(0xFFE8ECF4)
    val TextMuted = Color(0xFF98A2B8)
    val TextFaint = Color(0xFF66708A)

    val Blue = Color(0xFF7AA2F7)
    val BlueDeep = Color(0xFF3D6BE0)
    val Mint = Color(0xFF5DE4C7)
    val Green = Color(0xFF9ECE6A)
    val Amber = Color(0xFFE0AF68)
    val Red = Color(0xFFF7768E)
    val Purple = Color(0xFFBB9AF7)
    val Cyan = Color(0xFF7DCFFF)
    val Orange = Color(0xFFFF9E64)
    val Pink = Color(0xFFF2A3D4)
}

/** Semantic status colors that must read the same in both themes. */
object Status {
    val online = Tone.Green
    val busy = Tone.Amber
    val offline = Tone.Red
}

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Tone.Blue,
    onPrimary = Color(0xFF0A1630),
    primaryContainer = Color(0xFF223A6E),
    onPrimaryContainer = Color(0xFFD6E2FF),
    secondary = Tone.Mint,
    onSecondary = Color(0xFF00382C),
    secondaryContainer = Color(0xFF17463B),
    onSecondaryContainer = Color(0xFFBDF5E4),
    tertiary = Tone.Purple,
    onTertiary = Color(0xFF2A1250),
    tertiaryContainer = Color(0xFF3C2A66),
    onTertiaryContainer = Color(0xFFEBDDFF),
    error = Tone.Red,
    onError = Color(0xFF3B0713),
    errorContainer = Color(0xFF5C1F2C),
    onErrorContainer = Color(0xFFFFD9DE),
    background = Tone.Ink,
    onBackground = Tone.Text,
    surface = Tone.Surface0,
    onSurface = Tone.Text,
    surfaceVariant = Tone.Surface2,
    onSurfaceVariant = Tone.TextMuted,
    surfaceContainerLowest = Tone.Ink,
    surfaceContainerLow = Tone.Surface0,
    surfaceContainer = Tone.Surface1,
    surfaceContainerHigh = Tone.Surface2,
    surfaceContainerHighest = Tone.Surface3,
    surfaceBright = Tone.Surface3,
    surfaceDim = Tone.Ink,
    inverseSurface = Tone.Text,
    inverseOnSurface = Tone.Ink,
    inversePrimary = Tone.BlueDeep,
    outline = Tone.Outline,
    outlineVariant = Tone.OutlineSoft,
    scrim = Color.Black,
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Tone.BlueDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0E2559),
    secondary = Color(0xFF0E8C72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCF3E9),
    onSecondaryContainer = Color(0xFF00382C),
    tertiary = Color(0xFF6E48C8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEADDFF),
    onTertiaryContainer = Color(0xFF2A1250),
    error = Color(0xFFCC3352),
    onError = Color.White,
    errorContainer = Color(0xFFFFDBE0),
    onErrorContainer = Color(0xFF40000F),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF151A26),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF151A26),
    surfaceVariant = Color(0xFFE9ECF4),
    onSurfaceVariant = Color(0xFF515A70),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F7FB),
    surfaceContainer = Color(0xFFEFF2F8),
    surfaceContainerHigh = Color(0xFFE7EAF2),
    surfaceContainerHighest = Color(0xFFDFE3EC),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDFE3EC),
    inverseSurface = Color(0xFF151A26),
    inverseOnSurface = Color(0xFFF6F7FB),
    inversePrimary = Tone.Blue,
    outline = Color(0xFFC9CFDC),
    outlineVariant = Color(0xFFE1E5EE),
    scrim = Color.Black,
)

val MonoFamily = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal))

private val AppTypography: Typography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
        bodyLarge = t.bodyLarge.copy(letterSpacing = 0.sp),
        bodyMedium = t.bodyMedium.copy(letterSpacing = 0.sp),
    )
}

val CodeStyle = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, letterSpacing = 0.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** True when the app is currently showing its dark palette. */
val LocalIsDark = staticCompositionLocalOf { true }

@Composable
fun FlintTermTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as? App
    val settings = app?.store?.settings?.collectAsStateWithLifecycle()?.value
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings?.appTheme ?: AppTheme.DARK) {
        AppTheme.SYSTEM -> systemDark
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
    }
    val dynamic = settings?.dynamicColor == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalIsDark provides dark) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/** Accent palette for host cards; index stored in Host.color (0 = auto by id). */
val HostAccents = listOf(Tone.Blue, Tone.Mint, Tone.Purple, Tone.Amber, Tone.Red, Tone.Cyan, Tone.Orange, Tone.Green, Tone.Pink)

fun accentFor(seed: String, stored: Int): Color =
    if (stored != 0) Color(stored) else HostAccents[Math.floorMod(seed.hashCode(), HostAccents.size)]

@Composable
fun surfaceTint(color: Color, alpha: Float = 0.16f): Color = color.copy(alpha = alpha)

/** Tiny helpers for consistent muted text. */
val ColorScheme.muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
