package dev.chagok.app.ledger

import dev.chagok.app.data.SeedData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Signal orders, restarts and demo days that broke the ledger once. */
class LedgerEdgeCasesTest {

    private val tz = TimeZone.getTimeZone("Asia/Seoul")
    private val me = Person(1, "나", isSelf = true)
    private val jiwoo = Person(2, "지우")
    private val mom = Person(3, "어머니")
    private val people = listOf(me, jiwoo, mom)

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(tz).apply {
            clear()
            set(2026, Calendar.NOVEMBER, day, hour, minute)
        }.timeInMillis

    private fun minutes(s: Segment) = (s.endedAt - s.startedAt) / 60_000

    /** The repository's trim and split rules, without a database. */
    private class FakeLedger {
        val rows = linkedMapOf<Long, Segment>()
        private var next = 1L

        fun save(s: Segment): Segment? {
            val covered = rows.values.maxOfOrNull { it.endKm }
            val start = if (covered != null && covered > s.startKm) covered else s.startKm
            if (s.endKm - start < 0.05f) return null
            val trimmed = s.copy(startKm = start, id = next++)
            rows[trimmed.id] = trimmed
            return trimmed
        }

        fun guess(id: Long, km: Float, personId: Long) {
            val g = rows.getValue(id)
            val end = (g.startKm + km).coerceAtMost(g.endKm)
            rows[id] = g.copy(endKm = end, personId = personId, attribution = Attribution.SUGGESTED)
            if (g.endKm - end > 0.01f) {
                val rest = g.copy(id = next++, startKm = end)
                rows[rest.id] = rest
            }
        }

        fun covered() = rows.values.sumOf { it.km.toDouble() }.toFloat()
    }

    private fun apply(e: LedgerEngine, f: FakeLedger, events: List<LedgerEvent>) {
        for (event in events) {
            when (event) {
                is LedgerEvent.GapFound -> f.save(event.segment)?.let { e.onGapSaved(event.segment, it.id) }
                is LedgerEvent.GapRetracted -> f.rows.remove(event.segmentId)
                is LedgerEvent.SegmentClosed -> f.save(event.segment)?.let { e.onSegmentSaved(it.id) }
                is LedgerEvent.SegmentExtended -> f.rows[event.segmentId]?.let { r ->
                    if (r.endKm < event.endKm) f.rows[event.segmentId] = r.copy(endKm = event.endKm)
                }
                else -> Unit
            }
        }
    }

    // ---- A trip whose ticks all arrive after Park ----

