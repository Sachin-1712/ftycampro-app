#!/usr/bin/env python3
"""Proof-of-concept PPPP/PPCS client — phase 5 skeleton.

What is implemented: the transport framing. Packet encode/decode, LAN discovery,
the session handshake up to P2P_RDY, keepalives, and a DRW data-channel reader
that reassembles the stream and scans it for H.264 Annex-B frames.

What is NOT implemented, and cannot be until phase 3 produces a capture: the
*command* layer that rides inside DRW. That layer is vendor-specific — the login
blob, the "start stream" command id, the per-frame media header — and inventing
it would be fiction. The `send_command` hook and `FrameAssembler.parse_media_header`
are where captured bytes get turned into code.

    python poc_client.py --discover --broadcast 192.168.29.255
    python poc_client.py --target 192.168.29.214 --uid ABCD-123456-EFGHI --listen 30

Standard library only.
"""

from __future__ import annotations

import argparse
import binascii
import socket
import struct
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

MAGIC = 0xF1

MSG_HELLO = 0x00
MSG_QUERY_DID = 0x08
MSG_DEV_LGN = 0x10
MSG_P2P_REQ = 0x20
MSG_LAN_SEARCH = 0x30
MSG_LAN_NOTIFY = 0x31
MSG_PUNCH_TO = 0x40
MSG_PUNCH_PKT = 0x41
MSG_PUNCH_READY = 0x42
MSG_P2P_RDY = 0x50
MSG_DRW = 0x70
MSG_DRW_ACK = 0x71
MSG_ALIVE = 0xF0
MSG_ALIVE_ACK = 0xF1
MSG_CLOSE = 0xF8

TYPE_NAMES = {
    v: k
    for k, v in globals().items()
    if k.startswith("MSG_") and isinstance(v, int)
}

DEFAULT_PORT = 32108
H264_START = b"\x00\x00\x00\x01"


# --------------------------------------------------------------------------
# Framing
# --------------------------------------------------------------------------


@dataclass
class Packet:
    msg_type: int
    payload: bytes = b""

    def encode(self) -> bytes:
        return bytes([MAGIC, self.msg_type]) + struct.pack(">H", len(self.payload)) + self.payload

    @classmethod
    def decode(cls, data: bytes) -> "Packet | None":
        if len(data) < 4 or data[0] != MAGIC:
            return None
        length = struct.unpack(">H", data[2:4])[0]
        return cls(msg_type=data[1], payload=data[4 : 4 + length])

    @property
    def name(self) -> str:
        return TYPE_NAMES.get(self.msg_type, f"UNKNOWN_0x{self.msg_type:02x}")

    def __str__(self) -> str:
        return f"{self.name} len={len(self.payload)} {self.payload[:24].hex(' ')}"


def encode_uid(uid: str) -> bytes:
    """Pack a UID into the 20-byte DID structure: prefix[8] serial[4] check[8].

    Speculative until a capture confirms the exact layout — different builds of
    this SDK pack it differently. Verify against a real MSG_P2P_REQ before
    trusting it.
    """
    parts = uid.replace("_", "-").split("-")
    if len(parts) != 3:
        raise ValueError(f"expected PREFIX-SERIAL-CHECK, got {uid!r}")
    prefix, serial, check = parts
    return (
        prefix.encode("ascii").ljust(8, b"\x00")
        + struct.pack(">I", int(serial))
        + check.encode("ascii").ljust(8, b"\x00")
    )


# --------------------------------------------------------------------------
# Discovery
# --------------------------------------------------------------------------


@dataclass
class Device:
    addr: tuple[str, int]
    uid: str | None
    raw: bytes


def discover(broadcast: str, port: int, wait: float) -> list[Device]:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.settimeout(0.5)
    sock.bind(("", 0))

    probe = Packet(MSG_LAN_SEARCH).encode()
    print(f"-> LAN_SEARCH to {broadcast}:{port}  [{probe.hex(' ')}]")
    sock.sendto(probe, (broadcast, port))

    found: list[Device] = []
    deadline = time.monotonic() + wait
    while time.monotonic() < deadline:
        try:
            data, src = sock.recvfrom(2048)
        except socket.timeout:
            continue
        packet = Packet.decode(data)
        if packet is None:
            print(f"<- {src[0]}:{src[1]} non-PPPP reply: {data[:32].hex(' ')}")
            continue
        uid = extract_uid(packet.payload)
        print(f"<- {src[0]}:{src[1]}  {packet}" + (f"  UID={uid}" if uid else ""))
        found.append(Device(addr=src, uid=uid, raw=data))
    sock.close()
    return found


