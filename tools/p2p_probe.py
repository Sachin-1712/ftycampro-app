#!/usr/bin/env python3
"""Probe a camera for P2P SDK LAN-discovery services.

Why this exists: `nmap -sU` sends empty datagrams to a port list that does not
include the ports this hardware class uses (32108, 34569, 8600...). A UDP service
that only answers a specific magic byte sequence is invisible to that scan by
construction, so "no response" from nmap is the expected output for a healthy
service rather than evidence of absence.

This sends the actual LAN-search payloads used by the P2P stacks common in cheap
IP cameras, to both the unicast target and the subnet broadcast address, and
decodes whatever comes back.

    python p2p_probe.py --target 192.168.29.214 --broadcast 192.168.29.255

Probes whose payload has not been verified against a real device are marked
UNVERIFIED in the output. Silence from those tells you nothing — same trap as the
nmap result. Only silence from a verified probe is meaningful.
"""

from __future__ import annotations

import argparse
import ipaddress
import re
import select
import socket
import sys
import time
from dataclasses import dataclass, field
from typing import Callable

# --------------------------------------------------------------------------
# PPPP / PPCS / iLnkP2P  (CS2 Network)
# --------------------------------------------------------------------------
# Every packet is: F1 <type:u8> <payload_len:u16 be> [payload]
# The 0xF1 magic makes responses unmistakable, which is what makes this the
# single most informative probe in the set.

PPPP_MAGIC = 0xF1

PPPP_TYPES = {
    0x00: "MSG_HELLO",
    0x01: "MSG_HELLO_ACK",
    0x02: "MSG_HELLO_TO",
    0x03: "MSG_HELLO_TO_ACK",
    0x08: "MSG_QUERY_DID",
    0x09: "MSG_QUERY_DID_ACK",
    0x10: "MSG_DEV_LGN",
    0x11: "MSG_DEV_LGN_ACK",
    0x12: "MSG_DEV_LGN_CRC",
    0x14: "MSG_DEV_LGN_KEY",
    0x20: "MSG_P2P_REQ",
    0x21: "MSG_P2P_REQ_DSK",
    0x30: "MSG_LAN_SEARCH",
    0x31: "MSG_LAN_NOTIFY",
    0x32: "MSG_LAN_NOTIFY_ACK",
    0x40: "MSG_PUNCH_TO",
    0x41: "MSG_PUNCH_PKT",
    0x42: "MSG_PUNCH_READY",
    0x50: "MSG_P2P_RDY",
    0x60: "MSG_RS_LGN",
    0x70: "MSG_DRW",
    0x71: "MSG_DRW_ACK",
    0xF0: "MSG_ALIVE",
    0xF1: "MSG_ALIVE_ACK",
    0xF8: "MSG_CLOSE",
    0xFF: "MSG_REPORT_SESSION_READY",
}

# UID shape used by this SDK family: PREFIX-SERIAL-CHECK, e.g. "ABCD-123456-EFGHI".
UID_RE = re.compile(rb"[A-Z]{3,8}-?[0-9]{5,8}-?[A-Z]{4,6}")


def decode_did(payload: bytes) -> str | None:
    """Decode the 20-byte DID structure: prefix[8] serial[4 be] check[8].

    The UID does not appear as contiguous ASCII on the wire — the prefix is
    null-padded and the serial is binary — so a regex over the payload will miss
    it. This reassembles the printable form.
    """
    if len(payload) < 12:
        return None
    prefix = payload[:8].split(b"\x00")[0].decode("ascii", "ignore")
    if not prefix or not prefix.isalnum():
        return None
    serial = int.from_bytes(payload[8:12], "big")
    check = payload[12:20].split(b"\x00")[0].decode("ascii", "ignore") if len(payload) >= 20 else ""
    return f"{prefix}-{serial:06d}-{check}" if check else f"{prefix}-{serial:06d}"


