package com.navigator.app.controller

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.navigator.app.audio.CallAudioRouter
import com.navigator.app.audio.MediaControlBridge
import com.navigator.app.audio.NotificationReader
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.ble.BccuProtocol.HandlebarButton
import com.navigator.app.logging.AppLogger
import com.navigator.app.telephony.CallStateMonitor

/**
 * The handlebar remote: music controls plus Google Assistant, a now-playing
 * peek on the dash, read-aloud messages and call answering. Buttons only reach the phone while the dash's own remote-control
 * mode is on (the rider holds Back on the dash to toggle it); holding Back is
 * consumed by the dash and never arrives here.
 *
 * | Button | Tap         | Double-tap     | Hold             |
 * |--------|-------------|----------------|------------------|
 * | Up     | volume up   | -              | Google Assistant |
 * | Down   | volume down | -              | track on dash 5s |
 * | Set    | play/pause  | -              | read last message|
 * | Back   | next track  | previous track | (dash)           |
 *
 * While the phone rings, Set answers and Back rejects, both on release.
 *
 * Opening Assistant from the background needs "Display over other apps"
 * (Android's background-activity-start exemption).
 *
 * Must be created and driven on the main thread.
 */
class HandlebarRemote(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val media = MediaControlBridge(context)
    private val callAudioRouter = CallAudioRouter(context)
    private val reader = NotificationReader(context)
    private var callMonitor: CallStateMonitor? = null
    @Volatile private var ringing = false
    private var remoteActive = false

    private val recognizer = HandlebarGestureRecognizer(
        supportsDouble = { it == HandlebarButton.BACK && !ringing },
        schedule = { delayMs, block ->
            val r = Runnable(block)
            handler.postDelayed(r, delayMs)
            ({ handler.removeCallbacks(r) })
        },
        emit = ::onGesture,
    )

    /** Feed one decoded RCM notification: the dash's remote-control flag and the pressed-button mask. */
    fun onRcm(active: Boolean, mask: Int, nowMs: Long) {
        if (active != remoteActive) {
            remoteActive = active
            AppLogger.log("RCM", "Dash remote control ${if (active) "ON - buttons go to the phone" else "OFF - buttons back to the bike"}")
            if (!active) recognizer.cancelPending()
            ensureCallMonitor()
        }
        recognizer.onMask(if (active) mask else 0, nowMs)
    }

    fun destroy() {
        recognizer.cancelPending()
        reader.shutdown()
        callMonitor?.stop()
        callMonitor = null
    }

    private fun ensureCallMonitor() {
        if (callMonitor != null) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        callMonitor = CallStateMonitor(context) { ringing = it }.also { it.start() }
    }

    private fun onGesture(g: RemoteGesture) {
        AppLogger.log("RCM", "Gesture ${g.button} ${g.kind}${if (ringing) " (ringing)" else ""}")
        if (ringing) {
            when (g.button) {
                HandlebarButton.SET -> {
                    callMonitor?.answer()
                    callAudioRouter.routeCallToPreferredDevice()
                }
                HandlebarButton.BACK -> callMonitor?.reject()
                else -> {}
            }
            return
        }
        when (g.button to g.kind) {
            HandlebarButton.UP to GestureKind.SINGLE -> adjustVolume(raise = true)
            HandlebarButton.DOWN to GestureKind.SINGLE -> adjustVolume(raise = false)
            HandlebarButton.UP to GestureKind.LONG -> openAssistant()
            HandlebarButton.DOWN to GestureKind.LONG ->
                BccuConnectionService.showMediaPeekIfRunning(media.nowPlaying() ?: "Nothing playing")
            HandlebarButton.SET to GestureKind.SINGLE -> media.playPause()
            HandlebarButton.SET to GestureKind.LONG -> reader.readLatest()
            HandlebarButton.BACK to GestureKind.SINGLE -> media.next()
            HandlebarButton.BACK to GestureKind.DOUBLE -> media.previous()
            else -> {}
        }
    }

    private fun adjustVolume(raise: Boolean) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    private fun openAssistant() {
        launch("Assistant", Intent(Intent.ACTION_VOICE_COMMAND))
    }

    private fun launch(what: String, intent: Intent) {
        if (!Settings.canDrawOverlays(context)) {
            AppLogger.log("RCM", "!! $what may be blocked: 'Display over other apps' not granted (Settings > Handlebar remote)")
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { AppLogger.log("RCM", "Opened $what") }
            .onFailure { AppLogger.log("RCM", "!! Opening $what failed: $it") }
    }
}
