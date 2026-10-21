package com.navigator.app.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.logging.AppLogger
import com.navigator.app.settings.AppSettings

/**
 * Captures notifications from user-selected apps (WhatsApp/SMS/etc, see
 * AppSettings.notificationSourceApps) and mirrors them to the dash's
 * NOTIFICATION characteristic (the small bottom banner), plus separately
 * detects "which app is currently navigating" and mirrors that to the
 * dash's center guidance view (TURN_DISTANCE + TURN_ROAD - a different
 * display region entirely, confirmed from the official app's NavableBleImpl,
 * see BCCU_BLE_PROTOCOL.md) rather than the bottom banner.
 *
 * Scope note: turn-direction (left/right/straight) is derived via
 * TurnIconHeuristic - an explicitly unreliable image heuristic, not a
 * confirmed protocol field like everything else here. Included by request
 * despite that, since there's no text-based signal for turn direction in a
 * generic maps app's notification.
 */
class AppNotificationListener : NotificationListenerService() {

    /**
     * Confirmed via a full hardware calibration pass (BCCU_BLE_PROTOCOL.md
     * section 7.2, 2026-07-05, "Symbol testing" screen): of all 8
     * NotificationIcon codes, only NOTIFICATION_REROUTING and
     * NOTIFICATION_WAYPOINT actually render on this Gen-3 dash's bottom
     * banner. Using WAYPOINT as the settled default for all app-generated
     * notifications.
     */
    private val dashIcon = BccuProtocol.NotificationIcon.NOTIFICATION_WAYPOINT

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val settings = AppSettings(this)
        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        AppLogger.log("Notif", "Posted: pkg=$packageName category=${sbn.notification.category} title=${redact(title)}")
        if (title.isBlank() && text.isBlank()) return

