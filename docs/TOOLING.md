# Tooling

What each tool is for, when to reach for it, and how to read what it tells you.
Installation is in [SETUP.md](SETUP.md); the order to run things in is in
[INVESTIGATION-CHECKLIST.md](INVESTIGATION-CHECKLIST.md).

---

## Written for this project

### `tools/isolation_check.py`

**Answers:** do my packets reach the camera at all?

Run before anything else. It tests reachability with TCP RST rather than ICMP,
because the two say very different things. `ECONNREFUSED` from a random high port
means the camera's stack received your packet and answered — path proven. A failed
ping means almost nothing, since this class of firmware routinely disables echo
reply while TCP works normally.

Pass `--control <ip>` naming another wireless client you know is up. **Not the
gateway** — client-isolation policies deliberately exempt it, so a reachable
gateway proves nothing about station-to-station traffic.

Reading the output:

| Result | Meaning |
|---|---|
| RST from camera | Path works. Zero open ports is a real finding. Go to `p2p_probe.py`. |
| Camera silent, control answers | Path works, camera specifically is silent or asleep. Test with the vendor app streaming. |
| Both silent, ARP resolves | AP client isolation. Every scan so far is void. Fix the router or use a hotspot. |
| Gateway silent too | Basic connectivity is broken. Nothing else is meaningful. |

### `tools/p2p_probe.py`

**Answers:** does it respond to any known P2P discovery packet?

`nmap -sU` sends empty datagrams to a port list that omits 32108, 34569 and 8600.
A service that only answers a magic byte sequence cannot be found that way — its
silence is the expected output, not evidence. This sends the real LAN-search
payloads to both the unicast target and the broadcast address, since some firmware
answers only broadcast.

A reply starting `0xF1` is the PPPP/PPCS magic byte and is close to conclusive.
The tool decodes the message type and extracts any UID it finds.

Probes marked `!` have payloads that have not been verified against real hardware.
**Silence from those means nothing** — same trap as the nmap result — and the
report separates meaningful negatives from meaningless ones rather than presenting
a single "nothing found". `--verified-only` drops the unverified probes;
`--wide` sweeps the high-confidence PPPP magic across non-standard ports.

### `tools/lan_discover.py`

**Answers:** does it speak mDNS, SSDP or ONVIF?

Low expectation, asymmetric payoff. A single ONVIF response would collapse the
project into "point Media3 at an RTSP URL", so ninety seconds is cheap. Prints any
stream URLs it finds in the replies.

### `tools/apk_analyze.sh`

**Answers:** what is in the APK?

Unzips, runs APKTool and JADX, dumps native library exports, extracts permissions
and components, and prints `network_security_config.xml` — read that one before
attempting mitmproxy, since it tells you immediately whether interception can work
without root.

Checks for its dependencies up front rather than failing ten minutes into a
decompile.

### `tools/apk_signatures.py`

**Answers:** which P2P SDK is it?

Scores the decompiled tree against fingerprints for the known families — library
filenames, exported symbols, class paths — and separately extracts hostnames
(filtering out ad and analytics domains), hard-coded IPs, UID-shaped strings,
crypto markers and codec markers.

Read this before opening any decompiled source by hand. A strong hit names the SDK
outright, and these SDKs are documented well enough that identifying one can save
the entire capture phase.

An empty report is also informative: it means the transport is vendor-private or
uses an SDK not in the catalogue, and you should work from the native export lists
in `inventory.txt` instead. Add whatever you identify back into `SIGNATURES` so the
next run recognises it.

### `tools/pcap_triage.py`

**Answers:** which flow is the video, is it encrypted, and where is the header?

Ranks flows by volume, computes payload entropy, finds the byte prefix common to
every packet in a flow, and scans for H.264 Annex-B start codes.

Reading it:

- **Entropy ≈ 8.0** — encrypted, or already-compressed media. Ambiguous on its own.
- **Annex-B start codes present** — media is in the clear. Strip the framing and
  Media3 plays it directly. This is the good outcome.
- **Constant prefix on every packet** — that is the protocol header. `0xF1` means
  PPPP; `0xFF` means XM/Sofia; anything else is what you reverse next.
- **No flows involving the camera** — the capture point could not see
  station-to-station traffic. Re-capture from a hotspot you control before
  concluding there is no local path.

`--dump-flow N` hexdumps a flow's payloads, which is what you need to find the
byte offsets that stay constant across packets.

### `tools/poc_client.py`

**Answers:** can the handshake be reproduced outside the vendor app?

Implements PPPP framing, LAN discovery, the session handshake and keepalives, and
a DRW reader that reassembles channels and scans for H.264. The vendor-specific
command layer is left as a marked hook, because inventing it would be fiction.

Prototype protocol changes here, not in the Android app — iterating on a script is
far faster than rebuilding an APK. `PpppProtocol.kt` is the Kotlin counterpart and
the two are meant to agree.

### `tools/adb_capture.sh`

Wrappers for the phone-side work: finding the package name, pulling every split
APK, reading filtered logcat (these apps usually ship with logging left on), and
on-device tcpdump if the phone is rooted.

---

## Third-party tools

| Tool | Used for | Notes |
|---|---|---|
| **JADX** | APK → Java | Errors on obfuscated code are normal; output is still usable |
| **APKTool** | Resources, smali, manifest | Better resource output than JADX |
| **PCAPdroid** | Capture without root | VPN-based, so it sees the app's sockets but not other devices' traffic |
| **Wireshark** | Reading captures | Run `pcap_triage.py` first to know where to look |
| **mitmproxy** | HTTPS interception | Only if there is TLS. Check the network security config first |
| **Frida** | SSL unpinning, native hooking | Needed if the app pins certificates |
| **nmap** | Port scanning | Always `-Pn` here, since ping fails. Throttle with `--max-rate` |
| **ffmpeg / ffplay** | Verifying extracted media | `ffplay channel-01.h264` is the fastest confirmation that you have real video |

## A note on negative results

Two of the tools here — `isolation_check.py` and `p2p_probe.py` — exist because
the project began from a conclusion drawn out of measurements that could not have
produced a positive result. That is worth keeping in mind while using the rest of
them.

Before recording "X does not respond" as a finding, check that the tool was
capable of getting a response: right port, right payload, working path. A negative
result is only evidence when a positive one was possible.
