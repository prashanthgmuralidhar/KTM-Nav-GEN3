package com.navigator.app.notifications

import com.navigator.app.ble.BccuProtocol.TurnIcon
import org.junit.Assert.assertEquals
import org.junit.Test

/** Strings taken verbatim from a Google Maps ProgressStyle ride log (Duke 250, Oct 2026). */
class NavNotificationParsingTest {

    @Test fun progressStyleTitleSplitsIntoDistanceAndInstruction() {
        val f = NavNotificationParser.split("400 m · Turn left onto 1st Main Rd", "", "400 m")
        assertEquals("400 m", f.distance)
        assertEquals("Turn left onto 1st Main Rd", f.instruction)
    }

    @Test fun instructionOnlyTitleClearsDistance() {
        val f = NavNotificationParser.split("Head toward 8th Main Rd", "", "")
        assertEquals("", f.distance)
        assertEquals("Head toward 8th Main Rd", f.instruction)
    }

    @Test fun classicLayoutUnchanged() {
        val f = NavNotificationParser.split("110 m", "towards 1st Cross Rd", null)
        assertEquals("110 m", f.distance)
        assertEquals("towards 1st Cross Rd", f.instruction)
    }

    @Test fun compactsForDash() {
        assertEquals("Left onto 1st Main Rd", NavNotificationParser.compactForDash("Turn left onto 1st Main Rd"))
        assertEquals("Head to 8th Main Rd", NavNotificationParser.compactForDash("Head toward 8th Main Road"))
    }

    @Test fun formatsRemaining() {
        assertEquals("11 km", NavNotificationParser.formatMetres(11_482))
        assertEquals("2.5 km", NavNotificationParser.formatMetres(2_480))
        assertEquals("350 m", NavNotificationParser.formatMetres(350))
    }

    private fun icon(s: String) = TurnIconHeuristic.parseManeuverFromText(s.lowercase())

    @Test fun maneuverWordsMapToIcons() {
        assertEquals(TurnIcon.QUITE_LEFT, icon("Turn left onto 1st Main Rd"))
        assertEquals(TurnIcon.GO_STRAIGHT, icon("Head toward 8th Main Rd"))
        assertEquals(TurnIcon.KEEP_LEFT, icon("Keep left to stay on NH 44"))
        assertEquals(TurnIcon.KEEP_RIGHT, icon("Stay right toward Left Bank Rd"))
        assertEquals(TurnIcon.QUITE_RIGHT, icon("Turn right onto Destination Mall Rd"))
        assertEquals(TurnIcon.END, icon("Destination will be on the right"))
        assertEquals(TurnIcon.RAB_SECT_2_RH, icon("At the roundabout, take the 2nd exit onto MG Rd"))
    }

    @Test fun statusLinesAreNotManeuvers() {
        assertEquals(null, icon("Starting navigation…"))
        assertEquals(null, icon("Rerouting..."))
    }
}
