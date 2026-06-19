# DenzaNavigator — команды и управление

Non-root интеграция Яндекс.Навигатора с BYD/Denza N9 (DiLink 5.1). Голос — через перехват
распознавания Алисы (SpeechKit), исполнение — через shell-uid autoservice + JDWP-агент.

> ⚠️ dev/fid контролов валидированы на BYD Leopard3 (DiLink 5.0). На N9 (5.1) проверять перед доверием.
>
> ✅ **N9 live-validated (2026-06-17):** не-root запись в железо работает через
> `run-as com.byd.cameraautostudy → app_process → high-level HAL` (uid 10062 держит BODYWORK_SET,
> framework сам делает корректный нативный transact). Подтверждено физически: передняя+задняя
> электро-шторки панорамы (open/close). **Raw-autoservice запись от shell-uid на N9 НЕ актуирует**
> (нативный протокол отличается от Leopard3) — использовать HAL-путь.

---

## 🔓 Runtime-управление из Яндекса (N9, валидировано E2E)

Непривилегированный пересобранный Яндекс (`untrusted_app`) НЕ может актуировать кузов напрямую:
signature-perm `BYDAUTO_*_SET` проверяется по uid вызывающего во ВСЕХ каналах (autoservice native,
car.server `CarProperty.setProperties` → `checkCallingPermission`). Поэтому — делегирование
привилегированному процессу + кросс-доменный канал. Цепочка (вся live-подтверждена, физически
двигает шторки):

```
Яндекс (untrusted_app)
  └─ пишет команду в /sdcard/zbyd/cmd  (файл-мост)
        ▼
  HudPrivAgent в com.byd.cameraautostudy (platform_app, держит BODYWORK/INSTRUMENT/SETTING_SET)
        └─ исполняет BYDAuto*Device.<method>() своим uid → актуация + reply в /sdcard/zbyd/reply
```

**Почему так, а не иначе** (всё проверено на N9):
- abstract-socket `untrusted_app → platform_app`: SELinux **запрещает** `connectto` (probe подтвердил
  `Permission denied`). run-as демон тоже мёртв (домен `runas_app`, MLS-категория c62 ≠ c139 Яндекса).
- **файл-мост через /sdcard работает**: `/sdcard` = `mlstrustedobject` (MLS не применяется), обе
  стороны держат `MANAGE_EXTERNAL_STORAGE`. `/Android/data/` НЕ годится (Android 11 режет даже для MANAGE).

### Инжект агента (надёжный)
`jdwp_inject.py` грузит `HudPrivAgent.dex` в живой cameraautostudy через JDWP:
- poke = config-change (`cmd uimode night yes/no`) → `Handler.dispatchMessage` на main-потоке (надёжно).
- загрузка через **`InMemoryDexClassLoader`** из байтов, прочитанных из `/data/local/tmp/...dex`
  (`shell_data_file`, platform_app читает; обходит W^X-блок «writable dex» Android 11+).

```bash
adb push HudPrivAgent.dex /data/local/tmp/ && adb shell chmod 644 /data/local/tmp/HudPrivAgent.dex
python3 jdwp_inject.py 192.168.1.67:5555 com.byd.cameraautostudy /data/local/tmp/HudPrivAgent.dex
# one-time grants (персистят):
adb shell appops set com.byd.cameraautostudy MANAGE_EXTERNAL_STORAGE allow
adb shell appops set ru.yandex.yandexnavi    MANAGE_EXTERNAL_STORAGE allow
```

### Команды через файл-мост (dir-queue: `/sdcard/zbyd/req/<id>.cmd` → reply `/sdcard/zbyd/res/<id>.res`)
Клиент (`HudBridge`) атомарно публикует `req/<pid_seq>.tmp`→`.cmd`; агент исполняет + пишет `res/<id>.res`
(мультиклиент, без затирания). `HudBridge.send()` — fire-and-forget; `HudBridge.call()` — ждёт reply.

