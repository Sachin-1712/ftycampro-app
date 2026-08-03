# Network capture

Log of captures and what each one showed. Raw files live in `research/captures/`
and are git-ignored.

## The experiment

Capture the same scenarios under different network conditions, then diff them. The
comparison is the point — a single capture tells you what happens, but the diff
tells you *why*.

**Scenarios**

| ID | Scenario |
|---|---|
| S1 | First-run setup / provisioning |
| S2 | App login, camera list loads |
| S3 | Live video + audio, 60 seconds, then stop |
| S4 | Snapshot, start/stop recording, any other control |

**Conditions**

| ID | Condition | What it isolates |
|---|---|---|
| D1 | Phone and camera on the same Wi-Fi | local path, if one exists |
| D2 | Phone on mobile data only | forces the cloud relay |
| D3 | Phone joined to the camera's SoftAP | provisioning surface |

## The decisive comparison: D1 vs D2

Run S3 under both, then compare.

- **D1 contains a direct phone↔camera flow that D2 lacks** → a local protocol
  exists and can be reimplemented without the vendor cloud. This is the outcome the
  project needs.
- **D1 and D2 look the same, both relaying through the same external host** → the
  camera has no local mode. Say so plainly in a finding and reassess scope: an
  ad-free client would still be possible, but it would depend on the vendor cloud,
  which is a materially different project.

A caveat that costs people days: **a VPN-based capture on the phone, or sniffing
from a third host, will not show phone↔camera unicast traffic.** If D1 appears to
have no local flow, confirm the capture point could have seen one before believing
it. Capturing from a hotspot you control avoids the problem entirely — see
[docs/SETUP.md](../../docs/SETUP.md) section 4c.

## Capture log

| File | Condition | Scenario | Method | Date | Notes |
|---|---|---|---|---|---|
| | | | | | |

Naming: `<condition>-<scenario>-<yyyymmdd>.pcapng`, e.g. `d1-live-20260802.pcapng`.

## Triage

```bash
python tools/pcap_triage.py research/captures/d1-live-20260802.pcapng --peer 192.168.29.214
```

Reports flows by volume, payload entropy, repeated header prefixes and H.264 start
codes. Then:

```bash
python tools/pcap_triage.py <file> --dump-flow 0
```

to hexdump the bulk flow's payloads. What you're looking for in that dump is the
byte offsets that stay constant across packets — that's the header, and its layout
is what the PoC client has to reproduce.

## Per-capture notes

### <filename>

**Setup:** where the capture was taken from, and what it could and couldn't see.

**Flows:**

**Header structure:**

**Conclusion:** which hypothesis this supports or kills.
