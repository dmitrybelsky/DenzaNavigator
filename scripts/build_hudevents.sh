#!/usr/bin/env bash
# Build hudhook_src -> HudEvents.dex (the in-process mod bundled into the patched Yandex / the patcher).
# Requires JDK 17 + android build-tools (d8) + android.jar. Set ANDROID_JAR / BT if your paths differ.
set -euo pipefail
SELF="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
BT="${BT:-/opt/homebrew/share/android-commandlinetools/build-tools/34.0.0}"
ANDROID_JAR="${ANDROID_JAR:-/opt/homebrew/share/android-commandlinetools/platforms/android-34/android.jar}"
OUT="${1:-$SELF/HudEvents.dex}"

# NOTE: paste your Yandex Weather key into hudhook_src/.../HudWeatherApi.java (PUT-YANDEX-WEATHER-KEY-HERE)
grep -rq 'PUT-YANDEX-WEATHER-KEY-HERE' "$SELF/hudhook_src" && \
  echo "[!] weather key not set in HudWeatherApi.java — weather feature will be inert until you add it"

rm -rf /tmp/hud_cls /tmp/hud_dex; mkdir -p /tmp/hud_cls /tmp/hud_dex
"$JAVA_HOME/bin/javac" -source 8 -target 8 -cp "$ANDROID_JAR" -d /tmp/hud_cls \
  "$SELF"/hudhook_src/com/zbyd/hudhook/*.java
"$BT/d8" --min-api 26 --output /tmp/hud_dex /tmp/hud_cls/com/zbyd/hudhook/*.class
cp /tmp/hud_dex/classes.dex "$OUT"
echo "[ok] $OUT ($(wc -c < "$OUT") bytes)"
