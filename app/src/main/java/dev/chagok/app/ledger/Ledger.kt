package dev.chagok.app.ledger

/** Someone the driver drives for. Exactly one person has [isSelf] set: "내 길". */
data class Person(val id: Long, val name: String, val isSelf: Boolean = false)

enum class Attribution {
    /** Nobody knows who this stretch was for. Never guessed into a name. */
    UNCLASSIFIED,

    /** The car proposed a person from its signals and history. Shown as a proposal. */
    SUGGESTED,

    /** The driver said so. Only confirmed distance reaches named milestones. */
    CONFIRMED,

    /** Someone else drove. The distance belongs to nobody here and is shown as a load they took over. */
    OTHER_DRIVER,
}

/**
 * A stretch of the odometer. Segments never overlap and always follow the odometer, so the total is
 * correct even when the app missed trips: what it did not see becomes a [observed] = false segment.
 */
data class Segment(
    val id: Long = 0,
    val startKm: Float,
    val endKm: Float,
    val startedAt: Long,
    val endedAt: Long,
    /** False when recovered afterwards from the odometer difference. */
    val observed: Boolean = true,
    val personId: Long? = null,
    val attribution: Attribution = Attribution.UNCLASSIFIED,
    val placeName: String? = null,
    /** The door opened after stopping, a hint only. Null if none was seen. */
    val door: ExitDoor? = null,
    /** The empty drive back after dropping someone off. */
    val returnLeg: Boolean = false,
) {
    val km: Float get() = (endKm - startKm).coerceAtLeast(0f)
}

/** A door someone other than the driver gets out of: LEFT and RIGHT at the back, or the seat beside the driver. */
enum class ExitDoor { LEFT, RIGHT, PASSENGER }

/**
 * Time spent parked, as the app saw it, before the trip in [segmentId]. It counts for family only
 * when that trip was for someone in the family: waiting outside an academy, then driving them home.
 */
data class WaitEntry(
    val startedAt: Long,
    val endedAt: Long,
    val placeName: String?,
    val segmentId: Long?,
) {
    val minutes: Long get() = (endedAt - startedAt).coerceAtLeast(0) / 60_000
}

/** One tank or charge: the odometer range between two refills. */
data class Tank(
    val id: Long = 0,
    val startKm: Float,
    val endKm: Float?,
    val startedAt: Long,
    val endedAt: Long?,
)

data class PersonTotal(val person: Person, val km: Float, val confirmedKm: Float)

/** Everything a settlement card says. Family, self, other drivers and unclassified always add up to [totalKm]. */
data class Settlement(
    val totalKm: Float,
    val family: List<PersonTotal>,
    val selfKm: Float,
    val unclassifiedKm: Float,
    val waitMinutes: Long,
    val selfConfirmedKm: Float = selfKm,
    val otherDriverKm: Float = 0f,
) {
    val familyKm: Float get() = family.sumOf { it.km.toDouble() }.toFloat()
}

data class FamilyTime(val km: Float, val minutes: Long)

object Settlements {

    /**
     * Adds up segments inside an odometer range. A segment crossing the boundary counts only for the
     * part inside it. Distance the ledger has no segment for is unclassified, so the total stays true.
     */
    fun of(
        startKm: Float,
        endKm: Float,
        segments: List<Segment>,
        people: List<Person>,
        waits: List<WaitEntry> = emptyList(),
    ): Settlement {
        val total = (endKm - startKm).coerceAtLeast(0f)
        val byId = people.associateBy { it.id }
        val kmByPerson = mutableMapOf<Long, Float>()
        val confirmedByPerson = mutableMapOf<Long, Float>()
        var attributed = 0f
        var otherDriver = 0f
        for (s in segments) {
            val inside = overlap(s, startKm, endKm)
            if (s.attribution == Attribution.OTHER_DRIVER) {
                otherDriver += inside
                continue
            }
            val personId = s.personId
            if (inside <= 0f || personId == null || s.attribution == Attribution.UNCLASSIFIED) continue
            if (personId !in byId) continue
            kmByPerson.merge(personId, inside, Float::plus)
            if (s.attribution == Attribution.CONFIRMED) confirmedByPerson.merge(personId, inside, Float::plus)
            attributed += inside
        }
        val family = kmByPerson
            .mapNotNull { (id, km) ->
                val person = byId.getValue(id)
                if (person.isSelf) null else PersonTotal(person, km, confirmedByPerson[id] ?: 0f)
            }
            .sortedByDescending { it.km }
        val selfEntry = kmByPerson.entries.firstOrNull { byId.getValue(it.key).isSelf }
        val selfKm = selfEntry?.value ?: 0f
        return Settlement(
            totalKm = total,
            family = family,
            selfKm = selfKm,
            unclassifiedKm = (total - attributed - otherDriver).coerceAtLeast(0f),
            waitMinutes = familyWaits(waits, segments, people).sumOf { it.minutes },
            selfConfirmedKm = selfEntry?.let { confirmedByPerson[it.key] } ?: 0f,
            otherDriverKm = otherDriver,
        )
    }

    /** Waits followed by a trip for a family member, who is the reason for the wait. */
    fun familyWaits(waits: List<WaitEntry>, segments: List<Segment>, people: List<Person>): List<WaitEntry> {
        val family = people.filterNot { it.isSelf }.map { it.id }.toSet()
        val forFamily = segments
            .filter { it.personId in family && it.attribution != Attribution.UNCLASSIFIED }
            .map { it.id }
            .toSet()
        return waits.filter { it.segmentId in forFamily }
    }

    /**
     * Time and distance spent for family in a time window: driving for them plus waiting. Proposals
     * count here because this is the driver's own view, not a name said out loud.
     */
    fun familyTime(from: Long, to: Long, segments: List<Segment>, people: List<Person>, waits: List<WaitEntry>): FamilyTime {
        val family = people.filterNot { it.isSelf }.map { it.id }.toSet()
        val mine = segments.filter {
            it.personId in family && it.attribution != Attribution.UNCLASSIFIED && it.observed && it.endedAt in from..to
        }
        val driveMs = mine.sumOf { (it.endedAt - it.startedAt).coerceAtLeast(0) }
        val waitMs = familyWaits(waits, segments, people)
            .filter { it.startedAt in from..to }
            .sumOf { (it.endedAt - it.startedAt).coerceAtLeast(0) }
        return FamilyTime(km = mine.sumOf { it.km.toDouble() }.toFloat(), minutes = (driveMs + waitMs) / 60_000)
    }

    /** Confirmed distance for one person since [fromMillis]. Named milestones use only this. */
    fun confirmedKm(personId: Long, segments: List<Segment>, fromMillis: Long = 0): Float =
        segments
            .filter { it.personId == personId && it.attribution == Attribution.CONFIRMED && it.endedAt >= fromMillis }
            .sumOf { it.km.toDouble() }
            .toFloat()

    private fun overlap(s: Segment, startKm: Float, endKm: Float): Float =
        (minOf(s.endKm, endKm) - maxOf(s.startKm, startKm)).coerceAtLeast(0f)
}
