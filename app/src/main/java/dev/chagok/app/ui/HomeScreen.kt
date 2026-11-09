package dev.chagok.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.chagok.app.Answered
import dev.chagok.app.Prompt
import dev.chagok.app.data.SpecialDay
import dev.chagok.app.ledger.Attribution
import dev.chagok.app.ledger.Person
import dev.chagok.app.ledger.Phrases
import dev.chagok.app.ledger.Segment
import dev.chagok.app.ledger.Settlement
import dev.chagok.app.ledger.WayBack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HomeActions(
    val onConfirm: (segmentId: Long, personId: Long, promptId: Long) -> Unit,
    val onLeave: (segmentId: Long, promptId: Long) -> Unit,
    val onOtherDriver: (segmentId: Long, promptId: Long) -> Unit,
    val onDismiss: (promptId: Long) -> Unit,
    val onAcceptGuess: (Prompt.Gap) -> Unit,
    val onUndo: (Answered) -> Unit,
    val onOpenSettlement: (Prompt.TankClosed?) -> Unit,
    val onOpenPeople: () -> Unit,
    val onOpenDays: () -> Unit,
    val onPlayDay: (SpecialDay) -> Unit,
    /** Any record can still be corrected: it comes back as a card. */
    val onOpenRecord: (Segment) -> Unit,
    val onTab: (Tab) -> Unit,
)

/** The day the home screen offers to play, with its distance. */
data class DayHighlight(val day: SpecialDay, val person: Person, val km: Float)

/** The odometer range of the running fill, to list its records. */
data class FillRange(val startKm: Float, val endKm: Float)

