# Device facts

Ground truth. Update as things are measured; keep interpretation out of this file.

## Hardware

| Field | Value | Source |
|---|---|---|
| Marketing name | FtyCamPro / NMCamera "Mini DV" | user |
| MAC OUI vendor | Beken | ARP + OUI lookup |
| SoC family | Beken BK72xx-class (inferred, unconfirmed) | OUI |
| Full MAC | `ae-6e-84-0c-5c-3b` | ARP, 2026-08-03 (locally-administered bit set) |
| Device UID | **`XMSYINA-772459-VNYUK`** | LAN_SEARCH reply, finding 01 |
| P2P stack | **CS2 Network PPPP/PPCS**, confirmed | finding 01 |
| UID prefix family | `XMSYINA` — XM/Sofia (Xiongmai) vendor prefix | finding 01 |
| Firmware version | _record it_ | vendor app "about" screen |

> **Note on the MAC.** `ae-6e-84...` has the locally-administered bit set (the `a`
> low nibble = `1010`), so it is a randomised/soft MAC, not a Beken OUI. The Beken
> attribution came from the OUI seen during the original scan; the address changed
> between sessions. Re-confirm the hardware vendor from the APK's native libraries
> rather than from this MAC.

Beken makes Wi-Fi/BLE combo SoCs used in low-cost cameras, smart plugs and bulbs.
Their camera reference designs ship with a P2P client in the vendor SDK, which is
why the OUI is a meaningful hint about the transport rather than just the radio.

## Network

| Field | Value |
|---|---|
| Camera IP | `192.168.29.24` (was `192.168.29.214`; DHCP reassigned it — identify it by UID, not IP) |
| Analysis host | `192.168.29.156/24` Wi-Fi, or `192.168.29.127` Ethernet |
| Gateway | `192.168.29.1` (MAC `8c-a3-99-c7-be-c1`) |
| Subnet broadcast | `192.168.29.255` |

## Scan results as reported (2026-08-02, before path validation)

| Measurement | Result |
|---|---|
| ARP resolution | succeeds |
| ICMP echo | no reply |
| TCP 80, 8080, 554, 8554 | closed |
| Full TCP 1-65535 | 0 open, **32,249 closed**, 33,286 filtered |
| UDP top-50 | all `open\|filtered`, no responses |

**Not yet validated.** Two things need noting about this table before anything is
built on it.

The 32,249 `closed` ports are the important number. In `nmap`'s vocabulary `closed`
means a TCP RST came back, so something sent 32,249 reset packets. That is very hard
to explain unless unicast packets are reaching the camera and its IP stack is
answering — which would make the failed ping just a firmware that has ICMP echo reply
switched off, and would make "no TCP listeners" a real result rather than an artifact.
It also suggests the 33,286 `filtered` ports were an embedded stack shedding load at
`nmap`'s default rate, not a firewall.

The UDP line carries no information at all. `nmap -sU` sends empty datagrams and its
top-50 list does not include 32108, 34569 or 8600, so a service that only answers a
magic byte sequence could not have been found.

Both are resolved by `tools/isolation_check.py` and `tools/p2p_probe.py`. Record the
outcomes below.

## Validation results

| Date | Test | Outcome |
|---|---|---|
| 2026-08-03 | `isolation_check.py` — reachable? | **Yes.** ICMP reply; TCP silent (no listeners). Path works, no isolation. |
| 2026-08-03 | `p2p_probe.py` — any reply? | **Yes.** `0xF1` PUNCH_PKT on UDP 32108, UID in clear. H1 confirmed. |
| 2026-08-03 | `poc_client.py` — session opens? | **No.** Discovery works; unicast follow-ups get silence (finding 02). |
| | Slow rescan — do `filtered` ports become `closed`? | not needed — ICMP already proved reachability |
| | `lan_discover.py` — mDNS/SSDP/ONVIF? | not yet run |
| | SoftAP mode — services present? | not yet run |

## Vendor app

| Field | Value |
|---|---|
| Package name | _record it_ (`adb shell pm list packages \| grep -i cam`) |
| Version name / code | _record it_ |
| Native libraries | _from `tools/apk_analyze.sh` inventory_ |
| Identified SDK | _from `tools/apk_signatures.py`_ |
