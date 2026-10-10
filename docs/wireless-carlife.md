# Беспроводной CarLife на Honda Vezel 2024: проверено, не работает

Итог: беспроводного CarLife у этой ГУ нет. Остаётся только USB (AOA).

ГУ штатная: Honda Vezel 2024, Китай, CarLife 2.0, HU-приложение 1.1.0.

## Как устроен беспроводной режим у официального MD

Источник: декомпилированный Baidu CarLife MD 8.6.7, классы `com.baidu.carlife.core.connect.auto.*`.

- Телефон держит RFCOMM-сервер, имя записи в SDP — `carlife`. Слушает два UUID:
  - SPP `00001101-0000-1000-8000-00805F9B34FB`;
  - CarLife `a45bc7e5-bb50-4949-9de1-f78299cf6d78`.

  Подключается к нему ГУ (`BluetoothClient`).
- Кадр BT-канала устроен как cmd-канал USB. Заголовок 8 байт: `len u16 BE`, `reserved u16`, `serviceType u32 BE`. За ним `len` байт protobuf (`BluetoothConnect.readData`).
- Первым после accept пишет телефон: `WIRLESS_INFO_REQUEST` 0x00100001. Дальше идёт такой обмен:

  | Сообщение | Код |
  |---|---|
  | `INFO_RESPONSE` | 0x00108002 |
  | `TARGETINFO_REQUEST` | 0x00100004 |
  | `TARGETINFO_RESPONSE` | 0x00108005 |
  | `REQUEST_IP` | 0x00108006 |
  | `RESPONSE_IP` | 0x00100007 |
  | `MD_STATUS` | 0x00100008 |
  | `HU_STATUS` | 0x00108009 |

  Поля protobuf:
  - InfoResponse: `wirlessType=1`, `wifiFrequency=2`. Типы 2, 3, 4 означают беспроводное подключение.
  - TargetInfo: `wifiDeviceName=1`, `targetInfo=2`, `scanRecord=3`, `p2pNetInfo=4`.

  После обмена — Wi-Fi Direct и TCP-каналы, см. [carlife-protocol.md](carlife-protocol.md).
- UDP 7999 «CarlifeHost»: его шлёт телефон, когда сам раздаёт хотспот (`ConnectManager.sendHotspotUdp`). ГУ его слушает.

## Что пробовали, 2026-10-10

Делали диагностическую сборку Headswap с пассивной пробой. Она ничего не отвечала ГУ, только слушала:
- 4 RFCOMM-сервера: SPP и CarLife, каждый в secure- и insecure-варианте;
- SDP у спаренных устройств;
- BT-события;
- Wi-Fi Direct (пиры и сервисы);
- Wi-Fi scan;
- UDP 7999.

Ветку с пробой после эксперимента удалили. Код восстанавливается по этому описанию.

Прогоны:
1. С USB-кабелем, телефон давно спарен. От ГУ только HFP, A2DP и PBAP.
2. Без кабеля. Пару удалили и создали заново при работающей пробе, слушали 7 минут.
   - Ни одного входящего RFCOMM.
   - Единственное RFCOMM от ГУ по логам BT-стека — `UUID=0x111f`, HFP.
   - От ГУ ни P2P-пира, ни точки доступа, ни датаграмм на UDP 7999.
3. Официальный Baidu CarLife на телефоне подключался к ГУ только по USB. Беспроводных попыток в логкате нет. Своё логирование у Baidu выключено.

SDP-записи ГУ («Honda HFT»): SPP, OPP, A2DP-sink, AVRCP, PANU, HFP, MAP-MNS. CarLife-UUID и AA-wireless `4de17a00-…` среди них нет.

Логи прогонов: `logs/car-wprobe/`.

## Что видно на самой ГУ

- В CarLife на ГУ: «Помощь» (справа сверху), затем Android или iPhone. В обоих разделах первым пунктом идёт тип подключения, и там только USB. Выбора Wi-Fi нет.
- Пункт Wi-Fi Hotspot в настройках ГУ не включается без авторизации в Honda Connect. В ряде стран Honda Connect не работает. Поэтому схема BT + Wi-Fi, как в штатном беспроводном Android Auto, на этой ГУ тоже не получится.
