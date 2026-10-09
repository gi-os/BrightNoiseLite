package com.gios.brightnoiselite.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsTest {

    @Test
    fun picksRoundTrip() {
        for (id in SoundId.entries) assertEquals(id, Settings.decodePick(Settings.encodePick(id), null))
        assertNull(Settings.decodePick(Settings.encodePick(null), SoundId.RAIN))
    }

    @Test
    fun unknownOrMissingPickFallsBack() {
        assertEquals(SoundId.RAIN, Settings.decodePick("THEREMIN", SoundId.RAIN))
        assertEquals(SoundId.FAN, Settings.decodePick(null, SoundId.FAN))
    }

    @Test
    fun labels() {
        assertEquals("Rain", Settings().nowPlayingLabel)
        assertEquals("Rain + Fan", Settings(pickB = SoundId.FAN).nowPlayingLabel)
        assertEquals("Fan", Settings(pickA = null, pickB = SoundId.FAN).nowPlayingLabel)
        assertEquals("Silence", Settings(pickA = null).nowPlayingLabel)
    }

    @Test
    fun levelsAreClamped() {
        assertEquals(1f, Settings().withLevel(Slot.B, 3f).levelB)
        assertEquals(0f, Settings().withLevel(Slot.A, -1f).levelA)
    }

    @Test
    fun countdown() {
        assertEquals("0:07", formatCountdown(7))
        assertEquals("14:59", formatCountdown(899))
        assertEquals("1:05:09", formatCountdown(3909))
        assertEquals("0:00", formatCountdown(-4))
    }
}
