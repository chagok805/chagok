package dev.chagok.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chagok.app.ledger.Settlement
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One block of the map: who it is, how far, and how much of it is still only the car's proposal. */
data class Block(val key: String, val label: String, val km: Float, val proposedKm: Float, val paint: Paint)

object BlockKeys {
    const val SELF = "self"
    const val OTHER = "other"
    const val UNCLASSIFIED = "unclassified"
    fun person(id: Long) = "p:$id"
}

/**
 * The blocks of a settlement in map order: what nobody named first (top-left, where "정하기" is easy
 * to reach), then the rest from the smallest up, so the largest share is the base everything rests on.
 */
fun blocksOf(s: Settlement, familyIds: List<Long>, colors: ChagokColors): List<Block> {
    val named = s.family.map { t ->
        Block(BlockKeys.person(t.person.id), t.person.name, t.km, (t.km - t.confirmedKm).coerceAtLeast(0f), colors.forPerson(t.person.id, familyIds))
    } + listOf(
        Block(BlockKeys.SELF, "내 길", s.selfKm, (s.selfKm - s.selfConfirmedKm).coerceAtLeast(0f), colors.self),
        Block(BlockKeys.OTHER, "다른 사람", s.otherDriverKm, 0f, colors.otherDriver),
    )
    val shown = named.filter { it.km >= 0.5f }.sortedBy { it.km }
    val unclassified = Block(BlockKeys.UNCLASSIFIED, "미분류", s.unclassifiedKm, 0f, colors.unclassified).takeIf { it.km >= 0.5f }
    return listOfNotNull(unclassified) + shown
}

/** Spoken summary of the map, in the same order a sighted driver reads it: largest first. */
fun describe(blocks: List<Block>): String {
    val total = blocks.sumOf { it.km.toDouble() }.toFloat()
    return "거리만큼 나눈 블록 그림. " + blocks.sortedByDescending { it.km }.joinToString(". ") { b ->
        val pct = if (total > 0f) (b.km / total * 100).roundToInt() else 0
        buildString {
            append("${b.label} ${kmNumber(b.km)}킬로미터, $pct%")
            if (b.proposedKm >= 1f) append(", 그중 차의 제안 ${kmNumber(b.proposedKm)}킬로미터")
        }
    } + "."
}

/** Last layouts by blocks and size, so the map keeps its shape across screens and small changes. */
private object LayoutMemory {
    private val last = HashMap<String, BlockLayout.Result>()
    private fun key(keys: List<String>, w: Float, h: Float) = keys.joinToString(",") + "@" + w.roundToInt() + "x" + h.roundToInt()
    fun previous(keys: List<String>, w: Float, h: Float) = last[key(keys, w, h)]
    fun save(keys: List<String>, w: Float, h: Float, result: BlockLayout.Result) {
        if (last.size > 16) last.clear()
        last[key(keys, w, h)] = result
    }
}

private const val GAP_DP = 8f
private val FallIn = CubicBezierEasing(0.55f, 0f, 1f, 0.55f)
private val Land = CubicBezierEasing(0f, 0.5f, 0.5f, 1f)
private val Rise = CubicBezierEasing(0.5f, 0f, 1f, 0.5f)

/**
 * The map: each block's area is its share of the distance. Hatching is what the car proposed and
 * nobody confirmed yet. Blocks fall in once, the base first, the way things are stacked.
 *
 * [shapeFrom] keeps the shapes of another set of blocks while the numbers change, for previews.
 * [highlight] rings one block and quiets the rest.
 */
