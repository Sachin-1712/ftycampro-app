# Finding 04: Correction — the FtyCamPro camera is FTYA-747353-SZNTL, not XMSYINA-772459-VNYUK

**Date:** 2026-08-12
**Status:** confirmed
**Supersedes:** the device identity recorded in finding 01

## Claim

The UID recorded in finding 01, **`XMSYINA-772459-VNYUK`**, is **not** the
FtyCamPro camera. The vendor app identifies this camera as
**`FTYA-747353-SZNTL`** (firmware 2.2.2.45).

`XMSYINA-772459-VNYUK` is a *different* PPPP device that was present on the
`192.168.29.0/24` home network at `192.168.29.24`.

## How the mistake happened

`LAN_SEARCH` is a **broadcast to 255.255.255.255**. It is answered by *every*
PPPP-family device on the segment, not only the one being looked for. When the
probe was first run from the PC, exactly one device replied, and it was assumed to
be the target. It wasn't — it was another PPPP camera on the same LAN.

The prefixes make the distinction obvious in hindsight:

| UID | Prefix | Family |
|---|---|---|
| `FTYA-747353-SZNTL` | `FTYA` | **FtyCam** — the actual target |
| `XMSYINA-772459-VNYUK` | `XMSYINA` | XM / Sofia (Xiongmai) — a different vendor |

## What is still valid from finding 01

The protocol work is unaffected, because both devices speak the same stack:

- PPPP/PPCS over UDP 32108 with the `0xF1` magic — **still confirmed**, now on the
  actual target (finding 03 shows `PUNCH_PKT` from `FTYA-747353-SZNTL`).
- The 20-byte DID packing (prefix[8] + serial[4 BE] + check[8]) — **still
  confirmed**; `FTYA-747353-SZNTL` parses and re-encodes correctly under the same
  scheme.
- Reachability reasoning (ICMP answers, no TCP listeners) — that measurement was
  taken against `.24`, so it describes the *other* device. It should be re-run
  against the FtyCam unit before being relied on.

## What must be corrected

- `research/00-device-facts.md` — UID, and the note that `.24` was a different device.
- The `PpppProtocolTest` fixture uses `XMSYINA-772459-VNYUK` bytes. Those are still
  a valid real-world PPPP `PUNCH_PKT` and remain a useful parser regression test,
  but the file should say which device they came from.
- Any assumption that the camera lives at `192.168.29.24`.

## Scope note

`XMSYINA-772459-VNYUK` is not this project's target and must not be probed further.
It was observed only because it answered a broadcast on the operator's own LAN.
Whatever it is, it is out of scope — see `docs/LEGAL-SCOPE.md`.

## Lesson

Broadcast discovery finds a *population*, not a device. Match on UID prefix or
confirm against the vendor app before treating a single reply as "the camera" —
and prefer identifying a device by something the owner can independently verify.
