package dev.ftycam.transport.pppp

import dev.ftycam.transport.Codec
import dev.ftycam.transport.MediaChunk

/**
 * Reassembles fragmented media out of the DRW channel.
 *
 * A camera frame is larger than a UDP datagram, so the SDK splits it. This class
 * accumulates the pieces and emits one [MediaChunk] per complete access unit.
 *
 * Frame boundaries are detected by scanning for H.264 Annex-B start codes rather
 * than by trusting a length field, which is the more robust of the two options
 * while the per-frame header layout is still unknown: start codes are
 * self-synchronising, so a dropped datagram costs one frame instead of
 * desynchronising the stream permanently. Once a capture reveals the real header,
 * switch to it and keep this as the fallback.
 */
class FrameAssembler(private val codec: Codec) {

    private val buffer = StringBuilderBytes()
    private var frameCount = 0L

    /**
     * Add a fragment. Returns a chunk when a complete unit is available.
     *
     * For non-H.264 codecs — audio, principally — each fragment is treated as one
     * unit, which is correct for G.711 and ADPCM where packets are self-contained.
     */
    fun feed(fragment: ByteArray): MediaChunk? {
        if (fragment.isEmpty()) return null

        if (codec != Codec.H264 && codec != Codec.H265) {
            return MediaChunk(
                data = fragment.copyOf(),
                presentationTimeUs = nextTimestampUs(),
                isKeyFrame = true,
                codec = codec,
            )
        }

        buffer.append(fragment)

        // Locate the first frame's start code, then look for the *next* one. Emit
        // only when the second appears, which proves the first frame is complete —
        // handing the decoder a partial access unit gets it dropped or rendered
        // corrupt.
        //
        // The search for the second code must begin *after* the first start code,
        // not at byte 1: a 4-byte start code `00 00 00 01` contains a valid 3-byte
        // `00 00 01` at its own offset 1, so searching from 1 would "find" a
        // boundary inside the first frame's own prefix and slice it to nothing.
        val firstStart = buffer.indexOfStartCode(fromIndex = 0)
        if (firstStart < 0) return null

        val afterFirst = firstStart + buffer.startCodeLengthAt(firstStart)
        val secondStart = buffer.indexOfStartCode(fromIndex = afterFirst)
        if (secondStart < 0) return null

        val complete = buffer.take(secondStart)
        buffer.dropFirst(secondStart)

        return MediaChunk(
            data = complete,
            presentationTimeUs = nextTimestampUs(),
            isKeyFrame = isKeyFrame(complete),
            codec = codec,
        )
    }

    fun reset() {
        buffer.clear()
        frameCount = 0
    }

    /**
     * Synthesised timestamps at a nominal 15fps.
     *
     * The camera's own timestamps live in the per-frame header, which isn't
     * decoded yet. Synthesised ones are fine for live viewing — the player is
     * rendering as fast as frames arrive — but they will make recorded files
     * play back at the wrong rate, so this needs replacing before recording is
     * trustworthy.
     */
    private fun nextTimestampUs(): Long = frameCount++ * (1_000_000L / NOMINAL_FPS)

    private fun isKeyFrame(frame: ByteArray): Boolean {
        val offset = when {
            frame.size > 4 && frame[0] == 0.toByte() && frame[1] == 0.toByte() &&
                frame[2] == 0.toByte() && frame[3] == 1.toByte() -> 4
            frame.size > 3 && frame[0] == 0.toByte() && frame[1] == 0.toByte() &&
                frame[2] == 1.toByte() -> 3
            else -> return false
        }
        if (offset >= frame.size) return false
        // H.264 NAL type 5 is an IDR slice; 7 and 8 are SPS and PPS, which precede
        // one and are equally good as a decoder start point.
        val nalType = frame[offset].toInt() and 0x1F
        return nalType == 5 || nalType == 7 || nalType == 8
    }

    private companion object {
        const val NOMINAL_FPS = 15
    }
}

/**
 * Growable byte buffer with start-code search.
 *
 * `ByteArrayOutputStream` would need a full copy on every inspection, and this
 * runs per datagram on the receive path.
 */
private class StringBuilderBytes {
    private var array = ByteArray(INITIAL_CAPACITY)
    private var size = 0

    fun append(data: ByteArray) {
        ensureCapacity(size + data.size)
        data.copyInto(array, size)
        size += data.size
    }

    fun take(count: Int): ByteArray = array.copyOfRange(0, count.coerceAtMost(size))

    fun dropFirst(count: Int) {
        val n = count.coerceAtMost(size)
        array.copyInto(array, 0, n, size)
        size -= n
    }

    fun clear() {
        size = 0
    }

    /** Index of the next 4-byte or 3-byte Annex-B start code at or after [fromIndex], or -1. */
    fun indexOfStartCode(fromIndex: Int): Int {
        var i = fromIndex.coerceAtLeast(0)
        while (i + 2 < size) {
            if (array[i] == 0.toByte() && array[i + 1] == 0.toByte()) {
                // Prefer the 4-byte form: if a zero precedes `00 00 01`, the real
                // boundary is one byte earlier. Checking the 4-byte pattern first
                // keeps start-code lengths consistent for startCodeLengthAt.
                if (i + 3 < size && array[i + 2] == 0.toByte() && array[i + 3] == 1.toByte()) return i
                if (array[i + 2] == 1.toByte()) return i
            }
            i++
        }
        return -1
    }

    /** Length (3 or 4) of the Annex-B start code known to begin at [pos]. */
    fun startCodeLengthAt(pos: Int): Int =
        if (pos + 2 < size && array[pos + 2] == 1.toByte()) 3 else 4

    private fun ensureCapacity(required: Int) {
        if (required <= array.size) return
        var capacity = array.size
        while (capacity < required) capacity *= 2
        // A frame that never terminates would otherwise grow without bound; a
        // stuck assembler should lose data rather than the process.
        if (capacity > MAX_CAPACITY) {
            size = 0
            capacity = INITIAL_CAPACITY
        }
        array = array.copyOf(capacity)
    }

    companion object {
        const val INITIAL_CAPACITY = 64 * 1024
        const val MAX_CAPACITY = 8 * 1024 * 1024
    }
}
