# Investigation checklist

Work top to bottom. Each step states what it produces and what would make you stop
and change course. Record outcomes in `research/findings/` using the template in
`research/findings/_TEMPLATE.md` — **confirmed observations only**. Anything you
believe but haven't demonstrated belongs in
[`research/03-protocol-hypotheses.md`](../research/03-protocol-hypotheses.md).

Tick boxes as you go; this file is meant to be edited and committed.

---

## Track A — Validate the measurement path

Nothing downstream is trustworthy until A1 passes. This is the step that the
original scan skipped.

- [ ] **A1. Rule out AP / client isolation.**
  ```bash
  python tools/isolation_check.py --target 192.168.29.214 --iface-ip 192.168.29.156
  ```
  The script needs a **positive control**: a second device on the same SSID that you
  know is up (a phone, a laptop, a printer). If the control is also unreachable by
  unicast while its ARP entry resolves, the access point is isolating clients and
  *every* local scan result so far is void.

  *Fix if isolated:* disable "AP isolation" / "client isolation" / "guest network
  isolation" in the router admin UI, or move the camera and the analysis host onto a
  dedicated hotspot you control (a laptop's shared connection or a spare travel
  router works, and has the bonus of putting all traffic on an interface you can
  capture from directly).

  *Stop condition:* if the control host answers pings but the camera never does, the
  camera itself is silently dropping ICMP — normal for these SoCs — and you proceed
  to A2 rather than fighting the router.

- [ ] **A2. Confirm the camera is actually awake.** Many battery-backed Mini DV
  cameras drop to a low-power state and only bring the radio up on a cloud push.
  Open the vendor app and start a live view, then immediately re-run A1. If the
  device becomes reachable only while the app is streaming, that is itself a major
  finding — record it.

- [ ] **A3. Re-run a TCP scan while the stream is live**, from a host on the same L2
  segment, with isolation confirmed off:
  ```bash
  nmap -Pn -n -sS -p- --min-rate 2000 192.168.29.214
  ```
  `-Pn` matters: without it, `nmap` will skip the host entirely because ping fails.
  Note whether the earlier "filtered" ports become "closed" — that change alone tells
  you the path was previously blocked upstream.

- [ ] **A4. Passive listen.** Before sending anything, watch what the camera emits
  unprompted for ten minutes with the app open and again with the app closed:
  ```bash
  sudo tcpdump -i wlan0 -n -w research/captures/passive-idle.pcap host 192.168.29.214
  ```
  Destination IPs and ports here name the vendor's cloud infrastructure and the
  keepalive cadence. This is often the single most informative capture in the whole
  project, and it requires no interaction with the device at all.

---

## Track B — Local protocol discovery

- [ ] **B1. Magic-packet UDP probe.** `nmap`'s empty datagrams prove nothing here.
  ```bash
  sudo python tools/p2p_probe.py --target 192.168.29.214 --broadcast 192.168.29.255
  ```
  This sends the LAN-search payloads for the P2P SDK families common to this
  hardware class, including PPPP/PPCS on UDP 32108 which the top-50 UDP list omits.
  A reply beginning `0xF1` is close to conclusive for the CS2 Network family and
  usually carries the device UID in cleartext.

- [ ] **B2. Broadcast vs unicast.** Some firmware answers only broadcast discovery and
  ignores the identical packet sent unicast. `p2p_probe.py` tries both; make sure you
  read both columns of the output before concluding a port is dead.

- [ ] **B3. Standard discovery protocols**, for completeness and because they're cheap:
  ```bash
  python tools/lan_discover.py --iface-ip 192.168.29.156
  ```
  Covers mDNS, SSDP and ONVIF WS-Discovery. Low expectation of a hit given the port
  scan, but a single ONVIF response would collapse this entire project into "point
  Media3 at an RTSP URL," so it's worth ninety seconds.

- [ ] **B4. The camera's own access point.** Before it joined your Wi-Fi, the camera
  ran a SoftAP for provisioning. Factory-reset it (or trigger pairing mode), join that
  AP directly, and repeat A3, B1 and B3 against the gateway address — typically
  `192.168.1.1`, `192.168.169.1` or `172.16.10.1`. **Provisioning mode almost always
  exposes far more local surface than station mode does**, because the app has to
  configure the device over it with no cloud available. If a local control channel
  exists anywhere, it exists here.

---

## Track C — APK static analysis

- [ ] **C1. Get the APK off your own phone.** Do not download it from a mirror site;
  you want the exact build you're capturing traffic from.
  ```bash
  adb shell pm path com.example.ftycampro     # find the real package name first
  adb pull /data/app/.../base.apk research/captures/ftycampro.apk
  ```
  Package name discovery: `adb shell pm list packages | grep -iE 'cam|fty|nm'`.
  Split APKs will list several paths — pull all of them.

