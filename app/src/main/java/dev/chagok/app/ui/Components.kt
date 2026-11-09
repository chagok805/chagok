package dev.chagok.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** "3,420" with no unit, for display numbers set apart from their unit. */
fun kmNumber(value: Float): String = String.format(Locale.ROOT, "%,d", value.roundToInt())

// ---------------------------------------------------------------------------------------------
// The page

/** Top-level destinations in the tab bar. */
enum class Tab(val label: String) { HOME("홈"), SETTLEMENT("결산"), DAYS("그날"), SETTINGS("설정") }

/**
 * Every screen is laid out on a 1280 x 800 canvas and scaled to the window, so sizes below are the
 * design's own. Landscape fits the canvas to the window (the extra height goes to the tiles that
 * grow); the portrait window (840dp) gets one column at 560 wide. Nothing assumes either size.
 */
@Composable
fun DesignFrame(content: @Composable BoxScope.(wide: Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Chagok.colors.canvas)) {
        val wide = maxWidth >= 1000.dp && maxWidth >= maxHeight
        val scale = if (wide) min(maxWidth / 1280.dp, maxHeight / 800.dp) else maxWidth / 560.dp
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
            Box(Modifier.fillMaxSize()) { content(wide) }
        }
    }
}

/**
 * The two-column page of the design: tiles on the left above the tab bar, the big picture on the
 * right. In the portrait window the right side follows the left tiles in one scrolling column.
 */
@Composable
fun TilePage(
    wide: Boolean,
    tab: Tab?,
    onTab: (Tab) -> Unit,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable BoxScope.() -> Unit,
    /** Height of the right side when it follows the tiles in the portrait window. */
    narrowRightHeight: Dp = 600.dp,
    showTabs: Boolean = true,
) {
    if (wide) {
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.width(516.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                left()
                if (showTabs && tab != null) TabBar(tab, onTab)
            }
            Box(Modifier.weight(1f).fillMaxHeight(), content = right)
        }
    } else {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 44.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                left()
                Box(Modifier.fillMaxWidth().height(narrowRightHeight), content = right)
            }
            if (showTabs && tab != null) TabBar(tab, onTab)
        }
    }
}

/** A tap on anything that is not itself a control lets go of the text field, and the keyboard with it. */
fun Modifier.clearFocusOnTap(focus: FocusManager): Modifier = pointerInput(focus) { detectTapGestures { focus.clearFocus() } }

/** The tile that takes the spare height in landscape; a fixed height in the scrolling portrait column. */
fun ColumnScope.grow(wide: Boolean, narrow: Dp): Modifier = if (wide) Modifier.weight(1f) else Modifier.height(narrow)

/** Page navigation: four places, the current one filled. */
@Composable
fun TabBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val b = Chagok.colors
    Row(
        modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(32.dp)).background(b.sunken).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tab.entries.forEach { tab ->
            val on = tab == current
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pressScale(pressed)
                    .clip(RoundedCornerShape(32.dp))
                    .background(if (on) b.tabSelected else if (pressed) b.surface else Color.Transparent)
                    .selectable(on, interaction, null, role = Role.Tab) { onSelect(tab) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    style = ChagokType.body(18, if (on) FontWeight.ExtraBold else FontWeight.Bold, 1.2f),
                    color = if (on) b.onTabSelected else b.ink,
                )
            }
        }
    }
}

/** A tile: one rounded surface on the canvas. */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    color: Color = Chagok.colors.surface,
    padding: PaddingValues = PaddingValues(horizontal = 40.dp, vertical = 36.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.clip(RoundedCornerShape(32.dp)).background(color).padding(padding), content = content)
}

/** The wordmark row at the top of a page tile, with the demonstration tag when the data is seeded. */
@Composable
fun BrandRow(seeded: Boolean, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val b = Chagok.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BrandMark(size = 30.dp)
        Text(
            "차곡차곡",
            style = ChagokType.body(22, FontWeight.ExtraBold, 1.2f).copy(letterSpacing = (-0.03).em),
            color = b.ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (seeded) DemoTag()
        trailing()
    }
}

