package dev.chagok.app.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class LedgerEngineTest {

    private val tz = TimeZone.getTimeZone("Asia/Seoul")
    private val me = Person(1, "나", isSelf = true)
    private val jiwoo = Person(2, "지우")
    private val mom = Person(3, "어머니")
    private val people = listOf(me, jiwoo, mom)

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(tz).apply {
            clear()
            // 2026-11-02 is a Monday.
            set(2026, Calendar.NOVEMBER, day, hour, minute)
        }.timeInMillis

    private fun confirmed(km: Float, personId: Long, place: String?, whenAt: Long, door: ExitDoor? = null) = Segment(
        startKm = 0f,
        endKm = km,
        startedAt = whenAt - 600_000,
        endedAt = whenAt,
        personId = personId,
        attribution = Attribution.CONFIRMED,
        placeName = place,
        door = door,
    )

    private fun engine() = LedgerEngine(Suggester(tz))

    private fun drive(
        e: LedgerEngine,
        fromKm: Float,
        toKm: Float,
        start: Long,
        end: Long,
        place: String? = null,
        history: List<Segment> = emptyList(),
    ): List<LedgerEvent> {
        e.onGear(Gear.PARK, fromKm, start - 1, null, history, people)
        e.onGear(Gear.DRIVING, fromKm, start, null, history, people)
        return e.onGear(Gear.PARK, toKm, end, place, history, people)
    }

    // ---- Distance is never lost ----

    @Test
    fun `odometer difference while the app was off becomes an unclassified stretch`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 1000f, lastSeenAt = at(2, 8)))
        val events = e.onStart(1086f, at(4, 9))
        val gap = events.single() as LedgerEvent.GapFound
        assertEquals(86f, gap.segment.km, 0.01f)
        assertEquals(false, gap.segment.observed)
        assertEquals(Attribution.UNCLASSIFIED, gap.segment.attribution)
        assertNull(gap.segment.personId)
    }

    @Test
    fun `a first ever start finds no gap`() {
        assertTrue(engine().onStart(5000f, at(2, 8)).isEmpty())
    }

    @Test
    fun `a trip left open when the process died is covered from its start`() {
        // Ticks kept moving lastKm during the trip, so the stretch must start where the trip did.
        val e = LedgerEngine(
            Suggester(tz),
            EngineState(lastKm = 120f, lastSeenAt = at(2, 8), tripStartKm = 100f, tripStartAt = at(2, 7)),
        )
        val gap = e.onStart(130f, at(2, 9)).single() as LedgerEvent.GapFound
        assertEquals(100f, gap.segment.startKm, 0.01f)
        assertEquals(30f, gap.segment.km, 0.01f)
        assertNull(e.state.tripStartKm)
    }

    @Test
    fun `a restart in the middle of a trip continues it`() {
        val e = LedgerEngine(
            Suggester(tz),
            EngineState(lastKm = 120f, lastSeenAt = at(2, 8), tripStartKm = 100f, tripStartAt = at(2, 7)),
        )
        assertTrue(e.onStart(120.2f, at(2, 8, 1)).isEmpty())
        e.onGear(Gear.DRIVING, 120.2f, at(2, 8, 1), null, emptyList(), people)
        val closed = e.onGear(Gear.PARK, 130f, at(2, 9), "회사", emptyList(), people).single() as LedgerEvent.SegmentClosed
        assertEquals(100f, closed.segment.startKm, 0.01f)
        assertNull(closed.segment.personId)
    }

    @Test
    fun `a lower reading at start keeps the known odometer`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 200f, lastSeenAt = at(2, 8)))
        assertTrue(e.onStart(122f, at(2, 9)).isEmpty())
        assertEquals(200f, e.state.lastKm!!, 0.01f)
        val gap = e.onStart(210f, at(2, 10)).single() as LedgerEvent.GapFound
        assertEquals(200f, gap.segment.startKm, 0.01f)
    }

    @Test
    fun `an old odometer value replayed after start is ignored`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 200f, lastSeenAt = at(2, 8)))
        assertTrue(e.onOdometer(122f, at(2, 9)).isEmpty())
        assertEquals(200f, e.state.lastKm!!, 0.01f)
    }

    @Test
    fun `a jump while parked is distance the app did not see`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 100f, lastSeenAt = at(2, 8)))
        e.onGear(Gear.PARK, 100f, at(2, 8), null, emptyList(), people)
        val gap = e.onOdometer(186f, at(2, 9)).single() as LedgerEvent.GapFound
        assertEquals(86f, gap.segment.km, 0.01f)
        assertEquals(186f, e.state.lastKm!!, 0.01f)
    }

    @Test
    fun `odometer ticks during a trip are not gaps`() {
        val e = engine()
        e.onStart(100f, at(2, 8))
        e.onGear(Gear.PARK, 100f, at(2, 8), null, emptyList(), people)
        e.onGear(Gear.DRIVING, 100f, at(2, 8, 1), null, emptyList(), people)
        assertTrue(e.onOdometer(150f, at(2, 9)).isEmpty())
    }

    @Test
    fun `one odometer tick arriving before the gear event is not a gap`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 100f, lastSeenAt = at(2, 8)))
        e.onGear(Gear.PARK, 100f, at(2, 8), null, emptyList(), people)
        assertTrue(e.onOdometer(101f, at(2, 8, 1)).isEmpty())
    }

    // ---- Gear and odometer arrive in any order ----

    @Test
    fun `odometer ticks arriving just after park extend the closed stretch`() {
        val e = engine()
        e.onStart(0f, at(2, 8))
        drive(e, 0f, 11f, at(2, 8), at(2, 8, 20), "어린이집")
        e.onSegmentSaved(42)
        val extended = e.onOdometer(12f, at(2, 8, 20) + 2_000).single() as LedgerEvent.SegmentExtended
        assertEquals(42L, extended.segmentId)
        assertEquals(12f, extended.endKm, 0.01f)
    }

    @Test
    fun `park arriving before the whole trip's ticks still closes one stretch`() {
        val e = engine()
        e.onStart(100f, at(2, 8))
        val history = listOf(
            confirmed(12f, 2, "어린이집", at(2, 8, 20)),
            confirmed(12f, 2, "어린이집", at(3, 8, 20)),
            confirmed(12f, 2, "어린이집", at(4, 8, 20)),
        )
        val none = drive(e, 100f, 100f, at(5, 8), at(5, 8, 20), "어린이집", history)
        assertTrue(none.isEmpty())
        assertTrue(e.awaitingLateTrip)
        val closed = e.onOdometer(112f, at(5, 8, 20) + 2_000, history, people).single() as LedgerEvent.SegmentClosed
        assertEquals(12f, closed.segment.km, 0.01f)
        assertEquals(2L, closed.segment.personId)
    }

    @Test
    fun `a jump right before drive is the trip starting, not a gap`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 100f, lastSeenAt = at(2, 8)))
        e.onGear(Gear.PARK, 100f, at(2, 8), null, emptyList(), people)
        val jump = e.onOdometer(112f, at(2, 9)).single() as LedgerEvent.GapFound
        e.onGapSaved(jump.segment, 9)
        val events = e.onGear(Gear.DRIVING, 112f, at(2, 9) + 2_000, null, emptyList(), people)
        assertEquals(9L, (events.single() as LedgerEvent.GapRetracted).segmentId)
        val closed = e.onGear(Gear.PARK, 112f, at(2, 9, 5), "어린이집", emptyList(), people).single() as LedgerEvent.SegmentClosed
        assertEquals(100f, closed.segment.startKm, 0.01f)
    }

    @Test
    fun `a gap stays a gap when drive comes much later`() {
        val e = LedgerEngine(Suggester(tz), EngineState(lastKm = 100f, lastSeenAt = at(2, 8)))
        e.onGear(Gear.PARK, 100f, at(2, 8), null, emptyList(), people)
        val jump = e.onOdometer(112f, at(2, 9)).single() as LedgerEvent.GapFound
        e.onGapSaved(jump.segment, 9)
        assertTrue(e.onGear(Gear.DRIVING, 112f, at(2, 9, 30), null, emptyList(), people).isEmpty())
        assertEquals(112f, e.state.tripStartKm!!, 0.01f)
    }

    @Test
    fun `with recording off stretches are cut but stay unclassified and unasked`() {
        val e = engine()
        e.onPassengerSeat(true)
        e.onGear(Gear.PARK, 0f, at(2, 8), null, emptyList(), people, recording = false)
        e.onGear(Gear.DRIVING, 0f, at(2, 8, 1), null, emptyList(), people, recording = false)
        val closed = e.onGear(Gear.PARK, 30f, at(2, 9), "회사", emptyList(), people, recording = false).single() as LedgerEvent.SegmentClosed
        assertNull(closed.segment.personId)
        assertEquals(false, closed.segment.observed)
        assertEquals(false, closed.shouldAsk)
    }

    @Test
    fun `settlement parts always add up to the odometer total`() {
        val segments = listOf(
            Segment(startKm = 0f, endKm = 284f, startedAt = 0, endedAt = 1, personId = 2, attribution = Attribution.SUGGESTED),
            Segment(startKm = 284f, endKm = 380f, startedAt = 2, endedAt = 3, personId = 3, attribution = Attribution.CONFIRMED),
            Segment(startKm = 380f, endKm = 570f, startedAt = 4, endedAt = 5, personId = 1, attribution = Attribution.CONFIRMED),
            Segment(startKm = 570f, endKm = 612f, startedAt = 6, endedAt = 7, observed = false),
        )
        val s = Settlements.of(0f, 612f, segments, people)
        assertEquals(612f, s.totalKm, 0.01f)
        assertEquals(380f, s.familyKm, 0.01f)
        assertEquals(190f, s.selfKm, 0.01f)
        assertEquals(42f, s.unclassifiedKm, 0.01f)
        assertEquals(s.totalKm, s.familyKm + s.selfKm + s.unclassifiedKm, 0.01f)
    }

    @Test
    fun `distance with no segment at all counts as unclassified`() {
        val s = Settlements.of(0f, 100f, emptyList(), people)
        assertEquals(100f, s.unclassifiedKm, 0.01f)
    }

    @Test
    fun `settlement counts only the part of a segment inside the range`() {
        val seg = Segment(startKm = 90f, endKm = 110f, startedAt = 0, endedAt = 1, personId = 2, attribution = Attribution.CONFIRMED)
        val s = Settlements.of(100f, 200f, listOf(seg), people)
        assertEquals(10f, s.familyKm, 0.01f)
    }

    // ---- Names only from what the driver confirmed ----

    @Test
    fun `milestones count confirmed distance only`() {
        val segments = listOf(
            Segment(startKm = 0f, endKm = 100f, startedAt = 0, endedAt = 1, personId = 2, attribution = Attribution.CONFIRMED),
            Segment(startKm = 100f, endKm = 300f, startedAt = 2, endedAt = 3, personId = 2, attribution = Attribution.SUGGESTED),
        )
        assertEquals(100f, Settlements.confirmedKm(2, segments), 0.01f)
    }

    @Test
    fun `no history means no name and a question`() {
        val e = engine()
        val closed = drive(e, 0f, 12f, at(2, 8), at(2, 8, 20), "어린이집").single() as LedgerEvent.SegmentClosed
        assertNull(closed.segment.personId)
        assertEquals(Attribution.UNCLASSIFIED, closed.segment.attribution)
        assertTrue(closed.shouldAsk)
    }

    @Test
    fun `repeat confirmations at the same place and time produce a suggestion without asking`() {
        val history = listOf(
            confirmed(12f, 2, "어린이집", at(2, 8, 20)),
            confirmed(12f, 2, "어린이집", at(3, 8, 25)),
            confirmed(12f, 2, "어린이집", at(4, 8, 15)),
        )
        val closed = drive(engine(), 0f, 12f, at(5, 8), at(5, 8, 20), "어린이집", history).single() as LedgerEvent.SegmentClosed
        assertEquals(2L, closed.segment.personId)
        assertEquals(Attribution.SUGGESTED, closed.segment.attribution)
        assertEquals(false, closed.shouldAsk)
    }

    @Test
    fun `a different place never inherits a name from time alone`() {
        val history = listOf(
            confirmed(12f, 2, "어린이집", at(2, 8, 20)),
            confirmed(12f, 2, "어린이집", at(3, 8, 20)),
            confirmed(12f, 2, "어린이집", at(4, 8, 20)),
        )
        val closed = drive(engine(), 0f, 30f, at(5, 8), at(5, 8, 20), "회사", history).single() as LedgerEvent.SegmentClosed
        assertNull(closed.segment.personId)
    }

    @Test
    fun `two people at one place stay undecided`() {
        val history = listOf(
            confirmed(12f, 2, "병원", at(2, 10)),
            confirmed(12f, 3, "병원", at(3, 10)),
            confirmed(12f, 2, "병원", at(4, 10)),
            confirmed(12f, 3, "병원", at(5, 10)),
        )
        val closed = drive(engine(), 0f, 12f, at(9, 10), at(9, 10, 20), "병원", history).single() as LedgerEvent.SegmentClosed
        assertNull(closed.segment.personId)
    }

    @Test
    fun `an empty passenger seat alone does not name anyone but the driver`() {
        val e = engine()
        e.onPassengerSeat(true)
        val closed = drive(e, 0f, 30f, at(2, 8), at(2, 8, 40), "회사").single() as LedgerEvent.SegmentClosed
        assertEquals(me.id, closed.segment.personId)
        assertEquals(Attribution.SUGGESTED, closed.segment.attribution)
    }

    @Test
    fun `an unknown passenger seat proves nothing`() {
        val e = engine()
        e.onPassengerSeat(false)
        val closed = drive(e, 0f, 30f, at(2, 8), at(2, 8, 40), "회사").single() as LedgerEvent.SegmentClosed
        assertNull(closed.segment.personId)
    }

    @Test
    fun `starting already in drive is not a watched trip`() {
        val e = engine()
        e.onPassengerSeat(true)
        e.onGear(Gear.DRIVING, 0f, at(2, 8), null, emptyList(), people)
        val closed = e.onGear(Gear.PARK, 30f, at(2, 9), "회사", emptyList(), people).single() as LedgerEvent.SegmentClosed
        assertNull(closed.segment.personId)
    }

    // ---- Round trips, waiting, doors ----

    @Test
    fun `driving back right after a drop-off stays with the same person`() {
        val dropOff = Segment(
            id = 7, startKm = 0f, endKm = 12f, startedAt = at(2, 8), endedAt = at(2, 8, 20),
            personId = 2, attribution = Attribution.SUGGESTED, placeName = "어린이집", door = ExitDoor.LEFT,
        )
        val e = engine()
        e.onGear(Gear.PARK, 12f, at(2, 8, 20), "어린이집", listOf(dropOff), people)
        e.onGear(Gear.DRIVING, 12f, at(2, 8, 25), null, listOf(dropOff), people)
        val back = e.onGear(Gear.PARK, 24f, at(2, 8, 45), "집", listOf(dropOff), people).single() as LedgerEvent.SegmentClosed
        assertEquals(2L, back.segment.personId)
        assertTrue(back.segment.returnLeg)
    }

    @Test
    fun `driving on to work after a drop-off is not a return leg`() {
        val dropOff = Segment(
            id = 7, startKm = 0f, endKm = 12f, startedAt = at(2, 8), endedAt = at(2, 8, 20),
            personId = 2, attribution = Attribution.SUGGESTED, placeName = "어린이집", door = ExitDoor.LEFT,
        )
        val e = engine()
        e.onGear(Gear.PARK, 12f, at(2, 8, 20), "어린이집", listOf(dropOff), people)
        e.onGear(Gear.DRIVING, 12f, at(2, 8, 25), null, listOf(dropOff), people)
        val onward = e.onGear(Gear.PARK, 50f, at(2, 9, 5), "회사", listOf(dropOff), people).single() as LedgerEvent.SegmentClosed
        assertEquals(false, onward.segment.returnLeg)
        assertNull(onward.segment.personId)
    }

    @Test
    fun `a rear door opened before leaving means the trip is not proposed as the driver's own`() {
        val e = engine()
        e.onPassengerSeat(true)
        e.onGear(Gear.PARK, 0f, at(2, 8), null, emptyList(), people)
        e.onDoorOpened(ExitDoor.LEFT, at(2, 8))
        e.onGear(Gear.DRIVING, 0f, at(2, 8, 1), null, emptyList(), people)
        val closed = e.onGear(Gear.PARK, 12f, at(2, 8, 20), "어린이집", emptyList(), people).single() as LedgerEvent.SegmentClosed
        assertNull(closed.segment.personId)
        assertTrue(closed.shouldAsk)
    }

    @Test
    fun `driving on much later is not a return leg`() {
        val dropOff = Segment(
            id = 7, startKm = 0f, endKm = 12f, startedAt = at(2, 8), endedAt = at(2, 8, 20),
            personId = 2, attribution = Attribution.SUGGESTED, placeName = "어린이집", door = ExitDoor.LEFT,
        )
        val e = engine()
        e.onGear(Gear.PARK, 12f, at(2, 8, 20), "어린이집", listOf(dropOff), people)
        e.onGear(Gear.DRIVING, 12f, at(2, 18), null, listOf(dropOff), people)
        val later = e.onGear(Gear.PARK, 24f, at(2, 18, 30), "집", listOf(dropOff), people).single() as LedgerEvent.SegmentClosed
        assertEquals(false, later.segment.returnLeg)
    }

    @Test
    fun `a rear door opened after parking marks the closed stretch`() {
        val e = engine()
        drive(e, 0f, 12f, at(2, 8), at(2, 8, 20), "어린이집")
        e.onSegmentSaved(42)
        val hint = e.onDoorOpened(ExitDoor.LEFT, at(2, 8, 21)) as LedgerEvent.DropOffSeen
        assertEquals(42L, hint.segmentId)
        assertEquals(ExitDoor.LEFT, hint.door)
    }

    @Test
    fun `a rear door while driving is ignored`() {
        val e = engine()
        e.onGear(Gear.DRIVING, 0f, at(2, 8), null, emptyList(), people)
        assertNull(e.onDoorOpened(ExitDoor.LEFT, at(2, 8, 1)))
    }

    @Test
    fun `a wait travels with the trip that follows it`() {
        val e = engine()
        e.onGear(Gear.PARK, 0f, at(2, 17), "학원", emptyList(), people)
        assertTrue(e.onGear(Gear.DRIVING, 0f, at(2, 17, 40), null, emptyList(), people).isEmpty())
        val closed = e.onGear(Gear.PARK, 9f, at(2, 18), "집", emptyList(), people).single() as LedgerEvent.SegmentClosed
        assertEquals(40L, closed.wait!!.minutes)
        assertEquals("학원", closed.wait!!.placeName)
    }

    @Test
    fun `a short stop is not waiting`() {
        val e = engine()
        e.onGear(Gear.PARK, 0f, at(2, 17), "학원", emptyList(), people)
        e.onGear(Gear.DRIVING, 0f, at(2, 17, 2), null, emptyList(), people)
        val closed = e.onGear(Gear.PARK, 9f, at(2, 18), "집", emptyList(), people).single() as LedgerEvent.SegmentClosed
        assertNull(closed.wait)
    }

    @Test
    fun `a night parked is not waiting`() {
        val e = engine()
        e.onGear(Gear.PARK, 0f, at(2, 20), "집", emptyList(), people)
        e.onGear(Gear.DRIVING, 0f, at(3, 8), null, emptyList(), people)
        val closed = e.onGear(Gear.PARK, 9f, at(3, 8, 30), "회사", emptyList(), people).single() as LedgerEvent.SegmentClosed
        assertNull(closed.wait)
    }

    @Test
    fun `only a wait before a family trip counts as time for family`() {
        val forJiwoo = Segment(id = 1, startKm = 0f, endKm = 9f, startedAt = at(2, 17, 40), endedAt = at(2, 18), personId = 2, attribution = Attribution.CONFIRMED)
        val forMe = Segment(id = 2, startKm = 9f, endKm = 30f, startedAt = at(3, 8, 5), endedAt = at(3, 9), personId = 1, attribution = Attribution.CONFIRMED)
        val waits = listOf(
            WaitEntry(at(2, 17), at(2, 17, 40), "학원", 1),
            WaitEntry(at(3, 8), at(3, 8, 5), "집", 2),
        )
        val s = Settlements.of(0f, 30f, listOf(forJiwoo, forMe), people, waits)
        assertEquals(40L, s.waitMinutes)
        val week = Settlements.familyTime(at(2, 0), at(4, 0), listOf(forJiwoo, forMe), people, waits)
        assertEquals(60L, week.minutes)
    }

    // ---- Tanks ----

    @Test
    fun `a real refill closes the tank`() {
        val e = engine()
        e.onEnergy(40, 0f, at(2, 8))
        e.onEnergy(20, 500f, at(5, 8))
        val events = e.onEnergy(90, 612f, at(9, 8))
        val tank = (events.single() as LedgerEvent.TankClosed).tank
        assertEquals(0f, tank.startKm, 0.01f)
        assertEquals(612f, tank.endKm!!, 0.01f)
        assertEquals(612f, e.state.tankStartKm!!, 0.01f)
    }

    @Test
    fun `a small top-up merges into the running tank`() {
        val e = engine()
        e.onEnergy(40, 0f, at(2, 8))
        assertTrue(e.onEnergy(50, 100f, at(3, 8)).isEmpty())
        assertEquals(0f, e.state.tankStartKm!!, 0.01f)
    }

    @Test
    fun `falling energy never closes a tank`() {
        val e = engine()
        e.onEnergy(80, 0f, at(2, 8))
        assertTrue(e.onEnergy(40, 300f, at(4, 8)).isEmpty())
    }

    @Test
    fun `a slow charge closes the tank when the car moves off`() {
        val e = engine()
        e.onGear(Gear.PARK, 0f, at(2, 8), null, emptyList(), people)
        e.onEnergy(80, 0f, at(2, 8))
        e.onEnergy(40, 300f, at(4, 8))
        for (level in 41..60) assertTrue(e.onEnergy(level, 300f, at(4, 9)).isEmpty())
        assertTrue(e.state.refillPending)
        val events = e.onGear(Gear.DRIVING, 300f, at(4, 10), null, emptyList(), people)
        val tank = (events.single() as LedgerEvent.TankClosed).tank
        assertEquals(300f, tank.endKm!!, 0.01f)
        assertEquals(false, e.state.refillPending)
        assertEquals(60, e.state.tankMinPercent)
    }

    // ---- Words ----

    @Test
    fun `particles follow the name the family typed`() {
        assertEquals("지우를 위한 길로 적어 뒀어요.", Phrases.recorded("지우", false))
        assertEquals("민준을 위한 길로 적어 뒀어요.", Phrases.recorded("민준", false))
        assertEquals("오늘이 민준을 위한 첫 기록이에요.", Phrases.firstRecord("민준"))
        assertTrue(Phrases.daySpoken("민준", "입학한 날", 100f).startsWith("민준아, "))
        assertTrue(Phrases.daySpoken("지우", "입학한 날", 100f).startsWith("지우야, "))
    }

    @Test
    fun `a sentence with a name carries only confirmed distance`() {
        val s = Settlement(100f, listOf(PersonTotal(jiwoo, 80f, 50f)), 0f, 20f, 0)
        assertTrue(Phrases.handOffs("지우", s).first().contains("50km"))
    }

    // ---- Gap guesses ----

    @Test
    fun `a gap is explained by the driver's usual round trip`() {
        val history = listOf(
            confirmed(9f, 2, "학원", at(2, 17)),
            confirmed(9f, 2, "학원", at(3, 17)),
            confirmed(9f, 2, "학원", at(4, 17)),
        )
        val guess = GapGuesser.guess(86f, history, people)
        assertNotNull(guess)
        assertEquals("학원", guess!!.placeName)
        assertEquals(18f, guess.roundTripKm, 0.01f)
        assertEquals(4, guess.times)
    }

    @Test
    fun `a route seen once is too thin to guess from`() {
        assertNull(GapGuesser.guess(86f, listOf(confirmed(9f, 2, "학원", at(2, 17))), people))
    }

    @Test
    fun `the driver's own commute never explains a family gap`() {
        val history = listOf(
            confirmed(20f, 1, "회사", at(2, 9)),
            confirmed(20f, 1, "회사", at(3, 9)),
        )
        assertNull(GapGuesser.guess(86f, history, people))
    }
}
