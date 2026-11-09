package dev.chagok.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * The parent's own words for "그날", recorded in Park and kept only in the car.
 * One short clip per day; recording again replaces it.
 */
class Memos(context: Context) {

    private val app = context.applicationContext
    private val dir = File(app.filesDir, "memos").apply { mkdirs() }
    private val audio = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var recordFocus: AudioFocusRequest? = null
    private var onPlayDone: (() -> Unit)? = null

    fun file(dayId: Long) = File(dir, "day_$dayId.m4a")

    fun exists(dayId: Long) = file(dayId).let { it.exists() && it.length() > 0 }

    /** Length of a recorded memo, rounded to the second; null if there is none or it cannot be read. */
    fun seconds(dayId: Long): Int? {
        if (!exists(dayId)) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file(dayId).path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { ((it + 500) / 1000).toInt() }
        } catch (e: RuntimeException) {
            Log.w(TAG, "memo length unreadable", e)
            null
        } finally {
            retriever.release()
        }
    }

    fun delete(dayId: Long) {
        file(dayId).delete()
    }

    fun deleteAll() {
        stopPlaying()
        stopRecording(keep = false)
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Starts recording; stops by itself after [MAX_MS]. Returns false if the microphone is unavailable. */
    fun startRecording(dayId: Long, onAutoStop: () -> Unit): Boolean {
        stopRecording(keep = false)
        val target = File(dir, "day_$dayId.tmp.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(app) else @Suppress("DEPRECATION") MediaRecorder()
        return runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setMaxDuration(MAX_MS)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    main.post {
                        stopRecording(keep = true, dayId = dayId)
                        onAutoStop()
                    }
                }
            }
            r.setOutputFile(target)
            r.prepare()
            takeRecordFocus(dayId, onAutoStop)
            r.start()
            recorder = r
            recordingFile = target
            true
        }.getOrElse {
            Log.w(TAG, "record failed: ${it.javaClass.simpleName}")
            r.release()
            releaseRecordFocus()
            target.delete()
            false
        }
    }

    /** Quiets the music so it is not in the parent's words. A call ends the take and keeps what was said. */
    private fun takeRecordFocus(dayId: Long, onStopped: () -> Unit) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    stopRecording(keep = true, dayId = dayId)
                    onStopped()
                }
            }
            .build()
        if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) recordFocus = request
    }

    private fun releaseRecordFocus() {
        recordFocus?.let { audio.abandonAudioFocusRequest(it) }
        recordFocus = null
    }

    /** Ends recording. With [keep], the clip replaces the day's memo. */
    fun stopRecording(keep: Boolean, dayId: Long? = null) {
        val r = recorder ?: return
        releaseRecordFocus()
        val tmp = recordingFile
        recorder = null
        recordingFile = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (keep && ok && tmp != null && dayId != null && tmp.length() > 0) {
            tmp.renameTo(file(dayId))
        } else {
            tmp?.delete()
        }
    }

    val isRecording: Boolean get() = recorder != null

    /** Plays the memo with transient focus, so music resumes afterwards. */
    fun play(dayId: Long, onDone: () -> Unit) {
        stopPlaying()
        val source = file(dayId)
        if (!exists(dayId)) {
            onDone()
            return
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    val done = onPlayDone
                    stopPlaying()
                    done?.invoke()
                }
            }
            .build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onDone()
            return
        }
        focus = request
        onPlayDone = onDone
        val p = MediaPlayer()
        runCatching {
            p.setAudioAttributes(attributes)
            p.setDataSource(source.absolutePath)
            p.setOnCompletionListener {
                stopPlaying()
                onDone()
            }
            p.prepare()
            player = p
            // Output paths in a car wake up slowly; silence first keeps the first syllable.
            main.postDelayed({ if (player === p) p.start() }, PRE_ROLL_MS)
        }.onFailure {
            Log.w(TAG, "play failed: ${it.javaClass.simpleName}")
            p.release()
            stopPlaying()
            onDone()
        }
    }

    fun stopPlaying() {
        onPlayDone = null
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private companion object {
        const val TAG = "ChagokMemo"
        const val MAX_MS = 20_000
        const val PRE_ROLL_MS = 200L
    }
}
