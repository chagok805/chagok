package dev.chagok.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.ErrorCode
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.chagok.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Doors the ledger cares about. Trunk and hood are not boarding signals. */
enum class DoorSide(val value: DoorValue) {
    FRONT_LEFT(DoorValue.ROW_1_LEFT),
    FRONT_RIGHT(DoorValue.ROW_1_RIGHT),
    REAR_LEFT(DoorValue.ROW_2_LEFT),
    REAR_RIGHT(DoorValue.ROW_2_RIGHT),
}

/** Latest vehicle readings. Null means unknown or unsupported on this vehicle. */
data class VehicleSnapshot(
    val odometerKm: Float? = null,
    /** False once this vehicle said it has no readable odometer; nothing can be recorded then. */
    val odometerSupported: Boolean = true,
    val fuelPercent: Int? = null,
    val batteryPercent: Int? = null,
    val openDoors: Set<DoorSide> = emptySet(),
    /**
     * True only when the SDK reports the front passenger seat as vacant. Vehicle 2.3.2 converts the
     * raw value with `raw == 1`, so `false` means occupied or unknown and proves nothing.
     */
    val passengerSeatVacant: Boolean = false,
)

/**
 * Owns the Vehicle SDK instance. Follows capability -> get -> register for every signal, and treats
 * a missing capability as "this vehicle does not have it": the ledger must keep working without it.
 */
