package com.gios.brightnoiselite.hw

import android.view.KeyEvent

/** The wheel's two directions, as they arrive from the sensor. */
enum class LightKey {
    /** Wheel turned towards the top of the phone. */
    WheelUp,

    /** Wheel turned towards the bottom of the phone. */
    WheelDown,
}

/**
 * Recognising the Light Phone III's wheel, the same way the full app does.
 *
 * The wheel is a `Pixart pat9126ja` optical sensor that emits one DOWN+UP key pair per notch.
 * Light patched `/system/usr/keylayout/Generic.kl` to relabel its scancodes:
 *
 * ```
 * key 19    WHEEL_CCW      # wheel up      (Pixart, was R)
 * key 20    WHEEL_CW       # wheel down    (Pixart, was T)
 * ```
 *
 * `WHEEL_CCW` and `WHEEL_CW` are not AOSP keycodes, so their integer values are resolved from
 * the label at runtime. If that fails, the raw scancode is used, but only when it comes from
 * the wheel's own device: scancode 19 is also `r` on a paired keyboard.
 */
object LightKeys {

    private const val SCAN_WHEEL_UP = 19
    private const val SCAN_WHEEL_DOWN = 20

    private val trustedDevices = setOf("Pixart pat9126ja")

    private val byScanCode = mapOf(
        SCAN_WHEEL_UP to LightKey.WheelUp,
        SCAN_WHEEL_DOWN to LightKey.WheelDown,
    )

    private val byKeyCode: Map<Int, LightKey> = buildMap {
        putLabel("WHEEL_CCW", LightKey.WheelUp)
        putLabel("WHEEL_CW", LightKey.WheelDown)
    }

    private fun MutableMap<Int, LightKey>.putLabel(label: String, key: LightKey) {
        val code = runCatching { KeyEvent.keyCodeFromString(label) }
            .getOrDefault(KeyEvent.KEYCODE_UNKNOWN)
        if (code != KeyEvent.KEYCODE_UNKNOWN) put(code, key)
    }

    /** Which way the wheel turned for this key, or null if it was some other key. */
    fun of(keyCode: Int, event: KeyEvent): LightKey? {
        byKeyCode[keyCode]?.let { return it }
        val device = event.device?.name ?: return null
        if (device in trustedDevices) return byScanCode[event.scanCode]
        return null
    }
}
