package dev.chagok.app.ui

// 차곡차곡 brand layer: blocks where one cell is 1% of a fill-up. Every color here has a meaning:
// a person's color is their block, the navy tile is the car speaking, yellow is what nobody named yet.
// Values and contrast checks: docs/brand/README.md.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.chagok.app.R

/** A block's fill and the color of anything written on it. */
@Immutable
data class Paint(val fill: Color, val on: Color)

@Immutable
data class ChagokColors(
    val canvas: Color,
    /** Tiles. */
    val surface: Color,
    /** The tab bar and quiet panels, one step below the canvas. */
    val sunken: Color,
    val ink: Color,
    val inkSecondary: Color,
    val muted: Color,
    /** A number that is still zero. */
    val faint: Color,
    val line: Color,
    val divider: Color,
    /** Pressed state of a white button or row. */
    val pressed: Color,
    /** The navy tile: the car speaking, or the one thing to answer. */
    val strong: Color,
    val strongPressed: Color,
    val onStrong: Color,
    val onStrongSecondary: Color,
    val tabSelected: Color,
    val onTabSelected: Color,
    val field: Color,
    val placeholder: Color,
    val switchOn: Color,
    val switchOff: Color,
    val disabled: Color,
    val onDisabled: Color,
    val focus: Color,
    /** An empty cell of the 10 x 10 grid. */
    val cell: Color,
    /** Family members, by registration order. */
    val family: List<Paint>,
    /** "내 길". */
    val self: Paint,
    /** Distance someone else drove: a load taken over, so a neutral slate. */
    val otherDriver: Paint,
    /** What nobody named yet. */
    val unclassified: Paint,
)

private val Navy = Color(0xFF13213D)
private val White = Color(0xFFFFFFFF)

// Each fill carries its text at 4.5:1 or more: white on blue 4.6, green 5.1, purple 4.8; navy on the rest.
private val Blocks = listOf(
    Paint(Color(0xFF3B72D6), White),
    Paint(Color(0xFFF2A7B8), Navy),
    Paint(Color(0xFFE98B4F), Navy),
    Paint(Color(0xFF7A62C9), White),
)
private val SelfBlock = Paint(Color(0xFF2F7B57), White)
private val OtherBlock = Paint(Color(0xFF8A99AD), Navy)
private val UnclassifiedBlock = Paint(Color(0xFFF2B93B), Navy)

val ChagokLightColors = ChagokColors(
    canvas = Color(0xFFEEF4F9),
    surface = White,
    sunken = Color(0xFFDCE5EE),
    ink = Navy,
    inkSecondary = Color(0xFF4F5D75), // 6.7:1 on white, 6.0:1 on the canvas
    muted = Color(0xFF8A99AD),
    faint = Color(0xFFC3CFDD),
    line = Color(0xFFC9D5E1),
    divider = Color(0xFFE3EAF1),
    pressed = Color(0xFFE6EDF5),
    strong = Navy,
    strongPressed = Color(0xFF22345A),
    onStrong = White,
    onStrongSecondary = Color(0xFFB8C6DE), // 9.3:1 on navy, 6.9:1 on the dark navy
    tabSelected = Navy,
    onTabSelected = White,
    field = Color(0xFFEEF4F9),
    placeholder = Color(0xFF5E6C82), // 4.8:1 on the field
    switchOn = Navy,
    switchOff = Color(0xFFB8C6D4),
    disabled = Color(0xFFE3EAF1),
    onDisabled = Color(0xFF56647A), // 4.9:1 on the disabled fill
    focus = Color(0xFF3B72D6),
    cell = Color(0xFFE2EAF2),
    family = Blocks,
    self = SelfBlock,
    otherDriver = OtherBlock,
    unclassified = UnclassifiedBlock,
)

val ChagokDarkColors = ChagokColors(
    canvas = Color(0xFF0B1220),
    surface = Color(0xFF172235),
    sunken = Color(0xFF111B2D),
    ink = Color(0xFFEEF3F9),
    inkSecondary = Color(0xFFA7B4C8), // 7.6:1 on the dark tile
    muted = Color(0xFF6E7E96),
    faint = Color(0xFF34435E),
    line = Color(0xFF34435E),
    divider = Color(0xFF25324A),
    pressed = Color(0xFF22304A),
    strong = Color(0xFF22365E),
    strongPressed = Color(0xFF2C4472),
    onStrong = White,
    onStrongSecondary = Color(0xFFB8C6DE),
    tabSelected = Color(0xFFEEF3F9),
    onTabSelected = Color(0xFF0B1220),
    field = Color(0xFF0B1220),
    placeholder = Color(0xFF8592A8),
    switchOn = Color(0xFF3B72D6),
    switchOff = Color(0xFF34435E),
    disabled = Color(0xFF25324A),
    onDisabled = Color(0xFF8592A8),
    focus = Color(0xFF8FB2F0),
    cell = Color(0xFF1A2639),
    family = Blocks,
    self = SelfBlock,
    otherDriver = OtherBlock,
    unclassified = UnclassifiedBlock,
)

