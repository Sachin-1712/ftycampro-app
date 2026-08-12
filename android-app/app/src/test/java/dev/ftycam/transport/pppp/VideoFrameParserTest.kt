package dev.ftycam.transport.pppp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Video framing tests, built on the real 24-byte header captured from the camera
 * (finding 05): `55 AA` magic, frame counter u16le at offset 12, payload length
 * u32le at offset 16.
 */
class VideoFrameParserTest {

    /** Build a frame the way the camera does. [payload] is given in clear. */
    private fun frame(counter: Int, payload: ByteArray): ByteArray {
        val header = ByteArray(VideoFrameParser.HEADER_SIZE)
        header[0] = 0x55
        header[1] = 0xAA.toByte()
        // Bytes 2..11 are fixed device fields; content is irrelevant to parsing.
        header[12] = (counter and 0xFF).toByte()
        header[13] = ((counter shr 8) and 0xFF).toByte()
        header[16] = (payload.size and 0xFF).toByte()
        header[17] = ((payload.size shr 8) and 0xFF).toByte()
        return header + PpppCommands.obfuscate(payload)
    }

    @Test
    fun `a complete frame is emitted as soon as its length is satisfied`() {
        val parser = VideoFrameParser()
        val body = ByteArray(40) { 0x42 }

        val frames = parser.feed(frame(100, body))

        assertEquals(1, frames.size)
        assertEquals(40, frames[0].data.size)
    }

    /**
     * Using the length field rather than waiting for the next start code is the
     * point of this parser — a frame must not be held back by one.
     */
    @Test
    fun `emitting does not wait for a following frame`() {
        val parser = VideoFrameParser()

        assertEquals(1, parser.feed(frame(1, ByteArray(16))).size)
    }

    @Test
    fun `a frame split across datagrams is reassembled`() {
        val parser = VideoFrameParser()
        val whole = frame(5, ByteArray(100) { it.toByte() })

        assertEquals(0, parser.feed(whole.copyOfRange(0, 30)).size)
        assertEquals(0, parser.feed(whole.copyOfRange(30, 80)).size)
        val done = parser.feed(whole.copyOfRange(80, whole.size))

        assertEquals(1, done.size)
        assertEquals(100, done[0].data.size)
    }

    @Test
    fun `two frames in one datagram both come out`() {
        val parser = VideoFrameParser()

        val frames = parser.feed(frame(1, ByteArray(8)) + frame(2, ByteArray(12)))

        assertEquals(2, frames.size)
        assertEquals(8, frames[0].data.size)
        assertEquals(12, frames[1].data.size)
    }

    /** Timestamps come from the camera's own counter, not a synthesised clock. */
    @Test
    fun `timestamps derive from the frame counter`() {
        val parser = VideoFrameParser(fps = 6)

        val first = parser.feed(frame(1000, ByteArray(4))).single()
        val second = parser.feed(frame(1001, ByteArray(4))).single()
        val fourth = parser.feed(frame(1003, ByteArray(4))).single()

        assertEquals(0L, first.presentationTimeUs)
        assertEquals(166_666L, second.presentationTimeUs)
        // A dropped frame must leave a gap, not silently renumber.
        assertEquals(500_000L, fourth.presentationTimeUs)
    }

    @Test
    fun `payload is deobfuscated`() {
        val parser = VideoFrameParser()
        val clear = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65)

        val out = parser.feed(frame(1, clear)).single()

        assertEquals(0x00.toByte(), out.data[0])
        assertEquals(0x01.toByte(), out.data[3])
        assertEquals(0x65.toByte(), out.data[4])
    }

    @Test
    fun `an IDR nal is flagged as a key frame`() {
        val parser = VideoFrameParser()
        val idr = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x11)

        assertTrue(parser.feed(frame(1, idr)).single().isKeyFrame)
    }

    @Test
    fun `junk before a header is skipped and counted as a resync`() {
        val parser = VideoFrameParser()

        val frames = parser.feed(byteArrayOf(1, 2, 3, 4, 5) + frame(1, ByteArray(8)))

        assertEquals(1, frames.size)
        assertEquals(1, parser.resyncCount)
    }

    /**
     * A corrupt length must not make the parser wait forever for bytes that will
     * never arrive, nor try to allocate them.
     */
    @Test
    fun `an implausible length triggers a resync instead of a stall`() {
        val parser = VideoFrameParser()
        val bad = ByteArray(VideoFrameParser.HEADER_SIZE)
        bad[0] = 0x55; bad[1] = 0xAA.toByte()
        bad[16] = 0xFF.toByte(); bad[17] = 0xFF.toByte()
        bad[18] = 0xFF.toByte(); bad[19] = 0x7F

        parser.feed(bad)
        val recovered = parser.feed(frame(2, ByteArray(8)))

        assertTrue(parser.resyncCount > 0)
        assertEquals(1, recovered.size)
    }

    @Test
    fun `reset clears buffered state`() {
        val parser = VideoFrameParser()
        val whole = frame(1, ByteArray(100))

        parser.feed(whole.copyOfRange(0, 30))
        parser.reset()

        // The remaining bytes alone are not a frame, so nothing should emerge.
        assertEquals(0, parser.feed(whole.copyOfRange(30, whole.size)).size)
    }

    @Test
    fun `empty input is ignored`() {
        assertEquals(0, VideoFrameParser().feed(ByteArray(0)).size)
    }
}
