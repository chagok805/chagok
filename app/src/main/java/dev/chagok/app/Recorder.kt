package dev.chagok.app

import android.util.Log
import dev.chagok.app.data.LedgerRepository
import dev.chagok.app.data.SeedData
import dev.chagok.app.ledger.Attribution
import dev.chagok.app.ledger.GapGuess
import dev.chagok.app.ledger.GapGuesser
import dev.chagok.app.ledger.Gear
import dev.chagok.app.ledger.LedgerEngine
import dev.chagok.app.ledger.LedgerEvent
import dev.chagok.app.ledger.Milestones
import dev.chagok.app.ledger.Person
import dev.chagok.app.ledger.Phrases
import dev.chagok.app.ledger.ExitDoor
import dev.chagok.app.ledger.Segment
import dev.chagok.app.ledger.Settlement
import dev.chagok.app.ledger.Settlements
import dev.chagok.app.ledger.Suggester
import dev.chagok.app.ledger.Tank
import dev.chagok.app.ledger.WayBack
import dev.chagok.app.vehicle.CarSignals
import dev.chagok.app.vehicle.DoorSide
import dev.chagok.app.vehicle.NaviGateway
import dev.chagok.app.vehicle.VehicleGateway
import dev.chagok.app.voice.Memos
import dev.chagok.app.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

/** The odometer range of the running tank. */
data class TankRange(val startKm: Float, val startAt: Long)

/** What the home screen should bring up next. */
sealed interface Prompt {
    val id: Long

    /** A stretch was just recorded. With no person it asks; with one it offers a correction. */
    data class Recorded(
        override val id: Long,
        val segment: Segment,
        val person: Person?,
        val ask: Boolean,
        /** Opened from the recent list to fix a past stretch, so the choices show right away. */
        val correcting: Boolean = false,
    ) : Prompt

    /** Distance the app did not see. */
    data class Gap(override val id: Long, val segment: Segment, val guess: GapGuess?, val guessPerson: Person?) : Prompt

    /** A tank or charge just ended. */
    data class TankClosed(override val id: Long, val tank: Tank, val settlement: Settlement) : Prompt
}

/**
 * The answer just given, for a short while: what it did in one sentence, and how every stretch it
 * touched looked before, so "되돌리기" can put it all back and ask again.
 */
data class Answered(
    val id: Long,
    val text: String,
    val restore: List<Segment>,
    /** Stretches the answer added, such as the rest of a gap split off by a guess. */
    val inserted: List<Long>,
    val prompt: Prompt?,
    val noticeBefore: String?,
    val noticeAfter: String?,
)

/**
 * Turns vehicle signals into ledger entries. All inputs go through one queue, because the engine and
 * the database must see them in order even when several signals change at once.
 */
