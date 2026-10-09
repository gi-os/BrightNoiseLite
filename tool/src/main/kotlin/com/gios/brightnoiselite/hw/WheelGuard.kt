package com.gios.brightnoiselite.hw

import kotlin.math.sign

/**
 * Notches, minus the stray ones. Ported from the full app's wheel handling.
 *
 * The wheel sits under a thumb and catches stray brushes, and here a stray notch would change
 * the volume of something you are trying to fall asleep to. So the first notch after a pause
 * buys nothing on its own: it is held, and only a second notch releases both. Once turning,
 * every notch applies straight away until [idleMs] passes with the wheel still.
 */
class WheelGuard(
    private val armNotches: Int = 2,
    private val idleMs: Long = 1_500L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var armed = false
    private var held = 0
    private var count = 0
    private var last = Long.MIN_VALUE

    /** Feeds one event of [notches] (positive for up) and returns how many to act on now. */
    fun accept(notches: Int): Int {
        val now = clock()
        if (last == Long.MIN_VALUE || now - last > idleMs) {
            armed = false
            held = 0
            count = 0
        }
        last = now
        if (armed) return notches
        held += notches
        count++
        if (count < armNotches) return 0
        armed = true
        // Release what the guard was holding, so nothing deliberate is lost.
        val release = if (held != 0) held else notches.sign
        held = 0
        return release
    }
}
