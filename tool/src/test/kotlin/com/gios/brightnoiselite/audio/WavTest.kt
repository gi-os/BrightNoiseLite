package com.gios.brightnoiselite.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class WavTest {

    @Test
    fun headerDescribesMono16BitPcm() {
        val h = ByteBuffer.wrap(Wav.header(1000, 44100)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(Wav.header(1000).copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(36 + 2000, h.getInt(4))
        assertEquals(1, h.getShort(20).toInt())
        assertEquals(1, h.getShort(22).toInt())
        assertEquals(44100, h.getInt(24))
        assertEquals(88200, h.getInt(28))
        assertEquals(16, h.getShort(34).toInt())
        assertEquals(2000, h.getInt(40))
    }

    @Test
    fun samplesAreWrittenLittleEndian() {
        val pcm = ShortArray(5000) { (it * 7 - 17000).toShort() }
        val bytes = ByteArrayOutputStream().also { Wav.write(it, pcm) }.toByteArray()
        assertEquals(44 + pcm.size * 2, bytes.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in pcm.indices) assertEquals(pcm[i], b.getShort(44 + 2 * i))
    }
}
