#!/usr/bin/env bash
# Deploy + JDWP-inject the privileged HUD/car agent into com.byd.cameraautostudy (debuggable, platform_app,
# holds BYDAUTO_INSTRUMENT_SET / BODYWORK_SET / SETTING_SET + MANAGE_EXTERNAL_STORAGE). After this the
# agent executes privileged BYDAuto HAL on behalf of the re-signed Yandex, which reaches it through the
# /sdcard/zbyd req/res directory queue (HudBridge) — the abstract socket is SELinux-unreachable from
# untrusted_app. Re-run after a car reboot (the injection lives only in the app process).
#
# The inject is reliable via jdwp_inject.py: config-change poke (cmd uimode) hits Handler.dispatchMessage,
# and the dex is loaded with InMemoryDexClassLoader from bytes read out of /data/local/tmp (shell_data_file,
# readable by platform_app) — sidestepping the Android 11+ "writable dex" (W^X) block.
set -u
SERIAL="${1:-192.168.1.67:5555}"
PKG=com.byd.cameraautostudy
YANDEX="${2:-ru.yandex.yandexnavi}"
DEX_LOCAL="$(cd "$(dirname "$0")" && pwd)/patches/HudPrivAgent.dex"
DEX_DEV=/data/local/tmp/HudPrivAgent.dex

adb connect "$SERIAL" >/dev/null 2>&1
echo "[*] pushing agent dex -> $DEX_DEV"
adb -s "$SERIAL" push "$DEX_LOCAL" "$DEX_DEV" >/dev/null || { echo "[x] push failed"; exit 1; }
adb -s "$SERIAL" shell chmod 644 "$DEX_DEV"           # plain-readable by platform_app for the in-memory load

echo "[*] injecting via JDWP ..."
python3 "$(dirname "$0")/jdwp_inject.py" "$SERIAL" "$PKG" "$DEX_DEV" || { echo "[x] inject failed"; exit 1; }

echo "[*] granting MANAGE_EXTERNAL_STORAGE (file bridge; persists across reboot) ..."
adb -s "$SERIAL" shell appops set "$PKG"    MANAGE_EXTERNAL_STORAGE allow
adb -s "$SERIAL" shell appops set "$YANDEX" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || echo "    (Yandex not installed yet — grant after install)"

echo "[*] agent log:"
adb -s "$SERIAL" shell "run-as $PKG cat files/hudpriv.log 2>/dev/null || cat /data/data/$PKG/files/hudpriv.log 2>/dev/null" | tail -10
echo "[*] bridge test (read moonroof config -> expect ret=8 on N9):"
adb -s "$SERIAL" shell "mkdir -p /sdcard/zbyd/req; printf 'BODY getMoonRoofConfig\n' > /sdcard/zbyd/req/t.tmp; mv /sdcard/zbyd/req/t.tmp /sdcard/zbyd/req/t.cmd"
sleep 1
adb -s "$SERIAL" shell "cat /sdcard/zbyd/res/t.res 2>/dev/null; rm -f /sdcard/zbyd/res/t.res"
