package dev.chagok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.chagok.app.ledger.Person
import dev.chagok.app.ledger.Phrases
import dev.chagok.app.ledger.Settlement

/** The settlement of a fill-up, and the way to bring it up with someone if the driver wants to. */
@Composable
fun SettlementScreen(
    settlement: Settlement,
    people: List<Person>,
    /** A fill that has ended: "지난번", and nothing says "이번". */
    closed: Boolean,
    familyMinutes: Long,
    seeded: Boolean,
    onConfirmAll: () -> Unit,
    onTab: (Tab) -> Unit,
) {
    val b = Chagok.colors
    val familyIds = people.filterNot { it.isSelf }.map { it.id }
    val blocks = blocksOf(settlement, familyIds, b)
    var justConfirmed by rememberSaveable { mutableStateOf(false) }
    DesignFrame { wide ->
        TilePage(
            wide = wide,
            tab = Tab.SETTLEMENT,
            onTab = onTab,
            narrowRightHeight = 760.dp,
            left = {
                SettlementHero(settlement, closed, seeded, blocks.any { it.proposedKm >= 1f }, Modifier.fillMaxWidth().then(grow(wide, 440.dp)))
                Row(Modifier.fillMaxWidth().height(if (wide) 256.dp else 240.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProposalsTile(settlement, justConfirmed, {
                        justConfirmed = true
                        onConfirmAll()
                    }, Modifier.weight(1f).fillMaxHeight())
                    WeekTile("그동안", familyMinutes, settlement.waitMinutes, Modifier.weight(1f).fillMaxHeight())
                }
            },
            right = {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (blocks.isEmpty()) {
                        EmptyGrid(Modifier.fillMaxWidth().weight(1f)) { CenterCard("아직 달린 길이 없어요.", "달린 만큼 아래부터 채워져요.") }
                    } else {
                        BlockMap(blocks, Modifier.fillMaxWidth().weight(1f), drop = false, compact = true)
                    }
                    HandOffTile(settlement, Modifier.fillMaxWidth().height(328.dp))
                }
            },
        )
    }
}

/** The 결산 tab before the odometer has been read: what is happening, not an unresponsive tab. */
@Composable
fun SettlementPendingScreen(seeded: Boolean, odometerAvailable: Boolean, onTab: (Tab) -> Unit) {
    val b = Chagok.colors
    DesignFrame { wide ->
        TilePage(
            wide = wide,
            tab = Tab.SETTLEMENT,
            onTab = onTab,
            left = {
                Tile(Modifier.fillMaxWidth().then(grow(wide, 440.dp))) {
                    BrandRow(seeded)
                    Heading("결산", 56, Modifier.padding(top = 24.dp))
                    Text(
                        if (odometerAvailable) {
                            "주행거리를 읽으면 이번에 채워 달린 길이\n여기에 정리돼요."
                        } else {
                            "이 차에서는 주행거리를 읽을 수 없어요.\n거리를 읽을 수 있는 차에서 다시 열어 주세요."
                        },
                        style = ChagokType.body(18, lineHeight = 1.5f),
                        color = b.inkSecondary,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            },
            right = {
                EmptyGrid(Modifier.fillMaxSize()) {
                    CenterCard(
                        if (odometerAvailable) "주행거리를 읽고 있어요." else "주행거리를 읽을 수 없어요.",
                        if (odometerAvailable) "잠시 뒤 결산이 여기에 쌓여요." else "결산은 거리를 읽을 수 있을 때만 보여요.",
                    )
                }
            },
        )
    }
}

@Composable
private fun SettlementHero(s: Settlement, closed: Boolean, seeded: Boolean, proposals: Boolean, modifier: Modifier) {
    val b = Chagok.colors
    val span = if (closed) "지난번 채워 달린" else "이번에 채워 달린"
    val family = s.familyKm >= 1f
    Tile(modifier) {
        BrandRow(seeded)
        VSpace(28.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    append("$span ")
                    withStyle(figureSpan(22, b.ink)) { append(Phrases.km(s.totalKm)) }
                    if (family) append(" 중")
                },
                style = ChagokType.body(18, FontWeight.SemiBold, 1.3f),
                color = b.inkSecondary,
                modifier = Modifier.weight(1f),
            )
            if (proposals) ProposalKey()
        }
        VSpace(16.dp)
        Distance(if (family) s.familyKm else s.totalKm, 180, unitSize = 40)
        Spacer(Modifier.weight(1f))
        val names = s.family.filter { it.km >= 1f }.map { it.person.name }
        Text(
            when {
                !family -> "이때는 가족을 위한\n길로 적힌 거리가 없어요."
                names.size == 1 -> "${names.first()}${if (endsWithConsonant(names.first())) "을" else "를"} 위한\n길이었어요."
                else -> "가족을 위한\n길이었어요."
            },
            style = ChagokType.display(44, 1.08f),
            color = b.ink,
            modifier = Modifier.padding(top = 24.dp).semantics { heading() },
        )
    }
}

