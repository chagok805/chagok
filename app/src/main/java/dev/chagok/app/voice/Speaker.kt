package dev.chagok.app.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/** On-device voice: needs no credentials and nothing leaves the car. */
class Speaker(context: Context) {

    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ready = false
    private var failed = false
    private var pending: Pending? = null
    private var current: Utterance? = null

    private class Pending(val text: String, val scene: Boolean, val onDone: (() -> Unit)?)

    /** Engine events do not say which utterance they belong to, so leftovers must not end this one. */
    private class Utterance(val onDone: (() -> Unit)?) {
        var started = false
    }

    private val events = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() {
            main.post { current?.started = true }
        }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = finishStarted()
        override fun onStop() = finishStarted()
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "tts error $errorType")
            main.post { current?.let(::finish) }
        }
    }

    fun connect() {
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    tts.addEventListener(events)
                    // The engine needs the region: Locale.KOREAN is rejected as "ko-".
                    tts.setLocale(Locale.KOREA)
                    ready = true
                    main.post {
                        pending?.let { speak(it.text, it.scene, it.onDone) }
                        pending = null
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "tts init failed $errorType")
                    main.post(::giveUp)
                }
            })
        }.onFailure {
            Log.w(TAG, "tts not available: ${it.javaClass.simpleName}")
            main.post(::giveUp)
        }
    }

    /**
     * Replaces whatever is being said. [onDone] also runs when speaking fails, so a scene never waits
     * forever, but not when this utterance is replaced or stopped. A short line lets other audio duck;
     * a [scene] pauses it.
     */
    fun speak(text: String, scene: Boolean = false, onDone: (() -> Unit)? = null) {
        if (!ready) {
            val waiting = Pending(text, scene, onDone)
            pending = waiting
            // One failed start must not silence the car until the next restart.
            if (failed) {
                failed = false
                connect()
            }
            main.postDelayed({
                if (pending === waiting) {
                    pending = null
                    onDone?.invoke()
                }
            }, INIT_WATCHDOG_MS)
            return
        }
        if (current != null) runCatching { tts.stop() }
        // The speech service plays without asking for focus, so the app asks on its behalf.
        if (!takeFocus(scene)) {
            current = null
            onDone?.invoke()
            return
        }
        val utterance = Utterance(onDone)
        current = utterance
        main.postDelayed({ if (current === utterance) finish(utterance) }, WATCHDOG_MS)
        // Output paths in a car wake up slowly; a moment of silence keeps the first syllable.
        main.postDelayed({
            if (current === utterance) {
                runCatching { tts.speak(text) }.onFailure {
                    Log.w(TAG, "speak failed: ${it.javaClass.simpleName}")
                    finish(utterance)
                }
            }
        }, PRE_ROLL_MS)
    }

    private fun takeFocus(scene: Boolean): Boolean {
        releaseFocus()
        val request = AudioFocusRequest.Builder(
            if (scene) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                // A call or a navigation prompt comes first.
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    current?.let { utterance ->
                        runCatching { tts.stop() }
                        finish(utterance)
                    }
                }
            }
            .build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = request
        return true
    }

    private fun releaseFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    /** Stops speaking without running the pending callback. */
    fun stop() {
        current = null
        pending = null
        if (ready) runCatching { tts.stop() }
        releaseFocus()
    }

    private fun finishStarted() {
        main.post { current?.takeIf { it.started }?.let(::finish) }
    }

    private fun finish(utterance: Utterance) {
        current = null
        // In this order, so a memo that follows takes focus before music gets it back.
        utterance.onDone?.invoke()
        if (current == null) releaseFocus()
    }

    private fun giveUp() {
        failed = true
        val done = pending?.onDone
        pending = null
        done?.invoke()
    }

    private companion object {
        const val TAG = "ChagokVoice"
        const val WATCHDOG_MS = 30_000L
        const val INIT_WATCHDOG_MS = 8_000L
        const val PRE_ROLL_MS = 200L
    }
}
