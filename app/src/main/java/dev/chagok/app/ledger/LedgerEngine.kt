package dev.chagok.app.ledger

enum class Gear { PARK, DRIVING }

/** Where the engine keeps what it needs to survive process death and ignition off. */
data class EngineState(
    val lastKm: Float? = null,
    val lastSeenAt: Long = 0,
    /** Open stretch: odometer and time when the car left Park. */
    val tripStartKm: Float? = null,
    val tripStartAt: Long = 0,
    /** Stretch closed at the last Park, kept open for a door hint and late odometer ticks. */
    val parkedSegmentId: Long? = null,
    val parkedAt: Long? = null,
    /** Fuel or battery level at the last reading, to notice refills. */
    val lastEnergyPercent: Int? = null,
    val tankStartKm: Float? = null,
    val tankStartAt: Long = 0,
    /** Energy level when the current tank started, so small top-ups merge into it. */
    val tankStartPercent: Int? = null,
    /** Lowest level seen in this tank: a slow charge is a refill once it rises far enough above it. */
    val tankMinPercent: Int? = null,
    /** A slow charge crossed the refill threshold; the tank closes when the car moves off. */
    val refillPending: Boolean = false,
)

sealed interface LedgerEvent {
    /** A stretch was closed and needs saving, with the engine-on wait that came before it. */
    data class SegmentClosed(val segment: Segment, val shouldAsk: Boolean, val wait: WaitEntry? = null) : LedgerEvent

    /** Odometer ticks arrived after Park: the just-closed stretch ends further on. */
    data class SegmentExtended(val segmentId: Long, val endKm: Float) : LedgerEvent

    /** Distance the app did not see. */
    data class GapFound(val segment: Segment) : LedgerEvent

    /** A jump that looked like a gap was the start of a trip after all. */
    data class GapRetracted(val segmentId: Long) : LedgerEvent

    /** A door opened after Park: the just-closed stretch can use this hint. */
    data class DropOffSeen(val segmentId: Long, val door: ExitDoor) : LedgerEvent

    /** A tank or charge ended. */
    data class TankClosed(val tank: Tank) : LedgerEvent
}

/**
 * The car-side logic, free of Android so it can be tested without a vehicle.
 *
 * Total distance never depends on the app being alive: on every start the engine compares the
 * odometer with the last value it saw and turns the difference into an unobserved stretch.
 * Attribution does depend on it, which is why unobserved stretches stay unclassified.
 *
 * Gear and odometer come from different services, so their order is not guaranteed. A few seconds
 * of tolerance on both sides keeps one trip from turning into a trip plus a gap.
 */
