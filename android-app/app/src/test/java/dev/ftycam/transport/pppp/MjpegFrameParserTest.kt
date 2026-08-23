package dev.ftycam.transport.pppp

import dev.ftycam.transport.Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MJPEG reassembly tests.
 *
 * The camera sends complete JPEGs wrapped in a `55 AA` header whose size field
 * does not land on the EOI marker (finding 06), so the parser scans for JPEG
 * delimiters. These tests exercise that against realistic wrapping.
 */
class MjpegFrameParserTest {

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun jpeg(payloadSize: Int = 16): ByteArray =
        soi + ByteArray(payloadSize) { 0x42 } + eoi

    /** The 24-byte frame header + 8-byte sub-header the camera puts before each JPEG. */
    private fun wrapped(jpeg: ByteArray): ByteArray {
        val header = ByteArray(32)
        header[0] = 0x55
        header[1] = 0xAA.toByte()
        return header + jpeg
    }

    @Test
    fun `a complete jpeg is emitted`() {
        val frames = MjpegFrameParser().feed(wrapped(jpeg()))

        assertEquals(1, frames.size)
        assertEquals(Codec.MJPEG, frames[0].codec)
    }

    @Test
    fun `emitted data starts with SOI and ends with EOI`() {
        val frame = MjpegFrameParser().feed(wrapped(jpeg())).single().data

        assertEquals(0xFF.toByte(), frame[0])
        assertEquals(0xD8.toByte(), frame[1])
        assertEquals(0xFF.toByte(), frame[frame.size - 2])
        assertEquals(0xD9.toByte(), frame[frame.size - 1])
    }

    /** The wrapper bytes must not end up inside the image handed to the decoder. */
    @Test
    fun `the 55AA wrapper is stripped`() {
        val image = jpeg(32)

        val frame = MjpegFrameParser().feed(wrapped(image)).single().data

        assertEquals(image.size, frame.size)
    }

    @Test
    fun `a frame split across datagrams is reassembled`() {
        val parser = MjpegFrameParser()
        val whole = wrapped(jpeg(200))

        assertEquals(0, parser.feed(whole.copyOfRange(0, 40)).size)
        assertEquals(0, parser.feed(whole.copyOfRange(40, 150)).size)
        val done = parser.feed(whole.copyOfRange(150, whole.size))

        assertEquals(1, done.size)
    }

    @Test
    fun `two frames arriving in one datagram both come out`() {
        val frames = MjpegFrameParser().feed(wrapped(jpeg(8)) + wrapped(jpeg(24)))

        assertEquals(2, frames.size)
    }

    /** Every MJPEG frame is independently decodable, so all are key frames. */
    @Test
    fun `every frame is a key frame`() {
        assertTrue(MjpegFrameParser().feed(wrapped(jpeg())).single().isKeyFrame)
    }

    @Test
    fun `timestamps advance at the nominal frame rate`() {
        val parser = MjpegFrameParser(fps = 6)

        val first = parser.feed(wrapped(jpeg())).single()
        val second = parser.feed(wrapped(jpeg())).single()

        assertEquals(0L, first.presentationTimeUs)
        assertEquals(166_666L, second.presentationTimeUs)
    }

    /**
     * A datagram lost mid-frame must cost one frame, not desynchronise the stream —
     * the next SOI has to be found and decoded normally.
     */
    @Test
    fun `a truncated frame does not break the following one`() {
        val parser = MjpegFrameParser()
        val truncated = wrapped(jpeg(64)).let { it.copyOfRange(0, it.size - 10) }

        parser.feed(truncated)
        val recovered = parser.feed(wrapped(jpeg(16)))

        assertEquals(1, recovered.size)
    }

    @Test
    fun `junk before the first marker is discarded`() {
        val frames = MjpegFrameParser().feed(byteArrayOf(1, 2, 3, 4, 5, 6) + wrapped(jpeg()))

        assertEquals(1, frames.size)
    }

    @Test
    fun `empty input is ignored`() {
        assertEquals(0, MjpegFrameParser().feed(ByteArray(0)).size)
    }

    @Test
    fun `reset clears partial state`() {
        val parser = MjpegFrameParser()
        val whole = wrapped(jpeg(200))

        parser.feed(whole.copyOfRange(0, 60))
        parser.reset()

        assertEquals(0, parser.feed(whole.copyOfRange(60, whole.size)).size)
    }

    /** A stream of garbage must not grow the buffer without bound. */
    @Test
    fun `an unterminated frame eventually resynchronises`() {
        val parser = MjpegFrameParser()
        val junk = ByteArray(64 * 1024) { 0x11 }

        parser.feed(soi)
        repeat(40) { parser.feed(junk) } // 2.6 MB with no EOI

        assertTrue(parser.droppedFrames > 0)
        assertEquals(1, parser.feed(wrapped(jpeg())).size)
    }
}
