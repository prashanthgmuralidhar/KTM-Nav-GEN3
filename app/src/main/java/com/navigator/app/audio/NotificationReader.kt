package com.navigator.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.navigator.app.logging.AppLogger
import com.navigator.app.notifications.NotificationEntry
import com.navigator.app.notifications.NotificationRepository

/**
 * Reads the most recent mirrored message notification (never the nav app's)
 * aloud, ducking music while it speaks. Asking again while it's speaking
 * stops it instead.
 */
class NotificationReader(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun readLatest() {
        tts?.takeIf { ready && it.isSpeaking }?.let {
            AppLogger.log("TTS", "Stopped reading")
            it.stop()
            abandonFocus()
            return
        }
        val entry = NotificationRepository.entries.value.firstOrNull { !it.isNavigation }
        speak(entry?.let(::describe) ?: "No new messages")
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        abandonFocus()
    }

    private fun describe(e: NotificationEntry): String {
        val minutes = ((System.currentTimeMillis() - e.postTimeMs) / 60_000).coerceAtLeast(0)
        val ago = when (minutes) {
            0L -> "just now"
            1L -> "1 minute ago"
            in 2..59 -> "$minutes minutes ago"
            else -> "over an hour ago"
        }
        return "${e.appLabel}, $ago. ${e.title}. ${e.text}"
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine != null && ready) {
            say(engine, text)
            return
        }
        pending = text
        if (engine != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                AppLogger.log("TTS", "!! Text-to-speech engine failed to start (status=$status)")
                tts = null
                return@TextToSpeech
            }
            tts?.setAudioAttributes(attributes)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = abandonFocus()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = abandonFocus()
            })
            pending?.let { p -> tts?.let { say(it, p) } }
            pending = null
        }
    }

    private fun say(engine: TextToSpeech, text: String) {
        AppLogger.log("TTS", "Reading: \"$text\"")
        audio?.requestAudioFocus(focusRequest)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "latest-notification")
    }

    private fun abandonFocus() {
        audio?.abandonAudioFocusRequest(focusRequest)
    }
}