def decode_pppp(data: bytes) -> str | None:
    """Return a human description if `data` looks like a PPPP packet."""
    if len(data) < 4 or data[0] != PPPP_MAGIC:
        return None
    msg_type = data[1]
    declared = int.from_bytes(data[2:4], "big")
    name = PPPP_TYPES.get(msg_type, f"unknown(0x{msg_type:02x})")
    body = data[4:]
    parts = [f"PPPP {name}", f"declared_len={declared}", f"actual_body={len(body)}"]

    uid = decode_did(body) or (
        match.group().decode("ascii", "replace") if (match := UID_RE.search(body)) else None
    )
    if uid:
        parts.append(f"UID={uid}")
    strings = printable_runs(body, minimum=4)
    if strings:
        parts.append("strings=" + ",".join(strings[:4]))
    return "  ".join(parts)


# --------------------------------------------------------------------------
# XM / Sofia "DVRIP"  (also sold as Netsurveillance, XMEye)
# --------------------------------------------------------------------------
# 20-byte header: FF 00 00 00 | session:u32le | seq:u32le | total:u8 cur:u8 |
#                 msgid:u16le | payload_len:u32le
# msgid 1530 (0x05FA) is the LAN search request.


def dvrip_packet(msgid: int, payload: bytes = b"") -> bytes:
    return (
        b"\xff\x00\x00\x00"
        + (0).to_bytes(4, "little")
        + (0).to_bytes(4, "little")
        + b"\x00\x00"
        + msgid.to_bytes(2, "little")
        + len(payload).to_bytes(4, "little")
        + payload
    )


def decode_dvrip(data: bytes) -> str | None:
    if len(data) < 20 or data[0] != 0xFF:
        return None
    msgid = int.from_bytes(data[14:16], "little")
    length = int.from_bytes(data[16:20], "little")
    body = data[20 : 20 + length]
    desc = f"DVRIP/XM msgid={msgid} len={length}"
    text = body.decode("utf-8", "replace").strip("\x00").strip()
    if text:
        desc += f"  body={text[:300]}"
    return desc


# --------------------------------------------------------------------------
# Generic helpers
# --------------------------------------------------------------------------


def printable_runs(data: bytes, minimum: int = 4) -> list[str]:
    return [
        m.group().decode("ascii", "replace")
        for m in re.finditer(rb"[\x20-\x7e]{%d,}" % minimum, data)
    ]


def decode_generic(data: bytes) -> str:
    strings = printable_runs(data, minimum=4)
    desc = f"{len(data)} bytes, first16={data[:16].hex(' ')}"
    if strings:
        desc += "  strings=" + " | ".join(strings[:5])
    return desc


def hexdump(data: bytes, limit: int = 128) -> str:
    out = []
    chunk = data[:limit]
    for offset in range(0, len(chunk), 16):
        row = chunk[offset : offset + 16]
        hexpart = " ".join(f"{b:02x}" for b in row).ljust(47)
        asciipart = "".join(chr(b) if 0x20 <= b < 0x7F else "." for b in row)
        out.append(f"    {offset:04x}  {hexpart}  {asciipart}")
    if len(data) > limit:
        out.append(f"    ... {len(data) - limit} more bytes")
    return "\n".join(out)


# --------------------------------------------------------------------------
# Probe catalogue
# --------------------------------------------------------------------------


@dataclass
class Probe:
    family: str
    ports: tuple[int, ...]
    payload: bytes
    note: str
    verified: bool = True
    decoders: list[Callable[[bytes], str | None]] = field(default_factory=list)


