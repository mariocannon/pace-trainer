package com.mariocannon.pacetrainer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import com.mariocannon.pacetrainer.engine.Cue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Plays the cue sounds on its own thread. Sounds are generated sine bursts written once to
 * small WAV files in the cache dir and played through SoundPool. Music ducks while off pace.
 */
class ClickPlayer(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(attributes)
        .build()

    private val thread = HandlerThread("pace-audio").also { it.start() }
    private val handler = Handler(thread.looper)

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ }, handler)
        .build()

    private val sounds: Map<Cue, Int>

    private var cue = Cue.SILENT
    private var intervalMs = 0L
    private var hasFocus = false

    private val loop = object : Runnable {
        override fun run() {
            val id = sounds[cue] ?: return
            soundPool.play(id, 1f, 1f, 1, 0, 1f)
            if (intervalMs > 0) handler.postDelayed(this, intervalMs)
        }
    }

    init {
        val dir = context.cacheDir
        sounds = mapOf(
            Cue.TOO_SLOW to load(dir, "cue_slow.wav", tone(1500.0, 25, 0.9)),
            Cue.TOO_FAST to load(dir, "cue_fast.wav", tone(600.0, 40, 0.9)),
            Cue.ON_PACE to load(dir, "cue_tick.wav", tone(1000.0, 15, 0.35)),
            Cue.NO_GPS to load(
                dir, "cue_nogps.wav",
                tone(880.0, 80, 0.6) + ShortArray(SAMPLE_RATE * 80 / 1000) + tone(880.0, 80, 0.6),
            ),
        )
    }

    /** Thread-safe. Same cue with a new interval just changes the rate from the next click. */
    fun setCue(newCue: Cue, newIntervalMs: Long) {
        handler.post {
            val changed = newCue != cue
            cue = newCue
            intervalMs = newIntervalMs
            if (changed) {
                handler.removeCallbacks(loop)
                updateFocus(newCue == Cue.TOO_SLOW || newCue == Cue.TOO_FAST)
                if (newCue != Cue.SILENT) handler.post(loop)
            }
        }
    }

    fun release() {
        handler.post {
            handler.removeCallbacks(loop)
            updateFocus(false)
            soundPool.release()
            thread.quitSafely()
        }
    }

    private fun updateFocus(want: Boolean) {
        if (want && !hasFocus) {
            hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else if (!want && hasFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            hasFocus = false
        }
    }

    private fun load(dir: File, name: String, samples: ShortArray): Int {
        val file = File(dir, name)
        writeWav(file, samples)
        return soundPool.load(file.absolutePath, 1)
    }

    companion object {
        private const val SAMPLE_RATE = 44_100

        /** Sine burst with a 2 ms attack and exponential decay, so it clicks without popping. */
        fun tone(freqHz: Double, durationMs: Int, gain: Double): ShortArray {
            val n = SAMPLE_RATE * durationMs / 1000
            val attack = SAMPLE_RATE * 0.002
            return ShortArray(n) { i ->
                val envelope = min(1.0, i / attack) * exp(-5.0 * i / n)
                val v = sin(2 * PI * freqHz * i / SAMPLE_RATE) * envelope * gain
                (v * Short.MAX_VALUE).toInt().toShort()
            }
        }

        private fun writeWav(file: File, samples: ShortArray) {
            val dataBytes = samples.size * 2
            val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
            buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
            buf.put("data".toByteArray()).putInt(dataBytes)
            samples.forEach { buf.putShort(it) }
            file.writeBytes(buf.array())
        }
    }
}
