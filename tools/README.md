# tools

Instrumentation for the investigation. Everything except `pcap_triage.py` runs on
the standard library alone.

```bash
pip install -r tools/requirements.txt   # only needed for pcap_triage.py
```

| Tool | Answers | Needs |
|---|---|---|
| [`isolation_check.py`](isolation_check.py) | Do my packets actually reach the camera? | — |
| [`p2p_probe.py`](p2p_probe.py) | Does it answer any known P2P LAN-discovery packet? | — |
| [`lan_discover.py`](lan_discover.py) | Does it speak mDNS / SSDP / ONVIF? | — |
| [`apk_analyze.sh`](apk_analyze.sh) | What's inside the APK? | jadx, apktool, unzip |
| [`apk_signatures.py`](apk_signatures.py) | Which P2P SDK is it? | — |
| [`adb_capture.sh`](adb_capture.sh) | Pull the APK, read logs, capture on-device | adb |
| [`pcap_triage.py`](pcap_triage.py) | Which flow is the video, and is it encrypted? | scapy |
| [`poc_client.py`](poc_client.py) | Can the handshake be reproduced from a script? | — |

Run them roughly in that order; it matches
[docs/INVESTIGATION-CHECKLIST.md](../docs/INVESTIGATION-CHECKLIST.md).

---

## The two that matter most

### `isolation_check.py` — run this first

Settles whether the original scan measured the camera or measured the network,
using TCP RST rather than ICMP as the reachability test. `ECONNREFUSED` from a
random high port proves end-to-end reachability; a failed ping proves nothing,
because this class of firmware routinely disables echo reply while the TCP stack
answers normally.

```bash
python tools/isolation_check.py --target 192.168.29.214 --iface-ip 192.168.29.156
```

Add `--control <ip>` naming another wireless client you know is up. Not the
gateway — client-isolation policies exempt the gateway, so a reachable gateway
tells you nothing about station-to-station traffic.

### `p2p_probe.py` — the probe nmap couldn't do

`nmap -sU` sends empty datagrams to a port list that omits 32108, 34569 and 8600.
A service that only answers a magic byte sequence is invisible to it. This sends
the real LAN-search payloads.

```bash
sudo python tools/p2p_probe.py --target 192.168.29.214 --broadcast 192.168.29.255
```

A reply beginning `0xF1` is the PPPP/PPCS magic byte and is close to conclusive.

Probes whose payload has not been verified against real hardware are printed with
a `!` and called out separately in the results. **Silence from those means
nothing** — the same trap as the original UDP scan. `--verified-only` drops them;
`--wide` sweeps the high-confidence PPPP magic across non-standard ports.

---

## Notes

`p2p_probe.py` and `lan_discover.py` send to broadcast and multicast addresses, so
they touch every device on the subnet. Harmless, but if anything on the network is
fussy, do this on an isolated hotspot instead — which you may want anyway, since a
hotspot you control is the only reliable way to capture phone↔camera traffic
(see [docs/SETUP.md](../docs/SETUP.md) section 4c).

Binding broadcast sockets may need elevation depending on the firewall profile. On
Windows, `scapy` additionally needs [Npcap](https://npcap.com/) with WinPcap
compatibility enabled, though only `pcap_triage.py` uses it.

## Extending

`apk_signatures.py` carries a `SIGNATURES` list of SDK fingerprints. If the report
comes back empty, that is a finding in itself — work from the native export lists
in `inventory.txt`, and add whatever you identify back into that list so the next
run recognises it.

`p2p_probe.py` carries a `PROBES` list in the same spirit. When a capture reveals
the exact bytes the vendor app sends, add them as a probe and set `verified=True`.