/** "시연용 데이터": every seeded screen says so. */
@Composable
fun DemoTag(modifier: Modifier = Modifier) {
    val b = Chagok.colors
    Row(
        modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, b.line, RoundedCornerShape(999.dp)).padding(start = 12.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(b.muted))
        Text("시연용 데이터", style = ChagokType.body(16, FontWeight.SemiBold, 1.3f), color = b.inkSecondary)
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons

/** Where a button sits decides its look: white and ghost on the navy tile, strong and line on white. */
enum class PillStyle { WHITE, GHOST, STRONG, LINE, SOFT, TINT }

/** Shrinks to the Pleos pressed scale while held. */
fun Modifier.pressScale(pressed: Boolean): Modifier = graphicsLayer {
    val s = if (pressed) PleosMotion.PRESSED_SCALE else 1f
    scaleX = s
    scaleY = s
}

@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.WHITE,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    textSize: Int = 17,
    padding: Dp = 20.dp,
) {
    val b = Chagok.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PleosMotion.PRESSED_SCALE else 1f, tween(PleosMotion.SHORT1), label = "press")
    val (fill, ink) = when {
        !enabled -> b.disabled to b.onDisabled
        style == PillStyle.WHITE -> (if (pressed) b.pressed else Color.White) to Color(0xFF13213D)
        style == PillStyle.GHOST -> Color.White.copy(alpha = if (pressed) 0.22f else 0.12f) to Color.White
        style == PillStyle.STRONG -> (if (pressed) b.strongPressed else b.strong) to b.onStrong
        style == PillStyle.SOFT -> (if (pressed) b.pressed else b.canvas) to b.ink
        // On a light block, whatever the screen mode: navy ink on a navy wash.
        style == PillStyle.TINT -> Color(0xFF13213D).copy(alpha = if (pressed) 0.18f else 0.1f) to Color(0xFF13213D)
        else -> (if (pressed) b.canvas else Color.Transparent) to b.ink
    }
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier
            .heightIn(min = height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(fill)
            .then(if (style == PillStyle.LINE && enabled) Modifier.border(2.dp, b.ink, shape) else Modifier)
            .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.size(textSize.dp))
        Text(text, style = ChagokType.body(textSize, FontWeight.ExtraBold, 1.2f), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round icon-only button. Always carries a description. */
@Composable
fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: PillStyle = PillStyle.GHOST, size: Dp = 56.dp) {
    val b = Chagok.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val (fill, ink) = when (style) {
        PillStyle.GHOST -> Color.White.copy(alpha = if (pressed) 0.22f else 0.12f) to Color.White
        PillStyle.STRONG -> (if (pressed) b.strongPressed else b.strong) to b.onStrong
        else -> (if (pressed) b.pressed else b.canvas) to b.ink
    }
    Box(
        modifier
            .size(size)
            .pressScale(pressed)
            .clip(CircleShape)
            .background(fill)
            .clickable(interaction, null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(size * 0.38f))
    }
}

