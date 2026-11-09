package dev.chagok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Calendar

private const val MONTHS_IN_YEAR = 12
private val DAY_NAMES = listOf("일", "월", "화", "수", "목", "금", "토")

/**
 * A calendar sized for a car: every day is a large round target. Years move as easily as months, because the
 * day a family counts from can be years back. The future cannot be picked, since no distance is there.
 */
@Composable
fun DateSheet(selected: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val today = remember { Calendar.getInstance() }
    val thisYear = today.get(Calendar.YEAR)
    val thisMonth = today.get(Calendar.MONTH)
    val start = remember { Calendar.getInstance().apply { selected?.let { timeInMillis = it } } }
    var year by rememberSaveable { mutableIntStateOf(start.get(Calendar.YEAR)) }
    var month by rememberSaveable { mutableIntStateOf(start.get(Calendar.MONTH)) }
    var picked by rememberSaveable { mutableStateOf(selected) }

    // A dialog is its own window with its own density: carry the page's design scale into it.
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
            Tile(Modifier.width(520.dp), padding = PaddingValues(horizontal = 32.dp, vertical = 28.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stepper(
                        label = "${year}년",
                        what = "해",
                        canGoForward = year < thisYear,
                        onBack = { year-- },
                        onForward = {
                            year++
                            if (year == thisYear && month > thisMonth) month = thisMonth
                        },
                    )
                    Stepper(
                        label = "${month + 1}월",
                        what = "달",
                        canGoForward = year < thisYear || month < thisMonth,
                        onBack = {
                            if (month == 0) {
                                year--
                                month = MONTHS_IN_YEAR - 1
                            } else {
                                month--
                            }
                        },
                        onForward = {
                            if (month == MONTHS_IN_YEAR - 1) {
                                year++
                                month = 0
                            } else {
                                month++
                            }
                        },
                    )
                    MonthGrid(year, month, today, picked) { picked = it }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("취소", onDismiss, Modifier.weight(1f), style = PillStyle.SOFT)
                        Pill("확인", { picked?.let(onPick) }, Modifier.weight(1f), style = PillStyle.STRONG, enabled = picked != null)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, what: String, canGoForward: Boolean, onBack: () -> Unit, onForward: () -> Unit) {
    val b = Chagok.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("이전 $what", enabled = true, flipped = false, onBack)
        Text(
            label,
            style = ChagokType.display(28, 1.1f),
            color = b.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        StepButton("다음 $what", enabled = canGoForward, flipped = true, onForward)
    }
}

@Composable
private fun StepButton(description: String, enabled: Boolean, flipped: Boolean, onClick: () -> Unit) {
    val b = Chagok.colors
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (enabled) b.canvas else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            ChagokIcons.Back,
            null,
            tint = if (enabled) b.ink else b.faint,
            modifier = Modifier.size(24.dp).rotate(if (flipped) 180f else 0f),
        )
    }
}

@Composable
private fun MonthGrid(year: Int, month: Int, today: Calendar, picked: Long?, onPick: (Long) -> Unit) {
    val b = Chagok.colors
    val first = Calendar.getInstance().apply {
        clear()
        set(year, month, 1)
    }
    val blanks = first.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
    val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val cells = List(blanks) { null } + (1..days).toList()
    Column(Modifier.widthIn(max = 520.dp)) {
        Row {
            DAY_NAMES.forEach { name ->
                Box(Modifier.weight(1f).height(36.dp), contentAlignment = Alignment.Center) {
                    Text(name, style = ChagokType.body(16, FontWeight.Bold, 1.3f), color = b.inkSecondary)
                }
            }
        }
        cells.chunked(DAY_NAMES.size).forEach { week ->
            Row {
                week.forEach { day ->
                    Box(Modifier.weight(1f).height(56.dp), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val at = Calendar.getInstance().apply {
                                clear()
                                set(year, month, day)
                            }.timeInMillis
                            DayCell(day, year, month, today, at == picked, at <= today.timeInMillis) { onPick(at) }
                        }
                    }
                }
                repeat(DAY_NAMES.size - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, year: Int, month: Int, today: Calendar, isPicked: Boolean, selectable: Boolean, onClick: () -> Unit) {
    val b = Chagok.colors
    val isToday = today.get(Calendar.YEAR) == year && today.get(Calendar.MONTH) == month && today.get(Calendar.DAY_OF_MONTH) == day
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (isPicked) b.strong else Color.Transparent)
            .then(if (isToday && !isPicked) Modifier.border(2.dp, b.inkSecondary, CircleShape) else Modifier)
            .clickable(enabled = selectable, onClick = onClick)
            .semantics {
                contentDescription = "${year}년 ${month + 1}월 ${day}일"
                selected = isPicked
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$day",
            style = ChagokType.figures(20),
            color = when {
                isPicked -> b.onStrong
                selectable -> b.ink
                else -> b.faint
            },
        )
    }
}
