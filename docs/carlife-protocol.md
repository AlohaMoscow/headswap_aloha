# Протокол Baidu CarLife (проводной Android / AOA)

Спецификация составлена по открытым исходникам CarLife (список ниже) и по наблюдениям за реальной ГУ. Первая часть (разделы 1-10) выведена чтением кода открытых репозиториев (протокол версии 1.0); что именно делает ГУ с CarLife 2.0, см. [carlife-v2-handshake.md](carlife-v2-handshake.md).

## 0. Как читать

Пометки у утверждений:

- **[К]** подтверждено кодом (ссылка path:line рядом).
- **[Д]** догадка, вывод из косвенных признаков или внешних знаний; в коде прямого подтверждения нет.

Сокращения путей (репозитории публичные на GitHub):

| Алиас | Путь |
|---|---|
| `HUJ` | [milesking/apollo-DuerOS](https://github.com/milesking/apollo-DuerOS), `CarLife-Android-Vehicle/src/com/baidu/carlifevehicle` (HU-приложение Baidu, Java, роль HU) |
| `HUC` | там же, `CarLife-Vehicle-Lib/LibSource` (HU-библиотека C++) |
| `PB` | `HUC/protobuf/proto` (все `.proto`, proto2) |
| `DOC` | там же, `CarLife-Vehicle-Lib/CarLifeVehicleLib动态链接库使用说明.docx` (описание API) |
| `MDL` | [Doris-duo/CarLife-MD-Lib](https://github.com/Doris-duo/CarLife-MD-Lib), `LibSource` |
| `CP` | [aa112901/CarProjection](https://github.com/aa112901/CarProjection), `app/src/main/java/com/projection/car` (MD-клиент без root, работает по AOA) |

Состав источников и что в них на самом деле:

- `HUJ` и `HUC`: реализация стороны **HU**. Это протокол версии 1.0 (`PROTOCOL_VERSION_MAJOR=1`, `MINOR=0`, `HUJ/CommonParams.java:79-82`), эпохи 2015-2017.
- `MDL`: порт `HUC` под MD. Отличие от `HUC` только в четырёх добавленных функциях отправки данных MD→HU по каналам video/media/vr/tts (`MDL/core/CTranRecvPackageProcess.cpp:3184-3279`, плюс объявления в `MDL/include/CConnectManager.h` и `CTranRecvPackageProcess.h`). Функций отправки CMD-сообщений от MD там нет. Как референс MD-логики библиотека бесполезна. [К]
- `CP`: единственная рабочая открытая реализация MD по AOA. Она поверхностна: отвечает на 5 сообщений, остальное игнорирует. [К] (`CP/MsgProcess.java:444-575`)
- `icar`: в каталоге только `README.md` и картинки, исходников протокола нет. README: «Carlife 8.4.2», использует настоящее приложение CarLife на телефоне («参考MultiService服务获取Carlife状态和触摸事件», «系统权限基于Shizuku», «音频同步使用蓝牙传输车机»). То есть icar не реализует протокол, а паразитирует на оригинальном приложении Baidu. Для спецификации ничего не даёт; косвенно подтверждает, что в реальных ГУ звук может идти по Bluetooth, а не по USB (см. §7.4). [К] README репозитория [yenole/icar](https://github.com/yenole/icar).
- Android Auto (aasdk, headunit-revived) к CarLife не относится, см. [aa-headunit.md](aa-headunit.md).

Прикладные сообщения везде кодируются Protocol Buffers **proto2** (в `.proto` почти все поля `required`).

---

## 1. AOA (USB Accessory)

Роль HU = USB host (в нашем тесте — PC). Телефон (MD) переводится в accessory mode.

### 1.1 Идентификационные строки [К]

`HUJ/connect/AOAHostSetup.java:71-76`:

| index (wIndex) | Поле | Значение |
|---|---|---|
| 0 | manufacturer | `Baidu` |
| 1 | model | `CarLife` |
| 2 | description | `Baidu CarLife` |
| 3 | version | `1.0.0` |
| 4 | URI | `http://carlife.baidu.com/` |
| 5 | serial | `0720SerialNo.` |

Строки отправляются как `String.getBytes()`, **без завершающего NUL** (`AOAHostSetup.java:511-538`). Длина = wLength. [К]

MD-клиент CarProjection матчится по accessory filter `manufacturer="Baidu" model="CarLife" version="1.0.0"` (`CP/../res/xml/accessory_filter.xml:4-7`, подключён в `AndroidManifest.xml` через `USB_ACCESSORY_ATTACHED`). [К] Значит, наше MD-приложение должно объявить такой же filter (лучше только manufacturer+model, чтобы не зависеть от версии). Какие строки шлёт настоящая ГУ Honda, неизвестно [Д]: логично, что те же, раз CarProjection работает с реальными ГУ.

### 1.2 Порядок запросов HU [К]

Все запросы идут как vendor control transfer на EP0, таймаут 0 (бесконечный) (`AOAHostSetup.java:425-439`, `TIME_OUT=0` на :64).

| Шаг | Запрос | bmRequestType | bRequest | wValue | wIndex | Данные | Источник |
|---|---|---|---|---|---|---|---|
| 1 | GET_PROTOCOL | `0xC0` (IN\|VENDOR) | 51 | 0 | 0 | читаем 2 байта, версия LE: `buf[1]<<8 \| buf[0]`; допустимо 1 или 2, иначе отказ | :495-509, :66 |
| 2 | SEND_IDENT x6 | `0x40` (OUT\|VENDOR) | 52 | 0 | 0..5 | строка (таблица 1.1) | :511-538, :67 |
| 3 | START_ACCESSORY | `0x40` | 53 | 0 | 0 | нет | :540-547, :68 |

Прочее:

- `AOA_AUDIO_SUPPORT = 58` объявлена (:69), но **нигде не вызывается**. HID-запросы (54-57) отсутствуют. Звука по AOA-audio нет. [К]
- Перед этим HU пропускает устройства: Apple (VID `0x05AC`) и устройства с единственным интерфейсом mass storage (class 8, subclass 6, protocol 80) (`AOAHostSetup.java:203-219`, `HUJ/util/CarlifeUtil.java:54-58,390-407`). [К]
- Если устройство уже в accessory mode (см. 1.3), шаги 1-3 пропускаются (`AOAHostSetup.java:249-296`).
- После `START_ACCESSORY` телефон переэнумерируется. HU сканирует список USB каждую секунду (`AOAConnectManager.java:39,225-229`), максимум 10 попыток переключения и 5 пустых сканов после переключения (`AOAHostSetup.java:103-104,146-158,256-262`). [К]

### 1.3 VID/PID после переключения [К]

`AOAHostSetup.java:47-55,441-472`: HU считает устройство в accessory mode, если VID = `0x18D1` и PID один из `0x2D00` (accessory), `0x2D01` (accessory+adb), `0x2D02` (audio), `0x2D03` (audio+adb), `0x2D04` (accessory+audio), `0x2D05` (accessory+audio+adb). Константа `PID_ACCESSORY = 0x2D05` объявлена, но в логике не используется.

В нашем тесте ожидаем `18D1:2D00` или `18D1:2D01` (у телефона с включённым ADB). [Д] по спецификации AOA, не по коду.

### 1.4 Интерфейс, эндпоинты, буферы

- HU берёт интерфейс `getInterface(0)`, `claimInterface(force=true)` (`AOAHostSetup.java:239-241`). [К]
- Эндпоинты берёт **по индексу**: `getEndpoint(0)` = IN, `getEndpoint(1)` = OUT (:62-63,272-273). Константы адресов `0x81` / `0x02` объявлены (:60-61), но не используются. [К] Для pyusb находить эндпоинты по направлению (`usb.util.endpoint_direction`), не по индексу. [Д]
- Все передачи bulk. Размер куска **16 КиБ**: `AOA_MAX_BUFFER_BYTES = 16*1024` (:98); и чтение, и запись длиннее 16 КиБ режутся на куски по 16 КиБ (:327-350, :378-397). [К]
- Заголовок канала (8 байт, см. §2) HU пишет **отдельным bulk-transfer**, потом тело отдельным(и) (`bulkTransferOut(head,8,msg,len)`: две последовательные записи, :413-423). При чтении так же: сначала ровно 8 байт, потом тело (`AOAConnectManager.java:266-290`). [К]
- Максимум одного канального сообщения: `AOA_MAX_BYTES = 64 MiB`, минимум 8 (`AOAConnectManager.java:45,277`). [К]
- [Д] Почему это важно: у Android-устройства чтение accessory fd возвращает данные по границам USB-transfer; остаток не влезшего в буфер transfer теряется (ядро `f_accessory`, `BULK_BUFFER_SIZE=16384`; в открытых исходниках CarLife этого нет, это внешнее знание). MD-клиент CarProjection делает `read(8)` затем `read(msgLen)` (`CP/MsgProcess.java:447-455`) и работает только если HU присылает заголовок и тело отдельными transfer. Поэтому фейковая HU **обязана** слать 8-байтный внешний заголовок отдельной bulk-записью, тело следом отдельной записью (до 16384 байт на запись). Для MD-приложения: читать по кругу до нужного числа байт.

---

## 2. Мультиплексирование каналов по одной bulk-паре

### 2.1 Внешний заголовок (только USB/AOA) [К]

Каждое сообщение любого канала в обоих направлениях предваряется 8 байтами, big-endian:

```
 0       4       8
 +-------+-------+----------------------------+
 | chan  | len   |  inner message (len bytes) |
 | u32BE | u32BE |                            |
 +-------+-------+----------------------------+
```

- `chan`: id канала (таблица 2.2). HU принимает только 1..6 (`AOAConnectManager.java:277`).
- `len`: длина «внутреннего сообщения» (внутренний заголовок + данные), без этих 8 байт. HU считает `lenMsgHead + lenMsgData` (`AOAConnectManager.java:493-495`).
- Доказательства: сборка на стороне HU `AOAConnectManager.java:341-364,493,505`; разбор на стороне HU :266-314; на стороне MD (CarProjection) `Utils.java:19-22`, `MsgProcess.java:198-201,448-452,612-617`: `head[3]=channel`, `intToBytes2(len, head, 4)`.

### 2.2 Идентификаторы каналов [К]

`HUJ/CommonParams.java:450-455`, `HUC/include/CTranRecvPackageProcess.h:50-57`:

| id | Имя | Внутренний заголовок | Кто пишет |
|---|---|---|---|
| 1 | CMD | 8 байт | оба |
| 2 | VIDEO | 12 байт | MD→HU |
| 3 | MEDIA (музыка) | 12 байт | MD→HU |
| 4 | TTS (навигационные подсказки) | 12 байт | MD→HU |
| 5 | VR (голос) | 12 байт | MD→HU: звук ответов; HU→MD: микрофон |
| 6 | TOUCH/CTRL | 8 байт | HU→MD (кроме редкого `UI_ACTION_SOUND`) |

### 2.3 Внутренние заголовки [К]

Канал CMD и TOUCH (8 байт) (`HUJ/connect/CarlifeCmdMessage.java:29-99`, `HUC/core/CTranRecvPackageProcess.cpp:348-420,1591-1621`):

```
0       2       4               8
+-------+-------+---------------+---------------+
| len   | resv  | service type  | data (len B)  |
| u16BE | u16BE | u32BE         |               |
+-------+-------+---------------+---------------+
```

`len` — длина protobuf-данных (максимум 32 КиБ: `MSG_CMD_MAX_DATA_LEN`, `CommonParams.java:63`; буфер C++ 40 КиБ, `CTranRecvPackageProcess.h:30`). `resv` всегда 0 (`CarlifeCmdMessage.java:36`; в C++ байты не инициализируются, `new u8[]`, на практике 0) [К/Д].

Каналы VIDEO/MEDIA/TTS/VR (12 байт):

```
0       4       8       12
+-------+-------+-------+----------------+
| len   | time  | type  | data (len B)   |
| u32BE | u32BE | u32BE |                |
+-------+-------+-------+----------------+
```

`len` — длина данных (без 12 байт). `time` — метка времени (см. §5). Подтверждено у HU (`CTranRecvPackageProcess.cpp:348-420`, `HUJ/util/DecodeUtil.java:296-320`, `PackageHeadAnalyseModule.java:75-97`) и у MD (`CP/Utils.java:144-166`).

Итого, на проводе для канала VIDEO: `[00 00 00 02][outerLen u32]` + `[dataLen u32][ts u32][00 02 00 01]` + H.264. Для канала CMD: `[00 00 00 01][outerLen u32]` + `[dataLen u16][00 00][type u32]` + protobuf.

### 2.4 Отличие от TCP/Wi-Fi-режима [К]

- В TCP-режиме **внешнего заголовка нет**. Каждый канал это отдельное TCP-соединение, поток внутренних сообщений идёт как есть.
- Порты (`HUJ/CommonParams.java:425-437`, `HUC/include/CConnectManager.h:23-44`): MD слушает 7240 (CMD), 8240 (VIDEO), 9240 (MEDIA), 9241 (TTS), 9242 (VR), 9340 (TOUCH). Для Wi-Fi HU подключается клиентом к этим портам телефона. Для ADB-режима C++-библиотеки HU делает `adb forward tcp:7200 tcp:7240` и т.д., подключаясь к локальным 7200, 8200, 9200, 9201, 9202, 9300.
- В **AOA-режиме** Java-HU сама поднимает локальные TCP-серверы на 127.0.0.1:7200/8200/9200/9201/9202/9300 (`AOAConnectManager.java:133-157`), подключается к ним как клиент с теми же портами (`ConnectManager.java:122-131,298-351`) и «качает» байты между этими сокетами и USB, добавляя/снимая внешний заголовок. Внутренние сообщения при этом идентичны TCP-режиму. Для нас важно только: на USB есть внешний заголовок, внутри всё как в TCP. Порты нам не нужны.
- «Подключение завершено» у HU = поднято 6 сокетов (`TOTAL_SOCKET=6`, `ConnectManager.java:87,469-475`); после этого HU сразу шлёт `HU_PROTOCOL_VERSION`. Между открытием accessory и подключением сокетов HU спит 1 с (`AOAHostSetup.java:286-291`). [К]

---

## 3. Сообщения CMD-канала (id канала 1)

Направление закодировано в самом коде: бит `0x8000` в младшем слове = HU→MD, нет бита = MD→HU. Исключение: `GEAR_INFO` (0x00010029, HU→MD, бит не стоит). [К] по перечислению `HUC/include/CTranRecvPackageProcess.h:63-165` и `HUJ/CommonParams.java:89-234`.

Колонка «proto» дана по `PB/*.proto`; «пусто» = `len=0`, данных нет.

| Код | Имя | proto | Напр. | Примечание |
|---|---|---|---|---|
| 0x00018001 | HU_PROTOCOL_VERSION | CarlifeProtocolVersion{majorVersion,minorVersion} | HU→MD | HU шлёт `{1,0}` первым (`CarlifeProtocolVersionInfoManager.java:51-57,79-92`) |
| 0x00010002 | PROTOCOL_VERSION_MATCH_STATUS | CarlifeProtocolVersionMatchStatus{matchStatus} | MD→HU | 1=match, 2=not match (`CommonParams.java:82-83`) |
| 0x00018003 | HU_INFO | CarlifeDeviceInfo | HU→MD | поля Build.* HU (`CarlifeDeviceInfoManager.java:51-94`) |
| 0x00010004 | MD_INFO | CarlifeDeviceInfo | MD→HU | HU только сохраняет; проверяет `sdk_int >= 21` для AOA (`CarlifeActivity.java:502-518`) |
| 0x00018005 | HU_BT_OOB_INFO (C++: HU_BT_PAIR_INFO) | CarlifeBTPairInfo | HU→MD | |
| 0x00010006 | MD_BT_OOB_INFO | CarlifeBTPairInfo | MD→HU | |
| 0x00018007 | VIDEO_ENCODER_INIT | CarlifeVideoEncoderInfo{width,height,frameRate} | HU→MD | |
| 0x00010008 | VIDEO_ENCODER_INIT_DONE | CarlifeVideoEncoderInfo | MD→HU | реальные размеры энкодера |
| 0x00018009 | VIDEO_ENCODER_START | пусто | HU→MD | |
| 0x0001800A | VIDEO_ENCODER_PAUSE | пусто | HU→MD | HU шлёт в `onPause` (`CarlifeActivity.java:285`) |
| 0x0001800B | VIDEO_ENCODER_RESET | пусто | HU→MD | в Java-HU не используется |
| 0x0001800C | VIDEO_ENCODER_FRAME_RATE_CHANGE | CarlifeVideoFrameRate{frameRate} | HU→MD | адаптивный fps (`DecodeUtil.java:336-345,655-667`) |
| 0x0001000D | VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE | CarlifeVideoFrameRate | MD→HU | Java-HU не обрабатывает |
| 0x0001800E | PAUSE_MEDIA (устарело) | пусто | HU→MD | |
| 0x0001800F | CAR_VELOCITY | CarlifeCarSpeed | HU→MD | |
| 0x00018010 | CAR_GPS | CarlifeCarGps | HU→MD | |
| 0x00018011 | CAR_GYROSCOPE | CarlifeGyroscope | HU→MD | |
| 0x00018012 | CAR_ACCELERATION | CarlifeAcceleration | HU→MD | |
| 0x00018013 | CAR_OIL | CarlifeOil | HU→MD | |
| 0x00010014 | TELE_STATE_CHANGE_INCOMING | пусто | MD→HU | |
| 0x00010015 | TELE_STATE_CHANGE_OUTGOING | пусто | MD→HU | |
| 0x00010016 | TELE_STATE_CHANGE_IDLE | пусто | MD→HU | |
| 0x00010017 | TELE_STATE_CHANGE_INCALLING | пусто | MD→HU | |
| 0x00010018 | SCREEN_ON | пусто | MD→HU | |
| 0x00010019 | SCREEN_OFF | пусто | MD→HU | |
| 0x0001001A | SCREEN_USERPRESENT | пусто | MD→HU | |
| 0x0001001B | FOREGROUND | пусто | MD→HU | CarProjection шлёт сразу после INIT_DONE (`MsgProcess.java:636-646`) |
| 0x0001001C | BACKGROUND | пусто | MD→HU | HU показывает экран-заглушку (`CarlifeActivity.java:530-536`) |
| 0x0001801D | LAUNCH_MODE_NORMAL | пусто | HU→MD | |
| 0x0001801E | LAUNCH_MODE_PHONE | пусто | HU→MD | |
| 0x0001801F | LAUNCH_MODE_MAP | пусто | HU→MD | |
| 0x00018020 | LAUNCH_MODE_MUSIC | пусто | HU→MD | |
| 0x00010021 | GO_TO_DESKTOP | пусто | MD→HU | |
| 0x00010022 | MIC_RECORD_WAKEUP_START | пусто | MD→HU | |
| 0x00010023 | MIC_RECORD_END | пусто | MD→HU | |
| 0x00010024 | MIC_RECORD_RECOG_START | пусто | MD→HU | |
| 0x00018025 | GO_TO_FOREGROUND | пусто | HU→MD | `CarlifeUtil.java:295-301` |
| 0x00010026 | MODULE_STATUS | CarlifeModuleStatusList{cnt, repeated CarlifeModuleStatus{moduleID,statusID}} | MD→HU | см. 4.9 |
| 0x00018027 | STATISTIC_INFO | CarlifeStatisticsInfo | HU→MD | |
| 0x00018028 | MODULE_CONTROL | CarlifeModuleStatus{moduleID,statusID} | HU→MD | |
| 0x00010029 | GEAR_INFO | CarlifeGearInfo{gear} | HU→MD | только C++ |
| 0x00010030 | NAVI_NEXT_TURN_INFO | CarlifeNaviNextTurnInfo | MD→HU | |
| 0x00010031 | CAR_DATA_SUBSCRIBE (Java: _REQ) | CarlifeVehicleInfoList | MD→HU | |
| 0x00010032 (C++) / 0x00018032 (Java) | CAR_DATA_SUBSCRIBE_DONE / _RSP | CarlifeVehicleInfoList | HU→MD | **расхождение кодов** между `HUC/.../CTranRecvPackageProcess.h:125` и `HUJ/CommonParams.java:210`; [К] оба значения есть в коде, какое из двух ждёт реальный MD, неизвестно |
| 0x00010033 | CAR_DATA_START_REQ (C++: SUBSCRIBE_START) | CarlifeVehicleInfoList (Java парсит как одиночный CarlifeVehicleInfo) | MD→HU | |
| 0x00010034 | CAR_DATA_STOP_REQ (C++: SUBSCRIBE_STOP) | то же | MD→HU | |
| 0x00010035 | MEDIA_INFO | CarlifeMediaInfo | MD→HU | |
| 0x00010036 | MEDIA_PROGRESS_BAR | CarlifeMediaProgressBar{progressBar} | MD→HU | |
| 0x00010037 | CONNECT_EXCEPTION | CarlifeConnectException{exceptionType} | MD→HU | |
| 0x00010038 | REQUEST_GO_TO_FOREGROUND | пусто | MD→HU | HU отвечает `GO_TO_FOREGROUND` |
| 0x00010039 | UI_ACTION_SOUND | пусто | MD→HU | |
| 0x00010040 | BT_HFP_REQUEST | CarlifeBTHfpRequest | MD→HU | |
| 0x00018041 | BT_HFP_INDICATION | CarlifeBTHfpIndication | HU→MD | |
| 0x00018042 | BT_HFP_CONNECTION | CarlifeBTHfpConnection | HU→MD | |
| 0x00018043 | CARLIFE_DATA_SUBSCRIBE | CarlifeSubscribeMobileCarLifeInfoList | HU→MD | только C++ |
| 0x00010044 | CARLIFE_DATA_SUBSCRIBE_DONE | то же | MD→HU | только C++ |
| 0x00018045 | CARLIFE_DATA_SUBSCRIBE_START | то же | HU→MD | только C++ |
| 0x00018046 | CARLIFE_DATA_SUBSCRIBE_STOP | то же | HU→MD | только C++ |
| 0x00010047 | NAVI_ASSITANTGUIDE_INFO | CarlifeNaviAssitantGuideInfo | MD→HU | только C++ |
| 0x00018048 | HU_AUTHEN_REQUEST | CarlifeAuthenRequest{randomValue:string} | HU→MD | см. 4.3 |
| 0x00010049 | MD_AUTHEN_RESPONSE | CarlifeAuthenResponse{encryptValue:string} | MD→HU | |
| 0x0001804A | HU_AUTHEN_RESULT | CarlifeAuthenResult{authenResult:bool} | HU→MD | |
| 0x0001004B | MD_AUTHEN_RESULT | CarlifeAuthenResult{authenResult:bool} | MD→HU | в комментариях C++ ошибочно «0x0001804B», в коде и Java 0x0001004B (DOC, changelog: «修复MSG_CMD_MD_AUTHEN_RESULT消息的错误定义») |
| 0x0001004C | GO_TO_FOREGROUND_RESPONSE | пусто | MD→HU | MD шлёт, если за 5 с не перешёл на передний план (DOC) |
| 0x0001004D | START_BT_AUTOPAIR_REQUEST | CarlifeBTStartPairReq | MD→HU | |
| 0x0001804E | BT_HFP_RESPONSE | CarlifeBTHfpResponse | HU→MD | |
| 0x0001004F | BT_HFP_STATUS_REQUEST | CarlifeBTHfpStatusRequest{type} | MD→HU | в основном запрос состояния микрофона ГУ (DOC) |
| 0x00018050 | BT_HFP_STATUS_RESPONSE | CarlifeBTHfpStatusResponse | HU→MD | |
| 0x00010051 | MD_FEATURE_CONFIG_REQUEST | пусто | MD→HU | |
| 0x00018052 | HU_FEATURE_CONFIG_RESPONSE | CarlifeFeatureConfigList{cnt, repeated {key:string, value:int32}} | HU→MD | |
| 0x00018053 | BT_START_IDENTIFY_REQ | CarlifeBTStartIdentifyReq | HU→MD | |
| 0x00010054 | BT_IDENTIFY_RESULT_IND | CarlifeBTIdentifyResultInd | MD→HU | |
| 0x00018055 | ERROR_CODE | CarlifeErrorCode{errorCode:string} | HU→MD | |
| 0x00018056 | VIDEO_ENCODER_JPEG | пусто | HU→MD | «шли JPEG вместо H.264» (DOC) |
| 0x00010057 | VIDEO_ENCODER_JPEG_ACK | пусто | MD→HU | |
| 0x00010058 | BT_HFP_CALL_STATUS_COVER | CarlifeBTHfpCallStatusCover{state,phoneNum,name} | MD→HU | только Java, в C++ «deprecated» |
| 0x00010059 | MD_EXIT | пусто | MD→HU | HU считает соединение разорванным (`CarlifeActivity.java:855-857`) |
| 0x0001006A | MD_RSA_PUBLIC_KEY_REQUEST | пусто | MD→HU | только Java, §8 |
| 0x0001806B | HU_RSA_PUBLIC_KEY_RESPONSE | CarlifeHuRsaPublicKeyResponse{rsaPublicKey:string} | HU→MD | |
| 0x0001006C | MD_AES_KEY_SEND_REQUEST | CarlifeMdAesKeyRequest{aesKey:string} | MD→HU | |
| 0x0001806D | HU_AES_REC_RESPONSE | пусто | HU→MD | |
| 0x0001006E | MD_ENCRYPT_READY | пусто | MD→HU | |

Источники таблицы: имена и коды `HUJ/CommonParams.java:89-234`, C++-перечисление `HUC/include/CTranRecvPackageProcess.h:63-165`, направления по send-функциям `HUC/core/CTranRecvPackageProcess.cpp:1630-3131` и по обработчикам приёма `:420-878,971-1590`. Файлы `.proto` для Java-only сообщений (`HuRsaPublicKeyResponse`, `MdAesKeyRequest`, `BTHfpCallStatusCover`) лежат только как сгенерированный Java (apollo-DuerOS, `CarLife-Android-Vehicle/src/com/baidu/carlife/protobuf/*.java`, дескрипторы внутри). Их схемы: `rsaPublicKey = 1 (required string)`, `aesKey = 1 (required string)`, `state = 1 (int32), phoneNum = 2 (string), name = 3 (string)` (все required). [К]

Ключевые тела для минимума (все поля varint/строка proto2):

- `CarlifeProtocolVersion`: `08 <major> 10 <minor>`; для {1,0}: `08 01 10 00`.
- `CarlifeProtocolVersionMatchStatus`: `08 <status>`; match = `08 01`.
- `CarlifeVideoEncoderInfo`: `08 <width> 10 <height> 18 <frameRate>` (varint). 1280x720, fps 0: `08 80 0A 10 D0 05 18 00`.
- `CarlifeAuthenResult`: `08 01` (true).
- `CarlifeTouchAction`: `08 <action> 10 <x> 18 <y>`.
- `CarlifeCarHardKeyCode`: `08 <keycode>`.
- `CarlifeMusicInit` / `CarlifeTTSInit`: `08 <sampleRate> 10 <channelConfig> 18 <sampleFormat>`.
- `CarlifeStatisticsInfo`: cuid(1,string), versionName(2,string), versionCode(3,int32), channel(4,string), connectCount(5,int32), connectSuccessCount(6,int32), connectTime(7,int32), crashLog(8,optional string).
- `CarlifeDeviceInfo` (23 полей, все optional): os(1), board(2), bootloader(3), brand(4), cpu_abi(5), cpu_abi2(6), device(7), display(8), fingerprint(9), hardware(10), host(11), cid(12), manufacturer(13), model(14), product(15), serial(16), codename(17), incremental(18), release(19), sdk(20, string), sdk_int(21, int32), token(22), btaddress(23).
- Примеры целиком: см. §9.

---

## 4. Handshake по шагам

Последовательность взята из Java-HU (`CarlifeActivity.java`), C++-тестера (`HUC/CarLifeLibTest.cpp`) и MD-клиента CarProjection. Что делает реальный MD Baidu, исходников нет; поведение фиксируют только его сообщения, которые обрабатывает HU.

### 4.1 Точка старта

1. HU делает AOA-переключение (§1), открывает 6 локальных сокетов, спит 1 с, подключает их (§2.4). [К]
2. HU **первым** шлёт `HU_PROTOCOL_VERSION`. Даже C++-тестер для всех сценариев сначала шлёт её: «it is necessary to send MSG_CMD_HU_PROTOCOL_VERSION (0x00018001) message» (`HUC/CarLifeLibTest.cpp:253-293`). [К] Условие в Java: для типа соединения AOA (`!= USBMUXD`) (`CarlifeActivity.java:341-346`).

### 4.2 Версия протокола

- HU→MD `HU_PROTOCOL_VERSION {major=1, minor=0}`.
- MD→HU `PROTOCOL_VERSION_MATCH_STATUS {matchStatus}`. 1 = совпало, 2 = нет. CarProjection отвечает безусловно 1 (`MsgProcess.java:466-473`). [К]
- Если HU получил не 1: рвёт соединение (`CarlifeActivity.java:465-470`). [К]
- Сообщения `MD_PROTOCOL_VERSION` в коде **нет**: версию MD HU не получает. [К]

### 4.3 Аутентификация (AUTHEN)

Что есть в источниках:

- **API HU**: `cmdHuAuthenRequest(randomValue)` → 0x00018048; `cmdHuAuthenResult(bool)` → 0x0001804A; приём 0x00010049 и 0x0001004B (`HUC/core/CTranRecvPackageProcess.cpp:2542-2610`, `HUC/libwrapper/CCarLifeLib.cpp:530-546`). [К]
- **Задуманная схема** (DOC, раздел Command channel): HU генерирует случайную строку и шлёт в `HU_AUTHEN_REQUEST`; MD шифрует её **закрытым ключом** и возвращает в `MD_AUTHEN_RESPONSE.encryptValue`; HU проверяет (открытым ключом) и шлёт `HU_AUTHEN_RESULT`; MD шлёт `MD_AUTHEN_RESULT`. После успешного «рукопожатия» MD запрашивает у HU функции (`MD_FEATURE_CONFIG_REQUEST`, DOC: «双方握手认证通过后，手机端会发送该消息来请求车机端的功能定制项»). Алгоритм, формат ключей и кодировка строки в коде не показаны. [К по DOC; детали Д]
- **Java-HU Baidu (`HUJ`) аутентификацию не реализует**: `HU_AUTHEN_REQUEST`, `MD_AUTHEN_RESPONSE`, `HU_AUTHEN_RESULT`, `MD_AUTHEN_RESULT` встречаются только в `CommonParams.java` (grep по `HUJ`). Ни отправки, ни обработки. [К]
- **C++-тестер**: вызов `cmdHuAuthenRequest(&authenRequest)` закомментирован, `randomValue = "CarLife_1.0.0"` (`HUC/CarLifeLibTest.cpp:130,264-266`); обработчик ответа только печатает `encryptValue` (:738-741). [К]
- **CarProjection (MD)**: `HU_AUTHEN_REQUEST` не обрабатывается вообще (в `switch` по CMD-типам нет такого case, `MsgProcess.java:465-545`), `MD_AUTHEN_RESPONSE` не шлётся. Единственное, что есть: сразу после `STATISTIC_INFO` MD отправляет `MD_AUTHEN_RESULT {result=true}` (`MsgProcess.java:524-541`; его локальный `.proto` называет поле `result`, номер 1, тип bool, wire-совместимо с `authenResult`, `CarProjection/app/src/main/proto/CarlifeAuthenResultProto.proto`). Это даёт рабочее соединение с реальными ГУ, судя по назначению проекта. [К о коде, Д о «рабочем»]
- **icar**: исходников нет.
- Вывод для нас: если HU не шлёт `HU_AUTHEN_REQUEST`, MD просто шлёт `MD_AUTHEN_RESULT{true}` после `STATISTIC_INFO` (поведение CarProjection). Наблюдение на реальной ГУ Honda Vezel 2024: `HU_AUTHEN_REQUEST` она не присылает, `MD_AUTHEN_RESULT{true}` ей достаточно, шифрование не включается. Если другая ГУ потребует подписанный ответ, приложение с ней не заработает. См. §10.

### 4.4 Статистика и информация об устройствах

После `MATCH_STATUS = 1` HU делает в таком порядке (`CarlifeActivity.java:439-457`):

1. запускает монитор «сердцебиения» (4.8);
2. `STATISTIC_INFO` (HU→MD) (`CarlifeUtil.java:303-326`);
3. `HU_INFO` (HU→MD) (`CarlifeDeviceInfoManager.java:108-121`);
4. `VIDEO_ENCODER_INIT` (HU→MD) (см. 4.5), **кроме** случая, когда у HU включено `CONTENT_ENCRYPTION` и `AES_ENCRYPT_AS_BEGINE=false`: тогда HU ждёт `MD_ENCRYPT_READY` (§8);
5. запускает декодер, плеер PCM, touch;
6. `MODULE_CONTROL {moduleID=5 (CONNECT), statusID=2 (AOA)}` (`ConnectManager.java:659-671`, `ModuleStatusModel.java:43`).

`MD_INFO` MD шлёт по собственной инициативе; CarProjection отвечает им на `HU_INFO` (`MsgProcess.java:474-495`). HU `MD_INFO` лишь сохраняет и проверяет `sdk_int >= LOLLIPOP`; иначе через 2 с показывает «AOA не поддерживается» (`CarlifeActivity.java:502-518`). Поэтому в `MD_INFO` нужно ставить `sdk_int >= 21`. [К]

### 4.5 Видео: INIT / INIT_DONE / START

- HU→MD `VIDEO_ENCODER_INIT {width, height, frameRate}`. Java-HU: `width/height` = размер своей SurfaceView в пикселях, `frameRate = 0` («if 0, means no limit to frame rate», `CarlifeUtil.java:233-248`; вызов `CarlifeActivity.java:447`). C++-тестер: 768x480@30 (`CarLifeLibTest.cpp:120`). [К]
- MD→HU `VIDEO_ENCODER_INIT_DONE {width, height, frameRate}`: **реальные** размеры, с которыми MD будет кодировать. HU использует **эти** значения для инициализации MediaCodec и как «phone container» для пересчёта координат касаний (`CarlifeActivity.java:472-494`). То есть MD имеет право ответить другим разрешением (например, подогнанным по соотношению сторон). CarProjection отвечает эхом присланного `msgdata` (`MsgProcess.java:515`; сформированный перед этим `builder` не используется). [К]
- HU после INIT_DONE: `initDecoder(surface, w, h)`; шлёт `VIDEO_ENCODER_START`; шлёт `LAUNCH_MODE_*` (`CarlifeActivity.java:486-497`). [К]
- MD после `VIDEO_ENCODER_START` начинает слать видео. CarProjection на `START` сначала отправляет `MEDIA_INIT` (48000/2/16), затем запускает MediaProjection+MediaCodec (`MsgProcess.java:651-670`). [К]
- В `onResume` HU шлёт `VIDEO_ENCODER_START` без предшествующего `INIT` (`CarlifeActivity.java:231-233`); в `onPause` `VIDEO_ENCODER_PAUSE` (:285). MD должен переживать повторные `START`/`PAUSE`. [К]

### 4.6 Foreground / background

- MD→HU `FOREGROUND` (пусто): HU, если декодер готов, через 2 с показывает видео (`CarlifeActivity.java:537-542`). CarProjection шлёт его сразу после `INIT_DONE` (`MsgProcess.java:636-646`).
- MD→HU `BACKGROUND`: HU показывает заглушку (`CarlifeActivity.java:530-536`).
- Декодер считается готовым после вывода 16 кадров (`DecodeUtil.java:555,616`, `mOutputCount >= 16`), после чего HU показывает экран проекции. [К]

### 4.7 Feature config

- MD→HU `MD_FEATURE_CONFIG_REQUEST` (пусто) → HU→MD `HU_FEATURE_CONFIG_RESPONSE {cnt, [key,value]...}` (`HUJ/logic/FeatureConfigManager.java:87-138`). Если у HU нет ни одной записи, он **ничего не отвечает** (`getFeatureConfigCount() > 0` на :94). [К]
- Ключи Java-HU (`FeatureConfigManager.java:117-137`, имена `CarlifeConfUtil.java:166-188`): `VOICE_WAKEUP`, `BLUETOOTH_AUTO_PAIR`, `BLUETOOTH_INTERNAL_UI`, `FOCUS_UI`, `VOICE_MIC` (0 = микрофон ГУ, 1 = микрофон телефона, 2 = нет), `MEDIA_SAMPLE_RATE` (0 = исходная, 1 = 48 кГц), `AUDIO_TRANSMISSION_MODE`, `CONTENT_ENCRYPTION`, `ENGINE_TYPE`, `INPUT_DISABLE`. Значения берутся из зашифрованного конфига `assets/bdcf` (содержимое не читалось). [К по ключам; значения Д]
- CarProjection `MD_FEATURE_CONFIG_REQUEST` не шлёт (нет в коде), то есть работает с настройками HU по умолчанию. [К]
- Реальный MD обычно шлёт этот запрос после успешной аутентификации (DOC). [К по DOC]

### 4.8 Heartbeat

- Отдельного CMD-heartbeat нет. «Сердцебиение» живёт на канале VIDEO: MD шлёт видео-сообщения; сообщение с `len = 0` HU считает heartbeat (`DecodeUtil.java:320-323`: «empty package, used for heart beat»), его тип `MSG_VIDEO_HEARTBEAT = 0x00020002` (`CommonParams.java:198`, обработка `HUC/core/CTranRecvPackageProcess.cpp:746`). [К]
- HU считает вызовы чтения видео-сокета (`numOfHeartBeat`, `ConnectManager.java:521-528`) раз в секунду; для AOA (не Wi-Fi/tether/usbmuxd) рвёт соединение после 6 подряд тиков без чтения (таймер стартует через 5 с, затем 1 с; `ConnectManager.java:585-633`), то есть примерно 11 с после последнего видео-чтения. [К]
- Итого MD должен слать в VIDEO хотя бы раз в ~5 с сообщение: кадр или пустой `VIDEO_HEARTBEAT`. CarProjection специально повторяет кадры (`KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100000 us`, `MediaCodecTool.java:74`), а heartbeat не шлёт. Период настоящего MD неизвестен. [Д]
- Разрыв: HU при `MD_EXIT` и при любой ошибке чтения/записи (`ConnectClient.setIsConnected(false)`).

### 4.9 Модули (MODULE_STATUS)

MD→HU `MODULE_STATUS {cnt, [moduleID,statusID]...}` при смене состояния модулей. Идентификаторы (`HUJ/model/ModuleStatusModel.java:20-48`): PHONE=1 (0 idle, 1 incoming, 2 outgoing), NAVI=2 (0 idle, 1 start, 2 stop), MUSIC=3 (0 idle, 1 running), VR=4 (0 idle, 1 running), CONNECT=5 (HU→MD, только iOS-тип подключения; Java-HU шлёт `2` для AOA), MIC=6 (0 микрофон ГУ, 1 микрофон телефона, 2 не поддерживается). HU сохраняет статусы и реагирует: телефон — заглушка «идёт вызов», VR — голосовой UI. Для минимума MD можно `MODULE_STATUS` не слать. [К]

### 4.10 Прочее

- `STATISTIC_INFO.cuid` — ID ГУ; CarProjection показывает его пользователю как «VIS ID» (`MsgProcess.java:526-537`).
- `HU_LOG = 0x7FFF0001` существует только локально внутри Java-HU (`CommonParams.java:89`), по проводу не ходит.

---

## 5. Видео-канал (id 2)

### 5.1 Формат кадра [К]

Сообщение MD→HU: `[outer: 00 00 00 02, len]` + `[dataLen u32][timestamp u32][00 02 00 01]` + данные.

- Тип: `MSG_VIDEO_DATA = 0x00020001`; heartbeat `0x00020002` с `dataLen = 0`.
- Данные: один кодированный кадр **H.264, Annex-B, 4-байтные стартовые коды** `00 00 00 01`. HU конфигурирует `MediaCodec` на `video/avc` (`DecodeUtil.java:195-197`) и при этом сам парсит поток.
- Максимальный кадр: Java-HU `1920*1080*3/2 = 3 110 400` байт (`DecodeUtil.java:26,304-306`); буфер C++ 600 КиБ (`CTranRecvPackageProcess.h:33`).
- `timestamp`: Java-HU её читает и **игнорирует** (`DecodeUtil.java:310-314`). CarProjection ставит `(int)System.currentTimeMillis()` (`Utils.java:149,161`); в C++ MD-Lib тоже просто параметр `timeStamp`. [К]
- Шифрование (если включено): шифруется только блок данных, `dataLen` в заголовке = размер шифртекста (`DecodeUtil.java:354-362`).

### 5.2 SPS/PPS и IDR [К]

Требования Java-HU (`DecodeUtil.java:485-520`):

- Первый принятый кадр обязан начинаться с SPS (NAL type 7): проверяется `(buf[4] & 0x1F) == 7`. HU ищет два следующих стартовых кода `00 00 00 01` (`findFlag2`, :465-485), то есть **SPS и PPS в начале первого кадра подряд**, и запоминает этот префикс как `mSpsPps`. Если первый кадр не начинается с SPS, он молча отбрасывается (`byteCount == 0`, return).
- Если сразу после SPS+PPS идёт не IDR, ждёт IDR позже.
- После внутреннего сброса декодера HU дописывает сохранённый `mSpsPps` к кадру, который начинается **прямо с IDR** (`(buf[4] & 0x1F) == 5`, `isIFrame`, :455-463; :375-398); кадры, не являющиеся «голым IDR», в состоянии сброса отбрасываются.
- CarProjection ведёт себя так: SPS+PPS (BUFFER_FLAG_CODEC_CONFIG) приклеивает **только к первому keyframe**, дальше keyframe идут без SPS/PPS (`MediaCodecTool.java:97-124`), `I_FRAME_INTERVAL = 1` (с) (:73). Рекомендация: повторять этот способ (первый кадр = SPS+PPS+IDR в одном сообщении; следующие IDR голые). Если слать SPS/PPS с каждым IDR, в состоянии сброса именно такой кадр не распознается как IDR и будет отброшен до следующего «голого» IDR. [К по коду HU; вывод о риске Д]

### 5.3 Профиль, разрешение, fps

- В коде HU **нет** ограничений на profile/level/разрешение; `MediaFormat` создаётся только с width/height из INIT_DONE, csd не передаётся (SPS берётся из потока) (`DecodeUtil.java:195-203`). [К]
- CarProjection кодирует `COLOR_FormatSurface`, профиль не задан (закомментированы `profile=1`, `level=256`; :77-78), bitrate по умолчанию 3 000 000, fps 30 (`MainActivity.java:176-177`; в `MediaCodecTool.java:70-72` аргументы в коде перепутаны местами, фактически bit rate получает «frame» = 3 000 000, frame rate «bit» = 30). Исходное разрешение 1280x720 (`MsgProcess.java:106-107`). [К]
- Рекомендации [Д]: Baseline/Main/High 4:2:0, без B-кадров (Changan-ветка HU ставит `disreorder=1`, `DecodeUtil.java:25,198-202`, значит reorder в декодере по умолчанию включён), размеры кратные 16, 720p@30.
- Управление fps: HU сам снижает целевой fps от 30 до 3 и шлёт `VIDEO_ENCODER_FRAME_RATE_CHANGE` (`DecodeUtil.java:336-346,636-666`); MD должен изменить частоту энкодера и (по протоколу) ответить `FRAME_RATE_CHANGE_DONE`. CarProjection не обрабатывает ни то, ни другое. [К]

---

## 6. Касания и управление (канал TOUCH, id 6)

Внутренний заголовок 8 байт, как у CMD. Направление HU→MD. [К]

### 6.1 Основной режим (MSG_TOUCH_ACTION = 0x00068001)

`CarlifeTouchAction {action:int32 = 1, x:int32 = 2, y:int32 = 3}` (`PB/CarlifeTouchActionProto.proto`).

- `action`: код `MotionEvent`: 0 = DOWN, 1 = UP, 2 = MOVE (HU пишет `event.getAction()`, `HUJ/touch/TouchListenerManager.java:207-231`; MD-сторона `CP/Utils.java:24-26`, `MsgProcess.java:242-310`). Мультитача в протоколе нет: одна точка (x, y), поля индекса указателя нет. [К]
- `x`, `y`: **пиксели в системе координат кодированного видео**, пересчитанные из координат на экране ГУ: `x*phoneW/carW`, `y*phoneH/carH`, где `phoneW/H` = размеры из `VIDEO_ENCODER_INIT_DONE`, `carW/H` = размеры SurfaceView ГУ (`TouchListenerManager.java:211-212`, :488-494 `CarlifeActivity`). Начало координат левый верхний угол. [К]
- Режим включается флагом `TRANSPARENT_SEND_TOUCH_EVENT` из конфига ГУ: `true` → этот режим (`CarlifeActivity.java:453-454`, `TouchListenerManager.java:60-73,88-101`). Какой режим у реальной Honda, неизвестно. [Д]
- CarProjection в MD-стороне обрабатывает **только** `MSG_TOUCH_ACTION` и `MSG_TOUCH_CAR_HARD_KEY_CODE` (`MsgProcess.java:546-575`). Остальные типы игнорирует. Значит, для нашего MD реализуем эти два, а остальные (ниже) принимаем и отбрасываем, либо мапим. [К]

### 6.2 «Жестовый» режим (если `TRANSPARENT_SEND_TOUCH_EVENT=false`)

HU разбирает касания `GestureDetector`-ом и шлёт, все с телом `CarlifeTouchSinglePoint {x=1, y=2}` в тех же пикселях (`TouchListenerManager.java:233-355`, `GestureListenerImp.java:36-161`):

| Код | Имя | Когда |
|---|---|---|
| 0x0006800A | TOUCH_ACTION_BEGIN | `onDown` |
| 0x00068002 | TOUCH_ACTION_DOWN | начало скролла (или в `onDown`, если `SEND_ACTION_DOWN`) |
| 0x00068004 | TOUCH_ACTION_MOVE | `onScroll` |
| 0x00068003 | TOUCH_ACTION_UP | fling / таймаут 150 мс после последнего MOVE / long press |
| 0x00068005 | TOUCH_SINGLE_CLICK | `onSingleTapUp` |
| 0x00068006 | TOUCH_DOUBLE_CLICK | двойное нажатие |
| 0x00068007 | TOUCH_LONG_PRESS | `onShowPress` |

Есть ещё `CarlifeTouchEvent`, `TouchFling`, `TouchScroll`, `TouchEventDevice/AllDevice` в `PB/` (для сырых evdev-событий и жестов); в Java-HU и C++-API HU не используются. [К]

### 6.3 Аппаратные кнопки

`MSG_TOUCH_CAR_HARD_KEY_CODE = 0x00068008`, тело `CarlifeCarHardKeyCode {keycode = 1}` (`TouchListenerManager.java:399-415`). Коды keycode (`HUJ/CommonParams.java:244-292`): HOME=1, PHONE_CALL=2, PHONE_END=3, PHONE_END_MUTE=4, HFP=5, SELECTOR_NEXT=6, SELECTOR_PREVIOUS=7, SETTING=8, MEDIA=9, RADIO=0x0A, NAVI=0x0B, SRC=0x0C, MODE=0x0D, BACK=0x0E, SEEK_SUB=0x0F, SEEK_ADD=0x10, VOLUME_SUB=0x11, VOLUME_ADD=0x12, MUTE=0x13, OK=0x14, MOVE_LEFT=0x15, MOVE_RIGHT=0x16, MOVE_UP=0x17, MOVE_DOWN=0x18, MOVE_UP_LEFT=0x19, MOVE_UP_RIGHT=0x1A, MOVE_DOWN_LEFT=0x1B, MOVE_DOWN_RIGHT=0x1C, TEL=0x1D, MAIN=0x1E, MEDIA_START=0x1F, MEDIA_STOP=0x20, VR_START=0x21, VR_STOP=0x22, NUMBER_0..9 = 0x23..0x2C, STAR=0x2D, POUND=0x2E, DEL=0x2F, CLEAR=0x30, ADD=0x31. CarProjection реагирует на SEEK_SUB/SEEK_ADD (предыдущий/следующий трек) (`MsgProcess.java:549-564`). Имена в C++-перечислении доходят до 0x1E (`CTranRecvPackageProcess.h:208-237`). [К]

`MSG_TOUCH_UI_ACTION_SOUND = 0x00060009` (MD→HU, звук нажатия) определён в обоих кодах; HU-библиотека на канале CTRL ничего не принимает (`CTranRecvPackageProcess.cpp:293-345` не имеет ветки CTRL_CHANNEL); для нас это мёртвый код. [К]

---

## 7. Аудио

### 7.1 Каналы

| Канал | id | Назначение | Источник → приёмник |
|---|---|---|---|
| MEDIA | 3 | музыка | MD→HU |
| TTS | 4 | голосовые подсказки навигации | MD→HU |
| VR | 5 | голосовой помощник: ответы MD→HU, микрофон HU→MD | оба |

Формат всех трёх: **несжатый PCM**, без кодека. Никакого AAC/Opus в протоколе нет. [К] (`HUJ/audioplayer/audiotrackmanager/AudioTrackManagerSingle.java:84-134` создаёт `AudioTrack` на `ENCODING_PCM_8BIT/16BIT`; `MEDIA_DATA` пишется в `AudioTrack` как есть).

### 7.2 Init-сообщения

Инициатор: **MD** (HU сам ничего не просит). Перед потоком данных MD шлёт `*_INIT` с `CarlifeMusicInit` (media/VR) или `CarlifeTTSInit` (tts), поля одинаковы:

`{sampleRate:int32 = 1, channelConfig:int32 = 2, sampleFormat:int32 = 3}`, где `channelConfig` = **число каналов** (1 = моно, 2 = стерео; любое другое трактуется как стерео), `sampleFormat` = **разрядность** (8 → PCM 8 бит, всё остальное → 16 бит) (`AudioTrackManagerSingle.java:113-125`). [К]

Умолчания HU при ошибке разбора: музыка 44100/2/16, TTS 16000/1/16 (`PackageHeadAnalyseModule.java:208,256`). CarProjection шлёт `MEDIA_INIT` 48000/2/16 (`MsgProcess.java:654-658`) и данные: захват внутреннего звука (`AudioPlaybackCapture` или REMOTE_SUBMIX) 48000 Гц, стерео, PCM16, порциями по 2560 байт (`MsgProcess.java:345-372,386-397`). [К]

Коды:

| Канал | INIT | DATA | Остальные |
|---|---|---|---|
| MEDIA | 0x00030001 | 0x00030006 | STOP 0x30002, PAUSE 0x30003, RESUME_PLAY 0x30004, SEEK_TO 0x30005 |
| TTS | 0x00040001 | 0x00040003 | END/STOP 0x40002 |
| VR (MD→HU) | 0x00050002 (INIT) | 0x00050003 (DATA) | STOP 0x50004, INTERRUPT 0x50006 (только Java) |
| VR (HU→MD) | | 0x00058001 MIC DATA | |

(`CommonParams.java:200-224`; C++ `CTranRecvPackageProcess.h:169-196`; в Java `MSG_VR_DATA = 0x00058001` — это микрофон, в C++ та же константа названа `MSG_VR_MIC_DATA`, а `MSG_VR_DATA = 0x00050003` — звук ответа.) [К]

Размеры: музыка до 100 КиБ на сообщение (`PCMPlayerUtils.java:103`, `CTranRecvPackageProcess.h:36`), TTS/VR до 50 КиБ (`:39,42`). Заголовок 12 байт, как в §2.3. [К]

### 7.3 Микрофон (VR record)

- HU→MD по каналу VR (id 5), тип `0x00058001`, заголовок 12 байт, `timestamp = 0` (`HUJ/logic/voice/PcmSender.java:188-217`; C++ `sendMicData`, `CTranRecvPackageProcess.cpp:2865-2886`). Данные: PCM16 mono **16 кГц** (`logic/voice/PcmRecorder.java:29-35,56`), опционально сжатый до 8 кГц простым прореживанием (`PcmSender.java:151-181`). Порция до 320 элементов очереди. [К]
- Когда HU пишет микрофон: по статусам MD. `MIC_RECORD_WAKEUP_START` (0x00010022), `MIC_RECORD_RECOG_START` (0x00010024), `MIC_RECORD_END` (0x00010023) и `MODULE_STATUS` модуля VR (`logic/voice/VoiceManager.java:84-120,160-190`). Микрофон ГУ вообще используется, если `VOICE_MIC = 0`. [К]

### 7.4 Режим передачи звука

Конфиг HU `AUDIO_TRANSMISSION_MODE` (`FeatureConfigManager.java:129-130`) и `PCMPlayerUtils.isBTTransmissionMode()`: если звук идёт по Bluetooth (A2DP), HU сбрасывает PCM, пришедший по USB, `if (!PCMPlayerUtils.isBTTransmissionMode()) mQueue.add(...)` (`MediaReceiveProcessor.java:126,148,171`). Значения не расшифрованы. [К по флагу; значения Д]

---

## 8. Шифрование AES / RSA [К]

Файлы: `HUJ/encryption/{EncryptConfig,EncryptSetupManager,RSAManager,AESManager}.java`.

- **Не обязательно в Java-HU по умолчанию.** Включается только по инициативе MD: он шлёт `MD_RSA_PUBLIC_KEY_REQUEST` (0x0001006A). Без этого `isEncryptEnable()` = false (`EncryptConfig.java:22-24`: `DEBUG_ENABLE = true`, `AES_ENCRYPT_AS_BEGINE = false`; `EncryptSetupManager.java:87-97`). CarProjection шифрование не использует. [К]
- Исключение: если конфиг HU содержит `CONTENT_ENCRYPTION = 1`, HU **не шлёт** `VIDEO_ENCODER_INIT` после `MATCH_STATUS`, а ждёт `MD_ENCRYPT_READY` (`CarlifeActivity.java:445-449,902-905`). Тогда MD обязан инициировать RSA/AES. [К]
- Если выставить `AES_ENCRYPT_AS_BEGINE = true` (тестовый режим), обе стороны шифруют всё с самого начала ключом `"1234567890123456"` (`EncryptSetupManager.java:37,87-97`, `AESManager.java:32-40`). [К]

Последовательность (CMD-канал):

1. MD→HU `MD_RSA_PUBLIC_KEY_REQUEST` (пусто).
2. HU→MD `HU_RSA_PUBLIC_KEY_RESPONSE {rsaPublicKey}`: RSA-2048, ключ создаётся при запуске HU (`RSAManager.java:122-134`), в виде Base64 (NO_WRAP) от `PublicKey.getEncoded()`, то есть DER X.509 SubjectPublicKeyInfo (`:133-134`). (Парсер `getmPubkey` в HU почему-то использует `PKCS8EncodedKeySpec`, но он не вызывается.)
3. MD→HU `MD_AES_KEY_SEND_REQUEST {aesKey}`: 16-символьный ключ (ASCII), зашифрованный RSA `RSA/ECB/PKCS1Padding` открытым ключом HU, затем Base64 (NO_WRAP) (`RSAManager.java:80-120`, `EncryptConfig.java:29`; `EncryptSetupManager.java:67-85`). HU расшифровывает и **включает шифрование сразу**, до отправки ответа (`CarlifeActivity.java:890-901`).
4. HU→MD `HU_AES_REC_RESPONSE` (пусто; тело нулевое, поэтому не шифруется).
5. MD→HU `MD_ENCRYPT_READY` (пусто). HU реагирует отправкой `VIDEO_ENCODER_INIT` (`CarlifeActivity.java:902-905`).

После включения:

- Шифруется **тело любого сообщения с длиной > 0** на всех каналах и в обоих направлениях: CMD и TOUCH (`ConnectSocket.java:140-152,258-266`), VIDEO (`DecodeUtil.java:354-362`), MEDIA/TTS/VR данные и init (`MediaReceiveProcessor.java:114`, `NaviReceiveProcessor.java:101`, `VRReceiveProcessor.java:108`, `PackageHeadAnalyseModule.java:185,233`), микрофон (`PcmSender.java:203-212`). Заголовки открытые.
- Алгоритм: `Cipher.getInstance("AES")` на Android = **AES/ECB/PKCS5Padding**, ключ 16 байт = байты строки `aesKey` (`AESManager.java:56-63`). [К, значение «AES» по умолчанию = ECB/PKCS5 это Д из знания JCE]
- В заголовке поле `len` = длина шифртекста (`ConnectSocket.java:146`).

---

## 9. Минимальный путь «MD показывает видео 720p и принимает касания»

Предпосылки: оба конца используют внешний заголовок из §2.1, 8-байтный заголовок канала шлётся **отдельным** bulk-transfer, затем тело (§1.4). Шифрование и аутентификация не используются.

### 9.1 Последовательность

| # | Напр. | Канал | Сообщение | Тело |
|---|---|---|---|---|
| 0 | HU | USB | AOA: GET_PROTOCOL, 6x SEND_IDENT, START_ACCESSORY, ждать `18D1:2D0x`, claim interface 0 | см. §1 |
| 1 | HU→MD | CMD | `HU_PROTOCOL_VERSION` 0x00018001 | `08 01 10 00` |
| 2 | MD→HU | CMD | `PROTOCOL_VERSION_MATCH_STATUS` 0x00010002 | `08 01` |
| 3 | HU→MD | CMD | `STATISTIC_INFO` 0x00018027 | cuid и т.д. (MD может игнорировать) |
| 4 | HU→MD | CMD | `HU_INFO` 0x00018003 | CarlifeDeviceInfo |
| 5 | MD→HU | CMD | `MD_INFO` 0x00010004 | CarlifeDeviceInfo с `os="Android"`, `sdk_int >= 21` |
| 6 | MD→HU | CMD | `MD_AUTHEN_RESULT` 0x0001004B | `08 01` (по образцу CarProjection, после шага 3) |
| 7 | HU→MD | CMD | `VIDEO_ENCODER_INIT` 0x00018007 | `08 80 0A 10 D0 05 18 00` (1280x720, fps 0) |
| 8 | MD→HU | CMD | `VIDEO_ENCODER_INIT_DONE` 0x00010008 | `08 80 0A 10 D0 05 18 1E` (1280x720, 30) |
| 9 | MD→HU | CMD | `FOREGROUND` 0x0001001B | пусто |
| 10 | HU→MD | CMD | `VIDEO_ENCODER_START` 0x00018009 | пусто |
| 11 | HU→MD | CMD | `LAUNCH_MODE_NORMAL` 0x0001801D | пусто (опционально) |
| 12 | HU→MD | CMD | `MODULE_CONTROL` 0x00018028 (CONNECT=2) | `08 05 10 02` |
| 13 | MD→HU | VIDEO | `VIDEO_DATA` 0x00020001: первый кадр SPS+PPS+IDR, дальше P-кадры/голые IDR; heartbeat `0x00020002` (len 0) при простое | H.264 Annex-B |
| 14 | HU→MD | TOUCH | `TOUCH_ACTION` 0x00068001 (DOWN=0, MOVE=2, UP=1) | `08 act 10 x 18 y` |
| 15 | HU→MD | TOUCH | `TOUCH_CAR_HARD_KEY_CODE` 0x00068008 (опционально) | `08 key` |

Необязательно для минимального видео+тач: MEDIA/TTS/VR, MODULE_STATUS, FEATURE_CONFIG, FRAME_RATE_CHANGE (но желательно принимать и не падать).

### 9.2 Байты на проводе (примеры)

`HU_PROTOCOL_VERSION`:
```
outer: 00 00 00 01  00 00 00 0C
inner: 00 04 00 00  00 01 80 01  08 01 10 00
```
`MATCH_STATUS`:
```
outer: 00 00 00 01  00 00 00 0A
inner: 00 02 00 00  00 01 00 02  08 01
```
`VIDEO_ENCODER_INIT` 1280x720, fps 0:
```
outer: 00 00 00 01  00 00 00 10
inner: 00 08 00 00  00 01 80 07  08 80 0A 10 D0 05 18 00
```
`VIDEO_ENCODER_START`:
```
outer: 00 00 00 01  00 00 00 08
inner: 00 00 00 00  00 01 80 09
```
`TOUCH_ACTION` DOWN (640,360):
```
outer: 00 00 00 06  00 00 00 10
inner: 00 08 00 00  00 06 80 01  08 00 10 80 05 18 E8 02
```
`VIDEO_DATA` (n байт H.264):
```
outer: 00 00 00 02  <u32: n+12>
inner: <u32 n> <u32 ts>  00 02 00 01  <n байт Annex-B>
```

### 9.3 Что реализовать

**Фейковая HU (Python, pyusb + libusb/WinUSB)**:

- Переключение в AOA и открытие интерфейса 0; эндпоинты находить по направлению.
- Запись: `write(outer8)` отдельным transfer, затем `write(body)` кусками ≤ 16384.
- Чтение: большой буфер (16384), накапливать байты в потоке, разбирать по внешнему заголовку `chan/len`, затем по внутреннему заголовку канала (§2.3). Не полагаться на границы transfer при разборе.
- Состояния: CONNECTED → send VERSION → ждать MATCH (если ≠1 — стоп) → STATISTIC, HU_INFO, INIT(1280x720,0) → ждать INIT_DONE → START → принять видео, дать декодировать (ffplay/PyAV) → слать TOUCH_ACTION.
- Таймаут «жив ли MD»: нет видео-сообщений > 6 с = разрыв (как у настоящего HU).
- Принимать и логировать все неизвестные типы, не падая.

**MD-приложение (Android)**:

- `uses-feature android.hardware.usb.accessory`, accessory filter `manufacturer="Baidu" model="CarLife"`, `USB_ACCESSORY_ATTACHED`; `UsbManager.openAccessory()` → `FileInputStream/FileOutputStream` (по образцу `CP/MainActivity.java:238-262`).
- Читать поток с внешним заголовком, диспетчер по `chan`, на CMD внутренний 8-байтный разбор.
- Ответы шагов 2, 5, 6, 8, 9; на `START` запускать энкодер (MediaCodec, Surface, H.264) и слать кадры в VIDEO; не реже 1 сообщения в ~5 с (кадр или heartbeat).
- Тач: из `TOUCH_ACTION` (x, y в пикселях кадра INIT_DONE) формировать события для Android Auto (или для хоста, показывающего AA). Что именно подставить в `INIT_DONE` по размеру, решать при интеграции AA.
- Запись только одним потоком: заголовок + тело подряд, без перемежения сообщений разных каналов.

---

## 10. Открытые вопросы и риски

1. **AUTHEN на других ГУ.** В открытых источниках ни HU Baidu (Java), ни MD (CarProjection) не проверяют подпись. Схема из DOC (случайная строка, подпись на стороне MD) без стороннего ключа не воспроизводится. Проверенная ГУ Honda Vezel 2024 `HU_AUTHEN_REQUEST` не шлёт и принимает «ложный» `MD_AUTHEN_RESULT{true}`; другие ГУ могут вести себя иначе. [Д]
2. **Версия протокола реальной ГУ.** Исходники версии 1.0 (2015-2017); `icar` ссылается на CarLife 8.4.2. В новых версиях возможны другие поля, обязательное шифрование (`CONTENT_ENCRYPTION`), дополнительные сообщения. Мы не знаем, что объявит ГУ в `HU_FEATURE_CONFIG_RESPONSE`. [Д]
3. **Строки AOA на ГУ.** Фильтр в нашем приложении по `Baidu/CarLife`; если ГУ шлёт другие строки (OEM-вариант), приложение не получит `USB_ACCESSORY_ATTACHED`. Нужен дамп. [Д]
4. **NUL-терминатор в AOA-строках.** Baidu шлёт без NUL (§1.1). Что реально делает ГУ-хост и как Android обрабатывает, зависит от ядра; для PC-теста копируем Baidu. Если телефон не примет, попробовать с NUL. [Д]
5. **Границы transfer.** Критично слать заголовок отдельным transfer, а тело в одном transfer ≤ 16 КиБ (§1.4). Для ГУ как хоста проверить, что настоящая ГУ делает так же (по коду Baidu делает). Для нашего MD читать потоково, не полагаться на один `read` = одно сообщение. [Д, внешнее знание о ядре]
6. **Windows/WinUSB/libusb.** Для PC-теста телефон в обычном режиме нужно привязать к WinUSB (Zadig), после переключения 18D1:2D0x появляется новым устройством, драйвер WinUSB надо привязать заново; `claimInterface(0)`. Сведений в открытых источниках нет. [Д]
7. **Размер кадров от MD.** Реальный HU Baidu принимает кадр до ~3.1 МБ; аппаратный декодер ГУ Honda может быть строже по profile/level и числу reorder-кадров. Начать с Baseline, 720p, без B-кадров, fps 30, битрейт 3-4 Мбит/с. [Д]
8. **SPS/PPS с каждым IDR или только в первом кадре.** Код Java-HU ожидает первый кадр с SPS+PPS и «голые» IDR после сброса (§5.2). У реального HU Honda поведение может отличаться. Сделать настройку. [Д]
9. **Heartbeat.** Реальный MD, вероятно, шлёт `VIDEO_HEARTBEAT` периодически (период неизвестен). Если картинка статична, а энкодер не повторяет кадры, надо слать heartbeat или принудительно кадры. [Д]
10. **Расхождение кодов `CAR_DATA_SUBSCRIBE_DONE` (0x00010032 в C++ vs 0x00018032 в Java)** и тип тела у `CAR_DATA_START` (список vs одиночный). Затрагивает только данные автомобиля (скорость/GPS), не видео и не тач. [К, оба значения есть в коде]
11. **Тач-режим ГУ.** Режим `TRANSPARENT_SEND_TOUCH_EVENT` (один `TOUCH_ACTION` против набора жестовых сообщений) определяется конфигом ГУ; нужно принимать оба. Мультитач протокол не поддерживает (одна точка). [К/Д]
12. **Звук.** Если ГУ в режиме BT-аудио (`AUDIO_TRANSMISSION_MODE`), PCM по USB игнорируется, звук придётся вести по A2DP. [К по флагу HU, Д по значению]
13. **`MD_INFO.sdk_int`.** При `sdk_int < 21` Java-HU показывает «AOA не поддерживается» (§4.4); ставить реальный SDK телефона. [К]
14. **`icar` и `CarLife-MD-Lib`.** Для MD-стороны это не источник правды (`icar` без кода, `MDL` без MD-команд). Единственный образец MD-логики это `CarProjection`, и он урезанный (нет feature config, нет heartbeat, нет FRAME_RATE_CHANGE, нет шифрования, нет мультимедиа-метаданных). [К]
