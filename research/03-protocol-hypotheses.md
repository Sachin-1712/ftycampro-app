# Protocol hypotheses

**Nothing in this file is a finding.** Each entry states what is being claimed, how
confident that claim is, what would confirm it, and — importantly — what would kill
it. When a hypothesis is confirmed, move it to `research/findings/` with the evidence
attached and mark it resolved here.

Confidence is stated as **high** (would bet on it), **medium** (plausible, worth
testing early), **low** (cheap to test, so test it).

---

## H0 — The camera is reachable, has no TCP listeners at all, and simply ignores ICMP

**RESOLVED 2026-08-03 → finding 01.** Reading B was correct in outcome, wrong in one
detail: the camera does *not* ignore ICMP — it answers echo requests, and it's TCP
that stays silent (no listeners, no RST). Reachability is confirmed; there is no
client isolation; the empty TCP surface is real. Retained below for the reasoning.

**Confidence: high**

Two readings of the original scan compete, and the existing data already mostly
settles which one wins.

**Reading A — AP/client isolation.** ARP resolving while ICMP and all 65,535 unicast
TCP ports fail is a known signature of client isolation: ARP is layer-2 broadcast and
gets flooded to every station regardless of policy, while isolation blocks
station-to-station *unicast* forwarding. If this were the case, the scan characterised
the access point and every conclusion from it is void.

**Reading B — reachable, no listeners.** The scan reported **32,249 ports as
`closed`**, and in `nmap`'s vocabulary `closed` means *a TCP RST came back*. Under
isolation nothing comes back and every port reads `filtered`. Thirty-two thousand
resets is very hard to explain except as the camera's own stack answering. On that
reading the failed ping means only that the firmware disables ICMP echo reply — routine
for these SoCs — and the 33,286 `filtered` ports are an embedded stack shedding load at
`nmap`'s default send rate rather than a firewall.

Reading B is the better-supported one and it is also the more useful one, because it
makes "zero TCP listeners" a genuine finding rather than a measurement artifact. A
device with a live IP stack, no TCP services, a Beken Wi-Fi SoC and a UID-based
companion app is close to a description of a UDP-only P2P camera, which is what H1
predicts.

- **Confirm:** `tools/isolation_check.py` gets `ECONNREFUSED` from a random high port
  on the camera. That is a RST, and it proves the path end to end.
- **Also confirm:** rescan a small range with `--max-rate 50`. Previously-`filtered`
  ports returning as `closed` proves the filtering was rate limiting, which means the
  full-range scan was valid and the TCP space really is empty.
- **Kill:** every TCP probe times out with no RST, *and* a known-good control station
  on the same SSID behaves identically. That would resurrect Reading A, and the fix is
  to disable client isolation or move both devices onto a controlled hotspot.
- **Consequence if true:** stop looking for TCP services, and put the effort into UDP
  magic-packet probing and the SoftAP provisioning surface.

---

## H1 — The camera uses the CS2 Network "PPPP" / PPCS P2P stack

**CONFIRMED 2026-08-03 → finding 01.** The camera replies to a broadcast `LAN_SEARCH`
on UDP 32108 with `0xF1 0x41` (PUNCH_PKT) carrying UID `XMSYINA-772459-VNYUK`. The
20-byte DID packing predicted below matches the wire bytes exactly. What remains open
is session establishment (finding 02, and see H6), not identification. Retained for
the protocol detail.

**Confidence: high**

This is the dominant P2P stack in cheap Chinese IP cameras and the near-default for
the "Mini DV" form factor. It is sold under many names — PPCS, iLnkP2P, CS2 Network
P2P — and appears in apps as `libPPCS_API.so`, `libvdp.so`, `libp2p.so`, or bundled
into a vendor wrapper. The Beken OUI is consistent with this: Beken makes the
BK72xx/BK7231-class Wi-Fi SoCs that these cameras are built on, and the vendor SDKs
for those parts ship with a PPPP client.

Protocol shape:

- All transport is **UDP**. There is no TCP listener, which is exactly why a full TCP
  scan finds nothing even when the path is healthy.
- Every packet begins with the magic byte **`0xF1`**, followed by a one-byte message
  type and a two-byte big-endian payload length.
- LAN discovery is `MSG_LAN_SEARCH` = `F1 30 00 00`, **broadcast to UDP 32108**. A
  device on the segment replies with a packet carrying its UID, typically as
  `MSG_PUNCH_PKT` (`0xF1 0x41`).
- Cloud rendezvous servers are contacted on UDP **32100** (sometimes 32090/32108) to
  register the device and to NAT-punch a session between app and camera.
- Device identity is a **UID string** of the form `PREFIX-NNNNNN-CCCCC` — a vendor
  prefix, a serial, and a check block. The check block is derivable from the serial
  in some versions of the SDK, which is the basis of the published iLnkP2P
  enumeration weaknesses (CVE-2019-11219 / CVE-2019-11220). Note them for
  *defensive* awareness only: enumerating other people's devices is out of scope here
  and illegal in most jurisdictions.
- Application data rides in `MSG_DRW` (`0x70`) packets with their own channel and
  sequence numbering, acknowledged by `MSG_DRW_ACK` (`0x71`). Video, audio and control
  each get a channel.

- **Confirm:** `tools/p2p_probe.py` gets any reply starting `0xF1` on 32108, **or**
  `apk_signatures.py` finds `PPPP_`-prefixed exports in a bundled `.so`.
- **Kill:** no `0xF1` response on any candidate port *after* H0 is resolved, and no
  PPPP symbols anywhere in the APK.
- **Consequence if true:** very good news. The framing is documented, the LAN path
  usually works without any cloud contact, and the remaining work is the
  vendor-specific command layer inside the DRW channel.

