package com.navigator.app.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import com.navigator.app.ble.BccuProtocol
import com.navigator.app.logging.AppLogger

/**
 * Resolves a navigation notification into a dash [BccuProtocol.TurnIcon].
 *
 * Three layers, in order of confidence:
 *
 * 1. **Text maneuver parsing** (new, primary). Most nav apps put the maneuver
 *    in words somewhere in the notification ("Sharp left", "Make a U-turn",
 *    "3rd exit", "Keep right", "Slight left onto…"). Parsing that maps
 *    directly onto the full icon table - including sharp/slight severity,
 *    U-turns, roundabout exits and keep-lane maneuvers - which the old
 *    left/right-ink heuristic could never express. This is what fixes both
 *    "hard turns show as a slight arrow" (a sharp left now maps to HEAVY_LEFT,
 *    not LIGHT_LEFT) and "other symbols never show" (u-turns/roundabouts/keep).
 * 2. **Exact bitmap-hash cache** ([IconHashCache]) for icons a maneuver word
 *    wasn't found for - reliable once a hash has been confirmed.
 * 3. **Graduated ink heuristic** as a last resort: left/right ink dominance
 *    now grades into LIGHT / QUITE / HEAVY (and straight) by how lopsided the
 *    arrow is, instead of always emitting a single LIGHT_* value.
 */
object TurnIconHeuristic {

    // Matches the classifier's 96x96 input so it never sees an upscaled, blurred icon.
    private const val SAMPLE_SIZE = 96

    // Severity bands, calibrated from real Google Maps arrows on hardware
    // (2026-07-06 logs): a straight arrow measured a full-height left/right ink
    // ratio of ~1.00, while a real 90-degree "Turn left" measured only ~1.14 -
    // the tall vertical shaft dilutes the ratio, so the OLD 1.30 cutoff for a
    // "normal" turn mis-called every Maps turn as a slight one. These cutoffs
    // put ~1.14 into QUITE (a proper turn) instead of LIGHT.
    private const val STRAIGHT_MAX = 1.05   // below this -> going straight
    private const val LIGHT_MAX = 1.12      // slight turn
    private const val QUITE_MAX = 1.40      // normal ~90-degree turn; above -> sharp
    // The arrowhead lives in the top of the icon; measuring only the top rows
    // separates sharp turns (head swung far to one side) from gentle ones far
    // better than the shaft-diluted full-height ratio, so it's used to escalate.
    private const val TOP_REGION_FRACTION = 0.45
    private const val TOP_QUITE_RATIO = 1.8
    private const val TOP_HEAVY_RATIO = 3.0
    // A genuinely-straight arrow has a near-1.0 arrowhead (top-region) ratio
    // (measured 1.005 on real Maps). A fork/keep whose full-image ratio still
    // looks straight (shaft dominates) but whose arrowhead clearly leans - e.g.
    // the real "towards A / B" fork measured full=1.024 but top=1.592 - must NOT
    // be called GO_STRAIGHT. Above this top-ratio we treat it as at least a
    // slight turn in the leaning direction. Comfortably above straight's ~1.00
    // and below a real 90-degree turn's ~2.05, so neither is reclassified.
    private const val STRAIGHT_TOP_MAX = 1.35

