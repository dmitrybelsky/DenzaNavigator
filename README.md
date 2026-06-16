# DenzaNavigator

Non-root modification of **Yandex Navigator** for **BYD / Denza N9** (DiLink 5.1) head units — bring
Yandex navigation to the car's **windshield AR-HUD + instrument cluster**, feed the car's own
**RTK/INS position** back into Yandex (jam-resistant, lane-level), speak prompts through the car's TTS,
and remove ads/paywall. Plus an **on-device patcher** that re-applies the whole mod to any future Yandex
update, right on the car, non-root.

> Everything here is **non-root**: read the world-readable installed APK, patch it (dexlib2 + binary
> AXML), re-sign (apksig), reinstall. No system partition, no bootloader, no Magisk.

---

## What it does

### Yandex → car (display the navigation)
- **Windshield AR-HUD (SOME/IP topic 665)** — maneuver, road name, distance, ETA, AR road-arrow.
- **SD-map SOME/IP** — weather, tunnel, traffic-light, parking, lanes.
- **Instrument cluster via BYDAuto HAL** (`BYDAutoInstrumentDevice`) — maneuver + road name + **cameras**
  (`sendSimpleGuidanceInfo` / `sendNextPathName` / `sendCameraGuidanceInfo`). This is a TIER-1 channel
  that bypasses the launchermap vsomeip owner-gate, so Yandex turns show even while the stock AMap nav owns
  the HUD topics.
- **Voice** — Yandex maneuvers spoken through the car's TTS engine + **media ducking** (standard audio focus).

### car → Yandex (better positioning)
- **`HudLocation`** overrides the phone/head-unit GPS with the car's fused **RTK + INS** position via the
  Android mock-location provider (appop-granted, non-root) → beats GPS jamming, centimeter-class accuracy,
  lane-level. Fed by `HudLocationCar` (subscribes the car's SOME/IP positioning topics + reads BYDAuto HAL).
- **Rain sensor → weather**, **turn-signal → lane confirmation**, speed/gear/steering from the HAL.

### App fixes
- **Anti-tamper bypass** for Yandex 29.4.2+ (its native MapKit attestation rejects re-signing; setting
  `android:debuggable="true"` disables the guard).
- **Login fix** — re-signed app can't co-own the shared `com.yandex.passport` account type; namespace it
  to `com.yandex.passport.zbyd` (the patcher *detects* the conflict and logs it).
- **Paywall bypass**, **ad removal** (strip the Mobile Ads init provider + NSC ad-domain block),
  **dup-permission strip** (so install doesn't fail on shared Yandex permissions).

### Region note (RF)
AMap/AutoNavi cloud + China-HD-map channels (electronic horizon ADASISV2, NOA alarms, the stock
traffic-light countdown) **do not work outside China** and are NOT used. Hardware channels (GNSS/RTK/INS,
camera lane-lines, CAN sensors) work anywhere. Traffic-light countdown therefore relies on the bundled
OCR (`HudTlOcr` + `HudDigits`).

---

## Repository layout

```
hudhook_src/com/zbyd/hudhook/   in-process mod (reflection over NaviKit/MapKit; SOME/IP + HAL publishers)
  HudEvents        guidance reflection -> maneuver/road/dist/lanes/incidents/cameras
  HudSomeIp        in-process SOME/IP publisher (665 + SD-map)
  HudInstrumentHal Yandex -> cluster via BYDAutoInstrumentDevice (TIER-1, no owner-gate)
  HudLocation      car position -> Yandex via Android mock location
  HudLocationCar   subscribe car SOME/IP positioning (RTK/INS/VEHICLE_POSITION/LANE) + BYDAuto HAL
  HudWeatherApi    in-process api.weather.yandex.ru fetch (KEY redacted — see below)
  HudTts/HudAudio  car-voice prompts + media ducking
  HudTlOcr/HudDigits  traffic-light countdown OCR (no SDK source for it)
  HudSensors       BYDAuto rain/slope/blinker
  ...
scripts/
  patch_yandex.sh         PC patcher (apktool + d8 + apksigner) — full feature set incl. resource edits
  patch_weatherplugin.sh  patch ru.yandex.weatherplugin (alternate weather source)
patcher/                  ON-DEVICE patcher (Android app, dexlib2 + AXML + apksig) — re-patches after OTA
patches/network_security_config.xml   telemetry/ad TLS block
docs/                     reverse-engineering findings
```

---

## Prerequisites

- **JDK 17** (`openjdk@17`)
- **Android cmdline-tools / build-tools 34** (`aapt2`, `d8`, `zipalign`, `apksigner`)
- **apktool 3.x** (for the PC script `patch_yandex.sh`)
- For the **on-device patcher** build: `smali-dexlib2` 3.0.9 + `guava` + `jsr305` + `apksig`
  (all ship inside the Android cmdline-tools `lib/external` + build-tools `lib/apksigner.jar`)
- A signing keystore you generate yourself (see below). **No keystore is committed.**
- The **Yandex Weather API key** — extract the `X-Yandex-API-Key` value from `ru.yandex.weatherplugin`
  and paste it into `HudWeatherApi.java` (placeholder `PUT-YANDEX-WEATHER-KEY-HERE`). In-process use only.

### Generate a keystore
```bash
keytool -genkeypair -keystore zbyd-debug.keystore -storepass android -keypass android \
  -alias zbyd -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=zbyd, OU=local, O=zbyd, L=zbyd, ST=zbyd, C=US"
```

---

## Build & install (PC path)

```bash
# 1. build the HudEvents.dex from hudhook_src (javac + d8 against android.jar)
# 2. pull the installed Yandex apk, then:
apktool d yandexnavi.apk -o /tmp/yn          # full decode (text manifest)
./scripts/patch_yandex.sh /tmp/yn yandexnavi-patched-signed.apk
# 3. uninstall stock (signature differs), install patched, then on the car:
adb shell appops set ru.yandex.yandexnavi android:mock_location allow
```
`patch_yandex.sh` is signature-based, so the smali hooks survive obfuscation moves between versions.

## Build & use (on-device patcher)

```bash
cd patcher && ./build_patcher.sh        # produces zbyd-patcher.apk
adb install -t zbyd-patcher.apk
```
On the car: open **zbyd Yandex Patcher**, tap **Патчить**. It reads the installed Yandex, applies every
patch (dexlib2 smali + AXML manifest + bundled `HudEvents.dex` + apksig), and **logs each step** to
`Android/data/com.zbyd.patcher/files/patch.log` — a moved/failed target is reported, never silent.
Output: `yandexnavi-zbyd-patched.apk` (uninstall stock, install it).

On-device coverage: paywall, guidance hook, speed-limit, OCR-activity, debuggable, ads-provider strip,
dup-permission, dex append, re-sign. Resource-table edits (passport account-type namespace, ad-domain NSC)
need the PC `patch_yandex.sh` — the on-device patcher *detects + logs* the passport conflict.

---

## Security / legal

- **No secrets in this repo**: signing keys and the Yandex Weather API key are excluded/redacted — supply
  your own. The weather key is intended for in-process use inside a Yandex app only; do not reuse externally.
- For **personal, authorized** use on your own vehicle. Reverse-engineering notes are for interoperability.
