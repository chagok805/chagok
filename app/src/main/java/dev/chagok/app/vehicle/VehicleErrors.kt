package dev.chagok.app.vehicle

import ai.pleos.playground.vehicle.constant.ErrorCode

/**
 * The docs describe `VehicleException.errorCode`, but in the published Vehicle 2.3.2 AAR that class
 * is obfuscated (`ai.pleos.playground.vehicle.callback.exception.a`), so `e is VehicleException`
 * does not compile. Read the ErrorCode field by type instead. ErrorCode itself lives in
 * `ai.pleos.playground:vehicle-data`, declared explicitly in app/build.gradle.kts.
 */
fun Throwable.vehicleErrorCode(): ErrorCode? =
    javaClass.declaredFields.firstOrNull { it.type == ErrorCode::class.java }?.let { field ->
        runCatching {
            field.isAccessible = true
            field.get(this) as? ErrorCode
        }.getOrNull()
    }
