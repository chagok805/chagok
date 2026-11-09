package dev.chagok.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chagok.app.data.SpecialDay
import dev.chagok.app.ledger.Person
import dev.chagok.app.ledger.Phrases
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recording the parent's own words for a day. Parked only, like every screen here. */
data class MemoControls(
    val exists: (dayId: Long) -> Boolean,
    val seconds: (dayId: Long) -> Int?,
    val recordingDayId: Long?,
    val micDenied: Boolean = false,
    val onOpenAppSettings: () -> Unit = {},
    val onRecord: (dayId: Long) -> Unit,
    val onStop: (dayId: Long) -> Unit,
    val onDelete: (dayId: Long) -> Unit,
)

/** A block's color as text on a white tile: light fills read too faintly, so they fall back to ink. */
@Composable
private fun Paint.onSurface(): Color = if (on == Color.White) fill else Chagok.colors.ink

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DaysScreen(
    days: List<SpecialDay>,
    people: List<Person>,
    seeded: Boolean,
    confirmedKm: (personId: Long, fromMillis: Long) -> Float,
    onAdd: (personId: Long, title: String, fromMillis: Long) -> Unit,
    onDelete: (Long) -> Unit,
    onPlay: (SpecialDay) -> Unit,
    memo: MemoControls,
    onOpenPeople: () -> Unit,
    onTab: (Tab) -> Unit,
) {
    val b = Chagok.colors
    val family = people.filterNot { it.isSelf }
    val familyIds = family.map { it.id }
    var title by rememberSaveable { mutableStateOf("") }
    var personId by rememberSaveable { mutableLongStateOf(family.firstOrNull()?.id ?: -1L) }
    var picking by remember { mutableStateOf(false) }
    var fromMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    val format = remember { SimpleDateFormat("yyyy년 M월 d일", Locale.KOREAN) }
    var typing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val shown = days.mapNotNull { day -> people.firstOrNull { it.id == day.personId }?.let { day to it } }
    DesignFrame { wide ->
        Box(Modifier.fillMaxSize().clearFocusOnTap(focus)) {
            TilePage(
                wide = wide,
                tab = Tab.DAYS,
                onTab = onTab,
                narrowRightHeight = (shown.size.coerceAtLeast(1) * 776).dp,
                left = {
                    // The car's keyboard floats over the lower half: while typing, the form moves up.
                    if (!typing) {
                        Tile(Modifier.fillMaxWidth().height(256.dp)) {
                            BrandRow(seeded)
                            Heading("그날", 56, Modifier.padding(top = 24.dp))
                            Text(
                                "입학이나 생일처럼 가족이 정한 날부터 세요.\n온 가족이 탔을 때 세워 두고 들려주세요.",
                                style = ChagokType.body(18, lineHeight = 1.5f),
                                color = b.inkSecondary,
                                modifier = Modifier.padding(top = 14.dp),
                            )
                        }
                    }
                    Tile(Modifier.fillMaxWidth().then(if (typing) Modifier else grow(wide, 440.dp)), padding = PaddingValues(horizontal = 32.dp, vertical = 28.dp)) {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Heading("새로운 날", 28, Modifier.padding(end = 8.dp).align(Alignment.CenterVertically))
                            Spacer(Modifier.weight(1f))
                            family.forEach { p ->
                                ChoiceOnSurface(p.name, b.forPerson(p.id, familyIds).fill, personId == p.id, { personId = p.id })
                            }
                        }
                        if (family.isEmpty()) {
                            Text("먼저 사람을 등록해 주세요.\n그 사람의 날을 정할 수 있어요.", style = ChagokType.body(18, lineHeight = 1.5f), color = b.inkSecondary, modifier = Modifier.padding(top = 20.dp))
                            Spacer(Modifier.weight(1f))
                            Pill("사람 등록하기", onOpenPeople, Modifier.fillMaxWidth(), style = PillStyle.STRONG, icon = ChagokIcons.Plus, height = 60.dp, textSize = 18)
                        } else {
                            FieldLabel("어떤 날인가요?", Modifier.padding(top = 24.dp))
                            InputField(title, { title = it.take(MAX_TITLE) }, "예: 입학한 날", Modifier.padding(top = 10.dp), onTyping = { typing = it })
                            FieldLabel("언제부터 셀까요?", Modifier.padding(top = 20.dp))
                            DateField(fromMillis?.let { "${format.format(Date(it))}부터" }, {
                                focus.clearFocus()
                                picking = true
                            }, Modifier.padding(top = 10.dp))
                            if (!typing) Spacer(Modifier.weight(1f))
                            VSpace(16.dp)
                            Pill(
                                "그날 추가하기",
                                {
                                    onAdd(personId, title.trim(), fromMillis ?: return@Pill)
                                    title = ""
                                    fromMillis = null
                                    focus.clearFocus()
                                },
                                Modifier.fillMaxWidth(),
                                style = PillStyle.STRONG,
                                icon = ChagokIcons.Plus,
                                enabled = personId in familyIds && title.isNotBlank() && fromMillis != null,
                                height = 60.dp,
                                textSize = 18,
                            )
                        }
                    }
                },
                right = {
                    if (shown.isEmpty()) {
                        EmptyGrid(Modifier.fillMaxSize()) {
                            CenterCard("아직 정한 날이 없어요.", "날을 정해 두면, 그날부터 함께 달린 길을\n차가 모아 두었다가 들려줘요.")
                        }
                    } else {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val single = shown.size == 1 && wide
                            val page = if (single) maxHeight else 768.dp
                            Column(
                                Modifier.fillMaxSize().then(if (single) Modifier else Modifier.verticalScroll(rememberScrollState())),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                shown.forEach { (day, person) ->
                                    val km = confirmedKm(day.personId, day.fromMillis)
                                    val paint = b.forPerson(person.id, familyIds)
                                    Column(Modifier.fillMaxWidth().height(page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        DayCard(day, person, km, paint, memo.recordingDayId == null, wide, onPlay, onDelete, Modifier.fillMaxWidth().weight(452f))
                                        Row(Modifier.fillMaxWidth().weight(308f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            MemoTile(day.id, memo, Modifier.weight(1f).fillMaxHeight())
                                            CompareTile(km, paint, Modifier.weight(1f).fillMaxHeight())
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
        if (picking) {
            DateSheet(
                selected = fromMillis,
                onPick = {
                    fromMillis = it
                    picking = false
                },
                onDismiss = { picking = false },
            )
        }
    }
}

@Composable
private fun DateField(text: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val b = Chagok.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (pressed) b.pressed else b.field)
            .clickable(interaction, null, role = Role.Button, onClick = onClick)
            .padding(start = 20.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(ChagokIcons.Calendar, null, tint = b.inkSecondary, modifier = Modifier.size(22.dp))
        Text(text ?: "날짜 고르기", style = ChagokType.body(19, lineHeight = 1.3f), color = if (text != null) b.ink else b.inkSecondary, modifier = Modifier.weight(1f))
        Icon(ChagokIcons.Chevron, null, tint = b.inkSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DayCard(
    day: SpecialDay,
    person: Person,
    km: Float,
    paint: Paint,
    idle: Boolean,
    wide: Boolean,
    onPlay: (SpecialDay) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier,
) {
    val since = remember(day.fromMillis) { SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).format(Date(day.fromMillis)) }
    val soft = paint.on.copy(alpha = 0.8f)
    Tile(modifier, color = paint.fill, padding = PaddingValues(start = 40.dp, end = 36.dp, top = 32.dp, bottom = 36.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                person.name,
                style = ChagokType.body(16, FontWeight.ExtraBold, 1.3f),
                color = paint.fill,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(paint.on).padding(horizontal = 14.dp, vertical = 4.dp),
            )
            Text(
                buildAnnotatedString {
                    withStyle(figureSpan(20, soft)) { append(since) }
                    append("부터")
                },
                style = ChagokType.body(18, FontWeight.Bold, 1.3f),
                color = soft,
                modifier = Modifier.weight(1f),
            )
            Pill("지우기", { onDelete(day.id) }, style = if (paint.on == Color.White) PillStyle.GHOST else PillStyle.TINT, enabled = idle)
        }
        Heading(day.title, 44, Modifier.padding(top = 12.dp), color = paint.on)
        Spacer(Modifier.weight(1f))
        val figure: @Composable () -> Unit = {
            Column {
                Distance(km, if (wide) 180 else 120, color = paint.on, unitColor = soft, unitSize = 40)
                Text("함께 달린 길", style = ChagokType.body(18, FontWeight.Bold, 1.3f), color = paint.on, modifier = Modifier.padding(top = 14.dp))
            }
        }
        // The car never tells a child the distance was zero.
        val play: @Composable () -> Unit = {
            if (km >= 1f && idle) {
                PlayPill("들려주기", { onPlay(day) }, Color(0xFF13213D), big = true)
            } else if (km < 1f) {
                Text("이 날부터 함께 달린 길을\n확인하면 들려줄 수 있어요.", style = ChagokType.body(17, lineHeight = 1.45f), color = paint.on)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { figure() }
                play()
            }
        } else {
            figure()
            VSpace(16.dp)
            play()
        }
    }
}

@Composable
private fun MemoTile(dayId: Long, memo: MemoControls, modifier: Modifier) {
    val b = Chagok.colors
    val recording = memo.recordingDayId == dayId
    var elapsed by remember(dayId, recording) { mutableIntStateOf(0) }
    if (recording) {
        LaunchedEffect(dayId) {
            while (elapsed < MEMO_SECONDS) {
                delay(1_000)
                elapsed++
            }
        }
    }
    val has = memo.exists(dayId)
    val seconds = if (has) memo.seconds(dayId) else null
    Tile(modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = b.strong, padding = PaddingValues(start = 28.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(b.family[0].fill), contentAlignment = Alignment.Center) {
                Icon(ChagokIcons.Mic, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Waveform(bars = 24, moving = recording, color = Color(0xFF8FAEEB), modifier = Modifier.weight(1f).height(32.dp), quiet = !has && !recording)
            Text(
                when {
                    recording -> clock(MEMO_SECONDS - elapsed)
                    seconds != null -> clock(seconds)
                    else -> "0:20"
                },
                style = ChagokType.figures(18),
                color = b.onStrongSecondary,
            )
        }
        Text(
            when {
                recording -> "녹음하고 있어요."
                memo.micDenied -> "마이크를 허용하면\n녹음할 수 있어요."
                has -> "한마디가\n녹음돼 있어요."
                else -> "숫자 다음에 들려줄\n한마디"
            },
            style = ChagokType.display(28, 1.12f),
            color = b.onStrong,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            when {
                recording -> "${(MEMO_SECONDS - elapsed).coerceAtLeast(0)}초 남았어요."
                memo.micDenied -> "녹음한 목소리는 이 차 안에만 남아요."
                has -> "차가 숫자를 말한 다음 이어서 들려줘요."
                else -> "직접 녹음해 두면 그날 차가 이어서 들려줘요. 20초까지예요."
            },
            style = ChagokType.body(16, lineHeight = 1.5f),
            color = b.onStrongSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                recording -> Pill("녹음 끝", { memo.onStop(dayId) }, Modifier.weight(1f), icon = ChagokIcons.Stop)
                memo.micDenied -> {
                    Pill("설정 열기", memo.onOpenAppSettings, Modifier.weight(1.4f))
                    if (has) Pill("지우기", { memo.onDelete(dayId) }, Modifier.weight(1f), style = PillStyle.GHOST)
                }
                has -> {
                    Pill("다시 녹음", { memo.onRecord(dayId) }, Modifier.weight(1.4f), style = PillStyle.GHOST, enabled = memo.recordingDayId == null)
                    Pill("지우기", { memo.onDelete(dayId) }, Modifier.weight(1f), style = PillStyle.GHOST)
                }
                else -> Pill("녹음", { memo.onRecord(dayId) }, Modifier.weight(1f), icon = ChagokIcons.Mic, enabled = memo.recordingDayId == null)
            }
        }
    }
}

private fun clock(seconds: Int): String = "%d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)

/** Bars of a voice. They move only while a voice is being recorded or played. */
@Composable
private fun Waveform(bars: Int, moving: Boolean, color: Color, modifier: Modifier, quiet: Boolean = false) {
    val heights = remember(bars) { List(bars) { i -> listOf(0.3f, 0.5f, 0.8f, 0.4f, 0.95f, 0.65f, 0.35f, 0.85f, 0.55f, 1f, 0.4f, 0.7f)[i % 12] } }
    val pulse = if (moving) {
        val t = rememberInfiniteTransition(label = "wave")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "phase").value
    } else {
        0f
    }
    Canvas(modifier.clearAndSetSemantics {}) {
        val w = 4.dp.toPx()
        val step = w + 4.dp.toPx()
        val count = minOf(bars, (size.width / step).toInt())
        for (i in 0 until count) {
            val wave = if (moving) 0.35f + 0.65f * kotlin.math.abs(kotlin.math.sin((pulse + i * 0.07f) * Math.PI.toFloat() * 2f)) else 1f
            val h = (size.height * heights[i] * wave).coerceAtLeast(w)
            drawRoundRect(
                color.copy(alpha = if (quiet) 0.35f else 1f),
                Offset(i * step, (size.height - h) / 2),
                Size(w, h),
                CornerRadius(w / 2),
            )
        }
    }
}

/** The distance as Seoul-Busan round trips, stacked from the bottom. */
@Composable
private fun CompareTile(km: Float, paint: Paint, modifier: Modifier) {
    val unit = Phrases.SEOUL_BUSAN_ROUND_TRIP_KM
    val trips = Phrases.dayTrips(km)
    val rest = km - trips * unit
    val shownTrips = minOf(trips, 5)
    val title = Phrases.dayComparison(km)?.replace("부산까지 ", "부산까지\n") ?: "서울에서 부산 왕복까지\n${Phrases.km(unit - km)} 남았어요."
    Tile(modifier, padding = PaddingValues(start = 28.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)) {
        Heading(title, 28)
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.clearAndSetSemantics { contentDescription = "왕복 한 번 ${kmNumber(unit)}킬로미터로 ${trips}번, 그리고 ${kmNumber(rest)}킬로미터 더" },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (rest >= 1f) Bar("+${kmNumber(rest)}", (rest / unit).coerceIn(0.18f, 1f), lerp(paint.fill, Color.White, 0.65f), Color(0xFF13213D))
            for (n in trips downTo trips - shownTrips + 1) Bar("왕복 $n", 1f, paint.fill, paint.on)
        }
    }
}

@Composable
private fun Bar(label: String, fraction: Float, color: Color, ink: Color) {
    // Never narrower than its own label, however small the leftover is.
    Box(Modifier.widthIn(min = 72.dp).fillMaxWidth(fraction).height(30.dp).clip(RoundedCornerShape(10.dp)).background(color).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        Text(label, style = ChagokType.body(16, FontWeight.ExtraBold, 1.2f), color = ink, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// The closing scene

/** A steady rise that settles at the end: the number arrives the way time passes, at one pace. */
private val TimePassing = CubicBezierEasing(0.35f, 0f, 0.45f, 1f)

/**
 * The closing scene: said aloud in the car with the family inside. Only confirmed distance is used,
 * because a wrong name here would undo everything this screen is for.
 *
 * As the number counts up, one Seoul-Busan round trip after another lands on the right, and the last,
 * unfinished one fills the bar under them. Everything follows the count and stops with it.
 */
@Composable
fun DaySceneScreen(
    person: Person,
    day: SpecialDay,
    km: Float,
    hasMemo: Boolean,
    memoSeconds: Int?,
    play: (String) -> Unit,
    stop: () -> Unit,
    onClose: () -> Unit,
    seeded: Boolean = false,
) {
    val b = Chagok.colors
    val familyIds = listOf(person.id)
    val paint = b.forPerson(person.id, familyIds).let { if (it == b.self) b.family[0] else it }
    val counted = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var run by remember { mutableIntStateOf(0) }
    val line = remember(person, day, km) { Phrases.daySpoken(person.name, day.title, km) }
    val comparison = remember(km) { Phrases.dayComparison(km) }
    LaunchedEffect(line, run) {
        counted.snapTo(0f)
        play(line)
        counted.animateTo(km, tween(COUNT_UP_MS, easing = TimePassing))
    }
    val arrived = km <= 0f || counted.value >= km - 0.5f
    val reveal by animateFloatAsState(if (arrived) 1f else 0f, tween(600, easing = PleosMotion.Tension), label = "reveal")
    val spoken = "${person.name}, ${day.title}부터 오늘까지 우리가 함께 달린 길 ${Phrases.km(km)}"
    DesignFrame { wide ->
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(
                    "닫기",
                    {
                        scope.launch { counted.stop() }
                        stop()
                        onClose()
                    },
                    style = PillStyle.WHITE,
                    icon = ChagokIcons.Close,
                    height = 64.dp,
                    textSize = 19,
                )
                Pill("다시 들려주기", { run++ }, style = PillStyle.WHITE, icon = ChagokIcons.Replay, height = 64.dp, textSize = 19, enabled = arrived)
                Spacer(Modifier.weight(1f))
                if (seeded) DemoTag()
            }
            val left: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile(Modifier.fillMaxWidth().weight(1f).clearAndSetSemantics { contentDescription = spoken }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                person.name,
                                style = ChagokType.body(16, FontWeight.ExtraBold, 1.3f),
                                color = paint.on,
                                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(paint.fill).padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                            Text("${day.title}부터 오늘까지", style = ChagokType.body(18, FontWeight.Bold, 1.3f), color = b.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Distance(km, if (wide) 176 else 130, Modifier.padding(top = 28.dp), color = paint.onSurface(), unitColor = paint.onSurface(), unitSize = 44, text = kmNumber(counted.value))
                        Spacer(Modifier.weight(1f))
                        Text(
                            "우리가 함께\n달린 길",
                            style = ChagokType.display(56, 1.08f),
                            color = b.ink,
                            modifier = Modifier.graphicsLayer {
                                alpha = reveal
                                translationY = (1f - reveal) * 12.dp.toPx()
                            },
                        )
                        if (comparison != null) {
                            Text(
                                "${comparison}예요.",
                                style = ChagokType.body(18),
                                color = b.inkSecondary,
                                modifier = Modifier.padding(top = 12.dp).graphicsLayer {
                                    alpha = reveal
                                    translationY = (1f - reveal) * 12.dp.toPx()
                                },
                            )
                        }
                    }
                    if (hasMemo) VoiceTile(memoSeconds, reveal, Modifier.fillMaxWidth().height(160.dp))
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    left(Modifier.width(516.dp).fillMaxHeight())
                    Trips(km, counted.value, paint, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                // The portrait window is too short for both: the scene scrolls, the trips below the number.
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    left(Modifier.fillMaxWidth().height(if (hasMemo) 720.dp else 552.dp))
                    Trips(km, counted.value, paint, Modifier.fillMaxWidth().height(420.dp))
                }
            }
        }
    }
}

@Composable
private fun VoiceTile(seconds: Int?, reveal: Float, modifier: Modifier) {
    val b = Chagok.colors
    Row(
        modifier
            .graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * 16.dp.toPx()
            }
            .clip(RoundedCornerShape(32.dp))
            .background(b.strong)
            .padding(start = 28.dp, end = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(b.family[0].fill), contentAlignment = Alignment.Center) {
            Icon(ChagokIcons.Mic, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("이어서, 녹음한 한마디", style = ChagokType.display(24, 1.1f), color = b.onStrong)
            Waveform(bars = 30, moving = reveal >= 1f, color = Color(0xFF8FAEEB), modifier = Modifier.fillMaxWidth().height(36.dp))
        }
        if (seconds != null) Text(clock(seconds), style = ChagokType.figures(24), color = b.onStrongSecondary)
    }
}

/** Round trips land one by one as the count passes them; the unfinished one fills the bar below. */
@Composable
private fun Trips(km: Float, counted: Float, paint: Paint, modifier: Modifier) {
    val b = Chagok.colors
    val unit = Phrases.SEOUL_BUSAN_ROUND_TRIP_KM
    val trips = Phrases.dayTrips(km)
    val (cols, rows) = when {
        trips <= 2 -> 2 to 1
        trips <= 4 -> 2 to 2
        trips <= 6 -> 3 to 2
        trips <= 9 -> 3 to 3
        else -> 4 to 3
    }
    val shownTrips = minOf(trips, cols * rows)
    val extra = (counted - trips * unit).coerceIn(0f, km - trips * unit)
    val toNext = (unit - (km - trips * unit)).coerceAtLeast(0f)
    BoxWithConstraints(modifier.clearAndSetSemantics { contentDescription = "서울과 부산 왕복 ${kmNumber(unit)}킬로미터가 ${trips}번, 그리고 ${kmNumber(km - trips * unit)}킬로미터 더." }) {
        val barH = 80.dp
        val gap = 8.dp
        val fullWidth = maxWidth
        val gridH = maxHeight - barH - gap
        val cellW = (maxWidth - gap * (cols - 1)) / cols
        val cellH = (gridH - gap * (rows - 1)) / rows
        for (i in 0 until shownTrips) {
            val n = trips - shownTrips + i + 1
            val on = counted >= unit * n - 0.5f
            val appear by animateFloatAsState(if (on) 1f else 0f, tween(450), label = "trip$i")
            val pop by animateFloatAsState(if (on) 1f else 0.86f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 300f), label = "pop$i")
            val col = i % cols
            val row = i / cols
            // From deeper to lighter, never so light that white figures lose their contrast.
            val light = if (paint.on == Color.White) 0.06f else 0.3f
            val shade = lerp(lerp(paint.fill, Color.Black, 0.22f), lerp(paint.fill, Color.White, light), if (shownTrips <= 1) 0f else i / (shownTrips - 1f))
            Column(
                Modifier
                    .offset(x = (cellW + gap) * col, y = (cellH + gap) * row)
                    .size(cellW, cellH)
                    .graphicsLayer {
                        alpha = appear
                        scaleX = pop
                        scaleY = pop
                    }
                    .clip(RoundedCornerShape(32.dp))
                    .background(shade)
                    .padding(if (cellH > 220.dp) 40.dp else 24.dp),
            ) {
                Text("서울 ⇄ 부산", style = ChagokType.body(16, FontWeight.ExtraBold, 1.3f), color = paint.on.copy(alpha = 0.8f))
                Text("왕복 $n", style = ChagokType.display(if (cellH > 220.dp) 40 else 30, 1.1f), color = paint.on, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.weight(1f))
                Text(kmNumber(unit), style = ChagokType.figures(if (cellH > 220.dp) 96 else 64), color = paint.on)
            }
        }
        // The unfinished round trip: dashed until the count fills it.
        Box(Modifier.offset(y = gridH + gap).fillMaxWidth().height(barH)) {
            Canvas(Modifier.matchParentSize()) {
                val s = 2.dp.toPx()
                drawRoundRect(
                    b.line,
                    topLeft = Offset(s / 2, s / 2),
                    size = Size(size.width - s, size.height - s),
                    cornerRadius = CornerRadius(32.dp.toPx()),
                    style = Stroke(s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))),
                )
            }
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("다음 왕복까지 ", style = ChagokType.body(16, FontWeight.Bold, 1.3f), color = b.inkSecondary)
                Text(Phrases.km(toNext), style = ChagokType.figures(22), color = b.ink)
            }
            if (extra > 0.5f) {
                // Wide enough for its own figure, however little of the round trip it is.
                val width = maxOf(fullWidth * (extra / unit).coerceIn(0f, 1f), 148.dp)
                Box(
                    Modifier.width(width).fillMaxHeight().clip(RoundedCornerShape(32.dp)).background(lerp(paint.fill, Color.White, 0.45f)).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("+${kmNumber(extra)}", style = ChagokType.figures(36), color = Color(0xFF13213D), maxLines = 1)
                }
            }
        }
    }
}

private const val MAX_TITLE = 20
private const val COUNT_UP_MS = 6_000
private const val MEMO_SECONDS = 20
