package dev.ftycam.transport.pppp

import dev.ftycam.transport.Codec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAssemblerTest {

    private val startCode = byteArrayOf(0, 0, 0, 1)

    private fun nal(type: Int, vararg body: Byte): ByteArray =
        startCode + byteArrayOf(type.toByte()) + body

    @Test
    fun `no frame is emitted until a second start code proves the first is complete`() {
        val assembler = FrameAssembler(Codec.H264)

        assertNull(assembler.feed(nal(0x65, 1, 2, 3)))
    }

    @Test
    fun `a frame is emitted once the next one begins`() {
        val assembler = FrameAssembler(Codec.H264)
        val first = nal(0x65, 1, 2, 3)

        assembler.feed(first)
        val emitted = assembler.feed(nal(0x41, 4, 5))

        assertNotNull(emitted)
        assertArrayEquals(first, emitted!!.data)
    }

    /**
     * The whole reason for buffering: a frame arrives across several datagrams and
     * must be handed to the decoder as one unit.
     */
    @Test
    fun `fragments spanning several datagrams are joined`() {
        val assembler = FrameAssembler(Codec.H264)

        assembler.feed(startCode + byteArrayOf(0x65))
        assembler.feed(byteArrayOf(0x0A, 0x0B))
        assembler.feed(byteArrayOf(0x0C))
        val emitted = assembler.feed(startCode + byteArrayOf(0x41))

        assertNotNull(emitted)
        assertArrayEquals(
            startCode + byteArrayOf(0x65, 0x0A, 0x0B, 0x0C),
            emitted!!.data,
        )
    }

    @Test
    fun `three byte start codes are recognised`() {
        val assembler = FrameAssembler(Codec.H264)
        val shortStart = byteArrayOf(0, 0, 1)

        assembler.feed(shortStart + byteArrayOf(0x65, 0x11))
        val emitted = assembler.feed(shortStart + byteArrayOf(0x41))

        assertNotNull(emitted)
        assertArrayEquals(shortStart + byteArrayOf(0x65, 0x11), emitted!!.data)
    }

    @Test
    fun `idr slices are flagged as key frames`() {
        val assembler = FrameAssembler(Codec.H264)

        assembler.feed(nal(0x65)) // NAL type 5, IDR
        val emitted = assembler.feed(nal(0x41))

        assertTrue(emitted!!.isKeyFrame)
    }

    @Test
    fun `sps is treated as a key frame because it precedes an idr`() {
        val assembler = FrameAssembler(Codec.H264)

        assembler.feed(nal(0x67)) // NAL type 7, SPS
        val emitted = assembler.feed(nal(0x41))

        assertTrue(emitted!!.isKeyFrame)
    }

    @Test
    fun `non idr slices are not key frames`() {
        val assembler = FrameAssembler(Codec.H264)

        assembler.feed(nal(0x41)) // NAL type 1
        val emitted = assembler.feed(nal(0x41))

        assertFalse(emitted!!.isKeyFrame)
    }

    @Test
    fun `timestamps advance at the nominal frame rate`() {
        val assembler = FrameAssembler(Codec.H264)

        assembler.feed(nal(0x65))
        val first = assembler.feed(nal(0x41))!!
        val second = assembler.feed(nal(0x41))!!

        assertEquals(0L, first.presentationTimeUs)
        assertEquals(66_666L, second.presentationTimeUs)
    }

    /**
     * G.711 and ADPCM packets are self-contained, so buffering them would only add
     * latency to audio that is already meant to be live.
     */
    @Test
    fun `audio fragments are emitted immediately`() {
        val assembler = FrameAssembler(Codec.G711_ULAW)

        val emitted = assembler.feed(byteArrayOf(1, 2, 3, 4))

        assertNotNull(emitted)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), emitted!!.data)
        assertEquals(Codec.G711_ULAW, emitted.codec)
    }

    @Test
    fun `empty fragments are ignored`() {
        assertNull(FrameAssembler(Codec.H264).feed(ByteArray(0)))
    }

    @Test
    fun `reset drops buffered data`() {
        val assembler = FrameAssembler(Codec.H264)

        assembler.feed(nal(0x65, 1, 2, 3))
        assembler.reset()
        val emitted = assembler.feed(nal(0x41))

        // Nothing buffered, so the new fragment is itself incomplete.
        assertNull(emitted)
    }

    /**
     * A stream that never produces a second start code — because the camera sent
     * garbage, or because the framing guess is wrong — must not grow the buffer
     * without bound.
     */
    @Test
    fun `a stream with no frame boundaries does not exhaust memory`() {
        val assembler = FrameAssembler(Codec.H264)
        val junk = ByteArray(64 * 1024) { 0x42 }

        repeat(200) { assembler.feed(junk) } // 12.8 MB of input

        // Reaching here without an OutOfMemoryError is the assertion.
        assertNull(assembler.feed(junk))
    }
}