class LedgerEngine(
    private val suggester: Suggester = Suggester(),
    initial: EngineState = EngineState(),
) {
    var state: EngineState = initial
        private set

    private var gear: Gear? = null
    private var watchedWholeTrip = false
    private var passengerVacant = false
    /** First door opened during the current stop. */
    private var doorThisStop: ExitDoor? = null
    /** Door opened before the trip that is now running: someone likely got in. */
    private var boardingDoor: ExitDoor? = null
    private var waitStartedAt: Long? = null
    private var waitPlace: String? = null
    /** Wait that ended when the current trip started, attached to that trip when it closes. */
    private var pendingWait: WaitEntry? = null
    /** A trip too short to save at Park, kept briefly in case its odometer ticks arrive late. */
    private var shortTrip: ShortTrip? = null
    /** Jumps seen while parked, which a Drive right after turns into the start of that trip. */
    private val liveGaps = mutableListOf<LiveGap>()
    private var stopBeforeTrip: Stop? = null
    /** When the app last saw a trip that was open as the process died. */
    private var interruptedAt: Long? = null
    private var lastStopMarkedAt = 0L

    private data class ShortTrip(
        val startKm: Float,
        val startAt: Long,
        val placeName: String?,
        val parkedAt: Long,
        val wait: WaitEntry?,
        val boardingDoor: ExitDoor?,
        val watched: Boolean,
    )
    private data class LiveGap(val segment: Segment, val seenAt: Long, var id: Long? = null)

    /** The stop before a trip, put back if that "trip" turns out to be a parking maneuver. */
    private data class Stop(
        val segmentId: Long?,
        val parkedAt: Long?,
        val waitStartedAt: Long?,
        val waitPlace: String?,
        val door: ExitDoor?,
    )

    /** First reading after the app starts. Recovers whatever happened while it was not running. */
    fun onStart(km: Float, now: Long): List<LedgerEvent> {
        val last = state.lastKm
        // An odometer never goes back. A lower value is a stale replay; keep the known reading.
        if (last != null && km < last) return emptyList()
        val events = mutableListOf<LedgerEvent>()
        if (state.tripStartKm != null) interruptedAt = state.lastSeenAt
        if (last == null) {
            state = state.copy(tankStartKm = state.tankStartKm ?: km, tankStartAt = now)
        } else if (km - last >= MIN_GAP_KM) {
            // A trip left open when the process died started before `last`: cover it whole.
            val from = state.tripStartKm ?: last
            events += LedgerEvent.GapFound(Segment(startKm = from, endKm = km, startedAt = state.lastSeenAt, endedAt = now, observed = false))
            state = state.copy(tripStartKm = null)
            interruptedAt = null
        }
        state = state.copy(lastKm = km, lastSeenAt = now)
        return events
    }

    /**
     * Readings that go backwards are stale replays and ignored. Ticks just after Park belong to the
     * trip that ended. A jump while parked is distance the app did not see, unless Drive follows
     * within a few seconds, in which case it was the start of that trip.
     */
    fun onOdometer(
        km: Float,
        now: Long,
        history: List<Segment> = emptyList(),
        people: List<Person> = emptyList(),
        recording: Boolean = true,
    ): List<LedgerEvent> {
        val last = state.lastKm
        if (last != null && km < last) return emptyList()
        val events = mutableListOf<LedgerEvent>()
        val parkedAt = state.parkedAt
        val parkedId = state.parkedSegmentId
        shortTrip = shortTrip?.takeIf { now - it.parkedAt <= LATE_TICK_MS }
        val short = shortTrip
        when {
            last == null -> Unit
            km <= last -> Unit
            short != null -> {
                if (km - short.startKm >= MIN_SEGMENT_KM) {
                    shortTrip = null
                    state = state.copy(parkedSegmentId = null)
                    events += segmentClosed(
                        short.startKm, short.startAt, km, now, short.placeName, history, people, recording,
                        short.wait, short.boardingDoor, short.watched,
                    )
                }
            }
            gear == Gear.PARK && parkedAt != null && parkedId != null && now - parkedAt <= LATE_TICK_MS ->
                events += LedgerEvent.SegmentExtended(parkedId, km)
            state.tripStartKm == null && gear != Gear.DRIVING && km - last >= MIN_LIVE_GAP_KM -> {
                val segment = Segment(startKm = last, endKm = km, startedAt = state.lastSeenAt, endedAt = now, observed = false)
                liveGaps += LiveGap(segment, now)
                events += LedgerEvent.GapFound(segment)
            }
        }
        state = state.copy(lastKm = km, lastSeenAt = now)
        return events
    }

    /** True while a late tick may close a trip, which needs the history to suggest a person. */
    val awaitingLateTrip: Boolean get() = shortTrip != null

    /** Starts over from [km], for when the ledger itself was replaced. */
    fun rebase(km: Float, tankStartKm: Float, tankStartAt: Long) {
        state = state.copy(
            lastKm = km,
            tripStartKm = null,
            parkedSegmentId = null,
            parkedAt = null,
            tankStartKm = tankStartKm,
            tankStartAt = tankStartAt,
            tankMinPercent = state.lastEnergyPercent,
            refillPending = false,
        )
        shortTrip = null
        liveGaps.clear()
        stopBeforeTrip = null
        interruptedAt = null
        waitStartedAt = null
        pendingWait = null
        boardingDoor = null
        doorThisStop = null
        lastStopMarkedAt = 0L
    }

    /** The store assigned an id to a live gap, so a Drive right after can take it back. */
    fun onGapSaved(segment: Segment, id: Long) {
        liveGaps.lastOrNull { it.segment == segment }?.id = id
    }

    /** The driver explained the jump, so a Drive right after must not take it back. */
    fun forgetLiveGaps() = liveGaps.clear()

    fun onPassengerSeat(vacant: Boolean) {
        passengerVacant = vacant
    }

    fun onDoorOpened(door: ExitDoor, now: Long): LedgerEvent? {
        // Only a door opened while parked says anything about this stop.
        if (gear != Gear.PARK) return null
        if (doorThisStop == null) doorThisStop = door
        val id = state.parkedSegmentId ?: return null
        // Hours later it is someone getting in for the next trip, not a drop-off from the last one.
        val parkedAt = state.parkedAt ?: return null
        if (now - parkedAt > DOOR_HINT_MS) return null
        return LedgerEvent.DropOffSeen(id, door)
    }

    val inDrive: Boolean get() = gear == Gear.DRIVING

    /**
     * Most drop-offs happen in Drive with the brake held, and a passenger's door does not open on a moving
     * car, so it marks a stop. Barely any distance yet means someone getting in for this trip, and a
     * door right after a stop was marked is the other door of that same stop.
     */
    fun doorMarksStop(door: ExitDoor, km: Float, now: Long): Boolean {
        if (gear != Gear.DRIVING) return false
        val startKm = state.tripStartKm ?: return false
        if (now - lastStopMarkedAt <= LATE_TICK_MS) return false
        if (maxOf(km, state.lastKm ?: km) - startKm < MIN_STOP_KM) {
            boardingDoor = boardingDoor ?: door
            return false
        }
        return true
    }

    /** Ends the stretch at a stop [doorMarksStop] accepted; the next one starts from here. */
    fun closeAtDoor(
        door: ExitDoor,
        km: Float,
        now: Long,
        placeName: String?,
        history: List<Segment>,
        people: List<Person>,
    ): LedgerEvent.SegmentClosed {
        val current = maxOf(km, state.lastKm ?: km)
        val closed = segmentClosed(
            state.tripStartKm ?: current, state.tripStartAt, current, now, placeName, history, people, true,
            pendingWait, boardingDoor, watchedWholeTrip,
        ) as LedgerEvent.SegmentClosed
        pendingWait = null
        boardingDoor = door
        watchedWholeTrip = true
        // The stop before this stretch is over: a Park here starts a new stop, not a parking maneuver.
        stopBeforeTrip = null
        lastStopMarkedAt = now
        state = state.copy(lastKm = current, lastSeenAt = now, tripStartKm = current, tripStartAt = now)
        return closed
    }

    /**
     * Besides the stop a door marks in Drive, gear changes are the only trip boundaries. The first
     * reading after a start only sets the baseline: starting in Drive means part of the trip was
     * missed, so it is not "watched".
     * With [recording] off, stretches are still cut so that nothing merges later, but they stay
     * unclassified and unobserved.
     */
    fun onGear(
        new: Gear,
        km: Float,
        now: Long,
        placeName: String?,
        history: List<Segment>,
        people: List<Person>,
        recording: Boolean = true,
    ): List<LedgerEvent> {
        val previous = gear
        gear = new
        state = state.copy(lastKm = maxOf(km, state.lastKm ?: km), lastSeenAt = now)
        val current = state.lastKm ?: km
        if (previous == new) return emptyList()
        val events = mutableListOf<LedgerEvent>()
        when (new) {
            Gear.DRIVING -> {
                shortTrip = null
                stopBeforeTrip = Stop(state.parkedSegmentId, state.parkedAt, waitStartedAt, waitPlace, doorThisStop)
                pendingWait = if (recording) closeWait(now) else null.also { waitStartedAt = null }
                boardingDoor = doorThisStop
                doorThisStop = null
                val gaps = liveGaps.filter { now - it.seenAt <= GAP_RETRACT_MS }
                liveGaps.clear()
                when {
                    // Process restarted mid-trip: the open trip continues, but it was not watched.
                    previous == null && state.tripStartKm != null -> watchedWholeTrip = false
                    gaps.isNotEmpty() -> {
                        gaps.forEach { gap -> gap.id?.let { events += LedgerEvent.GapRetracted(it) } }
                        watchedWholeTrip = false
                        // The car left when the jump was seen, not when the last reading before it came.
                        state = state.copy(tripStartKm = gaps.first().segment.startKm, tripStartAt = gaps.first().seenAt)
                    }
                    else -> {
                        watchedWholeTrip = previous == Gear.PARK
                        state = state.copy(tripStartKm = current, tripStartAt = now)
                    }
                }
                state = state.copy(parkedSegmentId = null, parkedAt = null)
                if (state.refillPending) closeTank(current, now)?.let { events += it }
            }
            Gear.PARK -> {
                // A trip left open when the process died ended when the app last saw it, not now.
                val interrupted = interruptedAt.takeIf { previous == null }
                interruptedAt = null
                val before = stopBeforeTrip
                stopBeforeTrip = null
                waitStartedAt = now
                waitPlace = placeName
                doorThisStop = null
                val startKm = state.tripStartKm
                val startAt = state.tripStartAt
                state = state.copy(tripStartKm = null, parkedAt = now)
                if (startKm != null) {
                    if (current - startKm >= MIN_SEGMENT_KM) {
                        events += segmentClosed(
                            startKm, startAt, current, interrupted ?: now,
                            placeName.takeIf { interrupted == null }, history, people, recording,
                            pendingWait, boardingDoor, watchedWholeTrip,
                        )
                    } else {
                        shortTrip = ShortTrip(startKm, startAt, placeName, now, pendingWait, boardingDoor, watchedWholeTrip)
                        // No distance: a parking maneuver, so the stop carries on where it was.
                        if (before != null) {
                            state = state.copy(parkedSegmentId = before.segmentId, parkedAt = before.parkedAt ?: now)
                            waitStartedAt = before.waitStartedAt ?: now
                            waitPlace = before.waitPlace ?: placeName
                            doorThisStop = before.door
                        }
                    }
                }
                pendingWait = null
                boardingDoor = null
            }
        }
        return events
    }

    /** The store assigned an id to the closed stretch, so a later door hint can reach it. */
    fun onSegmentSaved(id: Long) {
        state = state.copy(parkedSegmentId = id)
    }

    /**
     * Notices a refill from the energy level. A jump is a refuel; a slow climb (charging with the
     * app running) counts once it is far enough above the tank's lowest point, and closes when the
     * car moves off. Small top-ups merge into the running tank.
     */
    fun onEnergy(percent: Int, km: Float, now: Long): List<LedgerEvent> {
        val last = state.lastEnergyPercent
        val min = minOf(state.tankMinPercent ?: percent, percent)
        state = state.copy(
            lastEnergyPercent = percent,
            tankMinPercent = min,
            tankStartPercent = state.tankStartPercent ?: percent,
            tankStartKm = state.tankStartKm ?: km,
            tankStartAt = if (state.tankStartAt == 0L) now else state.tankStartAt,
        )
        if (last != null && percent - last >= MIN_REFILL_POINTS) {
            return listOfNotNull(closeTank(km, now))
        }
        if (percent - min >= MIN_REFILL_POINTS) state = state.copy(refillPending = true)
        return emptyList()
    }

    private fun closeTank(km: Float, now: Long): LedgerEvent? {
        val startKm = state.tankStartKm ?: km
        val level = state.lastEnergyPercent
        if (km - startKm < MIN_TANK_KM) {
            // Too short to be worth a settlement: carry on as the same tank.
            state = state.copy(refillPending = false, tankMinPercent = level)
            return null
        }
        val tank = Tank(startKm = startKm, endKm = km, startedAt = state.tankStartAt, endedAt = now)
        state = state.copy(
            tankStartKm = km,
            tankStartAt = now,
            tankStartPercent = level,
            tankMinPercent = level,
            refillPending = false,
        )
        return LedgerEvent.TankClosed(tank)
    }

    private fun segmentClosed(
        startKm: Float,
        startAt: Long,
        km: Float,
        now: Long,
        placeName: String?,
        history: List<Segment>,
        people: List<Person>,
        recording: Boolean,
        wait: WaitEntry?,
        door: ExitDoor?,
        watched: Boolean,
    ): LedgerEvent {
        if (!recording) {
            val segment = Segment(startKm = startKm, endKm = km, startedAt = startAt, endedAt = now, observed = false, placeName = placeName)
            return LedgerEvent.SegmentClosed(segment, shouldAsk = false)
        }
        val length = km - startKm
        val lastSegment = history.maxByOrNull { it.endedAt }
        // Driving straight back from a drop-off belongs to the same person, empty seat or not. Only a
        // similar distance counts: going on to work after school is not the way back.
        val isReturn = lastSegment != null &&
            lastSegment.personId != null &&
            lastSegment.attribution != Attribution.UNCLASSIFIED &&
            people.none { it.id == lastSegment.personId && it.isSelf } &&
            WayBack.follows(lastSegment, startAt, length)

        val context = StopContext(now, placeName, door, passengerVacant, watched)
        val suggestion = if (isReturn) null else suggester.suggest(context, history, people)
        val personId = if (isReturn) lastSegment!!.personId else suggestion?.personId
        val attribution = if (personId != null) Attribution.SUGGESTED else Attribution.UNCLASSIFIED
        val segment = Segment(
            startKm = startKm,
            endKm = km,
            startedAt = startAt,
            endedAt = now,
            observed = true,
            personId = personId,
            attribution = attribution,
            // The way back is named for where it came from, not for the house it ended at.
            placeName = if (isReturn) lastSegment!!.placeName else placeName,
            door = null,
            returnLeg = isReturn,
        )
        // Asking is only for the first visits, answered or not. Without a named place the time of day
        // stands in for it or narrows it. Afterwards the car keeps quiet and the stretch stays unclassified.
        val preciseName = placeName != null && !Places.isNeighborhood(placeName)
        val visits = history.count { s ->
            s.observed && !s.returnLeg && s.placeName == placeName && (preciseName || suggester.sameTimeSlot(now, s.endedAt))
        }
        val shouldAsk = personId == null && visits < ASK_FIRST_VISITS
        return LedgerEvent.SegmentClosed(segment, shouldAsk, wait)
    }

    private fun closeWait(now: Long): WaitEntry? {
        val started = waitStartedAt ?: return null
        waitStartedAt = null
        if (now - started < MIN_WAIT_MS) return null
        // A long stop is not waiting for someone; cap it so a night parked never counts.
        if (now - started > MAX_WAIT_MS) return null
        return WaitEntry(started, now, waitPlace, null)
    }

    companion object {
        const val MIN_GAP_KM = 0.5f
        /** Larger than one odometer tick, so a late gear event cannot look like missed distance. */
        const val MIN_LIVE_GAP_KM = 2f
        const val MIN_SEGMENT_KM = 0.1f
        /** Less than this since the trip began is not a stop worth a stretch of its own. */
        const val MIN_STOP_KM = 0.5f
        const val MIN_TANK_KM = 20f
        const val MIN_REFILL_POINTS = 15
        const val ASK_FIRST_VISITS = 3
        const val RETURN_WINDOW_MS = 10 * 60_000L
        const val RETURN_LENGTH_TOLERANCE = 0.3f
        const val MIN_WAIT_MS = 3 * 60_000L
        const val MAX_WAIT_MS = 120 * 60_000L
        const val DOOR_HINT_MS = 10 * 60_000L
        /** Odometer ticks this long after Park still belong to the trip that just ended. */
        const val LATE_TICK_MS = 10_000L
        /** A parked jump followed by Drive this quickly was the start of that trip. */
        const val GAP_RETRACT_MS = 10_000L
    }
}
