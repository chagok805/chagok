package dev.chagok.app.ledger

import kotlin.math.abs

/** Names for where a stretch ended when the driver set no destination. */
object Places {

    private val NEIGHBORHOOD = Regex("[가-힣][가-힣0-9]*동|[가-힣]{2,}(읍|면)|[가-힣][가-힣0-9]*[0-9]가")
    private val ROAD = Regex("[가-힣][가-힣0-9]*(로|길)")
    private val NUMBERED_ROAD = Regex("(?<=[가-힣])[0-9]+번?(로|길)$")
    private val HOUSE_NUMBER = Regex("[0-9]+(-[0-9]+)?")
    private val BRACKETS = Regex("\\(([^)]*)\\)")
    private val SEPARATORS = Regex("[\\s,]+")

    /**
     * Keeps only the neighborhood of an address, so no house number is ever stored:
     * "서울특별시 강남구 테헤란로 152 (역삼동, 강남파이낸스센터)" becomes "역삼동". Whatever follows the
     * first number is a building or a unit, never a neighborhood.
     */
    fun neighborhoodOf(address: String): String? {
        val inBrackets = BRACKETS.find(address)?.groupValues?.get(1).orEmpty().split(SEPARATORS)
        val beforeNumber = address.replace(BRACKETS, " ").split(SEPARATORS)
            .filter { it.isNotEmpty() }
            .takeWhile { !it.first().isDigit() }
        return (beforeNumber + inBrackets).firstOrNull(NEIGHBORHOOD::matches)
            ?: beforeNumber.asSequence().map(::roadOf).firstOrNull(ROAD::matches)
    }

    /** "판교역로146번길" is a house-level lane of "판교역로". */
    private fun roadOf(part: String) = if (NUMBERED_ROAD.containsMatchIn(part)) part.replace(NUMBERED_ROAD, "") else part

    /** A destination can be a bare address; a name with a house number in it is cut down the same way. */
    fun storableName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.split(SEPARATORS).any(HOUSE_NUMBER::matches)) neighborhoodOf(trimmed) else trimmed
    }

    /** A neighborhood can hold both the school and the house, so it is weaker than a named place. */
    fun isNeighborhood(name: String): Boolean = NEIGHBORHOOD.matches(name) || ROAD.matches(name)
}

/** Driving straight back from a drop-off, over about the same distance. */
object WayBack {
    private const val SAME_KM = 0.01f

    fun follows(out: Segment, backStartedAt: Long, backKm: Float): Boolean =
        !out.returnLeg &&
            out.door != null &&
            out.km > 0f &&
            backStartedAt - out.endedAt <= LedgerEngine.RETURN_WINDOW_MS &&
            abs(backKm - out.km) <= out.km * LedgerEngine.RETURN_LENGTH_TOLERANCE

    /** The way out a way back belongs to, if [back] is one. */
    fun wayOutOf(back: Segment, all: List<Segment>): Segment? =
        if (!back.returnLeg) null else all.firstOrNull { !it.returnLeg && abs(it.endKm - back.startKm) < SAME_KM }

    /** The stretch that starts where [segment] ended. */
    fun legAfter(segment: Segment, all: List<Segment>): Segment? =
        all.firstOrNull { it.id != segment.id && abs(it.startKm - segment.endKm) < SAME_KM }

    /** The other leg that naming someone for [answered] also settles. What the driver confirmed is never touched. */
    fun otherLegSettled(answered: Segment, all: List<Segment>): Segment? =
        if (answered.returnLeg) {
            wayOutOf(answered, all)?.takeIf { it.attribution != Attribution.CONFIRMED }
        } else {
            legAfter(answered, all)?.takeIf { back ->
                (back.attribution == Attribution.SUGGESTED && back.returnLeg) ||
                    (back.attribution == Attribution.UNCLASSIFIED && back.observed && follows(answered, back.startedAt, back.km))
            }
        }

    /** The way back that goes with [answered] when the driver leaves it unclassified. */
    fun otherLegLeft(answered: Segment, all: List<Segment>): Segment? =
        legAfter(answered, all)?.takeIf { it.returnLeg && it.attribution == Attribution.SUGGESTED }

    /** The other leg that "someone else drove" also covers. */
    fun otherLegOtherDriver(answered: Segment, all: List<Segment>): Segment? =
        if (answered.returnLeg) {
            wayOutOf(answered, all)
        } else {
            legAfter(answered, all)?.takeIf { back -> back.returnLeg || (back.observed && follows(answered, back.startedAt, back.km)) }
        }?.takeIf { it.attribution != Attribution.CONFIRMED }
}