private fun endsWithConsonant(word: String): Boolean {
    val c = word.lastOrNull() ?: return false
    return c in '가'..'힣' && (c - '가') % 28 != 0
}

/** What the car proposed in this fill, confirmed in one answer. */
@Composable
private fun ProposalsTile(s: Settlement, justConfirmed: Boolean, onConfirmAll: () -> Unit, modifier: Modifier) {
    val b = Chagok.colors
    val pending = s.family.filter { it.km - it.confirmedKm >= 1f }.map { it.person.name to (it.km - it.confirmedKm) } +
        listOfNotNull(("내 길" to (s.selfKm - s.selfConfirmedKm)).takeIf { it.second >= 1f })
    Tile(
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = b.strong,
        padding = PaddingValues(start = 24.dp, end = 20.dp, top = 22.dp, bottom = 20.dp),
    ) {
        if (pending.isNotEmpty()) {
            Text("차가 제안한 거리", style = ChagokType.body(16, FontWeight.Bold, 1.3f), color = b.onStrongSecondary)
            Text(
                buildAnnotatedString {
                    pending.take(2).forEachIndexed { i, (name, km) ->
                        if (i > 0) append("\n")
                        append("$name ")
                        withStyle(figureSpan(28, b.onStrong)) { append(Phrases.km(km)) }
                    }
                    if (pending.size > 2) append("\n외 ${pending.size - 2}명")
                },
                style = ChagokType.display(28, 1.15f),
                color = b.onStrong,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text("맞으면 한 번에 쌓아요.", style = ChagokType.body(16, lineHeight = 1.35f), color = b.onStrongSecondary, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.weight(1f))
            Pill("모두 맞아요", onConfirmAll, Modifier.fillMaxWidth(), icon = ChagokIcons.Check)
        } else {
            Box(Modifier.size(44.dp).clip(CircleShape).background(b.family[0].fill), contentAlignment = Alignment.Center) {
                Icon(ChagokIcons.Check, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (justConfirmed) "제안을\n모두 쌓았어요." else "모두 확인한\n길이에요.",
                style = ChagokType.display(28, 1.1f),
                color = b.onStrong,
            )
        }
    }
}

/** "한 줄 건네기": pick a sentence, carry it to a phone by QR. Nothing is ever sent by itself. */
@Composable
private fun HandOffTile(s: Settlement, modifier: Modifier) {
    val b = Chagok.colors
    // Named lines carry confirmed distance only. With none yet, "0km" is not worth passing on.
    val options = Phrases.handOffs(s.family.firstOrNull()?.person?.name, s).filterNot { it.contains(" 0km") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // Confirming proposals changes the numbers: a QR must never carry a sentence no longer on screen.
    val chosen = selected?.takeIf { it in options } ?: options.firstOrNull()
    Tile(modifier, color = b.strong, padding = PaddingValues(start = 32.dp, end = 28.dp, top = 28.dp, bottom = 28.dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                Heading("한 줄 건네기", 28, color = b.onStrong)
                VSpace(16.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.take(3).forEach { line -> LineChoice(line, chosen == line) { selected = line } }
                }
            }
            Column(Modifier.width(188.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (chosen != null) QrCode(chosen, Modifier.size(188.dp)) else QrPlaceholder(Modifier.size(188.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(ChagokType.body(17, FontWeight.ExtraBold).toSpanStyle().copy(color = b.onStrong)) { append("폰 카메라로 비춰요.\n") }
                        append("저절로 보내지지 않아요.")
                    },
                    style = ChagokType.body(16, lineHeight = 1.45f),
                    color = b.onStrongSecondary,
                )
            }
        }
    }
}

@Composable
private fun LineChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    val b = Chagok.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressScale(pressed)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Color.White else Color.White.copy(alpha = if (pressed) 0.16f else 0.08f))
            .selectable(selected, interaction, null, role = Role.RadioButton, onClick = onClick)
            .padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = ChagokType.body(17, FontWeight.Bold, 1.4f), color = if (selected) Color(0xFF13213D) else Color.White, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (selected) Color(0xFF13213D) else Color.Transparent)
                .then(if (selected) Modifier else Modifier.border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(ChagokIcons.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}
