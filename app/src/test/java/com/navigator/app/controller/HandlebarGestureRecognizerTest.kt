package com.navigator.app.controller

import com.navigator.app.ble.BccuProtocol.HandlebarButton
import com.navigator.app.ble.BccuProtocol.HandlebarButton.BACK
import com.navigator.app.ble.BccuProtocol.HandlebarButton.SET
import com.navigator.app.ble.BccuProtocol.HandlebarButton.UP
import com.navigator.app.controller.GestureKind.DOUBLE
import com.navigator.app.controller.GestureKind.LONG
import com.navigator.app.controller.GestureKind.SINGLE
import org.junit.Assert.assertEquals
import org.junit.Test

class HandlebarGestureRecognizerTest {

    private var now = 0L
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val out = mutableListOf<RemoteGesture>()
    private var backDoubles = true

    private val recognizer = HandlebarGestureRecognizer(
        supportsDouble = { it == BACK && backDoubles },
        schedule = { delay, block ->
            val t = (now + delay) to block
            timers += t
            ({ timers.remove(t) })
        },
        emit = { out += it },
    )

    private fun advance(ms: Long) {
        now += ms
        timers.filter { it.first <= now }.forEach { timers.remove(it); it.second() }
    }

    private fun bit(b: HandlebarButton) = 1 shl b.bitIndex

    private fun tap(b: HandlebarButton, holdMs: Long = 80) {
        recognizer.onMask(bit(b), now)
        advance(holdMs)
        recognizer.onMask(0, now)
    }

    @Test fun upTapIsImmediate() {
        tap(UP)
        assertEquals(listOf(RemoteGesture(UP, SINGLE)), out)
    }

    @Test fun upHoldIsLong() {
        tap(UP, holdMs = 450)
        assertEquals(listOf(RemoteGesture(UP, LONG)), out)
    }

    @Test fun backTapWaitsForDoubleWindow() {
        tap(BACK)
        assertEquals(emptyList<RemoteGesture>(), out)
        advance(HandlebarGestureRecognizer.DOUBLE_TAP_WINDOW_MS)
        assertEquals(listOf(RemoteGesture(BACK, SINGLE)), out)
    }

    @Test fun backDoubleTap() {
        tap(BACK)
        advance(100)
        tap(BACK)
        advance(1000)
        assertEquals(listOf(RemoteGesture(BACK, DOUBLE)), out)
    }

    @Test fun backTapIsImmediateWhenDoublesDisabled() {
        backDoubles = false
        tap(BACK)
        assertEquals(listOf(RemoteGesture(BACK, SINGLE)), out)
    }

    @Test fun cancelDropsPendingBack() {
        tap(BACK)
        recognizer.cancelPending()
        advance(1000)
        assertEquals(emptyList<RemoteGesture>(), out)
    }

    @Test fun backHeldAtActivationIsIgnored() {
        recognizer.onActivated(bit(BACK), now)
        advance(200)
        recognizer.onMask(0, now)
        advance(1000)
        assertEquals(emptyList<RemoteGesture>(), out)
    }

    @Test fun backReportedJustAfterActivationIsIgnored() {
        recognizer.onActivated(0, now)
        advance(50)
        tap(BACK, holdMs = 150)
        advance(1000)
        assertEquals(emptyList<RemoteGesture>(), out)
    }

    @Test fun backAfterGraceWorks() {
        recognizer.onActivated(0, now)
        advance(HandlebarGestureRecognizer.ACTIVATION_GRACE_MS + 10)
        tap(BACK)
        advance(1000)
        assertEquals(listOf(RemoteGesture(BACK, SINGLE)), out)
    }

    @Test fun otherButtonsWorkDuringGrace() {
        recognizer.onActivated(0, now)
        advance(100)
        tap(UP)
        assertEquals(listOf(RemoteGesture(UP, SINGLE)), out)
    }

    @Test fun simultaneousButtonsAreIndependent() {
        recognizer.onMask(bit(UP) or bit(SET), now)
        advance(50)
        recognizer.onMask(bit(UP), now)
        advance(400)
        recognizer.onMask(0, now)
        assertEquals(listOf(RemoteGesture(SET, SINGLE), RemoteGesture(UP, LONG)), out)
    }
}
