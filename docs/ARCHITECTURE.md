# Architecture

## The constraint that shapes everything

The camera's protocol is not known yet. The app therefore has to be built so that
learning the answer changes *one class*, not the whole thing.

That single constraint explains most of the decisions below. The `CameraTransport`
interface exists to be the only place the protocol is visible. Everything above it
— view models, player, recording, UI — is written against an abstraction that
doesn't care whether the bytes came from a proprietary UDP session, an RTSP URL,
or a file.

```
┌────────────────────────────────────────────────────┐
│  Compose UI                                        │
│  CameraList · AddCamera · Live · Settings          │
└────────────────────┬───────────────────────────────┘
                     │ StateFlow<UiState>
┌────────────────────┴───────────────────────────────┐
│  ViewModels                                        │
└──────┬──────────────────────────────┬──────────────┘
       │                              │
┌──────┴───────────────┐   ┌──────────┴──────────────┐
│  Repositories        │   │  PlayerController       │
│  Camera · Settings   │   │  (ExoPlayer)            │
└──────┬───────────────┘   └──────────┬──────────────┘
       │                              │ MediaChunk
┌──────┴───────────────┐   ┌──────────┴──────────────┐
│  SecureCameraStore   │   │  CameraTransport  ◄──── the seam
│  DataStore           │   │  PpppTransport          │
└──────────────────────┘   │  RtspTransport          │
                           └─────────────────────────┘
```

## Layers

### `data`

`SecureCameraStore` wraps `EncryptedSharedPreferences` over a hardware-keystore
master key, and holds both the camera list and per-camera credentials.

Credentials are keyed separately from the camera record, and `Camera` has no
password field at all. That is deliberate: `Camera` gets passed into Compose
state, into logs, into `toString()` on a crash. A secret stored on it will
eventually be printed somewhere. Keeping the two apart makes the leak structurally
impossible rather than a matter of remembering.

`SecureCameraStore` also recovers from keystore corruption — which really does
happen after OS updates and device-to-device restores — by resetting the store
instead of throwing on construction. Losing the camera list is bad; an app that
cannot launch is worse.

`CameraRepository` is the single source of truth, exposing a `StateFlow` so the UI
never reads disk on the main thread. It also implements LAN discovery, sending the
same PPPP `LAN_SEARCH` broadcast as `tools/p2p_probe.py`. Keeping the two in
lockstep is useful: if the script finds the camera from the PC and the app doesn't
find it from the phone, that discrepancy is itself a finding.

### `transport`

The seam. `CameraTransport` exposes connection state and two flows of
`MediaChunk`, and nothing else about how the bytes arrive.

| Implementation | State |
|---|---|
| `PpppTransport` | Framing, discovery, handshake and keepalives are real. The DRW command layer throws `NotImplemented`. |
| `RtspTransport` | Complete — delegates to ExoPlayer's RTSP client. Only useful if the camera turns out to speak RTSP. |

`PpppTransport` failing loudly at `startStream()` is a deliberate choice. The
alternative — connecting successfully and then showing a black screen — looks like
a bug in the player and costs an hour to diagnose. An explicit
`TransportException.NotImplemented` says exactly what is missing and where.

`PpppProtocol` is pure Kotlin: no sockets, no Android, no coroutines. That makes
the framing unit-testable against bytes captured from the real device, which is
the only way to be confident about a protocol nobody has documented. It is the
counterpart of `tools/poc_client.py`, and the two are meant to agree — the Python
one is where the protocol gets worked out, this one is where it ships.

Uncertain parts are marked `UNCONFIRMED` in the source, with a note on what would
confirm them. `encodeUid`'s DID packing and `DrwHeader`'s offsets are the two that
matter.

### `stream`

`TransportDataSource` adapts a push source to ExoPlayer's pull-based `DataSource`.
The queue holding frames between them is deliberately shallow — sixteen entries,
about a second. A deep buffer would convert network jitter into permanent latency:
the player would fall further and further behind real time and never catch up.
Dropping the oldest frame keeps latency bounded, which is right for live video and
wrong for recorded playback.

