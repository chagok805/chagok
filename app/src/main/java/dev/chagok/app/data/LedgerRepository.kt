package dev.chagok.app.data

import android.content.Context
import androidx.room.withTransaction
import dev.chagok.app.ledger.Attribution
import dev.chagok.app.ledger.EngineState
import dev.chagok.app.ledger.GapGuess
import dev.chagok.app.ledger.Person
import dev.chagok.app.ledger.ExitDoor
import dev.chagok.app.ledger.Segment
import dev.chagok.app.ledger.StopContext
import dev.chagok.app.ledger.Suggester
import dev.chagok.app.ledger.Tank
import dev.chagok.app.ledger.WaitEntry
import dev.chagok.app.ledger.WayBack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A date the family chose for "그날". */
data class SpecialDay(val id: Long, val personId: Long, val title: String, val fromMillis: Long)

class LedgerRepository(context: Context, private val db: LedgerDatabase = LedgerDatabase.create(context)) {

    private val dao = db.dao()
    private val prefs = context.applicationContext.getSharedPreferences("ledger", Context.MODE_PRIVATE)

    val people: Flow<List<Person>> = dao.people().map { list -> list.map { it.toPerson() } }
    val segments: Flow<List<Segment>> = dao.segments().map { list -> list.map { it.toSegment() } }
    val waits: Flow<List<WaitEntry>> = dao.waits().map { list -> list.map { it.toWait() } }
    val tanks: Flow<List<Tank>> = dao.tanks().map { list -> list.map { Tank(it.id, it.startKm, it.endKm, it.startedAt, it.endedAt) } }
    val days: Flow<List<SpecialDay>> = dao.days().map { list -> list.map { SpecialDay(it.id, it.personId, it.title, it.fromMillis) } }

    suspend fun peopleOnce(): List<Person> = dao.peopleOnce().map { it.toPerson() }

    suspend fun history(): List<Segment> = dao.segmentsOnce().map { it.toSegment() }

    suspend fun waitsOnce(): List<WaitEntry> = dao.waitsOnce().map { it.toWait() }

    /** Every ledger needs the driver: distance that is "내 길" is attributed to this person. */
    suspend fun ensureSelf(now: Long) {
        if (dao.peopleOnce().none { it.isSelf }) dao.insertPerson(PersonEntity(name = "나", isSelf = true, createdAt = now))
    }

    suspend fun addPerson(name: String, now: Long): Long = dao.insertPerson(PersonEntity(name = name, createdAt = now))

    /**
     * Removes the name everywhere; the distance stays and becomes unclassified.
     * Returns the ids of the person's days, whose voice memos the caller deletes.
     */
    suspend fun deletePerson(id: Long): List<Long> = db.withTransaction {
        val days = dao.dayIdsOf(id)
        dao.clearWaitPlacesOf(id)
        dao.unclassifyPerson(id)
        dao.deleteDaysOfPerson(id)
        dao.deletePerson(id)
        days
    }

    /**
     * Saves a stretch, trimmed so it never overlaps distance already in the ledger. This is the one
     * guard against counting the same kilometres twice, whatever order the signals arrived in.
     * Returns the stored stretch, or null when nothing new is left.
     */
    suspend fun saveSegment(segment: Segment): Segment? = db.withTransaction {
        val covered = dao.maxEndKm()
        val start = if (covered != null && covered > segment.startKm) covered else segment.startKm
        if (segment.endKm - start < MIN_NEW_KM) return@withTransaction null
        val trimmed = segment.copy(startKm = start)
        trimmed.copy(id = dao.insertSegment(trimmed.toEntity()))
    }

    suspend fun deleteSegment(id: Long) = dao.deleteSegment(id)

    suspend fun extendSegment(id: Long, endKm: Float) = dao.extendSegment(id, endKm)

    /** The driver answered or corrected: from here on this distance can carry a name. */
    suspend fun confirm(segmentId: Long, personId: Long) = dao.confirmSegment(segmentId, personId)

