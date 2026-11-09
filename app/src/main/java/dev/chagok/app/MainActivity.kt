package dev.chagok.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chagok.app.data.SpecialDay
import dev.chagok.app.ledger.Person
import dev.chagok.app.ledger.Segment
import dev.chagok.app.ledger.Settlement
import dev.chagok.app.ledger.Settlements
import dev.chagok.app.ledger.Tank
import dev.chagok.app.ledger.WaitEntry
import dev.chagok.app.ui.Chagok
import dev.chagok.app.ui.DayHighlight
import dev.chagok.app.ui.DaySceneScreen
import dev.chagok.app.ui.DaysScreen
import dev.chagok.app.ui.DrivingScreen
import dev.chagok.app.ui.FillRange
import dev.chagok.app.ui.HomeActions
import dev.chagok.app.ui.HomeScreen
import dev.chagok.app.ui.MemoControls
import dev.chagok.app.ui.PeopleScreen
import dev.chagok.app.ui.PleosTheme
import dev.chagok.app.ui.SettingsScreen
import dev.chagok.app.ui.SettlementPendingScreen
import dev.chagok.app.ui.SettlementScreen
import dev.chagok.app.ui.Tab
import dev.chagok.app.ui.blocksOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything here happens in Park. The activity is distraction optimized only to show its resting
 * screen while the platform restricts the display; the service keeps recording behind it.
 */
class MainActivity : ComponentActivity() {

    private enum class Screen { HOME, SETTLEMENT, PEOPLE, DAYS, DAY_SCENE, SETTINGS }

