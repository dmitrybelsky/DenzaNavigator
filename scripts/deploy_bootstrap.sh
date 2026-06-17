#!/usr/bin/env bash
# Install the boot-persistence bootstrap app + boot script. On reboot its BootReceiver -> BootstrapService
# -> on-device adb -> runs zbyd_boot.sh as SHELL uid (restarts AutoHelper etc). FIRST run prompts
# "Allow debugging?" on the car screen — tap Allow (one-time; key persists). Also kicks it once now.
set -u
SERIAL="${1:-192.168.1.67:5555}"
S="$(dirname "$0")"
adb connect "$SERIAL" >/dev/null 2>&1
adb -s "$SERIAL" push "$S/patches/AutoHelper.dex" /data/local/tmp/AutoHelper.dex >/dev/null
adb -s "$SERIAL" push "$S/zbyd_boot.sh" /data/local/tmp/zbyd_boot.sh >/dev/null
adb -s "$SERIAL" shell chmod 755 /data/local/tmp/zbyd_boot.sh
adb -s "$SERIAL" install -r -t "$S/bootstrap.apk" 2>&1 | tail -1
echo "[*] trigger bootstrap now (authorize debugging on car if prompted):"
adb -s "$SERIAL" shell am start-foreground-service com.zbyd.bootstrap/.BootstrapService 2>&1 | tail -1
sleep 6
adb -s "$SERIAL" shell "run-as com.zbyd.bootstrap cat files/bootstrap.log 2>/dev/null" | tail -5
echo "[i] reboot test: adb reboot ; then helper should auto-start (after one-time authorize)"
