# Метаданные музыки и навигации AA → CarLife (приборка ГУ)

Разведка 2026-10-11. Повод: CL2AA (ISO_QUARTZ, Toyota Levin) выводит трек с прогрессом и пошаговые подсказки («Proceed to …, 110 m» со стрелкой) на приборку через CarLife.
Пути: RE = `re/carlife-8.6.7/sources/com/baidu/carlife/`, AND = `android/app/src/main/java/com/yegorkapomidorka/headswap/`.

## Вывод
- Музыка: блокеров нет. Телефон по AA уже шлёт song/artist/album/обложку/прогресс на ch9, мост их игнорирует.
- Навигация: Vezel подписывается на moduleID=0 (SUBSCRIBE_START), мы отвечаем «данные не шлю». Какие nav-события шлёт телефон — неизвестно (в логах нет поездки с маршрутом).

## CarLife, MD→HU, канал CMD (все поля proto2 required)
| Что | Код | Proto / поля | Где шлёт Baidu | Условия |
|---|---|---|---|---|
| MEDIA_INFO | 0x10035 | CarlifeMediaInfo: source1, song2, artist3, album4, albumArt5 (bytes), duration6, playlistNum7, songId8, mode9 | RE/core/base/subscribe/MusicInfoSender.java:160-262 | init нет; подписка музыки включена по умолчанию (:43), выключает STOP moduleID=2 |
| MEDIA_PROGRESS_BAR | 0x10036 | CarlifeMediaProgressBar{progressBar=1} | MusicInfoSender.java:293-316 | троттлинг 1 с |
| NAVI_NEXT_TURN_INFO | 0x10030 | CarlifeNaviNextTurnInfo: action1, nextTurn2, roadName3, totalDistance4, remainDistance5, поле6 | RE/core/base/subscribe/RGEventHUDCollection.java:109-125 | нужна подписка moduleID=0 (SUBSCRIBE_START) |
| MODULE_STATUS NAVI | 0x10026 | {moduleID=2, statusID} | RGEventHUDCollection.java:183,194 | 1 старт навигации, 0 конец |
| NAVI_ASSITANTGUIDE_INFO | 0x10047 | — | в 8.6.7 не шлётся | DONE supportFlag=false |

Детали:
- Поле 6 NextTurn: в 8.6.7 `bytes turnIconData`; в refs/apollo-DuerOS и refs/CarProjection — `int32 time`. Wire-type разный, что ждёт Vezel — неизвестно.
- action: 1 первый показ, 2 обновление, 3 конец (конец: nextTurn=24, roadName «目的地», иконка пустая, :186-193).
- remainDistance в метрах; totalDistance — значение, зафиксированное при смене дороги (:261-269).
- Без иконки Baidu обновление поворота не шлёт (:245,277). Иконка PNG < 30720 Б, иначе 160×160 (:157-173).
- duration в секундах (MusicInfoSender.java:184). Единицы progressBar не подтверждены.
- Обложка JPEG < 30720 Б (CarlifeUtil.java:398), иначе 160×160.
- Для channel 20832125 (Vezel) особых веток в 8.6.7 нет.
- Подписка: HU→MD 0x18043 → DONE 0x10044 (CarlifeDataSubscribe.java:18-53) → START 0x18045 / STOP 0x18046. moduleID: 0 навигация, 1 assistant guide, 2 музыка.

