# Finding 06: The video is MJPEG, not H.264 — and login replies are cleartext

**Date:** 2026-08-23
**Status:** confirmed
**Corrects:** finding 05 (video framing and obfuscation scope)

## Claim 1 — the video is MJPEG at 640×480

Every frame on channel 1 is a **complete, standalone JPEG**. Not H.264, not an
elementary stream, no NAL units, no SPS/PPS, no inter-frame prediction.

Scanning the reassembled channel-1 stream for `FF D8 … FF D9` recovers **675
complete JPEGs**, every one starting with SOI and ending with EOI. Resolution
640×480, 6.6–27 KB per frame (avg 10 KB), ~6 fps.

`research/captures/frame0.jpg` is one of them and renders correctly — it matches
the scene shown in the vendor app.

### Why the earlier H.264 reading was wrong

Finding 05 reported Annex-B start codes with NAL type `0x01` after XOR-0x01. That
was a false positive:

- The `00 00 00 01` hits were **exactly 2 per frame** (1352 over 676 frames) — far
  too regular for real H.264, which has a variable number of NALs per frame.
- Only ever *one* NAL type appeared (`0x01` XOR'd, `0x02` raw). A real stream must
  contain SPS (7), PPS (8) and IDR (5) as well.
- Both readings produced identical counts, which should have been the tell that the
  pattern was structural noise rather than a codec boundary.

The decisive test was not statistical at all — it was **looking at the first bytes
of a frame payload**, which read `FF D8 FF E0 … 4A 46 49 46` ("JFIF"). Byte
frequencies suggested a story; the magic number settled it.

### Frame layout on channel 1

```
55 AA <10 bytes fixed> <counter:u16le @12> <size:u32le @16> <4 bytes> | <8 bytes> | FFD8 … FFD9
|------------------ 24-byte frame header --------------------------|  sub-hdr  |  JPEG
```

The 8 bytes between the header and the JPEG look like two little-endian words in
the `0x0090xxxx` range — plausibly leaked firmware pointers. They are not needed.

**The `size` field at offset 16 does not land exactly on the EOI marker**, so the
robust reassembly is to scan for `FF D8 FF` … `FF D9` rather than trust it. That is
what the implementation does.

### The video payload is NOT obfuscated

`FF D8` appears literally on the wire. Only app→camera command payloads are
XOR-0x01.

## Claim 2 — camera→app payloads are cleartext; only requests are obfuscated

The `0x2011` login reply, raw off the wire:

```
payload : 00 00 00 00 50 39 31 45 fe 01 01 01
          ^^^^^^^^^^^ result = 0 (success)
                      ^^^^^^^^^^^ token "P91E", ASCII, in clear
```

The implementation was XOR-ing replies before parsing, which turned the success
code `00 00 00 00` into `01 01 01 01` and made every login look rejected. On the
device this surfaced as **"Authentication rejected"** even though the camera had
accepted the login and returned a valid token.

So the rule is asymmetric and must be applied that way:

| Direction | Obfuscation |
|---|---|
| app → camera (command payloads) | **XOR 0x01** |
| camera → app (replies, video) | **cleartext** |

A test asserting the reply path had encoded the same wrong assumption — it built
its fixture by calling `obfuscate()` instead of using the captured bytes verbatim,
so it passed against buggy code. Fixture data must be copied from the wire, not
synthesised by the code under test.

## What this simplifies

MJPEG is dramatically easier than H.264:

- **No decoder setup.** No SPS/PPS, no codec configuration, no
  `TransportDataSource`/`ProgressiveMediaSource`/extractor plumbing. Android
  decodes each frame with `BitmapFactory.decodeByteArray`.
- **No timestamp problem.** Frames are independent; there is nothing to reorder and
  no B-frames. The MP4-muxing obstacle from earlier findings evaporates.
- **Snapshot becomes trivial** — the current frame is already a JPEG file; write the
  bytes straight to disk, no re-encoding.
- **The browser goal gets much closer.** MJPEG over HTTP
  (`multipart/x-mixed-replace`) is a handful of lines and works in every browser
  with a plain `<img>` tag. No Media Source Extensions, no transcoding.

## Consequences for the code

- `VideoFrameParser` → rewritten as an MJPEG reassembler keyed on SOI/EOI.
- `PpppCommands.parse` → must not deobfuscate replies.
- `PlayerController` / `TransportDataSource` / `RawH264Extractor` → **not needed for
  this camera.** Media3 is the wrong tool; a Compose `Image` fed a `Bitmap` is right.
  Keep the RTSP path for any future camera that needs it.
- `Codec.MJPEG` should exist as a codec value.
