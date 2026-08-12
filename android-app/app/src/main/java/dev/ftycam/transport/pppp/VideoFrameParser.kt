package dev.ftycam.transport.pppp

import dev.ftycam.transport.Codec
import dev.ftycam.transport.MediaChunk

/**
 * Reassembles video frames from channel 1.
 *
 * Replaces the earlier start-code-scanning heuristic with the camera's real frame
 * header, decoded from a capture of the vendor app (finding 05):
 *
 * ```
 * 55 AA 15 A8 03 01 48 F9 1D E5 7C 6A | 43 1A 00 00 | EC 24 00 00 | E8 FF 93 00
 * ^^^^^ magic                            ^ counter    ^ payload len  ^ constant
 *                                        u16le @12     u32le @16
 * ```
 *
 * Using the length field rather than scanning for the next start code means a
 * frame is emitted as soon as it is complete, instead of being held until the
 * following frame arrives — one frame less latency, and it no longer depends on
 * start codes being visible in the transport layer at all.
 *
 * The counter at offset 12 gives real, monotonic frame numbering, which replaces
 * the synthesised timestamps that were blocking correct MP4 muxing.
 */
class VideoFrameParser(private val fps: Int = DEFAULT_FPS) {

    private val buffer = GrowableBuffer()
    private var firstCounter: Int? = null

    /** Frames dropped because the buffer resynchronised. Surfaced in diagnostics. */
    var resyncCount: Int = 0
        private set

    /**
     * Add a fragment from channel 1. Returns every complete frame it produced —
     * a single datagram can complete one frame and begin another.
     */
    fun feed(fragment: ByteArray): List<MediaChunk> {
        if (fragment.isEmpty()) return emptyList()
        buffer.append(fragment)

        val out = mutableListOf<MediaChunk>()
        while (true) {
            val start = buffer.indexOf(MAGIC, 0)
            if (start < 0) {
                // No header anywhere: keep only a magic-length tail in case the
                // header straddles this datagram and the next.
                buffer.trimToTail(MAGIC.size - 1)
                break
            }
            if (start > 0) {
                // Junk before the header — resynchronise.
                buffer.dropFirst(start)
                resyncCount++
                continue
            }
            if (buffer.size < HEADER_SIZE) break

            val payloadLength = buffer.readU32Le(OFFSET_LENGTH)
            if (payloadLength <= 0 || payloadLength > MAX_FRAME_BYTES) {
                // Implausible length: drop this header and look for the next.
                buffer.dropFirst(MAGIC.size)
                resyncCount++
                continue
            }
            val total = HEADER_SIZE + payloadLength
            if (buffer.size < total) break // frame not fully arrived yet

            val counter = buffer.readU16Le(OFFSET_COUNTER)
            val payload = buffer.slice(HEADER_SIZE, total)
            buffer.dropFirst(total)

            if (firstCounter == null) firstCounter = counter
            val index = counter - (firstCounter ?: counter)

            out += MediaChunk(
                data = PpppCommands.deobfuscate(payload),
                presentationTimeUs = index.toLong() * 1_000_000L / fps,
                isKeyFrame = isKeyFrame(payload),
                codec = Codec.H264,
            )
        }
        return out
    }

    fun reset() {
        buffer.clear()
        firstCounter = null
        resyncCount = 0
    }

    /**
     * Whether the frame opens with an IDR, SPS or PPS NAL.
     *
     * Operates on the deobfuscated bytes. SPS/PPS were not located in the capture
     * that produced this parser, so a stream may begin without them — if the
     * decoder refuses to start, that absence is the first thing to check.
     */
    private fun isKeyFrame(obfuscated: ByteArray): Boolean {
        val frame = PpppCommands.deobfuscate(obfuscated.copyOfRange(0, minOf(8, obfuscated.size)))
        val offset = when {
            frame.size > 4 && frame[0] == 0.toByte() && frame[1] == 0.toByte() &&
                frame[2] == 0.toByte() && frame[3] == 1.toByte() -> 4
            frame.size > 3 && frame[0] == 0.toByte() && frame[1] == 0.toByte() &&
                frame[2] == 1.toByte() -> 3
            else -> return false
        }
        if (offset >= frame.size) return false
        val nalType = frame[offset].toInt() and 0x1F
        return nalType == 5 || nalType == 7 || nalType == 8
    }

    companion object {
        val MAGIC = byteArrayOf(0x55, 0xAA.toByte())
        const val HEADER_SIZE = 24
        const val OFFSET_COUNTER = 12
        const val OFFSET_LENGTH = 16

        /** Observed frames are ~9.4 KB; this is a sanity ceiling, not a limit. */
        const val MAX_FRAME_BYTES = 4 * 1024 * 1024
        const val DEFAULT_FPS = 6
    }
}

/** Minimal growable byte buffer with the primitives the parser needs. */
private class GrowableBuffer {
    private var array = ByteArray(128 * 1024)
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
        outer@ for (i in from..size - pattern.size) {
            for (j in pattern.indices) if (array[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    fun readU16Le(offset: Int): Int =
        (array[offset].toInt() and 0xFF) or ((array[offset + 1].toInt() and 0xFF) shl 8)

    fun readU32Le(offset: Int): Int =
        (array[offset].toInt() and 0xFF) or
            ((array[offset + 1].toInt() and 0xFF) shl 8) or
            ((array[offset + 2].toInt() and 0xFF) shl 16) or
            ((array[offset + 3].toInt() and 0xFF) shl 24)

    private fun ensure(required: Int) {
        if (required <= array.size) return
        var capacity = array.size
        while (capacity < required) capacity *= 2
        array = array.copyOf(capacity)
    }
}
