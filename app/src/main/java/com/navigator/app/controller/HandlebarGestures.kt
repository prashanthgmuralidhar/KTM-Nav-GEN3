package com.navigator.app.controller

import com.navigator.app.ble.BccuProtocol.HandlebarButton

enum class GestureKind { SINGLE, DOUBLE, LONG }

data class RemoteGesture(val button: HandlebarButton, val kind: GestureKind)

/**
 * Turns the dash's raw button masks into tap / double-tap / hold gestures.
 * Every gesture is decided on release: a hold is a press of at least
 * [LONG_PRESS_MS]; a second release within [DOUBLE_TAP_WINDOW_MS] is a double.
 * Only buttons for which [supportsDouble] is true wait out that window - the
 * rest emit SINGLE immediately, so their taps feel instant.
 *
 * Entering remote-control mode is itself a Back hold on the dash, and if the
 * rider is still holding when the mode switches on, the dash reports that
 * same press to us. [onActivated] swallows it: buttons already held at that
 * moment, and a Back press starting within [ACTIVATION_GRACE_MS], are ignored
 * until released.
 *
 * Pure Kotlin so it can be unit tested; timers come from [schedule], which
 * runs a block after a delay and returns a function that cancels it. Not
 * thread-safe: call everything from one thread.
 */
class HandlebarGestureRecognizer(
    private val supportsDouble: (HandlebarButton) -> Boolean,
    private val schedule: (delayMs: Long, block: () -> Unit) -> () -> Unit,
    private val emit: (RemoteGesture) -> Unit,
) {
    companion object {
        // Measured on real hardware by the v0.3.0-beta release build.
        const val LONG_PRESS_MS = 400L
        const val DOUBLE_TAP_WINDOW_MS = 350L
        const val ACTIVATION_GRACE_MS = 1_000L
    }

    private var lastMask = 0
    private var rawMask = 0
    private var ignoredMask = 0
    private var graceUntilMs = Long.MIN_VALUE
    private val pressedAt = HashMap<HandlebarButton, Long>()
    private val pendingSingle = HashMap<HandlebarButton, () -> Unit>()

    /** Remote-control mode just switched on; [mask] is what's held right now. */
    fun onActivated(mask: Int, nowMs: Long) {
        cancelPending()
        ignoredMask = mask
        rawMask = mask
        graceUntilMs = nowMs + ACTIVATION_GRACE_MS
    }

    fun onMask(mask: Int, nowMs: Long) {
        val back = 1 shl HandlebarButton.BACK.bitIndex
        val newlyPressed = mask and rawMask.inv()
        if (nowMs < graceUntilMs && newlyPressed and back != 0) ignoredMask = ignoredMask or back
        ignoredMask = ignoredMask and mask
        rawMask = mask
        track(mask and ignoredMask.inv(), nowMs)
    }

    private fun track(mask: Int, nowMs: Long) {
        for (button in HandlebarButton.entries) {
            val was = (lastMask shr button.bitIndex) and 1 == 1
            val now = (mask shr button.bitIndex) and 1 == 1
            if (!was && now) {
                pressedAt[button] = nowMs
            } else if (was && !now) {
                val held = pressedAt.remove(button)?.let { nowMs - it } ?: 0L
                onRelease(button, held)
            }
        }
        lastMask = mask
    }

    /** Drop presses in flight and taps still waiting for a possible second tap. */
    fun cancelPending() {
        pendingSingle.values.forEach { it() }
        pendingSingle.clear()
        pressedAt.clear()
        lastMask = 0
        rawMask = 0
        ignoredMask = 0
    }

    private fun onRelease(button: HandlebarButton, heldMs: Long) {
        val pending = pendingSingle.remove(button)
        if (heldMs >= LONG_PRESS_MS) {
            pending?.invoke()
            emit(RemoteGesture(button, GestureKind.LONG))
            return
        }
        if (pending != null) {
            pending()
            emit(RemoteGesture(button, GestureKind.DOUBLE))
            return
        }
        if (supportsDouble(button)) {
            pendingSingle[button] = schedule(DOUBLE_TAP_WINDOW_MS) {
                pendingSingle.remove(button)
                emit(RemoteGesture(button, GestureKind.SINGLE))
            }
        } else {
            emit(RemoteGesture(button, GestureKind.SINGLE))
        }
    }
}