        val isNav = isNavigationNotification(sbn, settings)
        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }

        if (isNav) {
            val navText = if (title.isNotBlank()) "$title $text" else text
            val fields = NavNotificationParser.parse(extras)
            AppLogger.log("Notif", "Nav update from $packageName: distance=${redact(fields.distance.orEmpty())} instruction=${redact(fields.instruction.orEmpty())}")
            // Dump every extras key so we can see, from real logs, exactly which
            // field carries ETA/duration/remaining-distance - no guessing which
            // Android notification extra Google Maps uses for that subtitle line.
            // DEBUG-only: extra *values* carry the destination road/address
            // (location PII) and AppLogger mirrors every line to the public
            // Downloads copy, so raw values must never be emitted in a release.
            if (com.navigator.app.BuildConfig.DEBUG) {
                for (key in extras.keySet()) {
                    AppLogger.log("Notif", "  extra[$key] = ${extras.get(key)}")
                }
            }
            NotificationRepository.updateNavText(packageName, navText)
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
            val (etaText, subTextDistance) = parseEtaAndRemainingDistance(subText)
            val remainingDistanceText = subTextDistance ?: fields.remaining
            BccuConnectionService.sendGuidanceIfRunning(
                fields.distance,
                fields.instruction?.let { NavNotificationParser.compactForDash(it) },
                etaText,
                remainingDistanceText
            )
            captureManeuverIconIfEnabled(sbn, title, fields.instruction.orEmpty())
            val guessedIcon = TurnIconHeuristic.guessDirection(this, sbn, fields.instruction)
            guessedIcon?.let { BccuConnectionService.sendTurnIconIfRunning(it) }
            // Stereo approach beeps: left ear = left turn, right ear = right turn,
            // faster pattern as the distance (the notification title) shrinks.
            // Ducked to a background hum while the bike is provably waiting
            // (GPS says stationary / vibration says the engine is off).
            if (settings.turnBeepEnabled) {
                com.navigator.app.audio.TurnBeeper.swapChannels = settings.swapBeepChannels
                com.navigator.app.audio.TurnBeeper.volumePercent = settings.beepVolumePercent
                com.navigator.app.audio.TurnBeeper.gpsSpeedKmh = com.navigator.app.location.SpeedMonitor.speedKmh.value
                com.navigator.app.audio.TurnBeeper.engineOn = com.navigator.app.sensors.VibrationMonitor.engineOn.value
                com.navigator.app.audio.TurnBeeper.onGuidance(fields.distance?.ifBlank { null }, guessedIcon, fields.instruction)
            }
            NotificationRepository.updateNavGuidance(
                NotificationRepository.NavGuidance(
                    distance = fields.distance?.ifBlank { null },
                    road = fields.instruction,
                    eta = etaText,
                    remaining = remainingDistanceText,
                    maneuver = maneuverTextFor(guessedIcon),
                    turnIcon = guessedIcon,
                )
            )
            return
        }

        if (packageName !in settings.notificationSourceApps) return

        // Only INDIVIDUAL conversations reach the dash: skip group chats
        // (WhatsApp groups, group MMS) and the per-app summary notifications
        // that just say "5 new messages".
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            AppLogger.log("Notif", "Skipping group-summary notification from $packageName")
            return
        }
        if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) {
            AppLogger.log("Notif", "Skipping GROUP conversation from $packageName: \"$title\"")
            return
        }
        // WhatsApp fallback for older notification styles: group messages title
        // as "Group name: Sender" or carry "@ Group" in the conversation title.
        if (packageName == "com.whatsapp" &&
            (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null &&
                extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, true))
        ) {
            AppLogger.log("Notif", "Skipping WhatsApp group (conversation title present): \"$title\"")
            return
        }

        val combined = if (title.isNotBlank()) "$title: $text" else text
        AppLogger.log("Notif", "Captured from $packageName ($appLabel): ${redact(combined)}")
        NotificationRepository.addEntry(
            NotificationEntry(
                packageName = packageName,
                appLabel = appLabel,
                title = title,
                text = text,
                postTimeMs = sbn.postTime,
                isNavigation = false
            )
        )
        // Quick-mute: still record the notification in the in-app feed, but skip
        // pushing non-navigation notifications to the dash while mirroring is off.
        if (settings.mirrorEnabled) {
            BccuConnectionService.sendNotificationIfRunning(combined, dashIcon)
        }
    }

    /**
     * Best-effort extraction of ETA and remaining distance from a nav app's
     * notification subtitle line, e.g. "22 min - 9.7 km - 2:12 am ETA" (the
     * "Maps - ... - Now" wrapper around it is added by Android's system UI,
     * not part of the app's own extras). This is standard Android notification
     * API usage (EXTRA_SUB_TEXT), not reverse-engineered BLE protocol - but the
     * exact subtitle format is still only as reliable as what real logs show,
     * so this returns null for either field rather than guess if the segment
     * doesn't look like a time or a distance.
     *
     * ETA is reformatted to plain zero-padded "HH:MM" with no am/pm suffix -
     * the official app's own confirmed example (Eta.java: "12:45") has no
     * am/pm text, and a real-hardware test sending "2:12 am" wrote successfully
     * over BLE but never rendered, while the original working screenshot for
     * this same dash slot showed a bare "00:00" - strongly suggesting the
     * firmware expects a strict numeric time, not extra letters.
     */
    private fun parseEtaAndRemainingDistance(subText: String): Pair<String?, String?> {
        if (subText.isBlank()) return null to null
        // Nav apps use a variety of separators between the "22 min", "9.7 km"
        // and "2:12 ETA" segments: middle dot, bullet, hyphen, en/em dashes.
        val segments = subText.split("·", "•", "-", "–", "—", "|").map { it.trim() }.filter { it.isNotEmpty() }
        val timePattern = Regex("""(\d{1,2}):(\d{2})\s*(am|pm)?""", RegexOption.IGNORE_CASE)
        val distancePattern = Regex("""\d+(\.\d+)?\s*(km|mi|m)\b""", RegexOption.IGNORE_CASE)
        var eta: String? = null
        var distance: String? = null
        for (segment in segments) {
            if (eta == null) {
                val match = timePattern.find(segment)
                if (match != null) {
                    var hour = match.groupValues[1].toInt()
                    val minute = match.groupValues[2]
                    val meridiem = match.groupValues[3].lowercase()
                    if (meridiem == "pm" && hour < 12) hour += 12
                    if (meridiem == "am" && hour == 12) hour = 0
                    eta = "%02d:%s".format(hour, minute)
                }
            }
            if (distance == null && distancePattern.matches(segment)) {
                distance = segment
            }
        }
        return eta to distance
    }

    /** Best-effort maneuver label for the Direction screen, derived from the guessed turn icon. */
    private fun maneuverTextFor(icon: com.navigator.app.ble.BccuProtocol.TurnIcon?): String? {
        val name = icon?.name ?: return null
        return when {
            name.contains("LEFT") -> "Turn left onto"
            name.contains("RIGHT") -> "Turn right onto"
            name.contains("STRAIGHT") || name.contains("CONTINUE") -> "Continue onto"
            name.contains("UTURN") || name.contains("U_TURN") -> "Make a U-turn onto"
            else -> null
        }
    }

    /**
     * When the navigation app's own notification goes away (rider exited Maps,
     * arrived, or stopped guidance), tell the dash to clear its center guidance
     * view. Without this the last turn stayed frozen on the dash. The service
     * debounces the actual clear, so Maps' routine remove+repost during a route
     * doesn't cause a flicker.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isNavigationNotification(sbn, AppSettings(this))) {
            AppLogger.log("Notif", "Nav notification removed from ${sbn.packageName} - scheduling guidance clear")
            // TurnBeeper.reset() deliberately NOT called here: Maps routinely
            // removes+reposts its notification mid-route, and resetting the beep
            // dedup on every raw removal made the same turn beep again on each
            // repost. The service's debounced clearGuidance() resets it instead,
            // only when navigation has genuinely ended.
            BccuConnectionService.clearGuidanceIfRunning()
            NotificationRepository.clearNav()
        }
    }

    /**
     * Debug maneuver-icon harvester, OFF unless a marker file
     * `files/CAPTURE_MANEUVERS` exists (create it with adb during an emulator
     * session; it never exists for real users). Saves each distinct maneuver
     * largeIcon to `files/maps_capture/<label>__<hash>.png`, where <label> is the
     * nav app's own instruction text - giving a ground-truth-labeled bitmap set
     * to validate the text parser and the geometric fallback against real Maps.
     */
    private fun captureManeuverIconIfEnabled(sbn: StatusBarNotification, title: String, text: String) {
        try {
            val dir = filesDir
            val marker = java.io.File(dir, "CAPTURE_MANEUVERS")
            if (!marker.exists()) return
            val bitmap = TurnIconHeuristic.loadManeuverBitmap(this, sbn) ?: return
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val hash = pixels.contentHashCode()
            val label = (text.ifBlank { title }).lowercase()
                .replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifBlank { "unlabeled" }
            val outDir = java.io.File(dir, "maps_capture").apply { mkdirs() }
            val file = java.io.File(outDir, "${label}__${hash}.png")
            if (file.exists()) return
            java.io.FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            AppLogger.log("Capture", "Saved maneuver icon: text=${redact(text)} hash=$hash -> ${file.name}")
        } catch (e: Exception) {
            AppLogger.log("Capture", "!! capture failed: $e")
        }
    }

    /**
     * Redact notification content before logging. [AppLogger] mirrors every line
     * to a public `Download/OpenDash` copy, so raw titles/bodies/destinations
     * there would leak private messages and locations to shared storage
     * (readable by other apps with storage access, over USB/MTP and cloud
     * backup, and surviving app uninstall). Log only the length, never content.
     */
    private fun redact(s: String): String = if (s.isEmpty()) "<empty>" else "<${s.length} chars>"

    private fun isNavigationNotification(sbn: StatusBarNotification, settings: AppSettings): Boolean {
        val override = settings.navAppOverride
        if (override != null) {
            return sbn.packageName == override
        }
        // Any notification from a known nav app counts, not just ones tagged
        // CATEGORY_NAVIGATION - several maps apps don't reliably set that
        // category on their turn-by-turn notification, so requiring both
        // was silently dropping real navigation updates.
        return sbn.packageName in AppSettings.KNOWN_NAV_APPS
    }
}
