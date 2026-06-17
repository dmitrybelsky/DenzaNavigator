#!/system/bin/sh
# zbyd boot bootstrap — runs as SHELL uid (started by BootstrapService via on-device adb).
pkill -f autohelper.AutoHelper 2>/dev/null
CLASSPATH=/data/local/tmp/AutoHelper.dex nohup app_process /system/bin com.zbyd.autohelper.AutoHelper </dev/null >/data/local/tmp/autohelper.out 2>&1 &
am start-foreground-service com.byd.cluster.projection.mapdemo/.ClusterMapService 2>/dev/null
echo "zbyd_boot done $(date)"
