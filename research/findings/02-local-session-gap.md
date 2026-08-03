# Finding 02: The local PPPP session does not open without the vendor handshake

**Date:** 2026-08-03
**Status:** confirmed
**Confirms / kills hypothesis:** partially supports H6; motivates the capture phase

## Claim

The camera answers PPPP `LAN_SEARCH` (broadcast) but does not progress to a session
in response to the standard unicast follow-ups. Sending `HELLO`, `QUERY_DID`,
`P2P_REQ` or `DEV_LGN` to the address and port the device replied from produces
silence. No `P2P_RDY`, no `DRW` data.

This means discovery is reproducible but **streaming is not yet**, and the missing
piece is the exact unicast exchange the vendor app performs — which is a capture
task, not something that can be guessed.

## How it was shown

```
$ python tools/poc_client.py --target 192.168.29.24 --uid XMSYINA-772459-VNYUK -v --listen 12

-> MSG_LAN_SEARCH
<- MSG_PUNCH_PKT len=20 ... UID=XMSYINA-772459-VNYUK      # discovery works
-> MSG_QUERY_DID                                          # silence
-> MSG_P2P_REQ  len=20 <did>                              # silence
  No P2P_RDY.
Listening 12s for DRW data...
  (no DRW data)
```

A separate probe sent `HELLO`, `QUERY_DID`, `P2P_REQ`, `DEV_LGN` and `ALIVE`
directly to the exact source port of the device's `PUNCH_PKT` reply. Every one was
met with silence.

## Interpretation (bounded)

Three explanations remain open, and this finding does **not** distinguish them —
that requires the capture:

1. **Cloud-mediated rendezvous is mandatory even on the LAN.** In many CS2/PPPP
   deployments the app first contacts a supernode, which coordinates a NAT-punch;
   the device may ignore unsolicited unicast that didn't come through that flow. The
   changing reply source port on each `LAN_SEARCH` is consistent with a
   punch-oriented design.
2. **There is an init-string / token step first.** Some builds require an
   app-computed value (derived from the UID prefix's licence, or a credential) in
   the first unicast packet before the device will engage.
3. **A different first message.** The real app may open with a message type or
   payload this PoC doesn't send.

What is *not* a live explanation: client isolation (ICMP works, finding 01) or wrong
DID packing (the encoder reproduces the wire bytes, finding 01).

## What it doesn't show

Whether a purely-local stream is achievable at all. That is exactly the D1-vs-D2
experiment in the capture phase: if local viewing in the vendor app shows a direct
phone↔camera flow, this gap is just an unknown handshake to capture and replay; if
local and remote both relay through the cloud, the camera has no local mode and the
project must be re-scoped.

## Next

1. Capture the vendor app on the same Wi-Fi doing discovery → live view
   (checklist D1, scenario S3).
2. `pcap_triage.py --peer 192.168.29.24 --dump-flow N` on the result to read the
   first unicast packets the app sends after the `PUNCH_PKT`.
3. Port those bytes into `poc_client.send_command` / `PpppTransport.startStream`.
4. Capture on mobile data (D2) as well, to settle whether a local path exists.
