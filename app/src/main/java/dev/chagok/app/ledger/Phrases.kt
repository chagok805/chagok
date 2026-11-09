package dev.chagok.app.ledger

import java.util.Locale
import kotlin.math.roundToInt

/** Every sentence the car says or shows, in one place so the tone stays the same. */
object Phrases {

    const val SEOUL_BUSAN_ROUND_TRIP_KM = 832f

    /** Family titles the car should not speak to in 반말. */
    private val ELDERS = listOf("어머니", "아버지", "어머님", "아버님", "엄마", "아빠", "장모님", "장인어른", "할머니", "할아버지", "님")

    /** Grouped like the odometer reads: 3,420km. */
    fun km(value: Float): String = String.format(Locale.ROOT, "%,dkm", value.roundToInt())

    /** Screen reader and TTS read "km" badly, so spoken text spells it out. */
    fun spokenKm(value: Float): String = "${value.roundToInt()}킬로미터"

    /** Names are whatever the family typed, so particles depend on the last syllable. */
    private fun hasFinalConsonant(word: String): Boolean {
        val c = word.lastOrNull() ?: return false
        return c in '\uAC00'..'\uD7A3' && (c - '\uAC00') % 28 != 0
    }

    private fun objectOf(name: String) = name + if (hasFinalConsonant(name)) "을" else "를"

    private fun calling(name: String) = name + if (hasFinalConsonant(name)) "아" else "야"

    fun recorded(name: String, isSelf: Boolean): String =
        if (isSelf) "내 길로 적어 뒀어요." else "${objectOf(name)} 위한 길로 적어 뒀어요."

    /**
     * Said aloud at Park, when the child may still be in the car. It names no one on purpose: being
     * told "this drive was for you" should never sound like a debt. The card on screen has the name.
     */
    const val RECORDED_SPOKEN = "오늘 길도 차곡차곡 적어 뒀어요."

    fun ask(segmentKm: Float): String = "${km(segmentKm)}, 이 길은 누구를 위한 길이었나요?"

    /** Right after an answer, with "되돌리기" beside it. */
    fun stacked(name: String, isSelf: Boolean, stackedKm: Float): String =
        if (isSelf) "내 길에 ${km(stackedKm)}를 쌓았어요." else "$name 길에 ${km(stackedKm)}를 쌓았어요."

    const val OTHER_DRIVER_NOTED = "다른 사람이 맡아 준 길로 적었어요."

    const val LEFT_UNCLASSIFIED = "미분류로 뒀어요."

    /** Whole Seoul-Busan round trips in [km], for the "그날" pictures. */
    fun dayTrips(km: Float): Int = (km / SEOUL_BUSAN_ROUND_TRIP_KM).toInt()

    fun gap(gapKm: Float): String = "그 사이 ${km(gapKm)}를 달렸어요."

    fun gapGuess(guess: GapGuess): String =
        "${guess.placeName} 왕복 ${km(guess.roundTripKm)}가 ${times(guess.times)} 있었던 것 같아요."

    fun firstRecord(name: String): String = "오늘이 ${objectOf(name)} 위한 첫 기록이에요."

    // One span between two refills is "한 번 채워 달린 길": it reads naturally aloud and fits both
    // fuel (주유) and charging (충전), since the recorder only sees the level rise.
    fun tankSpoken(s: Settlement): String = buildString {
        append("한 번 채워 ${spokenKm(s.totalKm)}를 달렸어요.")
        // A zero is never said aloud: "가족을 위한 길이 0킬로미터" would sound like an accusation.
        if (s.familyKm >= 1f) {
            append(" 그중 가족을 위한 길이 ${spokenKm(s.familyKm)}")
            if (s.selfKm >= 1f) append(", 내 길이 ${spokenKm(s.selfKm)}")
            if (s.otherDriverKm >= 1f) append(", 다른 사람이 맡아 준 길이 ${spokenKm(s.otherDriverKm)}")
            append("예요.")
        }
    }

    fun tankHeadline(s: Settlement): String =
        if (s.familyKm >= 1f) {
            "한 번 채워 달린 ${km(s.totalKm)} 중 ${km(s.familyKm)}는 가족을 위한 길이었어요."
        } else {
            "한 번 채워 ${km(s.totalKm)}를 달렸어요."
        }

    /** Lines the driver can choose to pass on. Never sent automatically. */
    fun handOffs(personName: String?, s: Settlement): List<String> {
        val who = personName ?: "가족"
        // A sentence with a name in it carries only what the driver confirmed.
        val distance = s.family.firstOrNull { it.person.name == personName }?.confirmedKm
            ?: s.family.sumOf { it.confirmedKm.toDouble() }.toFloat()
        // Thanks come first: it is the sentence the whole ledger was hoping to make possible.
        val thanks = listOfNotNull("이번에 맡아 준 길 ${km(s.otherDriverKm)}, 고마워요.".takeIf { s.otherDriverKm >= 1f })
        return thanks + listOf(
            "한 번 채우는 동안 $who 길 ${km(distance)}를 다녀왔어요.",
            "이번 주 한 번만 맡아 줄래요?",
            "요즘 $who 길이 ${km(distance)}나 되더라고요. 같이 이야기해 볼까요?",
        )
    }

    /**
     * Read aloud in the car with the family inside. "함께 달린" on purpose: a child should not hear
     * this as a debt.
     */
    fun daySpoken(name: String, title: String, km: Float): String = buildString {
        val elder = ELDERS.any { name.endsWith(it) }
        val close = if (elder) "예요." else "야."
        append(if (elder) "$name, $title" else "${calling(name)}, $title")
        append(if (title.endsWith("날")) "부터" else " 부터")
        append(" 오늘까지 우리가 함께 달린 길이 ${spokenKm(km)}$close")
        dayComparison(km)?.let { append(" $it$close") }
    }

    /** The same comparison the car says, shown on screen once the number has arrived. */
    fun dayComparison(km: Float): String? {
        val trips = (km / SEOUL_BUSAN_ROUND_TRIP_KM).toInt()
        return if (trips >= 1) "서울에서 부산까지 ${times(trips)} 왕복한 거리" else null
    }

    fun milestone(name: String, km: Float): String = "${objectOf(name)} 위한 길 ${km(km)}"

    fun firstWeek(t: FamilyTime): String =
        "우리의 첫 주예요. 가족을 위해 ${km(t.km)}를 달렸고, ${minutes(t.minutes)}을 썼어요."

    fun thisWeek(t: FamilyTime): String = "이번 주 가족을 위해 쓴 시간 ${minutes(t.minutes)}"

    fun minutes(total: Long): String {
        val hours = total / 60
        val rest = total % 60
        return when {
            hours == 0L -> "${rest}분"
            rest == 0L -> "${hours}시간"
            else -> "${hours}시간 ${rest}분"
        }
    }

    private fun times(n: Int): String = when (n) {
        1 -> "한 번"
        2 -> "두 번"
        3 -> "세 번"
        4 -> "네 번"
        5 -> "다섯 번"
        else -> "${n}번"
    }
}

object Milestones {
    private val STEPS = listOf(100f, 500f, 1_000f, 3_000f, 5_000f, 10_000f)

    /** The step just crossed by confirmed distance, if any. */
    fun crossed(beforeKm: Float, afterKm: Float): Float? = STEPS.lastOrNull { beforeKm < it && afterKm >= it }
}