def extract_uid(payload: bytes) -> str | None:
    """Pull a printable UID out of a DID blob, tolerant of layout differences."""
    if len(payload) < 12:
        return None
    prefix = payload[:8].split(b"\x00")[0].decode("ascii", "ignore")
    if not prefix.isalnum() or not prefix:
        return None
    serial = struct.unpack(">I", payload[8:12])[0]
    check = payload[12:20].split(b"\x00")[0].decode("ascii", "ignore") if len(payload) >= 20 else ""
    return f"{prefix}-{serial:06d}-{check}" if check else f"{prefix}-{serial:06d}"


# --------------------------------------------------------------------------
# Session
# --------------------------------------------------------------------------


@dataclass
class FrameAssembler:
    """Reassembles DRW payloads and looks for media.

    Each DRW packet carries a channel id and a sequence number ahead of the
    application payload. The exact offsets vary by SDK build — the values below
    are the common case and MUST be checked against a capture before they are
    trusted. `pcap_triage.py --dump-flow N` prints what you need.
    """

    channel_offset: int = 0
    seq_offset: int = 1
    header_len: int = 4

    buffers: dict[int, bytearray] = field(default_factory=dict)
    seen_channels: set[int] = field(default_factory=set)

    def feed(self, payload: bytes) -> None:
        if len(payload) <= self.header_len:
            return
        channel = payload[self.channel_offset]
        self.seen_channels.add(channel)
        self.buffers.setdefault(channel, bytearray()).extend(payload[self.header_len :])

    def parse_media_header(self, channel: int) -> None:
        """TODO(phase 4): decode the per-frame header once a capture reveals it.

        Expect something like: magic, frame type (I/P), timestamp, payload length.
        Until then the raw channel bytes are dumped and scanned for start codes,
        which is enough to prove the media is reachable and in the clear.
        """
        raise NotImplementedError("needs a capture — see docs/INVESTIGATION-CHECKLIST.md D4")

    def report(self, outdir: Path) -> None:
        if not self.buffers:
            print("\nNo DRW data received.")
            return
        outdir.mkdir(parents=True, exist_ok=True)
        print(f"\nChannels seen: {sorted(self.seen_channels)}")
        for channel, buf in sorted(self.buffers.items()):
            path = outdir / f"channel-{channel:02d}.bin"
            path.write_bytes(bytes(buf))
            starts = bytes(buf).count(H264_START)
            print(f"  channel {channel}: {len(buf)} bytes -> {path}")
            if starts:
                print(f"    >> {starts} H.264 Annex-B start codes. Media is in the clear.")
                h264 = outdir / f"channel-{channel:02d}.h264"
                index = bytes(buf).find(H264_START)
                h264.write_bytes(bytes(buf)[index:])
                print(f"    >> wrote {h264} — try:  ffplay {h264}")
            else:
                print("    no start codes: either not video, or encrypted (see H5)")


