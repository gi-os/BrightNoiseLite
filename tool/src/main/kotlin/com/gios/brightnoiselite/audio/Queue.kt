package com.gios.brightnoiselite.audio

/** The three kinds of file a session is built from. */
enum class Part { Head, Loop, Tail }

/** One queue item: a [part], starting [offsetFrames] in (non-zero only right after a remix). */
data class Segment(val part: Part, val offsetFrames: Int = 0)

/**
 * What the player is working through right now, item for item. Kept so a level change can
 * pick up from the same point instead of restarting the session and its timer.
 */
data class LiveQueue(
    val segments: List<Segment>,
    val tailFrames: Int,
) {
    /**
     * The queue that carries on from [offsetFrames] into item [index], for a remix. The item
     * being played becomes a partial item that starts where playback is; everything after it
     * stays as it was, so a sleep timer still ends when it was going to.
     *
     * Returns null when [index] is not in the queue or nothing is left to play.
     */
    fun resumeAt(index: Int, offsetFrames: Int, partFrames: (Part) -> Int): LiveQueue? {
        if (index !in segments.indices) return null
        val seg = segments[index]
        val at = seg.offsetFrames + offsetFrames.coerceAtLeast(0)
        val rest = segments.subList(index + 1, segments.size)
        // A sliver this short is not worth a file; go straight on to the next item.
        val list = if (at >= partFrames(seg.part) - MIN_PARTIAL_FRAMES) {
            rest.toList()
        } else {
            listOf(Segment(seg.part, at)) + rest
        }
        return if (list.isEmpty()) null else copy(segments = list)
    }

    companion object {
        const val MIN_PARTIAL_FRAMES = SAMPLE_RATE / 4

        fun fresh(plan: QueuePlan): LiveQueue {
            val segments = buildList {
                add(Segment(Part.Head))
                repeat(plan.loops) { add(Segment(Part.Loop)) }
                if (plan.hasTail) add(Segment(Part.Tail))
            }
            return LiveQueue(segments, plan.tailFrames)
        }
    }
}
