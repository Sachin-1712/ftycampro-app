# Finding 05: The PPPP session handshake, command layer and video framing, decoded

**Date:** 2026-08-12
**Status:** confirmed (except where marked)
**Closes:** finding 02 (the session gap)
**Source:** `research/captures/d3-softap-live-20260812.pcap` — PCAPdroid capture of the
vendor app streaming over the camera's SoftAP. 9,985 packets, 112s, 100% UDP.

## Headline

The handshake gap is closed. **This firmware uses different message-type numbers
than the documented CS2/PPPP table**, which is the whole reason our client's
`P2P_REQ` was ignored.

| Role | Documented PPPP | **This firmware** |
|---|---|---|
| Session open | `0x20` P2P_REQ | **`0x42` PUNCH_READY + 20-byte DID** |
| Keepalive ping | `0xF0` ALIVE | **`0xE0`** |
| Keepalive pong | `0xF1` ALIVE_ACK | **`0xE1`** |
| Data | `0x70` DRW | **`0xD0`** |
| Data ack | `0x71` DRW_ACK | **`0xD1`** |

Outer framing is unchanged: `F1 <type> <len:u16be> <payload>`.

## The handshake

```
[00] 0.000s APP->CAM  F1 42 0014  <20-byte DID>     PUNCH_READY — session open
[01] 0.006s CAM->APP  F1 E0 0000                    camera pings
[02] 0.007s CAM->APP  F1 42 0014  <20-byte DID>     PUNCH_READY echoed = accepted
[03] 0.007s APP->CAM  F1 E1 0000                    pong
[04] 0.007s APP->CAM  F1 E0 0000                    app pings
[05] 0.219s APP->CAM  F1 D0 00B0  <login>           first command
[06] 0.352s CAM->APP  F1 D1 0006                    ack
```

The DID is `46 54 59 41 00 00 00 00 | 00 0B 67 59 | 53 5A 4E 54 4C 00 00 00` —
`FTYA` + serial `747353` big-endian + `SZNTL`. **Byte-identical to what
`PpppProtocol.encodeUid` already produces.** Our encoder was always correct; only
the message type was wrong.

Keepalive is E0/E1 roughly every 300ms, bidirectional.

## Data channel (`0xD0` / `0xD1`)

Sub-header, 4 bytes, ahead of every `D0` body:

```
D1 <channel:u8> <seq:u16be>
   channel 0x00 = command
   channel 0x01 = video
```

Acks are `D2 00 <count:u16be> <seq:u16be> ...` — a count followed by that many
sequence numbers, so one ack can cover several packets (observed acking seq 1 and 2
together).

## Command format (channel 0)

```
11 0A <cmd:u16be> <len:u16le> <payload...>
```

Requests use an even low nibble, responses the next odd value: `2010` → `2011`,
`0810` → `0811`, `1830` → `1831`. Commands observed: `2010` (login), `0810`,
`1830`, `1030`, `1930`, `0530`, `3210`, `FF50`.

### Obfuscation: XOR 0x01 over the whole command payload

Confirmed by decoding:

```
60 65 6C 68 6F  XOR 0x01  ->  "admin"
51 38 30 44     XOR 0x01  ->  "P91E"
```

Padding appears as `01` bytes (i.e. plaintext `00`). This is obfuscation, not
encryption — there is no key, no negotiation.

### Authentication

1. App sends `2010` login containing the username **`admin`** (XOR-0x01).
2. Camera replies `2011` containing a 4-byte session token, cleartext **`P91E`**.
3. Every later command carries that token XOR-0x01, i.e. `51 38 30 44` (`Q80D`).

The token is per-session; do not hard-code it.

## Video framing (channel 1)

Video packets are `F1 D0 0404` (1028-byte payload) with sub-header
`D1 01 <seq:u16be>`. Frames begin with a **24-byte header**:

```
55 AA 15 A8 03 01 48 F9 1D E5 7C 6A | 43 1A 00 00 | EC 24 00 00 | E8 FF 93 00
^^^^^ magic                            ^ counter    ^ frame size   ^ constant
       (offset 12, u16le, +1 per frame)  (offset 16, u32le)
```

675 frames recovered over 112s ≈ **6 fps**, ~9.4 KB per frame, 506 kbps.

### Video payload also appears to be XOR-0x01 — *unverified*

**Confidence: medium-high, NOT confirmed.** After stripping the 24-byte headers and
XOR-ing the payload with 0x01, 1,352 Annex-B start codes appear with **NAL type
`0x01`** (coded slice, non-IDR) — the most common NAL type in a real stream.
Without the XOR, the same positions yield NAL type `0x02` (slice data partition A),
which is essentially never used in practice.

That asymmetry is the evidence. It is *not* proof: no decoder has accepted the
output yet, because ffmpeg is not installed on this machine.

**To confirm:** install ffmpeg and run

```bash
ffplay research/captures/frame-test.h264
```

If it renders, the video path is fully understood. If not, revisit the header
length and the XOR assumption before writing any decoder code.

Also still to find: SPS (NAL 7) / PPS (NAL 8), which a decoder needs for
initialisation and which were not located in this capture — they may only be sent
on the first keyframe or in a separate command response.

## Cloud contact

Three small flows to `170.106.50.82:32100` and `146.56.226.66:32100` (~30 packets
total, 0.9 KiB) also carry the `0xF1` magic — PPPP supernodes. They are negligible
next to the 6.9 MB local video flow, and video streamed fine on an isolated AP with
no internet, so **they are not required for local streaming**.

## What this unblocks

`PpppTransport` can now be completed:

1. Open with `PUNCH_READY` (`0x42`) + DID instead of `P2P_REQ`.
2. Use `0xE0`/`0xE1` keepalives and `0xD0`/`0xD1` data.
3. Parse the `D1 <channel> <seq>` sub-header; route channel 1 to video.
4. Send the `2010` login (XOR-0x01, user `admin`), keep the returned token, attach
   it XOR-0x01 to later commands.
5. Replace `FrameAssembler`'s start-code scanning with the real `55 AA` header,
   using the frame size at offset 16 — and take real timestamps from it, which also
   fixes the synthesised-timestamp problem blocking proper MP4 muxing.
