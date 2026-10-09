package com.gios.brightnoiselite.hw

import kotlin.test.Test
import kotlin.test.assertEquals

class WheelGuardTest {

    private var now = 0L
    private val guard = WheelGuard(armNotches = 2, idleMs = 1_500L, clock = { now })

    @Test
    fun aSingleStrayNotchDoesNothing() {
        assertEquals(0, guard.accept(1))
        now += 5_000
        assertEquals(0, guard.accept(-1))
    }

    @Test
    fun theSecondNotchReleasesBoth() {
        assertEquals(0, guard.accept(1))
        now += 40
        assertEquals(2, guard.accept(1))
        now += 40
        assertEquals(1, guard.accept(1))
    }

    @Test
    fun aBackAndForthStillMovesOneWay() {
        assertEquals(0, guard.accept(1))
        now += 40
        assertEquals(-1, guard.accept(-1))
    }

    @Test
    fun aPauseRearmsTheGuard() {
        guard.accept(-1)
        now += 40
        assertEquals(-2, guard.accept(-1))
        now += 2_000
        assertEquals(0, guard.accept(-1))
    }
}