/** A choice among a few, as a pill: on the navy tile it fills with the block's color once picked. */
@Composable
fun ChoiceOnStrong(text: String, paint: Paint?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = if (selected && paint != null) paint.fill else Color.Transparent
    val ink = if (selected && paint != null) paint.on else Color.White
    Row(
        modifier
            .height(60.dp)
            .pressScale(pressed)
            .clip(RoundedCornerShape(30.dp))
            .background(fill)
            .then(if (!selected) Modifier.border(2.dp, Color.White.copy(alpha = if (pressed) 0.5f else 0.28f), RoundedCornerShape(30.dp)) else Modifier)
            .selectable(selected, interaction, null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (paint != null) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(if (selected) paint.on else Color.White).padding(2.dp).clip(CircleShape).background(paint.fill))
        }
        Text(text, style = ChagokType.body(18, FontWeight.ExtraBold, 1.2f), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A choice on a white tile: navy once picked. */
@Composable
fun ChoiceOnSurface(text: String, dot: Color?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val b = Chagok.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .height(56.dp)
            .pressScale(pressed)
            .clip(RoundedCornerShape(28.dp))
            .background(if (selected) b.strong else if (pressed) b.pressed else b.canvas)
            .selectable(selected, interaction, null, role = Role.RadioButton, onClick = onClick)
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (dot != null) {
            Box(
                Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(dot)
                    .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier),
            )
        }
        Text(text, style = ChagokType.body(18, FontWeight.ExtraBold, 1.2f), color = if (selected) b.onStrong else b.ink, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Figures

/** A big distance: figures with the unit set smaller beside them, read as one number. */
@Composable
fun Distance(
    km: Float,
    size: Int,
    modifier: Modifier = Modifier,
    color: Color = Chagok.colors.ink,
    unitColor: Color = Chagok.colors.inkSecondary,
    unitSize: Int = (size * 0.22f).roundToInt().coerceAtLeast(18),
    text: String = kmNumber(km),
) {
    Row(modifier.clearAndSetSemantics { contentDescription = "${kmNumber(km)}킬로미터" }, verticalAlignment = Alignment.Bottom) {
        Text(text, style = ChagokType.figures(size), color = color, modifier = Modifier.alignByBaseline())
        Text(
            "km",
            style = ChagokType.figures(unitSize, FontWeight.SemiBold),
            color = unitColor,
            modifier = Modifier.alignByBaseline().padding(start = (size * 0.05f).dp),
        )
    }
}

/** "8시간 20분" with the numbers as figures and the units small, like the design's time tiles. */
@Composable
fun TimeFigure(minutes: Long, size: Int, color: Color = Chagok.colors.ink, unitColor: Color = Chagok.colors.inkSecondary) {
    val hours = minutes / 60
    val rest = minutes % 60
    val figure = ChagokType.figures(size).toSpanStyle()
    val unit = ChagokType.body(16, FontWeight.SemiBold).toSpanStyle().copy(color = unitColor)
    val text = buildAnnotatedString {
        if (hours > 0) {
            withStyle(figure) { append("$hours") }
            withStyle(unit) { append(" 시간 ") }
        }
        if (rest > 0 || hours == 0L) {
            withStyle(figure) { append("$rest") }
            withStyle(unit) { append(" 분") }
        }
    }
    Text(text, color = color, style = TextStyle(lineHeight = (size * 1.1f).sp))
}

/** A figure inside running text: "612km" set in figures, inline. */
fun figureSpan(size: Int, color: Color): SpanStyle = ChagokType.figures(size).toSpanStyle().copy(color = color)

// ---------------------------------------------------------------------------------------------
// Proposals are hatched

/** White diagonal hatch over [rect]: a stretch the car proposed and nobody confirmed yet. */
fun DrawScope.hatch(left: Float, top: Float, right: Float, bottom: Float, period: Float = 10.dp.toPx(), stroke: Float = 4.dp.toPx()) {
    if (right <= left || bottom <= top) return
    clipRect(left, top, right, bottom) {
        drawRect(Color.White.copy(alpha = 0.12f), Offset(left, top), Size(right - left, bottom - top))
        val h = bottom - top
        var x = left - h
        while (x < right) {
            drawLine(Color.White.copy(alpha = 0.55f), Offset(x, bottom), Offset(x + h, top), strokeWidth = stroke)
            x += period
        }
    }
}

/** The key that says "hatched means proposed". */
@Composable
fun HatchSwatch(color: Color, modifier: Modifier = Modifier, width: Dp = 22.dp, height: Dp = 14.dp) {
    Canvas(modifier.size(width, height).clip(RoundedCornerShape(4.dp))) {
        drawRect(color)
        hatch(0f, 0f, size.width, size.height, period = 7.dp.toPx(), stroke = 3.dp.toPx())
    }
}

/** A small square of a block's color; hatched when it is only a proposal. */
@Composable
fun Swatch(color: Color, modifier: Modifier = Modifier, size: Dp = 36.dp, radius: Dp = 12.dp, hatched: Boolean = false, content: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier.size(size).clip(RoundedCornerShape(radius)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(color)
            if (hatched) hatch(0f, 0f, this.size.width, this.size.height, period = 8.dp.toPx(), stroke = 3.dp.toPx())
        }
        content()
    }
}

// ---------------------------------------------------------------------------------------------
// Controls on white tiles

/** Switch: 84 x 48 track, 40 knob. Visual only; the row owns the touch. */
@Composable
fun ChagokSwitch(checked: Boolean) {
    val b = Chagok.colors
    val track by animateColorAsState(if (checked) b.switchOn else b.switchOff, tween(PleosMotion.SHORT2), label = "track")
    val knob by animateDpAsState(if (checked) 40.dp else 4.dp, tween(PleosMotion.MEDIUM1, easing = PleosMotion.Tension), label = "knob")
    Box(Modifier.size(width = 84.dp, height = 48.dp).clip(RoundedCornerShape(24.dp)).background(track)) {
        Box(Modifier.offset(x = knob, y = 4.dp).size(40.dp).clip(CircleShape).background(Color.White))
    }
}

/** A row that toggles as a whole: title, what it means, the switch. */
@Composable
fun ToggleRow(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val b = Chagok.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 108.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (pressed) b.pressed else Color.Transparent)
            .toggleable(value, interaction, null, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = ChagokType.body(22, FontWeight.ExtraBold, 1.25f), color = b.ink)
            Text(detail, style = ChagokType.body(17), color = b.inkSecondary)
        }
        ChagokSwitch(value)
    }
}