@Composable
fun HomeScreen(
    settlement: Settlement?,
    range: FillRange?,
    lastFill: Settlement?,
    weekMinutes: Long,
    weekWaitMinutes: Long,
    people: List<Person>,
    segments: List<Segment>,
    prompts: List<Prompt>,
    notice: String?,
    answered: Answered?,
    day: DayHighlight?,
    seeded: Boolean,
    odometerAvailable: Boolean,
    actions: HomeActions,
) {
    val b = Chagok.colors
    val familyIds = people.filterNot { it.isSelf }.map { it.id }
    val blocks = settlement?.takeIf { it.totalKm >= 1f }?.let { blocksOf(it, familyIds, b) }.orEmpty()
    val closed = prompts.firstOrNull { it is Prompt.TankClosed } as Prompt.TankClosed?
    // The home screen has one place for what the car wants to say, so one thing at a time: a fix the
    // driver asked for, then the answer just given, then a fill that just ended (what the car just said
    // aloud), then the newest stretch, then distance it missed. The others wait their turn.
    val recorded = (prompts.firstOrNull { it is Prompt.Recorded } as Prompt.Recorded?)
        ?.takeIf { it.correcting || (answered == null && closed == null) }
    val gap = (prompts.firstOrNull { it is Prompt.Gap } as Prompt.Gap?)
        ?.takeIf { recorded == null && answered == null && closed == null }

    // Choosing who a stretch was for, from a card or from the records of a block.
    var fixing by remember(recorded?.id, gap?.id) { mutableStateOf(recorded != null && (recorded.correcting || recorded.person == null)) }
    var pick by remember(recorded?.id, gap?.id) { mutableStateOf(recorded?.takeIf { it.correcting }?.let { keyOf(it.segment, people) }) }
    var inspecting by rememberSaveable { mutableStateOf<String?>(null) }
    if (inspecting != null && blocks.none { it.key == inspecting }) inspecting = null

    val subject: Segment? = recorded?.segment ?: gap?.segment
    val choosing = fixing && subject != null
    val preview = if (choosing && pick != null && subject != null && range != null) previewMove(blocks, subject, pick!!, range, people, b, segments) else blocks
    val gapLayout = gap != null && !choosing && inspecting == null

    // Back closes what is open on the home screen before it leaves the app.
    BackHandler(enabled = choosing || inspecting != null) {
        if (choosing) {
            fixing = false
            pick = null
            recorded?.takeIf { it.correcting || it.person == null }?.let { actions.onDismiss(it.id) }
        } else {
            inspecting = null
        }
    }

    DesignFrame { wide ->
        TilePage(
            wide = wide,
            tab = Tab.HOME,
            onTab = actions.onTab,
            showTabs = !choosing && inspecting == null,
            narrowRightHeight = if (gapLayout) 760.dp else 620.dp,
            left = {
                Hero(settlement, preview, lastFill, seeded, odometerAvailable, familyIds, Modifier.fillMaxWidth().then(grow(wide, 440.dp)).graphicsLayer { alpha = if (choosing) 0.5f else 1f })
                when {
                    choosing && subject != null -> FixTile(
                        segment = subject,
                        suggested = recorded?.person,
                        people = people,
                        familyIds = familyIds,
                        pick = pick,
                        correcting = recorded?.correcting == true,
                        isGap = recorded == null,
                        onPick = { pick = it },
                        onAdd = actions.onOpenPeople,
                        onClose = {
                            fixing = false
                            pick = null
                            if (recorded != null && (recorded.correcting || recorded.person == null)) actions.onDismiss(recorded.id)
                        },
                        onSave = { key ->
                            val id = recorded?.id ?: gap!!.id
                            when {
                                key == BlockKeys.OTHER -> actions.onOtherDriver(subject.id, id)
                                key == BlockKeys.UNCLASSIFIED -> if (recorded != null) actions.onLeave(subject.id, id) else actions.onDismiss(id)
                                else -> personFor(key, people)?.let { actions.onConfirm(subject.id, it.id, id) }
                            }
                            fixing = false
                            pick = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    inspecting != null -> RecordsTile(
                        block = blocks.first { it.key == inspecting },
                        records = segments.filter { s -> range != null && overlaps(s, range) && keyOf(s, people) == inspecting }.sortedByDescending { it.endedAt },
                        people = people,
                        familyIds = familyIds,
                        onOpen = {
                            inspecting = null
                            actions.onOpenRecord(it)
                        },
                        onClose = { inspecting = null },
                        modifier = Modifier.fillMaxWidth().height(if (wide) 360.dp else 420.dp),
                    )
                    gapLayout -> RecentTile(segments.sortedByDescending { it.endedAt }.take(3), people, familyIds, actions.onOpenRecord, Modifier.fillMaxWidth().height(if (wide) 256.dp else 240.dp))
                    else -> Row(Modifier.fillMaxWidth().height(if (wide) 256.dp else 240.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val tile = Modifier.weight(1f).fillMaxHeight()
                        when {
                            answered != null -> AnsweredTile(answered, settlement, { actions.onUndo(answered) }, tile)
                            recorded != null && recorded.person != null -> SuggestionTile(recorded, onConfirm = { actions.onConfirm(recorded.segment.id, recorded.person.id, recorded.id) }, onFix = {
                                fixing = true
                                pick = keyOf(recorded.segment, people)
                            }, modifier = tile)
                            closed != null -> LastFillTile(closed.settlement, { actions.onOpenSettlement(closed) }, { actions.onDismiss(closed.id) }, tile)
                            notice != null -> NoticeTile(notice, tile)
                            familyIds.isEmpty() -> InviteTile("처음이라면", "누구를 위해\n자주 달리나요?", "사람 등록하기", actions.onOpenPeople, tile)
                            day != null -> DayTile(day, { actions.onPlayDay(day.day) }, tile)
                            else -> InviteTile("그날", "입학이나 생일부터\n함께 달린 길을 세요.", "그날 정하기", actions.onOpenDays, tile)
                        }
                        WeekTile("이번 주", weekMinutes, weekWaitMinutes, tile)
                    }
                }
            },
            right = {
                when {
                    gapLayout && gap != null -> GapSide(gap, familyIds, actions, wide, onChoose = {
                        fixing = true
                        pick = gap.guessPerson?.let { p -> if (p.isSelf) BlockKeys.SELF else BlockKeys.person(p.id) }
                    })
                    settlement == null -> EmptyGrid(Modifier.fillMaxSize()) {
                        CenterCard(
                            if (odometerAvailable) "주행거리를 읽고 있어요." else "이 차에서는 주행거리를 읽을 수 없어요.",
                            if (odometerAvailable) "잠시 뒤 이번에 채워 달린 길이\n여기에 쌓여요." else "거리를 읽을 수 있는 차에서\n다시 열어 주세요.",
                        )
                    }
                    blocks.isEmpty() -> EmptyGrid(Modifier.fillMaxSize()) {
                        CenterCard("새 길을 쌓을 자리예요.", "한 칸이 1%예요.\n달린 만큼 아래부터 채워져요.")
                    }
                    else -> BlockMap(
                        blocks = preview,
                        shapeFrom = blocks.takeIf { choosing },
                        highlight = if (choosing) pick else inspecting,
                        modifier = Modifier.fillMaxSize(),
                        onBlock = { key -> if (!choosing) inspecting = key },
                        onPick = { inspecting = BlockKeys.UNCLASSIFIED }.takeIf { !choosing },
                        extra = ("다른 사람 길도 나눠 보기" to actions.onOpenPeople).takeIf { familyIds.size == 1 && !choosing },
                    )
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Hero

private val CountEase = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)

@Composable
private fun Hero(
    settlement: Settlement?,
    blocks: List<Block>,
    lastFill: Settlement?,
    seeded: Boolean,
    odometerAvailable: Boolean,
    familyIds: List<Long>,
    modifier: Modifier,
) {
    val b = Chagok.colors
    val total = settlement?.totalKm ?: 0f
    val family = blocks.filter { it.key.startsWith("p:") }
    val familyKm = family.sumOf { it.km.toDouble() }.toFloat()
    val proposals = blocks.any { it.proposedKm >= 1f }
    BoxWithConstraints(modifier) {
    // When a choosing or records tile takes the room below, the figure gets smaller, never cut.
    val figure = if (maxHeight < 520.dp) 120 else 180
    Tile(Modifier.fillMaxSize()) {
        BrandRow(seeded)
        VSpace(28.dp)
        when {
            settlement == null -> {
                Caption("이번에 채워 달린 길", icon = null, legend = false)
                VSpace(16.dp)
                Distance(0f, figure, color = b.faint, unitColor = b.muted)
                Spacer(Modifier.weight(1f))
                Headline(if (odometerAvailable) "주행거리를 읽고 있어요." else "주행거리를 읽을 수 없어요.", null)
            }
            total < 1f -> {
                Caption("이번에 채워 달린 길", icon = ChagokIcons.Fuel, legend = false)
                VSpace(16.dp)
                Distance(0f, figure, color = b.faint, unitColor = b.muted)
                Spacer(Modifier.weight(1f))
                Headline(
                    if (lastFill != null) "방금 채웠어요." else "여기서부터 쌓여요.",
                    when {
                        familyIds.isEmpty() -> "누구를 위해 달리는지 알려 주면, 첫 길부터 차가 먼저 적어 둘게요."
                        lastFill != null -> "이제 새 길이 오른쪽에 차곡차곡 쌓여요."
                        else -> "주행을 마치고 P에 두면, 누구를 위한 길이었는지 차가 먼저 적어 둘게요."
                    },
                )
            }
            familyKm >= 1f -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildAnnotatedString {
                            append("이번에 채워 달린 ")
                            withStyle(figureSpan(22, b.ink)) { append(Phrases.km(total)) }
                            append(" 중")
                        },
                        style = ChagokType.body(18, FontWeight.SemiBold, 1.3f),
                        color = b.inkSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    if (proposals) ProposalKey()
                }
                VSpace(16.dp)
                CountingDistance(familyKm, figure)
                Spacer(Modifier.weight(1f))
                val names = family.map { it.label }
                Headline(
                    if (names.size == 1) "${names.first()}${if (hasFinal(names.first())) "을" else "를"} 위한\n길이었어요." else "가족을 위한\n길이었어요.",
                    "블록을 누르면 그 길의 기록을 보고 고칠 수 있어요.",
                )
            }
            else -> {
                Caption("이번에 채워 달린 길", icon = null, legend = false)
                VSpace(16.dp)
                CountingDistance(total, figure)
                Spacer(Modifier.weight(1f))
                Headline(
                    if ((settlement.unclassifiedKm) >= total / 2) "누구 길인지 몰라요." else "아직 가족 길이 없어요.",
                    if (familyIds.isEmpty()) "누구를 위해 달리는지 알려 주면, 그 길을 차가 먼저 알아볼게요." else "가족을 위한 길이 생기면 여기에 차곡차곡 쌓여요.",
                )
            }
        }
    }
    }
}

@Composable
private fun Caption(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, legend: Boolean) {
    val b = Chagok.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) Icon(icon, null, tint = b.inkSecondary, modifier = Modifier.size(22.dp))
        Text(text, style = ChagokType.body(18, FontWeight.SemiBold, 1.3f), color = b.inkSecondary, modifier = Modifier.weight(1f))
        if (legend) ProposalKey()
    }
}

/** "빗금은 차의 제안": the key for the hatching, wherever hatching is on screen. */
@Composable
fun ProposalKey(label: String = "빗금은 차의 제안") {
    val b = Chagok.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HatchSwatch(b.family[0].fill)
        Text(label, style = ChagokType.body(16, FontWeight.SemiBold, 1.3f), color = b.inkSecondary)
    }
}

@Composable
private fun ColumnScope.Headline(text: String, sub: String?) {
    val b = Chagok.colors
    Text(text, style = ChagokType.display(44, 1.08f), color = b.ink, modifier = Modifier.padding(top = 24.dp))
    if (sub != null) Text(sub, style = ChagokType.body(18), color = b.inkSecondary, modifier = Modifier.padding(top = 10.dp))
}

/** The family number counts up once, the moment the blocks land. */
@Composable
private fun CountingDistance(km: Float, size: Int) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(km) {
        val first = shown.value == 0f
        shown.animateTo(km, tween(if (first) 1700 else 400, delayMillis = if (first) 200 else 0, easing = CountEase))
    }
    Distance(km, size, text = kmNumber(shown.value), unitSize = (size * 0.22f).toInt())
}

private fun hasFinal(word: String): Boolean {
    val c = word.lastOrNull() ?: return false
    return c in '가'..'힣' && (c - '가') % 28 != 0
}

// ---------------------------------------------------------------------------------------------
// The navy tile: what the car wants to say now

@Composable
private fun StrongTile(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Tile(modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Chagok.colors.strong, padding = PaddingValues(start = 24.dp, end = 20.dp, top = 22.dp, bottom = 20.dp), content = content)
}

@Composable
private fun Kicker(text: String) {
    Text(text, style = ChagokType.body(16, FontWeight.Bold, 1.3f), color = Chagok.colors.onStrongSecondary)
}

@Composable
private fun SuggestionTile(prompt: Prompt.Recorded, onConfirm: () -> Unit, onFix: () -> Unit, modifier: Modifier) {
    val b = Chagok.colors
    val person = prompt.person ?: return
    StrongTile(modifier) {
        Kicker("차가 적어 둔 길")
        Text(Phrases.recorded(person.name, person.isSelf), style = ChagokType.display(28, 1.1f), color = b.onStrong, modifier = Modifier.padding(top = 8.dp))
        Text(detailLine(prompt.segment, b.onStrong), style = ChagokType.body(16, lineHeight = 1.35f), color = b.onStrongSecondary, modifier = Modifier.padding(top = 6.dp), maxLines = 2)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("맞아요", onConfirm, Modifier.weight(1f), icon = ChagokIcons.Check, padding = 10.dp)
            Pill("고치기", onFix, Modifier.weight(1f), style = PillStyle.GHOST, padding = 10.dp)
        }
    }
}

