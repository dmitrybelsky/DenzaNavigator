#!/usr/bin/env bash
# One-shot redeploy of the patched Yandex Navigator onto the N9 with the CURRENT patches/HudEvents.dex
# (NOA route-feed + FlagReceiver kill-switch + voice + all HUD/cluster hooks).
#
# Chain:  apktool d (base.apk) -> patch_yandex.sh (bundles fresh dex + smali hooks) -> sign
#         -> stage into yandexnavi29/ -> deploy_all.sh (chunked push + install + grants + JDWP agent + cluster)
#
# Idempotent. Re-run after editing hudhook_src (rebuild the dex first with scripts/build_hudevents.sh or the
# inline javac+d8), or after a car reboot (re-runs agent/cluster which are not boot-persistent).
#
# Usage:  ./redeploy_yandex.sh [serial]          serial default 192.168.1.67:5555
#         FORCE_DECODE=1 ./redeploy_yandex.sh     re-decode even if /tmp/yandex-decompiled exists
set -euo pipefail
SELF="$(cd "$(dirname "$0")" && pwd)"
SERIAL="${1:-192.168.1.67:5555}"
BASE="$SELF/yandexnavi29/base.apk"                       # original unpatched 29.4.2 (decode source)
DEC="/tmp/yandex-decompiled"
SIGNED="/tmp/yandexnavi-signed.apk"
STAGED="$SELF/yandexnavi29/yandexnavi-29.4.2-patched-signed.apk"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

[ -f "$SELF/patches/HudEvents.dex" ] || { echo "[x] patches/HudEvents.dex missing — build it first"; exit 1; }
echo "[*] bundling HudEvents.dex = $(wc -c < "$SELF/patches/HudEvents.dex") bytes"

# 1. decode (skip if already decoded, unless FORCE_DECODE)
if [ "${FORCE_DECODE:-0}" = "1" ] || [ ! -d "$DEC/smali" ]; then
  [ -f "$BASE" ] || { echo "[x] no base apk: $BASE"; exit 1; }
  echo "[*] apktool d $BASE -> $DEC (slow, ~couple min) ..."
  rm -rf "$DEC"
  apktool d -f "$BASE" -o "$DEC" >/dev/null
else
  echo "[=] reusing decoded dir $DEC (FORCE_DECODE=1 to redo)"
fi

# 2. patch + rebuild + sign (idempotent; re-injects all hooks + the current dex)
echo "[*] patch_yandex.sh ..."
"$SELF/patch_yandex.sh" "$DEC" "$SIGNED"

# 3. stage for deploy_all
cp "$SIGNED" "$STAGED"
echo "[*] staged -> $STAGED ($(wc -c < "$STAGED") bytes)"

# 4. push + install + grants + agent + cluster
echo "[*] deploy_all.sh $SERIAL ..."
"$SELF/deploy_all.sh" "$SERIAL"

echo ""
echo "=== POST-DEPLOY CHECKS ==="
echo "  kill-switch test (should log 'FLAG via broadcast adas_noa=false'):"
echo "    adb -s $SERIAL shell am broadcast -a com.zbyd.hud.FLAG --es key adas_noa --ez val false"
echo "  NOA send log (build a route in Yandex, then):"
echo "    adb -s $SERIAL shell run-as ru.yandex.yandexnavi cat files/zbyd.log | grep -E 'NOA|FLAG|FlagReceiver' | tail"
echo "  re-arm NOA:"
echo "    adb -s $SERIAL shell am broadcast -a com.zbyd.hud.FLAG --es key adas_noa --ez val true"