/** Text field: 60 tall on the canvas color, a focus ring instead of a border. Parked screens only. */
@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /** Whether the field holds the keyboard, so the page can move what the keyboard would cover. */
    onTyping: (Boolean) -> Unit = {},
) {
    val b = Chagok.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = LocalFocusManager.current
    LaunchedEffect(focused) { onTyping(focused) }
    val shape = RoundedCornerShape(20.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = ChagokType.body(19, lineHeight = 1.3f).copy(color = b.ink),
        cursorBrush = SolidColor(b.ink),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(shape)
                    .background(b.field)
                    .then(if (focused) Modifier.border(3.dp, b.focus, shape) else Modifier)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = ChagokType.body(19, lineHeight = 1.3f), color = b.placeholder, maxLines = 1)
                inner()
            }
        },
    )
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = ChagokType.body(16, FontWeight.Bold, 1.3f), color = Chagok.colors.inkSecondary, modifier = modifier)
}

@Composable
fun Divider(modifier: Modifier = Modifier, color: Color = Chagok.colors.divider) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun Heading(text: String, size: Int, modifier: Modifier = Modifier, color: Color = Chagok.colors.ink) {
    Text(text, style = ChagokType.display(size), color = color, modifier = modifier.semantics { heading() })
}

// ---------------------------------------------------------------------------------------------
// QR

/** A QR with no external service involved: the line travels in the code itself. Always dark on white. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1))
    }
    Canvas(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(12.dp)
            .semantics { contentDescription = "폰으로 옮길 QR 코드" },
    ) {
        val cell = size.minDimension / matrix.width
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix[x, y]) drawRect(Color(0xFF13213D), Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@Composable
fun QrPlaceholder(modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(20.dp)).clearAndSetSemantics {}) {
        val stroke = 2.dp.toPx()
        drawRoundRect(
            Color.White.copy(alpha = 0.4f),
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(20.dp.toPx()),
            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 8.dp.toPx()))),
        )
    }
}

/** A list row on a white tile: what it is, a quiet line under it, an optional value. */
@Composable
fun ListRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    clickLabel: String? = null,
    height: Dp = 72.dp,
    lead: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val b = Chagok.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .clip(RoundedCornerShape(20.dp))
            .background(if (pressed) b.pressed else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(interaction, null, onClickLabel = clickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        lead()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        trailing()
    }
}

/** Small spacing helper so tiles read like the design's flex gaps. */
@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))

/** Marks a radio-like choice as selected for screen readers when the visual is custom. */
fun Modifier.selectedChoice(on: Boolean): Modifier = semantics { selected = on }
