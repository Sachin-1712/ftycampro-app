# android-app

Kotlin / Jetpack Compose / MVVM client. No ads, no analytics, no crash reporting,
nothing that phones home.

## Build

```bash
cd android-app
gradle wrapper --gradle-version 8.9
```

The wrapper JAR isn't committed — run that once, or open the folder in Android
Studio and let it generate one.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

JDK 17, Android SDK 35, minSdk 26.

## State

| Feature | State |
|---|---|
| Add camera by UID or IP | works |
| LAN discovery | works — sends the same PPPP broadcast as `tools/p2p_probe.py` |
| Encrypted credential storage | works |
| Settings | works |
| Fullscreen, mute, recording controls | works |
| RTSP playback | works, if the camera turns out to speak RTSP |
| **Live video over PPPP** | **blocked** — needs the DRW command layer |
| Snapshot | blocked on the above |

The app compiles, packages to a debug APK, and all 41 unit tests pass (verified
with Android Studio's bundled JDK + Gradle 9.3). The blocker for live video is one
method: `PpppTransport.startStream()`. The framing, discovery, handshake and
keepalives around it are implemented and runnable; the command that tells the
camera to start sending video is vendor-specific and can't be written until a
capture of the vendor app reveals it. It throws
`TransportException.NotImplemented` rather than connecting and showing a black
screen, which would look like a player bug and cost an hour to diagnose.

See [docs/INVESTIGATION-CHECKLIST.md](../docs/INVESTIGATION-CHECKLIST.md) track E.

## Layout

```
dev/ftycam/
├── data/           repositories, encrypted store, models
├── transport/      the protocol seam — CameraTransport and implementations
│   ├── pppp/       PpppProtocol (pure, testable), PpppTransport, FrameAssembler
│   └── rtsp/       RtspTransport
├── stream/         ExoPlayer glue, snapshot and recording writers
├── ui/             Compose screens and view models
├── di/             AppContainer (manual DI)
└── util/           logging
```

Design decisions and their reasons are in
[docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).

## Adding a transport

1. Implement `CameraTransport`.
2. Add a `TransportKind` entry and a branch in `TransportFactory`.

Nothing above the transport layer should need to change. If it does, the seam is
in the wrong place.

## Tests

`PpppProtocol`, `FrameAssembler` and `AddressValidator` have unit tests. They
currently assert against data the code generated itself, which proves internal
consistency and nothing more — **when a capture yields real device bytes, add them
as fixtures.** That is what turns these into real regression tests.
