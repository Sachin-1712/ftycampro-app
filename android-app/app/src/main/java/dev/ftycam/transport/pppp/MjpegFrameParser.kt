package dev.ftycam.transport.pppp

import dev.ftycam.transport.Codec
import dev.ftycam.transport.MediaChunk

/**
 * Reassembles MJPEG frames from channel 1.
 *
 * The camera sends **complete, independent JPEGs** — 640×480, ~10 KB each, ~6 fps
 * (finding 06). Not H.264: there are no NAL units, no SPS/PPS, and no inter-frame
 * dependencies, which makes this far simpler than the codec this project spent a
 * while assuming.
 *
 * Frames arrive wrapped in a 24-byte `55 AA` header plus an 8-byte sub-header, but
 * **the size field in that header does not land exactly on the EOI marker**, so
 * trusting it truncates the image. This scans for the JPEG delimiters instead:
 * `FF D8 FF` … `FF D9`. That is self-synchronising — a lost datagram costs one
 * frame rather than desynchronising the stream — and it makes the wrapper's exact
 * layout irrelevant.
 *
 * Payloads are **not** obfuscated in this direction; `FF D8` appears literally on
 * the wire.
 */
class MjpegFrameParser(private val fps: Int = DEFAULT_FPS) {

    private val buffer = GrowableBuffer()
    private var frameIndex = 0L

    /** Frames discarded because the buffer had to resynchronise. */
    var droppedFrames: Int = 0
        private set

    /**
     * Add a fragment from channel 1 and return every complete JPEG it produced.
     *
     * One datagram can complete a frame and start the next, so this returns a list.
     */
    fun feed(fragment: ByteArray): List<MediaChunk> {
        if (fragment.isEmpty()) return emptyList()
        buffer.append(fragment)

        val frames = mutableListOf<MediaChunk>()
        while (true) {
            val start = buffer.indexOf(SOI, 0)
            if (start < 0) {
                // Nothing usable yet. Keep a short tail so a marker split across
                // two datagrams is still found next time.
                buffer.trimToTail(SOI.size - 1)
                break
            }
            if (start > 0) {
                buffer.dropFirst(start) // discard the wrapper bytes before the image
                continue
            }

            val end = buffer.indexOf(EOI, SOI.size)
            if (end < 0) {
                if (buffer.size > MAX_FRAME_BYTES) {
                    // No end marker within any plausible frame size: the stream is
                    // corrupt. Drop this SOI and hunt for the next one.
                    buffer.dropFirst(SOI.size)
                    droppedFrames++
                    continue
                }
                break // frame still arriving
            }

            val frame = buffer.slice(0, end + EOI.size)
            buffer.dropFirst(end + EOI.size)

            frames += MediaChunk(
                data = frame,
                presentationTimeUs = frameIndex * 1_000_000L / fps,
                isKeyFrame = true, // every MJPEG frame is independently decodable
                codec = Codec.MJPEG,
            )
            frameIndex++
        }
        return frames
    }

    fun reset() {
        buffer.clear()
        frameIndex = 0
        droppedFrames = 0
    }

    companion object {
        /** JPEG start-of-image. The third byte is always a marker, so include it. */
        val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        /** Largest observed frame was 27 KB; this is a generous corruption ceiling. */
        const val MAX_FRAME_BYTES = 2 * 1024 * 1024
        const val DEFAULT_FPS = 6
    }
}

/** Minimal growable byte buffer with the primitives the parser needs. */
private class GrowableBuffer {
    private var array = ByteArray(64 * 1024)
    var size: Int = 0
        private set

    fun append(data: ByteArray) {
        ensure(size + data.size)
        data.copyInto(array, size)
        size += data.size
    }

    fun slice(from: Int, to: Int): ByteArray = array.copyOfRange(from, to.coerceAtMost(size))

    fun dropFirst(count: Int) {
        val n = count.coerceIn(0, size)
        array.copyInto(array, 0, n, size)
        size -= n
    }

    fun trimToTail(keep: Int) {
        if (size > keep) dropFirst(size - keep)
    }

    fun clear() {
        size = 0
    }

    fun indexOf(pattern: ByteArray, from: Int): Int {
        outer@ for (i in from.coerceAtLeast(0)..size - pattern.size) {
            for (j in pattern.indices) if (array[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private fun ensure(required: Int) {
        if (required <= array.size) return
        var capacity = array.size
        while (capacity < required) capacity *= 2
        array = array.copyOf(capacity)
    }
}
