package com.gios.brightnoiselite.audio

// DspTest is carried over from the full app, which used JUnit's message-first assertions.
// These keep its body unchanged on top of kotlin.test.

internal fun assertTrue(message: String, condition: Boolean) =
    kotlin.test.assertTrue(condition, message)

internal fun assertEquals(message: String, expected: Any?, actual: Any?) =
    kotlin.test.assertEquals(expected, actual, message)
