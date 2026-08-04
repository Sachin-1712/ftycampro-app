# Project status — ftycam

_Last updated: 2026-08-03. This is the "where things are right now" handoff. Read it
first when picking the project up on another machine._

## One-paragraph summary

An ad-free Android client for a FtyCamPro / NMCamera "Mini DV" camera, plus the
reverse-engineering tooling that identified it. The camera has been confirmed
(against the physical device) to be a **CS2 Network PPPP/PPCS** P2P camera with UID
`XMSYINA-772459-VNYUK`. Discovery works end-to-end from a script; the remaining
blocker for live video is the vendor-specific session/stream handshake, which needs
a packet capture of the vendor app to reproduce. The Android app
(Kotlin/Compose/MVVM) **compiles, packages to a debug APK, and its 41 unit tests
pass** (verified 2026-08-04 with Android Studio's bundled JDK 25 + Gradle 9.3).

## What is CONFIRMED (against the real camera)

- **Protocol:** CS2 Network PPPP/PPCS over UDP. Answers a broadcast `LAN_SEARCH`
  (`F1 30 00 00`) on **UDP 32108** with a `0xF1 0x41` PUNCH_PKT.
- **UID:** `XMSYINA-772459-VNYUK`, transmitted in cleartext. 20-byte DID layout
  (prefix[8] + serial[4 big-endian] + check[8]) is verified against the wire and is
  now a unit-test fixture.
- **Reachability:** the camera answers ICMP; TCP is silent because it has no
  listeners (not client isolation). The original "all ports closed/filtered, ping
  fails" scan was a UDP-only P2P device — a correct reading only once you notice the
  32,249 RSTs and the working ping to a control host.
- **Addressing:** the camera's IP is DHCP-assigned and moves (was `.214`, found at
  `192.168.29.24`). Always locate it by UID via broadcast, never a fixed IP.

Details: [research/findings/01-pppp-confirmed.md](research/findings/01-pppp-confirmed.md).

## What is OPEN (the blocker)

**Local session establishment.** The camera answers broadcast discovery but is
silent to every unicast follow-up (`HELLO`, `QUERY_DID`, `P2P_REQ`, `DEV_LGN`) sent
to the port it replied from. Live video cannot start until this handshake is known.

Three candidate explanations, not yet distinguished (needs a capture to decide):
1. cloud-mediated rendezvous is required even on the LAN;
2. an app-computed init-string / token must precede the session;
3. the app opens with a different first message.

Details: [research/findings/02-local-session-gap.md](research/findings/02-local-session-gap.md).

## Phase status

| # | Phase | State |
|---|---|---|
| 1 | Repo, docs, checklist, tooling | **done** |
| 2 | APK static analysis | tooling ready; needs the vendor APK |
| 3 | Traffic capture + comparison | tooling ready; needs phone + camera + capture host |
| 4 | Identify SDK / local handshake | SDK **identified (PPPP, confirmed)**; handshake open |
| 5 | CLI proof of concept | discovery works vs. real device; session/frame open |
| 6 | Android app | **builds + runs; APK packages**; discovery + DID packing confirmed |
| 7 | Tests, logging, reproducible build | **41 unit tests pass**; real captured bytes are a fixture |

## The single next action

Capture the vendor app doing discovery → live view, then read the first unicast
packets it sends after the PUNCH_PKT and port them into
`PpppTransport.startStream()` / `poc_client.send_command`.

```bash
# 1. confirm the camera is still there and get its current UID
python tools/p2p_probe.py --broadcast 192.168.29.255

# 2. pull the vendor APK from your own phone (for phase 2)
bash tools/adb_capture.sh find
bash tools/adb_capture.sh pull <package.name.from.step.above>
bash tools/apk_analyze.sh research/captures/<package>-base.apk
python tools/apk_signatures.py research/01-apk-analysis

# 3. capture a live-view session on the same Wi-Fi (phase 3), then
python tools/pcap_triage.py research/captures/<file>.pcapng --peer <camera-ip>
```

Full ordered checklist: [docs/INVESTIGATION-CHECKLIST.md](docs/INVESTIGATION-CHECKLIST.md).

## Setting up on another machine

```bash
git clone <your-repo-url> ftycam
cd ftycam/tools
python -m venv .venv && . .venv/Scripts/activate   # or .venv/bin/activate on macOS/Linux
pip install -r requirements.txt
```

The Python tools (except `pcap_triage.py`, which needs scapy) run on the standard
library alone. For the Android app you additionally need JDK 17, Android SDK 35, and
either Android Studio or a local Gradle 8.9 — see
[android-app/README.md](android-app/README.md). The Gradle wrapper JAR is not
committed; generate it once with `gradle wrapper --gradle-version 8.9`.

## What's deliberately NOT in the repo

- `research/captures/**` — pcaps and APKs are git-ignored. They contain the camera
  UID, session tokens, and (in provisioning captures) your Wi-Fi passphrase. Commit
  only redacted excerpts into a finding.
- Decompiled APK output — vendor-copyrighted, regenerable, git-ignored.
- Any signing key — release signing is intentionally unconfigured.

## A privacy note before you push this anywhere

The committed files (`research/00-device-facts.md`, the findings, this file) contain
your **camera UID and home network layout** (subnet, gateway, MAC addresses). None
of it is a credential, but it is information about a device inside your home. **Use a
private repository** unless you have a reason to publish, and scrub network specifics
if you ever make it public.

## Key files to orient by

| File | What it is |
|---|---|
| [README.md](README.md) | Project overview and the "the scan doesn't mean what it looks like" explanation |
| [research/00-device-facts.md](research/00-device-facts.md) | Ground-truth device/network facts |
| [research/03-protocol-hypotheses.md](research/03-protocol-hypotheses.md) | Hypotheses with confidence + disconfirming tests |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Android app design and the CameraTransport seam |
| [tools/poc_client.py](tools/poc_client.py) | PPPP proof-of-concept; where the session work continues |
| [android-app/.../PpppProtocol.kt](android-app/app/src/main/java/dev/ftycam/transport/pppp/PpppProtocol.kt) | Pure, tested protocol framing (Kotlin twin of poc_client) |
