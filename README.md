# ftycam

A clean-room investigation of a **FtyCamPro / NMCamera "Mini DV"** wireless camera,
and an ad-free, tracker-free Android client for it.

The vendor app works but is loaded with advertising and analytics. The goal is to
understand how the camera actually talks to its app, then reimplement just the parts
needed for live video, audio, snapshots and recording — for a camera the owner
physically possesses, on their own network.

> **Scope.** Everything here targets one physically-owned device and one legitimately
> installed copy of its companion app. No third-party accounts, no vendor cloud
> credentials, no other people's cameras. See [docs/LEGAL-SCOPE.md](docs/LEGAL-SCOPE.md).

---

## Read this first: the scan results probably don't mean what they look like

The starting evidence was:

| Observation | Result |
|---|---|
| ARP resolution for `192.168.29.214` | succeeds, Beken OUI |
| `ping 192.168.29.214` | no reply |
| Full 65535-port TCP scan | 32,249 closed, 33,286 filtered, **0 open** |
| UDP top-50 scan | every port `open\|filtered`, no responses |

The natural reading is "the device exposes no local services, therefore it must be
cloud/P2P only." That conclusion is probably right — but the most important line in
that table is the one that's easiest to skim past, and it argues the *opposite* of
what the failed ping suggests:

**`nmap` reported 32,249 ports as `closed`, and `closed` means it received a TCP RST.**
Something sent 32,249 reset packets back. Under AP client isolation nothing would come
back at all and every port would read `filtered`. So unicast packets almost certainly
*are* reaching the camera and its TCP stack *is* answering — it simply has no
listeners. The failed ping means only that the firmware has ICMP echo reply disabled,
which is completely ordinary on these SoCs.

That reading also explains the 33,286 `filtered` ports without invoking a firewall: a
tiny embedded stack being hit at `nmap`'s default rate drops whatever it can't service.
The discriminating test is to rescan a small range slowly — if those ports come back
`closed` when throttled, it was rate limiting, and the entire TCP port space really has
been scanned and really is empty.

The second trap is subtler. **`nmap -sU` reports `open|filtered` when it gets no reply,
and for most ports it sends an empty datagram.** A UDP service that only answers a
specific magic byte sequence is invisible to it *by construction*, and the top-50 UDP
list doesn't include 32108, 34569 or 8600 anyway. Silence there is the expected output
for a perfectly healthy service, not evidence of absence.

So the order of work is: first confirm reachability the way that actually settles it
(RST, not ping) with [`tools/isolation_check.py`](tools/isolation_check.py); then probe
UDP with payloads these devices actually answer, using
[`tools/p2p_probe.py`](tools/p2p_probe.py). If the RST reading holds, "no TCP
listeners, UDP-only P2P stack" goes from a guess to a well-supported finding, and the
Beken SoC plus a UID-based app narrows the candidates sharply — see
[research/03-protocol-hypotheses.md](research/03-protocol-hypotheses.md).

The second job is probing UDP with payloads that these devices actually answer.
[`tools/p2p_probe.py`](tools/p2p_probe.py) sends the LAN-discovery magic packets used
by the P2P SDK families that dominate this class of hardware — notably the
CS2 Network **PPPP/PPCS** family on UDP 32108, which `nmap`'s top-50 list does not
even cover.

---

## Repository layout

```
ftycam/
├── docs/            Setup, tooling, architecture, checklist, scope
├── research/        Findings, hypotheses, capture notes, templates
│   ├── 01-apk-analysis/
│   ├── 02-network-capture/
│   ├── findings/            confirmed facts only
│   └── captures/            pcaps and APKs (git-ignored)
├── tools/           Python + shell instrumentation (the runnable part)
└── android-app/     Kotlin / Compose / MVVM client
```

## What's been confirmed against the actual camera

The tooling has already been run against the device, and it settled the two open
questions the original scan couldn't (see [research/findings/](research/findings/)):

- **It's a CS2 Network PPPP/PPCS camera.** It answers a broadcast `LAN_SEARCH` on
  UDP 32108 with a `0xF1` PUNCH_PKT carrying its UID, `XMSYINA-772459-VNYUK`, in the
  clear. Hypothesis H1 confirmed.
- **It's reachable — the scan wasn't measuring isolation.** The camera answers ICMP;
  TCP is silent because it has no listeners, not because packets don't arrive. The
  "all ports closed/filtered" result was a device with no TCP surface, exactly as a
  UDP-only P2P camera should look.
- **DHCP had moved it** from `.214` to `.24`. Identify it by UID, not IP.
- **Local session establishment is the open problem.** Discovery reproduces
  perfectly; the unicast handshake that follows does not, and pinning it down needs
  a capture of the vendor app (finding 02).

## Phase status

| # | Phase | State | Gated on |
|---|---|---|---|
| 1 | Repository, docs, checklist, tooling | **done** | — |
| 2 | APK static analysis | **tooling ready** | the vendor APK |
| 3 | Traffic capture and comparison | **tooling ready** | phone + camera + capture host |
| 4 | Identify the P2P SDK / local handshake | **SDK identified (PPPP, confirmed); handshake open** | a vendor-app capture |
| 5 | CLI proof of concept (one frame) | **discovery works against the real device; session/frame open** | outcome of a capture |
| 6 | Android app | **scaffold builds; discovery + DID packing confirmed against device** | the stream command from 5 |
| 7 | Tests, logging, reproducible build | **partial; real captured bytes now a test fixture** | 5 and 6 |

Phases 2–5 need physical artifacts that can't be produced from a keyboard alone: the
APK, the phone, the camera. For each of those, what's committed here is the thing that
makes the phase runnable the moment you supply the input — a script, a checklist, a
template — rather than a guess at its output. Speculation lives in
[research/03-protocol-hypotheses.md](research/03-protocol-hypotheses.md), clearly
labelled with confidence levels and a disconfirming test for each entry, and never in
`research/findings/`.

---

## Quick start

```bash
cd tools && python -m venv .venv && . .venv/Scripts/activate && pip install -r requirements.txt
```

Then, in order:

```bash
python tools/isolation_check.py --target 192.168.29.214 --iface-ip 192.168.29.156
```

```bash
sudo python tools/p2p_probe.py --target 192.168.29.214 --broadcast 192.168.29.255
```

```bash
bash tools/apk_analyze.sh research/captures/ftycampro.apk
```

Full instructions, including how to get the APK off your own phone and how to capture
traffic without rooting it, are in [docs/SETUP.md](docs/SETUP.md).

## Where to look next

- **What to do, in order** → [docs/INVESTIGATION-CHECKLIST.md](docs/INVESTIGATION-CHECKLIST.md)
- **What each tool does and when to reach for it** → [docs/TOOLING.md](docs/TOOLING.md)
- **What the camera probably is** → [research/03-protocol-hypotheses.md](research/03-protocol-hypotheses.md)
- **How the Android app is structured** → [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Licence

MIT — see [LICENSE](LICENSE).
