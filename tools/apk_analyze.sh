#!/usr/bin/env bash
#
# Decompile an APK and produce a first-pass inventory.
#
#   bash tools/apk_analyze.sh research/captures/ftycampro.apk
#
# Output goes to research/01-apk-analysis/:
#   unzipped/        raw APK contents (native libs live here)
#   jadx-out/        decompiled Java
#   apktool-out/     resources, smali, decoded AndroidManifest
#   inventory.txt    manifest facts, permissions, .so exports
#
# Checks for its dependencies up front rather than failing halfway through a
# ten-minute decompile.

set -euo pipefail

APK="${1:-}"
OUT_DIR="${2:-research/01-apk-analysis}"

if [[ -z "$APK" ]]; then
    echo "usage: $0 <path-to.apk> [output-dir]" >&2
    exit 2
fi
if [[ ! -f "$APK" ]]; then
    echo "error: no such file: $APK" >&2
    echo "Pull it from your own phone first — see docs/SETUP.md section 3." >&2
    exit 2
fi

have() { command -v "$1" >/dev/null 2>&1; }

missing=0
for tool in unzip; do
    have "$tool" || { echo "error: '$tool' not found on PATH" >&2; missing=1; }
done
[[ $missing -eq 1 ]] && exit 3

have jadx    || echo "warning: jadx not found — skipping Java decompilation" >&2
have apktool || echo "warning: apktool not found — skipping resource decoding" >&2

# Pick whichever symbol dumper exists; they all print the same thing for our purposes.
NM_TOOL=""
for candidate in nm llvm-nm arm-linux-androideabi-nm objdump; do
    have "$candidate" && { NM_TOOL="$candidate"; break; }
done

mkdir -p "$OUT_DIR"
INVENTORY="$OUT_DIR/inventory.txt"

banner() {
    printf '\n%s\n== %s\n%s\n' "$(printf '=%.0s' {1..70})" "$1" "$(printf '=%.0s' {1..70})"
}

{
    banner "APK INVENTORY"
    echo "source : $APK"
    echo "sha256 : $( (sha256sum "$APK" 2>/dev/null || shasum -a 256 "$APK") | awk '{print $1}')"
    echo "size   : $(du -h "$APK" | awk '{print $1}')"
    echo "date   : $(date -Iseconds)"
} | tee "$INVENTORY"

# ---------------------------------------------------------------- unzip
banner "Unpacking" | tee -a "$INVENTORY"
rm -rf "$OUT_DIR/unzipped"
mkdir -p "$OUT_DIR/unzipped"
unzip -qo "$APK" -d "$OUT_DIR/unzipped"
echo "unpacked to $OUT_DIR/unzipped"

{
    banner "NATIVE LIBRARIES"
    find "$OUT_DIR/unzipped/lib" -name '*.so' 2>/dev/null \
        | sort \
        | while read -r so; do printf '%10s  %s\n' "$(du -h "$so" | awk '{print $1}')" "$so"; done \
        || echo "(no lib/ directory — pure Java transport)"
} | tee -a "$INVENTORY"

# ---------------------------------------------------------- native exports
if [[ -n "$NM_TOOL" ]]; then
    {
        banner "NATIVE EXPORTS  (via $NM_TOOL)"
        echo "Exported symbol names are the single most reliable SDK fingerprint."
        while IFS= read -r so; do
            echo
            echo "--- $so"
            if [[ "$NM_TOOL" == "objdump" ]]; then
                objdump -T "$so" 2>/dev/null | awk '$0 ~ /DF .text/ {print $NF}' | sort -u | head -200
            else
                "$NM_TOOL" -D --defined-only "$so" 2>/dev/null \
                    | awk '$2 ~ /^[TW]$/ {print $3}' | sort -u | head -200
            fi
        done < <(find "$OUT_DIR/unzipped/lib" -name '*.so' 2>/dev/null | sort -u)
    } | tee -a "$INVENTORY"
else
    echo "note: no nm/objdump available — skipping native export dump" | tee -a "$INVENTORY"
fi

# ---------------------------------------------------------------- apktool
if have apktool; then
    banner "APKTool" | tee -a "$INVENTORY"
    rm -rf "$OUT_DIR/apktool-out"
    apktool d -f -o "$OUT_DIR/apktool-out" "$APK" >/dev/null
    echo "decoded to $OUT_DIR/apktool-out"

    MANIFEST="$OUT_DIR/apktool-out/AndroidManifest.xml"
    if [[ -f "$MANIFEST" ]]; then
        {
            banner "PERMISSIONS"
            grep -o 'android.permission.[A-Z_]*' "$MANIFEST" | sort -u

            banner "COMPONENTS"
            grep -oE '<(activity|service|receiver|provider)[^>]*android:name="[^"]*"' "$MANIFEST" \
                | grep -oE 'android:name="[^"]*"' | sort -u | head -60

            banner "CLEARTEXT / NETWORK SECURITY CONFIG"
            grep -oE 'usesCleartextTraffic="[^"]*"|networkSecurityConfig="[^"]*"' "$MANIFEST" || \
                echo "(not declared)"
        } | tee -a "$INVENTORY"

        NSC="$OUT_DIR/apktool-out/res/xml/network_security_config.xml"
        if [[ -f "$NSC" ]]; then
            { banner "network_security_config.xml"; cat "$NSC"; } | tee -a "$INVENTORY"
            echo
            echo ">> Read this before attempting mitmproxy. If it does not trust the"
            echo "   'user' certificate store, interception needs root or Frida."
        fi
    fi
fi

# ------------------------------------------------------------------ jadx
if have jadx; then
    banner "JADX" | tee -a "$INVENTORY"
    rm -rf "$OUT_DIR/jadx-out"
    # Decompilation of obfuscated code routinely hits errors; --no-res keeps it
    # fast since apktool already produced better resources.
    jadx -d "$OUT_DIR/jadx-out" --no-res --show-bad-code "$APK" >/dev/null 2>&1 || \
        echo "jadx reported errors (normal for obfuscated apps) — output is still usable"
    echo "decompiled to $OUT_DIR/jadx-out"
    echo "java files: $(find "$OUT_DIR/jadx-out" -name '*.java' | wc -l)"
fi

banner "NEXT"
cat <<'EOF'
Signature report — read this before opening any decompiled source by hand:

    python tools/apk_signatures.py research/01-apk-analysis

Then, depending on what it finds:
  * strong SDK hit  -> look up that SDK's published API; you may be able to skip
                       the traffic-capture phase entirely
  * no hit          -> work from the native export lists in inventory.txt and go
                       straight to capture (docs/INVESTIGATION-CHECKLIST.md, D)

Record what you confirm in research/findings/ using _TEMPLATE.md. Keep guesses
in research/03-protocol-hypotheses.md — not in findings.
EOF

echo
echo "Inventory written to $INVENTORY"
