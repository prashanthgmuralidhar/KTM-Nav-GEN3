package com.navigator.app.notifications

import android.app.Notification
import android.os.Bundle

/**
 * Splits a nav app's notification into the dash's guidance fields.
 *
 * Google Maps ships two layouts:
 * - Classic: title = distance ("110 m"), text = road ("towards 1st Cross Rd").
 * - ProgressStyle (Android 16 "live update"): title = distance and instruction
 *   joined by a middle dot ("400 m · Turn left onto 1st Main Rd"), text = null,
 *   distance repeated in `android.shortCriticalText`, ETA in subText
 *   ("Arrive 8:15 pm"), and route length / distance covered in metres as
 *   progressMax / progress.
 */
object NavNotificationParser {

    data class NavFields(
        /** For TURN_DISTANCE; "" clears a stale distance, null leaves it untouched. */
        val distance: String?,
        /** The maneuver instruction / road, for TURN_ROAD and maneuver parsing. */
        val instruction: String?,
        /** Remaining trip distance derived from progress extras, if present. */
        val remaining: String?,
    )

    private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

    private val DISTANCE = Regex("""^\d+([.,]\d+)?\s*(m|km|mi|ft|yd)$""", RegexOption.IGNORE_CASE)
    private val SEPARATOR = Regex("""\s*[·•]\s*""")

    fun parse(extras: Bundle): NavFields {
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val shortCritical = extras.getCharSequence(EXTRA_SHORT_CRITICAL_TEXT)?.toString()?.trim()
        return split(title, text, shortCritical, remainingFromProgress(extras))
    }

    fun split(title: String, text: String, shortCritical: String?, remaining: String? = null): NavFields {
        if (text.isNotEmpty()) {
            return NavFields(title.ifEmpty { null }, text, remaining)
        }
        val parts = title.split(SEPARATOR, limit = 2)
        if (parts.size == 2 && DISTANCE.matches(parts[0])) {
            return NavFields(parts[0], parts[1].ifEmpty { null }, remaining)
        }
        if (DISTANCE.matches(title)) {
            return NavFields(title, null, remaining)
        }
        val distance = shortCritical?.takeIf { DISTANCE.matches(it) } ?: ""
        return NavFields(distance, title.ifEmpty { null }, remaining)
    }

    private fun remainingFromProgress(extras: Bundle): String? {
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val done = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        if (max <= 0 || done < 0 || done > max) return null
        return formatMetres(max - done)
    }

    fun formatMetres(m: Int): String = when {
        m < 1000 -> "$m m"
        m < 10_000 -> "%.1f km".format(java.util.Locale.US, m / 1000.0)
        else -> "${(m + 500) / 1000} km"
    }

    private val ABBREVIATIONS = listOf(
        Regex("""\bRoad\b""") to "Rd",
        Regex("""\bStreet\b""") to "St",
        Regex("""\bAvenue\b""") to "Ave",
        Regex("""\bHighway\b""") to "Hwy",
        Regex("""\bJunction\b""") to "Jn",
        Regex("""\btowards?\b""", RegexOption.IGNORE_CASE) to "to",
        Regex("""^Turn (left|right)\b""", RegexOption.IGNORE_CASE) to "$1",
    )

    /**
     * Shortens an instruction for the dash's road line so more of it survives
     * the firmware's truncation; the turn icon already shows the maneuver.
     */
    fun compactForDash(instruction: String): String {
        var s = instruction.trim()
        for ((re, rep) in ABBREVIATIONS) s = re.replace(s, rep)
        s = s.replace(Regex("""\s+"""), " ")
        return s.replaceFirstChar { it.uppercase() }
    }
}
