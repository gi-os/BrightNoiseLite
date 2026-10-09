package com.gios.brightnoiselite.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueTest {

    private val lengths = mapOf(Part.Head to 100, Part.Loop to 1000, Part.Tail to 500)
    private fun len(p: Part) = lengths.getValue(p)

    private val queue = LiveQueue.fresh(QueuePlan(headFrames = 100, loops = 3, tailFrames = 500))

    @Test
    fun freshQueueIsHeadLoopsTail() {
        assertEquals(
            listOf(Part.Head, Part.Loop, Part.Loop, Part.Loop, Part.Tail),
            queue.segments.map { it.part },
        )
        assertEquals(500, queue.tailFrames)
    }

    @Test
    fun resumeCutsTheCurrentItemAndKeepsTheRest() {
        val next = queue.resumeAt(2, 400, ::len)!!
        assertEquals(
            listOf(Segment(Part.Loop, 400), Segment(Part.Loop), Segment(Part.Tail)),
            next.segments,
        )
    }

    @Test
    fun resumeTwiceAddsTheOffsets() {
        val once = queue.resumeAt(1, 300, ::len)!!
        val twice = once.resumeAt(0, 200, ::len)!!
        assertEquals(Segment(Part.Loop, 500), twice.segments.first())
        assertEquals(once.segments.size, twice.segments.size)
    }

    @Test
    fun resumeNearTheEndOfAnItemSkipsToTheNext() {
        val q = LiveQueue.fresh(QueuePlan(100, 1, SAMPLE_RATE))
        val lens = mapOf(Part.Head to 100, Part.Loop to SAMPLE_RATE, Part.Tail to SAMPLE_RATE)
        val next = q.resumeAt(1, SAMPLE_RATE - 10) { lens.getValue(it) }!!
        assertEquals(listOf(Segment(Part.Tail)), next.segments)
    }

    @Test
    fun resumePastTheEndIsNothing() {
        assertNull(queue.resumeAt(9, 0, ::len))
        val q = LiveQueue(listOf(Segment(Part.Tail)), SAMPLE_RATE)
        assertNull(q.resumeAt(0, SAMPLE_RATE) { SAMPLE_RATE })
    }
}
