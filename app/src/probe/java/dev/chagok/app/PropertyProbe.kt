package dev.chagok.app

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/**
 * `probe` build type only: logs every AAOS vehicle property this app can access, with its
 * current value. Run: ./gradlew installProbe, launch the app, adb logcat -d -s PropertyProbe
 */
object PropertyProbe {
    private const val TAG = "PropertyProbe"

    fun dump(context: Context) = Thread { dumpBlocking(context.applicationContext) }.start()

    private fun dumpBlocking(context: Context) {
        val car = Car.createCar(context) ?: return
        try {
            val pm = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            val configs = pm.propertyList.sortedBy { VehiclePropertyIds.toString(it.propertyId) }
            Log.i(TAG, "accessible properties: ${configs.size}")
            for (cfg in configs) {
                val id = cfg.propertyId
                val name = VehiclePropertyIds.toString(id)
                val value = runCatching {
                    val area = cfg.areaIds.firstOrNull() ?: 0
                    val v = pm.getProperty<Any>(id, area)?.value
                    if (v is Array<*>) v.contentToString() else v.toString()
                }.getOrElse { "<${it.javaClass.simpleName}>" }
                Log.i(TAG, "%s 0x%08x areas=%d access=%d value=%s".format(name, id, cfg.areaIds.size, cfg.access, value))
            }
        } finally {
            car.disconnect()
        }
    }
}
