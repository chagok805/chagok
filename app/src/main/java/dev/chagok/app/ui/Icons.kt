package dev.chagok.app.ui

// Line icons drawn for this app on the Pleos 48dp icon grid: one stroke weight, round caps.
// Tinted by Icon(tint = ...), so the stroke color here does not matter.

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object ChagokIcons {
    private const val STROKE = 3.5f

    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 48.dp, 48.dp, 48f, 48f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    private fun filled(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 48.dp, 48.dp, 48f, 48f).apply {
            path(fill = SolidColor(Color.Black), pathBuilder = block)
        }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        moveTo(l + rad, t)
        lineTo(r - rad, t)
        arcTo(rad, rad, 0f, false, true, r, t + rad)
        lineTo(r, b - rad)
        arcTo(rad, rad, 0f, false, true, r - rad, b)
        lineTo(l + rad, b)
        arcTo(rad, rad, 0f, false, true, l, b - rad)
        lineTo(l, t + rad)
        arcTo(rad, rad, 0f, false, true, l + rad, t)
        close()
    }

    val Back = icon("back") {
        moveTo(29f, 11f); lineTo(16f, 24f); lineTo(29f, 37f)
    }

    val Close = icon("close") {
        moveTo(13f, 13f); lineTo(35f, 35f)
        moveTo(35f, 13f); lineTo(13f, 35f)
    }

    val Person = icon("person") {
        circle(24f, 16f, 7f)
        moveTo(10f, 39f)
        curveTo(10f, 31f, 16f, 27f, 24f, 27f)
        curveTo(32f, 27f, 38f, 31f, 38f, 39f)
    }

    /** A calendar page with one marked day: "그날". */
    val Day = icon("day") {
        roundRect(9f, 12f, 39f, 40f, 6f)
        moveTo(9f, 20f); lineTo(39f, 20f)
        moveTo(17f, 8f); lineTo(17f, 14f)
        moveTo(31f, 8f); lineTo(31f, 14f)
        circle(24f, 30f, 3f)
    }

    val Settings = icon("settings") {
        moveTo(9f, 16f); lineTo(39f, 16f)
        moveTo(9f, 32f); lineTo(39f, 32f)
        circle(18f, 16f, 4.5f)
        circle(30f, 32f, 4.5f)
    }

    val Mic = icon("mic") {
        roundRect(18f, 7f, 30f, 28f, 6f)
        moveTo(11f, 23f)
        curveTo(11f, 30.5f, 16.8f, 36f, 24f, 36f)
        curveTo(31.2f, 36f, 37f, 30.5f, 37f, 23f)
        moveTo(24f, 36f); lineTo(24f, 41f)
    }

    val Stop = filled("stop") {
        moveTo(17f, 14f); lineTo(31f, 14f)
        arcTo(3f, 3f, 0f, false, true, 34f, 17f)
        lineTo(34f, 31f)
        arcTo(3f, 3f, 0f, false, true, 31f, 34f)
        lineTo(17f, 34f)
        arcTo(3f, 3f, 0f, false, true, 14f, 31f)
        lineTo(14f, 17f)
        arcTo(3f, 3f, 0f, false, true, 17f, 14f)
        close()
    }

    val Play = filled("play") {
        moveTo(17f, 12.5f)
        curveTo(17f, 10.9f, 18.7f, 10f, 20f, 10.8f)
        lineTo(37f, 22.3f)
        curveTo(38.2f, 23.1f, 38.2f, 24.9f, 37f, 25.7f)
        lineTo(20f, 37.2f)
        curveTo(18.7f, 38f, 17f, 37.1f, 17f, 35.5f)
        close()
    }

    val Check = icon("check") {
        moveTo(11f, 25f); lineTo(20f, 34f); lineTo(37f, 15f)
    }

    val Plus = icon("plus") {
        moveTo(24f, 11f); lineTo(24f, 37f)
        moveTo(11f, 24f); lineTo(37f, 24f)
    }

    /** Phone, for "hand off to your phone". */
    val Phone = icon("phone") {
        roundRect(14f, 6f, 34f, 42f, 5f)
        moveTo(21f, 36f); lineTo(27f, 36f)
    }

    /** Play again: a circle that comes back to its start. */
    val Replay = icon("replay") {
        moveTo(6f, 24f)
        arcTo(18f, 18f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 12f, y1 = 10.6f)
        moveTo(6f, 8f); lineTo(6f, 18f); lineTo(16f, 18f)
    }

    val Lock = icon("lock") {
        roundRect(10f, 22f, 38f, 42f, 5f)
        moveTo(16f, 22f); lineTo(16f, 16f)
        arcTo(8f, 8f, 0f, false, true, 32f, 16f)
        lineTo(32f, 22f)
    }

    /** A steering wheel, for the screen that rests while driving. */
    val Wheel = icon("wheel") {
        circle(24f, 24f, 18f)
        circle(24f, 24f, 5f)
        moveTo(24f, 29f); lineTo(24f, 42f)
        moveTo(19.2f, 22.4f); lineTo(6.8f, 19.2f)
        moveTo(28.8f, 22.4f); lineTo(41.2f, 19.2f)
    }

    val Calendar = icon("calendar") {
        roundRect(6f, 10f, 42f, 42f, 6f)
        moveTo(6f, 20f); lineTo(42f, 20f)
        moveTo(16f, 6f); lineTo(16f, 14f)
        moveTo(32f, 6f); lineTo(32f, 14f)
    }

    val Chevron = icon("chevron") {
        moveTo(18f, 12f); lineTo(30f, 24f); lineTo(18f, 36f)
    }

    /** A fuel pump: the hero after a refill. */
    val Fuel = icon("fuel") {
        moveTo(8f, 42f); lineTo(8f, 10f)
        arcTo(4f, 4f, 0f, false, true, 12f, 6f)
        lineTo(24f, 6f)
        arcTo(4f, 4f, 0f, false, true, 28f, 10f)
        lineTo(28f, 42f)
        moveTo(6f, 42f); lineTo(30f, 42f)
        moveTo(28f, 18f); lineTo(32f, 18f)
        arcTo(4f, 4f, 0f, false, true, 36f, 22f)
        lineTo(36f, 32f)
        arcTo(3f, 3f, 0f, false, false, 42f, 32f)
        lineTo(42f, 16f); lineTo(36f, 10f)
    }
}
