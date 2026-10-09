package com.gios.brightnoiselite.audio

/** The two mixer layers. */
enum class Slot { A, B }

/**
 * Everything the user can set. A null pick means the layer is off ("None" in the full app).
 * Defaults are the full app's: rain on A, B off, and the master high on purpose, because at
 * 0.7 the phone still had to be turned all the way up to match anything else.
 */
data class Settings(
    val pickA: SoundId? = SoundId.RAIN,
    val pickB: SoundId? = null,
    val levelA: Float = 1f,
    val levelB: Float = 0.45f,
    val master: Float = 0.9f,
    /** Minutes, or 0 for no timer. */
    val timerMinutes: Int = 0,
) {
    fun pick(slot: Slot): SoundId? = if (slot == Slot.A) pickA else pickB

    fun withPick(slot: Slot, id: SoundId?): Settings =
        if (slot == Slot.A) copy(pickA = id) else copy(pickB = id)

    fun level(slot: Slot): Float = if (slot == Slot.A) levelA else levelB

    fun withLevel(slot: Slot, v: Float): Settings = v.coerceIn(0f, 1f).let {
        if (slot == Slot.A) copy(levelA = it) else copy(levelB = it)
    }

    val hasSound: Boolean get() = pickA != null || pickB != null

    /** "Rain + Fan", "Rain", or "Silence". */
    val nowPlayingLabel: String
        get() {
            val a = pickA?.label
            val b = pickB?.label
            return when {
                a != null && b != null -> "$a + $b"
                a != null -> a
                b != null -> b
                else -> "Silence"
            }
        }

    companion object {
        val TIMER_PRESETS = listOf(0, 15, 30, 45, 60, 90, 120)

        /** Stored form of a pick: the enum name, or an empty string for off. */
        fun encodePick(id: SoundId?): String = id?.name ?: ""

        /**
         * Reads a stored pick back. An unknown name means the list of sounds changed under a
         * saved setting, and [fallback] is used rather than silently turning the layer off.
         */
        fun decodePick(raw: String?, fallback: SoundId?): SoundId? = when {
            raw == null -> fallback
            raw.isEmpty() -> null
            else -> SoundId.entries.firstOrNull { it.name == raw } ?: fallback
        }
    }
}

/** "1:05:09", "14:59" or "0:07" for a countdown in whole seconds. */
fun formatCountdown(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
