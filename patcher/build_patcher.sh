#!/usr/bin/env bash
# Build the zbyd on-device Yandex patcher APK.
# Bundles dexlib2 + guava + jsr305 + apksig into the app dex, packs HudEvents.dex + the signing key
# (PKCS12) as assets, builds with aapt2 + d8, then signs the patcher itself.
set -euo pipefail
SELF="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
BT="/opt/homebrew/share/android-commandlinetools/build-tools/34.0.0"
AJ="/opt/homebrew/share/android-commandlinetools/platforms/android-34/android.jar"
CL="/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/lib/external"
DEXLIB2="$CL/com/android/tools/smali/smali-dexlib2/3.0.9/smali-dexlib2-3.0.9.jar"
SMALIUTIL="$(ls "$CL"/com/android/tools/smali/smali-util/3.0.9/smali-util-3.0.9.jar 2>/dev/null || true)"
GUAVA="$CL/com/google/guava/guava/33.3.1-jre/guava-33.3.1-jre.jar"
JSR305="$CL/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar"
APKSIG="$BT/lib/apksigner.jar"
KS="$SELF/../zbyd-debug.keystore"
OUT="$SELF/zbyd-patcher.apk"
W="$SELF/work"; rm -rf "$W"; mkdir -p "$W/classes" "$W/assets" "$W/dex"

echo "[*] convert signing keystore JKS -> PKCS12 asset"
rm -f "$SELF/assets/zbyd.p12"
"$JAVA_HOME/bin/keytool" -importkeystore -srckeystore "$KS" -srcstoretype JKS -srcstorepass android \
  -destkeystore "$SELF/assets/zbyd.p12" -deststoretype PKCS12 -deststorepass android \
  -srcalias zbyd -destalias zbyd -noprompt 2>&1 | grep -iv warning || true
cp "$SELF/../patches/HudEvents.dex" "$SELF/assets/HudEvents.dex"

echo "[*] javac (app + deps on classpath)"
CP="$AJ:$DEXLIB2:$GUAVA:$JSR305:$APKSIG"; [ -n "$SMALIUTIL" ] && CP="$CP:$SMALIUTIL"
set +e
"$JAVA_HOME/bin/javac" -source 17 -target 17 -cp "$CP" -d "$W/classes" \
  "$SELF"/src/com/zbyd/patcher/*.java 2>"$W/javac.err"
JRC=$?; grep -vE 'warning:|Note:' "$W/javac.err" | head -40; [ $JRC -ne 0 ] && { echo "[x] javac failed ($JRC)"; exit 1; }
set -e

echo "[*] d8 (app classes + bundled libs -> dex, with desugaring)"
LIBS=("$DEXLIB2" "$GUAVA" "$JSR305" "$APKSIG"); [ -n "$SMALIUTIL" ] && LIBS+=("$SMALIUTIL")
"$BT/d8" --min-api 26 --lib "$AJ" --output "$W/dex" \
  $(find "$W/classes" -name '*.class') "${LIBS[@]}" 2>&1 | tail -5

echo "[*] aapt2 link (base apk with manifest + assets)"
"$BT/aapt2" link -o "$W/base.apk" -I "$AJ" --manifest "$SELF/AndroidManifest.xml" \
  -A "$SELF/assets" --min-sdk-version 26 --target-sdk-version 30 2>&1 | tail -3

echo "[*] add dex(es)"
cd "$W"; cp base.apk patcher-unsigned.apk
i=0; for d in dex/classes*.dex; do
  n=$([ $i -eq 0 ] && echo "classes.dex" || echo "classes$((i+1)).dex"); cp "$d" "$n"; zip -j patcher-unsigned.apk "$n" >/dev/null; i=$((i+1)); done
echo "    dex count: $i"

echo "[*] zipalign + sign patcher"
"$BT/zipalign" -p -f 4 patcher-unsigned.apk patcher-aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias zbyd \
  --out "$OUT" patcher-aligned.apk
echo "[ok] $OUT ($(du -h "$OUT" | cut -f1))"
