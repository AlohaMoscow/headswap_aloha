# pc-headunit

Минимальный эмулятор головного устройства Android Auto для ПК. Нужен для отладки: подключается к «серверу головного устройства» AA на телефоне (TCP 5277), проходит Version -> TLS -> Auth -> Service Discovery, принимает H.264-видео в `out/video.h264` и пишет весь обмен в консоль и `out/session.log`. Работает без телефона-ГУ и без CarLife, поэтому удобен, чтобы проверить сторону AA отдельно от приложения Headswap.

Протокол описан в [../docs/aa-headunit.md](../docs/aa-headunit.md).

## Зависимости

- Python 3.10+
- `pip install -r requirements.txt` (пакет `protobuf` 5.x; сгенерированные `proto/*_pb2.py` требуют runtime >= 5.27.2)
- для тестов: `pytest`; тест с проверкой потока через `ffprobe` пропускается, если `ffmpeg`/`ffprobe` нет в PATH
- для перегенерации `proto/*_pb2.py` (не нужно для запуска): `pip install grpcio-tools==1.67.1`, затем `python proto/gen.py`

## Сертификат

Эмулятор, как и настоящая ГУ, обязан предъявить телефону клиентский сертификат, подписанный Google Automotive Link. В репозиторий сертификаты не входят (`certs/` в `.gitignore`). Положите PEM-файлы в одну из папок:

```
pc-headunit/certs/jvc/cert.pem   # JVC Kenwood, из aasdk: src/Messenger/Cryptor.cpp, cert/headunit.crt и headunit.key
pc-headunit/certs/jvc/key.pem
pc-headunit/certs/hur/cert.pem   # из headunit-revived: app/src/main/res/raw/cert и privkey
pc-headunit/certs/hur/key.pem
```

Источники: [opencardev/aasdk](https://github.com/opencardev/aasdk) (продолжение [f1xpl/aasdk](https://github.com/f1xpl/aasdk)), [andreknieriem/headunit-revived](https://github.com/andreknieriem/headunit-revived). Набор выбирается флагом `--cert jvc|hur` (по умолчанию `jvc`). Для тестов нужны оба набора.

## Запуск

```
adb forward tcp:5277 tcp:5277
python hu.py --duration 30 --cert jvc
```

На телефоне перед этим: Android Auto -> Настройки -> 10 тапов по «Версия» -> меню «три точки» -> «Start head unit server». Сервер принимает одно соединение за запуск и зависает после грубого обрыва, поэтому останавливайте скрипт через Ctrl+C или `--duration`: он закроет сессию корректно.

Полезные флаги: `--check` (локальная проверка сертификата и TLS без подключения), `--touch-test` (тап в центр через 10 с после первого кадра), `--resolution`, `--fps`, `--moto`, `--full-audio`, `--minimal`. Полный список: `python hu.py --help`.

## Эмулятор ГУ CarLife (`carlife_hu.py`)

Только стандартная библиотека. По умолчанию (`--transport stream`) это TCP-сервер: мост Headswap на телефоне подключается к нему одним соединением через `adb reverse tcp:7240 tcp:7240`, весь обмен идёт с внешним заголовком канала. Лог и видео пишутся в `out/carlife_hu.log` и `out/carlife_video.h264`.

```
adb reverse tcp:7240 tcp:7240
python carlife_hu.py --duration 120
```

## Режим 6 сокетов (беспроводной формат)

В беспроводном формате CarLife у каждого канала свой TCP-сокет и нет внешнего заголовка. Телефон слушает шесть портов, эмулятор подключается к ним как клиент (CMD 7240, VIDEO 8240, MEDIA 9240, TTS 9241, VR 9242, TOUCH 9340). Порты достаём с телефона через `adb forward`, поэтому хост по умолчанию 127.0.0.1:

```
adb forward tcp:7240 tcp:7240
adb forward tcp:8240 tcp:8240
adb forward tcp:9240 tcp:9240
adb forward tcp:9241 tcp:9241
adb forward tcp:9242 tcp:9242
adb forward tcp:9340 tcp:9340
adb shell am start-foreground-service -n com.yegorkapomidorka.headswap/.BridgeService --ez debug_tcp6 true
python carlife_hu.py --transport tcp6 --touch-test
```

Сначала запускается мост на телефоне (он открывает порты), потом эмулятор. Логика сессии та же: HU_PROTOCOL_VERSION, init burst после MD_INFO, авторизация, heartbeat раз в секунду, тап через 15 с после первого кадра, итог в конце. Для Data-канала (порт 9440, телефон его принимает и игнорирует) есть `--extra-data-socket`.

Проверка реакции моста на потерю канала: эмулятор закрывает один сокет и ждёт, закроет ли мост остальные.

```
python carlife_hu.py --transport tcp6 --kill-chan VIDEO --kill-after 10
```

`--kill-chan` принимает CMD, VIDEO, MEDIA, TTS, VR, TOUCH; `--kill-after` считается от первого видеокадра. В итоге будет строка `kill: VIDEO at 12.3 s, bridge closed others after 0.4 s` или `bridge did NOT close others within 10 s` (последнее значит, что мост остался висеть с частью каналов).

## Тесты

```
python -m pytest tests -q
```

Тесты автономные: «телефон» написан прямо в тесте и общается с сессией через `socket.socketpair()`. Порт 5277 и телефон не нужны.
