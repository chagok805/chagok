package dev.chagok.app

import android.app.Application
import dev.chagok.app.data.LedgerRepository
import dev.chagok.app.vehicle.CarSignals
import dev.chagok.app.vehicle.NaviGateway
import dev.chagok.app.vehicle.VehicleGateway
import dev.chagok.app.voice.Memos
import dev.chagok.app.voice.Speaker

/** Owns the vehicle connections and the ledger for the whole process; screens only observe them. */
class ChagokApp : Application() {

    lateinit var vehicle: VehicleGateway
        private set
    lateinit var repository: LedgerRepository
        private set
    lateinit var recorder: Recorder
        private set
    lateinit var memos: Memos
        private set
    lateinit var signals: CarSignals
        private set

    override fun onCreate() {
        super.onCreate()
        vehicle = VehicleGateway(this).also { it.connect() }
        signals = CarSignals(this).also { it.connect() }
        val navi = NaviGateway(this).also { it.connect() }
        val speaker = Speaker(this).also { it.connect() }
        repository = LedgerRepository(this)
        memos = Memos(this)
        recorder = Recorder(repository, vehicle, signals, navi, speaker, memos)
        PropertyProbe.dump(this)
        vehicle.start()
        signals.start()
        // Recording runs whenever the process does; the service only keeps the process alive.
        recorder.start()
    }
}
