#!/usr/bin/env bash
#
# patch_weatherplugin.sh — inject the weather HUD hook into Yandex.Weather (ru.yandex.weatherplugin).
#
# Locates the current-weather POJO by FIELD SIGNATURE (temp:I + condition + icon + feelsLike — the
# obfuscated class name varies per version, e.g. Lym3 in 26.4.12) and injects an invoke-static at the
# top of its getTemp()I getter:  HudWeather.fromWeather(p0)  → reflects temp/condition/icon → SOME/IP
# regionalAndWeather (via HudSomeIp). Adds HudEvents.dex (carries HudWeather + HudSomeIp) as classesN.
#
# Usage:
#   apktool d ru.yandex.weatherplugin.apk -o /tmp/wx-decompiled   # one-time decode
#   ./patch_weatherplugin.sh [decoded_dir] [out_apk] [--no-build]
set -uo pipefail

DEC="${1:-/tmp/wx-decompiled}"
OUT="${2:-/tmp/weatherplugin-signed.apk}"
NO_BUILD=0; [[ "${3:-}" == "--no-build" || "${2:-}" == "--no-build" ]] && NO_BUILD=1
SELF="$(cd "$(dirname "$0")" && pwd)"
KS="$SELF/yandexnavi/zbyd-debug.keystore"; [ -f "$KS" ] || KS="$SELF/zbyd-debug.keystore"
BT="/opt/homebrew/share/android-commandlinetools/build-tools/34.0.0"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

[ -d "$DEC" ] || { echo "[x] no decoded dir: $DEC  (run: apktool d <apk> -o $DEC)"; exit 1; }

echo "[*] locating weather POJO in $DEC"
python3 - "$DEC" <<'PY'
import sys, os, re, glob
dec = sys.argv[1]

def strip_foreign_permissions(man):
    """Strip ONLY <permission> definitions that collide cross-app: a re-signed standalone APK can't
    co-own a shared permission already defined by another installed app (-> INSTALL_FAILED_DUPLICATE_
    PERMISSION). But the app's OWN self-enforced permissions MUST stay, else a provider/receiver that
    requires an now-undefined permission throws SecurityException at init -> early crash. Rule: strip a
    <permission> def only if its name is (a) NOT under the app's package prefix AND (b) NOT referenced
    by any android:permission/readPermission/writePermission in this manifest."""
    pkg_m = re.search(r'<manifest\b[^>]*\bpackage="([^"]+)"', man)
    pkg = pkg_m.group(1) if pkg_m else ''
    referenced = set(re.findall(r'android:(?:permission|readPermission|writePermission|targetPermission)="([^"]+)"', man))
    defs = re.findall(r'<permission\b[^>]*?/>|<permission\b.*?</permission>', man, re.S)
    stripped = kept = 0
    for d in defs:
        nm = re.search(r'android:name="([^"]+)"', d)
        name = nm.group(1) if nm else ''
        own = pkg and (name == pkg or name.startswith(pkg + '.'))
        if (not own) and (name not in referenced):
            man = man.replace(d, ''); stripped += 1
            print(f"[+] dup-permission fix: stripped foreign <permission> {name}")
        else:
            kept += 1
    if stripped == 0:
        print(f"[=] dup-permission fix: no foreign <permission> to strip ({kept} kept)")
    else:
        print(f"[=] dup-permission fix: kept {kept} self/required <permission> definition(s)")
    return man
# class with all four private fields = the parsed current-weather model
need = (r'\.field private temp:I',
        r'\.field private (final )?condition:Ljava/lang/String;',
        r'\.field private (final )?icon:Ljava/lang/String;',
        r'\.field private (final )?feelsLike:D')
target = None
for f in glob.glob(os.path.join(dec, 'smali*', '**', '*.smali'), recursive=True):
    t = open(f, encoding='utf-8', errors='ignore').read()
    if all(re.search(p, t) for p in need):
        target = (f, t); break
if not target:
    print("[!] weather POJO not found (version diff?) -- skip"); sys.exit(0)
f, t = target
print(f"[+] weather POJO: {os.path.relpath(f, dec)}")
if 'HudWeather' in t:
    print("[=] already hooked"); sys.exit(0)
m = re.search(r'(\.method public (?:final )?getTemp\(\)I\n[ \t]*\.locals \d+\n)', t)
if not m:
    print("[!] getTemp()I not matched -- skip"); sys.exit(0)
inj = m.group(1) + "\n    invoke-static {p0}, Lcom/zbyd/hudhook/HudWeather;->fromWeather(Ljava/lang/Object;)V\n"
open(f, 'w', encoding='utf-8').write(t[:m.start()] + inj + t[m.end():])
print("[+] getTemp() hooked -> HudWeather.fromWeather(p0)")

# dup-permission fix: a re-signed standalone APK can't co-own a Yandex shared permission already
# defined by another installed Yandex app -> INSTALL_FAILED_DUPLICATE_PERMISSION. Strip <permission>
# *definitions* (keep <uses-permission>). Needs a full (text-manifest) decode; skips on binary AXML.
try:
    mf = os.path.join(dec, 'AndroidManifest.xml'); man = open(mf, encoding='utf-8').read()
    if '<application' in man:
        man = strip_foreign_permissions(man)
        open(mf, 'w', encoding='utf-8').write(man)
except Exception as e:
    print(f"[!] dup-permission fix: skip ({e}) -- decode without -r to enable")
PY

if [ "$NO_BUILD" = "1" ]; then echo "[*] --no-build: patch applied"; exit 0; fi

echo "[*] apktool b"; apktool b "$DEC" -o /tmp/wx-final.apk >/dev/null
# append HudEvents.dex (HudWeather + HudSomeIp) as the next contiguous classesN.dex
if [ -f "$SELF/patches/HudEvents.dex" ]; then
    maxn=1
    for e in $(unzip -l /tmp/wx-final.apk | grep -oE 'classes[0-9]*\.dex'); do
        num=$(echo "$e" | sed -E 's/classes//; s/\.dex//'); [ -z "$num" ] && num=1
        [ "$num" -gt "$maxn" ] && maxn=$num
    done
    NEXT=$((maxn + 1)); cp "$SELF/patches/HudEvents.dex" "/tmp/classes${NEXT}.dex"
    (cd /tmp && zip -j /tmp/wx-final.apk "classes${NEXT}.dex" >/dev/null); rm -f "/tmp/classes${NEXT}.dex"
    echo "[+] added HudEvents.dex as classes${NEXT}.dex"
fi
echo "[*] zipalign"; "$BT/zipalign" -p -f 4 /tmp/wx-final.apk /tmp/wx-aligned.apk
echo "[*] apksigner"; "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias zbyd --out "$OUT" /tmp/wx-aligned.apk
echo "[ok] signed: $OUT ($(du -h "$OUT" | cut -f1))"