`PlayerController` picks between two paths. RTSP hands ExoPlayer a URL and lets it
own the socket; everything else feeds `TransportDataSource` into an explicit
`H264Extractor`, because a raw Annex-B stream has no container for ExoPlayer to
sniff. Its load control is also tuned down from ExoPlayer's on-demand defaults for
the same latency reason.

`MediaWriter` writes snapshots to the shared Pictures collection via MediaStore,
so they appear in the gallery. Recordings go to app-private storage as raw
`.h264`, **not** MP4 — see the note in that class: `MediaMuxer` needs real
presentation timestamps, and the transport currently synthesises them at a nominal
15fps because the camera's true timestamps live in a per-frame header that hasn't
been decoded yet. Muxing against invented timestamps produces files that play at
the wrong speed and drift. An honest `.h264` can be remuxed correctly later:

```bash
ffmpeg -framerate 15 -i recording.h264 -c copy recording.mp4
```

### `ui`

Compose with MVVM. View models expose a single immutable `UiState` per screen;
screens are otherwise stateless. Navigation is Navigation-Compose with routes in
one object.

Errors carry a `hint` alongside the message. "Connection failed" on its own is
useless when the most likely cause is a router setting three menus deep, so
`TransportException.Unreachable` explains what client isolation is and why it
would produce exactly this symptom.

## Dependency injection

Manual, via `AppContainer`. Hilt would mean a compiler plugin plus KSP plus a
build that breaks whenever those disagree with the Kotlin version. This app has
six dependencies and a transport layer that is going to be rewritten; the object
graph is not the hard part, and a boring build is worth more than the annotations.
Room and kotlinx-serialization are absent for the same reason — camera records go
through `org.json`, which ships with Android.

## No ads, no tracking

Not a claim, a property of the dependency list. There is no analytics SDK, no
crash reporter, no advertising library, and nothing that phones home. The
permission set in `AndroidManifest.xml` lists only what a named feature needs, and
notes which permissions the vendor app requests that this one does not.

`network_security_config.xml` permits cleartext **only** for RFC1918 and
link-local ranges. Everything routable is TLS-only. This is the inverse of the
usual vendor configuration, which sets `usesCleartextTraffic="true"` globally.

Backups are disabled and `data_extraction_rules.xml` excludes everything, so
credentials never reach cloud backup or a device transfer.

Logging stays on the device. Verbose protocol tracing can be turned on in Settings
— reverse-engineering needs it — and writes to a bounded in-memory ring buffer
that the user exports deliberately. Nothing is written to disk unless they ask.

## Testing

| Layer | Approach |
|---|---|
| `PpppProtocol` | Pure unit tests. Encode/decode round trips, malformed input, UID packing. |
| `FrameAssembler` | Unit tests for fragmentation, key-frame detection and the unbounded-growth guard. |
| `AddressValidator` | Unit tests for every rejection path. |
| View models | Turbine over the state flows. |
| Transports | Not unit tested against a device that doesn't exist yet. |

The protocol tests currently assert against data this code generated itself, which
proves internal consistency and nothing more. **When a capture yields real device
bytes, add them as fixtures** — that is what turns these from self-consistency
checks into real regression tests.

```bash
cd android-app && ./gradlew testDebugUnitTest
```

## Reproducible build

`org.gradle.configuration-cache` and `org.gradle.caching` are on;
`jniLibs.useLegacyPackaging=false` gives deterministic ordering inside the APK.
Release signing is intentionally unconfigured — supply your own key through
`signingConfigs`; no key material belongs in the repository.

```bash
cd android-app
gradle wrapper --gradle-version 8.9   # once; the wrapper JAR is not committed
./gradlew assembleRelease
```

Requires JDK 17 and Android SDK 35, minSdk 26.

## What happens when the protocol is identified

1. Prototype the working exchange in `tools/poc_client.py` — iterating on a script
   is far faster than rebuilding an APK.
2. Port the confirmed bytes into `PpppProtocol`, replacing the `UNCONFIRMED`
   sections, and add the captured packets as test fixtures.
3. Implement `PpppTransport.startStream()` and the DRW command layer.
4. Replace `FrameAssembler`'s synthesised timestamps with the camera's real ones,
   then switch `MediaWriter` to `MediaMuxer` for proper MP4 output.

Nothing above `CameraTransport` should need to change. If it does, the seam is in
the wrong place and should be moved.
