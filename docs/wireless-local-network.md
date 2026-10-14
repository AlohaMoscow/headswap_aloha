# targetSdk 37 и ACCESS_LOCAL_NETWORK: проверка на эмуляторе Android 17

Дата: 2026-10-14. Сборка: debug, ветка `claude/wireless-e1-transport`, compileSdk/targetSdk 37. Эмулятор AVD `A17` (API 37). Решение Егора: переходим на 37 до Э2.

## Что в коде

- `build.gradle.kts`: compileSdk 37, targetSdk 37 (из stash `d24587f3`, там же отладочная команда `aa_probe`, `aa/AaProbe.kt`).
- Манифест: `android.permission.ACCESS_LOCAL_NETWORK`.
- Отладочные команды `MainActivity` (`--es cmd …`): `net_probe` (`link/diag/NetProbe.kt`: маяк UDP «CarlifeHost» на 7999 по broadcast каждого IPv4-интерфейса и unicast `--es to <ip>`, ответчик UDP 8999, всё в `WL`), `net_perm` (системный запрос разрешения).
- debug tcp6: `--es debug_tcp6_bind 0.0.0.0` слушает не только loopback; состояние разрешения пишется в `WL link begin`.
- Онбординг и запрос разрешения у пользователя: Э4 (вместе с `NEARBY_WIFI_DEVICES` и BT).

## Что платформа говорит о разрешении

`pm list permissions`: `ACCESS_LOCAL_NETWORK`, `protectionLevel:dangerous`, группа `NEARBY_DEVICES`. Системный диалог при запросе общий для группы: «Allow Headswap to find, connect to, and determine the relative position of nearby devices?». После «Allow» `granted=true, USER_SET`.

## Результаты

Связь с ПК через NAT эмулятора (`adb emu redir`): ПК виден телефону как 10.0.2.2, то есть как узел локальной сети, а не loopback.

| Проверка | Без разрешения | С разрешением |
|---|---|---|
| Маяк UDP 7999: broadcast 10.0.2.255 (eth0, wlan0) и unicast 10.0.2.2 | `sendto failed: EPERM` на каждой отправке | отправлено, ПК принял 8 из 8 |
| Ответчик UDP 8999: датаграмма с ПК | не пришла ни одна (0 из 8), ошибок нет | 8 из 8, ответ JSON готовности дошёл до ПК |
| Входящий TCP на 6 портов (bind 0.0.0.0, ГУ = pc-headunit `--transport tcp6`) | bind успешен, ПК «подключился» (это NAT эмулятора), но до приложения не дошёл ни один accept: тишина без ошибок | 6/6 за ~3 с с 10.0.2.2, рукопожатие, видео ~30 fps |
| tcp6 на 127.0.0.1 через `adb forward` | 6/6, видео 30 fps | — |
| AA-сервер 127.0.0.1:5277 | работает (проверено 2026-10-12, `aa_probe`) | — |

## Выводы для Э2 и Э6

1. Loopback разрешения не требует: USB-режим (AA на 5277) и debug-стенд через `adb forward` работают на 37 без него.
2. Беспроводные ветки без разрешения не работают совсем: маяк и ответ на 8999 падают с EPERM, входящий TCP от ГУ молча не доходит. Входящий отказ ничем не виден приложению, поэтому диагностика обязана писать состояние разрешения (уже в `WL link begin`) и показывать подсказку, если за N с нет ни одного accept при `ACCESS_LOCAL_NETWORK=denied`.
3. Разрешение запрашивается одним диалогом группы «Устройства поблизости» вместе с `NEARBY_WIFI_DEVICES` и BT: в онбординге Э4 один шаг.
4. На Android 16 и ниже (телефоны большинства тестеров, Redmi Егора) разрешение не действует: проверено только поведение Android 17.

## Чего не проверяли

Реальный Wi-Fi (хотспот, P2P-интерфейс), реальное устройство на Android 17, поведение при отзыве разрешения посреди сессии.
