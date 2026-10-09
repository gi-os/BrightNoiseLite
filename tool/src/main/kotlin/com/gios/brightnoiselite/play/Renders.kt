package com.gios.brightnoiselite.play

import com.gios.brightnoiselite.audio.LiveQueue
import com.gios.brightnoiselite.audio.Loop
import com.gios.brightnoiselite.audio.Part
import com.gios.brightnoiselite.audio.SAMPLE_RATE
import com.gios.brightnoiselite.audio.Settings
import com.gios.brightnoiselite.audio.Slot
import com.gios.brightnoiselite.audio.SoundId
import com.gios.brightnoiselite.audio.Wav
import com.gios.brightnoiselite.audio.generatorFor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** One rendered layer. Compared by identity, so a re-render is always a new mix. */
internal class RenderedLayer(val id: SoundId, val pcm: ShortArray)

private data class MixKey(
    val a: RenderedLayer?,
    val b: RenderedLayer?,
    val levelA: Float,
    val levelB: Float,
    val master: Float,
)

/**
 * Rendered audio and the files written from it.
 *
 * Process-wide on purpose. Detached playback outlives the screen and its view model, and when
 * the tool is opened again mid-session a level change should not have to re-synthesise three
 * minutes of rain. Every member is touched only by [NoiseViewModel]'s session lock.
 */
internal object Renders {
    private var layerA: RenderedLayer? = null
    private var layerB: RenderedLayer? = null
    private var mixKey: MixKey? = null
    private var mix: ShortArray? = null

    /** Files written from the current mix, by role ("loop", "head", "tail:<frames>"). */
    private val files = HashMap<String, File>()
    private var seq = 0L

    /** The queue the player was last given, or null when nothing is playing. */
    @Volatile
    var live: LiveQueue? = null

    /** Wall clock ms when a timed session ends, or 0. */
    @Volatile
    var endsAt: Long = 0L

    fun hasLayer(slot: Slot, id: SoundId?): Boolean {
        val cur = if (slot == Slot.A) layerA else layerB
        return id == null || cur?.id == id
    }

    /** Renders whichever layers [s] needs and does not have yet, both at once. */
    suspend fun ensureLayers(s: Settings) = coroutineScope {
        val a = if (hasLayer(Slot.A, s.pickA)) null else s.pickA?.let { id ->
            async(Dispatchers.Default) { RenderedLayer(id, Loop.renderLayer(generatorFor(id))) }
        }
        val b = if (hasLayer(Slot.B, s.pickB)) null else s.pickB?.let { id ->
            async(Dispatchers.Default) { RenderedLayer(id, Loop.renderLayer(generatorFor(id))) }
        }
        a?.await()?.let { layerA = it }
        b?.await()?.let { layerB = it }
    }

    /** The mixed loop for [s], rendering and mixing only what changed. Null when both layers are off. */
    suspend fun loopFor(s: Settings): ShortArray? {
        if (!s.hasSound) return null
        ensureLayers(s)
        val a = if (s.pickA == null) null else layerA
        val b = if (s.pickB == null) null else layerB
        val key = MixKey(a, b, s.levelA, s.levelB, s.master)
        val cached = mix
        if (key == mixKey && cached != null) return cached
        val fresh = Loop.mix(a?.pcm, b?.pcm, s.levelA, s.levelB, s.master)
        mix = fresh
        mixKey = key
        files.clear()
        return fresh
    }

    /** The file for one queue item under the current mix, writing it if needed. */
    fun fileFor(dir: File, loop: ShortArray, part: Part, offsetFrames: Int, tailFrames: Int): File {
        if (offsetFrames > 0) {
            val whole = pcmFor(loop, part, tailFrames)
            val from = offsetFrames.coerceAtMost(whole.size - 1)
            return write(dir, "rest", whole.copyOfRange(from, whole.size))
        }
        val role = when (part) {
            Part.Head -> "head"
            Part.Loop -> "loop"
            Part.Tail -> "tail:$tailFrames"
        }
        files[role]?.takeIf { it.exists() }?.let { return it }
        return write(dir, role.substringBefore(':'), pcmFor(loop, part, tailFrames)).also { files[role] = it }
    }

    /** Length in frames of each part under the current plan. */
    fun partFrames(part: Part, tailFrames: Int): Int = when (part) {
        Part.Head -> Loop.HEAD_SECONDS * SAMPLE_RATE
        Part.Loop -> Loop.LOOP_FRAMES
        Part.Tail -> tailFrames
    }

    /** Deletes every file in [dir] the player is not using. */
    fun sweep(dir: File, keep: Collection<File>) {
        val names = keep.map { it.name }.toSet()
        dir.listFiles()?.forEach { f -> if (f.name !in names) f.delete() }
        files.values.removeAll { it.name !in names }
    }

    private fun pcmFor(loop: ShortArray, part: Part, tailFrames: Int): ShortArray = when (part) {
        Part.Head -> Loop.head(loop)
        Part.Loop -> loop
        Part.Tail -> Loop.tail(loop, tailFrames)
    }

    private fun write(dir: File, prefix: String, pcm: ShortArray): File {
        seq += 1
        val f = File(dir, "$prefix-${System.currentTimeMillis()}-$seq.wav")
        Wav.writeFile(f, pcm)
        return f
    }
}