    /**
     * [instruction] is only the maneuver line (e.g. "Turn left onto 1st Main Rd"),
     * never the ETA/subtext: Maps' "Arrive 8:15 pm" subtext otherwise reads as
     * an arrival and turns every maneuver into the destination flag.
     */
    fun guessDirection(context: Context, sbn: StatusBarNotification, instruction: String?): BccuProtocol.TurnIcon? {
        val bitmap = loadIconBitmap(context, sbn)

        // 1. User's manual calibration (exact per-bitmap override) wins. Also
        //    saves unseen icons so the calibration screen can list them.
        bitmap?.let { IconHashCache.lookup(it, context) }?.let { return it }

        parseManeuverFromText(instruction.orEmpty().lowercase())?.let {
            AppLogger.log("TurnIcon", "Maneuver from text -> ${it.name}")
            return it
        }

        if (bitmap == null) return null

        // 2. On-device TFLite classifier (ported from the KTM Gen-3 companion
        //    app). Purpose-trained on nav maneuver icons, so it recognises the
        //    exact maneuver - including every roundabout exit - which the pixel
        //    heuristic below cannot. This is the real fix for Google Maps icons.
        ManeuverClassifier.classify(context, bitmap)?.let {
            AppLogger.log("TurnIcon", "Maneuver from model -> ${it.name}")
            return it
        }

        // 3. Last-resort ink heuristic (only if the model isn't confident / absent).
        val (leftInk, rightInk) = measureLeftRightInk(bitmap, 0.0)
        val (topLeft, topRight) = measureLeftRightInk(bitmap, TOP_REGION_FRACTION)
        if (leftInk + rightInk <= 0) return null

        // Direction from the arrowhead (top region) - more reliable than the
        // full image, whose bottom shaft is centred for every maneuver.
        val leftDominant = if (topLeft + topRight > 0) topLeft >= topRight else leftInk >= rightInk
        val fullRatio = ratio(leftInk, rightInk)
        val topRatio = ratio(topLeft, topRight)
        AppLogger.log("TurnIcon", "Icon heuristic: full L=$leftInk R=$rightInk (r=${"%.3f".format(fullRatio)}), top L=$topLeft R=$topRight (r=${"%.3f".format(topRatio)})")

        val severity = when {
            // Straight only when BOTH the full image and the arrowhead are
            // near-symmetric - a leaning arrowhead (STRAIGHT_TOP_MAX) means a
            // fork/keep/slight turn, never straight, even if the shaft-diluted
            // full ratio looks straight.
            fullRatio < STRAIGHT_MAX && topRatio < STRAIGHT_TOP_MAX -> 0 // straight
            fullRatio >= QUITE_MAX || topRatio >= TOP_HEAVY_RATIO -> 3  // heavy/sharp
            fullRatio >= LIGHT_MAX || topRatio >= TOP_QUITE_RATIO -> 2  // quite/normal
            else -> 1 // light/slight
        }
        return when (severity) {
            0 -> BccuProtocol.TurnIcon.GO_STRAIGHT
            1 -> if (leftDominant) BccuProtocol.TurnIcon.LIGHT_LEFT else BccuProtocol.TurnIcon.LIGHT_RIGHT
            2 -> if (leftDominant) BccuProtocol.TurnIcon.QUITE_LEFT else BccuProtocol.TurnIcon.QUITE_RIGHT
            else -> if (leftDominant) BccuProtocol.TurnIcon.HEAVY_LEFT else BccuProtocol.TurnIcon.HEAVY_RIGHT
        }
    }

    private fun ratio(a: Long, b: Long): Double {
        val hi = maxOf(a, b); val lo = minOf(a, b)
        return hi.toDouble() / lo.coerceAtLeast(1)
    }

    // Where the maneuver phrase ends and the road name begins, so a road called
    // "Right Bank Rd" or "Destination Mall" can't steer the icon.
    private val ROAD_START = Regex("""\s(onto|toward|towards|on|into|to|at)\s""")

    /**
     * Map an instruction string to an icon. Order matters - the most specific
     * phrases (sharp/slight/u-turn/roundabout/keep) are checked before the
     * generic "turn left/right", so a "sharp left" isn't swallowed by "left".
     * Matches whole words of the maneuver phrase only. Returns null when no
     * maneuver word is present (falls through to bitmap).
     */
    internal fun parseManeuverFromText(full: String): BccuProtocol.TurnIcon? {
        if (full.isBlank()) return null
        // Roundabout exits ("take the 2nd exit onto X") sit before the road
        // name too, but "at the roundabout, take..." starts with a cut word.
        val roundabout = Regex("""\b(roundabout|rotary)\b""").containsMatchIn(full)
        val t = if (roundabout) full else ROAD_START.find(full)?.let { full.substring(0, it.range.first) } ?: full
        fun has(vararg words: String) = words.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(t) }
        val left = has("left")
        val right = has("right")

        // Roundabouts: "roundabout" / "rotary" + an exit ordinal.
        if (roundabout) {
            val exit = roundaboutExit(full)
            if (exit != null) {
                val idx = exit.coerceIn(1, 16)
                return BccuProtocol.TurnIcon.entries.first { it.name == "RAB_SECT_${idx}_RH" }
            }
            return BccuProtocol.TurnIcon.RAB_SECT_2_RH
        }

        // U-turn.
        if (has("u-turn", "u turn", "uturn", "make a u")) {
            return if (right) BccuProtocol.TurnIcon.UTURN_RIGHT else BccuProtocol.TurnIcon.UTURN_LEFT
        }

