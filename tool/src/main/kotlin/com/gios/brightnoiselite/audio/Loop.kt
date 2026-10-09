package com.gios.brightnoiselite.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns the endless generators into something a file player can play for hours.
 *
 * The SDK has no streaming PCM output that keeps going once the tool leaves the screen. The
 * only detached path is the SDK's file player. So each layer is rendered once into a loop of
 * [LOOP_SECONDS], and the join is hidden by rendering a few seconds past the end and
 * crossfading them into the start. Played back to back, the loop's last sample runs straight
 * into material that was generated after it, so there is no seam, only a long repeat period.
 *
 * Everything here is plain arrays and arithmetic, so it runs and is tested on the JVM.
 */
object Loop {

    /** How long one loop lasts before it repeats. Thunder and swell need minutes, not seconds. */
    const val LOOP_SECONDS = 180

    /** Length of the crossfade that folds the loop's end into its start. */
    const val CROSSFADE_SECONDS = 4

    /** Fade-in at the start of a session, so pressing play is not a thud in the speaker. */
    const val HEAD_SECONDS = 2

    /** The sleep timer ramps the volume down over this long. Same as the full app. */
    const val FADE_SECONDS = 25

    /**
     * Makeup gain after the layer mix. Same value and same reasoning as the full app's engine:
     * the generators sit near 0.12 RMS, and this plus the limiter lands them near music level.
     */
    const val MAKEUP = 1.7f

    private const val BLOCK = 1024
    private const val FULL_SCALE = 32767f

    const val LOOP_FRAMES: Int = LOOP_SECONDS * SAMPLE_RATE

    /**
     * Renders [gen] into a seamless loop of [frames] samples, stored as 16-bit PCM.
     *
     * Every generator ends in [softClip], so its output is already inside -1..1 and full-scale
     * shorts lose nothing that matters. The first [fadeFrames] samples are held as floats, the
     * middle goes straight to the output, and the [fadeFrames] rendered after the end are
     * blended over the start with an equal-power curve. The two are uncorrelated noise, so
     * equal power is what keeps the level flat through the join.
     */
    fun renderLayer(
        gen: Generator,
        frames: Int = LOOP_FRAMES,
        fadeFrames: Int = CROSSFADE_SECONDS * SAMPLE_RATE,
    ): ShortArray {
        require(fadeFrames in 1 until frames) { "crossfade must be shorter than the loop" }
        val out = ShortArray(frames)
        val head = FloatArray(fadeFrames)
        val buf = FloatArray(BLOCK)
        val total = frames + fadeFrames
        var pos = 0
        while (pos < total) {
            val n = minOf(BLOCK, total - pos)
            gen.render(buf, n)
            for (i in 0 until n) {
                val at = pos + i
                val x = buf[i]
                when {
                    at < fadeFrames -> head[at] = x
                    at < frames -> out[at] = toShort(x)
                    else -> {
                        val k = at - frames
                        val t = (k + 0.5) / fadeFrames * (PI / 2)
                        // At k = 0 this is nearly all tail, which carries on from out[frames - 1].
                        out[k] = toShort((head[k] * sin(t) + x * cos(t)).toFloat())
                    }
                }
            }
            pos += n
        }
        return out
    }

    /**
     * Mixes two rendered layers through the same gain law, makeup gain, look-ahead limiter
     * and soft clip as the full app's live engine.
     *
     * The limiter runs around the loop rather than along it: it is warmed up on the loop's own
     * end and flushed with the loop's own start, so its state at the join is what it would be
     * in an endless stream and the repeat does not click.
     */
    fun mix(
        a: ShortArray?,
        b: ShortArray?,
        levelA: Float,
        levelB: Float,
        master: Float,
    ): ShortArray {
        val frames = (a ?: b)?.size ?: return ShortArray(0)
        require(a == null || a.size == frames) { "layers differ in length" }
        require(b == null || b.size == frames) { "layers differ in length" }

        val la = levelA.coerceIn(0f, 1f)
        val lb = levelB.coerceIn(0f, 1f)
        val m = master.coerceIn(0f, 1f)
        // Perceptual taper, as in the live engine: a linear level feels dead until its last quarter.
        val ga = if (a == null) 0f else la * la
        val gb = if (b == null) 0f else lb * lb
        val gm = m * m * MAKEUP

        val limiter = Limiter()
        val delay = limiter.lookaheadFrames
        val warm = minOf(frames, SAMPLE_RATE)
        val out = ShortArray(frames)
        for (k in -warm until frames + delay) {
            val i = k.mod(frames)
            var dry = 0f
            if (a != null) dry += a[i] / FULL_SCALE * ga
            if (b != null) dry += b[i] / FULL_SCALE * gb
            val y = softClip(limiter.process(dry * gm))
            val outAt = k - delay
            if (outAt in 0 until frames) out[outAt] = toShort(y)
        }
        return out
    }

