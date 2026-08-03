#!/usr/bin/env python3
"""Score a decompiled APK tree against known camera P2P SDK fingerprints.

Run this before reading any decompiled source by hand. A strong hit here names
the SDK outright, and these SDKs are well enough documented that identifying one
can save the entire traffic-capture phase.

    python apk_signatures.py research/01-apk-analysis

Expects the layout produced by apk_analyze.sh:

    <root>/jadx-out/       decompiled Java
    <root>/apktool-out/    resources, smali, manifest
    <root>/unzipped/       raw APK contents including lib/*/*.so

Standard library only.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

TEXT_SUFFIXES = {".java", ".smali", ".xml", ".json", ".txt", ".kt", ".properties", ".cfg"}
BINARY_SUFFIXES = {".so", ".dex", ".bin", ".dat"}
MAX_FILE_BYTES = 12 * 1024 * 1024


@dataclass
class Signature:
    """A fingerprint for one SDK family."""

    family: str
    weight: int
    libraries: tuple[str, ...] = ()
    symbols: tuple[str, ...] = ()
    classes: tuple[str, ...] = ()
    notes: str = ""


SIGNATURES: list[Signature] = [
    Signature(
        family="CS2 Network PPPP / PPCS / iLnkP2P",
        weight=10,
        libraries=("libPPCS_API", "libpppp", "libvdp", "libp2p_api", "libilnkp2p", "libIOTCare"),
        symbols=(
            "PPPP_Initialize",
            "PPPP_Connect",
            "PPPP_Write",
            "PPPP_Read",
            "PPPP_LanSearch",
            "PPCS_Initialize",
            "PPCS_Connect",
            "PPCS_LanSearch",
            "PPCS_Write",
        ),
        classes=("com.p2p", "PPPP_APIs", "PPCS_APIs", "St_PPCS_Session", "P2PClient"),
        notes="UDP only, 0xF1 magic, LAN search on 32108. Best case for this project.",
    ),
    Signature(
        family="TUTK Kalay (IOTC / AVAPI)",
        weight=10,
        libraries=("libIOTCAPIs", "libAVAPIs", "libTUTKGlobalAPIs", "libRDTAPIs"),
        symbols=(
            "IOTC_Initialize",
            "IOTC_Initialize2",
            "IOTC_Connect_ByUID",
            "IOTC_Get_SessionID",
            "avClientStart",
            "avClientStart2",
            "avSendIOCtrl",
            "avRecvFrameData",
            "TUTK_SDK_Set_License_Key",
        ),
        classes=("com.tutk.IOTC", "com.tutk.p2p", "IOTCAPIs", "AVAPIs"),
        notes="20-char UID, UDP 8000-8900. Licence key is a long base64 constant.",
    ),
    Signature(
        family="Gwelltimes / Yoosee",
        weight=8,
        libraries=("libgwp2p", "libclient", "libgwsdk"),
        symbols=("gw_p2p_init", "GW_Init", "gwInitP2P"),
        classes=("com.gwell", "com.gwelltimes", "com.jwkj"),
        notes="Yoosee family. UDP based, own cloud.",
    ),
    Signature(
        family="XM / Sofia (XMEye, Netsurveillance, DVRIP)",
        weight=8,
        libraries=("libFunSDK", "libMediaPlayer", "libNetSDK"),
        symbols=("FUN_Init", "XMSDK", "H264_DVR_Init"),
        classes=("com.xm.", "com.lib.", "com.mobile.myeye", "com.basic"),
        notes="0xFF-prefixed 20-byte header on UDP/TCP 34569, JSON payloads.",
    ),
    Signature(
        family="HiChip / Shenzhen Anni",
        weight=7,
        libraries=("libhichip", "libAnyanCamera", "libHiChipDefines"),
        symbols=("HiChipSDK", "hi_p2p_init"),
        classes=("com.hichip", "com.anni"),
        notes="Frequently a PPPP derivative under a vendor wrapper.",
    ),
    Signature(
        family="Anyka / Ingenic / Goke SoC SDK",
        weight=5,
        libraries=("libakmedia", "libanyka", "libgoke", "libjz"),
        symbols=("ak_vi_", "ak_venc_", "gk_api"),
        classes=("com.anyka", "com.goke"),
        notes="SoC vendor media SDK. Names the silicon, not the transport.",
    ),
    Signature(
        family="Beken Wi-Fi SoC support",
        weight=4,
        libraries=("libbk", "libbeken"),
        symbols=("bk_wlan", "bk_p2p"),
        classes=("com.beken", "bk7231", "bk7252"),
        notes="Consistent with the observed MAC OUI. Usually paired with PPPP.",
    ),
    Signature(
        family="Agora / third-party RTC",
        weight=6,
        libraries=("libagora", "libagora-rtc", "libwebrtc"),
        symbols=("agora_rtc", "AgoraRtcEngine"),
        classes=("io.agora", "org.webrtc"),
        notes="If present, media may be standard WebRTC — very different approach.",
    ),
]

# Things worth extracting regardless of which SDK it turns out to be.
HOSTNAME_RE = re.compile(
    rb"(?:https?://)?([a-z0-9][a-z0-9\-]{1,61}(?:\.[a-z0-9][a-z0-9\-]{1,61})+\.[a-z]{2,12})",
    re.IGNORECASE,
)
IPV4_RE = re.compile(rb"\b(?:\d{1,3}\.){3}\d{1,3}\b")
UID_RE = re.compile(rb"\b[A-Z]{4,8}-?\d{5,8}-?[A-Z]{4,6}\b")

CRYPTO_MARKERS = (
    b"Cipher.getInstance",
    b"SecretKeySpec",
    b"AES/CBC",
    b"AES/ECB",
    b"IvParameterSpec",
    b"MessageDigest",
    b"MD5",
    b"HmacSHA",
    b"DESKeySpec",
)

CODEC_MARKERS = (
    b"video/avc",
    b"video/hevc",
    b"MediaCodec",
    b"H264",
    b"H265",
    b"avcodec",
    b"G711",
    b"G726",
    b"ADPCM",
    b"AAC",
    b"PCMU",
    b"PCMA",
)

# Hostnames that are almost always analytics/ads rather than camera transport.
NOISE_DOMAINS = (
    "google.com", "googleapis.com", "gstatic.com", "android.com", "schemas.android.com",
    "facebook.com", "fbcdn.net", "doubleclick.net", "crashlytics.com", "firebase.com",
    "firebaseio.com", "umeng.com", "umengcloud.com", "pangle.cn", "bytedance.com",
    "adjust.com", "appsflyer.com", "flurry.com", "w3.org", "apache.org", "github.com",
    "oracle.com", "json.org", "bouncycastle.org", "xmlpull.org", "sun.com",
)


@dataclass
class Report:
    scores: dict[str, int] = field(default_factory=lambda: defaultdict(int))
    evidence: dict[str, list[str]] = field(default_factory=lambda: defaultdict(list))
    native_libs: list[str] = field(default_factory=list)
    hostnames: dict[str, int] = field(default_factory=lambda: defaultdict(int))
    ips: dict[str, int] = field(default_factory=lambda: defaultdict(int))
    uids: set[str] = field(default_factory=set)
    crypto: dict[str, int] = field(default_factory=lambda: defaultdict(int))
    codecs: dict[str, int] = field(default_factory=lambda: defaultdict(int))
    files_scanned: int = 0


def iter_files(root: Path):
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix.lower() in TEXT_SUFFIXES or path.suffix.lower() in BINARY_SUFFIXES:
            try:
                if path.stat().st_size > MAX_FILE_BYTES:
                    continue
            except OSError:
                continue
            yield path


def scan(root: Path) -> Report:
    report = Report()

    # Native library inventory first — the filenames alone are often decisive.
    for so in root.rglob("*.so"):
        rel = str(so.relative_to(root))
        report.native_libs.append(rel)
        stem = so.stem.lower()
        for sig in SIGNATURES:
            for lib in sig.libraries:
                if lib.lower() in stem:
                    report.scores[sig.family] += sig.weight * 3
                    report.evidence[sig.family].append(f"native library: {rel}")

    for path in iter_files(root):
        try:
            data = path.read_bytes()
        except OSError:
            continue
        report.files_scanned += 1
        lowered = data.lower()
        rel = str(path.relative_to(root))

        for sig in SIGNATURES:
            for symbol in sig.symbols:
                if symbol.encode().lower() in lowered:
                    report.scores[sig.family] += sig.weight * 2
                    report.evidence[sig.family].append(f"symbol '{symbol}' in {rel}")
            for cls in sig.classes:
                if cls.encode().lower() in lowered:
                    report.scores[sig.family] += sig.weight
                    report.evidence[sig.family].append(f"class '{cls}' in {rel}")

        for host in HOSTNAME_RE.findall(data):
            name = host.decode("ascii", "replace").lower()
            if any(name == n or name.endswith("." + n) for n in NOISE_DOMAINS):
                continue
            report.hostnames[name] += 1

        for ip in IPV4_RE.findall(data):
            text = ip.decode()
            octets = text.split(".")
            if all(0 <= int(o) <= 255 for o in octets) and not text.startswith(
                ("0.", "1.0.", "127.", "255.")
            ):
                report.ips[text] += 1

        for uid in UID_RE.findall(data):
            report.uids.add(uid.decode("ascii", "replace"))

        for marker in CRYPTO_MARKERS:
            if marker in data:
                report.crypto[marker.decode()] += 1
        for marker in CODEC_MARKERS:
            if marker in data:
                report.codecs[marker.decode()] += 1

    return report


def render(report: Report, root: Path, top: int) -> None:
    line = "=" * 72
    print(line)
    print("APK SIGNATURE REPORT")
    print(line)
    print(f"  tree          : {root}")
    print(f"  files scanned : {report.files_scanned}")
    print(f"  native libs   : {len(report.native_libs)}")

    print("\n" + line)
    print("SDK FAMILY SCORES")
    print(line)
    if not report.scores:
        print(
            "\n  No known SDK fingerprints matched.\n\n"
            "  That is itself informative: it means the transport is either a\n"
            "  vendor-private implementation or an SDK not in this catalogue.\n"
            "  Fall back to the native library export lists (see below) and to\n"
            "  traffic capture, and add whatever you find to SIGNATURES here.\n"
        )
    else:
        ranked = sorted(report.scores.items(), key=lambda kv: kv[1], reverse=True)
        best = ranked[0][1]
        for family, score in ranked:
            bar = "#" * min(40, int(40 * score / best)) if best else ""
            note = next((s.notes for s in SIGNATURES if s.family == family), "")
            print(f"\n  {family}")
            print(f"    score {score:>5}  {bar}")
            if note:
                print(f"    {note}")
            for item in dict.fromkeys(report.evidence[family][:6]):
                print(f"      - {item}")
            extra = len(set(report.evidence[family])) - 6
            if extra > 0:
                print(f"      - ... and {extra} more matches")

        if best >= 20:
            print(
                f"\n  >> Strong match on '{ranked[0][0]}'. Confirm it by dumping the\n"
                "     exported symbols of the matching .so, then look up the SDK's\n"
                "     published API before writing any capture-based analysis."
            )

    print("\n" + line)
    print("NATIVE LIBRARIES")
    print(line)
    for lib in sorted(set(report.native_libs)):
        print(f"  {lib}")
    if report.native_libs:
        print(
            "\n  Dump exports for each:\n"
            "    nm -D --defined-only <path>          (or: llvm-nm, objdump -T)\n"
            "  Exported names are the single most reliable SDK tell."
        )
    else:
        print("  none found — pure-Java transport, which makes this much easier")

    print("\n" + line)
    print(f"HOSTNAMES (top {top}, ad/analytics domains filtered out)")
    print(line)
    for host, count in sorted(report.hostnames.items(), key=lambda kv: -kv[1])[:top]:
        print(f"  {count:>5}  {host}")
    print(
        "\n  Cross-reference these against the destinations in your idle-capture\n"
        "  pcap. A hostname appearing in both static strings and live traffic is\n"
        "  a confirmed endpoint, not a candidate."
    )

    print("\n" + line)
    print(f"HARD-CODED IPs (top {top})")
    print(line)
    for ip, count in sorted(report.ips.items(), key=lambda kv: -kv[1])[:top]:
        print(f"  {count:>5}  {ip}")

    if report.uids:
        print("\n" + line)
        print("UID-SHAPED STRINGS")
        print(line)
        for uid in sorted(report.uids)[:40]:
            print(f"  {uid}")
        print("\n  A prefix here may be the vendor's PPPP/TUTK licence prefix.")

    print("\n" + line)
    print("CRYPTO MARKERS")
    print(line)
    for marker, count in sorted(report.crypto.items(), key=lambda kv: -kv[1]):
        print(f"  {count:>5}  {marker}")
    print(
        "\n  Next step is not 'does it encrypt' but 'where does the key come from'.\n"
        "  Grep the JADX output for the constants passed to SecretKeySpec — a fixed\n"
        "  key compiled into the app is the common case in this hardware class."
    )

    print("\n" + line)
    print("CODEC MARKERS")
    print(line)
    for marker, count in sorted(report.codecs.items(), key=lambda kv: -kv[1]):
        print(f"  {count:>5}  {marker}")
    print(
        "\n  Determines what Media3 must be handed. H.264 in Annex-B needs no\n"
        "  FFmpeg extension; G.711 audio needs a ~30-line decoder."
    )
    print()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="Decompiled tree, e.g. research/01-apk-analysis")
    parser.add_argument("--top", type=int, default=30)
    parser.add_argument("--json", type=Path, help="Also write the raw report as JSON")
    args = parser.parse_args()

    if not args.root.is_dir():
        print(f"error: not a directory: {args.root}", file=sys.stderr)
        print("Run tools/apk_analyze.sh first.", file=sys.stderr)
        return 2

    report = scan(args.root)
    render(report, args.root, args.top)

    if args.json:
        args.json.write_text(
            json.dumps(
                {
                    "scores": dict(report.scores),
                    "evidence": {k: sorted(set(v)) for k, v in report.evidence.items()},
                    "native_libs": sorted(set(report.native_libs)),
                    "hostnames": dict(report.hostnames),
                    "ips": dict(report.ips),
                    "uids": sorted(report.uids),
                    "crypto": dict(report.crypto),
                    "codecs": dict(report.codecs),
                    "files_scanned": report.files_scanned,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        print(f"JSON written to {args.json}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