| Команда | Действие |
|---|---|
| `HAL <fqClass> <method> [intargs]` | любой BYDAuto*Device метод (сеттер или геттер) — `ret=` в reply |
| `DSET <fqClass> <fid> <val>` | raw fid write на любом устройстве |
| `RGET <fqClass> <fid>` | raw fid read |
| `BODY <method> [intargs]` | BYDAutoBodyworkDevice (`setSunshadeState 0..100`, `setHetchDoorStatus 1/2`, `setBodyWindowCtrlState <win> <act>`, `getMoonRoofConfig`…) |
| `BODYFID <fid> <val>` | raw fid на bodywork (dev 1001) — задняя шторка `1276178472` |
| `SETTING <method> [intargs]` | BYDAutoSettingDevice (`setSeatHeatingState`, `setSteeringWheelHeatingState`…) |
| `FIDSET <fid> <val>` | raw fid на instrument (dev 1007) — HUD-кластер поля |
| `CLUSTER <turn> <dist> [road]` | нав-инфо на приборку |

```bash
# ручной тест из adb shell:
mkdir -p /sdcard/zbyd/req
printf 'BODY setSunshadeState 100\n' > /sdcard/zbyd/req/t.tmp && mv /sdcard/zbyd/req/t.tmp /sdcard/zbyd/req/t.cmd
sleep 1; cat /sdcard/zbyd/res/t.res        # -> BODY setSunshadeState [100] ret=0  (передняя шторка открылась)
```

---

## 🧪 ADAS route-инъекция (ЭКСПЕРИМЕНТ, OFF по умолчанию)

> ⚠️⚠️ **БЕЗОПАСНОСТЬ.** Подаёт роут из Яндекса на SOME/IP-топик, который читает нативный ADAS. Цель —
> **L2 nav-ассист** (подсказки смены полосы/съезда идут по нашему роуту); руление остаётся в камера-вето
> нативном ADAS, это НЕ команда рулём. **Только закрытая/пустая дорога, водитель контролирует, руки на
> руле.** Не валидировано (нативный гейтинг producer/CRC) — может просто не сработать.

**Что построено** (`HudAdasRoute`, флаг `HudFlags.ADAS_ROUTE`, default **false**):
- Парсит AR-guideLine (полилиния маршрута Яндекса) → protobuf `naviSDRouteNotify` → `fireEvent` на
  `NAVI_SD_ROUTE_NOTIFY` (topic 1126084593287170, сервис NAVIGATION_SD_LINK2 3096409430228992) тем же
  no-perm ISomeIpServerInterface, что и HUD.

**Включить (осознанно, на тест-дороге):**
```bash
# 1) убрать конфликт продюсера (Amap), иначе два источника одного топика:
adb shell pm disable-user com.byd.launchermap          # вернуть: pm enable com.byd.launchermap
# 2) включить флаг (SharedPreferences пересобранного Яндекса):
adb shell "run-as ru.yandex.yandexnavi sh -c 'mkdir -p shared_prefs; cat > shared_prefs/zbyd_hud.xml <<EOF
<?xml version=\"1.0\"?><map><boolean name=\"adas_route\" value=\"true\"/></map>
EOF'"
# 3) построить маршрут в Яндексе на пустой дороге, руки на руле.
# выключить: value=\"false\" (или pm enable launchermap назад).
```

**Честные границы (research):**
- **L2** (`autoType==210`, камера): HD-карта НЕ нужна; наш роут — подсказка, руление камерное. Инъекция
  механически проходит, но примет ли нативный ADAS второго продюсера — неизвестно из Java.
- **L3/NOA** (`autoType≠210`): **жёсткий HD-блокер** — нативный ADAS валидирует `pathID`/CRC/lane-id против
  HD-карты в прошивке. Синтетический/Яндекс-роут проваливает нативную проверку. **L3 этим не включить.**
- `CHANGE_LANE_DATA_NOTIFY` — это ВЫХОД ADAS (статус для UI), инъекция спуфит только дисплей, не команду.

---

## 🛰️ NOA по маршруту Яндекса (легитимный HD-путь) — `HudFlags.ADAS_NOA`, default **ON**

> Полный reverse-engineering: **`docs/NOA_ROUTE_FEED.md`** (формат deep-link, coordinateType, file:line).

Отдаём Amap пункт назначения + плотные waypoints из полилинии Яндекса через deep-link
`bydautomap://route` → Amap строит **настоящий** HD-маршрут → штатный NOA ведёт по нему. НЕ синтетика
(её ADAS отвергает по CRC) — Amap сам валидный продюсер. `HudNoa.followRoute` (`HudEvents.java:341`),
опрос 1с, change-gate (шлём только при изменении маршрута).

