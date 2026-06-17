#!/usr/bin/env bash
# Deploy + start the shell-uid autoservice helper (BYDMate-style). Runs as uid 2000 (shell) via
# app_process so the BYD "autoservice" daemon accepts its read/write transacts (per-app perm wall
# bypassed). Serves clients on abstract LocalSocket @zbyd_auto. Re-run after reboot.
set -u
SERIAL="${1:-192.168.1.67:5555}"
DEX=/data/local/tmp/AutoHelper.dex
adb connect "$SERIAL" >/dev/null 2>&1
adb -s "$SERIAL" push "$(dirname "$0")/patches/AutoHelper.dex" "$DEX" >/dev/null
adb -s "$SERIAL" shell "pkill -f autohelper.AutoHelper 2>/dev/null; rm -f /data/local/tmp/autohelper.log"
adb -s "$SERIAL" shell "CLASSPATH=$DEX nohup app_process /system/bin com.zbyd.autohelper.AutoHelper </dev/null >/data/local/tmp/autohelper.out 2>&1 &"
sleep 2
echo "[*] helper log:"; adb -s "$SERIAL" shell "cat /data/local/tmp/autohelper.log 2>/dev/null"
echo "[i] TEST (one-shot, no socket):"
echo "    adb -s $SERIAL shell \"CLASSPATH=$DEX app_process /system/bin com.zbyd.autohelper.AutoHelper once EV\""
echo "    ...once R 1000 1077936144   (read)   |   ...once W 1001 1125122056 4  (sunroof STOP — safe test)"