- [ ] **C2. Decompile and inventory.**
  ```bash
  bash tools/apk_analyze.sh research/captures/ftycampro.apk
  ```
  Produces `research/01-apk-analysis/` containing the JADX Java output, the APKTool
  resource/smali output, the native library inventory, and an automatic signature
  report.

- [ ] **C3. Read the signature report first.**
  ```bash
  python tools/apk_signatures.py research/01-apk-analysis
  ```
  It scores the decompiled tree against the known P2P SDK fingerprints (library
  names, JNI symbol names, characteristic class paths, hard-coded server hostnames).
  A strong hit here can save you the entire capture phase, because these SDKs are
  well documented and in several cases have public client implementations.

- [ ] **C4. Extract the hard-coded endpoints.** The report lists candidate hostnames
  and IPs. Cross-reference them against the destinations seen in A4 — agreement
  between static strings and observed traffic is what upgrades a hypothesis to a
  finding.

- [ ] **C5. Locate the native transport.** For every `.so` in `lib/`:
  ```bash
  nm -D --defined-only research/01-apk-analysis/unzipped/lib/arm64-v8a/libXXX.so | less
  ```
  Exported symbols like `PPPP_Connect`, `IOTC_Connect_ByUID`, `av_client_start`,
  `P2P_Init` name the SDK outright. Record the full export list — it defines the
  API surface you'd have to reproduce.

- [ ] **C6. Find the crypto and the credential handling.** Search the JADX output for
  `AES`, `Cipher.getInstance`, `MD5`, `SecretKeySpec`, and for the string constants
  passed to them. Cheap camera SDKs very often use a fixed key compiled into the app.
  Record where the key material comes from, not just that encryption exists.

- [ ] **C7. Codec and container.** Search for `MediaCodec`, `avcodec`, `H264`, `H265`,
  `G711`, `ADPCM`, `AAC`. Determines what Media3 will need to be handed and whether an
  FFmpeg decoder extension is required at all.

---

## Track D — Traffic capture

Capture the same four scenarios in each network condition so the diffs are meaningful.
Details and exact commands in [SETUP.md](SETUP.md#capturing-traffic).

Scenarios:
1. First-run camera setup / provisioning
2. App login and camera list load
3. Live video + audio start, run 60s, stop
4. Controls: snapshot, start/stop recording, any PTZ or IR toggle

Conditions:
- [ ] **D1. Local Wi-Fi**, phone and camera on the same SSID
- [ ] **D2. Mobile data**, phone off Wi-Fi entirely — forces the cloud relay path
- [ ] **D3. Camera SoftAP**, phone joined directly to the camera

The D1-vs-D2 diff is the key experiment of the whole project: **if D1 shows a direct
phone↔camera flow that D2 lacks, a local-only protocol exists and can be
reimplemented without touching the vendor cloud.** If D1 and D2 are byte-for-byte
similar and both go through the same relay, the camera has no local mode and the
project's ceiling is much lower — say so in the findings and reassess.

- [ ] **D4. Triage each capture.**
  ```bash
  python tools/pcap_triage.py research/captures/d1-local-live.pcapng
  ```
  Reports endpoint pairs, per-flow byte volumes, packet size distributions, payload
  entropy and repeated header prefixes. High entropy on the bulk flow means encrypted
  or already-compressed video; a repeating low-entropy prefix on every packet is your
  protocol header, and its offsets are what you reverse next.

- [ ] **D5. TLS interception, only if the triage shows real TLS.** `mitmproxy` with a
  user CA will not be trusted by an app targeting API 24+ unless it opts in. Expect to
  need either a rooted device with the CA in the system store, or Frida SSL unpinning.
  If the bulk transport is raw UDP — which the hypotheses say is likely — skip this
  entirely; there's no TLS to intercept.

---

## Track E — Reproduce

- [ ] **E1.** Replay a captured discovery/handshake exchange from the PC and confirm the
  camera answers. This is the moment the project becomes real: it proves the protocol
  is reproducible outside the vendor app.
- [ ] **E2.** Complete authentication from a script.
- [ ] **E3.** Pull one video frame and write it to disk.
  `tools/poc_client.py` is the skeleton to fill in.
- [ ] **E4.** Sustain a stream and pipe it to a file playable by `ffplay`.
- [ ] **E5.** Port the working transport into `android-app` behind the
  `CameraTransport` interface.

---

## Progress log

| Date | Step | Outcome | Finding doc |
|---|---|---|---|
| | | | |
