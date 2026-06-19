# NOA route-feed — Yandex → Amap HD → нативный NOA

Reverse-engineering запись: как отдать маршрут Yandex штатному NOA через **легитимный HD-путь**
(deep-link в launchermap), без root, без синтетического HD (который нативный ADAS отвергает по CRC).

Все находки — против декомпиляции `n9-20260615-085743` (jadx/apktool). Цитаты `file:line` проверяемы.

---

## 1. Два разных пути подачи маршрута

| Путь | Канал | Гейтинг | Статус |
|---|---|---|---|
| **ADAS_ROUTE** (`HudAdasRoute`) | SOME/IP `NAVI_SD_ROUTE_NOTIFY` напрямую в ADAS | нативный producer/CRC валидация — синтетику отвергает | эксперимент, **L2 only**, не подтверждён |
| **ADAS_NOA** (`HudNoa`) ✅ | deep-link `bydautomap://route` → Amap строит **настоящий** HD-маршрут → ADAS ведёт NOA по нему | нет — Amap сам валидный продюсер | рабочий код-путь, runtime не проверен |

Ключевая идея NOA-пути: **не подсовываем HD-роут сами**. Даём Amap пункт назначения + промежуточные
waypoints, выдернутые из полилинии Yandex. Amap строит ВАЛИДНЫЙ HD-маршрут (с настоящими link/lane-id),
нитью через эти via. NOA ведёт по нему. Плотные via → Amap-маршрут прижимается к траектории Yandex
(там где у Amap есть HD-покрытие).

---

## 2. Формат deep-link (ВЕРИФИЦИРОВАН producer + consumer)

### Producer (эталонный отправитель) — `com.byd.nfvc`
`NaviProxy.startNavigation(...)` строит ровно этот URI:
```
jadx/com.byd.nfvc/sources/com/byd/nfvc/logic/navi/NaviProxy.java:417-431
```
```java
String json = gson.toJson(new NavigationData(destination, waypoints, coordinateType));
String str  = "bydautomap://route?sourceApplication=" + sourceApplication + "&data=" + json;
intent.setData(Uri.parse(str));
intent.setPackage(appName.get());   // целевой пакет = активная карта (N9 = com.byd.launchermap)
context.startActivity(intent, ...);
```

### Consumer (парсер) — `com.byd.launchermap`
```
jadx/com.byd.launchermap/sources/k/k/c/z/m1.java:196-227
```
```java
String host = intent.getData().getHost();                    // "route"
String data = intent.getData().getQueryParameter("data");    // JSON (getQueryParameter авто-декодит)
JSONObject o = new JSONObject(data);
int i2          = o.getInt("coordinateType");
JSONObject dst  = o.getJSONObject("destination");
String name     = dst.getString("name");                     // ОБЯЗАТЕЛЕН (getString бросает если null)
double lat      = dst.getDouble(TrackReportField.LATITUDE);  // "latitude"
double lon      = dst.getDouble(TrackReportField.LONGITUDE); // "longitude"
JSONArray wps   = o.getJSONArray("waypoints");               // ОБЯЗАТЕЛЕН (минимум пустой [])
// ... каждый waypoint: name (обяз.), latitude, longitude
```

### Манифест (зарегистрирован, exported)
```
apktool/com.byd.launchermap/AndroidManifest.xml:184
<data android:host="route" android:scheme="bydautomap"/>
```
Активность: `com.byd.automap.activity.MainActivity`, `exported="true"`, `launchMode="singleTask"`,
`action.VIEW` + `category.DEFAULT`. **singleTask** → повторный intent идёт в `onNewIntent` (без релонча).