@Composable
private fun AnsweredTile(answered: Answered, settlement: Settlement?, onUndo: () -> Unit, modifier: Modifier) {
    val b = Chagok.colors
    val remaining = settlement?.let { s -> s.family.sumOf { (it.km - it.confirmedKm).toDouble() }.toFloat() + (s.selfKm - s.selfConfirmedKm) } ?: 0f
    StrongTile(modifier) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(b.family[0].fill), contentAlignment = Alignment.Center) {
            Icon(ChagokIcons.Check, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Text(answered.text, style = ChagokType.display(28, 1.1f), color = b.onStrong, modifier = Modifier.padding(top = 14.dp))
        if (remaining >= 1f) {
            Text(
                buildAnnotatedString {
                    append("남은 제안 ")
                    withStyle(figureSpan(18, b.onStrong)) { append(Phrases.km(remaining)) }
                },
                style = ChagokType.body(16, lineHeight = 1.35f),
                color = b.onStrongSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Pill("되돌리기", onUndo, Modifier.fillMaxWidth(), style = PillStyle.GHOST)
    }
}

@Composable
private fun LastFillTile(s: Settlement, onOpen: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val b = Chagok.colors
    StrongTile(modifier) {
        Kicker("지난번 채워 달린 길")
        Row(Modifier.padding(top = 8.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Bottom) {
            val family = s.familyKm >= 1f
            Text(kmNumber(if (family) s.familyKm else s.totalKm), style = ChagokType.figures(52), color = b.onStrong, modifier = Modifier.alignByBaseline())
            Text(
                if (family) " / ${Phrases.km(s.totalKm)}" else "km",
                style = ChagokType.figures(20, FontWeight.SemiBold),
                color = b.onStrongSecondary,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Text(
            if (s.familyKm >= 1f) "가족을 위한 길이었어요." else "한 번 채워 달렸어요.",
            style = ChagokType.display(22, 1.15f),
            color = b.onStrong,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("결산 보기", onOpen, Modifier.weight(1.3f), padding = 10.dp)
            Pill("닫기", onClose, Modifier.weight(1f), style = PillStyle.GHOST, padding = 10.dp)
        }
    }
}

@Composable
private fun NoticeTile(text: String, modifier: Modifier) {
    StrongTile(modifier) {
        BrandMark(size = 40.dp)
        Spacer(Modifier.weight(1f))
        Text(text, style = ChagokType.display(28, 1.12f), color = Chagok.colors.onStrong)
    }
}

@Composable
private fun InviteTile(kicker: String, title: String, action: String, onClick: () -> Unit, modifier: Modifier) {
    StrongTile(modifier) {
        Kicker(kicker)
        Text(title, style = ChagokType.display(28, 1.1f), color = Chagok.colors.onStrong, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.weight(1f))
        Pill(action, onClick, Modifier.fillMaxWidth(), icon = ChagokIcons.Plus)
    }
}

@Composable
private fun DayTile(day: DayHighlight, onPlay: () -> Unit, modifier: Modifier) {
    val b = Chagok.colors
    val familyIds = listOf(day.person.id)
    val paint = b.forPerson(day.person.id, familyIds)
    val since = remember(day.day.fromMillis) { SimpleDateFormat("M월 d일", Locale.KOREAN).format(Date(day.day.fromMillis)) }
    StrongTile(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                day.person.name,
                style = ChagokType.body(16, FontWeight.ExtraBold, 1.3f),
                color = paint.on,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(paint.fill).padding(horizontal = 12.dp, vertical = 3.dp),
            )
            Text(day.day.title, style = ChagokType.display(24, 1.1f), color = b.onStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Distance(day.km, 60, Modifier.padding(top = 14.dp), color = b.onStrong, unitColor = b.onStrongSecondary, unitSize = 20)
        Text("${since}부터 함께 달린 길", style = ChagokType.body(16, lineHeight = 1.35f), color = b.onStrongSecondary, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.weight(1f))
        if (day.km >= 1f) PlayPill("들려주기", onPlay, paint.fill)
    }
}

/** White pill with a round play mark: the one action that speaks to the whole family. */
@Composable
fun PlayPill(text: String, onClick: () -> Unit, mark: Color, modifier: Modifier = Modifier, big: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val h = if (big) 72.dp else 56.dp
    Row(
        modifier
            .height(h)
            .pressScale(pressed)
            .clip(RoundedCornerShape(h / 2))
            .background(if (pressed) Chagok.colors.pressed else Color.White)
            .clickable(interaction, null, role = Role.Button, onClick = onClick)
            .padding(start = if (big) 10.dp else 6.dp, end = if (big) 30.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (big) 14.dp else 12.dp),
    ) {
        Box(Modifier.size(if (big) 52.dp else 44.dp).clip(CircleShape).background(mark), contentAlignment = Alignment.Center) {
            Icon(ChagokIcons.Play, null, tint = Color.White, modifier = Modifier.size(if (big) 26.dp else 20.dp))
        }
        Text(text, style = ChagokType.body(if (big) 21 else 18, FontWeight.ExtraBold, 1.2f), color = Color(0xFF13213D))
    }
}

@Composable
fun WeekTile(title: String, givenMinutes: Long, waitMinutes: Long, modifier: Modifier) {
    val b = Chagok.colors
    Tile(modifier, padding = PaddingValues(horizontal = 24.dp, vertical = 22.dp)) {
        Text(title, style = ChagokType.body(16, FontWeight.ExtraBold, 1.3f), color = b.inkSecondary)
        Spacer(Modifier.weight(1f))
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("가족을 위해 쓴 시간", style = ChagokType.body(16, lineHeight = 1.3f), color = b.inkSecondary)
            TimeFigure(givenMinutes, 36)
        }
        Spacer(Modifier.weight(1f))
        Divider()
        Spacer(Modifier.weight(1f))
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("세워 두고 기다린 시간", style = ChagokType.body(16, lineHeight = 1.3f), color = b.inkSecondary)
            TimeFigure(waitMinutes, 36)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Choosing who a stretch was for

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FixTile(
    segment: Segment,
    suggested: Person?,
    people: List<Person>,
    familyIds: List<Long>,
    pick: String?,
    correcting: Boolean,
    isGap: Boolean,
    onPick: (String) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier,
) {
    val b = Chagok.colors
    Tile(modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = b.strong, padding = PaddingValues(start = 32.dp, end = 28.dp, top = 28.dp, bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(detailLine(segment, b.onStrong), style = ChagokType.body(16, FontWeight.Bold, 1.35f), color = b.onStrongSecondary)
                Heading("누구를 위한 길이었나요?", 36, Modifier.padding(top = 8.dp), color = b.onStrong)
                val said = when {
                    suggested != null && !correcting -> "차는 ${if (suggested.isSelf) "내 길" else suggested.name}로 적어 뒀어요."
                    correcting -> "고르면 이 기록이 바뀌어요."
                    isGap -> "앱이 꺼져 있던 동안의 거리예요."
                    familyIds.isEmpty() -> "가족을 위한 길이었다면, 사람을 추가하고 골라 주세요."
                    else -> null
                }
                if (said != null) Text(said, style = ChagokType.body(16, lineHeight = 1.35f), color = b.onStrongSecondary, modifier = Modifier.padding(top = 6.dp))
            }
            RoundButton(ChagokIcons.Close, "닫기", onClose)
        }
        VSpace(20.dp)
        val choices = people.sortedBy { it.isSelf }.map { p ->
            Triple(if (p.isSelf) BlockKeys.SELF else BlockKeys.person(p.id), if (p.isSelf) "내 길" else p.name, if (p.isSelf) b.self else b.forPerson(p.id, familyIds))
        } + listOf(
            Triple(BlockKeys.OTHER, "다른 사람이 운전", b.otherDriver),
            Triple(BlockKeys.UNCLASSIFIED, if (correcting) "미분류로 두기" else if (isGap) "그대로 두기" else "모르겠어요", b.unclassified),
        )
        val cells = choices.map { (key, label, paint) -> FixCell(key, label, paint) }
        val people = cells.dropLast(2)
        // People three to a row; the two ways out, then adding and saving, two to a row so they read whole.
        val rows = people.chunked(3).map { it to 3 } +
            listOf(cells.takeLast(2) to 2, listOf(FixCell(ADD, "사람 추가", null), FixCell(SAVE, "적어 두기", null)) to 2)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { (row, columns) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { cell ->
                        val m = Modifier.weight(1f)
                        when (cell.key) {
                            ADD -> Pill(cell.label, onAdd, m, style = PillStyle.GHOST, icon = ChagokIcons.Plus, height = 60.dp)
                            SAVE -> Pill(cell.label, { pick?.let(onSave) }, m, enabled = pick != null, height = 60.dp, textSize = 18)
                            else -> ChoiceOnStrong(cell.label, cell.paint, pick == cell.key, { onPick(cell.key) }, m)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private data class FixCell(val key: String, val label: String, val paint: Paint?)

private const val ADD = "add"
private const val SAVE = "save"

/** The map while choosing: what the answer would move, a round trip's other leg included, into the picked block. */
private fun previewMove(
    blocks: List<Block>,
    s: Segment,
    to: String,
    range: FillRange,
    people: List<Person>,
    colors: ChagokColors,
    all: List<Segment>,
): List<Block> {
    val family = to != BlockKeys.SELF && personFor(to, people) != null
    val other = when {
        to == BlockKeys.OTHER -> WayBack.otherLegOtherDriver(s, all)
        to == BlockKeys.UNCLASSIFIED -> WayBack.otherLegLeft(s, all)
        family -> WayBack.otherLegSettled(s, all)
        else -> null
    }
    fun inFill(leg: Segment) = (minOf(leg.endKm, range.endKm) - maxOf(leg.startKm, range.startKm)).coerceAtLeast(0f)
    val legs = listOfNotNull(s, other).filter { keyOf(it, people) != to && inFill(it) > 0f }
    if (legs.isEmpty()) return blocks
    val km = legs.sumOf { inFill(it).toDouble() }.toFloat()
    // Answering for the way out moves the way back too, but it stays a proposal until someone confirms it.
    val stillProposed = other?.takeIf { family && !s.returnLeg && it in legs }?.let(::inFill) ?: 0f
    val moved = blocks.map { blk ->
        val leaving = legs.filter { keyOf(it, people) == blk.key }
        val out = leaving.sumOf { inFill(it).toDouble() }.toFloat()
        val outProposed = leaving.filter { it.attribution == Attribution.SUGGESTED }.sumOf { inFill(it).toDouble() }.toFloat()
        when {
            blk.key == to -> blk.copy(km = blk.km + km, proposedKm = blk.proposedKm + stillProposed)
            out > 0f -> blk.copy(km = (blk.km - out).coerceAtLeast(0f), proposedKm = (blk.proposedKm - outProposed).coerceAtLeast(0f))
            else -> blk
        }
    }
    if (moved.any { it.key == to }) return moved
    // The picked block is not on the map yet: it appears, and the shapes are laid out again.
    val label = when (to) {
        BlockKeys.OTHER -> "다른 사람"
        BlockKeys.UNCLASSIFIED -> "미분류"
        BlockKeys.SELF -> "내 길"
        else -> personFor(to, people)?.name ?: return moved
    }
    val familyIds = people.filterNot { it.isSelf }.map { it.id }
    val paint = when (to) {
        BlockKeys.OTHER -> colors.otherDriver
        BlockKeys.UNCLASSIFIED -> colors.unclassified
        BlockKeys.SELF -> colors.self
        else -> colors.forPerson(to.removePrefix("p:").toLong(), familyIds)
    }
    return (moved + Block(to, label, km, stillProposed, paint)).filter { it.km >= 0.5f }.let { all ->
        all.filter { it.key == BlockKeys.UNCLASSIFIED } + all.filterNot { it.key == BlockKeys.UNCLASSIFIED }.sortedBy { it.km }
    }
}

// ---------------------------------------------------------------------------------------------
// Records

@Composable
private fun RecordsTile(
    block: Block,
    records: List<Segment>,
    people: List<Person>,
    familyIds: List<Long>,
    onOpen: (Segment) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val b = Chagok.colors
    Tile(modifier, padding = PaddingValues(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Swatch(block.paint.fill, size = 28.dp, radius = 9.dp)
            Column(Modifier.weight(1f)) {
                Heading("${block.label} ${Phrases.km(block.km)}", 28)
                Text(if (records.isEmpty()) "앱이 기록하지 못한 거리예요." else "틀리면 눌러서 고쳐요", style = ChagokType.body(16), color = b.inkSecondary)
            }
            RoundButton(ChagokIcons.Close, "닫기", onClose, style = PillStyle.SOFT)
        }
        VSpace(8.dp)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            records.forEachIndexed { i, s ->
                if (i > 0) Divider(Modifier.padding(horizontal = 12.dp))
                RecordRow(s, people, familyIds, onOpen)
            }
        }
    }
}

@Composable
private fun RecentTile(records: List<Segment>, people: List<Person>, familyIds: List<Long>, onOpen: (Segment) -> Unit, modifier: Modifier) {
    val b = Chagok.colors
    Tile(modifier, padding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.Bottom) {
            Heading("최근 기록", 26, Modifier.weight(1f))
            Text("틀리면 눌러서 고쳐요", style = ChagokType.body(16), color = b.inkSecondary)
        }
        VSpace(4.dp)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            records.forEach { s ->
                Divider(Modifier.padding(horizontal = 12.dp))
                RecordRow(s, people, familyIds, onOpen, compact = true)
            }
        }
    }
}

@Composable
private fun RecordRow(s: Segment, people: List<Person>, familyIds: List<Long>, onOpen: (Segment) -> Unit, compact: Boolean = false) {
    val b = Chagok.colors
    val key = keyOf(s, people)
    val person = personFor(key, people)
    val paint = paintOf(key, familyIds, b)
    val who = when (key) {
        BlockKeys.OTHER -> "다른 사람이 운전"
        BlockKeys.UNCLASSIFIED -> "미분류"
        BlockKeys.SELF -> "내 길"
        else -> person?.name ?: "미분류"
    }
    val title = placeLine(s) ?: who
    val when_ = remember(s.endedAt) { SimpleDateFormat("M월 d일 a h:mm", Locale.KOREAN).format(Date(s.endedAt)) }
    val sub = listOfNotNull(who.takeIf { title != who }, "차의 제안".takeIf { s.attribution == Attribution.SUGGESTED }, when_.takeIf { !compact }).joinToString(", ")
    ListRow(onClick = { onOpen(s) }, clickLabel = "고치기", height = if (compact) 56.dp else 72.dp, lead = {
        Swatch(paint.fill, hatched = s.attribution == Attribution.SUGGESTED) {
            if (person != null && !person.isSelf && s.attribution != Attribution.SUGGESTED) {
                Text(person.name.take(1), style = ChagokType.body(16, FontWeight.ExtraBold, 1.2f), color = paint.on)
            }
        }
    }, content = {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = ChagokType.body(18, FontWeight.ExtraBold, 1.3f), color = b.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (compact && sub.isNotEmpty()) Text(sub, style = ChagokType.body(16, lineHeight = 1.3f), color = b.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!compact && sub.isNotEmpty()) Text(sub, style = ChagokType.body(16, lineHeight = 1.3f), color = b.inkSecondary, maxLines = 1)
    }, trailing = {
        Distance(s.km, 28, unitSize = 16)
    })
}

// ---------------------------------------------------------------------------------------------
// Distance the app did not see

@Composable
private fun GapSide(gap: Prompt.Gap, familyIds: List<Long>, actions: HomeActions, wide: Boolean, onChoose: () -> Unit) {
    val b = Chagok.colors
    val guess = gap.guess
    val person = gap.guessPerson
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val said: @Composable () -> Unit = {
            Text(
                if (guess != null && person != null) {
                    "${Phrases.gapGuess(guess)}\n맞으면 ${if (person.isSelf) "내" else person.name} 길로 쌓을게요."
                } else {
                    "앱이 꺼져 있던 동안의 거리예요.\n기억나면 골라 주세요."
                },
                style = ChagokType.body(18, lineHeight = 1.5f),
                color = b.onStrongSecondary,
            )
        }
        val answers: @Composable (Modifier) -> Unit = { m ->
            if (guess != null) Pill("맞아요", { actions.onAcceptGuess(gap) }, m, icon = ChagokIcons.Check)
            Pill("고르기", onChoose, m, style = PillStyle.GHOST)
            Pill("그대로 두기", { actions.onDismiss(gap.id) }, m, style = PillStyle.GHOST)
        }
        Tile(
            Modifier.fillMaxWidth().then(if (wide) Modifier.height(236.dp) else Modifier).semantics { liveRegion = LiveRegionMode.Polite },
            color = b.strong,
            padding = PaddingValues(start = 40.dp, end = 32.dp, top = 32.dp, bottom = 32.dp),
        ) {
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Kicker(if (guess != null) "차가 짐작한 길" else "앱이 못 본 사이")
                        Heading(Phrases.gap(gap.segment.km), 40, Modifier.padding(top = 10.dp), color = b.onStrong)
                        Spacer(Modifier.weight(1f))
                        said()
                    }
                    Column(Modifier.width(216.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                        answers(Modifier.fillMaxWidth())
                    }
                }
            } else {
                // The portrait window: the sentence first, the answers under it.
                Kicker(if (guess != null) "차가 짐작한 길" else "앱이 못 본 사이")
                Heading(Phrases.gap(gap.segment.km), 34, Modifier.padding(top = 10.dp), color = b.onStrong)
                VSpace(12.dp)
                said()
                VSpace(20.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { answers(Modifier.weight(1f)) }
            }
        }
        GapPicture(gap, person, familyIds, Modifier.fillMaxWidth().weight(1f))
    }
}

/** The missed distance as stretches: the guessed round trips hatched, the rest still unknown. */
@Composable
private fun GapPicture(gap: Prompt.Gap, person: Person?, familyIds: List<Long>, modifier: Modifier) {
    val b = Chagok.colors
    val guess = gap.guess
    val total = gap.segment.km
    val paint = person?.let { if (it.isSelf) b.self else b.forPerson(it.id, familyIds) } ?: b.family[0]
    val trips = if (guess != null && person != null) guess.times else 0
    val rest = (total - trips * (guess?.roundTripKm ?: 0f)).coerceAtLeast(0f)
    val spoken = if (trips > 0) "${kmNumber(total)}킬로미터 미분류. 그중 ${guess!!.placeName} 왕복 ${kmNumber(guess.roundTripKm)}킬로미터 ${trips}번이 제안됨." else "${kmNumber(total)}킬로미터 미분류."
    Tile(modifier.clearAndSetSemantics { contentDescription = spoken }, color = b.sunken, padding = PaddingValues(start = 40.dp, end = 40.dp, top = 32.dp, bottom = 40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("미분류 ${kmNumber(total)}", style = ChagokType.display(32, 1.1f), color = b.ink)
            if (trips > 0) {
                Text(
                    "빗금은 차의 짐작",
                    style = ChagokType.body(16, FontWeight.Bold, 1.3f),
                    color = b.inkSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(b.surface).padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
        VSpace(24.dp)
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(trips) {
                Stretch(guess!!.roundTripKm, "${if (person!!.isSelf) "내" else person.name} ${guess.placeName} 왕복", paint, hatched = true, Modifier.weight(guess.roundTripKm).fillMaxHeight())
            }
            if (rest >= 0.5f) Stretch(rest, "미분류", b.unclassified, hatched = false, Modifier.weight(rest).fillMaxHeight())
        }
    }
}

@Composable
private fun Stretch(km: Float, label: String, paint: Paint, hatched: Boolean, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(20.dp))) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            drawRect(paint.fill)
            if (hatched) hatch(0f, 0f, size.width, size.height)
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 24.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(kmNumber(km), style = ChagokType.figures(if (km >= 20f) 96 else 64), color = paint.on, maxLines = 1)
            Text(label, style = ChagokType.body(16, FontWeight.ExtraBold, 1.3f), color = paint.on, maxLines = 2)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared

@Composable
fun CenterCard(title: String, body: String) {
    val b = Chagok.colors
    Column(
        Modifier.width(360.dp).clip(RoundedCornerShape(32.dp)).background(b.surface).padding(horizontal = 32.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(size = 40.dp)
        Text(title, style = ChagokType.display(30, 1.15f), color = b.ink, modifier = Modifier.padding(top = 12.dp))
        Text(body, style = ChagokType.body(17, lineHeight = 1.5f), color = b.inkSecondary, modifier = Modifier.padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Which block a stretch belongs to. */
fun keyOf(s: Segment, people: List<Person>): String = when {
    s.attribution == Attribution.OTHER_DRIVER -> BlockKeys.OTHER
    s.personId == null || s.attribution == Attribution.UNCLASSIFIED -> BlockKeys.UNCLASSIFIED
    people.firstOrNull { it.id == s.personId }?.isSelf == true -> BlockKeys.SELF
    else -> BlockKeys.person(s.personId)
}

fun personFor(key: String, people: List<Person>): Person? = when {
    key == BlockKeys.SELF -> people.firstOrNull { it.isSelf }
    key.startsWith("p:") -> people.firstOrNull { it.id == key.removePrefix("p:").toLongOrNull() }
    else -> null
}

fun paintOf(key: String, familyIds: List<Long>, colors: ChagokColors): Paint = when {
    key == BlockKeys.OTHER -> colors.otherDriver
    key == BlockKeys.UNCLASSIFIED -> colors.unclassified
    key == BlockKeys.SELF -> colors.self
    else -> colors.forPerson(key.removePrefix("p:").toLong(), familyIds)
}

private fun overlaps(s: Segment, range: FillRange) = minOf(s.endKm, range.endKm) - maxOf(s.startKm, range.startKm) > 0.05f

/** "시흥동에서 돌아오는 길", "학교까지", or the plain fact that the app did not see it. */
private fun placeLine(s: Segment): String? {
    val place = s.placeName
    return when {
        !s.observed -> "앱이 못 본 사이에 달린 거리"
        place != null && s.returnLeg -> "${place}에서 돌아오는 길"
        place != null -> "${place}까지"
        s.returnLeg -> "돌아오는 길"
        else -> null
    }
}

/** "시흥동에서 돌아오는 길, 6km", with the distance set as figures. */
private fun detailLine(s: Segment, figure: Color) = buildAnnotatedString {
    placeLine(s)?.let { append("$it, ") }
    withStyle(figureSpan(18, figure)) { append(Phrases.km(s.km)) }
}
