#!/usr/bin/env python3
"""Summarise a capture: who talks to whom, how much, and what the payloads look like.

The point is to answer three questions fast, before anyone opens Wireshark:

  1. Which flow is the video?      -> the one with the bytes
  2. Is the payload encrypted?     -> entropy, plus a look for H.264 start codes
  3. Where is the protocol header? -> the byte prefix that repeats on every packet

    python pcap_triage.py research/captures/d1-local-live.pcapng
    python pcap_triage.py cap.pcapng --peer 192.168.29.214 --dump-flow 3

Needs scapy (pip install -r tools/requirements.txt).
"""

from __future__ import annotations

import argparse
import math
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

try:
    from scapy.all import IP, TCP, UDP, rdpcap  # type: ignore
except ImportError:
    print("error: scapy not installed.  pip install -r tools/requirements.txt", file=sys.stderr)
    sys.exit(3)

H264_START = b"\x00\x00\x00\x01"
H264_START_SHORT = b"\x00\x00\x01"


def shannon_entropy(data: bytes) -> float:
    """Bits per byte. ~8.0 means encrypted or already compressed."""
    if not data:
        return 0.0
    counts = Counter(data)
    total = len(data)
    return -sum((c / total) * math.log2(c / total) for c in counts.values())


@dataclass
class Flow:
    proto: str
    endpoints: tuple[tuple[str, int], tuple[str, int]]
    packets: int = 0
    payload_bytes: int = 0
    sizes: Counter = field(default_factory=Counter)
    samples: list[bytes] = field(default_factory=list)
    first_time: float = 0.0
    last_time: float = 0.0

    @property
    def label(self) -> str:
        (a_ip, a_port), (b_ip, b_port) = self.endpoints
        return f"{self.proto} {a_ip}:{a_port} <-> {b_ip}:{b_port}"

    @property
    def duration(self) -> float:
        return max(0.0, self.last_time - self.first_time)

    @property
    def bitrate_kbps(self) -> float:
        return (self.payload_bytes * 8 / 1000 / self.duration) if self.duration > 0.5 else 0.0

    def entropy(self) -> float:
        blob = b"".join(self.samples)[:200_000]
        return shannon_entropy(blob)

    def common_prefix(self) -> bytes:
        """Longest byte prefix shared by every sampled packet — the header, if any."""
        if len(self.samples) < 4:
            return b""
        shortest = min(len(s) for s in self.samples)
        prefix = bytearray()
        for i in range(min(shortest, 32)):
            byte = self.samples[0][i]
            if all(s[i] == byte for s in self.samples):
                prefix.append(byte)
            else:
                break
        return bytes(prefix)

    def stable_offsets(self) -> list[int]:
        """Offsets in the first 24 bytes where the value rarely changes."""
        if len(self.samples) < 8:
            return []
        shortest = min(len(s) for s in self.samples)
        result = []
        for i in range(min(shortest, 24)):
            distinct = len({s[i] for s in self.samples})
            if distinct <= 3:
                result.append(i)
        return result

    def has_h264(self) -> bool:
        return any(H264_START in s or s.startswith(H264_START_SHORT) for s in self.samples)


def flow_key(pkt) -> tuple[str, tuple, tuple] | None:
    if IP not in pkt:
        return None
    ip = pkt[IP]
    if UDP in pkt:
        proto, layer = "UDP", pkt[UDP]
    elif TCP in pkt:
        proto, layer = "TCP", pkt[TCP]
    else:
        return None
    a = (ip.src, int(layer.sport))
    b = (ip.dst, int(layer.dport))
    return (proto, *sorted([a, b]))


def build_flows(packets, max_samples: int) -> dict[tuple, Flow]:
    flows: dict[tuple, Flow] = {}
    for pkt in packets:
        key = flow_key(pkt)
        if key is None:
            continue
        proto = key[0]
        flow = flows.get(key)
        if flow is None:
            flow = Flow(proto=proto, endpoints=(key[1], key[2]))
            flow.first_time = float(pkt.time)
            flows[key] = flow

        payload = bytes(pkt[UDP].payload if proto == "UDP" else pkt[TCP].payload)
        flow.packets += 1
        flow.payload_bytes += len(payload)
        flow.sizes[len(payload)] += 1
        flow.last_time = float(pkt.time)
        if payload and len(flow.samples) < max_samples:
            flow.samples.append(payload)
    return flows


def hexdump(data: bytes, limit: int = 96) -> str:
    out = []
    chunk = data[:limit]
    for offset in range(0, len(chunk), 16):
        row = chunk[offset : offset + 16]
        hexpart = " ".join(f"{b:02x}" for b in row).ljust(47)
        ascii_part = "".join(chr(b) if 0x20 <= b < 0x7F else "." for b in row)
        out.append(f"      {offset:04x}  {hexpart}  {ascii_part}")
    return "\n".join(out)