## AA, что приходит
- SDR уже объявляет MEDIA_PLAYBACK id=9 и NAVIGATION id=10 (type=2 = ENUM/ImageCodesOnly, minimum_interval_ms=1000) — AND/aa/AapMessages.kt:340-341. Картинок манёвров при ENUM не будет.
- ch9: 0x8001 MediaPlaybackStatus{state1 (2=PLAYING), media_source2, playback_seconds3, shuffle4, repeat5, repeat_one6}; 0x8003 MediaPlaybackMetadata{song1, artist2, album3, album_art4, playlist5, duration_seconds6, rating7}. refs/aasdk/protobuf/aap_protobuf/service/mediaplayback/.
- ch10: 0x8001/0x8002 START/STOP; 0x8003 NavigationStatus (0 UNAVAILABLE, 1 ACTIVE, 2 INACTIVE, 3 REROUTING); 0x8004 NextTurnEvent и 0x8005 NextTurnDistanceEvent (deprecated); 0x8006 NavigationState (steps/maneuver/road/lanes/cue); 0x8007 NavigationCurrentPosition (step_distance, destination_distances, current_road). Разбор: refs/headunit-revived/.../aap/AapNavigation.kt:28-130.
- В наших логах это уже видно, но с неверными подписями (AapMessages.kt:159-167,395-402 применяют медиа-имена ко всем каналам): «MEDIA_START 0x8001» на ch9 = MediaPlaybackStatus; «MEDIA_CONFIG 0x8003 {status=…, max_unacked=…, f4=PNG}» на ch9 = Metadata с обложкой PNG 256×256 90–127 КБ; на ch10 «{status=2}» = NavigationStatus INACTIVE.

## Маппинг AA → CarLife
Музыка: song/artist/album → то же; album_art PNG → JPEG < 30 КБ; duration_seconds → duration; playback_seconds → progressBar (единицы проверить); media_source → source; state → MODULE_STATUS MUSIC 1/0; mode — неясно.
Навигация: NavigationStatus ACTIVE/INACTIVE → MODULE_STATUS NAVI 1/0 + action 1..3; road name → roadName; distance meters → remainDistance; totalDistance считать самим; ETA полей нет.
nextTurn (RE/core/base/subscribe/RouteGuideParams.java): 1 прямо, 2 право-вперёд, 3 право, 4 право-назад, 5 разворот, 6 лево-назад, 7 лево, 8 лево-вперёд, 9 въезд на кольцо, 10 съезд с кольца, 11–22 съезды/развилки, 23 старт, 24 цель, 29/30 паром. Маппинг AA по смыслу: STRAIGHT→1, SLIGHT R/L→2/8, NORMAL R/L→3/7, SHARP→4/6, U_TURN→5, ROUNDABOUT_ENTER/EXIT→9/10, FORK/KEEP/MERGE/RAMP→11–22, DEPART→23, DESTINATION→24.

## Мост сейчас и точки встраивания
- ch9/10 открываются, данные игнорируются: AND/aa/AapSession.kt:608 (dispatch :590).
- CarLife: подписка принимается, на START только лог (CarLifeSession.kt:324-336); DONE supportFlag=true для 0 и 2 (Protocol.kt:552-562); MODULE_STATUS — Protocol.kt:529-545; MEDIA_INFO/PROGRESS/NEXT_TURN только в таблице имён (Protocol.kt:136,142,143).
- Встраивать: парсинг ch9/10 — AapSession.kt:608; интерфейс CarlifeLink — AaBridge.kt:10-38; реализация у CarLifeSession.kt:578 через sendCmd (:689); флаги подписки — CarLifeSession.kt:333-336; билдеры — у Protocol.kt:529-573; схемы лога — Proto.kt:131-210. SDR не трогать.
- AaBridgeCore без Android (AaBridge.kt:49-52, JVM-тесты) → PNG→JPEG в CarLifeSession или Android-хелпере. Не конфликтовать с MODULE_STATUS MUSIC по isMediaInited (CarLifeSession.kt:377-380).

## ГУ Vezel (logs/car-20261007-4/files/probe-20261007-094730.log)
Протокол 2.0, channel 20832125, FEATURE_CONFIG 4 ключа (все 0), NAVI_TYPE нет. Подписка moduleID=0 + START. На moduleID=2 не подписывается (отправке не мешает). Рисует ли приборку — по логам не понять.

## Неизвестные (проверять в машине)
- Какие nav-сообщения шлёт телефон (legacy 0x8004/5 или 0x8006/7) — поездка с маршрутом и полным логом ch10.
- Формат поля 6 (иконка или time), рисует ли ГУ стрелку по nextTurn или по иконке; при ENUM иконки рисовать самим (набор стрелок).
- Показывает ли Vezel музыку без подписки на moduleID=2; единицы progressBar; допустимые размеры обложки/иконки.