    /**
     * The opening of a session: the last [frames] samples of [loop], faded in. It ends on the
     * loop's final sample, so the loop that follows starts without a jump.
     */
    fun head(loop: ShortArray, frames: Int = HEAD_SECONDS * SAMPLE_RATE): ShortArray {
        val n = frames.coerceIn(1, loop.size)
        val start = loop.size - n
        return ShortArray(n) { i ->
            val g = (i + 1).toFloat() / n
            (loop[start + i] * g * g).roundToInt().toShort()
        }
    }

    /**
     * The end of a timed session: [frames] samples of [loop] from its start, with the volume
     * ramped to silence over the last [fadeFrames]. The ramp is squared because the live engine
     * applied its fade to the master, and the master goes through a square law.
     */
    fun tail(
        loop: ShortArray,
        frames: Int,
        fadeFrames: Int = FADE_SECONDS * SAMPLE_RATE,
    ): ShortArray {
        val fadeStart = frames - fadeFrames.coerceIn(0, frames)
        val fadeLen = (frames - fadeStart).coerceAtLeast(1)
        return ShortArray(frames) { i ->
            val s = loop[i % loop.size]
            if (i < fadeStart) {
                s
            } else {
                val g = 1f - (i - fadeStart + 1).toFloat() / fadeLen
                (s * g * g).roundToInt().toShort()
            }
        }
    }

    private fun toShort(x: Float): Short =
        (x.coerceIn(-1f, 1f) * FULL_SCALE).roundToInt().toShort()
}

/**
 * The playback queue for one session: a fade-in head, then [loops] copies of the loop, then,
 * when a sleep timer is set, a tail of [tailFrames] that fades to silence and ends.
 */
data class QueuePlan(
    val headFrames: Int,
    val loops: Int,
    val tailFrames: Int,
) {
    val hasTail: Boolean get() = tailFrames > 0

    val totalFrames: Long
        get() = headFrames.toLong() + loops.toLong() * Loop.LOOP_FRAMES + tailFrames

    /** Queue length in items. */
    val items: Int get() = 1 + loops + if (hasTail) 1 else 0

    companion object {
        /**
         * With no timer the queue runs this many loops, a little over twelve hours. The SDK
         * player has no repeat mode, so the queue has to end somewhere.
         */
        const val UNTIMED_LOOPS = 240

        /**
         * Plans a session that ends [timerSeconds] after it starts, or an untimed one for 0.
         * The tail is never shorter than the fade, so the fade always gets its full length.
         */
        fun forTimer(
            timerSeconds: Int,
            loopFrames: Int = Loop.LOOP_FRAMES,
            headFrames: Int = Loop.HEAD_SECONDS * SAMPLE_RATE,
            fadeFrames: Int = Loop.FADE_SECONDS * SAMPLE_RATE,
        ): QueuePlan {
            if (timerSeconds <= 0) return QueuePlan(headFrames, UNTIMED_LOOPS, 0)
            val body = (timerSeconds.toLong() * SAMPLE_RATE - headFrames)
                .coerceAtLeast(fadeFrames.toLong())
            var loops = (body / loopFrames).toInt()
            var tail = (body - loops.toLong() * loopFrames).toInt()
            if (tail < fadeFrames && loops > 0) {
                loops -= 1
                tail += loopFrames
            }
            return QueuePlan(headFrames, loops, tail.coerceAtLeast(fadeFrames))
        }
    }
}
