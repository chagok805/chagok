package dev.chagok.app

import android.content.Context

/** No-op outside the `probe` build type (see app/build.gradle.kts). */
object PropertyProbe {
    fun dump(context: Context) = Unit
}
