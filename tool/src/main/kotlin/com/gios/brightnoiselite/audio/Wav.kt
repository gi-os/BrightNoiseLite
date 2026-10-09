package com.gios.brightnoiselite.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Mono 16-bit PCM WAV files: the one format the SDK's file player takes without decoding. */
object Wav {

    private const val HEADER_BYTES = 44

    /** The 44-byte RIFF header for [frames] mono 16-bit samples at [sampleRate]. */
    fun header(frames: Int, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val dataBytes = frames * 2
        val b = ByteArray(HEADER_BYTES)
        fun ascii(at: Int, s: String) = s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }
        fun le32(at: Int, v: Int) {
            b[at] = v.toByte()
            b[at + 1] = (v ushr 8).toByte()
            b[at + 2] = (v ushr 16).toByte()
            b[at + 3] = (v ushr 24).toByte()
        }
        fun le16(at: Int, v: Int) {
            b[at] = v.toByte()
            b[at + 1] = (v ushr 8).toByte()
        }
        ascii(0, "RIFF")
        le32(4, 36 + dataBytes)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16) // fmt chunk size
        le16(20, 1) // PCM
        le16(22, 1) // mono
        le32(24, sampleRate)
        le32(28, sampleRate * 2) // byte rate
        le16(32, 2) // block align
        le16(34, 16) // bits per sample
        ascii(36, "data")
        le32(40, dataBytes)
        return b
    }

    /** Writes [pcm] as a complete WAV stream, little-endian. */
    fun write(out: OutputStream, pcm: ShortArray, sampleRate: Int = SAMPLE_RATE) {
        out.write(header(pcm.size, sampleRate))
        val chunk = ByteArray(8192)
        var i = 0
        while (i < pcm.size) {
            val n = minOf(chunk.size / 2, pcm.size - i)
            for (k in 0 until n) {
                val v = pcm[i + k].toInt()
                chunk[2 * k] = v.toByte()
                chunk[2 * k + 1] = (v ushr 8).toByte()
            }
            out.write(chunk, 0, n * 2)
            i += n
        }
    }

    /**
     * Writes [pcm] to [file] through a temporary name, so a half-written file is never what the
     * player opens.
     */
    fun writeFile(file: File, pcm: ShortArray, sampleRate: Int = SAMPLE_RATE) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".part")
        BufferedOutputStream(FileOutputStream(tmp), 1 shl 16).use { write(it, pcm, sampleRate) }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("could not move ${tmp.name} into place")
        }
    }
}
