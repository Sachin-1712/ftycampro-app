#!/usr/bin/env python3
"""Decide whether the camera is actually reachable, before trusting any scan.

The question this answers is *not* "is a port open". It is "do my packets reach
the device at all, and does its IP stack answer". Those are different questions
and the second one has to be settled first, because a port scan run over a dead
path measures the path, not the target.

The discriminator is TCP RST. Connecting to a port with no listener gives one of
two outcomes:

    ECONNREFUSED  the host sent a RST -> your packet arrived, its stack is alive
    timeout       nothing came back   -> blocked upstream, dropped, or no host

ICMP is useless for this: embedded camera firmware very commonly disables echo
reply while the TCP stack answers normally, so a failed ping means almost
nothing on its own.

    python isolation_check.py --target 192.168.29.214 --iface-ip 192.168.29.156

Optionally pass --control <ip> naming another wireless client you know is up.
That distinguishes AP client isolation (which blocks station-to-station unicast
but not station-to-gateway) from a target-specific problem. The gateway is not a
valid control for this, because isolation policies deliberately exempt it.
"""

from __future__ import annotations

import argparse
import random
import socket
import subprocess
import sys
from dataclasses import dataclass

# Ports chosen to be almost certainly unused, so a RST is the expected answer
# from any healthy stack. Randomised high ports avoid accidentally hitting a
# real service and getting a misleading "open".
def unlikely_ports(count: int = 6) -> list[int]:
    rng = random.Random(0xC0FFEE)
    return [rng.randint(40000, 61000) for _ in range(count)]


@dataclass
class ProbeResult:
    host: str
    refused: int = 0      # RST received -> definitely reachable
    accepted: int = 0     # unexpected listener -> definitely reachable
    timed_out: int = 0    # silence -> no conclusion from this probe alone
    icmp: bool = False    # echo reply -> definitely reachable
    errors: list[str] = None

    def __post_init__(self) -> None:
        if self.errors is None:
            self.errors = []

    @property
    def stack_responded(self) -> bool:
        """Did anything at all come back from this host?

        Any one positive is sufficient. An earlier version of this script keyed
        the verdict on TCP RST alone and reported client isolation for a host that
        was answering pings — silent TCP is what most embedded stacks and every
        modern Android device do with a closed port, so its absence says nothing
        about reachability once something else has answered.
        """
        return self.refused > 0 or self.accepted > 0 or self.icmp

    @property
    def tcp_responded(self) -> bool:
        return self.refused > 0 or self.accepted > 0

    def summary(self) -> str:
        return (
            f"RST/refused={self.refused}  accepted={self.accepted}  "
            f"silent={self.timed_out}"
        )


def tcp_probe(host: str, ports: list[int], timeout: float = 1.5) -> ProbeResult:
    result = ProbeResult(host=host)
    for port in ports:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        try:
            sock.connect((host, port))
            result.accepted += 1
        except ConnectionRefusedError:
            result.refused += 1
        except (socket.timeout, TimeoutError):
            result.timed_out += 1
        except OSError as exc:
            # Windows raises WSAECONNREFUSED as OSError(10061) in some paths.
            if getattr(exc, "winerror", None) == 10061 or exc.errno == 111:
                result.refused += 1
            elif getattr(exc, "winerror", None) == 10060:
                result.timed_out += 1
            else:
                result.errors.append(f"{port}: {exc}")
        finally:
            sock.close()
    return result


def icmp_ping(host: str, count: int = 3) -> bool:
    """Best-effort ping via the system tool. Informational only."""
    if sys.platform.startswith("win"):
        cmd = ["ping", "-n", str(count), "-w", "1000", host]
    else:
        cmd = ["ping", "-c", str(count), "-W", "1", host]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return False
    if proc.returncode != 0:
        return False
    # Windows `ping` exits 0 even when every reply is "Destination host
    # unreachable", so check the text as well.
    out = proc.stdout.lower()
    return "ttl=" in out or "bytes from" in out


def arp_entry(host: str) -> str | None:
    """Look up the target in the local ARP cache."""
    cmd = ["arp", "-a", host] if sys.platform.startswith("win") else ["arp", "-n", host]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return None
    for line in proc.stdout.splitlines():
        if host in line and ("-" in line or ":" in line):
            fields = line.split()
            for field in fields:
                if len(field) == 17 and (field.count("-") == 5 or field.count(":") == 5):
                    return field
    return None


