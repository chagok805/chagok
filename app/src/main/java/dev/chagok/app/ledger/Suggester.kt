package dev.chagok.app.ledger

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/** What the car knows when a stretch ends. Every field may be missing on some vehicles. */
data class StopContext(
    val at: Long,
    val placeName: String?,
    val door: ExitDoor?,
    /** True only when the seat sensor reported vacant. False proves nothing. */
    val passengerVacant: Boolean,
    /** True when the app watched the whole stretch, so "no door opened" actually means something. */
    val watchedWholeTrip: Boolean,
)

data class Suggestion(val personId: Long, val score: Int)

/**
 * Proposes who a stretch was for, from what the driver confirmed before.
 *
 * Doors and seats never decide on their own: a door is not a person. They only add weight to a
 * pattern the driver already established, so a wrong guess costs one correction and nothing more.
 */
class Suggester(
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {

    fun suggest(context: StopContext, history: List<Segment>, people: List<Person>): Suggestion? {
        val known = people.associateBy { it.id }
        val confirmed = history.filter {
            // A confirmed gap says who it was for, but not when or where the car went.
            it.attribution == Attribution.CONFIRMED && it.personId in known && !it.returnLeg && it.observed
        }
        val scores = mutableMapOf<Long, Int>()
        for (s in confirmed) {
            val score = similarity(context, s)
            if (score > 0) scores.merge(s.personId!!, score, Int::plus)
        }
        val best = scores.maxByOrNull { it.value }
        val total = scores.values.sum()
        if (best != null && best.value >= MIN_SCORE && best.value * 100 >= total * MIN_SHARE_PERCENT) {
            return Suggestion(best.key, best.value)
        }
        // With no pattern, a watched trip with an empty passenger seat and no door opened is the
        // only case where the car has real evidence, and it points at the driver alone.
        val self = people.firstOrNull { it.isSelf }
        if (self != null && context.watchedWholeTrip && context.passengerVacant && context.door == null) {
            return Suggestion(self.id, 0)
        }
        return null
    }

    private fun similarity(context: StopContext, s: Segment): Int {
        val place = context.placeName
        val other = s.placeName
        val placeMatches = place != null && place == other
        val sameSlot = sameTimeSlot(context.at, s.endedAt)
        // "학교" from a set destination and "역삼동" from the address may be the same spot: neither
        // agrees nor disagrees, so only time and door speak.
        val notComparable = place != null && other != null && Places.isNeighborhood(place) != Places.isNeighborhood(other)
        // Door and time alone are too weak without the same place, unless no place is known at all.
        if (place != null && !placeMatches && !notComparable) return 0
        // Time alone cannot bridge two kinds of name: the same door has to agree as well.
        if (notComparable && (context.door == null || context.door != s.door)) return 0
        // A neighborhood can hold both the school and the house, so it needs the same time of day.
        if (placeMatches && Places.isNeighborhood(place!!) && !sameSlot) return 0
        var score = 0
        if (placeMatches) score += PLACE_WEIGHT
        if (sameSlot) score += TIME_WEIGHT
        if (context.door != null && context.door == s.door) score += DOOR_WEIGHT
        return score
    }

    fun sameTimeSlot(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance(timeZone).apply { timeInMillis = a }
        val cb = Calendar.getInstance(timeZone).apply { timeInMillis = b }
        val sameDayKind = isWeekend(ca) == isWeekend(cb)
        val minutes = abs(minuteOfDay(ca) - minuteOfDay(cb))
        return sameDayKind && minutes <= TIME_SLOT_MINUTES
    }

    private fun isWeekend(c: Calendar): Boolean {
        val day = c.get(Calendar.DAY_OF_WEEK)
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY
    }

    private fun minuteOfDay(c: Calendar) = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)

    private companion object {
        const val PLACE_WEIGHT = 3
        const val TIME_WEIGHT = 2
        const val DOOR_WEIGHT = 1
        const val TIME_SLOT_MINUTES = 60
        /** About two earlier confirmations of the same place and time. */
        const val MIN_SCORE = 6
        const val MIN_SHARE_PERCENT = 60
    }
}

/** A way to explain distance the app did not see, as the driver's usual round trip repeated. */
data class GapGuess(val personId: Long, val placeName: String, val roundTripKm: Float, val times: Int) {
    val km: Float get() = roundTripKm * times
}

/**
 * Explains a gap with round trips the driver already confirmed: "86km 중 학원 왕복 18km 두 번".
 * Only ever a proposal, and the rest of the gap stays unclassified.
 */
object GapGuesser {

    private const val MIN_ROUTE_CONFIRMATIONS = 2
    private const val MAX_TIMES = 5
    private const val MIN_ROUTE_KM = 1f

    fun guess(gapKm: Float, history: List<Segment>, people: List<Person>): GapGuess? {
        val family = people.filterNot { it.isSelf }.map { it.id }.toSet()
        val routes = roundTrips(history).filter { it.personId in family }
        return routes
            .mapNotNull { route ->
                if (route.km < MIN_ROUTE_KM || route.count < MIN_ROUTE_CONFIRMATIONS) return@mapNotNull null
                val times = (gapKm / route.km).toInt().coerceAtMost(MAX_TIMES)
                if (times < 1) null else GapGuess(route.personId, route.placeName, route.km, times)
            }
            // Prefer the route the driver takes most, then the one explaining more of the gap.
            .maxWithOrNull(
                compareBy<GapGuess> { guess -> routes.first { it.placeName == guess.placeName && it.personId == guess.personId }.count }
                    .thenBy { it.km },
            )
    }

    private data class Route(val personId: Long, val placeName: String, val km: Float, val count: Int)

    /**
     * A round trip is a confirmed stretch to a place plus its return leg. Without a recorded return
     * leg the outbound distance is doubled, which is the usual shape of a drop-off.
     */
    private fun roundTrips(history: List<Segment>): List<Route> {
        val ordered = history.sortedBy { it.startKm }
        val routes = mutableMapOf<Pair<Long, String>, MutableList<Float>>()
        for ((index, s) in ordered.withIndex()) {
            val person = s.personId ?: continue
            val place = s.placeName ?: continue
            if (s.attribution != Attribution.CONFIRMED || s.returnLeg || !s.observed) continue
            val back = ordered.getOrNull(index + 1)?.takeIf { it.returnLeg && it.personId == person }
            routes.getOrPut(person to place) { mutableListOf() }.add(s.km + (back?.km ?: s.km))
        }
        return routes.map { (key, kms) ->
            val sorted = kms.sorted()
            Route(key.first, key.second, sorted[sorted.size / 2], kms.size)
        }
    }
}
