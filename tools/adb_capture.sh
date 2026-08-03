#!/usr/bin/env bash
#
# ADB-side helpers for the capture phase.
#
#   bash tools/adb_capture.sh find                 locate the camera app package
#   bash tools/adb_capture.sh info <pkg>           version, install time, permissions
#   bash tools/adb_capture.sh pull <pkg>           pull every split APK
#   bash tools/adb_capture.sh log <pkg>            live logcat filtered to that app
#   bash tools/adb_capture.sh netstat              sockets the phone currently holds
#   bash tools/adb_capture.sh tcpdump <out.pcap>   root-only on-device capture
#
# Everything here targets your own phone and your own installed app.

set -euo pipefail

command -v adb >/dev/null 2>&1 || { echo "error: adb not on PATH" >&2; exit 3; }
adb get-state >/dev/null 2>&1 || { echo "error: no device. Check USB debugging." >&2; exit 3; }

CMD="${1:-help}"
shift || true

case "$CMD" in

find)
    echo "Packages matching camera-ish names:"
    adb shell pm list packages \
        | sed 's/^package://' \
        | grep -iE 'cam|fty|nm|mini|dv|ipc|eye|view' \
        | sort
    echo
    echo "If nothing looks right, list the most recently installed instead:"
    echo "  adb shell pm list packages -3"
    ;;

info)
    PKG="${1:?usage: $0 info <package>}"
    echo "== $PKG"
    adb shell dumpsys package "$PKG" \
        | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime|targetSdk|minSdk' \
        | sed 's/^ *//'
    echo
    echo "== granted permissions"
    adb shell dumpsys package "$PKG" \
        | sed -n '/requested permissions:/,/^$/p' | sed 's/^ *//'
    echo
    echo "Record the version in research/01-apk-analysis/README.md so a later"
    echo "re-analysis knows what it is comparing against."
    ;;

pull)
    PKG="${1:?usage: $0 pull <package>}"
    DEST="${2:-research/captures}"
    mkdir -p "$DEST"
    echo "Paths for $PKG:"
    adb shell pm path "$PKG" | sed 's/^package://' | tr -d '\r' | while read -r path; do
        name="$(basename "$path")"
        out="$DEST/${PKG}-${name}"
        echo "  pulling $path -> $out"
        adb pull "$path" "$out" >/dev/null
    done
    echo
    echo "Now:  bash tools/apk_analyze.sh $DEST/${PKG}-base.apk"
    ;;

log)
    PKG="${1:?usage: $0 log <package>}"
    PID="$(adb shell pidof "$PKG" | tr -d '\r' || true)"
    if [[ -z "$PID" ]]; then
        echo "App is not running — start it on the phone first." >&2
        exit 1
    fi
    echo "logcat for $PKG (pid $PID). Ctrl-C to stop."
    echo "Watch for UIDs, server hostnames, session ids and error codes — these"
    echo "apps are usually built with logging left on."
    adb logcat --pid="$PID" -v time
    ;;

netstat)
    echo "== UDP sockets"
    adb shell cat /proc/net/udp 2>/dev/null | head -40
    echo
    echo "== TCP sockets"
    adb shell cat /proc/net/tcp 2>/dev/null | head -40
    echo
    echo "Addresses are little-endian hex. 0100A8C0:7D6C is 192.168.0.1:32108."
    ;;

tcpdump)
    OUT="${1:-research/captures/phone-$(date +%Y%m%d-%H%M%S).pcap}"
    if ! adb shell 'su -c id' 2>/dev/null | grep -q 'uid=0'; then
        echo "This device is not rooted, so on-device tcpdump is unavailable." >&2
        echo "Use PCAPdroid instead — docs/SETUP.md section 4a. It needs no root." >&2
        exit 1
    fi
    adb shell 'su -c "ls /data/local/tmp/tcpdump"' >/dev/null 2>&1 || {
        echo "Push a tcpdump binary first:" >&2
        echo "  adb push tcpdump /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/tcpdump" >&2
        exit 1
    }
    mkdir -p "$(dirname "$OUT")"
    echo "Capturing to /sdcard/cap.pcap. Ctrl-C when the scenario is done."
    adb shell 'su -c "/data/local/tmp/tcpdump -i any -s 0 -w /sdcard/cap.pcap"' || true
    adb pull /sdcard/cap.pcap "$OUT"
    echo "Saved $OUT"
    echo "Now:  python tools/pcap_triage.py $OUT --peer 192.168.29.214"
    ;;

*)
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