    @Test
    fun `a wait survives when the trip closes on late ticks`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 100f, lastSeenAt = at(2, 17)))
        e.onGear(Gear.PARK, 100f, at(2, 17), "학원", emptyList(), people)
        e.onGear(Gear.DRIVING, 100f, at(2, 17, 40), null, emptyList(), people)
        e.onGear(Gear.PARK, 100f, at(2, 18), "집", emptyList(), people)
        val closed = e.onOdometer(109f, at(2, 18) + 2_000, emptyList(), people).single() as LedgerEvent.SegmentClosed
        assertEquals(40L, closed.wait?.minutes)
    }

    @Test
    fun `a rear door before leaving still counts when the trip closes on late ticks`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 100f, lastSeenAt = at(2, 8)))
        e.onPassengerSeat(true)
        e.onGear(Gear.PARK, 100f, at(2, 8), null, emptyList(), people)
        e.onDoorOpened(ExitDoor.LEFT, at(2, 8))
        e.onGear(Gear.DRIVING, 100f, at(2, 8, 1), null, emptyList(), people)
        e.onGear(Gear.PARK, 100f, at(2, 8, 20), "어린이집", emptyList(), people)
        val closed = e.onOdometer(112f, at(2, 8, 20) + 2_000, emptyList(), people).single() as LedgerEvent.SegmentClosed
        assertNull(closed.segment.personId)
    }

    // ---- Jumps taken back ----

    @Test
    fun `a retracted jump does not back-date the trip`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 100f, lastSeenAt = at(2, 8)))
        e.onGear(Gear.PARK, 100f, at(2, 8), null, emptyList(), people)
        val jump = e.onOdometer(103f, at(2, 17)).single() as LedgerEvent.GapFound
        e.onGapSaved(jump.segment, 9)
        e.onGear(Gear.DRIVING, 103f, at(2, 17) + 2_000, null, emptyList(), people)
        val closed = e.onGear(Gear.PARK, 112f, at(2, 17, 30), "집", emptyList(), people).single() as LedgerEvent.SegmentClosed
        assertTrue(minutes(closed.segment) <= 31)
    }

    @Test
    fun `every jump just before drive is taken back`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 100f, lastSeenAt = at(2, 8)))
        e.onGear(Gear.PARK, 100f, at(2, 8), null, emptyList(), people)
        val a = e.onOdometer(103f, at(2, 9)).single() as LedgerEvent.GapFound
        e.onGapSaved(a.segment, 9)
        val b = e.onOdometer(106f, at(2, 9) + 1_000).single() as LedgerEvent.GapFound
        e.onGapSaved(b.segment, 10)
        val retracted = e.onGear(Gear.DRIVING, 106f, at(2, 9) + 2_000, null, emptyList(), people)
            .filterIsInstance<LedgerEvent.GapRetracted>().map { it.segmentId }
        assertEquals(listOf(9L, 10L), retracted.sorted())
    }

    @Test
    fun `an explained jump is kept and the trip after it stays whole`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 100f, lastSeenAt = at(2, 8)))
        val f = FakeLedger()
        apply(e, f, e.onGear(Gear.PARK, 100f, at(2, 8), null, emptyList(), people))
        apply(e, f, e.onOdometer(130f, at(2, 9)))
        f.guess(1, 18f, 2)
        e.forgetLiveGaps()
        apply(e, f, e.onGear(Gear.DRIVING, 130f, at(2, 9) + 5_000, null, f.rows.values.toList(), people))
        apply(e, f, e.onOdometer(135f, at(2, 9, 10)))
        apply(e, f, e.onGear(Gear.PARK, 135f, at(2, 9, 20), null, f.rows.values.toList(), people))
        assertEquals(35f, f.covered(), 0.1f)
    }

    // ---- Restarts ----

    private fun interrupted() =
        LedgerEngine(Suggester(tz), EngineState(lastKm = 112f, lastSeenAt = at(2, 22), tripStartKm = 100f, tripStartAt = at(2, 21, 30)))

    @Test
    fun `a trip open when the process died ends when the app last saw it`() {
        val e = interrupted()
        e.onStart(112f, at(3, 8))
        val closed = e.onGear(Gear.PARK, 112f, at(3, 8), "회사", emptyList(), people).single() as LedgerEvent.SegmentClosed
        assertEquals(30L, minutes(closed.segment))
        assertNull(closed.segment.placeName)
    }

    @Test
    fun `a night trip is not named from a morning pattern after a restart`() {
        val history = (2..4).map { d ->
            Segment(startKm = 0f, endKm = 12f, startedAt = at(d, 7, 50), endedAt = at(d, 8), personId = 2, attribution = Attribution.CONFIRMED)
        }
        val e = interrupted()
        e.onStart(112f, at(5, 8))
        val closed = e.onGear(Gear.PARK, 112f, at(5, 8), null, history, people).single() as LedgerEvent.SegmentClosed
        assertNull(closed.segment.personId)
    }

    // ---- Stops that are not trips ----

    @Test
    fun `a parking maneuver keeps the drop-off hint and the wait`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 0f, lastSeenAt = at(2, 8)))
        e.onGear(Gear.PARK, 0f, at(2, 8), null, emptyList(), people)
        e.onGear(Gear.DRIVING, 0f, at(2, 8, 1), null, emptyList(), people)
        e.onGear(Gear.PARK, 12f, at(2, 8, 20), "학원", emptyList(), people)
        e.onSegmentSaved(42)
        // Reverse into the spot, Park again: no distance.
        e.onGear(Gear.DRIVING, 12f, at(2, 8, 21), null, emptyList(), people)
        e.onGear(Gear.PARK, 12f, at(2, 8, 22), "학원", emptyList(), people)
        val hint = e.onDoorOpened(ExitDoor.LEFT, at(2, 8, 23)) as LedgerEvent.DropOffSeen
        assertEquals(42L, hint.segmentId)
        e.onGear(Gear.DRIVING, 12f, at(2, 9), null, emptyList(), people)
        val closed = e.onGear(Gear.PARK, 21f, at(2, 9, 20), "집", emptyList(), people).single() as LedgerEvent.SegmentClosed
        assertEquals(40L, closed.wait?.minutes)
    }

    @Test
    fun `a door opened long after parking is not a drop-off`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 0f, lastSeenAt = at(2, 8)))
        e.onGear(Gear.PARK, 0f, at(2, 8), null, emptyList(), people)
        e.onGear(Gear.DRIVING, 0f, at(2, 8, 1), null, emptyList(), people)
        e.onGear(Gear.PARK, 12f, at(2, 8, 20), "집", emptyList(), people)
        e.onSegmentSaved(42)
        assertNull(e.onDoorOpened(ExitDoor.LEFT, at(2, 11)))
    }

    // ---- Patterns and asking ----

    @Test
    fun `a confirmed gap is not a pattern`() {
        val history = (2..5).map { d ->
            Segment(startKm = 0f, endKm = 12f, startedAt = at(d, 7, 50), endedAt = at(d, 8), observed = false, personId = 2, attribution = Attribution.CONFIRMED)
        }
        assertNull(Suggester(tz).suggest(StopContext(at(6, 8), null, null, passengerVacant = false, watchedWholeTrip = true), history, people))
    }

    @Test
    fun `the car stops asking after the first visits even when nobody answers`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 0f, lastSeenAt = at(2, 8)))
        e.onGear(Gear.PARK, 0f, at(2, 8), null, emptyList(), people)
        val history = mutableListOf<Segment>()
        var km = 0f
        var asked = 0
        for (day in 2..11) {
            e.onGear(Gear.DRIVING, km, at(day, 8), null, history, people)
            km += 12f
            val closed = e.onGear(Gear.PARK, km, at(day, 8, 20), "어린이집", history, people).single() as LedgerEvent.SegmentClosed
            history += closed.segment.copy(id = day.toLong())
            if (closed.shouldAsk) asked++
        }
        assertEquals(LedgerEngine.ASK_FIRST_VISITS, asked)
    }

    @Test
    fun `without navigation the same hour stands in for the place`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 0f, lastSeenAt = at(2, 8)))
        e.onGear(Gear.PARK, 0f, at(2, 8), null, emptyList(), people)
        val history = mutableListOf<Segment>()
        var km = 0f
        var asked = 0
        // Weekdays only: a weekend is a different slot and gets its own first visits.
        val weekdays = listOf(2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 16, 17)
        for ((i, day) in weekdays.withIndex()) {
            e.onGear(Gear.DRIVING, km, at(day, 8), null, history, people)
            km += 30f
            val closed = e.onGear(Gear.PARK, km, at(day, 8, 20), null, history, people).single() as LedgerEvent.SegmentClosed
            if (closed.shouldAsk) asked++
            history += closed.segment.copy(id = day.toLong(), personId = if (i % 2 == 0) 2L else 3L, attribution = Attribution.CONFIRMED)
        }
        assertTrue("asked $asked of 12", asked <= LedgerEngine.ASK_FIRST_VISITS)
    }

    // ---- Words said to family ----

    @Test
    fun `elders are not spoken to in banmal`() {
        val line = Phrases.daySpoken("어머니", "입학한 날", 900f)
        assertTrue(line, line.startsWith("어머니, "))
        assertFalse(line, line.contains("야."))
        assertTrue(Phrases.daySpoken("지우", "입학한 날", 900f).endsWith("거리야."))
    }

    // ---- The seeded demo ----

    private fun seedSuggestion(now: Long): Long? {
        val seed = SeedData.build(10_000f, now)
        val ids = mapOf("지우" to 2L, "어머니" to 3L, SeedData.SELF to 1L)
        val history = seed.segments.mapIndexed { i, s -> s.segment.copy(id = i + 1L, personId = s.person?.let(ids::getValue)) }
        val context = StopContext(now + 20 * 60_000, null, null, passengerVacant = false, watchedWholeTrip = true)
        return Suggester(TimeZone.getDefault()).suggest(context, history, people)?.personId
    }

    private fun withSeoulTime(block: () -> Unit) {
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(tz)
        try {
            block()
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `the seeded demo proposes the child on any day at any hour`() = withSeoulTime {
        val misses = mutableListOf<String>()
        for (day in 1..14) {
            for (hour in 0..23) {
                val now = at(day, hour, 30)
                if (seedSuggestion(now) != 2L) misses += "11/$day ${hour}시"
            }
        }
        assertTrue("misses: $misses", misses.isEmpty())
    }

    @Test
    fun `seeding works from the emulator's zero odometer`() = withSeoulTime {
        val seed = SeedData.build(0f, at(3, 10))
        val ids = mapOf("지우" to 2L, "어머니" to 3L, SeedData.SELF to 1L)
        val history = seed.segments.mapIndexed { i, s -> s.segment.copy(id = i + 1L, personId = s.person?.let(ids::getValue)) }
        val settlement = Settlements.of(seed.tankStartKm, 12f, history, people)
        assertEquals(612f, settlement.totalKm, 0.5f)
        assertEquals(368f, settlement.familyKm, 0.5f)
    }

    @Test
    fun `the seeded totals are what the presentation says on any day`() = withSeoulTime {
        for (day in 1..7) {
            val seed = SeedData.build(10_000f, at(day, 10))
            val ids = mapOf("지우" to 2L, "어머니" to 3L, SeedData.SELF to 1L)
            val history = seed.segments.mapIndexed { i, s -> s.segment.copy(id = i + 1L, personId = s.person?.let(ids::getValue)) }
            val s = Settlements.of(seed.tankStartKm, 10_000f, history, people)
            assertEquals(600f, s.totalKm, 0.5f)
            assertEquals(368f, s.familyKm, 0.5f)
            assertEquals(3420f, Settlements.confirmedKm(2, history, seed.days.single().fromMillis), 0.5f)
            assertEquals(0, history.sortedBy { it.startKm }.zipWithNext().count { (a, b) -> a.endKm > b.startKm + 0.01f })
        }
    }

    // ---- Drop-offs in Drive ----

    private fun drivingFrom(km: Float, whenAt: Long): LedgerEngine {
        val e = LedgerEngine(Suggester(tz))
        e.onStart(km, whenAt)
        e.onGear(Gear.PARK, km, whenAt, null, emptyList(), people)
        e.onGear(Gear.DRIVING, km, whenAt, null, emptyList(), people)
        return e
    }

    /** What the recorder does with a rear door in Drive. */
    private fun doorInDrive(
        e: LedgerEngine,
        km: Float,
        whenAt: Long,
        place: String? = null,
        door: ExitDoor = ExitDoor.LEFT,
    ): LedgerEvent.SegmentClosed? =
        if (e.doorMarksStop(door, km, whenAt)) e.closeAtDoor(door, km, whenAt, place, emptyList(), people) else null

    @Test
    fun `a rear door in Drive ends the stretch there and the next one starts from it`() {
        val e = drivingFrom(100f, at(2, 8))
        val f = FakeLedger()
        e.onOdometer(106f, at(2, 8, 15))
        apply(e, f, listOfNotNull(doorInDrive(e, 106f, at(2, 8, 15))))
        e.onOdometer(112f, at(2, 8, 30))
        apply(e, f, e.onGear(Gear.PARK, 112f, at(2, 8, 30), null, emptyList(), people))
        assertEquals(listOf(100f to 106f, 106f to 112f), f.rows.values.map { it.startKm to it.endKm })
    }

    @Test
    fun `a rear door in Drive before moving is someone getting in`() {
        val e = drivingFrom(100f, at(2, 8))
        assertNull(doorInDrive(e, 100f, at(2, 8, 1)))
        e.onOdometer(100.3f, at(2, 8, 2))
        assertNull("a few hundred metres is not a stop", doorInDrive(e, 100.3f, at(2, 8, 2)))
    }

    @Test
    fun `the other door at the same stop does not cut again, even with a late tick between`() {
        val e = drivingFrom(100f, at(2, 8))
        e.onOdometer(106f, at(2, 8, 15))
        assertTrue(doorInDrive(e, 106f, at(2, 8, 15)) != null)
        assertNull(doorInDrive(e, 106f, at(2, 8, 15), door = ExitDoor.RIGHT))
        e.onOdometer(106.8f, at(2, 8, 15) + 2_000)
        assertNull(doorInDrive(e, 106.8f, at(2, 8, 15) + 3_000, door = ExitDoor.RIGHT))
    }

    @Test
    fun `a door in Park is not a stop marked in Drive`() {
        val e = LedgerEngine(Suggester(tz))
        e.onStart(100f, at(2, 8))
        e.onGear(Gear.PARK, 100f, at(2, 8), null, emptyList(), people)
        assertNull(doorInDrive(e, 100f, at(2, 8, 1)))
    }

    @Test
    fun `the empty way back after a drop-off in Drive is still that person's`() {
        val e = drivingFrom(100f, at(2, 8))
        e.onOdometer(106f, at(2, 8, 15))
        val out = doorInDrive(e, 106f, at(2, 8, 15))!!
        val dropped = out.segment.copy(id = 1, personId = 2, attribution = Attribution.SUGGESTED, door = ExitDoor.LEFT)
        e.onOdometer(112f, at(2, 8, 20))
        val back = e.onGear(Gear.PARK, 112f, at(2, 8, 20), null, listOf(dropped), people).single() as LedgerEvent.SegmentClosed
        assertTrue(back.segment.returnLeg)
        assertEquals(2L, back.segment.personId)
    }

    @Test
    fun `the way back is named for where it came from`() {
        val e = drivingFrom(100f, at(2, 8))
        e.onOdometer(106f, at(2, 8, 15))
        val out = doorInDrive(e, 106f, at(2, 8, 15), place = "대치동")!!
        val dropped = out.segment.copy(id = 1, personId = 2, attribution = Attribution.SUGGESTED, door = ExitDoor.LEFT)
        e.onOdometer(112f, at(2, 8, 20))
        val back = e.onGear(Gear.PARK, 112f, at(2, 8, 20), "역삼동", listOf(dropped), people).single() as LedgerEvent.SegmentClosed
        assertEquals("대치동", back.segment.placeName)
    }

    @Test
    fun `parking right after a stop marked in Drive starts a new stop`() {
        val e = LedgerEngine(Suggester(tz))
        val f = FakeLedger()
        e.onStart(90f, at(2, 7, 30))
        e.onGear(Gear.PARK, 90f, at(2, 7, 30), null, emptyList(), people)
        apply(e, f, e.onGear(Gear.DRIVING, 90f, at(2, 7, 40), null, emptyList(), people))
        e.onOdometer(100f, at(2, 7, 50))
        apply(e, f, e.onGear(Gear.PARK, 100f, at(2, 7, 50), null, emptyList(), people))
        apply(e, f, e.onGear(Gear.DRIVING, 100f, at(2, 8), null, emptyList(), people))
        e.onOdometer(106f, at(2, 8, 15))
        apply(e, f, listOfNotNull(doorInDrive(e, 106f, at(2, 8, 15))))
        val marked = e.state.parkedSegmentId
        apply(e, f, e.onGear(Gear.PARK, 106f, at(2, 8, 16), null, emptyList(), people))
        assertEquals("a door hint here belongs to the stretch that just ended", marked, e.state.parkedSegmentId)
        apply(e, f, e.onGear(Gear.DRIVING, 106f, at(2, 8, 20), null, emptyList(), people))
        e.onOdometer(112f, at(2, 8, 40))
        val next = e.onGear(Gear.PARK, 112f, at(2, 8, 40), null, emptyList(), people).single() as LedgerEvent.SegmentClosed
        val wait = next.wait!!
        assertEquals("only the real stop counts as waiting", at(2, 8, 16), wait.startedAt)
        assertEquals(at(2, 8, 20), wait.endedAt)
    }

    @Test
    fun `the question about the way out can settle the way back`() {
        val out = trip("대치동", at(2, 8, 15), null, Attribution.UNCLASSIFIED).copy(door = ExitDoor.LEFT)
        assertTrue(WayBack.follows(out, at(2, 8, 15), 12f))
        assertTrue(WayBack.follows(out, at(2, 8, 24), 15f))
        assertFalse("too long to be the way back", WayBack.follows(out, at(2, 8, 15), 20f))
        assertFalse("too late to be the way back", WayBack.follows(out, at(2, 8, 30), 12f))
        assertFalse("no door, no drop-off", WayBack.follows(out.copy(door = null), at(2, 8, 15), 12f))
    }

    @Test
    fun `the two legs of a round trip find each other`() {
        val out = Segment(id = 1, startKm = 0f, endKm = 6f, startedAt = at(2, 8), endedAt = at(2, 8, 15), door = ExitDoor.LEFT)
        val back = Segment(id = 2, startKm = 6f, endKm = 12f, startedAt = at(2, 8, 16), endedAt = at(2, 8, 30), returnLeg = true)
        val next = Segment(id = 3, startKm = 12f, endKm = 40f, startedAt = at(2, 9), endedAt = at(2, 9, 30))
        val all = listOf(out, back, next)
        assertEquals(out, WayBack.wayOutOf(back, all))
        assertNull("only a way back has a way out", WayBack.wayOutOf(next, all))
        assertEquals(back, WayBack.legAfter(out, all))
        assertEquals(next, WayBack.legAfter(back, all))
        assertNull(WayBack.legAfter(next, all))
    }

    @Test
    fun `a passenger's door counts like a rear door`() {
        val e = drivingFrom(100f, at(2, 8))
        e.onOdometer(106f, at(2, 8, 15))
        val out = doorInDrive(e, 106f, at(2, 8, 15), door = ExitDoor.PASSENGER)!!
        val dropped = out.segment.copy(id = 1, personId = 3, attribution = Attribution.SUGGESTED, door = ExitDoor.PASSENGER)
        e.onOdometer(112f, at(2, 8, 20))
        val back = e.onGear(Gear.PARK, 112f, at(2, 8, 20), null, listOf(dropped), people).single() as LedgerEvent.SegmentClosed
        assertTrue(back.segment.returnLeg)
        assertEquals(3L, back.segment.personId)
    }

    @Test
    fun `the seeded way back comes after the way out`() = withSeoulTime {
        val seed = SeedData.build(10_000f, at(3, 10)).segments.map { it.segment }.filter { it.placeName == "학교" }
        val pairs = seed.sortedBy { it.startKm }.zipWithNext().filter { (a, b) -> b.returnLeg && !a.returnLeg }
        assertTrue(pairs.isNotEmpty())
        pairs.forEach { (out, back) -> assertTrue(out.endedAt < back.endedAt) }
    }

    // ---- Someone else drove ----

    private val driven = Segment(startKm = 0f, endKm = 40f, startedAt = 1, endedAt = 2, attribution = Attribution.OTHER_DRIVER)

    @Test
    fun `distance someone else drove is neither family nor unclassified, and the parts still add up`() {
        val forJiwoo = Segment(startKm = 40f, endKm = 100f, startedAt = 3, endedAt = 4, personId = 2, attribution = Attribution.CONFIRMED)
        val s = Settlements.of(0f, 120f, listOf(driven, forJiwoo), people)
        assertEquals(40f, s.otherDriverKm, 0.01f)
        assertEquals(60f, s.familyKm, 0.01f)
        assertEquals(20f, s.unclassifiedKm, 0.01f)
        assertEquals(s.totalKm, s.familyKm + s.selfKm + s.otherDriverKm + s.unclassifiedKm, 0.01f)
    }

    @Test
    fun `a stretch someone else drove never teaches a pattern or a way back`() {
        val history = listOf(2, 3, 4).map { trip("학교", at(it, 8, 20), null, Attribution.OTHER_DRIVER) }
        assertNull(suggestAt("학교", at(5, 8, 30), history))
        val out = trip("학교", at(5, 8, 20), null, Attribution.OTHER_DRIVER).copy(door = ExitDoor.LEFT)
        val e = drivingFrom(100f, at(5, 8, 25))
        e.onOdometer(112f, at(5, 8, 40))
        val back = e.onGear(Gear.PARK, 112f, at(5, 8, 40), null, listOf(out), people).single() as LedgerEvent.SegmentClosed
        assertFalse(back.segment.returnLeg)
    }

    @Test
    fun `thanks come first once someone has taken over, and only then`() {
        val none = Settlement(100f, listOf(PersonTotal(jiwoo, 80f, 50f)), 0f, 20f, 0)
        assertFalse(Phrases.handOffs("지우", none).any { it.contains("고마워요") })
        val some = none.copy(unclassifiedKm = 0f, otherDriverKm = 20f)
        assertEquals("이번에 맡아 준 길 20km, 고마워요.", Phrases.handOffs("지우", some).first())
    }

    @Test
    fun `the settlement says what someone else took over`() {
        val s = Settlement(100f, listOf(PersonTotal(jiwoo, 60f, 60f)), 20f, 0f, 0, otherDriverKm = 20f)
        assertEquals(
            "한 번 채워 100킬로미터를 달렸어요. 그중 가족을 위한 길이 60킬로미터, 내 길이 20킬로미터, 다른 사람이 맡아 준 길이 20킬로미터예요.",
            Phrases.tankSpoken(s),
        )
    }

    // ---- Neighborhoods standing in for a destination ----

    @Test
    fun `only the neighborhood of an address is kept`() {
        assertEquals("역삼동", Places.neighborhoodOf("서울특별시 강남구 역삼동 123-45"))
        assertEquals("역삼1동", Places.neighborhoodOf("서울특별시 강남구 역삼1동 123"))
        assertEquals("시흥동", Places.neighborhoodOf("수정구 시흥동"))
        assertEquals("역삼동", Places.neighborhoodOf("서울특별시 강남구 테헤란로 152 (역삼동, 강남파이낸스센터)"))
        assertEquals("테헤란로", Places.neighborhoodOf("서울특별시 강남구 테헤란로 152"))
        assertEquals("양서면", Places.neighborhoodOf("경기도 양평군 양서면 용담리 12"))
        assertEquals("강동로", Places.neighborhoodOf("서울특별시 강동구 강동로 12"))
        assertEquals("종로3가", Places.neighborhoodOf("서울특별시 종로구 종로3가 12"))
        assertNull(Places.neighborhoodOf("1600 Amphitheatre Parkway, Mountain View"))
        assertNull(Places.neighborhoodOf(""))
    }

    @Test
    fun `no unit, house number or building word is taken for a neighborhood`() {
        assertEquals("테헤란로", Places.neighborhoodOf("서울특별시 강남구 테헤란로 152 101동 1203호"))
        assertEquals("판교역로", Places.neighborhoodOf("경기도 성남시 분당구 판교역로146번길 20"))
        assertEquals("논현로", Places.neighborhoodOf("서울특별시 강남구 논현로 508 상가"))
        assertNull(Places.neighborhoodOf("서울특별시 강남구 강남빌딩 후면"))
    }

    @Test
    fun `a destination that is a bare address is cut down too`() {
        assertEquals("학교", Places.storableName("학교"))
        assertEquals("스타벅스 강남2호점", Places.storableName("스타벅스 강남2호점"))
        assertEquals("테헤란로", Places.storableName("서울특별시 강남구 테헤란로 152"))
        assertNull(Places.storableName("  "))
    }

    @Test
    fun `a named place is not mistaken for a neighborhood`() {
        listOf("학교", "학원", "병원", "회사", "어린이집", "학원가", "아파트상가").forEach { assertFalse(it, Places.isNeighborhood(it)) }
        listOf("역삼동", "양서면", "종로3가", "테헤란로").forEach { assertTrue(it, Places.isNeighborhood(it)) }
    }

    private fun trip(place: String?, whenAt: Long, personId: Long? = 2, kind: Attribution = Attribution.CONFIRMED) = Segment(
        startKm = 0f, endKm = 12f, startedAt = whenAt - 20 * 60_000, endedAt = whenAt, observed = true,
        personId = personId, attribution = kind, placeName = place,
    )

    private fun suggestAt(place: String?, whenAt: Long, history: List<Segment>, door: ExitDoor? = null) =
        Suggester(tz).suggest(StopContext(whenAt, place, door, passengerVacant = false, watchedWholeTrip = false), history, people)?.personId

    @Test
    fun `the same neighborhood at another time of day says nothing`() {
        val mornings = listOf(trip("역삼동", at(2, 8, 20)), trip("역삼동", at(3, 8, 20)), trip("역삼동", at(4, 8, 20)))
        assertEquals(2L, suggestAt("역삼동", at(5, 8, 30), mornings))
        assertNull(suggestAt("역삼동", at(5, 18, 30), mornings))
    }

    @Test
    fun `a different neighborhood rules a pattern out`() {
        val mornings = listOf(trip("역삼동", at(2, 8, 20)), trip("역삼동", at(3, 8, 20)), trip("역삼동", at(4, 8, 20)))
        assertNull(suggestAt("대치동", at(5, 8, 30), mornings))
    }

    @Test
    fun `a neighborhood today follows a named place only with the same rear door`() {
        val mornings = listOf(2, 3, 4).map { trip("학교", at(it, 8, 20)).copy(door = ExitDoor.LEFT) }
        assertEquals(2L, suggestAt("역삼동", at(5, 8, 30), mornings, ExitDoor.LEFT))
        assertNull("time alone cannot bridge two kinds of name", suggestAt("역삼동", at(5, 8, 30), mornings))
        assertNull(suggestAt("역삼동", at(5, 8, 30), mornings, ExitDoor.RIGHT))
    }

    @Test
    fun `mornings in a neighborhood do not use up the questions for its evenings`() {
        val e = drivingFrom(100f, at(5, 18))
        val mornings = listOf(2, 3, 4).map { trip("역삼동", at(it, 8, 20), null, Attribution.UNCLASSIFIED) }
        e.onOdometer(112f, at(5, 18, 20))
        val evening = e.onGear(Gear.PARK, 112f, at(5, 18, 20), "역삼동", mornings, people).single() as LedgerEvent.SegmentClosed
        assertTrue(evening.shouldAsk)
    }

    @Test
    fun `naming someone for one leg settles the other, short of what was confirmed`() {
        val out = Segment(id = 1, startKm = 100f, endKm = 106f, startedAt = at(2, 8), endedAt = at(2, 8, 15), personId = 2, attribution = Attribution.SUGGESTED, door = ExitDoor.LEFT)
        val back = Segment(id = 2, startKm = 106f, endKm = 112f, startedAt = at(2, 8, 16), endedAt = at(2, 8, 30), personId = 2, attribution = Attribution.SUGGESTED, returnLeg = true)
        val all = listOf(out, back)
        assertEquals(out, WayBack.otherLegSettled(back, all))
        assertEquals(back, WayBack.otherLegSettled(out, all))
        assertEquals(out, WayBack.otherLegOtherDriver(back, all))
        val confirmed = out.copy(attribution = Attribution.CONFIRMED)
        assertNull(WayBack.otherLegSettled(back, listOf(confirmed, back)))
        assertNull(WayBack.otherLegOtherDriver(back, listOf(confirmed, back)))
        assertEquals("leaving the way out unclassified takes its inferred way back along", back, WayBack.otherLegLeft(out, all))
        assertNull(WayBack.otherLegLeft(back, all))
    }
}
