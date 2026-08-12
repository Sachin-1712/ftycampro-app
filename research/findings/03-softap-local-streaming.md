# Finding 03: The camera streams live video fully locally over its own SoftAP

**Date:** 2026-08-12
**Status:** confirmed
**Confirms hypothesis:** H3 (SoftAP exposes the real local surface)

## Claim

With the phone joined to the camera's own access point, the **vendor app streams
live video with no internet connection and no cloud involvement**. The camera is
the AP gateway at `192.168.1.1`.

This is the single most important result so far: **a purely local video path
exists and works.** The project's goal — an ad-free client that never touches the
vendor cloud — is achievable, not merely hoped for.

## Evidence

Vendor app (`ftycampro`), phone on the camera's SoftAP:

| Field | Value |
|---|---|
| Device UID | `FTYA-747353-SZNTL` |
| Address | `192.168.1.1` (camera is the AP gateway) |
| Mode | `p2p` |
| Firmware | `2.2.2.45` |
| Storage | `No TF-Card` |
| Status | `Logged in`, live video rendering |

The app shows a working video feed, plus controls for snapshot, record, mic
(two-way audio), mirror/flip, OSD, IR light and QoS.

Our own client, on the same SoftAP, reaches the camera too:

```
Discovery         success
UID               FTYA-747353-SZNTL
Current IP        192.168.1.1
UDP source port   62512 (ephemeral)
Session handshake failed
Endpoints tried   192.168.1.1:62512, 192.168.1.1:32108
```

```
13:47:27.314  TX  LAN_SEARCH -> 255.255.255.255:32108
13:47:27.318  RX  PUNCH_PKT uid=FTYA-747353-SZNTL <- 192.168.1.1:62512
13:47:31.338  TX  P2P_REQ -> 192.168.1.1:62512
13:47:34.345  --  no reply to P2P_REQ from 192.168.1.1:62512 after 3000ms
13:47:34.346  TX  HELLO -> 192.168.1.1:62512
```

## What this establishes

1. **Local streaming is real.** No cloud rendezvous is required for video — the
   vendor app does it over an isolated AP with no internet at all. This kills the
   worst-case scenario in the H0 decision tree (cloud-only device, project
   unachievable).
2. **Discovery is fully reproduced.** Our `LAN_SEARCH` gets a `PUNCH_PKT` with the
   UID in cleartext, in ~4ms, identically to the vendor app's own discovery.
3. **The gap is precisely one step wide.** Everything up to and including discovery
   matches. The very next message — the session request — is where we diverge, and
   the camera ignores ours.
4. **The answer is now capturable.** The vendor app performs the exact exchange we
   are missing, on an isolated two-node network with no other traffic. That is the
   cleanest possible capture environment.

## What it does not show

Whether the same local path works when the camera is in station mode on the home
Wi-Fi rather than running its own AP. That is a separate test (finding 02 covers
the station-mode session gap) and matters for the final product, since users will
want the camera on their normal network.

## Next

Capture the vendor app over the SoftAP with PCAPdroid, then diff its first unicast
packets after `PUNCH_PKT` against ours. That yields the session handshake and
unblocks `PpppTransport.startStream()`.