### Итоговая схема
```
bydautomap://route?sourceApplication=<pkg>&data=<URLencoded JSON>

JSON = {
  "coordinateType": <int>,                                    // см. §3
  "destination": {"name": <str>, "latitude": <dbl>, "longitude": <dbl>},
  "waypoints":  [{"name": <str>, "latitude": <dbl>, "longitude": <dbl>}, ...]   // плотные via
}
```
- Ключи `"latitude"`/`"longitude"` подтверждены: `TrackReportField.LATITUDE/LONGITUDE` (`TrackReportField.java:7,9`).
- Поля POI: name/latitude/longitude/**address**. `address` парсером игнорится → в нашем JSON опускаем.
- Producer кодит raw-конкатенацией; мы делаем `Uri.encode(json)` (парсер декодит обратно) — безопаснее
  против `&`/`#`/пробелов в именах. Via-имена пустые (`""`) — стабилизирует change-сигнатуру.

---

## 3. coordinateType — семантика и почему Россия безопасна

Потребитель координат — `m1.v(POI, lon, lat)`:
```
jadx/com.byd.launchermap/sources/k/k/c/z/m1.java:401-422
```
```java
if (poi.getCoordinateType() == 2) { dB=d(lon,lat); dA=c(lon,lat); }   // WGS84 -> GCJ02 (add offset)
else {
  if (poi.getCoordinateType() != 3) {                                 // else (0/1/...) -> БЕЗ трансформа
     lonLatToMap(lon, lat); return;                                   // координаты verbatim (GCJ02-native)
  }
  dB=b(lon,lat); dA=a(lon,lat);                                       // ==3 -> BD09 -> GCJ02
}
```
`c()/d()` = `lat/lon + Coord.transformWGS84ToGCJ02Lat/Lon(...)` (`m1.java:38-43`).

**Out-of-China guard** в трансформе:
```
jadx/com.byd.launchermap/sources/com/autosdk/bussiness/pilot/data/Coord.java:155,230,240
boolean outOfChina(lon,lat) = lon<72.004 || lon>137.8347 || lat<0.8293 || lat>55.8271;
transformWGS84ToGCJ02Lat/Lon: if (outOfChina) return 0;   // вне Китая сдвиг = 0
```

**Вывод для России:** Москва lon~37 (<72.004) → `outOfChina=true` → offset=0. Значит coordinateType
**1 (no-transform) и 2 (WGS84→GCJ02) дают идентичный результат** — географического риска нет.
Шлём `coordinateType=1` (no-transform, Yandex WGS84 verbatim). Это `COORD_NATIVE` в `HudNoa`.

> Строковый вариант (другой deep-link `bydmap/direction`) использует `coord_type="gcj02ll"`
> (`HandleRequest.java:341`) — подтверждает, что BYD-экосистема по умолчанию GCJ02.

---

## 4. Реализация — `HudNoa.followRoute`

`hudhook_src/com/zbyd/hudhook/HudNoa.java`. Вызов из `HudEvents.java:341` (poll-цикл, 1с).

- Источник геометрии: `HudEvents.fullGuideLine(route, curSeg)` — **вся** оставшаяся полилиния до финала
  (НЕ 20-точечное AR-окно `forwardGuideLine` — это был баг, dest оказывался в 20 сегментах впереди).
- dest = последняя точка; waypoints = равномерный сэмпл интерьера (`MAX_WAYPOINTS=16`).
- **Change-gate**: квантованная сигнатура (dest+via, округление `q()` до ~11м). Шлём ТОЛЬКО при
  изменении маршрута (съезд/reroute/проезд via). Стабильный маршрут → 1 отправка. `MIN_INTERVAL_MS=1000`
  пол. Причина: каждая отправка = Amap RE-PLAN; спам перепланирований сбрасывал бы активный NOA-маневр.

Тюнинг-константы (правка → пересборка dex):
| Константа | Смысл |
|---|---|
| `MIN_INTERVAL_MS=1000` | жёсткий пол частоты |
| `q(v)*1e4` | гранулярность сигнатуры: 1e4=11м, 1e5=1.1м (чаще), 1e3=110м (реже) |
| `MAX_WAYPOINTS=16` | плотность via (предел приёма Amap — проверить live) |
| `COORD_NATIVE=1` | coordinateType (no-transform) |

---

## 5. Границы (честно)
- **Runtime не проверен** (машина offline): фактический приём launchermap'ом, что Amap реально строит
  HD-маршрут по via и публикует в ADAS, что NOA по нему едет. Код-путь доказан, поведение — нет.
- Геометрия — Amap'овская (снап каждой via к ближайшей HD-дороге). Via лишь прижимают, не пиксель-в-пиксель.
- NOA сам ведёт по камере/радару (вето). Синтетику не подаём — путь легитимный.
- **L3 синтетикой не включить** — отдельный HD-блокер (pathID/CRC/lane-id против прошивочной HD-карты).