@Composable
fun BlockMap(
    blocks: List<Block>,
    modifier: Modifier = Modifier,
    shapeFrom: List<Block>? = null,
    highlight: String? = null,
    drop: Boolean = true,
    compact: Boolean = false,
    onBlock: ((String) -> Unit)? = null,
    onPick: (() -> Unit)? = null,
    extra: Pair<String, () -> Unit>? = null,
) {
    if (blocks.isEmpty()) return
    val b = Chagok.colors
    BoxWithConstraints(modifier.semantics { contentDescription = describe(blocks) }) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val shapes = shapeFrom?.takeIf { s -> s.map { it.key } == blocks.map { it.key } } ?: blocks
        val keys = shapes.map { it.key }
        val shares = shapes.map { it.km.coerceAtLeast(0.01f) }
        val result = remember(keys, shares, w, h) {
            BlockLayout.of(shares, w, h, LayoutMemory.previous(keys, w, h)).also { LayoutMemory.save(keys, w, h, it) }
        }
        val gap = with(density) { GAP_DP.dp.toPx() }
        val radius = with(density) { (minOf(maxWidth.value, maxHeight.value) * 0.037f).coerceIn(16f, 28f).dp.toPx() }
        val paths = remember(result, w, h, gap, radius) { blocks.indices.map { outline(result.runs(it), w, h, gap, radius) } }
        val total = blocks.sumOf { it.km.toDouble() }.toFloat().coerceAtLeast(0.01f)
        // The block under the finger, so it answers the touch the way a button does.
        var pressed by remember { mutableStateOf<String?>(null) }
        val tapModifier = if (onBlock != null) {
            Modifier.pointerInput(result, blocks) {
                detectTapGestures(
                    onPress = { p ->
                        pressed = result.blockAt(p.x / w, p.y / h)?.let { blocks[it].key }
                        tryAwaitRelease()
                        pressed = null
                    },
                    onTap = { p -> result.blockAt(p.x / w, p.y / h)?.let { onBlock(blocks[it].key) } },
                )
            }
        } else {
            Modifier
        }
        Box(Modifier.fillMaxSize().then(tapModifier)) {
            blocks.forEachIndexed { i, block ->
                val rank = blocks.lastIndex - i
                val fall = remember { Animatable(if (drop) -(h + 120f) else 0f) }
                if (drop) {
                    LaunchedEffect(Unit) {
                        fall.animateTo(
                            0f,
                            keyframes {
                                durationMillis = 950
                                delayMillis = 150 + rank * 320
                                -(h + 120f) at 0 using FallIn
                                0f at 665 using Land
                                -gap * 1.5f at 780 using Rise
                            },
                        )
                    }
                }
                val proposedShare by animateFloatAsState(block.proposedKm / total, tween(600, easing = PleosMotion.Tension), label = "hatch")
                val dim = highlight != null && highlight != block.key
                val alpha by animateFloatAsState(if (dim) 0.4f else 1f, tween(350), label = "dim")
                val press by animateFloatAsState(if (pressed == block.key) PleosMotion.PRESSED_SCALE else 1f, tween(PleosMotion.SHORT1), label = "press")
                val labelRun = result.labelRun(i, w, h)
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        translationY = fall.value
                        this.alpha = alpha
                        // Shrinks toward its own middle, not the map's.
                        transformOrigin = TransformOrigin((labelRun.x0 + labelRun.x1) / 20f, (labelRun.y0 + labelRun.y1) / 2f)
                        scaleX = press
                        scaleY = press
                    },
                ) {
                    val path = paths[i]
                    val ringed = highlight == block.key
                    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                        drawPath(path, block.paint.fill)
                        if (pressed == block.key) drawPath(path, Color.Black.copy(alpha = 0.08f))
                        if (proposedShare > 0.0005f) {
                            val cut = result.depthHolding(i, proposedShare) * size.height
                            clipPath(path) { hatch(0f, 0f, size.width, cut) }
                        }
                        if (ringed) clipPath(path) { drawPath(path, b.ink, style = Stroke(12.dp.toPx())) }
                    }
                    BlockLabel(
                        block = block,
                        run = labelRun,
                        mapW = w,
                        mapH = h,
                        gap = gap,
                        pct = (block.km / total * 100).roundToInt(),
                        compact = compact,
                        onPick = onPick.takeIf { block.key == BlockKeys.UNCLASSIFIED },
                        extra = extra.takeIf { i == blocks.lastIndex && block.key != BlockKeys.UNCLASSIFIED },
                        onOpen = onBlock?.let { open -> { open(block.key) } },
                    )
                }
            }
        }
    }
}

