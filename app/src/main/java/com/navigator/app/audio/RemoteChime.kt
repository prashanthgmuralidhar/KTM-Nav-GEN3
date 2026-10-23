package com.navigator.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.navigator.app.settings.AppSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Audio feedback for the handlebar remote, so the rider never has to look at
 * the phone: a rising chime when the dash hands the buttons to the phone, a
 * falling one when they go back to the bike, a short tick on every press and
 * a low tone when a press counts as a hold. Same audio route and volume
 * setting as the turn beeps.
 */
object RemoteChime {

    private const val SAMPLE_RATE = 44_100
    private const val GAP_MS = 40
    // Bluetooth audio drops the first ~100 ms while an idle link wakes up.
    private const val LEAD_IN_MS = 150
    private const val LOW_HZ = 660.0
    private const val HIGH_HZ = 990.0
    private const val TICK_HZ = 1_400.0
    private const val HOLD_HZ = 440.0

    private val handler = Handler(Looper.getMainLooper())

    fun play(context: Context, entering: Boolean) =
        playNotes(context, if (entering) listOf(LOW_HZ, HIGH_HZ) else listOf(HIGH_HZ, LOW_HZ), noteMs = 120)

    fun tick(context: Context) = playNotes(context, listOf(TICK_HZ), noteMs = 35)

    fun holdTone(context: Context) = playNotes(context, listOf(HOLD_HZ), noteMs = 160)

    private fun playNotes(context: Context, notes: List<Double>, noteMs: Int) {
        val amplitude = AppSettings(context).beepVolumePercent.coerceIn(5, 100) / 100.0
        val linkIdle = context.getSystemService(AudioManager::class.java)?.isMusicActive != true
        val noteFrames = SAMPLE_RATE * noteMs / 1000
        val gapFrames = SAMPLE_RATE * GAP_MS / 1000
        val leadFrames = if (linkIdle) SAMPLE_RATE * LEAD_IN_MS / 1000 else 0
        val buf = ShortArray(leadFrames + notes.size * (noteFrames + gapFrames))
        var pos = leadFrames
        for (hz in notes) {
            for (i in 0 until noteFrames) {
                val env = 0.5 * (1.0 - cos(2.0 * PI * i / (noteFrames - 1)))
                buf[pos + i] = (sin(2.0 * PI * hz * i / SAMPLE_RATE) * env * Short.MAX_VALUE * amplitude).toInt().toShort()
            }
            pos += noteFrames + gapFrames
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(buf.size * 2)
                .build()
        }.getOrNull() ?: return
        track.write(buf, 0, buf.size)
        track.play()
        val durationMs = buf.size * 1000L / SAMPLE_RATE
        handler.postDelayed({ runCatching { track.release() } }, durationMs + 100)
    }
}
