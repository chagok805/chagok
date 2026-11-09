package dev.chagok.app.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The AAOS signals the Vehicle SDK does not expose: the gear, and whether the platform asks for a
 * distraction-optimized screen. The activity is distraction optimized and shows only its resting
 * screen while restricted. Restrictions fail closed: until the platform says otherwise, and again
 * whenever the car service goes away, it rests.
 */
class CarSignals(private val context: Context) {

    data class State(
        val gear: String? = null,
        val uxKnown: Boolean = false,
        val requiresDistractionOptimization: Boolean = true,
    )

    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    /** Asked for by [start]; registration waits for the car service if it is not there yet. */
    private var wanted = false
    private var registered = false
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val propCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val available = value.status == CarPropertyValue.STATUS_AVAILABLE
            if (value.propertyId == VehiclePropertyIds.GEAR_SELECTION) {
                _state.update { it.copy(gear = (value.value as? Int)?.takeIf { available }?.let(VehicleGear::toString)) }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "property error id=$propertyId area=$areaId")
            if (propertyId == VehiclePropertyIds.GEAR_SELECTION) _state.update { it.copy(gear = null) }
        }
    }

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { onUx(it) }

    /**
     * Process-lifetime connection; call once from Application.onCreate(). The lifecycle listener hears
     * the car service go and come back, so a restart can never leave a stale "not driving" behind.
     */
    fun connect() {
        car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready -> onCarService(c, ready) }
    }

    /** Subscribes to the gear and the driving restrictions, now or once the car service is ready. */
    fun start() {
        wanted = true
        register()
    }

    private fun onCarService(c: Car, ready: Boolean) {
        if (!ready) {
            Log.w(TAG, "car service gone; resting until it is back")
            props = null
            ux = null
            registered = false
            // What the service said last no longer holds: rest, gear unknown.
            _state.value = State()
            return
        }
        // Managers from before a restart are dead: take new ones every time the service is ready.
        props = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        registered = false
        register()
    }

    @Suppress("DEPRECATION") // see registerCallback note below
    private fun register() {
        if (!wanted || registered) return
        val restrictions = ux
        val properties = props
        if (restrictions == null && properties == null) return
        restrictions?.let { m ->
            m.currentCarUxRestrictions?.let(::onUx)
            m.registerListener(uxListener)
        } ?: Log.w(TAG, "UX restrictions not available; staying restricted")
        // registerCallback is deprecated in the API 35 stubs, but its replacement
        // (subscribePropertyEvents) does not exist on the API 34 emulator. Keep it.
        val gearOk = properties?.registerCallback(propCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE) == true
        if (!gearOk) Log.w(TAG, "GEAR_SELECTION not available")
        registered = true
    }

    private fun onUx(r: CarUxRestrictions) {
        _state.update { it.copy(uxKnown = true, requiresDistractionOptimization = r.isRequiresDistractionOptimization) }
    }

    private companion object {
        const val TAG = "CarSignals"
    }
}
