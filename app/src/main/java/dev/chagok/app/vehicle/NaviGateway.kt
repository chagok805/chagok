package dev.chagok.app.vehicle

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import android.content.Context
import android.util.Log
import dev.chagok.app.BuildConfig
import dev.chagok.app.ledger.Places
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Names the place a stretch ended at, when the vehicle has a built-in navigation. Vehicles without
 * one simply return null and suggestions fall back to time patterns.
 */
class NaviGateway(context: Context) {

    private val app = context.applicationContext
    @Volatile private var navi = NaviHelper(app)
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retries = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var retriesLeft = MAX_RETRIES
    @Volatile private var connected = false
    @Volatile private var arrived: String? = null
    @Volatile private var arrivedAt = 0L
    @Volatile private var location: CompletableDeferred<String?>? = null

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            arrived = Places.storableName(destinationArrivedInfo.name)
            arrivedAt = System.currentTimeMillis()
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val name = Places.neighborhoodOf(currentLocationInfo.address)
            if (BuildConfig.DEBUG) Log.d(TAG, "location: $name")
            location?.complete(name)
        }
    }

    fun connect() {
        runCatching {
            navi.initialize(
                onConnected = {
                    connected = true
                    navi.addListener(listener)
                },
                onFailed = { e -> retryLater(e) },
            )
        }.onFailure { retryLater(it) }
    }

    /**
     * When the car boots, this process can start before the navigation app does, and a helper whose
     * first connection failed stays disconnected. A new one has to try again. A vehicle with no
     * navigation fails every time, so this stops after a few minutes.
     */
    private fun retryLater(cause: Throwable) {
        Log.i(TAG, "navigation not available: ${cause.javaClass.simpleName}")
        retries.launch {
            if (retriesLeft-- <= 0) return@launch
            delay(RETRY_MS)
            renew()
        }
    }

    private fun renew() {
        connected = false
        runCatching { navi.release() }
        navi = NaviHelper(app)
        connect()
    }

    /** A new stretch begins, so the last arrival no longer says where it ends. */
    fun forgetArrival() {
        arrived = null
    }

    /**
     * The name the navigation gave on arrival, or else the neighborhood. A destination alone says
     * nothing, because the car may be stopping on the way there, and most daily drives have none.
     * Coordinates and the full address are dropped here and never stored.
     */
    suspend fun placeName(now: Long): String? =
        arrived?.takeIf { now - arrivedAt <= ARRIVAL_WINDOW_MS } ?: neighborhood()

    private suspend fun neighborhood(): String? {
        if (!connected) return null
        val waiting = CompletableDeferred<String?>()
        location = waiting
        // Off the caller's thread, so a stalled navigation app can only cost the wait below.
        requests.launch { runCatching { navi.getCurrentLocationInfo() }.onFailure { waiting.complete(null) } }
        return try {
            withTimeoutOrNull(LOCATION_WAIT_MS) { waiting.await() }
        } finally {
            // The callback carries no request id, so a late one must not answer the next wait.
            location = null
            // Connected but silent: the docs leave reconnection undefined and advise starting over.
            if (!waiting.isCompleted) retries.launch { renew() }
        }
    }

    private companion object {
        const val TAG = "ChagokNavi"
        const val ARRIVAL_WINDOW_MS = 5 * 60_000L
        const val LOCATION_WAIT_MS = 800L
        const val RETRY_MS = 30_000L
        const val MAX_RETRIES = 10
    }
}
