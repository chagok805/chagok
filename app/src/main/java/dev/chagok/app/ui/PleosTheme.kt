package dev.chagok.app.ui

// Pleos Connect design tokens (Figma "Pleos_Connect_design_assets_215" via docs/pleos/design/tokens.md).
// Asta Sans is bundled under SIL OFL 1.1 (see ASTA_SANS_OFL.txt at the template root).

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.chagok.app.R


@Immutable
data class PleosColors(
    val backgroundPrimary: Color,
    val backgroundSecondary: Color,
    val backgroundPopup: Color,
    val surfaceBasic: Color,
    val surfaceLow: Color,
    val surfaceHigh: Color,
    val surfaceAccent: Color,
    val textDarkPrimary: Color,
    val textDarkSecondary: Color,
    val textDarkTertiary: Color,
    val textDarkQuaternary: Color,
    val textLightPrimary: Color,
    val textLightSecondary: Color,
    val textLightTertiary: Color,
    val textLightQuaternary: Color,
    val iconDarkPrimary: Color,
    val iconDarkSecondary: Color,
    val iconDarkTertiary: Color,
    val iconDarkQuaternary: Color,
    val buttonBasicEnabled: Color,
    val buttonBasicPressed: Color,
    val buttonBasicDisabled: Color,
    val buttonFilledEnabled: Color,
    val buttonFilledPressed: Color,
    val buttonFilledDisabled: Color,
    val buttonSwitchEnabled: Color,
    val buttonSwitchPressed: Color,
    val buttonSwitchDisabled: Color,
    val switchOn: Color,
    val switchOff: Color,
    val switchDisabled: Color,
    val switchKnobOn: Color,
    val switchKnobOff: Color,
    val switchKnobDisabled: Color,
    val informativeActive: Color,
    val informativePositive: Color,
    val informativeNegative: Color,
    val informativeAutonomousDriving: Color,
    val regulationBlue: Color,
    val regulationGreen: Color,
    val regulationYellow: Color,
    val regulationOrange: Color,
    val regulationRed: Color,
    val fieldBackground: Color,
    val fieldFocused: Color,
    val dropdownNormal: Color,
    val dropdownPressed: Color,
    val controllerSliderNormal: Color,
    val controllerSliderPressed: Color,
    val controllerSliderKnob: Color,
    val controllerTabBackground: Color,
    val controllerStepperBackground: Color,
    val dimDarkPrimary: Color,
    val dimDarkSecondary: Color,
    val dimDarkTertiary: Color,
    val overlayDefault: Color,
    val dividerPrimary: Color,
    val dividerSecondary: Color,
    val dividerTertiary: Color,
)

val PleosLightColors = PleosColors(
    backgroundPrimary = Color(0xFFF7F8FA),
    backgroundSecondary = Color(0xFFFFFFFF),
    backgroundPopup = Color(0xFFFFFFFF),
    surfaceBasic = Color(0xFFFFFFFF),
    surfaceLow = Color(0xFFF7F8FA),
    surfaceHigh = Color(0xFFEDEEF2),
    surfaceAccent = Color(0xFFE0E1E6),
    textDarkPrimary = Color(0xFF131417),
    textDarkSecondary = Color(0xD6131417),
    textDarkTertiary = Color(0xA3131417),
    textDarkQuaternary = Color(0x52131417),
    textLightPrimary = Color(0xFFFFFFFF),
    textLightSecondary = Color(0xD6FFFFFF),
    textLightTertiary = Color(0xA3FFFFFF),
    textLightQuaternary = Color(0x52FFFFFF),
    iconDarkPrimary = Color(0xFF131417),
    iconDarkSecondary = Color(0xD6131417),
    iconDarkTertiary = Color(0xA3131417),
    iconDarkQuaternary = Color(0x52131417),
    buttonBasicEnabled = Color(0x0D131417),
    buttonBasicPressed = Color(0x1A131417),
    buttonBasicDisabled = Color(0x33FFFFFF),
    buttonFilledEnabled = Color(0xFF44464E),
    buttonFilledPressed = Color(0xFF313236),
    buttonFilledDisabled = Color(0x0D131417),
    buttonSwitchEnabled = Color(0xFFFFFFFF),
    buttonSwitchPressed = Color(0x80FFFFFF),
    buttonSwitchDisabled = Color(0x4DFFFFFF),
    switchOn = Color(0xFF02C265),
    switchOff = Color(0x4D131417),
    switchDisabled = Color(0x1A131417),
    switchKnobOn = Color(0xFFFFFFFF),
    switchKnobOff = Color(0xFFFFFFFF),
    switchKnobDisabled = Color(0xFFFFFFFF),
    informativeActive = Color(0xFF02C265),
    informativePositive = Color(0xFF0064FF),
    informativeNegative = Color(0xFFFE3D16),
    informativeAutonomousDriving = Color(0xFF5A46FA),
    regulationBlue = Color(0xFF0064FF),
    regulationGreen = Color(0xFF00BA13),
    regulationYellow = Color(0xFFFFC224),
    regulationOrange = Color(0xFFFF8A00),
    regulationRed = Color(0xFFF62E24),
    fieldBackground = Color(0xFFFFFFFF),
    fieldFocused = Color(0xFF313236),
    dropdownNormal = Color(0xFFFFFFFF),
    dropdownPressed = Color(0xFFEDEEF2),
    controllerSliderNormal = Color(0x33131417),
    controllerSliderPressed = Color(0x4D131417),
    controllerSliderKnob = Color(0xFFFFFFFF),
    controllerTabBackground = Color(0x0D131417),
    controllerStepperBackground = Color(0xFFFFFFFF),
    dimDarkPrimary = Color(0x0D131417),
    dimDarkSecondary = Color(0x1A131417),
    dimDarkTertiary = Color(0x33131417),
    overlayDefault = Color(0x33131417),
    dividerPrimary = Color(0x0D131417),
    dividerSecondary = Color(0x1A131417),
    dividerTertiary = Color(0x33131417),
)