    /**
     * A round trip is one errand, so one answer settles both legs. Answering for the way back confirms
     * the way out. Answering for the way out moves a way back that was only inferred from it, or binds
     * one that had nobody to follow when it closed. What the driver confirmed is never touched, and
     * "내 길" says nothing about the other leg.
     */
    suspend fun settleRoundTrip(segmentId: Long, personId: Long) = db.withTransaction {
        if (peopleOnce().any { it.id == personId && it.isSelf }) return@withTransaction
        val all = history()
        val answered = all.firstOrNull { it.id == segmentId } ?: return@withTransaction
        val other = WayBack.otherLegSettled(answered, all) ?: return@withTransaction
        when {
            answered.returnLeg -> dao.confirmSegment(other.id, personId)
            other.attribution == Attribution.SUGGESTED -> dao.suggestPerson(other.id, personId)
            else -> dao.bindWayBack(other.id, personId, answered.placeName)
        }
    }

    /** Someone else drove. Like any answer, it covers both legs of a round trip, short of what was confirmed. */
    suspend fun markOtherDriver(segmentId: Long) = db.withTransaction {
        val all = history()
        val answered = all.firstOrNull { it.id == segmentId } ?: return@withTransaction
        dao.markOtherDriver(segmentId)
        WayBack.otherLegOtherDriver(answered, all)?.let { dao.markOtherDriver(it.id) }
    }

    /**
     * Takes an answer back: each stretch gets everything it had before, and stretches the answer
     * split off (an accepted gap guess) go again, so the ledger is exactly as it was.
     */
    suspend fun restore(segments: List<Segment>, inserted: List<Long>) = db.withTransaction {
        inserted.forEach { dao.deleteSegment(it) }
        segments.forEach { dao.restoreSegment(it.id, it.personId, it.attribution.name, it.placeName, it.returnLeg, it.startKm, it.endKm) }
    }

    /** A way back inferred from this stretch has nothing to stand on once the stretch is left unknown. */
    suspend fun leaveUnclassified(segmentId: Long) = db.withTransaction {
        val all = history()
        val back = all.firstOrNull { it.id == segmentId }?.let { WayBack.otherLegLeft(it, all) }
        dao.unclassifySegment(segmentId)
        if (back != null) dao.unclassifySegment(back.id)
    }

    /** Moves the whole ledger down the odometer. Only differences are ever shown, so nothing changes. */
    suspend fun shiftOdometer(km: Float) = db.withTransaction {
        dao.shiftSegments(km)
        dao.shiftTanks(km)
    }

    suspend fun confirmSuggestions(startKm: Float, endKm: Float) = dao.confirmSuggestionsBetween(startKm, endKm)

    /**
     * Stores the door hint. A stretch nobody could name yet, or one only proposed as "내 길", gets one
     * more try: someone else got out, so it was probably not the driver's own trip. What the
     * driver confirmed is never touched.
     */
    suspend fun markDropOff(segmentId: Long, door: ExitDoor, suggester: Suggester) {
        dao.setRearDoor(segmentId, door.name)
        val current = dao.segment(segmentId) ?: return
        if (current.attribution == Attribution.OTHER_DRIVER.name) return
        val people = peopleOnce()
        val selfId = people.firstOrNull { it.isSelf }?.id
        val proposedSelf = current.attribution == Attribution.SUGGESTED.name && current.personId == selfId
        if (current.personId != null && !proposedSelf) return
        val history = history().filter { it.id != segmentId }
        val context = StopContext(current.endedAt, current.placeName, door, passengerVacant = false, watchedWholeTrip = false)
        val suggestion = suggester.suggest(context, history, people)?.takeIf { it.personId != selfId }
        when {
            suggestion != null -> dao.suggestPerson(segmentId, suggestion.personId)
            proposedSelf -> dao.unclassifySegment(segmentId)
        }
    }

    /** Accepts a guess for an unseen stretch: the explained part is a proposal, the rest stays unclassified. */
    suspend fun applyGapGuess(segmentId: Long, guess: GapGuess) = db.withTransaction {
        val gap = dao.segment(segmentId) ?: return@withTransaction
        val explainedEnd = (gap.startKm + guess.km).coerceAtMost(gap.endKm)
        dao.updateSegment(
            gap.copy(
                endKm = explainedEnd,
                personId = guess.personId,
                attribution = Attribution.SUGGESTED.name,
                placeName = guess.placeName,
            ),
        )
        if (gap.endKm - explainedEnd > 0.01f) {
            dao.insertSegment(gap.copy(id = 0, startKm = explainedEnd))
        }
    }