val LocalChagokColors = staticCompositionLocalOf { ChagokLightColors }

object Chagok {
    val colors: ChagokColors
        @Composable @ReadOnlyComposable
        get() = LocalChagokColors.current
}

/** Stable color per family member: by registration order, so a person keeps one color everywhere. */
fun ChagokColors.forPerson(personId: Long, familyIds: List<Long>): Paint {
    val index = familyIds.sorted().indexOf(personId)
    return if (index < 0) self else family[index % family.size]
}

// ---------------------------------------------------------------------------------------------
// Type. Body text stays Asta Sans (the system face); the display face and the figures are the brand's.
// Do Hyeon and Bricolage Grotesque are bundled under SIL OFL 1.1 (DO_HYEON_OFL.txt,
// BRICOLAGE_GROTESQUE_OFL.txt). Bricolage is cut to static condensed instances (wdth 75).

private val DoHyeon = FontFamily(Font(R.font.do_hyeon_regular, FontWeight.Normal))

/** Optical size 96: figures from about 30 up. */
private val FiguresDisplay = FontFamily(
    Font(R.font.bricolage_display_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_display_bold, FontWeight.Bold),
)

/** Optical size 18: figures inside running text. */
private val FiguresText = FontFamily(Font(R.font.bricolage_text_bold, FontWeight.Bold))

private val Tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

object ChagokType {
    /** The brand's display face, for headings and names. */
    fun display(size: Int, lineHeight: Float = 1.1f): TextStyle =
        KoreanText.copy(fontFamily = DoHyeon, fontWeight = FontWeight.Normal, fontSize = size.sp, lineHeight = (size * lineHeight).sp, lineHeightStyle = Tight)

    fun body(size: Int, weight: FontWeight = FontWeight.Normal, lineHeight: Float = 1.45f): TextStyle =
        KoreanText.copy(fontWeight = weight, fontSize = size.sp, lineHeight = (size * lineHeight).sp)

    /** Condensed tabular figures; the big ones set tight like the odometer. */
    fun figures(size: Int, weight: FontWeight = FontWeight.Bold): TextStyle {
        val big = size >= 30
        return TextStyle(
            fontFamily = if (big) FiguresDisplay else FiguresText,
            fontWeight = if (big) weight else FontWeight.Bold,
            fontSize = size.sp,
            lineHeight = (size * if (big) 0.86f else 1.2f).sp,
            lineHeightStyle = Tight,
            letterSpacing = if (size >= 96) (-0.035).em else if (big) (-0.02).em else 0.em,
            fontFeatureSettings = "tnum",
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The mark: an L-shaped block with a square resting in its corner, on a 30 x 30 grid. The same
// geometry is the launcher icon (res/drawable/ic_launcher_*).

private val MarkL = PathParser().parsePathString(
    "M0 3.5A3.5 3.5 0 0 1 3.5 0L9.5 0A3.5 3.5 0 0 1 13 3.5L13 11.1A5.9 5.9 0 0 0 18.9 17L26.5 17" +
        "A3.5 3.5 0 0 1 30 20.5L30 26.5A3.5 3.5 0 0 1 26.5 30L3.5 30A3.5 3.5 0 0 1 0 26.5Z",
).toPath()
private val MarkSquare = PathParser().parsePathString(
    "M15.4 3.5A3.5 3.5 0 0 1 18.9 0L26.5 0A3.5 3.5 0 0 1 30 3.5L30 11.1A3.5 3.5 0 0 1 26.5 14.6" +
        "L18.9 14.6A3.5 3.5 0 0 1 15.4 11.1Z",
).toPath()

@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 30.dp) {
    val blue = Blocks[0].fill
    val yellow = UnclassifiedBlock.fill
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        scale(this.size.minDimension / 30f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(MarkL, blue)
            drawPath(MarkSquare, yellow)
        }
    }
}
