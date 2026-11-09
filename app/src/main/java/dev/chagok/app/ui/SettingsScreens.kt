package dev.chagok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chagok.app.ledger.Person
import dev.chagok.app.ledger.Phrases

@Composable
fun SettingsScreen(
    people: List<Person>,
    /** Distance in the running fill, by person id. */
    kmThisFill: Map<Long, Float>,
    recording: Boolean,
    voice: Boolean,
    place: Boolean,
    seeded: Boolean,
    seedMessage: String?,
    onOpenPeople: () -> Unit,
    onRecording: (Boolean) -> Unit,
    onVoice: (Boolean) -> Unit,
    onPlace: (Boolean) -> Unit,
    onSeed: () -> Unit,
    onClear: () -> Unit,
    onTab: (Tab) -> Unit,
) {
    val b = Chagok.colors
    val familyIds = people.filterNot { it.isSelf }.map { it.id }
    var confirmingSeed by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    DesignFrame { wide ->
        TilePage(
            wide = wide,
            tab = Tab.SETTINGS,
            onTab = onTab,
            narrowRightHeight = 1000.dp,
            left = {
                PageTitle("설정", "함께 타는 사람과 기록, 안내 방식을 정해요.", seeded, Modifier.fillMaxWidth().height(232.dp))
                Tile(Modifier.fillMaxWidth().then(grow(wide, 440.dp)), padding = PaddingValues(horizontal = 32.dp, vertical = 28.dp)) {
                    Heading("사람", 28)
                    Text("달린 길을 나눠 받을 가족이에요. 색이 블록 색이 돼요.", style = ChagokType.body(16), color = b.inkSecondary, modifier = Modifier.padding(top = 8.dp))
                    VSpace(12.dp)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        people.sortedBy { it.isSelf }.forEach { p ->
                            val paint = if (p.isSelf) b.self else b.forPerson(p.id, familyIds)
                            val km = kmThisFill[p.id] ?: 0f
                            ListRow(onClick = onOpenPeople, clickLabel = "사람 관리", lead = { Swatch(paint.fill, size = 44.dp) }, content = {
                                Text(if (p.isSelf) "내 길" else p.name, style = ChagokType.body(20, FontWeight.ExtraBold, 1.25f), color = b.ink)
                                Text(
                                    (if (p.isSelf) "운전하는 나, " else "") + "이번에 ${Phrases.km(km)}",
                                    style = ChagokType.body(16, lineHeight = 1.3f),
                                    color = b.inkSecondary,
                                )
                            }, trailing = { Icon(ChagokIcons.Chevron, null, tint = b.inkSecondary, modifier = Modifier.size(20.dp)) })
                        }
                        ListRow(onClick = onOpenPeople, lead = {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).border(2.dp, b.line, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                Icon(ChagokIcons.Plus, null, tint = b.inkSecondary, modifier = Modifier.size(20.dp))
                            }
                        }, content = { Text("사람 추가", style = ChagokType.body(20, FontWeight.ExtraBold, 1.25f), color = b.ink) })
                    }
                }
            },
            right = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 36.dp, vertical = 4.dp)) {
                        ToggleRow("주행 기록", "끄면 그동안 달린 거리는 미분류로만 남아요.", recording, onRecording)
                        Divider()
                        ToggleRow("음성 안내", "차를 세웠을 때만 말해요.", voice, onVoice)
                        Divider()
                        ToggleRow("장소 기록", "세운 곳의 동 이름이나 도착지 이름을 시각과 함께 적어요. 끄면 시간으로만 알아봐요.", place, onPlace)
                    }
                    ActionTile(
                        icon = ChagokIcons.Lock,
                        title = if (confirmingClear) "사람과 기록을 모두 지울까요?" else "기록은 이 차 안에만 있어요",
                        body = if (confirmingClear) "되돌릴 수 없어요." else "사람과 기록을 어디로도 보내지 않아요.",
                    ) {
                        if (confirmingClear) {
                            Pill("모두 지우기", {
                                onClear()
                                confirmingClear = false
                            }, style = PillStyle.STRONG)
                            Pill("취소", { confirmingClear = false }, style = PillStyle.LINE)
                        } else {
                            Pill("기록 모두 지우기", { confirmingClear = true }, style = PillStyle.LINE)
                        }
                    }
                    ActionTile(
                        icon = ChagokIcons.Replay,
                        title = if (confirmingSeed) "지금 기록과 녹음이 모두 사라져요." else "1년치 시연용 데이터",
                        body = when {
                            confirmingSeed -> "넣을까요?"
                            seedMessage != null -> seedMessage
                            else -> "지금 기록은 사라지고 시연용 표시가 붙어요."
                        },
                    ) {
                        if (confirmingSeed) {
                            Pill("넣기", {
                                confirmingSeed = false
                                onSeed()
                            }, style = PillStyle.STRONG)
                            Pill("취소", { confirmingSeed = false }, style = PillStyle.LINE)
                        } else {
                            Pill(if (seeded) "시연 데이터 다시 넣기" else "시연 데이터 넣기", { confirmingSeed = true }, style = PillStyle.STRONG)
                        }
                    }
                    AboutTile(Modifier.fillMaxWidth().heightIn(min = 184.dp))
                }
            },
        )
    }
}

