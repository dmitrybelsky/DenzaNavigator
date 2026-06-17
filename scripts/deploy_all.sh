#!/usr/bin/env bash
# Full N9 deploy: chunked-push the patched Yandex (wifi-adb too unstable for a 338M single push) +
# grant mock-location + disable stock voice assistant + JDWP-inject the privileged HUD/cluster agent +
# set up the cluster-map masquerade. Idempotent; re-run after a car reboot (agent + projection are not
# persistent). Usage: ./deploy_all.sh [serial]
set -u
SELF="$(cd "$(dirname "$0")" && pwd)"
SERIAL="${1:-192.168.1.67:5555}"
APK="$SELF/yandexnavi29/yandexnavi-29.4.2-patched-signed.apk"
PKG=ru.yandex.yandexnavi
A(){ adb -s "$SERIAL" "$@"; }
recon(){ for i in 1 2 3 4 5; do adb connect "$SERIAL" >/dev/null 2>&1; A shell echo ok >/dev/null 2>&1 && return 0; sleep 2; done; return 1; }

recon || { echo "[x] cannot reach $SERIAL"; exit 1; }
MD5=$(md5 -q "$APK")
echo "[*] apk md5=$MD5"

echo "=== 1. chunked push + install ($PKG) ==="
rm -rf /tmp/ynchunks && mkdir -p /tmp/ynchunks
split -b 20m "$APK" /tmp/ynchunks/part_
A shell 'rm -f /data/local/tmp/yn.apk /data/local/tmp/part_* 2>/dev/null'
ok=0; n=$(ls /tmp/ynchunks/part_* | wc -l | tr -d ' ')
for f in /tmp/ynchunks/part_*; do
  b=$(basename "$f")
  for try in 1 2 3 4 5 6; do recon; A push "$f" /data/local/tmp/$b >/dev/null 2>&1 && { ok=$((ok+1)); break; }; sleep 2; done
done
echo "    pushed=$ok/$n"
CAR_MD5=$(A shell 'cd /data/local/tmp && cat part_* > yn.apk && rm -f part_* && (md5sum yn.apk 2>/dev/null||toybox md5sum yn.apk)' | awk '{print $1}')
[ "$CAR_MD5" = "$MD5" ] || { echo "[x] md5 mismatch car=$CAR_MD5"; exit 1; }
echo "    md5 OK; installing"
A shell pm install -r -d -t /data/local/tmp/yn.apk 2>&1 | tail -1
A shell appops set $PKG android:mock_location allow
A shell run-as $PKG rm -f /data/data/$PKG/files/zbyd.log 2>/dev/null

echo "=== 2. disable stock voice assistant ==="
"$SELF/disable_stock_voice.sh" "$SERIAL"

echo "=== 3. relaunch Yandex (so socket clients/agent can connect) ==="
A shell am force-stop $PKG
A shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 4; echo "    pid=$(A shell pidof $PKG)"

echo "=== 4. JDWP-inject privileged HUD/cluster agent ==="
"$SELF/deploy_agent.sh" "$SERIAL"

echo "=== 5. cluster-map masquerade (setprop + projection) ==="
"$SELF/deploy_clustermap.sh" "$SERIAL"

echo "=== DONE. Build a route on the N9, then check: ==="
echo "    HUD: arrows+icon+dist+ETA+speed-limit+map ; cluster: guide-text+ETA/mileage+map(right)"
echo "    logs: adb -s $SERIAL shell run-as $PKG cat files/zbyd.log | tail"