/** The faint silhouette of a map: the driving screen shows the shape of the fill, nothing to read. */
@Composable
fun BlockSilhouette(blocks: List<Block>, color: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.clearAndSetSemantics {}) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val shares = blocks.map { it.km.coerceAtLeast(0.01f) }
        val keys = blocks.map { it.key }
        val result = remember(keys, shares, w, h) { BlockLayout.of(shares, w, h, LayoutMemory.previous(keys, w, h)) }
        val gap = with(density) { GAP_DP.dp.toPx() }
        val radius = with(density) { 28.dp.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            blocks.indices.forEach { drawPath(outline(result.runs(it), w, h, gap, radius), color) }
        }
    }
}

/** The empty grid after a refill: 100 cells, one per percent, waiting to be filled from the bottom. */
@Composable
fun EmptyGrid(modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val b = Chagok.colors
    BoxWithConstraints(modifier) {
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(1400)) }
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            val gap = GAP_DP.dp.toPx()
            val r = 14.dp.toPx()
            for (row in 0 until 10) for (col in 0 until 10) {
                // Bottom-left first, the way the blocks will come.
                val order = ((9 - row) + col) / 18f
                val a = ((appear.value - order * 0.6f) / 0.4f).coerceIn(0f, 1f)
                if (a <= 0f) continue
                val l = col / 10f * size.width + if (col > 0) gap / 2 else 0f
                val rt = (col + 1) / 10f * size.width - if (col < 9) gap / 2 else 0f
                val t = row / 10f * size.height + if (row > 0) gap / 2 else 0f
                val bt = (row + 1) / 10f * size.height - if (row < 9) gap / 2 else 0f
                drawRoundRect(b.cell.copy(alpha = b.cell.alpha * a), Offset(l, t), Size(rt - l, bt - t), CornerRadius(r))
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

// ---------------------------------------------------------------------------------------------
// Labels

@Composable
private fun BlockLabel(
    block: Block,
    run: BlockLayout.Run,
    mapW: Float,
    mapH: Float,
    gap: Float,
    pct: Int,
    compact: Boolean,
    onPick: (() -> Unit)?,
    extra: Pair<String, () -> Unit>?,
    /** Opens the block's records; also offered to screen readers as the block's action. */
    onOpen: (() -> Unit)?,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val l = run.x0 / 10f * mapW + if (run.x0 > 0) gap / 2 else 0f
    val r = run.x1 / 10f * mapW - if (run.x1 < 10) gap / 2 else 0f
    val t = run.y0 * mapH + if (run.y0 > 0.0001f) gap / 2 else 0f
    val bt = run.y1 * mapH - if (run.y1 < 0.9999f) gap / 2 else 0f
    val wPx = r - l
    val hPx = bt - t
    val wDp = with(density) { wPx.toDp() }.value
    val hDp = with(density) { hPx.toDp() }.value
    if (wDp < 64f || hDp < 56f) return
    val ink = block.paint.on
    val unclassified = block.key == BlockKeys.UNCLASSIFIED
    val pad = if (wDp < 180f || hDp < 140f) 16f else 24f
    val nameSize = ((16f + 4f * sqrt(pct.toFloat())) * if (compact) 0.72f else 1f).coerceIn(20f, 48f).roundToInt()
    // Narrow blocks stack name and figure; wider ones put the name top-left and the figure in a corner.
    val stacked = wDp < 240f || (unclassified && hDp >= 240f)
    val number = kmNumber(block.km)
    // Fit the figure to the room it has: by width at 100sp, then by the height left under the name.
    val at100 = remember(number) { measurer.measure(number, ChagokType.figures(100)).size.width.toFloat() }
    val widthDp100 = with(density) { at100.toDp() }.value
    val percentChip = !unclassified && !compact && wDp >= 150f
    val proposalChip = block.proposedKm >= 1f && wDp >= 150f
    val inline = !stacked && wDp >= 330f
    // Chips go on their own line under the name when the block is too narrow to hold both.
    val nameBlock = nameSize * 1.1f + if (!inline && (percentChip || proposalChip)) 38f else 0f
    val pickBeside = onPick != null && !stacked
    val pickBelow = onPick != null && stacked && hDp - pad * 2 >= 160f
    val sideRoom = when {
        pickBeside -> 150f
        extra != null && wDp >= 520f -> 320f
        else -> 0f
    }
    val byWidth = (wDp - pad * 2 - sideRoom) / widthDp100 * 100f
    val byHeight = if (stacked) {
        hDp - pad * 2 - nameBlock - 8f - if (pickBelow) 76f else 0f
    } else {
        (hDp - pad * 2 - nameBlock - 8f) / 0.86f
    }
    val target = 31f * sqrt(pct.toFloat()) * if (compact) 0.75f else 1f
    val numberSize = minOf(target, byWidth, byHeight).coerceAtMost(280f)
    val showNumber = numberSize >= 22f
    val chipBack = if (ink == Color.White) Color(0x3313213D) else Color(0x1A13213D)

    Box(
        Modifier
            .offset { IntOffset(l.roundToInt(), t.roundToInt()) }
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .semantics {
                contentDescription = "${block.label} ${kmNumber(block.km)}킬로미터"
                if (onOpen != null) {
                    role = Role.Button
                    onClick(label = "기록 보기") {
                        onOpen()
                        true
                    }
                }
            }
            .padding(pad.dp),
    ) {
        Column(Modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val chips: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (percentChip) Chip(null, "$pct%", chipBack, ink)
                    if (proposalChip) Chip("제안", kmNumber(block.proposedKm), Color.White, Color(0xFF13213D))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(block.label, style = ChagokType.display(nameSize, 1f), color = ink, maxLines = 1)
                if (inline) chips()
            }
            if (!inline && (percentChip || proposalChip)) chips()
            if (stacked && showNumber) Text(number, style = ChagokType.figures(numberSize.roundToInt()), color = ink, maxLines = 1)
        }
        if (!stacked && showNumber) {
            Text(
                number,
                style = ChagokType.figures(numberSize.roundToInt()),
                color = ink,
                maxLines = 1,
                // What nobody named keeps its figure by the name, so "정하기" can take the other corner.
                modifier = Modifier.align(if (unclassified) Alignment.BottomStart else Alignment.BottomEnd).clearAndSetSemantics {},
            )
        }
        if (onPick != null && (pickBelow || pickBeside)) {
            Pill(
                "정하기",
                onPick,
                if (pickBelow) Modifier.align(Alignment.BottomCenter).fillMaxWidth() else Modifier.align(Alignment.BottomEnd),
                icon = ChagokIcons.Plus,
                height = 52.dp,
            )
        }
        if (extra != null && wDp >= 520f) {
            Pill(extra.first, extra.second, Modifier.align(Alignment.BottomStart), style = PillStyle.GHOST, icon = ChagokIcons.Plus)
        }
    }
}