    suspend fun saveWait(wait: WaitEntry, segmentId: Long) {
        dao.insertWait(WaitEntity(startedAt = wait.startedAt, endedAt = wait.endedAt, placeName = wait.placeName, segmentId = segmentId))
    }

    suspend fun saveTank(tank: Tank): Long = dao.insertTank(
        TankEntity(
            startKm = tank.startKm,
            endKm = tank.endKm ?: tank.startKm,
            startedAt = tank.startedAt,
            endedAt = tank.endedAt ?: tank.startedAt,
        ),
    )

    suspend fun addDay(personId: Long, title: String, fromMillis: Long) {
        dao.insertDay(DayEntity(personId = personId, title = title, fromMillis = fromMillis))
    }

    suspend fun deleteDay(id: Long) = dao.deleteDay(id)

    suspend fun clearAll() = db.withTransaction {
        dao.clearSegments()
        dao.clearWaits()
        dao.clearTanks()
        dao.clearDays()
        dao.clearFamily()
    }

    /** Replaces the ledger with demonstration data. The screen says so while it is in use. */
    suspend fun replaceWithSeed(seed: SeedData) = db.withTransaction {
        dao.clearSegments()
        dao.clearWaits()
        dao.clearTanks()
        dao.clearDays()
        dao.clearFamily()
        val ids = seed.people.associateWith { name -> dao.insertPerson(PersonEntity(name = name, createdAt = seed.now, seed = true)) }
        val self = dao.peopleOnce().first { it.isSelf }.id
        fun resolve(name: String?): Long? = when (name) {
            null -> null
            SeedData.SELF -> self
            else -> ids.getValue(name)
        }
        val segmentIds = seed.segments.map { s ->
            s to dao.insertSegment(s.segment.copy(personId = resolve(s.person)).toEntity().copy(seed = true))
        }
        // Each wait belongs to the pick-up trip that followed it.
        val pickUps = segmentIds.filter { (s, _) -> s.person == seed.waitsFor }.map { it.second }
        seed.waits.forEachIndexed { i, w ->
            val segmentId = pickUps.getOrNull(i) ?: pickUps.firstOrNull()
            dao.insertWait(WaitEntity(startedAt = w.startedAt, endedAt = w.endedAt, placeName = w.placeName, segmentId = segmentId, seed = true))
        }
        for (t in seed.tanks) {
            dao.insertTank(TankEntity(startKm = t.startKm, endKm = t.endKm ?: t.startKm, startedAt = t.startedAt, endedAt = t.endedAt ?: t.startedAt, seed = true))
        }
        for (d in seed.days) {
            dao.insertDay(DayEntity(personId = resolve(d.person)!!, title = d.title, fromMillis = d.fromMillis, seed = true))
        }
        isSeeded = true
    }

    var isSeeded: Boolean
        get() = prefs.getBoolean(KEY_SEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_SEEDED, value).apply()

    /** Local date of the last spoken "적어 뒀어요", so it stays once a day across restarts. */
    var lastRecordedSpeechDay: Int
        get() = prefs.getInt(KEY_RECORDED_SPEECH_DAY, 0)
        set(value) = prefs.edit().putInt(KEY_RECORDED_SPEECH_DAY, value).apply()

