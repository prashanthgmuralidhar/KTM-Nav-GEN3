package com.navigator.app.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.navigator.app.R
import com.navigator.app.logging.AppLogger
import com.navigator.app.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * GPS overspeed monitor with hysteresis, hosted by BccuConnectionService (the
 * app's long-lived foreground service). While the measured GPS speed exceeds
 * the configured limit (default 80 km/h) an ongoing high-priority notification
 * shows the LIVE speed, updated on every fix, plus a warning tone; it keeps
 * updating until the speed drops below (limit − 10) km/h (i.e. under 70 for
 * the default), then clears. Speed comes from Location.getSpeed() on GPS fixes
 * (1 s interval), not from network locations, so it's real movement speed.
 */
class SpeedMonitor(private val context: Context) : LocationListener {

    companion object {
        private const val CHANNEL_ID = "overspeed_alert"
        private const val NOTIFICATION_ID = 42
        private const val HYSTERESIS_KMH = 10
        private const val STALE_FIX_MS = 15_000L
        private const val BEEP_INTERVAL_MS = 900L
        /** Dash banner refresh while overspeeding (each update is a BLE write). */
        private const val DASH_UPDATE_MS = 2_000L

        /** Live GPS speed in km/h for any UI that wants it; null = no recent fix. */
        private val _speedKmh = MutableStateFlow<Float?>(null)
        val speedKmh: StateFlow<Float?> = _speedKmh
    }

    private val settings = AppSettings(context)
    private var alerting = false
    private var started = false
    private var loggedFirstFix = false
    // One long-lived ToneGenerator: creating one per beep leaks native
    // AudioTracks (finalizer-reliant) and can exhaust AudioFlinger's track cap.
    private var toneGenerator: ToneGenerator? = null
    private val staleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // If GPS fixes stop while alerting (tunnel, provider off), the ongoing
    // notification would otherwise be stuck with a frozen speed forever.
    private val staleClear = Runnable {
        AppLogger.log("Speed", "No GPS fix for ${STALE_FIX_MS / 1000}s while alerting - clearing alert")
        clearAlert()
    }
    // Continuous warning beeps for the whole time the rider is over the limit
    // (the single tone every 5 s was too easy to miss at speed - R18 field test).
    private val beepLoop = object : Runnable {
        override fun run() {
            if (!alerting) return
            runCatching {
                // STREAM_ALARM at max generator volume: the alarm stream is
                // loud by default and independent of the (often-low) notification
                // volume, and it sounds through DND — right for a safety alert a
                // rider must hear over wind/engine (R23 field request: louder).
                val tg = toneGenerator ?: ToneGenerator(AudioManager.STREAM_ALARM, 100)
                    .also { toneGenerator = it }
                tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
            }
            staleHandler.postDelayed(this, BEEP_INTERVAL_MS)
        }
    }
    private var lastDashSpeed = -1
    private var lastDashSentAtMs = 0L

    fun start() {
        if (started) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.log("Speed", "No location permission - overspeed monitor idle")
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            started = true
            createChannel()
            val background = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            AppLogger.log("Speed", "Overspeed monitor started (limit=${settings.overspeedLimitKmh} km/h, enabled=${settings.overspeedEnabled}, background location=$background)")
            if (!background) {
                AppLogger.log("Speed", "!! Location is not 'Allow all the time' - GPS (and overspeed alerts) stop while the phone is locked")
            }
        } catch (e: Exception) {
            AppLogger.log("Speed", "!! requestLocationUpdates failed: $e")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        loggedFirstFix = false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { lm.removeUpdates(this) }
        clearAlert()
        toneGenerator?.let { runCatching { it.release() } }
        toneGenerator = null
        _speedKmh.value = null
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasSpeed()) return
        val kmh = location.speed * 3.6f
        if (!loggedFirstFix) {
            loggedFirstFix = true
            AppLogger.log("Speed", "First GPS fix with speed: %.0f km/h".format(kmh))
        }
        _speedKmh.value = kmh
        if (!settings.overspeedEnabled) { if (alerting) clearAlert(); return }

        val limit = settings.overspeedLimitKmh
        val clearBelow = limit - HYSTERESIS_KMH
        if (!alerting && kmh > limit) {
            alerting = true
            AppLogger.log("Speed", "OVERSPEED: %.0f km/h > $limit km/h".format(kmh))
            staleHandler.post(beepLoop)
        }
        if (alerting) {
            if (kmh < clearBelow) {
                AppLogger.log("Speed", "Speed back under $clearBelow km/h - clearing alert")
                clearAlert()
            } else {
                showAlert(kmh, limit)
            }
        }
    }

    private fun showAlert(kmh: Float, limit: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("OVERSPEED  %.0f km/h".format(kmh))
            .setContentText("Limit $limit km/h - slow down")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        nm.notify(NOTIFICATION_ID, n)
        // Mirror the LIVE speed onto the dash's bottom notification banner,
        // refreshed while the alert is active (throttled: each update is a BLE
        // write; also re-sent when the rounded value changes).
        val now = System.currentTimeMillis()
        val speedInt = kmh.toInt()
        if (speedInt != lastDashSpeed || now - lastDashSentAtMs > DASH_UPDATE_MS) {
            lastDashSpeed = speedInt
            lastDashSentAtMs = now
            // Low-priority banner write: yields to any mirrored message that is
            // currently scrolling, instead of cancelling it every second.
            com.navigator.app.ble.BccuConnectionService.sendSpeedBannerIfRunning("OVERSPEED $speedInt")
        }
        // Re-arm the staleness watchdog on every fix while alerting.
        staleHandler.removeCallbacks(staleClear)
        staleHandler.postDelayed(staleClear, STALE_FIX_MS)
    }

    private fun clearAlert() {
        alerting = false
        staleHandler.removeCallbacks(staleClear)
        staleHandler.removeCallbacks(beepLoop)
        lastDashSpeed = -1
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Overspeed alert", NotificationManager.IMPORTANCE_HIGH)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    @Deprecated("Deprecated in API 29 but still called on older devices")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        _speedKmh.value = null
        clearAlert()
    }
}
