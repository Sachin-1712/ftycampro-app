# Project status — ftycam

_Last updated: 2026-08-12. Read this first when picking the project up._

**End goal:** view this camera from an Android phone, and from a browser, with no
ads and no vendor cloud.

---

## One-paragraph summary

The camera is a **CS2 Network PPPP/PPCS** device, UID `FTYA-747353-SZNTL`, firmware
2.2.2.45. Its protocol has been **fully reverse-engineered** from a packet capture
of the vendor app, and that protocol is **implemented in the Android app**, which
builds and passes 68 unit tests anchored on real captured bytes. Crucially, a
**purely local video path exists** — the vendor app streams live video over the
camera's own Wi-Fi hotspot with no internet at all — so the no-cloud goal is
achievable. **The implementation has not yet been run against the camera.** That
single test is the next action, and everything else depends on its outcome.

## The one thing to do next

Install the built APK and open the camera. It takes ten minutes.

```bash
# 1. phone on home Wi-Fi, wireless debugging on; get IP:port from
#    Settings > Developer options > Wireless debugging
adb connect 192.168.29.xxx:PORT
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk

# 2. on the phone: join the camera's "FTYA747353SZNTL" hotspot
#    (hold the camera's reset button 5s if the hotspot isn't broadcasting)
# 3. open ftycam, tap the camera, watch the DIAGNOSTICS panel
```

Three possible outcomes, all informative:

| Outcome | Meaning | Next step |
|---|---|---|
| **Video appears** | Everything works, including the video-XOR guess | Move to the remaining features and the browser client |
| **Handshake OK, no picture** | Session + login correct; decoder problem | Almost certainly missing SPS/PPS — see "Known unknowns" |
| **Handshake fails** | Trace shows which of session/login/stream broke | Fix that one step |

The diagnostics panel on the viewer screen shows Discovery / UID / IP / UDP source
port / handshake state plus a full packet trace, so any failure is legible without
a re-capture.

---

## What is confirmed (against the real device)

| Fact | Evidence |
|---|---|
| CS2 PPPP/PPCS over UDP; `0xF1` magic | findings 01, 03 |
| UID `FTYA-747353-SZNTL`, firmware 2.2.2.45 | vendor app + capture |
| **Local streaming works with no internet** | finding 03 |
| Session opens with `0x42 PUNCH_READY` + 20-byte DID | finding 05 |
| Keepalive `0xE0`/`0xE1`, data `0xD0`/`0xD1` | finding 05 |
| Payload obfuscation is **XOR 0x01** (not encryption) | finding 05 |
| Login `0x2010` as `admin`; camera returns a 4-byte session token | finding 05 |
| Video frames: `55 AA` header, 24 bytes, counter @12, length @16 | finding 05 |
| ~6 fps, ~9.4 KB/frame, 506 kbps | finding 05 |

**This firmware does not use the documented PPPP message numbers** (spec says
`0x20`/`0xF0`/`0x70`; this build uses `0x42`/`0xE0`/`0xD0`). That mismatch is what
stalled the project for its first several sessions.

## Known unknowns — read before debugging

1. **Video payload XOR-0x01 is unverified.** Strong evidence (decoded NAL types are
   `0x01`, undecoded are the implausible `0x02`), but no decoder has accepted the
   output. Install ffmpeg and run
   `ffplay research/captures/frame-test.h264` to settle it.
2. **SPS/PPS not found** in the capture. A decoder needs them to initialise. If the
   handshake succeeds but there is no picture, this is the first suspect — they may
   arrive only with a keyframe, or in a command reply.
3. **The exact "start video" command is not isolated.** Six commands arrive within
   one millisecond before frames begin, so the app replays the whole burst.
4. **Station mode is unproven.** All streaming evidence is from the camera's SoftAP.
   Whether the same works when the camera is on the home Wi-Fi is untested, and it
   matters for daily use.

## Phase status

| # | Phase | State |
|---|---|---|
| 1 | Repo, docs, tooling | done |
| 2 | APK static analysis | **skipped** — the capture answered everything; do it only if stuck |
| 3 | Traffic capture | done (SoftAP); station-mode capture still useful |
| 4 | Protocol identification | **done** — fully decoded |
| 5 | CLI proof of concept | partial — `tools/poc_client.py` still uses the *old* message numbers; port findings 05 into it if you want a PC-side test |
| 6 | Android app | **implemented, untested against hardware** |
| 7 | Tests / docs / build | 68 tests passing; docs current |
| 8 | **Browser client** | **not started** — see below |

## The browser goal

Nothing has been built for this yet. The camera speaks a proprietary UDP protocol,
which a browser cannot speak directly — so this needs a small bridge:

```
camera --UDP/PPPP--> bridge (Python or Kotlin) --HTTP/WebSocket--> browser
```

The realistic shape: reuse the decoded protocol in `tools/poc_client.py`, have it
pull H.264 frames, and serve them either as MJPEG over HTTP (simplest, works in
every browser with a plain `<img>` tag) or as fragmented MP4 over WebSocket via
Media Source Extensions (better quality, more work). This is a well-understood
problem **once the Android client proves the protocol works** — which is why it is
sequenced after the test above.

## Repo map

| Path | What |
|---|---|
| `research/findings/` | Confirmed facts, one per file. **05 is the important one.** |
| `research/03-protocol-hypotheses.md` | Open questions with disconfirming tests |
| `android-app/.../transport/pppp/` | `PpppProtocol`, `PpppCommands`, `VideoFrameParser`, `PpppTransport` |
| `tools/` | Python diagnostics; `p2p_probe.py` and `poc_client.py` are the useful ones |
| `docs/ARCHITECTURE.md` | App design and the `CameraTransport` seam |
| `research/captures/` | pcaps — **git-ignored**, contains your Wi-Fi password |

## Setup on a fresh machine

```bash
git clone https://github.com/Sachin-1712/ftycampro-app.git
cd ftycampro-app/android-app && ./gradlew assembleDebug   # JDK 17+, Android SDK 35
cd ../tools && pip install -r requirements.txt            # scapy, for pcap work
```

Android Studio's bundled JDK works: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`.