Формат (верифицирован producer `NaviProxy` + parser `m1.java`):
```
bydautomap://route?sourceApplication=<pkg>&data={"coordinateType":1,
  "destination":{"name":..,"latitude":..,"longitude":..},
  "waypoints":[{"name":"","latitude":..,"longitude":..}, ...]}
```
`coordinateType=1` (no-transform) — в России GCJ02≡WGS84 (out-of-China guard, offset=0), риска нет.

**ВКЛ по умолчанию** (запрос оператора, тест на закрытой дороге). Геометрия — Amap'овская (снап via
к HD-дорогам). NOA сам ведёт по камере/радару. **Закрытая/пустая дорога, руки на руле.**

---

## 🛑 Kill-switch / внешнее управление флагами — `HudFlagReceiver`

Внешний стоп ADAS/любого флага с телефона/ноута, БЕЗ голоса, мгновенно (exported BroadcastReceiver,
регистрируется в poll-цикле). Критично для дорожного теста NOA.
```bash
# стоп только NOA
adb shell am broadcast -a com.zbyd.hud.FLAG --es key adas_noa    --ez val false
# стоп ВСЕЙ автоматики (master-гейт, гасит и NOA)
adb shell am broadcast -a com.zbyd.hud.FLAG --es key auto_master --ez val false
# re-arm NOA
adb shell am broadcast -a com.zbyd.hud.FLAG --es key adas_noa    --ez val true
```
Голосом то же: «выключи ноа» / «выключи автопилот» / «выключи автоматику».
Проверь ДО движения: лог `FLAG via broadcast adas_noa=false` в `files/zbyd.log`.

---

## ⚙️ Не-root запись в железо (N9, валидировано)

N9 не рутован, Яндекс пересобран. Запись в авто-железо обходит signature-стену так:

1. **`com.byd.cameraautostudy`** — debuggable, persistent, держит `BYDAUTO_BODYWORK_SET` /
   `INSTRUMENT_SET` / `SETTING_SET` / `POWER_SET`.
2. **`run-as com.byd.cameraautostudy app_process`** запускает наш код под uid 10062 (этого приложения) →
   нативная perm-проверка autoservice проходит.
3. Зовём **high-level HAL** `BYDAuto*Device.getInstance(ctx).<method>()` — framework делает
   правильный нативный transact (raw transact от shell на N9 даёт `ret`-мусор, не актуирует).
4. Context в app_process берём через `ActivityThread.systemMain().getSystemContext()`.

```bash
# one-shot (тест). Context + halt(0) внутри, чистый выход.
RUNAS="run-as com.byd.cameraautostudy sh -c"
DEV=android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice
adb shell "$RUNAS 'cp -f /data/local/tmp/AutoHelper.dex a.dex; \
  CLASSPATH=./a.dex app_process /system/bin com.zbyd.autohelper.AutoHelper once HAL $DEV setSunshadeState 100'"
# -> OK uid=10062 ret=0   (передняя шторка открылась)
```

| AutoHelper one-shot | Действие |
|---|---|
| `HAL <fqDeviceClass> <method> [intargs...]` | вызвать high-level HAL-метод под HAL-perm |
| `DSET <fqDeviceClass> <fid> <value>` | raw fid через protected `set(dev,fid,val)` (контролы без обёртки) |
| `CONST <class> <field>` / `DEVT <class>` | прочитать константу fid / deviceType |
| `R/W/RX/WX/WA` | raw autoservice transact (Leopard3; на N9 НЕ актуирует) |

### AC / свет / замки — через scenemodes (cameraautostudy их perm НЕ держит)
Агент в cameraautostudy покрывает BODYWORK/INSTRUMENT/SETTING/POWER (окна/шторки/багажник/люк/сиденья/
руль/кластер/SOC/свет-салона-через-SETTING). **AC/LIGHT/DOOR_LOCK_SET** у него нет, и ни один держатель
не debuggable → инжект туда невозможен.

