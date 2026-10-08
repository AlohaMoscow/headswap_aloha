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

## Тесты

```
python -m pytest tests -q
```

Тесты автономные: «телефон» написан прямо в тесте и общается с сессией через `socket.socketpair()`. Порт 5277 и телефон не нужны.
