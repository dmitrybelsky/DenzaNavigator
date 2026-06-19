#!/usr/bin/env bash
# AR-HUD map non-root experiment — location-separation test + full chain.
#
# Mechanism (source-grounded):
#   launchermap reads the fused Android LocationManager (mockable) -> follows a China mock.
#   Yandex MapKit is routed to a MapKit DummyLocationManager we feed the REAL car position to
#   (MapsLocationManagerImpl.p() patched -> HudDummyLocation.holder()), so Yandex ignores the system mock.
#   Backstop: even without the dummy, MapKit's raw-GNSS spoof detector rejects the China mock
#   (libmaps-mobile.so: location_spoofing_processor_reject_platform_lbs_near_spoofed_gps).
#   => launchermap = China, Yandex = real. launchermap's China nav engages the AR-HUD 0x8003 map
#      subscription; OUR pushMap (real Yandex render) overrides it.
#
# Run: ./test_arhud_separation.sh [serial]     (default 192.168.1.67:5555)
# Reversible: cleanup turns mock_china off -> real/car feed resumes.
set -u
S="${1:-192.168.1.67:5555}"; PKG=ru.yandex.yandexnavi; LM=com.byd.launchermap
A(){ adb -s "$S" "$@"; }
flag(){ A shell am broadcast -a com.zbyd.hud.FLAG --es key "$1" --ez val "$2" >/dev/null 2>&1; }
zlog(){ A shell "run-as $PKG cat files/zbyd.log 2>/dev/null" | grep -iE "$1" | tail -"${2:-6}"; }

adb connect "$S" >/dev/null 2>&1
A shell echo ok >/dev/null 2>&1 || { echo "[x] N9 $S unreachable"; exit 1; }

echo "=== PRE-FLIGHT ==="
echo "  launchermap enabled:"; A shell "dumpsys package $LM 2>/dev/null | grep -m1 'User 0:.*enabled='" | tr -d '\r'
A shell pm enable $LM >/dev/null 2>&1   # was disabled by an earlier ADAS experiment; HUD pipeline needs it
echo "  Yandex mock_location appop:"; A shell "appops get $PKG android:mock_location 2>&1" | head -1
A shell appops set $PKG android:mock_location allow >/dev/null 2>&1
echo "  agent bridge:"; A shell "ls /sdcard/zbyd/req >/dev/null 2>&1 && echo present || echo MISSING-run-deploy_agent"
echo "  dummy-location patch live? (HudDummyLocation in log on app start):"; zlog 'HudDummyLocation|setActivity' 3
echo "  baseline NaviKit position (real):"; zlog 'NAVIKIT loc' 2

echo ""
echo "=== PHASE 1 (DECISIVE): China mock -> launchermap follows, Yandex must stay REAL ==="
A shell "logcat -c" 2>/dev/null
flag mock_china true
echo "[*] mock_china=true; waiting 12s ..."
sleep 12
echo "--- HudDummyLocation state (want: enabled=true holder=true):"; zlog 'HudDummyLocation' 4
echo "--- NaviKit position now (DECISIVE):"; zlog 'NAVIKIT loc' 6
echo ""
echo "  >>> lat ~55.9 (Moscow) = Yandex REAL -> SEPARATION OK -> phase 2."
echo "  >>> lat ~39.9 (Beijing) = separation FAILED -> stop (check HudDummyLocation feed FAIL in log)."
echo "--- launchermap position (want Beijing ~39.9):"
A shell "logcat -d 2>/dev/null | grep -iE 'LocInfo|getLastKnownLocation|GpsController|39\.9|116\.4' | tail -3"

echo ""
read -rp "=== Phase 1 OK (Yandex real)? run phase 2 China-nav + map override? [y/N] " go
if [ "${go:-N}" = "y" ]; then
  echo "=== PHASE 2: China route -> launchermap nav -> onNavStart -> 0x8003 sub -> pushMap(real Yandex) ==="
  # enable HUD nav-map element via the agent (idempotent; helps the 0x8003 subscription)
  A shell "echo 'FIDSET 0x32B1102E 2' > /sdcard/zbyd/req/t_$(date +%s).cmd" 2>/dev/null
  # Beijing dest ~5km; coordinateType=2 (WGS84->GCJ02 inside China)
  DATA=$(python3 -c "import urllib.parse,json;print(urllib.parse.quote(json.dumps({'coordinateType':2,'destination':{'name':'BJ','latitude':39.95,'longitude':116.45},'waypoints':[]})))")
  A shell "am start -a android.intent.action.VIEW -d 'bydautomap://route?sourceApplication=com.zbyd.test&data=$DATA' $LM" 2>&1 | head -1
  echo "[*] route fired. If a PREVIEW shows on the IVI, tap 'Go'/导航 to start guidance (onNavStart)."
  sleep 8
  echo "--- onNavStart + pushMap ret (want ret 1->0 once subscribed):"
  A shell "logcat -d 2>/dev/null | grep -iE 'onNavStart|SomeIPDataHudManager' | tail -4"
  zlog 'pushMap fire' 5
  echo "--- IVI screencap (Yandex should show REAL Moscow; AR-HUD = photo it):"
  A shell screencap -p /sdcard/sep.png 2>/dev/null; A pull /sdcard/sep.png /tmp/n9_sep.png 2>&1 | tail -1; A shell rm -f /sdcard/sep.png 2>/dev/null
fi

echo ""
echo "=== CLEANUP: mock_china=false (real/car feed resumes) ==="
flag mock_china false
echo "[*] done. Verify NaviKit returns to real:"; sleep 3; zlog 'NAVIKIT loc' 2
