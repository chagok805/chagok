package dev.chagok.app.data

import dev.chagok.app.ledger.Attribution
import dev.chagok.app.ledger.ExitDoor
import dev.chagok.app.ledger.Segment
import dev.chagok.app.ledger.Tank
import dev.chagok.app.ledger.WaitEntry
import java.util.Calendar

/**
 * Demonstration data for the contest presentation. A year of driving cannot happen on stage, so this
 * fills the ledger and the screen always says the numbers are demonstration data. Everything that
 * happens after seeding comes from live vehicle signals.
 */
data class SeedData(
    val now: Long,
    val people: List<String>,
    val segments: List<SeedSegment>,
    val waits: List<WaitEntry>,
    val tanks: List<Tank>,
    val days: List<SeedDay>,
    val tankStartKm: Float,
    val tankStartAt: Long,
    /** The person the seeded waits were for. */
    val waitsFor: String,
) {
    data class SeedSegment(val person: String?, val segment: Segment)
    data class SeedDay(val person: String, val title: String, val fromMillis: Long)

    companion object {
        const val SELF = "__self__"
        private const val JIWOO = "지우"
        private const val MOTHER = "어머니"
        private const val SCHOOL = "학교"
        private const val ACADEMY = "학원"
        private const val HOSPITAL = "병원"
        private const val OFFICE = "회사"

        private const val DAY_MS = 24 * 60 * 60_000L
        private const val CURRENT_TANK_KM = 600f
        /** The "그날" scene reads this for 지우 once seeded. */
        private const val JIWOO_CONFIRMED_KM = 3_420f

        /**
         * Builds the ledger backwards from [nowKm]. The running tank adds up to 600km so that the
         * first live 12km drive on stage makes it the 612km from the proposal. Only differences are
         * ever shown, so this works from any odometer, including the emulator's 0 after a cold boot.
         */
        fun build(nowKm: Float, now: Long): SeedData {
            val segments = mutableListOf<SeedSegment>()
            var km = nowKm
            var at = now - DAY_MS

            fun add(length: Float, person: String?, place: String?, attribution: Attribution, door: ExitDoor? = null, back: Boolean = false, ended: Long = at) {
                val start = km - length
                segments += SeedSegment(
                    person,
                    Segment(
                        startKm = start,
                        endKm = km,
                        startedAt = ended - (length * 90_000).toLong(),
                        endedAt = ended,
                        personId = null,
                        attribution = attribution,
                        placeName = place,
                        door = door,
                        returnLeg = back,
                    ),
                )
                km = start
            }

            // Running fill, newest first: 지우 272 + 어머니 96 + 나 190 + 미분류 42 = 600.
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            // Everything that is not 지우 happens at another time of day, so the live drive on stage has
            // one clear pattern to match at whatever hour the demo runs.
            val otherHour = (hour + 8) % 24
            var jiwooInTank = 272f
            // Days like today come first: a pattern from weekdays says nothing on a Saturday.
            val days = (1..15).sortedBy { if (isWeekend(now - it * DAY_MS) == isWeekend(now)) 0 else 1 }
            for (day in days) {
                if (jiwooInTank <= 0f) break
                val ended = sameHourDaysAgo(now, hour, day)
                // Built backwards along the odometer, so the way back, which came later, goes first.
                val leg = minOf(12f, jiwooInTank)
                val back = minOf(12f, jiwooInTank - leg)
                if (back > 0f) add(back, JIWOO, SCHOOL, Attribution.CONFIRMED, back = true, ended = ended + 40 * 60_000)
                add(leg, JIWOO, SCHOOL, Attribution.CONFIRMED, ExitDoor.LEFT, ended = ended)
                jiwooInTank -= leg + back
            }
            add(48f, MOTHER, HOSPITAL, Attribution.CONFIRMED, ended = sameHourDaysAgo(now, otherHour, 4))
            add(48f, MOTHER, HOSPITAL, Attribution.SUGGESTED, ended = sameHourDaysAgo(now, otherHour, 5))
            add(42f, null, null, Attribution.UNCLASSIFIED, ended = sameHourDaysAgo(now, otherHour, 6))
            repeat(5) { add(38f, SELF, OFFICE, Attribution.CONFIRMED, ended = sameHourDaysAgo(now, otherHour, 7 + it)) }
            val tankStartKm = km
            val tankStartAt = now - 16 * DAY_MS

            // A previous tank so the history has weight.
            at = sameHourDaysAgo(now, otherHour, 17)
            val previousEnd = km
            add(330f, JIWOO, ACADEMY, Attribution.CONFIRMED)
            add(120f, MOTHER, HOSPITAL, Attribution.CONFIRMED)
            add(170f, SELF, OFFICE, Attribution.CONFIRMED)
            val tanks = listOf(Tank(startKm = km, endKm = previousEnd, startedAt = tankStartAt - 15 * DAY_MS, endedAt = tankStartAt))

            // Everything older, confirmed, so the family's day adds up to a known number.
            val confirmedSoFar = 272f + 330f
            at = sameHourDaysAgo(now, otherHour, 40)
            add(JIWOO_CONFIRMED_KM - confirmedSoFar, JIWOO, SCHOOL, Attribution.CONFIRMED)

            val waits = listOf(
                WaitEntry(now - 2 * DAY_MS, now - 2 * DAY_MS + 80 * 60_000, ACADEMY, null),
                WaitEntry(now - 3 * DAY_MS, now - 3 * DAY_MS + 60 * 60_000, ACADEMY, null),
                WaitEntry(now - 5 * DAY_MS, now - 5 * DAY_MS + 60 * 60_000, ACADEMY, null),
            )
            val seedDays = listOf(SeedDay(JIWOO, "입학한 날", lastSchoolStart(now)))
            return SeedData(now, listOf(JIWOO, MOTHER), segments.reversed(), waits, tanks, seedDays, tankStartKm, tankStartAt, JIWOO)
        }

        /** Korean schools start in early March, so the day reads true whenever the demo runs. */
        private fun lastSchoolStart(now: Long): Long {
            val march = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.MONTH, Calendar.MARCH)
                set(Calendar.DAY_OF_MONTH, 2)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (march.timeInMillis > now) march.add(Calendar.YEAR, -1)
            return march.timeInMillis
        }

        private fun isWeekend(at: Long): Boolean {
            val day = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.DAY_OF_WEEK)
            return day == Calendar.SATURDAY || day == Calendar.SUNDAY
        }

        private fun sameHourDaysAgo(now: Long, hour: Int, days: Int): Long =
            Calendar.getInstance().apply {
                timeInMillis = now - days * DAY_MS
                set(Calendar.HOUR_OF_DAY, hour)
            }.timeInMillis
    }
}
