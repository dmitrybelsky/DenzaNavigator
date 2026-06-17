#!/usr/bin/env bash
# Deploy + JDWP-inject the privileged HUD agent into com.byd.cameraautostudy (debuggable, holds
# BYDAUTO_INSTRUMENT_SET + BYDAUTO_SETTING_SET). After this the agent listens on @zbyd_hud_priv and
# the patched Yandex (HudPrivClient) drives the cluster through it. Re-run after a car reboot
# (the agent lives only in the app process; the injection is not persistent).
set -u
SERIAL="${1:-192.168.1.67:5555}"
PKG=com.byd.cameraautostudy
DEX_LOCAL="$(cd "$(dirname "$0")" && pwd)/patches/HudPrivAgent.dex"
DEX_DEV=/data/local/tmp/HudPrivAgent.dex

adb connect "$SERIAL" >/dev/null 2>&1
echo "[*] pushing agent dex -> $DEX_DEV"
adb -s "$SERIAL" push "$DEX_LOCAL" "$DEX_DEV" >/dev/null || { echo "[x] push failed"; exit 1; }
adb -s "$SERIAL" shell chmod 644 "$DEX_DEV"           # world-readable so cameraautostudy uid can load it
adb -s "$SERIAL" shell rm -rf /data/local/tmp/zbyd-odex 2>/dev/null

echo "[*] injecting via JDWP ..."
python3 "$(dirname "$0")/jdwp_inject.py" "$SERIAL" "$PKG" "$DEX_DEV"

echo "[*] agent log:"
adb -s "$SERIAL" shell "run-as $PKG cat files/hudpriv.log 2>/dev/null || cat /data/data/$PKG/files/hudpriv.log 2>/dev/null" | tail -10