class VehicleGateway(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cabinPrefs = context.applicationContext.getSharedPreferences("cabin", Context.MODE_PRIVATE)
    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private var started = false
    private var firstOdometerAt = 0L
    /** False while the service may still replay an old level, which could look like a refill. */
    @Volatile private var energySettled = false

    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) = setOdometer(value)
        override fun onFailed(e: Exception) = logFailure("odometer listener", e)
    }
    private val fuelListener = object : FuelPercentListener {
        override fun onFuelPercentUpdated(value: Int?) {
            if (energySettled) setFuel(value)
        }
        override fun onFailed(e: Exception) = logFailure("fuel listener", e)
    }
    private val batteryListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
            if (energySettled) setBattery(value)
        }
        override fun onFailed(e: Exception) = logFailure("battery listener", e)
    }
    private val seatListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(occupancy: Boolean?) = setPassengerSeat(occupancy)
        override fun onFailed(e: Exception) = logFailure("seat listener", e)
    }
    private val doorListeners = mutableMapOf<DoorSide, DoorPositionListener>()

    /** Process-lifetime connection; call once from Application.onCreate(). */
    fun connect() = vehicle.initialize()

    /** Subscribes to every supported signal. Idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch { startOdometer() }
        // One energy source only: a hybrid reporting both must not look like a refill between them.
        scope.launch { if (!startFuel()) startBattery() }
        scope.launch { restoreInterruptedCabin() }
        scope.launch { startDoors() }
        scope.launch { startPassengerSeat() }
    }

    private class SavedLight(val index: Int, val area: SeatArea, val switch: LightSwitch?, val color: Int, val brightness: Int?)

    private var cabinJob: Job? = null
    private var restoreJob: Job? = null
    private var savedLights: List<SavedLight>? = null

    private suspend fun cabinAreas(): List<SeatArea>? {
        val light = vehicle.getLight()
        return awaitCapability<List<SeatArea>>("ambient color") { done, fail ->
            light.checkAmbientLightBrightnessColorCapabilities({ _, a -> done(a) }, fail)
        }
    }

    /**
     * Warm cabin light for "그날", on vehicles that have it: the light gathers slowly while the number
     * counts up. What the family had is saved before anything changes, on disk as well, so that
     * [restoreCabin] can put it back even after a failure or a process death. An area that could not
     * be read is left alone. The scene works without any of this.
     */
    fun warmCabin() {
        restoreJob?.cancel()
        if (cabinJob?.isActive == true) return
        cabinJob = scope.launch {
            runCatching {
                val light = vehicle.getLight()
                val areas = cabinAreas() ?: return@launch
                val bounds = awaitCapability<Map<SeatArea, AreaBounds<Int>>>("ambient brightness") { done, fail ->
                    light.checkAmbientLightBrightnessCapabilities({ _, b -> done(b) }, fail)
                }.orEmpty()
                val switches = awaitCapability<Map<SeatArea, List<LightSwitch>>>("ambient switch") { done, fail ->
                    light.checkAmbientLightSwitchCapabilities({ _, s -> done(s) }, fail)
                }.orEmpty()
                val saved = savedLights ?: areas.mapIndexedNotNull { index, area ->
                    val color = awaitRead<Int> { d, f -> light.getAmbientLightBrightnessColor(area, d, f) } ?: return@mapIndexedNotNull null
                    SavedLight(
                        index,
                        area,
                        if (LightSwitch.ON in switches[area].orEmpty()) awaitRead { d, f -> light.getAmbientLightSwitch(area, d, f) } else null,
                        color,
                        if (area in bounds) awaitRead { d, f -> light.getAmbientLightBrightness(area, d, f) } else null,
                    )
                }.also {
                    savedLights = it
                    saveCabin(it)
                }
                for (s in saved) {
                    if (s.switch != null && s.switch != LightSwitch.ON) {
                        light.setAmbientLightSwitch(s.area, LightSwitch.ON, { _, _ -> }, { logFailure("ambient switch", it) })
                    }
                    light.setAmbientLightBrightnessColor(s.area, WARM_LIGHT, { _, _ -> }, { logFailure("ambient color", it) })
                }
                for (step in 1..WARM_STEPS) {
                    for (s in saved) {
                        if (s.brightness == null) continue
                        val range = bounds[s.area] ?: continue
                        val level = range.minValue + (range.maxValue - range.minValue) * step * WARM_PERCENT / (WARM_STEPS * 100)
                        light.setAmbientLightBrightness(s.area, level, { _, _ -> }, { logFailure("ambient brightness", it) })
                    }
                    delay(WARM_STEP_MS)
                }
            }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "cabin light failed: ${it.javaClass.simpleName}")
            }
        }
    }

    /**
     * Puts the light back. Safe to call at any time, and always called when the car leaves Park. The
     * saved values are kept until every write succeeded; the next Park tries again.
     */
    fun restoreCabin() {
        cabinJob?.cancel()
        cabinJob = null
        val saved = savedLights ?: return
        if (restoreJob?.isActive == true) return
        restoreJob = scope.launch {
            val light = vehicle.getLight()
            var ok = true
            for (s in saved) {
                // The SDK reads the color back without alpha and wants it opaque.
                ok = awaitWrite { d, f -> light.setAmbientLightBrightnessColor(s.area, s.color or OPAQUE, { _, _ -> d() }, f) } && ok
                if (s.brightness != null) ok = awaitWrite { d, f -> light.setAmbientLightBrightness(s.area, s.brightness, { _, _ -> d() }, f) } && ok
                if (s.switch != null && s.switch != LightSwitch.ON) ok = awaitWrite { d, f -> light.setAmbientLightSwitch(s.area, s.switch, { _, _ -> d() }, f) } && ok
            }
            if (ok) {
                savedLights = null
                cabinPrefs.edit().clear().apply()
            }
        }
    }

    private fun saveCabin(saved: List<SavedLight>) {
        val text = saved.joinToString(";") { "${it.index},${it.switch?.name.orEmpty()},${it.color},${it.brightness ?: ""}" }
        cabinPrefs.edit().putString(KEY_SAVED_LIGHTS, text).apply()
    }

    /** A process that died in the middle of a scene left the light warm. */
    private suspend fun restoreInterruptedCabin() {
        val text = cabinPrefs.getString(KEY_SAVED_LIGHTS, null) ?: return
        val areas = cabinAreas() ?: return
        savedLights = text.split(";").mapNotNull { row ->
            val parts = row.split(",")
            val index = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            SavedLight(
                index,
                areas.getOrNull(index) ?: return@mapNotNull null,
                LightSwitch.entries.firstOrNull { it.name == parts.getOrNull(1) },
                parts.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null,
                parts.getOrNull(3)?.toIntOrNull(),
            )
        }
        restoreCabin()
    }

    private suspend fun awaitWrite(write: (done: () -> Unit, onFailed: (Exception) -> Unit) -> Unit): Boolean =
        withTimeoutOrNull(CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                runCatching {
                    write(
                        { if (cont.isActive) cont.resume(true) },
                        { e ->
                            logFailure("cabin write", e)
                            if (cont.isActive) cont.resume(false)
                        },
                    )
                }.onFailure { if (cont.isActive) cont.resume(false) }
            }
        } ?: false

    private suspend fun <T> awaitRead(read: (onComplete: (T?) -> Unit, onFailed: (Exception) -> Unit) -> Unit): T? =
        withTimeoutOrNull(CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                read(
                    { value -> if (cont.isActive) cont.resume(value) },
                    { e ->
                        logFailure("read", e)
                        if (cont.isActive) cont.resume(null)
                    },
                )
            }
        }

    private suspend fun startOdometer() {
        val odometer = vehicle.getOdometer()
        awaitCapability<CapabilityConfig>("odometer") { done, fail -> odometer.checkCurrentDistanceDrivenCapabilities(done, fail) } ?: run {
            _snapshot.update { it.copy(odometerSupported = false) }
            return
        }
        odometer.getCurrentDistanceDriven({ setOdometer(it) }, { logFailure("get odometer", it) })
        odometer.registerDistanceDriven(odometerListener)
    }

    private suspend fun startFuel(): Boolean {
        val fuel = vehicle.getFuel()
        awaitCapability<CapabilityConfig>("fuel") { done, fail -> fuel.checkFuelPercentCapabilities(done, fail) } ?: return false
        fuel.registerFuelPercent(fuelListener)
        delay(ENERGY_SETTLE_MS)
        val percent = awaitRead<Int> { done, failed -> fuel.getFuelPercent(done, failed) }
        energySettled = true
        setFuel(percent)
        return true
    }

    private suspend fun startBattery() {
        val battery = vehicle.getEvBattery()
        awaitCapability<CapabilityConfig>("battery") { done, fail ->
            battery.checkEvBatteryStateOfChargeCapabilities(done, fail)
        } ?: return
        battery.registerEvBatteryStateOfCharge(batteryListener)
        delay(ENERGY_SETTLE_MS)
        val percent = awaitRead<Int> { done, failed -> battery.getEvBatteryStateOfCharge(done, failed) }
        energySettled = true
        setBattery(percent)
    }

    private suspend fun startDoors() {
        val door = vehicle.getDoor()
        val areas = awaitCapability<Map<DoorArea, AreaBounds<Int>>>("door") { done, fail ->
            door.checkDoorPositionCapabilities(done, fail)
        } ?: return
        for (side in DoorSide.entries) {
            val area = areas.keys.firstOrNull { side.value in it.values } ?: continue
            val closed = areas.getValue(area).minValue
            val listener = object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) = setDoor(side, pos?.let { it > closed })
                override fun onFailed(e: Exception) = logFailure("door $side listener", e)
            }
            doorListeners[side] = listener
            door.getCurrentDoorPosition(area, { pos -> setDoor(side, pos?.let { it > closed }) }, { logFailure("get door $side", it) })
            door.registerDoorPosition(area, listener)
        }
    }

    private suspend fun startPassengerSeat() {
        val seat = vehicle.getSeat()
        val areas = awaitCapability<List<SeatArea>>("seat") { done, fail ->
            seat.checkSeatOccupancyCapabilities(done, fail)
        } ?: return
        val passenger = areas.firstOrNull { SeatValue.ROW_1_RIGHT in it.values } ?: return
        seat.getCurrentSeatOccupancy(passenger, { setPassengerSeat(it) }, { logFailure("get seat", it) })
        seat.registerSeatOccupancy(passenger, seatListener)
    }

    private fun setOdometer(km: Float?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "odometer=$km")
        // An odometer only counts up, but the service can replay an older value right after binding.
        // A lower value later than that is real (a reset Virtual Console), and the ledger decides.
        if (km == null) return
        val now = SystemClock.elapsedRealtime()
        if (firstOdometerAt == 0L) firstOdometerAt = now
        val replayPossible = now - firstOdometerAt < REPLAY_WINDOW_MS
        _snapshot.update { s -> if (!replayPossible || (s.odometerKm ?: 0f) <= km) s.copy(odometerKm = km) else s }
    }

    private fun setFuel(percent: Int?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "fuel=$percent")
        if (percent != null) _snapshot.update { it.copy(fuelPercent = percent) }
    }

    private fun setBattery(percent: Int?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "battery=$percent")
        if (percent != null) _snapshot.update { it.copy(batteryPercent = percent) }
    }

    private fun setDoor(side: DoorSide, open: Boolean?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "door $side open=$open")
        if (open == null) return
        _snapshot.update {
            it.copy(openDoors = if (open) it.openDoors + side else it.openDoors - side)
        }
    }

    private fun setPassengerSeat(rawVacant: Boolean?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "passenger seat vacant=$rawVacant")
        _snapshot.update { it.copy(passengerSeatVacant = rawVacant == true) }
    }

    /**
     * Runs a capability check, retrying while the vehicle service is still connecting.
     * Returns null when the vehicle does not support the signal or the check keeps failing.
     */
    private suspend fun <T : Any> awaitCapability(
        name: String,
        check: (onComplete: (T) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
    ): T? {
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = withTimeoutOrNull(CALL_TIMEOUT_MS) {
                suspendCancellableCoroutine<Result<T>> { cont ->
                    check(
                        { value -> if (cont.isActive) cont.resume(Result.success(value)) },
                        { e -> if (cont.isActive) cont.resume(Result.failure(e)) },
                    )
                }
            }
            val value = result?.getOrNull()
            if (value != null) {
                Log.d(TAG, "$name capability=$value")
                return value
            }
            val error = result?.exceptionOrNull()
            val code = error?.vehicleErrorCode()
            val retryable = error == null || code == ErrorCode.ERROR_SERVICE_CONNECTION_FAILURE || code == ErrorCode.ERROR_INTERNAL_SERVICE
            if (!retryable) {
                Log.i(TAG, "$name not available: ${code ?: error?.javaClass?.simpleName}")
                return null
            }
            delay(RETRY_DELAY_MS * (attempt + 1))
        }
        Log.w(TAG, "$name capability check gave up")
        return null
    }

    private fun logFailure(where: String, e: Exception) {
        Log.w(TAG, "$where failed: ${e.vehicleErrorCode() ?: e.javaClass.simpleName}")
    }

    private companion object {
        /** The dusk of the "그날" scene. Alpha is fixed to opaque by the SDK. */
        const val WARM_LIGHT = 0xFFFF9E5E.toInt()
        const val WARM_PERCENT = 85
        const val OPAQUE = 0xFF000000.toInt()
        const val KEY_SAVED_LIGHTS = "saved_lights"
        const val REPLAY_WINDOW_MS = 15_000L
        /**
         * Right after binding, the service can send the level from before the app stopped, either
         * before or after the current one. Measured to arrive within milliseconds.
         */
        const val ENERGY_SETTLE_MS = 3_000L
        const val WARM_STEPS = 6
        const val WARM_STEP_MS = 700L
        const val TAG = "ChagokVehicle"
        const val MAX_ATTEMPTS = 5
        const val RETRY_DELAY_MS = 400L
        const val CALL_TIMEOUT_MS = 3_000L
    }
}
