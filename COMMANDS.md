# DenzaNavigator — команды и управление

Non-root интеграция Яндекс.Навигатора с BYD/Denza N9 (DiLink 5.1). Голос — через перехват
распознавания Алисы (SpeechKit), исполнение — через shell-uid autoservice + JDWP-агент.

> ⚠️ dev/fid контролов валидированы на BYD Leopard3 (DiLink 5.0). На N9 (5.1) проверять перед доверием.

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
| «открой/закрой люк» | панорама-крыша |
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
| Багажник / люк / окна / крылья | `BODY setHetchDoorStatus/setMoonRoofState/...` |

---

## 📦 Деплой (N9, wifi-adb)

```bash
./deploy_all.sh 192.168.1.67:5555        # apk (chunked) + grant + voice-off + agent + cluster-map
./deploy_autohelper.sh                    # shell-uid autoservice helper
./deploy_agent.sh                         # JDWP агент (cluster/body/seat) в cameraautostudy
./deploy_clustermap.sh                    # Яндекс-карта на приборку (нужен debug-prop)
./disable_stock_voice.sh                  # выключить штатный ассистент (опц.)
```

> Не персистентно после ребута — helper/agent/masquerade перезапускать.
