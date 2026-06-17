#!/usr/bin/env bash
# Deploy the cluster-map masquerade app + flip the cluster ProjectionService to debug mode so it
# whitelists/launches our package (com.byd.cluster.projection.mapdemo) instead of the stock map app.
# Renders the live Yandex map (streamed from the patched Yandex over @zbyd_cluster_map) on the
# instrument-cluster nav area. Re-run after a car reboot. Needs the debug prop to be shell-settable;
# if the readback below is empty, it requires root.
set -u
SERIAL="${1:-192.168.1.67:5555}"
adb connect "$SERIAL" >/dev/null 2>&1

echo "[*] setprop persist.debug.cluster.projection 1 (non-root test)"
adb -s "$SERIAL" shell setprop persist.debug.cluster.projection 1 2>&1
echo "    readback = '$(adb -s "$SERIAL" shell getprop persist.debug.cluster.projection 2>/dev/null)'  (expect 1)"

echo "[*] install masquerade app"
adb -s "$SERIAL" install -r -t "$(dirname "$0")/clustermap.apk" 2>&1 | tail -1

echo "[*] restart ProjectionService so it re-reads the map package in onCreate()"
adb -s "$SERIAL" shell am force-stop com.example.amapservice 2>/dev/null
adb -s "$SERIAL" shell am force-stop com.byd.projection.management 2>/dev/null

echo "[*] start cluster-map bridge service"
adb -s "$SERIAL" shell am start-foreground-service com.byd.cluster.projection.mapdemo/.ClusterMapService 2>&1 | tail -1

sleep 2
echo "[*] logs (our service + projection service):"
adb -s "$SERIAL" shell "logcat -d 2>/dev/null | grep -iE 'ZBYD_CLUSTERMAP|HwProjectionService|mapdemo' | tail -25"