class Recorder(
    private val repository: LedgerRepository,
    private val vehicle: VehicleGateway,
    private val signals: CarSignals,
    private val navi: NaviGateway,
    private val speaker: Speaker,
    private val memos: Memos,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val suggester = Suggester()
    private val engine = LedgerEngine(suggester, repository.loadEngineState())
    private val inputs = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val _prompts = MutableStateFlow<List<Prompt>>(emptyList())
    val prompts: StateFlow<List<Prompt>> = _prompts.asStateFlow()

    private val _tank = MutableStateFlow(rangeOf(engine))
    val tank: StateFlow<TankRange?> = _tank.asStateFlow()

    private val _latestNotice = MutableStateFlow<String?>(null)
    val latestNotice: StateFlow<String?> = _latestNotice.asStateFlow()

    private val _answered = MutableStateFlow<Answered?>(null)
    val answered: StateFlow<Answered?> = _answered.asStateFlow()
    private var answerSeq = 0L
    private var answerTimeout: Job? = null

    private fun rangeOf(e: LedgerEngine) = e.state.tankStartKm?.let { TankRange(it, e.state.tankStartAt) }

    private var promptSeq = 0L
    private var started = false
    private val _parked = MutableStateFlow(false)
    /** False until the gear is known to be Park: nothing plays or records on a guess. */
    val parked: StateFlow<Boolean> = _parked.asStateFlow()
    /** Read live, not from the queue: the queue can be behind, and a late Park must not unlock speech. */
    private val gearIsPark get() = signals.state.value.gear?.let(::parseGear) == Gear.PARK
    private var sceneActive = false
    /**
     * A settlement is said at the first stop after the fill: at the pump the driver is outside or
     * about to leave, and leaving would cut it off.
     */
    private var settlementLine: String? = null
    private var settlementKm = 0f

    fun start() {
        if (started) return
        started = true
        scope.launch { for (input in inputs) runCatching { input() }.onFailure { Log.e(TAG, "input failed", it) } }
        scope.launch {
            signals.state.map { it.gear }.distinctUntilChanged().collect { raw ->
                _parked.value = raw?.let(::parseGear) == Gear.PARK
                // A failed restore gets another try once the car is parked again.
                if (gearIsPark) vehicle.restoreCabin() else stopScene()
            }
        }
        scope.launch { begin() }
    }

    private suspend fun begin() {
        repository.ensureSelf(System.currentTimeMillis())
        checkFirstWeek()
        // Nothing can be recorded before the first odometer reading.
        val firstKm = vehicle.snapshot.map { it.odometerKm }.first { it != null }!!
        enqueue { handle(engine.onStart(firstKm, System.currentTimeMillis())) }
        followOdometerStartingOver()

        scope.launch {
            vehicle.snapshot.map { it.odometerKm }.distinctUntilChanged().collect { km ->
                if (km != null) enqueue { onOdometer(km) }
            }
        }
        scope.launch {
            vehicle.snapshot.map { it.passengerSeatVacant }.distinctUntilChanged().collect { vacant ->
                enqueue { engine.onPassengerSeat(vacant) }
            }
        }
        scope.launch {
            signals.state.map { it.gear }.distinctUntilChanged().collect { raw ->
                val gear = raw?.let(::parseGear) ?: return@collect
                enqueue { onGear(gear) }
            }
        }
        scope.launch {
            var open = emptySet<DoorSide>()
            vehicle.snapshot.map { it.openDoors }.distinctUntilChanged().collect { now ->
                val newlyOpen = now - open
                open = now
                for (side in newlyOpen) {
                    // The driver's own door says nothing about anyone else.
                    val door = when (side) {
                        DoorSide.REAR_LEFT -> ExitDoor.LEFT
                        DoorSide.REAR_RIGHT -> ExitDoor.RIGHT
                        DoorSide.FRONT_RIGHT -> ExitDoor.PASSENGER
                        DoorSide.FRONT_LEFT -> null
                    } ?: continue
                    enqueue { onExitDoor(door) }
                }
            }
        }
        scope.launch {
            vehicle.snapshot.map { it.fuelPercent ?: it.batteryPercent }.distinctUntilChanged().collect { percent ->
                if (percent != null) enqueue { onEnergy(percent) }
            }
        }
    }

    /**
     * The emulator's odometer goes back to 0 at every cold boot, which would leave the ledger ahead of
     * the car and every new reading ignored. Demonstration data starts over; real records move down
     * with the odometer and stay as they were. Waits out the replay of old values right after binding.
     */
    private fun followOdometerStartingOver() {
        scope.launch {
            delay(ODOMETER_SETTLE_MS)
            val km = vehicle.snapshot.value.odometerKm ?: return@launch
            val known = engine.state.lastKm ?: return@launch
            if (km >= known - 1f) return@launch
            if (repository.isSeeded) seedDemo {} else enqueue { shiftLedgerTo(km) }
        }
    }

    private suspend fun shiftLedgerTo(km: Float) {
        val known = engine.state.lastKm ?: return
        val down = known - km
        repository.shiftOdometer(down)
        engine.rebase(km, (engine.state.tankStartKm ?: known) - down, engine.state.tankStartAt)
        _prompts.value = emptyList()
        settlementLine = null
        _tank.value = rangeOf(engine)
        persist()
    }

    private fun enqueue(input: suspend () -> Unit) {
        inputs.trySend(input)
    }

    private suspend fun onOdometer(km: Float) {
        // Only a late tick closing a trip needs the history; loading it on every tick would be wasteful.
        val late = engine.awaitingLateTrip
        handle(
            engine.onOdometer(
                km,
                System.currentTimeMillis(),
                if (late) repository.history() else emptyList(),
                if (late) repository.peopleOnce() else emptyList(),
                repository.recordingEnabled,
            ),
        )
    }

    private suspend fun onGear(gear: Gear) {
        val km = engine.state.lastKm ?: return
        if (BuildConfig.DEBUG) Log.d(TAG, "gear $gear at $km")
        val recording = repository.recordingEnabled
        val now = System.currentTimeMillis()
        if (gear == Gear.DRIVING) navi.forgetArrival()
        val place = if (gear == Gear.PARK && recording && repository.placeEnabled) navi.placeName(now) else null
        handle(engine.onGear(gear, km, now, place, repository.history(), repository.peopleOnce(), recording))
        if (gear == Gear.PARK && !speakSettlementIfDue(engine.state.lastKm ?: km)) checkFirstWeek()
        persist()
    }

    private suspend fun onExitDoor(door: ExitDoor) {
        if (!repository.recordingEnabled) return
        if (engine.inDrive) {
            onExitDoorInDrive(door)
            return
        }
        val event = engine.onDoorOpened(door, System.currentTimeMillis()) as? LedgerEvent.DropOffSeen ?: return
        repository.markDropOff(event.segmentId, event.door, suggester)
        // A hint can turn "unknown" into a proposal: let the card on screen catch up.
        refreshRecordedPrompt(event.segmentId)
    }

    private suspend fun onExitDoorInDrive(door: ExitDoor) {
        val km = engine.state.lastKm ?: return
        val now = System.currentTimeMillis()
        if (!engine.doorMarksStop(door, km, now)) return
        val place = if (repository.placeEnabled) navi.placeName(now) else null
        val closed = engine.closeAtDoor(door, km, now, place, repository.history(), repository.peopleOnce())
        navi.forgetArrival()
        val id = onSegmentClosed(closed)
        _tank.value = rangeOf(engine)
        persist()
        if (id == null) return
        repository.markDropOff(id, door, suggester)
        refreshRecordedPrompt(id)
    }

    private suspend fun onEnergy(percent: Int) {
        val km = engine.state.lastKm ?: return
        handle(engine.onEnergy(percent, km, System.currentTimeMillis()))
        _tank.value = rangeOf(engine)
        persist()
    }

    private suspend fun handle(events: List<LedgerEvent>) {
        for (event in events) {
            if (BuildConfig.DEBUG) Log.d(TAG, "event $event")
            when (event) {
                is LedgerEvent.GapFound -> onGap(event)
                is LedgerEvent.GapRetracted -> {
                    repository.deleteSegment(event.segmentId)
                    _prompts.update { list -> list.filterNot { it is Prompt.Gap && it.segment.id == event.segmentId } }
                }
                is LedgerEvent.SegmentClosed -> onSegmentClosed(event)
                is LedgerEvent.SegmentExtended -> {
                    repository.extendSegment(event.segmentId, event.endKm)
                    refreshRecordedPrompt(event.segmentId)
                }
                is LedgerEvent.TankClosed -> onTankClosed(event.tank)
                is LedgerEvent.DropOffSeen -> Unit
            }
        }
        _tank.value = rangeOf(engine)
        persist()
    }

    private suspend fun onGap(event: LedgerEvent.GapFound) {
        val saved = repository.saveSegment(event.segment) ?: return
        engine.onGapSaved(event.segment, saved.id)
        if (!repository.recordingEnabled) return
        val people = repository.peopleOnce()
        val guess = GapGuesser.guess(saved.km, repository.history(), people)
        val person = guess?.let { g -> people.firstOrNull { it.id == g.personId } }
        add { Prompt.Gap(it, saved, guess, person) }
    }

    /** Returns the stored id, or null when nothing new was left to store. */
    private suspend fun onSegmentClosed(event: LedgerEvent.SegmentClosed): Long? {
        val saved = repository.saveSegment(event.segment) ?: return null
        engine.onSegmentSaved(saved.id)
        event.wait?.let { repository.saveWait(it, saved.id) }
        val person = saved.personId?.let { pid -> repository.peopleOnce().firstOrNull { it.id == pid } }
        // The way out is the question worth answering: its answer settles this way back too.
        val waitingOnWayOut = person == null && _prompts.value.any {
            it is Prompt.Recorded && it.ask && WayBack.follows(it.segment, saved.startedAt, saved.km)
        }
        if ((person != null || event.shouldAsk) && !waitingOnWayOut) {
            add { Prompt.Recorded(it, saved, person, event.shouldAsk) }
        }
        _latestNotice.value = null
        if (person != null && !person.isSelf) speakRecordedOnceADay(saved.endedAt)
        return saved.id
    }

    /** Warm once, nagging every time. The driver's own trips stay silent. */
    private fun speakRecordedOnceADay(now: Long) {
        // A stop marked in Drive stays silent, and a settlement about to be said takes this stop.
        // Either way today's line is kept for the next Park.
        if (!gearIsPark || settlementLine != null) return
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val day = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        if (day == repository.lastRecordedSpeechDay) return
        repository.lastRecordedSpeechDay = day
        speakIfParked(Phrases.RECORDED_SPOKEN)
    }

    private suspend fun onTankClosed(tank: Tank) {
        val id = repository.saveTank(tank)
        val endKm = tank.endKm ?: tank.startKm
        val waits = repository.waitsOnce().filter { it.startedAt >= tank.startedAt }
        val settlement = Settlements.of(tank.startKm, endKm, repository.history(), repository.peopleOnce(), waits)
        add { Prompt.TankClosed(it, tank.copy(id = id), settlement) }
        settlementLine = Phrases.tankSpoken(settlement)
        settlementKm = tank.endKm ?: tank.startKm
    }

    /** Returns whether it was said, so nothing else talks over it at this stop. */
    private fun speakSettlementIfDue(km: Float): Boolean {
        val line = settlementLine ?: return false
        if (!gearIsPark || km - settlementKm < SETTLEMENT_DRIVE_KM) return false
        settlementLine = null
        speakIfParked(line)
        return true
    }

    private suspend fun refreshRecordedPrompt(segmentId: Long) {
        val updated = repository.history().firstOrNull { it.id == segmentId } ?: return
        val people = repository.peopleOnce()
        _prompts.update { list ->
            list.map { prompt ->
                if (prompt is Prompt.Recorded && prompt.segment.id == segmentId) {
                    val person = updated.personId?.let { pid -> people.firstOrNull { it.id == pid } }
                    prompt.copy(segment = updated, person = person, ask = person == null)
                } else {
                    prompt
                }
            }
        }
    }

    private fun speakIfParked(text: String) {
        // Nothing is ever said while driving, and a scene in progress is not interrupted.
        if (gearIsPark && !sceneActive && repository.voiceEnabled) speaker.speak(text)
    }

    private inline fun add(build: (Long) -> Prompt) {
        val prompt = build(++promptSeq)
        _prompts.update { current ->
            // Every stretch stays in the ledger and the recent list, so only the newest gets a card.
            val kept = if (prompt is Prompt.Recorded) current.filterNot { it is Prompt.Recorded } else current
            (listOf(prompt) + kept).take(MAX_PROMPTS)
        }
    }

    fun dismiss(promptId: Long) {
        _prompts.update { list -> list.filterNot { it.id == promptId } }
    }

    /** Brings a past stretch back as a card, so a wrong record can always be fixed. */
    fun reopen(segment: Segment) {
        enqueue {
            val person = segment.personId?.let { pid -> repository.peopleOnce().firstOrNull { it.id == pid } }
            add { Prompt.Recorded(it, segment, person, ask = false, correcting = true) }
        }
    }

    /** Removes the name everywhere it is showing, not only from the ledger. */
    fun deletePerson(personId: Long) {
        enqueue {
            repository.deletePerson(personId).forEach(memos::delete)
            _latestNotice.value = null
            // An answer about someone who is gone cannot be taken back into their name.
            answerTimeout?.cancel()
            _answered.value = null
            _prompts.update { list ->
                list.mapNotNull { prompt ->
                    when (prompt) {
                        is Prompt.Recorded -> prompt.takeUnless { it.person?.id == personId }
                        is Prompt.Gap -> if (prompt.guessPerson?.id == personId) prompt.copy(guess = null, guessPerson = null) else prompt
                        is Prompt.TankClosed -> prompt
                    }
                }
            }
        }
    }

    fun confirm(segmentId: Long, personId: Long, promptId: Long) {
        enqueue {
            engine.forgetLiveGaps()
            val before = repository.history()
            val noticeBefore = _latestNotice.value
            repository.confirm(segmentId, personId)
            repository.settleRoundTrip(segmentId, personId)
            val prompt = takePrompt(promptId)
            val after = repository.history()
            noticeForConfirmation(personId, before, after)
            val person = repository.peopleOnce().firstOrNull { it.id == personId }
            val km = changedTo(before, after) { it.personId == personId && it.attribution == Attribution.CONFIRMED }
            keepForUndo(before, after, prompt, noticeBefore, person?.let { Phrases.stacked(it.name, it.isSelf, km) })
        }
    }

    fun markOtherDriver(segmentId: Long, promptId: Long) {
        enqueue {
            engine.forgetLiveGaps()
            val before = repository.history()
            repository.markOtherDriver(segmentId)
            keepForUndo(before, repository.history(), takePrompt(promptId), _latestNotice.value, Phrases.OTHER_DRIVER_NOTED)
        }
    }

    /** Takes back the answer just given: every stretch it touched as it was, and its card again. */
    fun undo(answer: Answered) {
        // Decided at the tap, not when the queue gets to it: a tap just before the timeout still counts.
        if (_answered.value?.id != answer.id) return
        answerTimeout?.cancel()
        _answered.value = null
        enqueue {
            repository.restore(answer.restore, answer.inserted)
            // A first record or milestone earned by this answer is no longer true.
            if (_latestNotice.value == answer.noticeAfter) _latestNotice.value = answer.noticeBefore
            when (val p = answer.prompt) {
                is Prompt.Recorded -> add { p.copy(id = it) }
                is Prompt.Gap -> add { p.copy(id = it) }
                else -> Unit
            }
        }
    }

    private fun takePrompt(promptId: Long): Prompt? {
        val prompt = _prompts.value.firstOrNull { it.id == promptId }
        dismiss(promptId)
        return prompt
    }

    /** Kilometres of stretches that an answer turned into what [now] describes. */
    private fun changedTo(before: List<Segment>, after: List<Segment>, now: (Segment) -> Boolean): Float {
        val old = before.associateBy { it.id }
        return after.filter { s -> now(s) && old[s.id]?.let { !now(it) } == true }.sumOf { it.km.toDouble() }.toFloat()
    }

    /** Keeps what an answer changed for [ANSWER_UNDO_MS], so it can be taken back. */
    private fun keepForUndo(before: List<Segment>, after: List<Segment>, prompt: Prompt?, noticeBefore: String?, text: String?) {
        val now = after.associateBy { it.id }
        val touched = before.filter { old ->
            val new = now[old.id] ?: return@filter false
            new.attribution != old.attribution || new.personId != old.personId || new.placeName != old.placeName ||
                new.returnLeg != old.returnLeg || new.startKm != old.startKm || new.endKm != old.endKm
        }
        val known = before.map { it.id }.toSet()
        val inserted = after.map { it.id }.filterNot { it in known }
        if ((touched.isEmpty() && inserted.isEmpty()) || text == null) return
        val answer = Answered(++answerSeq, text, touched, inserted, prompt, noticeBefore, _latestNotice.value)
        _answered.value = answer
        answerTimeout?.cancel()
        answerTimeout = scope.launch {
            delay(ANSWER_UNDO_MS)
            if (_answered.value?.id == answer.id) _answered.value = null
        }
    }

    /** A named sentence is only ever earned by confirmed distance. */
    private suspend fun noticeForConfirmation(personId: Long, before: List<Segment>, after: List<Segment>) {
        val person = repository.peopleOnce().firstOrNull { it.id == personId } ?: return
        if (person.isSelf) return
        val beforeKm = Settlements.confirmedKm(personId, before)
        val afterKm = Settlements.confirmedKm(personId, after)
        val text = when {
            beforeKm == 0f && afterKm > 0f -> Phrases.firstRecord(person.name)
            else -> Milestones.crossed(beforeKm, afterKm)?.let { Phrases.milestone(person.name, it) }
        } ?: return
        // Screen only: a child in the back should not hear "the road for you" as a debt.
        _latestNotice.value = text
    }

    fun confirmSuggestions(startKm: Float, endKm: Float) {
        enqueue {
            val before = repository.history()
            repository.confirmSuggestions(startKm, endKm)
            val after = repository.history()
            repository.peopleOnce().forEach { noticeForConfirmation(it.id, before, after) }
        }
    }

    /** Seven days after the first record, once: the first moment the number means something. */
    private suspend fun checkFirstWeek() {
        if (repository.firstWeekShown || repository.isSeeded) return
        val first = repository.history().filter { it.observed }.minOfOrNull { it.startedAt } ?: return
        val now = System.currentTimeMillis()
        if (now - first < WEEK_MS) return
        repository.firstWeekShown = true
        val week = Settlements.familyTime(first, first + WEEK_MS, repository.history(), repository.peopleOnce(), repository.waitsOnce())
        if (week.km < 1f) return
        val text = Phrases.firstWeek(week)
        _latestNotice.value = text
        speakIfParked(text)
    }

    /** Reads a scene aloud, then the parent's own words. Refuses while driving. */
    fun playScene(text: String, dayId: Long) {
        if (!gearIsPark) return
        memos.stopPlaying()
        sceneActive = true
        vehicle.warmCabin()
        speaker.speak(text, scene = true) { memos.play(dayId) { sceneActive = false } }
    }

    fun stopScene() {
        sceneActive = false
        vehicle.restoreCabin()
        speaker.stop()
        memos.stopPlaying()
    }

    fun leaveUnclassified(segmentId: Long, promptId: Long) {
        enqueue {
            val before = repository.history()
            repository.leaveUnclassified(segmentId)
            keepForUndo(before, repository.history(), takePrompt(promptId), _latestNotice.value, Phrases.LEFT_UNCLASSIFIED)
        }
    }

    fun acceptGapGuess(prompt: Prompt.Gap) {
        val guess = prompt.guess ?: return
        enqueue {
            engine.forgetLiveGaps()
            val before = repository.history()
            repository.applyGapGuess(prompt.segment.id, guess)
            val taken = takePrompt(prompt.id)
            val after = repository.history()
            val person = repository.peopleOnce().firstOrNull { it.id == guess.personId }
            val km = changedTo(before, after) { it.personId == guess.personId && it.attribution != Attribution.UNCLASSIFIED }
            keepForUndo(before, after, taken, _latestNotice.value, person?.let { Phrases.stacked(it.name, it.isSelf, km) })
        }
    }

    /** Fills the ledger for the presentation. Everything after this comes from live signals. */
    fun seedDemo(onDone: (Boolean) -> Unit) {
        val km = vehicle.snapshot.value.odometerKm ?: engine.state.lastKm
        if (km == null) {
            onDone(false)
            return
        }
        enqueue {
            val now = System.currentTimeMillis()
            val seed = SeedData.build(km, now)
            repository.ensureSelf(now)
            memos.deleteAll()
            repository.replaceWithSeed(seed)
            engine.rebase(km, seed.tankStartKm, seed.tankStartAt)
            repository.lastRecordedSpeechDay = 0
            _prompts.value = emptyList()
            _answered.value = null
            settlementLine = null
            _tank.value = rangeOf(engine)
            persist()
            onDone(true)
        }
    }

    fun clearAll() {
        enqueue {
            memos.deleteAll()
            repository.clearAll()
            repository.isSeeded = false
            repository.firstWeekShown = false
            repository.lastRecordedSpeechDay = 0
            _latestNotice.value = null
            val now = System.currentTimeMillis()
            (vehicle.snapshot.value.odometerKm ?: engine.state.lastKm)?.let { engine.rebase(it, it, now) }
            _prompts.value = emptyList()
            _answered.value = null
            settlementLine = null
            _tank.value = rangeOf(engine)
            persist()
        }
    }

    private fun persist() = repository.saveEngineState(engine.state)

    private fun parseGear(raw: String): Gear? = when {
        raw.contains("PARK", ignoreCase = true) -> Gear.PARK
        raw.contains("UNKNOWN", ignoreCase = true) -> null
        else -> Gear.DRIVING
    }

    private companion object {
        const val TAG = "ChagokRecorder"
        const val MAX_PROMPTS = 5
        /** How long "되돌리기" stays offered after an answer. */
        const val ANSWER_UNDO_MS = 15_000L
        const val DAY_MS = 24 * 60 * 60_000L
        const val ODOMETER_SETTLE_MS = 20_000L
        const val SETTLEMENT_DRIVE_KM = 0.5f
        const val WEEK_MS = 7 * DAY_MS
    }
}