def describe(name: str, host: str) -> ProbeResult:
    print(f"\n{name}: {host}")
    print("-" * 62)

    mac = arp_entry(host)
    print(f"  ARP cache      : {mac or 'no entry'}")

    pinged = icmp_ping(host)
    print(f"  ICMP echo      : {'reply' if pinged else 'no reply'}")

    ports = unlikely_ports()
    print(f"  TCP probe      : ports {', '.join(str(p) for p in ports)}")
    result = tcp_probe(host, ports)
    result.icmp = pinged
    print(f"                   {result.summary()}")
    for err in result.errors:
        print(f"                   ! {err}")

    if result.tcp_responded:
        print("  -> REACHABLE. TCP RST received; packets arrive and the stack answers.")
    elif pinged:
        print("  -> REACHABLE. ICMP answered. TCP silence just means the host drops")
        print("     packets to closed ports instead of resetting — normal for")
        print("     embedded firmware and for Android.")
    elif mac:
        print("  -> AMBIGUOUS. ARP resolves but nothing answers unicast.")
    else:
        print("  -> NO RESPONSE, and no ARP entry: the host is probably not on the")
        print("     network at all.")
    return result


def verdict(target: ProbeResult, control: ProbeResult | None, gateway_ok: bool) -> int:
    print("\n" + "=" * 62)
    print("VERDICT")
    print("=" * 62)

    if not gateway_ok:
        print(
            "\nThe gateway did not respond either. Fix basic connectivity before\n"
            "drawing any conclusion about the camera — right now nothing on this\n"
            "host can be trusted to reach anything."
        )
        return 2

    if target.stack_responded:
        if target.tcp_responded:
            print(
                "\nThe camera's TCP stack sent RST. Unicast packets reach the device\n"
                "and it answers. There is no client isolation on this path.\n"
                "\nSo a port scan finding zero open TCP ports is a real result: the\n"
                "device genuinely has no TCP listeners."
            )
        else:
            print(
                "\nThe camera answered ICMP, so the path works and there is no client\n"
                "isolation. TCP gave no RST, which is not a contradiction: most\n"
                "embedded stacks silently drop packets to closed ports rather than\n"
                "resetting. It does mean a TCP port scan of this host will report\n"
                "everything as 'filtered' and will tell you nothing.\n"
                "\nDon't spend more time on TCP. A device with a live IP stack and no\n"
                "TCP surface, paired with a UID-based app, is a UDP P2P camera."
            )
        print(
            "\nNext:  python tools/p2p_probe.py --target <ip> --broadcast <bcast>\n"
            "A reply beginning 0xF1 confirms the CS2 Network PPPP stack and usually\n"
            "carries the device UID in the clear."
        )
        return 0

    if control is None:
        print(
            "\nInconclusive. The camera did not answer, but with no control host\n"
            "there is no way to tell 'the camera is silent' from 'nothing on this\n"
            "network can reach anything'.\n"
            "\nRe-run with --control <ip of another device on the same SSID that you\n"
            "know is powered on>. Do not use the gateway: isolation policies exempt\n"
            "it, so a reachable gateway proves nothing about station-to-station\n"
            "traffic."
        )
        return 1

    if control.stack_responded:
        print(
            "\nThe control host answered but the camera did not. The path between\n"
            "stations works, so this is specific to the camera: either its stack\n"
            "silently drops everything, or it is asleep.\n"
            "\nTest the sleep theory: open the vendor app, start a live view, and\n"
            "re-run this immediately. If the camera becomes reachable only while\n"
            "the app is streaming, that is hypothesis H6 and it is a significant\n"
            "constraint — record it in research/findings/."
        )
        return 1

    print(
        "\nNeither the camera nor the control host answered anything — no ICMP, no\n"
        "TCP RST — while ARP resolves for them. That is AP / client isolation.\n"
        "\nEvery scan result collected so far is void — those packets never\n"
        "reached the camera. Before continuing:\n"
        "  - turn off 'AP isolation' / 'client isolation' / 'guest isolation'\n"
        "    in the router admin UI, or\n"
        "  - put the camera and this host on a hotspot you control, which has\n"
        "    the bonus of letting you capture all of their traffic directly.\n"
        "\nThen re-run the full TCP scan with -Pn and start again."
    )
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Prove the measurement path works before interpreting scan results.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--target", required=True, help="Camera IP")
    parser.add_argument("--iface-ip", help="This host's IP, used to infer the gateway")
    parser.add_argument("--gateway", help="Gateway IP (default: .1 of the target's /24)")
    parser.add_argument(
        "--control",
        help="Another station on the same SSID known to be up. Not the gateway.",
    )
    args = parser.parse_args()

    gateway = args.gateway or ".".join(args.target.split(".")[:3] + ["1"])

    print("=" * 62)
    print("Reachability and isolation check")
    print("=" * 62)
    print(f"  target  : {args.target}")
    print(f"  gateway : {gateway}")
    print(f"  control : {args.control or '(none given — result may be inconclusive)'}")

    gw_result = describe("Gateway (sanity check)", gateway)
    gateway_ok = gw_result.stack_responded or icmp_ping(gateway, count=1)

    target_result = describe("Camera", args.target)
    control_result = describe("Control station", args.control) if args.control else None

    return verdict(target_result, control_result, gateway_ok)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\ninterrupted")
        sys.exit(130)