    private companion object {
        const val WEEK_MS = 7 * 24 * 60 * 60_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PleosTheme { App() } }
    }

    override fun onStart() {
        super.onStart()
        if ((application as ChagokApp).repository.recordingEnabled) RecorderService.start(this)
    }

    @Composable
    private fun App() {
        val app = application as ChagokApp
        val repository = app.repository
        val recorder = app.recorder
        val scope = rememberCoroutineScope()

        val snapshot by app.vehicle.snapshot.collectAsStateWithLifecycle()
        // Null until the first read: "no people yet" must not flash on screen, or a scene close itself,
        // only because the database has not answered.
        val loadedPeople by repository.people.collectAsStateWithLifecycle<List<Person>?>(null)
        val loadedSegments by repository.segments.collectAsStateWithLifecycle<List<Segment>?>(null)
        val loadedWaits by repository.waits.collectAsStateWithLifecycle<List<WaitEntry>?>(null)
        val loadedDays by repository.days.collectAsStateWithLifecycle<List<SpecialDay>?>(null)
        val loadedTanks by repository.tanks.collectAsStateWithLifecycle<List<Tank>?>(null)
        val parked by recorder.parked.collectAsStateWithLifecycle()
        val tank by recorder.tank.collectAsStateWithLifecycle()
        val prompts by recorder.prompts.collectAsStateWithLifecycle()
        val notice by recorder.latestNotice.collectAsStateWithLifecycle()
        val answered by recorder.answered.collectAsStateWithLifecycle()
        val signals by app.signals.state.collectAsStateWithLifecycle()
        // Fails closed: until the platform says the display is free, the app rests.
        val restricted = signals.requiresDistractionOptimization
        val keyboard = LocalSoftwareKeyboardController.current

        var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
        var closedTankId by rememberSaveable { mutableLongStateOf(-1L) }
        var sceneDayId by rememberSaveable { mutableLongStateOf(-1L) }
        var seeded by remember { mutableStateOf(repository.isSeeded) }
        var recording by remember { mutableStateOf(repository.recordingEnabled) }
        var voice by remember { mutableStateOf(repository.voiceEnabled) }
        var place by remember { mutableStateOf(repository.placeEnabled) }
        var seedMessage by remember { mutableStateOf<String?>(null) }

        val memos = app.memos
        var recordingDayId by remember { mutableStateOf<Long?>(null) }
        var memoVersion by remember { mutableStateOf(0) }
        var askedFor by rememberSaveable { mutableLongStateOf(-1L) }
        var micDenied by rememberSaveable { mutableStateOf(false) }
        // The permission answer can come back after the car has started moving: read the state then.
        val canRecord by rememberUpdatedState(parked && !restricted)
        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val dayId = askedFor
            askedFor = -1L
            micDenied = !granted
            if (granted && canRecord && dayId >= 0 && memos.startRecording(dayId) { recordingDayId = null; memoVersion++ }) {
                recordingDayId = dayId
            }
        }
        fun record(dayId: Long) {
            val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                askedFor = dayId
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                micDenied = false
                if (canRecord && memos.startRecording(dayId) { recordingDayId = null; memoVersion++ }) recordingDayId = dayId
            }
        }

        // The microphone never stays open once the car is not parked.
        LaunchedEffect(parked) {
            if (!parked) {
                memos.stopRecording(keep = false)
                recordingDayId = null
            }
        }
        // Nothing plays, records or waits for typing behind the resting screen.
        LaunchedEffect(restricted) {
            if (restricted) {
                keyboard?.hide()
                memos.stopRecording(keep = false)
                recordingDayId = null
                recorder.stopScene()
                // The scene is never picked up again by itself once the car stops.
                if (screen == Screen.DAY_SCENE) screen = Screen.DAYS
            }
        }

        val people = loadedPeople
        val segments = loadedSegments
        val waits = loadedWaits
        val days = loadedDays
        val tanks = loadedTanks
        val km = snapshot.odometerKm
        val range = tank

        // Leaving the screen ends anything playing or recording: a parent's voice never plays on
        // behind another app, and the microphone never stays open.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        memos.stopRecording(keep = false)
                        recordingDayId = null
                        recorder.stopScene()
                    }
                    // Back from the system settings, where the microphone may have been allowed.
                    Lifecycle.Event.ON_START -> if (
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    ) {
                        micDenied = false
                    }
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                memos.stopRecording(keep = false)
                recorder.stopScene()
            }
        }

        BackHandler {
            when (screen) {
                Screen.HOME -> moveTaskToBack(true)
                Screen.DAY_SCENE -> screen = Screen.DAYS
                Screen.PEOPLE -> screen = Screen.SETTINGS
                else -> screen = Screen.HOME
            }
        }

        val running: Settlement? = remember(segments, people, waits, km, range) {
            if (people != null && segments != null && waits != null && km != null && range != null) {
                Settlements.of(range.startKm, km, segments, people, waits.filter { it.startedAt >= range.startAt })
            } else {
                null
            }
        }

        if (restricted) {
            val familyIds = people.orEmpty().filterNot { it.isSelf }.map { it.id }
            DrivingScreen(running?.let { blocksOf(it, familyIds, Chagok.colors) }.orEmpty(), recording)
            return
        }
        if (people == null || segments == null || waits == null || days == null || tanks == null) return

        val now = System.currentTimeMillis()
        val thisWeek = remember(segments, people, waits) { Settlements.familyTime(now - WEEK_MS, now, segments, people, waits) }
        val weekWait = remember(segments, people, waits) {
            Settlements.familyWaits(waits, segments, people).filter { it.startedAt >= now - WEEK_MS }.sumOf { it.minutes }
        }
        val lastTank = tanks.firstOrNull { it.endKm != null }
        val lastFill = lastTank?.let { t ->
            val toAt = t.endedAt ?: Long.MAX_VALUE
            Settlements.of(t.startKm, t.endKm ?: t.startKm, segments, people, waits.filter { it.startedAt in t.startedAt..toAt })
        }
        // Reading a recording's length opens the file: never on the main thread.
        val memoSeconds by produceState(emptyMap<Long, Int?>(), memoVersion, days) {
            value = withContext(Dispatchers.IO) { days.associate { it.id to memos.seconds(it.id) } }
        }

        fun goTab(tab: Tab) {
            screen = when (tab) {
                Tab.HOME -> Screen.HOME
                Tab.SETTLEMENT -> {
                    // The tab shows the last fill that ended; before the first refill, the running one.
                    closedTankId = lastTank?.id ?: -1L
                    Screen.SETTLEMENT
                }
                Tab.DAYS -> Screen.DAYS
                Tab.SETTINGS -> Screen.SETTINGS
            }
        }

        when (screen) {
            Screen.HOME -> HomeScreen(
                settlement = running,
                range = if (km != null && range != null) FillRange(range.startKm, km) else null,
                lastFill = lastFill,
                weekMinutes = thisWeek.minutes,
                weekWaitMinutes = weekWait,
                people = people,
                segments = segments,
                prompts = prompts,
                notice = notice,
                answered = answered,
                day = days.mapNotNull { d ->
                    people.firstOrNull { it.id == d.personId }?.let { p -> DayHighlight(d, p, Settlements.confirmedKm(p.id, segments, d.fromMillis)) }
                }.sortedByDescending { it.km }.firstOrNull(),
                seeded = seeded,
                odometerAvailable = snapshot.odometerSupported,
                actions = HomeActions(
                    onConfirm = recorder::confirm,
                    onLeave = recorder::leaveUnclassified,
                    onOtherDriver = recorder::markOtherDriver,
                    onDismiss = recorder::dismiss,
                    onAcceptGuess = recorder::acceptGapGuess,
                    onUndo = recorder::undo,
                    onOpenSettlement = { prompt ->
                        closedTankId = prompt?.tank?.id ?: -1L
                        prompt?.let { recorder.dismiss(it.id) }
                        screen = Screen.SETTLEMENT
                    },
                    onOpenPeople = { screen = Screen.PEOPLE },
                    onOpenDays = { screen = Screen.DAYS },
                    onPlayDay = { day ->
                        sceneDayId = day.id
                        screen = Screen.DAY_SCENE
                    },
                    onOpenRecord = recorder::reopen,
                    onTab = ::goTab,
                ),
            )

            Screen.SETTLEMENT -> {
                val closed = tanks.firstOrNull { it.id == closedTankId }
                val startKm = closed?.startKm ?: range?.startKm
                val endKm = closed?.endKm ?: km
                if (startKm == null || endKm == null) {
                    // Nothing to settle before the first odometer reading: say so instead of not reacting.
                    SettlementPendingScreen(seeded = seeded, odometerAvailable = snapshot.odometerSupported, onTab = ::goTab)
                } else {
                    val fromAt = closed?.startedAt ?: range?.startAt ?: 0L
                    val toAt = closed?.endedAt ?: Long.MAX_VALUE
                    val fillWaits = waits.filter { it.startedAt in fromAt..toAt }
                    SettlementScreen(
                        settlement = Settlements.of(startKm, endKm, segments, people, fillWaits),
                        people = people,
                        closed = closed != null,
                        familyMinutes = Settlements.familyTime(fromAt, minOf(toAt, now), segments, people, waits).minutes,
                        seeded = seeded,
                        onConfirmAll = { recorder.confirmSuggestions(startKm, endKm) },
                        onTab = ::goTab,
                    )
                }
            }

            Screen.PEOPLE -> PeopleScreen(
                people = people,
                seeded = seeded,
                onAdd = { name -> scope.launch { repository.addPerson(name, System.currentTimeMillis()) } },
                onDelete = recorder::deletePerson,
                onBack = { screen = Screen.SETTINGS },
                onTab = ::goTab,
            )

            Screen.DAYS -> {
                // Leaving this screen ends a recording: it must never run on where nobody can see it.
                DisposableEffect(Unit) {
                    onDispose {
                        memos.stopRecording(keep = false)
                        recordingDayId = null
                    }
                }
                DaysScreen(
                    days = days,
                    people = people,
                    seeded = seeded,
                    confirmedKm = { personId, from -> Settlements.confirmedKm(personId, segments, from) },
                    onAdd = { personId, title, from -> scope.launch { repository.addDay(personId, title, from) } },
                    onDelete = { id ->
                        memos.delete(id)
                        memoVersion++
                        scope.launch { repository.deleteDay(id) }
                    },
                    memo = MemoControls(
                        exists = { memoVersion; memos.exists(it) },
                        seconds = { memoSeconds[it] },
                        recordingDayId = recordingDayId,
                        micDenied = micDenied,
                        onOpenAppSettings = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                        },
                        onRecord = ::record,
                        onStop = { dayId ->
                            memos.stopRecording(keep = true, dayId = dayId)
                            recordingDayId = null
                            memoVersion++
                        },
                        onDelete = { dayId ->
                            memos.delete(dayId)
                            memoVersion++
                        },
                    ),
                    onPlay = { day ->
                        sceneDayId = day.id
                        screen = Screen.DAY_SCENE
                    },
                    onOpenPeople = { screen = Screen.PEOPLE },
                    onTab = ::goTab,
                )
            }

            Screen.DAY_SCENE -> {
                val day = days.firstOrNull { it.id == sceneDayId }
                val person = day?.let { d -> people.firstOrNull { it.id == d.personId } }
                if (day == null || person == null) {
                    screen = Screen.DAYS
                } else {
                    DisposableEffect(Unit) { onDispose { recorder.stopScene() } }
                    DaySceneScreen(
                        person = person,
                        day = day,
                        km = Settlements.confirmedKm(person.id, segments, day.fromMillis),
                        hasMemo = memos.exists(day.id),
                        memoSeconds = memoSeconds[day.id],
                        play = { line -> recorder.playScene(line, day.id) },
                        stop = recorder::stopScene,
                        onClose = { screen = Screen.DAYS },
                        seeded = seeded,
                    )
                }
            }

            Screen.SETTINGS -> SettingsScreen(
                people = people,
                kmThisFill = running?.let { r ->
                    r.family.associate { it.person.id to it.km } + listOfNotNull(people.firstOrNull { it.isSelf }?.let { it.id to r.selfKm })
                }.orEmpty(),
                recording = recording,
                voice = voice,
                place = place,
                seeded = seeded,
                seedMessage = seedMessage,
                onOpenPeople = { screen = Screen.PEOPLE },
                onRecording = {
                    repository.recordingEnabled = it
                    recording = it
                    if (it) RecorderService.start(this) else RecorderService.stop(this)
                },
                onVoice = {
                    repository.voiceEnabled = it
                    voice = it
                },
                onPlace = {
                    repository.placeEnabled = it
                    place = it
                },
                onSeed = {
                    recorder.seedDemo { ok ->
                        seeded = ok || seeded
                        seedMessage = if (ok) "시연 데이터를 넣었어요." else "주행거리를 읽고 있어요. 잠시 뒤 다시 눌러 주세요."
                    }
                },
                onClear = {
                    recorder.clearAll()
                    seeded = false
                    seedMessage = null
                },
                onTab = ::goTab,
            )
        }
    }
}
