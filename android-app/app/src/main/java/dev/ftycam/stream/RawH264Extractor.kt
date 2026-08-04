package dev.ftycam.stream

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.ts.H264Reader
import androidx.media3.extractor.ts.SeiReader
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * A Media3 [Extractor] for a raw H.264 Annex-B elementary stream with no container.
 *
 * Media3 ships readers for every elementary stream (`H264Reader`, `AdtsReader`, …)
 * but only exposes standalone *extractors* for the ones that turn up bare in the
 * wild — ADTS AAC, AC-3, raw MP3. Bare H.264 doesn't normally arrive without a
 * container, so there is no `H264Extractor`; this is the ~20 lines that wrap the
 * reader into one, following the same shape as `AdtsExtractor`.
 *
 * The camera transport hands us access units that already have Annex-B start codes
 * (see `FrameAssembler`), which is exactly what `H264Reader.consume` expects, so no
 * reframing is needed here.
 *
 * Not yet exercised against real device frames — the stream doesn't flow until the
 * DRW command layer is implemented (`PpppTransport.startStream`). Treat the
 * access-unit detection flags below as the first thing to revisit once a capture
 * shows the true frame cadence.
 */
@OptIn(UnstableApi::class)
class RawH264Extractor : Extractor {

    private val reader = H264Reader(
        SeiReader(emptyList()),
        /* allowNonIdrKeyframes = */ false,
        /* detectAccessUnits = */ true,
    )
    private val sampleData = ParsableByteArray(READ_BUFFER_SIZE)
    private var startedPacket = false

    // We only ever attach this extractor to our own stream, so sniffing always
    // succeeds — there is no other format it could be confused with.
    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        reader.createTracks(output, TsPayloadReader.TrackIdGenerator(0, 1))
        output.endTracks()
        // Live stream: no seeking, unknown duration.
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val bytesRead = input.read(sampleData.data, 0, READ_BUFFER_SIZE)
        if (bytesRead == C.RESULT_END_OF_INPUT) return Extractor.RESULT_END_OF_INPUT

        if (!startedPacket) {
            reader.packetStarted(0L, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR)
            startedPacket = true
        }

        sampleData.setPosition(0)
        sampleData.setLimit(bytesRead)
        reader.consume(sampleData)
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        startedPacket = false
        reader.seek()
    }

    override fun release() {
        // Nothing owned that needs explicit release.
    }

    private companion object {
        const val READ_BUFFER_SIZE = 64 * 1024
    }
}