PROBES: list[Probe] = [
    Probe(
        family="PPPP/PPCS LAN_SEARCH",
        ports=(32108, 32100, 32090, 10240),
        payload=bytes([PPPP_MAGIC, 0x30, 0x00, 0x00]),
        note="CS2 Network stack. Dominant in this hardware class. Reply starts 0xF1.",
        decoders=[decode_pppp],
    ),
    Probe(
        family="PPPP/PPCS QUERY_DID",
        ports=(32108,),
        payload=bytes([PPPP_MAGIC, 0x08, 0x00, 0x00]),
        note="Asks the device to state its own UID. Only answered by some builds.",
        decoders=[decode_pppp],
    ),
    Probe(
        family="XM/Sofia DVRIP search",
        ports=(34569,),
        payload=dvrip_packet(1530),
        note="XMEye / Netsurveillance family. Answers with a JSON device block.",
        decoders=[decode_dvrip],
    ),
    Probe(
        family="TUTK Kalay IOTC search",
        ports=(32761, 32760, 32764, 8000),
        payload=bytes([0x2C, 0x00, 0x00, 0x00]),
        note="Payload NOT verified against a real device — silence proves nothing.",
        verified=False,
    ),
    Probe(
        family="ASCII discovery probes",
        ports=(8600, 8800, 8899, 5000, 6000, 10008, 11000, 20188, 22600),
        payload=b"Gsearch\x00",
        note="Several vendor SDKs answer plaintext search words. Unverified.",
        verified=False,
    ),
    Probe(
        family="Generic JSON search",
        ports=(8600, 5000, 10008),
        payload=b'{"cmd":"search"}',
        note="Unverified. Cheap to try alongside the ASCII probe.",
        verified=False,
    ),
]

# Ports the PPPP magic is swept across in --wide mode. The magic byte is
# distinctive enough that a false positive is very unlikely, so casting a wide
# net costs nothing but time.
WIDE_PORTS = (
    list(range(32100, 32120))
    + [10240, 10241, 8000, 8080, 8600, 8800, 8899, 9000, 9999]
    + [5000, 5432, 6000, 7000, 10008, 11000, 20188, 22600, 34569, 39999]
)


# --------------------------------------------------------------------------
# Send / receive
# --------------------------------------------------------------------------


@dataclass
class Reply:
    src: tuple[str, int]
    data: bytes
    probe: Probe
    dest_port: int
    dest_kind: str


def run_probes(
    probes: list[Probe],
    targets: list[tuple[str, str]],
    wait: float,
    verbose: bool,
) -> list[Reply]:
    """Send every probe to every target, then collect replies for `wait` seconds.

    One socket per (probe, port, target) so a reply can be attributed to the exact
    packet that provoked it rather than guessed at from the source port.
    """
    registry: dict[socket.socket, tuple[Probe, int, str]] = {}
    sent = 0

    for probe in probes:
        for port in probe.ports:
            for addr, kind in targets:
                try:
                    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                    sock.setblocking(False)
                    sock.bind(("", 0))
                    sock.sendto(probe.payload, (addr, port))
                except OSError as exc:
                    if verbose:
                        print(f"  ! send failed {addr}:{port} ({probe.family}): {exc}")
                    continue
                registry[sock] = (probe, port, kind)
                sent += 1
                if verbose:
                    print(
                        f"  -> {addr:>15}:{port:<6} {probe.family:<28} "
                        f"{probe.payload[:12].hex(' ')}"
                    )

    print(f"\nSent {sent} probes. Listening {wait:.0f}s for replies...\n")

    replies: list[Reply] = []
    deadline = time.monotonic() + wait
    while time.monotonic() < deadline:
        remaining = max(0.0, deadline - time.monotonic())
        ready, _, _ = select.select(list(registry), [], [], min(0.5, remaining))
        for sock in ready:
            try:
                data, src = sock.recvfrom(65535)
            except OSError:
                continue
            if not data:
                continue
            probe, port, kind = registry[sock]
            replies.append(Reply(src, data, probe, port, kind))
            print(f"  <- REPLY from {src[0]}:{src[1]}  ({probe.family}, {kind})")

    for sock in registry:
        sock.close()
    return replies


