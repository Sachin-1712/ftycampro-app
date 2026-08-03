# Scope and boundaries

## What this project does

Interoperability research on **one camera that the operator physically owns**, on
**their own network**, using **their own legitimately installed copy** of the
vendor's app, in order to write a client that talks to that camera without ads or
tracking.

## What it does not do

- **No third-party devices.** Only the camera at `192.168.29.214`, owned by the
  operator. Nothing that scans, enumerates or contacts cameras belonging to anyone
  else.
- **No account bypass.** The vendor's cloud authentication is not circumvented,
  and no attempt is made to authenticate as another user or to obtain credentials
  that weren't already the operator's.
- **No attacks on vendor infrastructure.** The cloud servers are observed only in
  the sense that traffic the app already sends is recorded. They are not probed,
  fuzzed or load-tested.
- **No redistribution of vendor code.** Decompiled output stays local and
  git-ignored. Findings describe protocol behaviour in the operator's own words;
  they do not republish the vendor's source or binaries.
- **No exploitation of known SDK weaknesses.** The P2P stacks in this hardware
  class have published vulnerabilities — the iLnkP2P UID-enumeration issues
  (CVE-2019-11219 / CVE-2019-11220) are the obvious example. They are noted in the
  hypotheses because they help *identify* the SDK and because they are relevant to
  the operator's own security posture. They are not used to reach any device.

## A note on the UID-enumeration weakness

`research/03-protocol-hypotheses.md` mentions that some builds of the PPPP family
derive a UID's check block from its serial, which historically allowed strangers'
cameras to be enumerated. That is recorded for two legitimate reasons: it is a
strong fingerprint for identifying which SDK is in use, and if this camera turns
out to be affected, the operator should know that their device is reachable by
anyone who guesses the UID — which is information they need in order to decide
whether to keep the camera on their network at all.

Deriving identifiers in order to reach devices you do not own is unauthorised
access, and is illegal in most jurisdictions regardless of how easy the vendor
made it. Nothing in `tools/` does this: `p2p_probe.py` and `lan_discover.py`
broadcast to the local subnet, which is how discovery protocols work, and neither
scans address ranges nor enumerates identifiers.

## If the camera turns out to be cloud-only

If capture shows that local and remote viewing take the same relayed path, and the
SoftAP exposes nothing either, then the camera has no local mode. The honest
response is to record that finding and re-scope the project — an ad-free client
would still be possible but would depend on the vendor's cloud, which is a
materially different piece of work. Working around the cloud's authorisation to
avoid that conclusion is out of scope.

## A practical warning

Provisioning captures contain the Wi-Fi passphrase — the SoftAP setup exchange
transmits it, frequently with only light obfuscation. They also contain the device
UID, a durable identifier for a camera inside a home. That is why
`research/captures/` is git-ignored and why findings should carry redacted
excerpts rather than raw files. Check what you are committing before you commit
it.

## Reporting

If the analysis surfaces something serious that affects other owners of the same
hardware, consider reporting it to the vendor before publishing details. This
repository cannot enforce that; it is worth extending anyway.