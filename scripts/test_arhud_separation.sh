#!/usr/bin/env bash
# AR-HUD map non-root experiment — location-separation test.
#
# Hypothesis (source-grounded):
#   launchermap reads the fused Android LocationManager (mockable)  -> follows a China mock.
#   Yandex MapKit runs a raw-GNSS spoofing detector (libmaps-mobile.so:
#     location_spoofing_processor_reject_platform_lbs_near_spoofed_gps) -> REJECTS a China mock that
#     contradicts the real satellites, staying on the real raw-GNSS position.
#   => launchermap = China, Yandex = real  (separation).  Then launchermap's China nav engages the
#      AR-HUD 0x8003 map subscription, and OUR pushMap (real Yandex render) overrides it.
#
# PHASE 1 (decisive, harmless): mock China -> does NaviKit stay on the real position?
# PHASE 2 (full chain): China nav -> onNavStart -> 0x8003 subscription -> pushMap override.
#
# Reversible: PHASE 0 of cleanup turns the mock back off (car/real feed resumes).
set -u
S="${1:-192.168.1.67:5555}"; PKG=ru.yandex.yandexnavi
A(){ adb -s "$S" "$@"; }
flag(){ A shell am broadcast -a com.zbyd.hud.FLAG --es key "$1" --ez val "$2" >/dev/null 2>&1; }
loctail(){ A shell "run-as $PKG cat files/zbyd.log 2>/dev/null" | grep -iE "$1" | tail -"${2:-6}"; }

adb connect "$S" >/dev/null 2>&1
A shell echo ok >/dev/null 2>&1 || { echo "[x] N9 $S unreachable"; exit 1; }
echo "=== baseline NaviKit position (real) ==="; loctail 'NAVIKIT loc' 3

echo ""
echo "=== PHASE 1: enable China mock — launchermap should follow, Yandex should REJECT (stay real) ==="
flag mock_china true
echo "[*] mock_china=true sent; waiting 12s for providers + MapKit spoof eval ..."
A shell "logcat -c" 2>/dev/null
sleep 12
echo "--- NaviKit position now (DECISIVE):"
loctail 'NAVIKIT loc|mockChina' 6
echo ""
echo "  INTERPRET: lat ~55.9 (Moscow) = Yandex stayed REAL -> SEPARATION WORKS -> continue to phase 2."
echo "             lat ~39.9 (Beijing) = mock won -> separation failed -> stop, disable mock_china."
echo ""
echo "--- where launchermap thinks it is (should be Beijing ~39.9):"
A shell "logcat -d 2>/dev/null | grep -iE 'LocationController|LocInfo|getLastKnownLocation|39\.9|116\.4' | tail -4"

echo ""
read -rp "=== Phase 1 OK (Yandex stayed real)? run phase 2 China-nav? [y/N] " go
if [ "${go:-N}" != "y" ]; then echo "[*] stopping after phase 1"; else
  echo "=== PHASE 2: China route -> launchermap nav -> onNavStart -> 0x8003 sub -> pushMap override ==="
  # Beijing dest ~5km from Tiananmen; coordinateType=2 (WGS84->GCJ02 applies inside China).
  DATA=$(python3 -c "import urllib.parse,json;print(urllib.parse.quote(json.dumps({'coordinateType':2,'destination':{'name':'BJ','latitude':39.95,'longitude':116.45},'waypoints':[]})))")
  A shell "am start -a android.intent.action.VIEW -d 'bydautomap://route?sourceApplication=com.zbyd.test&data=$DATA' com.byd.launchermap" 2>&1 | head -1
  echo "[*] route fired; if it shows a PREVIEW, tap 'Go'/导航 on the IVI to start guidance (onNavStart)."
  sleep 8
  echo "--- launchermap onNavStart + our pushMap ret (expect ret 1->0 once subscribed):"
  A shell "logcat -d 2>/dev/null | grep -iE 'onNavStart|SomeIPDataHudManager|NaviController.*start' | tail -5"
  loctail 'pushMap fire' 5
  echo "--- screencap IVI (Yandex should show REAL Russia; AR-HUD = check physically/photo):"
  A shell screencap -p /sdcard/sep.png 2>/dev/null; A pull /sdcard/sep.png /tmp/n9_sep.png 2>&1 | tail -1; A shell rm -f /sdcard/sep.png 2>/dev/null
fi

echo ""
echo "=== CLEANUP: disable China mock (real/car feed resumes) ==="
flag mock_china false
echo "[*] mock_china=false. NaviKit should return to the real/car position."