def render(flows: dict[tuple, Flow], peer: str | None, top: int, dump: int | None) -> None:
    line = "=" * 76
    ranked = sorted(flows.values(), key=lambda f: f.payload_bytes, reverse=True)

    print(line)
    print("PCAP TRIAGE")
    print(line)
    total_bytes = sum(f.payload_bytes for f in ranked)
    total_packets = sum(f.packets for f in ranked)
    print(f"  flows   : {len(ranked)}")
    print(f"  packets : {total_packets}")
    print(f"  payload : {total_bytes/1024:.1f} KiB")

    udp = sum(f.payload_bytes for f in ranked if f.proto == "UDP")
    tcp = sum(f.payload_bytes for f in ranked if f.proto == "TCP")
    if total_bytes:
        print(f"  split   : UDP {100*udp/total_bytes:.1f}%   TCP {100*tcp/total_bytes:.1f}%")
        if udp > tcp * 4:
            print(
                "\n  >> Overwhelmingly UDP. Consistent with a P2P camera SDK rather\n"
                "     than anything HTTP-based."
            )

    print("\n" + line)
    print(f"TOP {top} FLOWS BY VOLUME")
    print(line)

    for index, flow in enumerate(ranked[:top]):
        marker = ""
        if peer and peer in (flow.endpoints[0][0], flow.endpoints[1][0]):
            marker = "   <-- camera"
        print(f"\n[{index}] {flow.label}{marker}")
        print(
            f"     {flow.packets} pkts   {flow.payload_bytes/1024:.1f} KiB   "
            f"{flow.duration:.1f}s   {flow.bitrate_kbps:.0f} kbps"
        )

        sizes = flow.sizes.most_common(4)
        print("     sizes: " + ", ".join(f"{s}B x{c}" for s, c in sizes))

        entropy = flow.entropy()
        if entropy >= 7.5:
            verdict = "encrypted or compressed media"
        elif entropy >= 6.0:
            verdict = "mixed — likely media with cleartext headers"
        elif entropy > 0:
            verdict = "structured / low-entropy — control or text"
        else:
            verdict = "no payload"
        print(f"     entropy: {entropy:.2f} bits/byte  ({verdict})")

        prefix = flow.common_prefix()
        if prefix:
            print(f"     constant prefix on every packet: {prefix.hex(' ')}")
            if prefix[0] == 0xF1:
                print("       >> 0xF1 is the PPPP/PPCS magic byte. Hypothesis H1 confirmed.")
            elif prefix[0] == 0xFF:
                print("       >> 0xFF prefix matches the XM/Sofia DVRIP header.")
            else:
                print("       >> This is the protocol header. Reverse it from here.")

        offsets = flow.stable_offsets()
        if offsets and not prefix:
            print(f"     near-constant byte offsets: {offsets}")
            print("       >> Header fields live at these positions.")

        if flow.has_h264():
            print("     >> H.264 Annex-B start codes present — media is in the CLEAR.")
            print("        Strip the framing and Media3 can play it directly.")

        if index == dump and flow.samples:
            print("\n     first 3 payloads:")
            for sample in flow.samples[:3]:
                print(hexdump(sample))
                print()

    if peer:
        peer_flows = [
            f for f in ranked if peer in (f.endpoints[0][0], f.endpoints[1][0])
        ]
        print("\n" + line)
        print(f"FLOWS INVOLVING {peer}")
        print(line)
        if not peer_flows:
            print(
                f"\n  None. Nothing in this capture talks to {peer} directly.\n"
                "  If this was meant to be the local-Wi-Fi scenario, either the\n"
                "  capture point cannot see station-to-station traffic (very likely\n"
                "  with a VPN-based capture or a third-host sniff), or the app is\n"
                "  routing through the cloud even on the LAN.\n"
                "  Re-capture from a hotspot you control — see docs/SETUP.md 4c."
            )
        else:
            for flow in peer_flows:
                print(f"  {flow.label}  {flow.payload_bytes/1024:.1f} KiB")

    print("\n" + line)
    print("EXTERNAL ENDPOINTS  (candidate cloud infrastructure)")
    print(line)
    external: dict[str, int] = defaultdict(int)
    for flow in ranked:
        for ip, _ in flow.endpoints:
            if not ip.startswith(("192.168.", "10.", "172.16.", "127.")):
                external[ip] += flow.payload_bytes
    if external:
        for ip, byte_count in sorted(external.items(), key=lambda kv: -kv[1])[:20]:
            print(f"  {byte_count/1024:>10.1f} KiB  {ip}")
        print(
            "\n  Cross-reference against the hostnames in the APK signature report.\n"
            "  Agreement between a static string and observed traffic upgrades a\n"
            "  candidate endpoint to a confirmed one."
        )
    else:
        print("  none — all traffic stayed on the LAN, which is the good outcome")

    print()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pcap", type=Path)
    parser.add_argument("--peer", help="Camera IP, to highlight and isolate its flows")
    parser.add_argument("--top", type=int, default=12)
    parser.add_argument(
        "--dump-flow", type=int, help="Hexdump the first payloads of flow N from the list"
    )
    parser.add_argument("--max-samples", type=int, default=400)
    args = parser.parse_args()

    if not args.pcap.is_file():
        print(f"error: no such file: {args.pcap}", file=sys.stderr)
        return 2

    print(f"reading {args.pcap} ...", file=sys.stderr)
    packets = rdpcap(str(args.pcap))
    flows = build_flows(packets, args.max_samples)
    if not flows:
        print("No IP flows found in this capture.", file=sys.stderr)
        return 1
    render(flows, args.peer, args.top, args.dump_flow)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\ninterrupted")
        sys.exit(130)
