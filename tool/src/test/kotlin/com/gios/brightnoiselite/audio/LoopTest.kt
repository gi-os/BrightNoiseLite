package com.gios.brightnoiselite.audio

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopTest {

    private val frames = 10 * SAMPLE_RATE
    private val fade = SAMPLE_RATE

    private fun rms(a: ShortArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0
        for (i in from until to) s += a[i].toDouble() * a[i]
        return sqrt(s / (to - from)) / 32767.0
    }

    /** Largest sample-to-sample jump anywhere in [a], counting the wrap from end to start. */
    private fun worstStep(a: ShortArray, from: Int, to: Int): Int {
        var worst = 0
        for (i in from until to) {
            val d = abs(a[(i + 1) % a.size] - a[i % a.size])
            if (d > worst) worst = d
        }
        return worst
    }

    @Test
    fun loopJoinIsNoRougherThanTheMiddle() {
        // Brown noise is smooth, so a seam would stand out as a jump far bigger than its usual step.
        val loop = Loop.renderLayer(generatorFor(SoundId.BROWN, seed = 7L), frames, fade)
        val join = worstStep(loop, loop.size - 50, loop.size + 50 - 1)
        val middle = worstStep(loop, frames / 2 - 5000, frames / 2 + 5000)
        assertTrue(join <= middle * 2, "join step $join vs middle $middle")
    }

    @Test
    fun crossfadeKeepsTheLevelFlat() {
        val loop = Loop.renderLayer(generatorFor(SoundId.WHITE, seed = 3L), frames, fade)
        val joinRms = rms(loop, 0, fade)
        val bodyRms = rms(loop, fade, frames)
        assertTrue(abs(joinRms / bodyRms - 1.0) < 0.1, "join rms $joinRms vs body $bodyRms")
    }

    @Test
    fun everySoundRendersALoopThatIsAudibleAndUnclipped() {
        for (id in SoundId.entries) {
            val loop = Loop.renderLayer(generatorFor(id, seed = 99L), frames, fade)
            val r = rms(loop)
            assertTrue(r > 0.02, "${id.name} too quiet ($r)")
            assertTrue(loop.none { it == Short.MIN_VALUE }, "${id.name} hit negative full scale")
        }
    }

    @Test
    fun mixHoldsTheLimiterCeiling() {
        val a = Loop.renderLayer(generatorFor(SoundId.STORM, seed = 1L), frames, fade)
        val b = Loop.renderLayer(generatorFor(SoundId.CAMPFIRE, seed = 2L), frames, fade)
        val out = Loop.mix(a, b, 1f, 1f, 1f)
        assertEquals(frames, out.size)
        val peak = out.maxOf { abs(it.toInt()) } / 32767.0
        assertTrue(peak <= 1.0, "peak $peak")
        assertTrue(rms(out) > 0.1, "full mix is too quiet (${rms(out)})")
    }

    @Test
    fun levelsFollowTheSquareLaw() {
        val a = Loop.renderLayer(generatorFor(SoundId.PINK, seed = 5L), frames, fade)
        val full = rms(Loop.mix(a, null, 1f, 0f, 0.5f))
        val half = rms(Loop.mix(a, null, 0.5f, 0f, 0.5f))
        assertTrue(abs(half / full - 0.25) < 0.03, "half level gave ${half / full}")
        val silent = Loop.mix(a, null, 0f, 0f, 1f)
        assertTrue(silent.all { it.toInt() == 0 })
    }

    @Test
    fun mixWithNoLayersIsEmpty() {
        assertEquals(0, Loop.mix(null, null, 1f, 1f, 1f).size)
    }

    @Test
    fun headFadesInAndEndsOnTheLoopsLastSample() {
        val loop = ShortArray(1000) { 10000 }
        loop[999] = 12345
        val head = Loop.head(loop, 100)
        assertEquals(100, head.size)
        assertTrue(abs(head[0].toInt()) < 10)
        assertEquals(12345, head[99].toInt())
        for (i in 1 until 99) assertTrue(head[i] >= head[i - 1])
    }

    @Test
    fun tailWrapsTheLoopAndFadesToSilence() {
        val loop = ShortArray(100) { (it * 10).toShort() }
        val tail = Loop.tail(loop, 250, fadeFrames = 50)
        assertEquals(250, tail.size)
        assertEquals(loop[30], tail[130]) // wrapped, before the fade
        assertEquals(0, tail[249].toInt())
        assertTrue(abs(tail[210].toInt()) < abs(loop[10].toInt()))
    }

    @Test
    fun untimedPlanRunsForHours() {
        val plan = QueuePlan.forTimer(0)
        assertEquals(QueuePlan.UNTIMED_LOOPS, plan.loops)
        assertTrue(!plan.hasTail)
        assertTrue(plan.totalFrames / SAMPLE_RATE >= 12 * 3600)
    }

    @Test
    fun timedPlansEndOnTheSecond() {
        for (minutes in Settings.TIMER_PRESETS.filter { it > 0 }) {
            val plan = QueuePlan.forTimer(minutes * 60)
            assertEquals(minutes * 60L * SAMPLE_RATE, plan.totalFrames, "$minutes min")
            assertTrue(plan.tailFrames >= Loop.FADE_SECONDS * SAMPLE_RATE, "$minutes min tail too short")
            assertEquals(plan.loops + 2, plan.items)
        }
    }

    @Test
    fun shortTailBorrowsALoopSoTheFadeFits() {
        // One second is 44 loops of 1000 frames plus 100 left over, too little for a 300-frame fade.
        val p = QueuePlan.forTimer(timerSeconds = 1, loopFrames = 1000, headFrames = 0, fadeFrames = 300)
        assertEquals(43, p.loops)
        assertEquals(1100, p.tailFrames)
    }
}
