# Setup

Everything needed to run the tooling in this repo, on Windows (the primary host here),
with Linux/macOS notes where the commands differ.

---

## 1. Python tooling

Python 3.10 or newer.

```bash
cd tools
python -m venv .venv
```

Activate it — `.venv/Scripts/activate` on Windows under Git Bash, `.venv/bin/activate`
elsewhere — then:

```bash
pip install -r tools/requirements.txt
```

`scapy` needs a packet driver to send raw frames. On Windows install
[Npcap](https://npcap.com/) with "WinPcap API-compatible mode" checked; on Linux the
kernel provides it but the scripts need `sudo` (or `CAP_NET_RAW`).

Only `isolation_check.py` and parts of `lan_discover.py` need raw sockets.
`p2p_probe.py` uses ordinary UDP sockets and runs unprivileged, except that binding a
broadcast socket may require elevation depending on the firewall profile.

---

## 2. Android tooling

| Tool | Purpose | Install |
|---|---|---|
| `adb` (platform-tools) | pull the APK, run `tcpdump`, read logs | [developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools) |
| **JADX** | APK → readable Java | `scoop install jadx` / [github.com/skylot/jadx](https://github.com/skylot/jadx) |
| **APKTool** | resources, smali, manifest | [apktool.org](https://apktool.org/) |
| **PCAPdroid** | capture on an unrooted phone | F-Droid or Play Store |
| **Wireshark** | read the captures | [wireshark.org](https://www.wireshark.org/) |
| **mitmproxy** | HTTPS interception, *if* there is any | `pip install mitmproxy` |
| **Frida** | SSL unpinning, native hooking | `pip install frida-tools` |
| Android Studio | build `android-app/` | Ladybug or newer, JDK 17 |

`apk_analyze.sh` looks for `jadx` and `apktool` on `PATH` and tells you which one is
missing rather than failing halfway through.

---

## 3. Getting the APK off your own phone

Find the package name — the vendor's display name rarely matches it:

```bash
adb shell pm list packages | grep -iE 'cam|fty|nm|mini|dv'
```

Get its install paths and pull every one (modern apps ship as split APKs):

```bash
adb shell pm path com.whatever.thepackage
adb pull /data/app/~~xxxx==/com.whatever.thepackage-yyyy==/base.apk research/captures/ftycampro.apk
```

Also record the version, so a later re-analysis knows what it's comparing against:

```bash
adb shell dumpsys package com.whatever.thepackage | grep -E 'versionName|versionCode|firstInstallTime'
```

Put that in `research/01-apk-analysis/README.md`.

---

## 4. Capturing traffic

Four options, roughly in increasing order of fidelity and effort.

### 4a. PCAPdroid — no root, start here

Install PCAPdroid on the phone, set it to target only the camera app, choose "PCAP
file" as the dump mode, and start. It runs a local VPN so it sees the app's traffic
without root. Exports straight to a `.pcapng` you can drop into
`research/captures/`.

Caveat: as a VPN-based capture it sees the app's sockets, so you get everything the
app sends and receives, but you don't see traffic from *other* devices — including the
camera's own unsolicited broadcasts. Pair it with 4c.

### 4b. `tcpdump` over adb — needs root

```bash
adb push tcpdump /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/tcpdump
adb shell su -c '/data/local/tmp/tcpdump -i any -s 0 -w /sdcard/cap.pcap'
adb pull /sdcard/cap.pcap research/captures/
```

Highest fidelity from the phone's perspective, but requires a rooted device.

### 4c. Capture from the PC on the same segment

Wi-Fi client isolation and switched Ethernet both mean you will *not* see
phone↔camera unicast traffic by simply sniffing from a third host. To actually see it
you need one of:

- **A hotspot you control.** Share the PC's connection (Windows Mobile Hotspot, or
  `nmcli`/`hostapd` on Linux), join both the phone and the camera to it, and capture
  on the hotspot interface. Every packet between them now transits your machine. This
  is the highest-value setup in the whole project and is worth the twenty minutes.
- **Monitor mode** on a Wi-Fi adapter that supports it, plus the WPA2 passphrase and a
  captured 4-way handshake so Wireshark can decrypt. Fiddly; the hotspot is easier.
- **A router that can mirror or run tcpdump** (OpenWrt: `opkg install tcpdump`).

### 4d. mitmproxy, only for HTTPS

Set the phone's Wi-Fi proxy to the PC, install the mitmproxy CA on the phone, run
`mitmweb`. Apps targeting API 24+ ignore user-installed CAs unless their network
security config opts in, so expect this to fail on a modern build and to need the CA
in the system store (root) or Frida unpinning. Check `apktool`'s
`res/xml/network_security_config.xml` first — it tells you immediately whether it's
worth trying.

### Naming convention

Name captures so the D1/D2/D3 comparison stays legible:

```
research/captures/<condition>-<scenario>-<yyyymmdd>.pcapng
  e.g. d1-local-live-20260802.pcapng
       d2-mobile-live-20260802.pcapng
       d3-softap-setup-20260802.pcapng
```

---

## 5. Building the Android app

```bash
cd android-app
gradle wrapper --gradle-version 8.9
```

(The wrapper JAR is deliberately not committed. Run that once, or just open the
directory in Android Studio and let it generate one.)

```bash
./gradlew assembleDebug
```

Output lands in `android-app/app/build/outputs/apk/debug/`. Requirements: JDK 17,
Android SDK 35, minSdk 26.

---

## 6. Safety notes for your own network

- Factory-resetting the camera to reach provisioning mode will drop it from your Wi-Fi.
  Have the SSID and passphrase to hand before you start.
- Probing UDP broadcast addresses touches every device on the subnet. It's harmless,
  but if anything else on the network is fussy, run the exercise on an isolated
  hotspot instead.
- Captures contain the camera UID, your Wi-Fi passphrase (in provisioning captures —
  the SoftAP setup exchange transmits it, sometimes barely obfuscated), and possibly
  account tokens. `research/captures/` is git-ignored for exactly this reason. Commit
  only redacted excerpts.