val PleosDarkColors = PleosColors(
    backgroundPrimary = Color(0xFF1D1E21),
    backgroundSecondary = Color(0xFF131417),
    backgroundPopup = Color(0xFF313236),
    surfaceBasic = Color(0xFF1D1E21),
    surfaceLow = Color(0xFF131417),
    surfaceHigh = Color(0xFF313236),
    surfaceAccent = Color(0xFF44464E),
    textDarkPrimary = Color(0xFFFFFFFF),
    textDarkSecondary = Color(0xD6FFFFFF),
    textDarkTertiary = Color(0xA3FFFFFF),
    textDarkQuaternary = Color(0x52FFFFFF),
    textLightPrimary = Color(0xFF131417),
    textLightSecondary = Color(0xD6131417),
    textLightTertiary = Color(0xA3131417),
    textLightQuaternary = Color(0x52131417),
    iconDarkPrimary = Color(0xFFFFFFFF),
    iconDarkSecondary = Color(0xD6FFFFFF),
    iconDarkTertiary = Color(0xA3FFFFFF),
    iconDarkQuaternary = Color(0x52FFFFFF),
    buttonBasicEnabled = Color(0x0DFFFFFF),
    buttonBasicPressed = Color(0x1AFFFFFF),
    buttonBasicDisabled = Color(0x33131417),
    buttonFilledEnabled = Color(0xFFE0E1E6),
    buttonFilledPressed = Color(0xFFC7C9CE),
    buttonFilledDisabled = Color(0x0DFFFFFF),
    buttonSwitchEnabled = Color(0x33FFFFFF),
    buttonSwitchPressed = Color(0x33FFFFFF),
    buttonSwitchDisabled = Color(0x4DFFFFFF),
    switchOn = Color(0xFF00E074),
    switchOff = Color(0x1AFFFFFF),
    switchDisabled = Color(0x1AFFFFFF),
    switchKnobOn = Color(0xFFFFFFFF),
    switchKnobOff = Color(0xFFA4A8AE),
    switchKnobDisabled = Color(0x33FFFFFF),
    informativeActive = Color(0xFF00E074),
    informativePositive = Color(0xFF0082FF),
    informativeNegative = Color(0xFFFF4C28),
    informativeAutonomousDriving = Color(0xFF6A57FF),
    regulationBlue = Color(0xFF0082FF),
    regulationGreen = Color(0xFF55F165),
    regulationYellow = Color(0xFFFFC224),
    regulationOrange = Color(0xFFFF8A00),
    regulationRed = Color(0xFFFF4339),
    fieldBackground = Color(0xFF131417),
    fieldFocused = Color(0xFFEDEEF2),
    dropdownNormal = Color(0x1AFFFFFF),
    dropdownPressed = Color(0x4DFFFFFF),
    controllerSliderNormal = Color(0x1AFFFFFF),
    controllerSliderPressed = Color(0x33FFFFFF),
    controllerSliderKnob = Color(0x4DFFFFFF),
    controllerTabBackground = Color(0x0DFFFFFF),
    controllerStepperBackground = Color(0x1A131417),
    dimDarkPrimary = Color(0x0DFFFFFF),
    dimDarkSecondary = Color(0x1AFFFFFF),
    dimDarkTertiary = Color(0x33FFFFFF),
    overlayDefault = Color(0x80131417),
    dividerPrimary = Color(0x0DFFFFFF),
    dividerSecondary = Color(0x1AFFFFFF),
    dividerTertiary = Color(0x33FFFFFF),
)

val LocalPleosColors = staticCompositionLocalOf { PleosLightColors }