def report(replies: list[Reply], probes: list[Probe]) -> int:
    print("\n" + "=" * 74)
    print("RESULTS")
    print("=" * 74)

    if not replies:
        print("\nNo replies.\n")
        verified = [p.family for p in probes if p.verified]
        unverified = [p.family for p in probes if not p.verified]
        if verified:
            print("Meaningful negatives (verified payloads, no answer):")
            for name in verified:
                print(f"  - {name}")
        if unverified:
            print("\nNot meaningful (payload unverified — absence of a reply here is")
            print("not evidence the service is absent):")
            for name in unverified:
                print(f"  - {name}")
        print(
            "\nBefore trusting the meaningful negatives, confirm the path works:\n"
            "  python tools/isolation_check.py --target <ip> --iface-ip <your ip>\n"
            "ARP resolving while unicast fails means AP client isolation, and\n"
            "under isolation none of these probes ever reached the camera.\n"
            "\nThen try the camera's own SoftAP in pairing mode — provisioning\n"
            "mode almost always exposes more local surface than station mode.\n"
        )
        return 1

    by_source: dict[tuple[str, int], list[Reply]] = {}
    for reply in replies:
        by_source.setdefault(reply.src, []).append(reply)

    for src, group in sorted(by_source.items()):
        print(f"\n{src[0]}:{src[1]}  —  {len(group)} reply/replies")
        print("-" * 74)
        for reply in group:
            print(f"  probe    : {reply.probe.family} (port {reply.dest_port}, {reply.dest_kind})")
            decoded = None
            for decoder in reply.probe.decoders:
                decoded = decoder(reply.data)
                if decoded:
                    break
            if not decoded:
                for decoder in (decode_pppp, decode_dvrip):
                    decoded = decoder(reply.data)
                    if decoded:
                        decoded += "   [matched by generic decoder]"
                        break
            print(f"  decoded  : {decoded or decode_generic(reply.data)}")
            print("  raw      :")
            print(hexdump(reply.data))

    if any(r.data[:1] == bytes([PPPP_MAGIC]) for r in replies):
        print("\n" + "*" * 74)
        print("A reply began with 0xF1 — that is the PPPP/PPCS magic byte.")
        print("Hypothesis H1 is confirmed. Record it in research/findings/ and")
        print("move on to reproducing the handshake in tools/poc_client.py.")
        print("*" * 74)

    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Probe for P2P camera LAN-discovery services.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--target", help="Camera unicast IP, e.g. 192.168.29.214")
    parser.add_argument(
        "--broadcast",
        help="Subnet broadcast, e.g. 192.168.29.255. Many devices answer only this.",
    )
    parser.add_argument(
        "--wide",
        action="store_true",
        help="Sweep the PPPP magic across a wide candidate port list.",
    )
    parser.add_argument(
        "--verified-only",
        action="store_true",
        help="Skip probes whose payload has not been confirmed against real hardware.",
    )
    parser.add_argument("--wait", type=float, default=6.0, help="Listen seconds (default 6)")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    if not args.target and not args.broadcast:
        parser.error("give at least one of --target or --broadcast")

    targets: list[tuple[str, str]] = []
    for value, kind in ((args.target, "unicast"), (args.broadcast, "broadcast")):
        if not value:
            continue
        try:
            ipaddress.IPv4Address(value)
        except ipaddress.AddressValueError:
            parser.error(f"not a valid IPv4 address: {value}")
        targets.append((value, kind))

    probes = list(PROBES)
    if args.verified_only:
        probes = [p for p in probes if p.verified]
    if args.wide:
        probes.append(
            Probe(
                family="PPPP LAN_SEARCH (wide sweep)",
                ports=tuple(sorted(set(WIDE_PORTS))),
                payload=bytes([PPPP_MAGIC, 0x30, 0x00, 0x00]),
                note="Same magic, non-standard ports.",
                decoders=[decode_pppp],
            )
        )

    print("=" * 74)
    print("P2P LAN discovery probe")
    print("=" * 74)
    for addr, kind in targets:
        print(f"  target: {addr} ({kind})")
    print(f"  probes: {len(probes)} families")
    print()
    for probe in probes:
        mark = " " if probe.verified else "!"
        ports = ",".join(str(p) for p in probe.ports[:8])
        if len(probe.ports) > 8:
            ports += f",... ({len(probe.ports)} total)"
        print(f" {mark} {probe.family:<30} udp/{ports}")
        print(f"     {probe.note}")
    print()

    replies = run_probes(probes, targets, args.wait, args.verbose)
    return report(replies, probes)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\ninterrupted")
        sys.exit(130)