class Session:
    def __init__(self, target: str, port: int, uid: str | None, verbose: bool):
        self.target = target
        self.port = port
        self.uid = uid
        self.verbose = verbose
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.settimeout(0.5)
        self.sock.bind(("", 0))
        self.assembler = FrameAssembler()
        self.ready = False

    def send(self, packet: Packet) -> None:
        if self.verbose:
            print(f"-> {packet}")
        self.sock.sendto(packet.encode(), (self.target, self.port))

    def handshake(self) -> bool:
        """Attempt to reach P2P_RDY. Which of these the device answers is itself data."""
        print(f"\nHandshake with {self.target}:{self.port}")

        self.send(Packet(MSG_LAN_SEARCH))
        if self._await({MSG_PUNCH_PKT, MSG_LAN_NOTIFY, MSG_P2P_RDY}, 2.0):
            print("  device answered LAN_SEARCH")

        self.send(Packet(MSG_QUERY_DID))
        self._await({0x09}, 1.5)

        if self.uid:
            try:
                did = encode_uid(self.uid)
            except ValueError as exc:
                print(f"  ! {exc}")
                return self.ready
            print(f"  P2P_REQ with DID {did.hex(' ')}")
            self.send(Packet(MSG_P2P_REQ, did))
            self._await({MSG_P2P_RDY, MSG_PUNCH_TO, MSG_PUNCH_READY}, 3.0)

        if not self.ready:
            print(
                "\n  No P2P_RDY. Possible reasons, in order of likelihood:\n"
                "    - the DID packing above is wrong for this SDK build\n"
                "    - the device requires a cloud-mediated rendezvous first\n"
                "    - it is not PPPP at all\n"
                "  Capture the vendor app doing this exchange and compare bytes."
            )
        return self.ready

    def _await(self, wanted: set[int], timeout: float) -> bool:
        deadline = time.monotonic() + timeout
        got = False
        while time.monotonic() < deadline:
            try:
                data, _ = self.sock.recvfrom(65535)
            except socket.timeout:
                continue
            packet = Packet.decode(data)
            if packet is None:
                print(f"<- non-PPPP: {data[:32].hex(' ')}")
                continue
            if self.verbose or packet.msg_type != MSG_DRW:
                print(f"<- {packet}")
            self._dispatch(packet)
            if packet.msg_type in wanted:
                got = True
        return got

    def _dispatch(self, packet: Packet) -> None:
        if packet.msg_type == MSG_P2P_RDY:
            self.ready = True
            print("  >> P2P_RDY — session established")
        elif packet.msg_type == MSG_ALIVE:
            self.send(Packet(MSG_ALIVE_ACK))
        elif packet.msg_type == MSG_DRW:
            self.assembler.feed(packet.payload)
            self.send(Packet(MSG_DRW_ACK, packet.payload[:4]))

    def send_command(self, blob: bytes, channel: int = 0) -> None:
        """Send an application command inside a DRW packet.

        The *contents* of `blob` — the login struct, the start-stream command id —
        are vendor-specific and come from the capture. This method only handles
        the envelope.
        """
        header = bytes([channel, 0x00, 0x00, 0x00])
        self.send(Packet(MSG_DRW, header + blob))

    def listen(self, seconds: float, outdir: Path) -> None:
        print(f"\nListening {seconds:.0f}s for DRW data...")
        deadline = time.monotonic() + seconds
        last_keepalive = 0.0
        while time.monotonic() < deadline:
            now = time.monotonic()
            if now - last_keepalive > 5.0:
                self.send(Packet(MSG_ALIVE))
                last_keepalive = now
            try:
                data, _ = self.sock.recvfrom(65535)
            except socket.timeout:
                continue
            packet = Packet.decode(data)
            if packet:
                self._dispatch(packet)
        self.assembler.report(outdir)

    def close(self) -> None:
        try:
            self.send(Packet(MSG_CLOSE))
        except OSError:
            pass
        self.sock.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", help="Camera IP")
    parser.add_argument("--broadcast", help="Subnet broadcast, for --discover")
    parser.add_argument("--uid", help="Device UID, e.g. ABCD-123456-EFGHI")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--discover", action="store_true")
    parser.add_argument("--listen", type=float, default=0.0, help="Seconds to collect DRW data")
    parser.add_argument("--out", type=Path, default=Path("research/captures/poc-out"))
    parser.add_argument("--send-hex", help="Raw hex blob to send as a DRW command")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    if args.discover:
        if not args.broadcast:
            parser.error("--discover needs --broadcast")
        devices = discover(args.broadcast, args.port, wait=5.0)
        print(f"\n{len(devices)} device(s) found")
        for device in devices:
            print(f"  {device.addr[0]}:{device.addr[1]}  uid={device.uid or '?'}")
        if not devices:
            print(
                "\nNothing answered. Before concluding it is not PPPP, run\n"
                "  python tools/p2p_probe.py --target <ip> --broadcast <bcast> --wide\n"
                "which sweeps the same magic across non-standard ports."
            )
        return 0 if devices else 1

    if not args.target:
        parser.error("give --target, or --discover with --broadcast")

    session = Session(args.target, args.port, args.uid, args.verbose)
    try:
        session.handshake()
        if args.send_hex:
            session.send_command(binascii.unhexlify(args.send_hex.replace(" ", "")))
        if args.listen > 0:
            session.listen(args.listen, args.out)
    finally:
        session.close()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\ninterrupted")
        sys.exit(130)