// Asta Sans bundled in res/font (Google Fonts, OFL).
val AstaSans = FontFamily(
    Font(R.font.asta_sans_regular, FontWeight.Normal),
    Font(R.font.asta_sans_bold, FontWeight.Bold),
    Font(R.font.asta_sans_extrabold, FontWeight.ExtraBold),
)

/**
 * Every style breaks lines at word boundaries in Korean, instead of between any two syllables, and
 * balances short text so no word is left alone on a last line. Sentences wrap the way they are read.
 * The locale is fixed because all copy in this app is Korean, whatever language the car is set to.
 */
internal val KoreanText = TextStyle(
    fontFamily = AstaSans,
    localeList = LocaleList("ko-KR"),
    lineBreak = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase),
)

/** Type scale for Connect-S-17 (2560x1440, 160 dpi). Adjust weight with copy(fontWeight = ...). */
object PleosType {
    val headlineLarge = KoreanText.copy(fontWeight = FontWeight.Bold, fontSize = 60.sp, lineHeight = 80.sp)
    val headlineMedium = KoreanText.copy(fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 72.sp)
    val headlineSmall = KoreanText.copy(fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 64.sp)
    val titleLarge = KoreanText.copy(fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 52.sp)
    val titleMedium = KoreanText.copy(fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp)
    val titleSmall = KoreanText.copy(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp)
    val bodyLarge = KoreanText.copy(fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 38.sp)
    val bodyMedium = KoreanText.copy(fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 36.sp)
    val bodySmall = KoreanText.copy(fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 34.sp)
    val labelMedium = KoreanText.copy(fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 28.sp)
    val labelSmall = KoreanText.copy(fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 24.sp)
    val drivingSpeedKmh = KoreanText.copy(fontWeight = FontWeight.Bold, fontSize = 112.sp, lineHeight = 134.sp, letterSpacing = (-0.03).em)
    /** The big distance: Driving/Speed style with tabular figures so a counting number does not jitter. */
    val distanceDisplay = drivingSpeedKmh.copy(fontFeatureSettings = "tnum")
    val drivingTrafficSign = KoreanText.copy(fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = (-0.03).em)
}

object PleosMotion {
    const val SHORT1 = 100; const val SHORT2 = 150
    const val MEDIUM1 = 250; const val MEDIUM2 = 300
    const val LONG1 = 400; const val LONG2 = 450
    val Tension = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    const val PRESSED_SCALE = 0.95f
}

object PleosDimens {
    val ButtonLarge = 100.dp; val ButtonMedium = 80.dp; val ButtonSmall = 64.dp
    val ButtonRadiusLarge = 32.dp; val ButtonRadiusMedium = 24.dp; val ButtonRadiusSmall = 16.dp
    val IconButtonMedium = 80.dp; val IconButtonSmall = 64.dp
    val MinTouchTarget = 64.dp   // smallest interactive size in the design system (inferred minimum)
    val WindowRadius = 40.dp
    val PopupRadius = 48.dp; val ToastRadius = 16.dp
    val PageNavigationHeight = 80.dp; val ListItemHeight = 112.dp
}


/**
 * Wraps content with Pleos colors that follow the system light/dark screen mode.
 * Material 3 components inside also get Pleos colors and type instead of the default purple;
 * the token-to-Material mapping is an approximation, prefer Pleos.colors / PleosType directly.
 */
@Composable
fun PleosTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) PleosDarkColors else PleosLightColors
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = colors.buttonFilledEnabled,
        onPrimary = colors.textLightPrimary,
        secondary = colors.informativePositive,
        background = brandCanvas(darkTheme),
        onBackground = colors.textDarkPrimary,
        surface = colors.surfaceBasic,
        onSurface = colors.textDarkPrimary,
        surfaceVariant = colors.surfaceHigh,
        onSurfaceVariant = colors.textDarkSecondary,
        error = colors.informativeNegative,
        outline = colors.dividerTertiary,
    )
    val typography = Typography(
        headlineLarge = PleosType.headlineLarge,
        headlineMedium = PleosType.headlineMedium,
        headlineSmall = PleosType.headlineSmall,
        titleLarge = PleosType.titleLarge,
        titleMedium = PleosType.titleMedium,
        titleSmall = PleosType.titleSmall,
        bodyLarge = PleosType.bodyLarge,
        bodyMedium = PleosType.bodyMedium,
        bodySmall = PleosType.bodySmall,
        labelLarge = PleosType.labelMedium,
        labelMedium = PleosType.labelMedium,
        labelSmall = PleosType.labelSmall,
    )
    val brand = if (darkTheme) ChagokDarkColors else ChagokLightColors
    CompositionLocalProvider(LocalPleosColors provides colors, LocalChagokColors provides brand) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

private fun brandCanvas(dark: Boolean) = if (dark) ChagokDarkColors.canvas else ChagokLightColors.canvas

object Pleos {
    val colors: PleosColors
        @Composable @ReadOnlyComposable
        get() = LocalPleosColors.current
}