---

## H2 — The camera uses TUTK Kalay (IOTC / AVAPI)

**Confidence: medium**

The other major P2P SDK in this market, more common in slightly more expensive
devices. Signatures: `libIOTCAPIs.so`, `libAVAPIs.so`, `libTUTKGlobalAPIs.so`, exported
symbols `IOTC_Initialize2`, `IOTC_Connect_ByUID`, `avClientStart2`, `avSendIOCtrl`.
UIDs are 20 characters, no hyphens, e.g. `ABCD1234EFGH5678IJKL`. Transport is UDP in
the 8000–8900 range with a TCP fallback on 8000.

- **Confirm:** those symbols in the APK's native libraries, or a licence/App-ID string
  of the form `AQAA...` in the Java code (Kalay requires a per-app licence key that is
  usually a long base64 constant).
- **Kill:** no TUTK symbols and no 20-character UID format in the app's stored camera
  list.
- **Consequence if true:** harder than H1. Kalay's newer versions negotiate a session
  key and the AVAPI layer is less publicly documented, though the general architecture
  is well described.

---

## H3 — Local control exists only in SoftAP / provisioning mode

**Confidence: medium**

Common design: in provisioning mode the camera is an access point with a small
HTTP or UDP control server so the app can hand it Wi-Fi credentials and read back the
UID; once it joins the home network it stops listening locally and becomes a pure
cloud client. If true, the camera genuinely has no local surface in station mode and
the original scan's conclusion is right for the wrong reason.

- **Confirm:** factory-reset into pairing mode, join the camera's AP, and repeat the
  port scan and probes against its gateway address. Services appear that were absent
  in station mode.
- **Kill:** the SoftAP shows the same empty surface.
- **Consequence if true:** the provisioning exchange is still extremely valuable — it
  reveals the UID, often the device's credential scheme, and sometimes a debug or
  factory command set that remains reachable after provisioning if you know the port.

---

## H4 — Video is H.264 baseline in a proprietary framing, audio is G.711 or ADPCM

**Confidence: medium-high**

Near-universal for this hardware: the SoC's encoder emits H.264 (occasionally H.265 on
newer parts) as raw Annex-B NAL units, which the SDK chops into fixed-size chunks with
a small per-frame header carrying frame type, timestamp and length, then ships over
the P2P data channel. Audio is almost always 8 kHz mono G.711 µ-law/A-law or IMA
ADPCM, low enough bitrate to ride the same channel.

- **Confirm:** `pcap_triage.py` finds `00 00 00 01` Annex-B start codes at a regular
  offset inside the bulk UDP flow, and `MediaCodec`/`H264` strings in the APK.
- **Kill:** the bulk flow is uniformly high-entropy with no recognisable start codes,
  implying the media itself is encrypted (see H5).
- **Consequence if true:** once the framing is stripped, Media3 plays the elementary
  stream directly. No FFmpeg extension needed for H.264; audio may need a small
  G.711 → PCM decoder, which is trivial to write.

---

## H5 — The payload is obfuscated or encrypted with a key baked into the app

**Confidence: medium**

These SDKs frequently apply a fixed XOR, a fixed AES key, or a trivially derived
per-session key to the data channel. It is rarely real cryptography; the point is to
deter casual inspection.

- **Confirm:** high payload entropy in `pcap_triage.py` combined with `SecretKeySpec`
  / `Cipher.getInstance` in the JADX output taking a constant, or a `.so` exporting
  something like `p2p_encrypt`.
- **Kill:** payload entropy matches what compressed video alone would produce and
  Annex-B start codes are visible in the clear.
- **Consequence if true:** manageable if the key is a constant in the app — that is
  the usual case. Genuinely per-device keys negotiated with the cloud would be a
  serious obstacle, and if that turns out to be the situation it should be recorded
  honestly rather than worked around.

---

## H6 — The device is reachable only while the vendor app holds a session

**Confidence: low-medium**

Battery-conscious firmware sometimes keeps the radio in a deep sleep and only responds
after a cloud-delivered wake. That would explain ARP-yes/ICMP-no without any router
involvement, since the ARP cache entry could be stale from an earlier wake period.

- **Confirm:** the camera answers probes only while the app is streaming, and stops
  within seconds of the app closing.
- **Kill:** identical probe behaviour with the app open and closed.
- **Consequence if true:** a standalone client must be able to trigger the wake, which
  probably means the cloud path — a significant constraint on the "no vendor cloud"
  goal that should be surfaced early.

---

## Ruled out

| Hypothesis | Why |
|---|---|
| ONVIF / RTSP | Ports 554 and 8554 closed and no WS-Discovery response — *pending re-test after H0 is resolved, because the original measurement is suspect* |
| Plain HTTP / CGI camera | Ports 80 and 8080 closed — *same caveat* |

Both are cheap to re-test and should be re-run once the path is validated; a hit on
either would dramatically simplify the project.

---

## Decision tree

```
H0 resolved (path is valid)
├── p2p_probe gets 0xF1 reply ─────────► H1 confirmed. Build a PPPP client. Best case.
├── APK shows TUTK symbols ────────────► H2. Harder, still tractable.
├── Nothing local in station mode
│   └── SoftAP shows services ─────────► H3. Mine the provisioning exchange.
│       └── SoftAP also empty ─────────► Cloud-only. Capture D1 vs D2; if identical,
│                                        the local-reimplementation goal is not
│                                        achievable and the project should be
│                                        re-scoped to an ad-free cloud client or
│                                        replacing the firmware/hardware.
└── ONVIF or HTTP responds ────────────► Ignore all of the above; point Media3 at it.
```
