# N9 test runbook — AR-HUD/HUD + location-separation

When the N9 (`192.168.1.67:5555`) is back online. Push-button order.

## 0. Pre-req
- N9 reachable over wifi-adb. Closed/empty road for any ADAS, driver supervising.
- All artifacts current: `patches/HudEvents.dex` (46 classes), `patches/HudPrivAgent.dex`, `patch_yandex.sh` (rename + voice + guidance + dummy-location step 9), `yandexnavi29/base.apk`.

## 1. Redeploy (applies the smali patches + fresh dex)
```bash
cd /Users/dmitrybelsky/projects/zbyd/build
./redeploy_yandex.sh 192.168.1.67:5555
```
Expect: `rename -> Denza Navigator`, `dummy-location: MapsLocationManagerImpl.p() -> HudDummyLocation.holder()`,
`road-events: added HudEvents as classesN.dex`, `pushed=N/N`, `Success`, agent injected.

## 2. Sanity (after Yandex relaunch + a route)
```bash
adb -s 192.168.1.67:5555 shell run-as ru.yandex.yandexnavi cat files/zbyd.log | tail -20
```
Want: `setActivity ... dex loaded`, `FlagReceiver registered`, `HudLocation: mock providers installed`,
`pushHud fire ret=0` (during nav), `NAVIKIT loc=<real Moscow ~55.9>`.

Kill-switch check (must work before any ADAS):
```bash
adb -s 192.168.1.67:5555 shell am broadcast -a com.zbyd.hud.FLAG --es key adas_noa --ez val false   # log: FLAG via broadcast
```

## 3. Location-separation test (the AR-HUD-map experiment)
```bash
./test_arhud_separation.sh 192.168.1.67:5555
```
- **PHASE 1 (decisive):** `mock_china=true` → read `NAVIKIT loc`.
  - `lat ~55.9` (Moscow) = **Yandex stayed real → SEPARATION WORKS** → continue.
  - `lat ~39.9` (Beijing) = mock won → separation failed → check `HudDummyLocation feed FAIL` in log.
  - Want: `HudDummyLocation enabled=true holder=true`; launchermap position ~39.9 (Beijing).
- **PHASE 2 (if phase 1 OK):** China route → tap "Go"/导航 on IVI → `onNavStart` → `pushMap ret 1→0` → photo the AR-HUD.
- **Cleanup:** auto `mock_china=false` → real/car feed resumes.

## Success criteria
| Output | Marker |
|---|---|
| AR-HUD arrows/dist/ETA/lanes | `pushHud fire ret=0` + visible on windshield |
| Cluster vector TBT | agent FIDSET ret=0 + cluster shows turn icon/dist/lanes |
| Separation | `NAVIKIT loc` stays Moscow under `mock_china` |
| AR-HUD map image | `pushMap fire ret=0` + real Yandex map on windshield (photo) |

## Abort / revert
- ADAS/NOA: `am broadcast -a com.zbyd.hud.FLAG --es key adas_noa --ez val false` (or `auto_master`).
- Separation: `mock_china=false` (cleanup does it).
- launchermap re-enable if needed: `pm enable com.byd.launchermap`.

## Known fragilities (device-side verify)
- `HudDummyLocation` reflection (MapKit `Location` ctor / `DummyLocationManager.setLocation`) — fails closed (holder null → real source). Watch for `HudDummyLocation feed FAIL`.
- `s.p()` patch — if `feed FAIL`, Yandex falls back to spoof-detect separation (backstop).
- pushMap (0x8003) — auto-disables after 20× ret≠0 (5.1 may lack a HUD map element; the map appears only with a launchermap nav session).
- China route deep-link previews → needs a "Go" tap to start nav (onNavStart).
