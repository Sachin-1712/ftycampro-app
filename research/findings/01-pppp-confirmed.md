# Finding 01: The camera runs the CS2 Network PPPP/PPCS P2P stack

**Date:** 2026-08-03
**Status:** confirmed
**Confirms / kills hypothesis:** confirms H1; resolves H0

## Claim

The camera speaks the CS2 Network PPPP/PPCS protocol over UDP. It answers a
broadcast `LAN_SEARCH` on UDP 32108 with a `PUNCH_PKT` (`0xF1 0x41`) carrying its
device UID in cleartext: **`XMSYINA-772459-VNYUK`**. The device is reachable on the
LAN; the original "all ports closed/filtered" scan reflected an embedded stack with
no TCP listeners, not an unreachable device.

The device was found at **`192.168.29.24`**, not `192.168.29.214` as originally
recorded — DHCP had reassigned it. MAC `ae-6e-84-0c-5c-3b`.

## How it was shown

```
$ python tools/p2p_probe.py --broadcast 192.168.29.255 --wait 8

  <- REPLY from 192.168.29.24:14206  (PPPP/PPCS LAN_SEARCH, broadcast)
  decoded : PPPP MSG_PUNCH_PKT  declared_len=20  actual_body=20  UID=XMSYINA-772459-VNYUK
  raw     :
    0000  f1 41 00 14 58 4d 53 59 49 4e 41 00 00 0b c9 6b  .A..XMSYINA....k
    0010  56 4e 59 55 4b 00 00 00                          VNYUK...
```

```
$ python tools/isolation_check.py --target 192.168.29.24 --control 192.168.29.121
  Camera 192.168.29.24: ICMP echo reply; TCP silent on 6 random high ports
  -> REACHABLE. ICMP answered.
  VERDICT: path works, no client isolation, no TCP surface -> UDP P2P camera
```

## Evidence

The reply is a textbook PPPP `PUNCH_PKT`:

| Offset | Bytes | Meaning |
|---|---|---|
| 0 | `F1` | PPPP magic |
| 1 | `41` | `MSG_PUNCH_PKT` |
| 2–3 | `00 14` | payload length = 20 (big-endian) |
| 4–11 | `58 4D 53 59 49 4E 41 00` | prefix `XMSYINA`, null-padded to 8 |
| 12–15 | `00 0B C9 6B` | serial = 772459 (big-endian) |
| 16–23 | `56 4E 59 55 4B 00 00 00` | check `VNYUK`, null-padded to 8 |

The 20-byte DID layout (prefix[8] · serial[4 BE] · check[8]) that
`PpppProtocol.encodeUid` and `poc_client.encode_uid` implement **matches the wire
bytes exactly** — re-encoding `XMSYINA-772459-VNYUK` reproduces offsets 4–23 byte
for byte. That closes the "UNCONFIRMED DID packing" question for this device.

The `XMSYINA` prefix is an XM/Sofia (Xiongmai) vendor prefix wrapped in the CS2
PPPP transport — a very common pairing in this hardware class.

## What this rules out

- **H0 Reading A (client isolation as the explanation for the original scan):** the
  camera answers ICMP, so packets reach it. The zero-TCP-ports result is a real
  property of the device, not a measurement artifact from isolation.
- **RTSP / ONVIF / HTTP:** no TCP surface at all; the transport is UDP PPPP.
- **TUTK Kalay (H2):** UID is hyphenated PREFIX-SERIAL-CHECK, not a 20-char Kalay
  string, and the reply framing is PPPP.

## What it doesn't show

The **local session does not establish from a script yet.** The device answers the
broadcast `LAN_SEARCH` but is silent to every unicast follow-up
(`HELLO`, `QUERY_DID`, `P2P_REQ`, `DEV_LGN`) sent to the port it replied from — see
finding 02. So this confirms *identification and discovery*, not *streaming*. The
media path is unproven until the session opens.

Note also that the device's reply source port changes on every `LAN_SEARCH`
(observed 10473, 11791, 14206), which is why a fixed-port unicast strategy fails and
why the next step is a capture, not more blind probing.

## Next

- Finding 02 documents the session-establishment gap.
- Capture the vendor app performing discovery → session → stream (checklist track D),
  and diff its unicast follow-up against what `poc_client.py` sends.
- The DID packing is confirmed, so `PpppProtocol.encodeUid` can drop its UNCONFIRMED
  caveat.