/** A page's own title tile: wordmark, the page name, what it is for. */
@Composable
private fun PageTitle(title: String, body: String, seeded: Boolean, modifier: Modifier, trailing: @Composable () -> Unit = {}) {
    Tile(modifier) {
        BrandRow(seeded, trailing = trailing)
        Heading(title, 56, Modifier.padding(top = 24.dp))
        Text(body, style = ChagokType.body(18, lineHeight = 1.5f), color = Chagok.colors.inkSecondary, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun ActionTile(icon: ImageVector, title: String, body: String, actions: @Composable () -> Unit) {
    val b = Chagok.colors
    Tile(Modifier.fillMaxWidth().heightIn(min = 168.dp), padding = PaddingValues(horizontal = 36.dp, vertical = 28.dp)) {
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(b.canvas), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = b.ink, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = ChagokType.body(22, FontWeight.ExtraBold, 1.25f), color = b.ink)
                Text(body, style = ChagokType.body(17), color = b.inkSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** A small signature at the end of settings. */
@Composable
private fun AboutTile(modifier: Modifier) {
    val b = Chagok.colors
    Box(modifier.clip(RoundedCornerShape(32.dp)).background(b.strong).padding(horizontal = 36.dp, vertical = 32.dp)) {
        BrandMark(Modifier.align(Alignment.TopEnd), size = 112.dp)
        Column(Modifier.align(Alignment.BottomStart).padding(top = 40.dp)) {
            Text("차곡차곡", style = ChagokType.display(32, 1f), color = b.onStrong)
            Text("가족을 위해 달린 길이 사라지지 않도록.", style = ChagokType.body(17, lineHeight = 1.5f), color = b.onStrongSecondary, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// People

@Composable
fun PeopleScreen(people: List<Person>, seeded: Boolean, onAdd: (String) -> Unit, onDelete: (Long) -> Unit, onBack: () -> Unit, onTab: (Tab) -> Unit) {
    val b = Chagok.colors
    val family = people.filterNot { it.isSelf }
    val familyIds = family.map { it.id }
    var name by rememberSaveable { mutableStateOf("") }
    var consented by rememberSaveable { mutableStateOf(false) }
    // The car's keyboard floats over the lower half of the screen without resizing the window.
    var typing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    // Deleting a person also deletes their days and recordings: one more tap to be sure.
    var confirming by remember { mutableStateOf<Long?>(null) }
    DesignFrame { wide ->
        Box(Modifier.fillMaxSize().clearFocusOnTap(focus)) {
            TilePage(
                wide = wide,
                tab = Tab.SETTINGS,
                onTab = onTab,
                narrowRightHeight = 560.dp,
                left = {
                    if (!typing) {
                        PageTitle("사람", "누구를 위해 자주 달리나요? 호칭이면 충분해요.", seeded, Modifier.fillMaxWidth().heightIn(min = 232.dp)) {
                            Pill("설정", onBack, style = PillStyle.SOFT, icon = ChagokIcons.Back)
                        }
                    }
                    Tile(Modifier.fillMaxWidth().then(if (typing) Modifier else grow(wide, 440.dp)), padding = PaddingValues(horizontal = 32.dp, vertical = 28.dp)) {
                        Heading("새로운 사람", 28)
                        Text(
                            "언제, 어느 동에서 세웠는지도 함께 적어요.\n기록은 이 차 안에만 저장돼요.",
                            style = ChagokType.body(16, lineHeight = 1.5f),
                            color = b.inkSecondary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        FieldLabel("호칭", Modifier.padding(top = 20.dp))
                        InputField(name, { name = it.take(MAX_NAME) }, "예: 지우, 어머니", Modifier.padding(top = 10.dp), onTyping = { typing = it })
                        ToggleRow("동의를 받았어요", "본인이 동의했어요. 14세 미만은 보호자로서 동의해요.", consented, { consented = it }, Modifier.padding(top = 8.dp))
                        if (typing) VSpace(12.dp) else Spacer(Modifier.weight(1f))
                        Pill(
                            "등록",
                            {
                                onAdd(name.trim())
                                name = ""
                                consented = false
                                focus.clearFocus()
                            },
                            Modifier.fillMaxWidth(),
                            style = PillStyle.STRONG,
                            icon = ChagokIcons.Plus,
                            enabled = name.isNotBlank() && consented,
                            height = 60.dp,
                            textSize = 18,
                        )
                    }
                },
                right = {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tile(Modifier.fillMaxWidth().weight(1f), padding = PaddingValues(horizontal = 32.dp, vertical = 28.dp)) {
                            Heading("함께 달리는 사람", 28)
                            VSpace(12.dp)
                            if (family.isEmpty()) {
                                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                    CenterCard("아직 등록한 사람이 없어요.", "호칭을 적어 두면, 그 사람을 위한 길을\n차가 먼저 알아보고 적어 둘게요.")
                                }
                            }
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                family.forEach { p ->
                                    val asking = confirming == p.id
                                    ListRow(onClick = null, lead = { Swatch(b.forPerson(p.id, familyIds).fill, size = 44.dp) }, content = {
                                        Text(p.name, style = ChagokType.body(20, FontWeight.ExtraBold, 1.25f), color = b.ink)
                                        if (asking) Text("지우면 이름과 그날, 녹음이 사라져요.", style = ChagokType.body(16, lineHeight = 1.3f), color = b.inkSecondary)
                                    }, trailing = {
                                        if (asking) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Pill("지우기", {
                                                    onDelete(p.id)
                                                    confirming = null
                                                }, style = PillStyle.STRONG)
                                                Pill("취소", { confirming = null }, style = PillStyle.LINE)
                                            }
                                        } else {
                                            Pill("지우기", { confirming = p.id }, style = PillStyle.LINE)
                                        }
                                    })
                                }
                            }
                        }
                        Text(
                            "사람을 지우면 이름과 장소가 사라지고, 그 길은 미분류로 남아요.",
                            style = ChagokType.body(16),
                            color = b.inkSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                },
            )
        }
    }
}

private const val MAX_NAME = 12