        // Sharp / hard.
        if (has("sharp", "hard") && (left || right)) {
            return if (left) BccuProtocol.TurnIcon.HEAVY_LEFT else BccuProtocol.TurnIcon.HEAVY_RIGHT
        }
        // Slight / soft / bear.
        if (has("slight", "soft", "bear") && (left || right)) {
            return if (left) BccuProtocol.TurnIcon.LIGHT_LEFT else BccuProtocol.TurnIcon.LIGHT_RIGHT
        }
        // Keep / stay in lane.
        if (has("keep", "stay") && (left || right)) {
            return if (left) BccuProtocol.TurnIcon.KEEP_LEFT else BccuProtocol.TurnIcon.KEEP_RIGHT
        }
        // Merge onto a highway.
        if (has("merge")) {
            return if (left) BccuProtocol.TurnIcon.ENTER_HIGHWAY_LEFT_LANE else BccuProtocol.TurnIcon.ENTER_HIGHWAY_RIGHT_LANE
        }
        // Take a motorway exit / off-ramp.
        if (has("exit", "ramp", "off-ramp") && (left || right)) {
            return if (left) BccuProtocol.TurnIcon.LEAVE_HIGHWAY_LEFT_LANE else BccuProtocol.TurnIcon.LEAVE_HIGHWAY_RIGHT_LANE
        }
        // Arrival ("Destination will be on the right", "You have arrived").
        if (has("destination", "arrive", "arrived", "arriving")) {
            return BccuProtocol.TurnIcon.END
        }
        // Plain turn: this is a normal ~90° turn, so QUITE_* (not LIGHT_*).
        if (left && has("turn")) return BccuProtocol.TurnIcon.QUITE_LEFT
        if (right && has("turn")) return BccuProtocol.TurnIcon.QUITE_RIGHT
        // Straight / continue / head.
        if (has("straight", "continue", "head", "proceed")) {
            return BccuProtocol.TurnIcon.GO_STRAIGHT
        }
        // A bare directional word with no other maneuver context - treat as a turn.
        if (left && !right) return BccuProtocol.TurnIcon.QUITE_LEFT
        if (right && !left) return BccuProtocol.TurnIcon.QUITE_RIGHT
        return null
    }

    /** Pull a roundabout exit number from phrases like "3rd exit" or "exit 2". */
    private fun roundaboutExit(t: String): Int? {
        Regex("""(\d+)(?:st|nd|rd|th)?\s+exit""").find(t)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("""exit\s+(\d+)""").find(t)?.let { return it.groupValues[1].toIntOrNull() }
        listOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5).forEach { (w, n) ->
            if (t.contains("$w exit")) return n
        }
        return null
    }

    /** Public wrapper: render a nav notification's large maneuver icon to the sampled bitmap (for debug capture). */
    fun loadManeuverBitmap(context: Context, sbn: StatusBarNotification): Bitmap? = loadIconBitmap(context, sbn)

    private fun loadIconBitmap(context: Context, sbn: StatusBarNotification): Bitmap? {
        val icon = sbn.notification.getLargeIcon() ?: return null
        val drawable = try {
            icon.loadDrawable(context)
        } catch (e: Exception) {
            AppLogger.log("TurnIcon", "!! Failed to load notification icon: $e")
            null
        } ?: return null
        return drawableToBitmap(drawable)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            AppLogger.log("TurnIcon", "!! Failed to render icon to bitmap: $e")
            null
        }
    }

    /**
     * Sum left-half vs right-half ink alpha. [fromYFraction] restricts the scan
     * to the rows starting at that fraction of the height downward from the top
     * (0.0 = whole image; 0.55 = top 45% only, where the arrowhead sits).
     */
    private fun measureLeftRightInk(bitmap: Bitmap, fromYFraction: Double): Pair<Long, Long> {
        var leftInk = 0L
        var rightInk = 0L
        val halfWidth = bitmap.width / 2
        val startY = 0
        val endY = if (fromYFraction <= 0.0) bitmap.height
        else (bitmap.height * fromYFraction).toInt().coerceIn(1, bitmap.height)
        for (y in startY until endY) {
            for (x in 0 until bitmap.width) {
                val alpha = (bitmap.getPixel(x, y) ushr 24) and 0xFF
                if (x < halfWidth) leftInk += alpha else rightInk += alpha
            }
        }
        return leftInk to rightInk
    }
}