@Composable
private fun Chip(label: String?, figure: String, back: Color, ink: Color) {
    Box(Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(back).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        // Words in the text face, figures in the figure face: Bricolage has no Hangul.
        Text(
            buildAnnotatedString {
                if (label != null) append("$label ")
                withStyle(figureSpan(17, ink)) { append(figure) }
            },
            style = ChagokType.body(16, FontWeight.ExtraBold, 1.2f),
            color = ink,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Outline

/**
 * The outline of a block's runs: inset by half the gap wherever it meets another block (flush with
 * the edge of the map), convex corners rounded by [radius] and inner corners by [radius] + gap, so
 * the gap between two blocks stays even around every bend.
 */
internal fun outline(runs: List<BlockLayout.Run>, w: Float, h: Float, gap: Float, radius: Float): Path {
    // Clockwise in grid units: along the tops left to right, down the right side, back along the bottoms.
    val pts = mutableListOf<Pair<Float, Float>>()
    runs.forEach { r ->
        pts += r.x0.toFloat() to r.y0
        pts += r.x1.toFloat() to r.y0
    }
    runs.asReversed().forEach { r ->
        pts += r.x1.toFloat() to r.y1
        pts += r.x0.toFloat() to r.y1
    }
    val poly = simplify(pts)
    val n = poly.size
    if (n < 4) return Path()
    val half = gap / 2
    fun edgeShift(i: Int): Float {
        val (ax, ay) = poly[i]
        val (bx, by) = poly[(i + 1) % n]
        return if (abs(ay - by) < 1e-5f) {
            // Horizontal: the region is below an eastward edge, above a westward one.
            val border = ay < 1e-5f || ay > 1 - 1e-5f
            if (border) 0f else if (bx > ax) half else -half
        } else {
            val border = ax < 1e-5f || ax > BlockLayout.COLUMNS - 1e-5f
            if (border) 0f else if (by > ay) -half else half
        }
    }
    val shifts = FloatArray(n) { edgeShift(it) }
    val corners = List(n) { i ->
        val prev = (i - 1 + n) % n
        val (x, y) = poly[i]
        val px = x / BlockLayout.COLUMNS * w
        val py = y * h
        val prevHorizontal = abs(poly[prev].second - y) < 1e-5f
        if (prevHorizontal) Offset(px + shifts[i], py + shifts[prev]) else Offset(px + shifts[prev], py + shifts[i])
    }
    val path = Path()
    val k = 0.5523f
    for (i in 0 until n) {
        val v = corners[i]
        val a = corners[(i - 1 + n) % n]
        val c = corners[(i + 1) % n]
        val inLen = hypot(v.x - a.x, v.y - a.y)
        val outLen = hypot(c.x - v.x, c.y - v.y)
        if (inLen < 0.01f || outLen < 0.01f) continue
        val d1 = Offset((v.x - a.x) / inLen, (v.y - a.y) / inLen)
        val d2 = Offset((c.x - v.x) / outLen, (c.y - v.y) / outLen)
        val convex = d1.x * d2.y - d1.y * d2.x > 0
        val r = minOf(if (convex) radius else radius + gap, inLen / 2, outLen / 2)
        val t1 = v - d1 * r
        val t2 = v + d2 * r
        if (i == 0) path.moveTo(t1.x, t1.y) else path.lineTo(t1.x, t1.y)
        path.cubicTo(t1.x + d1.x * r * k, t1.y + d1.y * r * k, t2.x - d2.x * r * k, t2.y - d2.y * r * k, t2.x, t2.y)
    }
    path.close()
    return path
}

/** Drops repeated points and the middle of three in a line, so edges alternate across and down. */
private fun simplify(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
    val out = mutableListOf<Pair<Float, Float>>()
    for (p in points) if (out.isEmpty() || abs(out.last().first - p.first) > 1e-5f || abs(out.last().second - p.second) > 1e-5f) out += p
    if (out.size > 1 && abs(out.first().first - out.last().first) < 1e-5f && abs(out.first().second - out.last().second) < 1e-5f) out.removeAt(out.lastIndex)
    var changed = true
    while (changed && out.size > 3) {
        changed = false
        for (i in out.indices) {
            val a = out[(i - 1 + out.size) % out.size]
            val p = out[i]
            val c = out[(i + 1) % out.size]
            val sameX = abs(a.first - p.first) < 1e-5f && abs(p.first - c.first) < 1e-5f
            val sameY = abs(a.second - p.second) < 1e-5f && abs(p.second - c.second) < 1e-5f
            if (sameX || sameY) {
                out.removeAt(i)
                changed = true
                break
            }
        }
    }
    return out
}