Обход — **`com.byd.scenemodes`** (держит AC_SET+LIGHT_SET+DOOR_LOCK_SET): exported bound-сервис
`com.byd.scenemodes.mind.sdk.SceneModeManagerServices` (action `byd.scenemodes.action.start`),
`onBind = return stub` — **caller НЕ проверяется**. Яндекс (untrusted_app) биндит напрямую (Binder
пересекает домены) и зовёт `ISceneModesService.execute(int action, Bundle{mode_type})` (tx=1):
- action 0 — открыть сцену (UI), 4 — setModeState, 3/5 — open/status
- mode_type: 10104=CAMPING (вентиляция+свет+замок), 10102=NAP (климат)
- сервис применяет AC/свет/замок СВОИМ perm — signature-стена не при чём

> ⚠️ верифицировано декомпилем (exported+ungated+actuation); live-тест из untrusted_app probe — pending
> (точный mode/param-маппинг под конкретную команду «AC on» требует доразведки state-setter'ов).

### Панорама N9 = электро-шторки (не открывающийся люк!)
`getMoonRoofConfig()=8 = CONFIG_FRONT_SUNSHADE_AND_REAR_SUNSHADE`. Стекло фиксированное, двигаются шторки.

| Контрол | путь | значения |
|---|---|---|
| Передняя шторка | `HAL …bodywork.BYDAutoBodyworkDevice setSunshadeState <pct>` | 0=закр / 100=откр / 254=стоп |
| Задняя шторка | `DSET …bodywork.BYDAutoBodyworkDevice 1276178472 <pct>` | 0=закр / 100=откр |
| (moonroof) | `setMoonRoofState`/`voiceCtlMoonRoof` — `ret=0`, но **мотора нет** | — |

---

## 🎤 Голосовые команды (говоришь Алисе)

Перехват финального распознавания → разбор RU-грамматики → действие → TTS-подтверждение.
Без cloud/скилла Алисы (она параллельно делает своё; эти фразы не понимает).

### Запросы (озвучивание)
| Скажи | Ответ |
|---|---|
| «давление [в шинах]» | давление 4 колёс |
| «заряд» / «запас хода» / «сколько проеду» | SOC % + запас хода км |

### Климат / комфорт
| Скажи | Действие |
|---|---|
| «включи/выключи климат (кондиционер)» | AC on/off |
| «температура 22» | AC температура (16–30) |
| «включи/выключи подогрев руля» | руль (немедленно) |
| «включи/выключи подогрев сидений» | оба передних (немедленно) |
| «прогрей машину» / «подготовь» / «разогрей» | AC on (преднагрев по запросу) |

### Кузов / доступ
| Скажи | Действие |
|---|---|
| «открой/закрой окно [пассажира / все]» | стёкла |
| «открой/закрой люк (панораму / шторки)» | электро-шторки панорамы (перед+зад) |
| «открой/закрой багажник» | багажник |
| «заблокируй/разблокируй двери» | замки |
| «включи/выключи свет [салона]» | свет салона |
| «включи/выключи подсветку (амбиент)» | ambient |

### Тоглы автоматики и автозапуска
| Скажи | Эффект |
|---|---|
| «включи/выключи автоматику» | мастер kill-switch всех авто-правил |
| «включи/выключи автозапуск (преднагрев)» | AC на старте маршрута |
| «включи/выключи подогрев руля на старте» | флаг WHEEL_HEAT |
| «включи/выключи подогрев сидений на старте» | флаг SEAT_HEAT |
| «включи/выключи открытие панорамы на старте» | флаг PANORAMA |
| «включи/выключи ноа / автопилот / веди по маршруту» | флаг ADAS_NOA (NOA arm/abort) |

---

## 🔘 Автоматика (event-driven, все отключаемы — HudFlags)

SharedPreferences `zbyd_hud`. Дефолты: safety-positive **on**, intrusive/comfort **off**.

| Флаг | Триггер → действие | Default |
|---|---|---|
| `auto_master` | общий выключатель всей автоматики | on |
| `auto_rain` | дождь (сенсор) → закрыть окна | on |
| `auto_ambient` | поворот <150м → импульс ambient | on |
| `auto_evwarn` | маршрут > запас хода → голос-предупреждение | on |
| `auto_tpms` | низкое давление шины → голос-алерт | on |
| `autostart` | старт маршрута → AC (преднагрев) | **off** |
| `auto_seatheat` | старт маршрута → подогрев передних сидений | **off** |
| `auto_wheelheat` | старт маршрута → подогрев руля | **off** |
| `auto_panorama` | старт маршрута → открыть панораму | **off** |
| `auto_headlight` | туннель → авто-фары (unvalidated fid) | **off** |
| `adas_noa` | активный маршрут → NOA по траектории Яндекса (deep-link → Amap HD) | **on** (тест) |
| `adas_route` | публикация роута в ADAS SOME/IP (L2 эксперимент) | **off** |

Тогл вручную без голоса:
```
adb shell "run-as ru.yandex.yandexnavi sh -c 'am ...'"   # или через voice
```

---

## 🚗 autoservice helper (shell-uid) — низкоуровневые команды

Сокет `@zbyd_auto` (abstract LocalSocket). Запуск: `deploy_autohelper.sh`.

| Команда | Действие |
|---|---|
| `R <dev> <fid>` | autoservice read int |
| `W <dev> <fid> <value>` | autoservice write (tx=6) |
| `EV` | `soc/mileage/total_elec/ign` из energydata.db |

One-shot тест (без сокета):
```bash
adb shell "CLASSPATH=/data/local/tmp/AutoHelper.dex app_process /system/bin com.zbyd.autohelper.AutoHelper once EV"
adb shell "CLASSPATH=... AutoHelper once W 1001 1125122056 4"   # sunroof STOP (безопасно)
```

### Validated dev/fid карта (BYDMate Leopard3)
| Контроль | dev | fid | значения |
|---|---|---|---|
| AC on/off/temp | 1000 | 501219352/364/368 | temp 16-30 |
| AC рециркуляция/обогрев стекла | 1000 | 501219355/357 | |
| Окна вод/пас | 1001 | 1125122104/107 | open=1 close=2 |
| Окна позиция LF/LR/RF/RR | 1001 | 1276219408/416/424/432 | 0-100% |
| Люк (панорама) | 1001 | 1125122056 | open=1/close=2/tilt=3/stop=4 |
| Багажник | 1001 | setHetchDoorStatus | open=1 close=2 |
| Замки | 1001 | 1276141590 | unlock=1 lock=2 |
| Свет салона | 1023 | 1330643002 | on=2 off=1 |
| Ambient | 1023 | 1069547536 | on=5 off=1 |
| TPMS чтение LF/LR/RF/RR | 1001 | 947912728/736/744/752 | kPa |

### Через JDWP-агент (BYDAutoSettingDevice, BODYWORK)
| Контроль | команда агента |
|---|---|
| Подогрев сиденья | `SETTING setSeatHeatingState <seat> <1off/2low/3high>` |
| Вентиляция сиденья | `SETTING setSeatVentilatingState <seat> <level>` |
| Подогрев руля | `SETTING setSteeringWheelHeatingState <1off/2on>` |
| Багажник / окна / крылья | `BODY setHetchDoorStatus/...` (high-level метод) |
| Передняя шторка панорамы | `BODY setSunshadeState <0..100>` |
| Задняя шторка панорамы | `BODYFID 1276178472 <0..100>` (raw fid, dev 1001) |
| Любой fid без обёртки (bodywork) | `BODYFID <fid> <val>` |

---

## 📦 Деплой (N9, wifi-adb)

```bash
./redeploy_yandex.sh 192.168.1.67:5555   # ПОЛНАЯ пересборка+деплой: decode base.apk -> patch (новый dex) -> deploy_all
./deploy_all.sh 192.168.1.67:5555        # apk (chunked) + grant + voice-off + agent + cluster-map
./deploy_autohelper.sh                    # shell-uid autoservice helper
./deploy_agent.sh                         # JDWP агент (cluster/body/seat) в cameraautostudy
./deploy_clustermap.sh                    # Яндекс-карта на приборку (нужен debug-prop)
./disable_stock_voice.sh                  # выключить штатный ассистент (опц.)
```

> **После правки `hudhook_src` → `redeploy_yandex.sh`** (пересоберёт dex-бандл в apk). `deploy_all.sh`
> ставит уже собранный apk без re-patch. Первый прогон redeploy ~5-8 мин (decode 352M + rebuild + push).
> Не персистентно после ребута — helper/agent/masquerade перезапускать.
