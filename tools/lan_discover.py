#!/usr/bin/env python3
"""Standard-protocol discovery sweep: mDNS, SSDP and ONVIF WS-Discovery.

Low expectation of a hit given the port scan, but the payoff is asymmetric. A
single ONVIF or RTSP response would collapse this whole project into "point
Media3 at a URL", so ninety seconds spent here is cheap insurance.

    python lan_discover.py --iface-ip 192.168.29.156

Standard library only.
"""

from __future__ import annotations

import argparse
import re
import select
import socket
import struct
import sys
import time
from dataclasses import dataclass

SSDP_ADDR = ("239.255.255.250", 1900)
WSD_ADDR = ("239.255.255.250", 3702)
MDNS_ADDR = ("224.0.0.251", 5353)

SSDP_MSEARCH = (
    "M-SEARCH * HTTP/1.1\r\n"
    "HOST: 239.255.255.250:1900\r\n"
    'MAN: "ssdp:discover"\r\n'
    "MX: 2\r\n"
    "ST: ssdp:all\r\n"
    "\r\n"
).encode()

# ONVIF devices answer a WS-Discovery Probe for the NetworkVideoTransmitter type.
WSD_PROBE = (
    '<?xml version="1.0" encoding="UTF-8"?>'
    '<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" '
    'xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" '
    'xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" '
    'xmlns:dn="http://www.onvif.org/ver10/network/wsdl">'
    "<e:Header>"
    "<w:MessageID>uuid:2b0ddc0a-4f1e-4f2a-9a1f-000000000001</w:MessageID>"
    "<w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
    "<w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>"
    "</e:Header>"
    "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body>"
    "</e:Envelope>"
).encode()

MDNS_SERVICES = [
    "_rtsp._tcp.local",
    "_onvif._tcp.local",
    "_http._tcp.local",
    "_axis-video._tcp.local",
    "_services._dns-sd._udp.local",
]


def mdns_query(names: list[str]) -> bytes:
    """Build a single mDNS query packet asking for PTR records."""
    header = struct.pack(">HHHHHH", 0, 0, len(names), 0, 0, 0)
    body = b""
    for name in names:
        for label in name.split("."):
            body += bytes([len(label)]) + label.encode("ascii")
        body += b"\x00" + struct.pack(">HH", 12, 1)  # QTYPE=PTR, QCLASS=IN
    return header + body


@dataclass
class Hit:
    protocol: str
    src: tuple[str, int]
    data: bytes


def send_and_listen(
    packets: list[tuple[str, bytes, tuple[str, int]]],
    iface_ip: str | None,
    wait: float,
) -> list[Hit]:
    socks: dict[socket.socket, str] = {}

    for protocol, payload, addr in packets:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
            if iface_ip:
                sock.setsockopt(
                    socket.IPPROTO_IP,
                    socket.IP_MULTICAST_IF,
                    socket.inet_aton(iface_ip),
                )
            sock.setblocking(False)
            sock.bind((iface_ip or "", 0))
            sock.sendto(payload, addr)
        except OSError as exc:
            print(f"  ! {protocol}: send failed ({exc})")
            continue
        socks[sock] = protocol
        print(f"  -> {protocol:<16} {addr[0]}:{addr[1]}  ({len(payload)} bytes)")

    hits: list[Hit] = []
    deadline = time.monotonic() + wait
    while time.monotonic() < deadline:
        remaining = max(0.0, deadline - time.monotonic())
        ready, _, _ = select.select(list(socks), [], [], min(0.5, remaining))
        for sock in ready:
            try:
                data, src = sock.recvfrom(65535)
            except OSError:
                continue
            if data:
                hits.append(Hit(socks[sock], src, data))
                print(f"  <- {socks[sock]:<16} reply from {src[0]}:{src[1]}")

    for sock in socks:
        sock.close()
    return hits


STREAM_URL_RE = re.compile(rb"(rtsp|rtmp|http|https)://[\x21-\x7e]{4,120}")


def report(hits: list[Hit], target: str | None) -> int:
    print("\n" + "=" * 70)
    print("RESULTS")
    print("=" * 70)

    if not hits:
        print(
            "\nNo responses to mDNS, SSDP or WS-Discovery.\n\n"
            "Consistent with a device that speaks only a proprietary UDP protocol.\n"
            "It does not rule out a local protocol — it rules out the standard ones.\n"
            "Next: tools/p2p_probe.py, and the camera's SoftAP in pairing mode.\n"
        )
        return 1

    from_target = [h for h in hits if target and h.src[0] == target]
    if target:
        print(
            f"\nReplies from the camera ({target}): {len(from_target)}"
            + ("  <-- worth reading closely" if from_target else "")
        )

    for hit in hits:
        marker = "  *** CAMERA ***" if target and hit.src[0] == target else ""
        print(f"\n{hit.protocol} from {hit.src[0]}:{hit.src[1]}{marker}")
        print("-" * 70)
        text = hit.data.decode("utf-8", "replace")
        printable = "".join(c if c.isprintable() or c in "\r\n\t" else "." for c in text)
        print(printable[:1200])
        for match in set(STREAM_URL_RE.findall(hit.data)):
            print(f"  >> stream URL candidate: {match.decode('ascii', 'replace')}")

    urls = {m for hit in hits for m in STREAM_URL_RE.findall(hit.data)}
    if urls:
        print("\n" + "*" * 70)
        print("Stream URLs were advertised. Try them with ffplay before writing any")
        print("protocol code — if one works, most of this project is unnecessary.")
        print("*" * 70)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--iface-ip", help="Bind to this local IP (multicast needs it)")
    parser.add_argument("--target", help="Camera IP, to highlight its replies")
    parser.add_argument("--wait", type=float, default=6.0)
    args = parser.parse_args()

    print("=" * 70)
    print("Standard discovery sweep")
    print("=" * 70)
    print(f"  interface: {args.iface_ip or '(default)'}\n")

    packets = [
        ("SSDP", SSDP_MSEARCH, SSDP_ADDR),
        ("WS-Discovery", WSD_PROBE, WSD_ADDR),
        ("mDNS", mdns_query(MDNS_SERVICES), MDNS_ADDR),
    ]
    hits = send_and_listen(packets, args.iface_ip, args.wait)
    return report(hits, args.target)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\ninterrupted")
        sys.exit(130)