    var firstWeekShown: Boolean
        get() = prefs.getBoolean(KEY_FIRST_WEEK, false)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_WEEK, value).apply()

    var recordingEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECORDING, true)
        set(value) = prefs.edit().putBoolean(KEY_RECORDING, value).apply()

    var placeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLACE, true)
        set(value) = prefs.edit().putBoolean(KEY_PLACE, value).apply()

    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    fun loadEngineState(): EngineState = EngineState(
        lastKm = prefs.floatOrNull(KEY_LAST_KM),
        lastSeenAt = prefs.getLong(KEY_LAST_SEEN, 0),
        tripStartKm = prefs.floatOrNull(KEY_TRIP_KM),
        tripStartAt = prefs.getLong(KEY_TRIP_AT, 0),
        lastEnergyPercent = prefs.intOrNull(KEY_ENERGY),
        tankMinPercent = prefs.intOrNull(KEY_TANK_MIN),
        refillPending = prefs.getBoolean(KEY_REFILL_PENDING, false),
        tankStartKm = prefs.floatOrNull(KEY_TANK_KM),
        tankStartAt = prefs.getLong(KEY_TANK_AT, 0),
        tankStartPercent = prefs.intOrNull(KEY_TANK_PERCENT),
    )

    fun saveEngineState(s: EngineState) {
        prefs.edit().apply {
            putFloatOrRemove(KEY_LAST_KM, s.lastKm)
            putLong(KEY_LAST_SEEN, s.lastSeenAt)
            putFloatOrRemove(KEY_TRIP_KM, s.tripStartKm)
            putLong(KEY_TRIP_AT, s.tripStartAt)
            if (s.lastEnergyPercent != null) putInt(KEY_ENERGY, s.lastEnergyPercent) else remove(KEY_ENERGY)
            putFloatOrRemove(KEY_TANK_KM, s.tankStartKm)
            putLong(KEY_TANK_AT, s.tankStartAt)
            if (s.tankStartPercent != null) putInt(KEY_TANK_PERCENT, s.tankStartPercent) else remove(KEY_TANK_PERCENT)
            if (s.tankMinPercent != null) putInt(KEY_TANK_MIN, s.tankMinPercent) else remove(KEY_TANK_MIN)
            putBoolean(KEY_REFILL_PENDING, s.refillPending)
        }.apply()
    }

    private fun PersonEntity.toPerson() = Person(id, name, isSelf)

    private fun WaitEntity.toWait() = WaitEntry(startedAt, endedAt, placeName, segmentId)

    private fun SegmentEntity.toSegment() = Segment(
        id = id,
        startKm = startKm,
        endKm = endKm,
        startedAt = startedAt,
        endedAt = endedAt,
        observed = observed,
        personId = personId,
        attribution = Attribution.valueOf(attribution),
        placeName = placeName,
        door = rearDoor?.let(ExitDoor::valueOf),
        returnLeg = returnLeg,
    )

    private fun Segment.toEntity() = SegmentEntity(
        id = id,
        startKm = startKm,
        endKm = endKm,
        startedAt = startedAt,
        endedAt = endedAt,
        observed = observed,
        personId = personId,
        attribution = attribution.name,
        placeName = placeName,
        rearDoor = door?.name,
        returnLeg = returnLeg,
    )

    private fun android.content.SharedPreferences.floatOrNull(key: String) = if (contains(key)) getFloat(key, 0f) else null

    private fun android.content.SharedPreferences.intOrNull(key: String) = if (contains(key)) getInt(key, 0) else null

    private fun android.content.SharedPreferences.Editor.putFloatOrRemove(key: String, value: Float?) {
        if (value != null) putFloat(key, value) else remove(key)
    }

    private companion object {
        const val KEY_SEEDED = "seeded"
        const val KEY_FIRST_WEEK = "first_week"
        const val KEY_RECORDING = "recording"
        const val KEY_VOICE = "voice"
        const val KEY_PLACE = "place"
        const val KEY_LAST_KM = "last_km"
        const val KEY_LAST_SEEN = "last_seen"
        const val KEY_TRIP_KM = "trip_km"
        const val KEY_TRIP_AT = "trip_at"
        const val KEY_ENERGY = "energy"
        const val KEY_TANK_KM = "tank_km"
        const val KEY_TANK_AT = "tank_at"
        const val KEY_TANK_PERCENT = "tank_percent"
        const val KEY_RECORDED_SPEECH_DAY = "recorded_speech_day"
        const val KEY_TANK_MIN = "tank_min"
        const val KEY_REFILL_PENDING = "refill_pending"
        const val MIN_NEW_KM = 0.05f
    }
}
