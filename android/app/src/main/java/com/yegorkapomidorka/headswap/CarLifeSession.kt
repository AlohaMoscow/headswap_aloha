package com.yegorkapomidorka.headswap

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.CarlifeAudio
import com.yegorkapomidorka.headswap.aa.AaTranscoder
import com.yegorkapomidorka.headswap.aa.CarlifeLink
import com.yegorkapomidorka.headswap.aa.TranscodeOutput
import com.yegorkapomidorka.headswap.aa.VideoMode
import com.yegorkapomidorka.headswap.aa.VideoTranscoder
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Настройки сессии, снятые в момент старта. */
class SessionConfig(
    val screenOn: Boolean = true,
    /** Вместе с MIC_RECORD_* слать MODULE_STATUS VR 1/0, как Baidu (prepareShowVr / prepareCloseVr). */
    val micVrStatus: Boolean = true,
)

/**
 * Одна сессия с ГУ поверх открытого аксессуара: читает поток, отвечает как MD, отдаёт мосту видео/звук/тач, пишет всё в лог.
 * Потоки: reader (чтение + диспетчер), writer (единственный писатель), enc-drain/render (VideoSource заглушки),
 * scheduler (статистика, watchdog тишины, отложенный MODULE_STATUS).
 * Протокол CarLife 2.0 по поведению MD Baidu CarLife 8.6.7, см. docs/carlife-v2-handshake.md.
 */
class CarLifeSession(
    private val pfd: ParcelFileDescriptor,
    private val cfg: SessionConfig,
    private val bridge: AaBridgeCore,
    private val onClosed: (String) -> Unit,
) : VideoSource.Listener, CarlifeLink {

    private class Out(val chan: Int, val inner: ByteArray)

    /** Видео-режим хоста, снятый при подключении: одинаков для INIT_DONE, энкодера и тача в течение всей сессии. */
    private val mode: VideoMode = bridge.videoMode

    private val input = FileInputStream(pfd.fileDescriptor)
    private val output = FileOutputStream(pfd.fileDescriptor)
    private val queue = LinkedBlockingQueue<Out>()
    private val parser = FrameParser()
    private val assembler = InnerAssembler()
    private val closed = AtomicBoolean(false)
    private val sched: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cl-sched") }
    private var reader: Thread? = null
    private var writer: Thread? = null

    private val t0 = SystemClock.elapsedRealtime()
    @Volatile private var lastRx = 0L
    @Volatile private var rxBytes = 0L
    @Volatile private var desync = false

    // протокол
    @Volatile private var matchSent = false
    private var huMajor = 0
    private var huMinor = 0
    private var pendingW = 0
    private var pendingH = 0
    private var pendingFps = FPS
    private var videoInited = false
    private var source: VideoSource? = null
    @Volatile private var csd: ByteArray? = null
    @Volatile private var csdPending = true
    @Volatile private var paused = false

    // находки для итогового резюме
    private var huVersion = "-"
    private var sawAuthRequest = false
    private var authRandom: String? = null
    private var huAuthResult: String? = null
    private var touchMode = "не было тачей"
    private var touchCount = 0
    private val rxByType = java.util.TreeMap<Int, Int>()
    private var huInfo: String? = null
    private var huStatistic: String? = null
    private var huFeature: String? = null
    private var encryptionNeeded: Boolean? = null
    private var requested = "-"
    private var chosen = "-"

    // видео статистика
    @Volatile private var lastVideoTx = 0L
    private var vFrames = 0L
    private var vBytes = 0L
    private var vKeys = 0L
    private var vBeats = 0L
    private var vDropped = 0L
    private var audioTx = 0L
    @Volatile private var micRxPackets = 0L
    @Volatile private var micRxBytes = 0L
    /** Когда сами слали MODULE_STATUS VR=1: ответный MODULE_CONTROL VR=1 сразу после него считаем эхом. */
    @Volatile private var lastOwnVrOnMs = 0L

    // заглушка (VideoSource с текстом) или AA-видео; переключается ядром моста
    private val videoLock = Any()
    private val srcLock = Any()
    @Volatile private var bannerText: String? = null
    @Volatile private var acceptEncoder = true
    @Volatile private var carlifeStarted = false

    fun start() {
        bridge.attach(this)
        AppLog.i("сессия: старт")
        CarLifeState.resetSession()
        CarLifeState.videoMode = mode
        AppLog.i(mode.describe())
        CarLifeState.status = "соединено, жду HU_PROTOCOL_VERSION"
        lastRx = SystemClock.elapsedRealtime()
        writer = Thread({ writeLoop() }, "cl-writer").also { it.start() }
        reader = Thread({ readLoop() }, "cl-reader").also { it.start() }
        sched.scheduleWithFixedDelay({ tick() }, 1, 1, TimeUnit.SECONDS)
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        AppLog.i("сессия: закрытие, причина: $reason")
        bridge.onCarlifeClosed(this)
        logSummary(reason)
        CarLifeState.status = "закрыто: $reason"
        try {
            sched.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            source?.stop()
        } catch (e: Exception) {
            AppLog.e("остановка видео", e)
        }
        source = null
        try {
            pfd.close()
        } catch (_: Exception) {
        }
        writer?.interrupt()
        reader?.interrupt()
        try {
            input.close()
        } catch (_: Exception) {
        }
        try {
            output.close()
        } catch (_: Exception) {
        }
        AppLog.i("сессия: закрыта")
        onClosed(reason)
    }

    // ---------------- чтение ----------------

    private fun readLoop() {
        val buf = ByteArray(Wire.CHUNK)
        try {
            while (!closed.get()) {
                val n = input.read(buf)
                if (n < 0) {
                    close("EOF чтения (ГУ отключилось)")
                    return
                }
                lastRx = SystemClock.elapsedRealtime()
                rxBytes += n
                AppLog.detail("RAW rx read=$n bytes: ${Hex.compact(buf.copyOf(minOf(n, 32)), 32)}")
                if (desync) continue
                try {
                    parser.feed(buf, 0, n) { chan, inner -> assembler.feed(chan, inner) { c, i -> dispatch(c, i) } }
                } catch (e: ProtocolDesync) {
                    desync = true
                    AppLog.e("РАССИНХРОН потока: ${e.message}; дальше только сырой дамп")
                    AppLog.detail(Hex.dump(buf.copyOf(n), 256))
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                AppLog.e("ошибка чтения", e)
                close("ошибка чтения: ${e.message}")
            }
        }
    }

    private fun dispatch(chan: Int, inner: ByteArray) {
        if (Ch.isCmdStyle(chan)) {
            val m = Wire.parseCmd(inner)
            if (m == null) {
                AppLog.i("RX ${Ch.name(chan)} слишком короткое inner (${inner.size} байт): ${Hex.compact(inner, 32)}")
                AppLog.detail(Hex.dump(inner))
                return
            }
            rxByType.merge(m.type, 1, Int::plus)
            val decoded = if (m.body.isEmpty()) null else PbText.render(m.body, Schemas.byType[m.type])
            val note = (if (m.truncated) " [TRUNCATED declared=${m.declaredLen}]" else "") +
                (if (m.resv != 0) " [resv=${m.resv}]" else "") +
                (if (Schemas.byType[m.type] == null && m.body.isNotEmpty()) " [схемы нет]" else "")
            val quiet = m.type == Msg.TOUCH_ACTION_MOVE || (m.type == Msg.TOUCH_ACTION && touchCount > 20 && isMove(m.body))
            AppLog.msg("RX", chan, inner.size, m.type, m.declaredLen, decoded, inner, ui = !quiet, note = note)
            if (chan == Ch.CMD) onCmd(m) else onTouch(m)
        } else {
            val m = Wire.parseData(inner)
            if (m == null) {
                AppLog.i("RX ${Ch.name(chan)} слишком короткое inner (${inner.size} байт): ${Hex.compact(inner, 32)}")
                return
            }
            rxByType.merge(m.type, 1, Int::plus)
            if (chan == Ch.VR && m.type == Msg.VR_MIC_DATA) {
                onMicData(m)
                return
            }
            val note = " time=${m.time}" + (if (m.truncated) " [TRUNCATED declared=${m.declaredLen}]" else "")
            AppLog.msg("RX", chan, inner.size, m.type, m.declaredLen, null, inner, note = note)
        }
    }

    /** Микрофон ГУ (VR 0x58001): тело = PCM16 mono 16 кГц, шифрования нет. В лог: первый пакет и каждый 200-й. */
    private fun onMicData(m: DataMsg) {
        micRxPackets++
        micRxBytes += m.data.size
        if (micRxPackets == 1L || micRxPackets % 200 == 0L) {
            AppLog.i("RX VR MIC_DATA len=${m.data.size} time=${m.time}${if (m.truncated) " [TRUNCATED declared=${m.declaredLen}]" else ""} #$micRxPackets, всего $micRxBytes Б", ui = micRxPackets == 1L)
        }
        if (m.truncated || m.data.isEmpty()) return
        bridge.onCarlifeMicData(m.data)
    }

    private fun isMove(body: ByteArray): Boolean {
        val f = PbReader.parse(body) ?: return false
        return PbReader.int(f, 1) == 2
    }

    // ---------------- CMD ----------------

    private fun onCmd(m: CmdMsg) {
        when (m.type) {
            Msg.HU_PROTOCOL_VERSION -> {
                val f = PbReader.parse(m.body)
                val maj = f?.let { PbReader.int(it, 1) }
                val min = f?.let { PbReader.int(it, 2) }
                huVersion = "$maj.$min"
                if (matchSent) {
                    // Baidu: ConnectClient.geHasMatch() -> второй MATCH не шлём (CarlifeConnectHsgHandler :308-310)
                    AppLog.i("== ПОВТОРНЫЙ HU_PROTOCOL_VERSION $huVersion, второй MATCH не шлю (как Baidu)")
                    return
                }
                huMajor = maj ?: 0
                huMinor = min ?: 0
                CarLifeState.protocol = "ГУ шлёт версию $huVersion"
                AppLog.i("== ВЕРСИЯ ПРОТОКОЛА ГУ: major=$maj minor=$min")
                matchSent = true
                // стартовая пачка сразу, ничего не ждём от ГУ: MATCH, FOREGROUND, SCREEN_ON, MD_INFO
                for (c in Handshake.startBatch(cfg.screenOn, deviceInfo())) sendCmd(c.type, c.body)
                val mod = Handshake.moduleStatus(huMajor)
                try {
                    sched.schedule({ sendCmd(mod.type, mod.body) }, Handshake.MODULE_STATUS_DELAY_MS, TimeUnit.MILLISECONDS)
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    // сессия уже закрывается
                }
                CarLifeState.status = "handshake: стартовая пачка отправлена"
            }
            Msg.HU_INFO -> {
                huInfo = PbText.render(m.body, Schemas.deviceInfo)
                AppLog.i("== HU_INFO: $huInfo")
                // ответа нет: Baidu только сохраняет (CarlifeDeviceInfoManager.setCarDeviceInfo)
            }
            Msg.STATISTIC_INFO -> {
                huStatistic = PbText.render(m.body, Schemas.statisticsInfo)
                AppLog.i("== STATISTIC_INFO: $huStatistic")
                for (c in Handshake.onStatisticInfo(sendFeatureConfigRequest = true)) sendCmd(c.type, c.body)
            }
            Msg.HU_AUTHEN_REQUEST -> {
                sawAuthRequest = true
                val f = PbReader.parse(m.body)
                val rnd = f?.firstOrNull { it.number == 1 && it.wire == 2 }?.data
                val rndStr = rnd?.toString(Charsets.UTF_8)
                authRandom = rnd?.let { PbText.quote(it) + " hex=" + Hex.compact(it, 64) }
                val v2 = Handshake.isAuthV2(rndStr)
                AppLog.i("== ГУ ШЛЁТ HU_AUTHEN_REQUEST randomValue=$authRandom")
                AppLog.i("== AUTHEN randomValue ${if (v2) "начинается с \"2.0;\"" else "не начинается с \"2.0;\""}")
                CarLifeState.auth = "HU_AUTHEN_REQUEST пришёл, ${if (v2) "2.0;" else "не 2.0;"}"
                // как Baidu 8.6.7 (PrivateKeyManager.encryptContentProcess): подпись приватным ключом Baidu сразу по запросу;
                // MD_AUTHEN_RESULT{true} по-прежнему уходит после STATISTIC_INFO
                val enc = if (rndStr == null) null else try {
                    HuAuth.encryptValue(rndStr)
                } catch (e: Exception) {
                    AppLog.e("подпись HU_AUTHEN_REQUEST", e)
                    null
                }
                if (enc == null) {
                    AppLog.i("на HU_AUTHEN_REQUEST не отвечаю (${if (rndStr == null) "нет randomValue" else "ветка 2.0 не реализована"})")
                } else {
                    AppLog.i("== отвечаю MD_AUTHEN_RESPONSE encryptValue=$enc")
                    CarLifeState.auth += ", MD_AUTHEN_RESPONSE отправлен"
                    sendCmd(Msg.MD_AUTHEN_RESPONSE, Msgs.authenResponse(enc))
                }
            }
            Msg.HU_FEATURE_CONFIG_RESPONSE -> onFeatureConfig(m)
            Msg.CARLIFE_DATA_SUBSCRIBE -> {
                val done = Msgs.subscribeDone(m.body)
                if (done == null) {
                    AppLog.i("== CARLIFE_DATA_SUBSCRIBE не разобрался, не отвечаю (как Baidu)")
                } else {
                    AppLog.i("== CARLIFE_DATA_SUBSCRIBE moduleID=${Msgs.subscribeIds(m.body)}, отвечаю DONE")
                    sendCmd(Msg.CARLIFE_DATA_SUBSCRIBE_DONE, done)
                }
            }
            Msg.CARLIFE_DATA_SUBSCRIBE_START ->
                AppLog.i("== SUBSCRIBE_START moduleID=${Msgs.subscribeIds(m.body)}, данные не шлю")
            Msg.CARLIFE_DATA_SUBSCRIBE_STOP ->
                AppLog.i("== SUBSCRIBE_STOP moduleID=${Msgs.subscribeIds(m.body)}")
            Msg.VIDEO_ENCODER_JPEG -> {
                AppLog.i("== ГУ просит JPEG-режим видео: отвечаю ACK, но шлю только H.264")
                sendCmd(Msg.VIDEO_ENCODER_JPEG_ACK, null)
            }
            Msg.REQUEST_MD_THEME_STATE -> sendCmd(Msg.RESPONSE_MD_THEME_STATE, Msgs.themeState(THEME_LIGHT))
            Msg.CHANGE_MD_THEME -> AppLog.i("== CHANGE_MD_THEME ${PbText.render(m.body, Schemas.themeState)}")
            Msg.HU_AUTHEN_RESULT -> {
                val f = PbReader.parse(m.body)
                val ok = f?.let { PbReader.int(it, 1) }
                huAuthResult = "authenResult=$ok"
                AppLog.i("== HU_AUTHEN_RESULT: $ok")
                CarLifeState.auth += " -> HU_AUTHEN_RESULT=$ok"
            }
            Msg.VIDEO_ENCODER_INIT -> onVideoInit(m)
            Msg.VIDEO_ENCODER_START -> onVideoStart()
            Msg.VIDEO_ENCODER_PAUSE -> {
                paused = true
                bridge.onCarlifePause()
                AppLog.i("видео: PAUSE, кадры не шлю")
            }
            Msg.VIDEO_ENCODER_RESET -> {
                AppLog.i("видео: RESET, прошу ключевой кадр")
                source?.requestKeyFrame()
                bridge.onCarlifeKeyFrameRequest()
            }
            Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE -> {
                // как VideoMsgHandler.changeFrameRate: вне 3..30 молча игнор, иначе DONE (fps энкодера не меняем)
                val fps = PbReader.parse(m.body)?.let { PbReader.int(it, 1) } ?: -1
                if (Handshake.frameRateChangeAllowed(fps)) {
                    sendCmd(Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE, Msgs.frameRate(fps))
                } else {
                    AppLog.i("видео: FRAME_RATE_CHANGE fps=$fps вне 3..30, игнор (как Baidu)")
                }
            }
            Msg.MODULE_CONTROL -> {
                // ГУ спрашивает состояние модуля (MUSIC=3 шлёт ~7 раз после хендшейка): отвечаем по факту, играет ли MEDIA
                val f = PbReader.parse(m.body)
                val module = f?.let { PbReader.int(it, 1) }
                val status = f?.let { PbReader.int(it, 2) }
                if (module == CarlifeAudio.MODULE_MUSIC && AaBridgeCore.MODULE_MUSIC_ENABLED) {
                    val on = bridge.isMediaInited
                    AppLog.i("== MODULE_CONTROL MUSIC status=$status, отвечаю MODULE_STATUS MUSIC=${if (on) 1 else 0}", ui = false)
                    sendModuleStatus(CarlifeAudio.MODULE_MUSIC, if (on) 1 else 0)
                } else if (module == MODULE_VR) {
                    // Кнопка голоса на ГУ/руле: Baidu на VR=1 открывает голосовой ввод (openVrAndDialog), 0/3 - закрывает
                    val sinceOwn = SystemClock.elapsedRealtime() - lastOwnVrOnMs
                    when {
                        status != 1 -> AppLog.i("== MODULE_CONTROL VR status=$status: игнор")
                        sinceOwn < VR_ECHO_MS -> AppLog.i("== MODULE_CONTROL VR=1 через $sinceOwn мс после нашего VR=1: эхо, игнор")
                        else -> bridge.onCarlifeVoiceKey()
                    }
                }
            }
            Msg.MD_EXIT -> close("ГУ прислало MD_EXIT")
            else -> Unit // всё остальное только в логе
        }
    }

    private fun onVideoInit(m: CmdMsg) {
        val f = PbReader.parse(m.body)
        val rw = f?.let { PbReader.int(it, 1) } ?: 0
        val rh = f?.let { PbReader.int(it, 2) } ?: 0
        val rf = f?.let { PbReader.int(it, 3) } ?: 0
        requested = "${rw}x$rh fps=$rf"
        AppLog.i("== ГУ ПРОСИТ РАЗРЕШЕНИЕ: ${rw}x$rh fps=$rf")
        if (videoInited && pendingW > 0) {
            // как VideoMsgHandler (:84-88): повторный INIT -> только INIT_DONE с прежним размером
            AppLog.i("== повторный INIT, отвечаю INIT_DONE ${pendingW}x$pendingH fps=$pendingFps")
            sendCmd(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(pendingW, pendingH, pendingFps))
            return
        }
        // размер задаёт видео-режим (по умолчанию 1280x720 fps 30: ГУ Honda принимает и показывает целиком, проверено в машине)
        val (w, h, why) = Triple(mode.outW, mode.outH, "мост: режим ${mode.name}")
        val fps = FPS
        pendingW = w
        pendingH = h
        pendingFps = fps
        videoInited = true
        chosen = "${w}x$h ($why)"
        AppLog.i("== ОТВЕЧАЮ INIT_DONE ${w}x$h fps=$fps ($why)")
        CarLifeState.video = "ГУ просит $requested, кодирую ${w}x$h"
        sendCmd(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(w, h, fps))
        sendCmd(Msg.FOREGROUND, null)
    }

    private fun onFeatureConfig(m: CmdMsg) {
        val fc = FeatureConfig.parse(m.body)
        if (fc == null) {
            AppLog.i("== FEATURE_CONFIG не разобрался: ${Hex.compact(m.body, 64)}")
            return
        }
        huFeature = fc.items.joinToString(" ") { "${it.first}=${it.second}" }
        bridge.onHuFeatureConfig(fc.value("AUDIO_TRANSMISSION_MODE"))
        AppLog.i("== HU FEATURE_CONFIG (${fc.items.size} ключей), btAudio=${fc.huBtAudioSupport} btName=${fc.huBtName} btMAC=${fc.huBtMac}")
        for ((k, v) in fc.items) AppLog.i("   feature $k = $v")
        val enc = fc.value(FEATURE_CONTENT_ENCRYPTION)
        encryptionNeeded = enc?.let { it == 1 }
        AppLog.i("== CONTENT_ENCRYPTION=${enc ?: "нет в списке"}, CONNECT_TYPE=${fc.value("CONNECT_TYPE") ?: "нет"}")
        if (enc == 1) {
            AppLog.i("== !!! ГУ ТРЕБУЕТ ШИФРОВАНИЕ КАНАЛА (CONTENT_ENCRYPTION=1): нужна цепочка RSA/AES (0x1006A..0x1006E), пока не реализована")
        }
    }

    /** START: гейт AA сбрасывается, заглушка (если активна) стартует с SPS+PPS+IDR. */
    private fun onVideoStart() {
        paused = false
        if (pendingW <= 0) {
            pendingW = mode.outW
            pendingH = mode.outH
            chosen = "${mode.outW}x${mode.outH} (START без INIT, мост, режим ${mode.name})"
        }
        carlifeStarted = true
        synchronized(videoLock) {
            csdPending = true
            lastVideoTx = SystemClock.elapsedRealtime()
        }
        CarLifeState.status = "видео: мост"
        bridge.onCarlifeStart()
        if (bannerText != null) startOrUpdatePlaceholder()
    }

    private fun startOrUpdatePlaceholder() {
        synchronized(srcLock) {
            val text = bannerText ?: return
            val cur = source
            if (cur != null) {
                cur.banner = text
                cur.requestKeyFrame()
                return
            }
            val s = VideoSource(pendingW, pendingH, pendingFps, mode.bitrate, this)
            s.banner = text
            try {
                s.start()
                source = s
                AppLog.i("BRIDGE заглушка: энкодер ${s.encoderName} ${pendingW}x$pendingH, текст '$text'")
            } catch (e: Exception) {
                AppLog.e("не удалось запустить энкодер заглушки", e)
                s.stop()
            }
        }
    }

    override fun sendVideo(payload: ByteArray): Boolean {
        if (closed.get() || paused) return false
        synchronized(videoLock) {
            if (queue.size > MAX_QUEUE) return false
            lastVideoTx = SystemClock.elapsedRealtime()
            send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_DATA, unixSeconds(), payload))
            return true
        }
    }

    /** Аудио моста: канал MEDIA/TTS, заголовок 12 байт; time = millis & 0xFFFFFFFF в DATA, 0 в INIT/STOP (как Baidu). */
    override fun sendAudio(channel: Int, type: Int, payload: ByteArray): Boolean {
        if (closed.get()) return false
        if (payload.isNotEmpty() && queue.size > MAX_QUEUE * 2) return false   // звук не должен душить видео в очереди
        val isData = type == CarlifeAudio.MEDIA_DATA || type == CarlifeAudio.TTS_DATA
        val time = if (isData) (System.currentTimeMillis() and 0xFFFFFFFFL).toInt() else 0
        send(channel, Wire.dataInner(type, time, payload))
        return true
    }

    /** MODULE_STATUS об одном модуле (NAVI перед TTS, MUSIC при MEDIA INIT/PAUSE), как Baidu ModuleStatusManage. */
    override fun sendModuleStatus(module: Int, status: Int): Boolean {
        if (closed.get()) return false
        sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(module, status))
        return true
    }

    /**
     * Запись микрофона на ГУ как у Baidu MD: старт = MIC_RECORD_RECOG_START (VrManager.sendStartRecogMsgToVehicle)
     * и MODULE_STATUS VR=1 (prepareShowVr); стоп = MIC_RECORD_END (sendRecordEndMsgToVehicle) и VR=0 (prepareCloseVr).
     */
    override fun requestMicRecord(start: Boolean): Boolean {
        if (closed.get()) return false
        if (start) {
            micRxPackets = 0
            micRxBytes = 0
            AppLog.i("MIC -> ГУ: MIC_RECORD_RECOG_START${if (cfg.micVrStatus) " + MODULE_STATUS VR=1" else ""}")
            sendCmd(Msg.MIC_RECORD_RECOG_START, null)
            if (cfg.micVrStatus) {
                lastOwnVrOnMs = SystemClock.elapsedRealtime()
                sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(MODULE_VR, 1))
            }
        } else {
            AppLog.i("MIC -> ГУ: MIC_RECORD_END${if (cfg.micVrStatus) " + MODULE_STATUS VR=0" else ""}; с ГУ принято $micRxPackets пакетов, $micRxBytes Б")
            sendCmd(Msg.MIC_RECORD_END, null)
            if (cfg.micVrStatus) sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusSingle(MODULE_VR, 0))
        }
        return true
    }

    /** Транскодер моста: декодер AA (кадр режима) -> кроп -> Surface энкодера (выход режима). null, если MediaCodec не поднялся. */
    override fun createTranscoder(out: TranscodeOutput): VideoTranscoder? = try {
        AaTranscoder(
            mode.outW, mode.outH, FPS, mode.bitrate, out, { AppLog.i(it, false) },
            srcWidth = mode.aaW, srcHeight = mode.aaH, crop = mode.crop,
        ).also { it.start() }
    } catch (e: Exception) {
        AppLog.e("BRIDGE транскодер не создан", e)
        null
    }

    override fun showPlaceholder(text: String) {
        bannerText = text
        synchronized(videoLock) {
            if (!acceptEncoder) csdPending = true
            acceptEncoder = true
        }
        if (carlifeStarted && !closed.get()) {
            synchronized(videoLock) { csdPending = true }
            startOrUpdatePlaceholder()
        }
    }

    override fun hidePlaceholder() {
        synchronized(videoLock) { acceptEncoder = false }
        bannerText = null
        val old = synchronized(srcLock) {
            val o = source
            source = null
            o
        }
        if (old != null) Thread({ old.stop() }, "cl-placeholder-stop").start()
    }

    // ---------------- TOUCH ----------------

    private fun onTouch(m: CmdMsg) {
        val f = PbReader.parse(m.body) ?: return
        var action = -1
        var x = -1
        var y = -1
        when (m.type) {
            Msg.TOUCH_ACTION -> {
                action = PbReader.int(f, 1) ?: -1
                x = PbReader.int(f, 2) ?: -1
                y = PbReader.int(f, 3) ?: -1
                setTouchMode("TOUCH_ACTION (прозрачный, TRANSPARENT_SEND_TOUCH_EVENT=true)")
            }
            Msg.TOUCH_ACTION_BEGIN, Msg.TOUCH_ACTION_DOWN, Msg.TOUCH_ACTION_UP, Msg.TOUCH_ACTION_MOVE,
            Msg.TOUCH_SINGLE_CLICK, Msg.TOUCH_DOUBLE_CLICK, Msg.TOUCH_LONG_PRESS -> {
                x = PbReader.int(f, 1) ?: -1
                y = PbReader.int(f, 2) ?: -1
                action = when (m.type) {
                    Msg.TOUCH_ACTION_BEGIN, Msg.TOUCH_ACTION_DOWN -> 0
                    Msg.TOUCH_ACTION_UP -> 1
                    Msg.TOUCH_ACTION_MOVE -> 2
                    else -> 3
                }
                setTouchMode("жестовый (отдельные коды 0x68002..0x6800A)")
            }
            Msg.TOUCH_CAR_HARD_KEY_CODE -> {
                val code = PbReader.int(f, 1)
                AppLog.i("== HARD KEY keycode=$code")
                if (code != null) bridge.onCarlifeHardKey(code)
                return
            }
            else -> return
        }
        touchCount++
        if (action in 0..2) bridge.onCarlifeTouch(action, x, y)
        CarLifeState.touch = "$touchMode; событий=$touchCount; последний act=$action x=$x y=$y"
    }

    private fun setTouchMode(mode: String) {
        if (touchMode != mode) {
            touchMode = mode
            AppLog.i("== РЕЖИМ ТАЧА: $mode")
        }
    }

    // ---------------- запись ----------------

    private fun sendCmd(type: Int, body: ByteArray?) = send(Ch.CMD, Wire.cmdInner(type, body))

    private fun send(chan: Int, inner: ByteArray) {
        if (closed.get()) return
        queue.put(Out(chan, inner))
    }

    private fun writeLoop() {
        try {
            while (!closed.get()) {
                val o = queue.take()
                // заголовок отдельным write, тело следом кусками <= 16384 (спека §1.4)
                for (part in Wire.writeSequence(o.chan, o.inner)) output.write(part)
                output.flush()
                logTx(o)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!closed.get()) {
                AppLog.e("ошибка записи", e)
                close("ошибка записи: ${e.message}")
            }
        }
    }

    private fun logTx(o: Out) {
        if (Ch.isCmdStyle(o.chan)) {
            val m = Wire.parseCmd(o.inner) ?: return
            val decoded = if (m.body.isEmpty()) null else PbText.render(m.body, Schemas.byType[m.type])
            AppLog.msg("TX", o.chan, o.inner.size, m.type, m.declaredLen, decoded, o.inner)
        } else {
            val m = Wire.parseData(o.inner) ?: return
            if (o.chan != Ch.VIDEO) { // аудио моста: INIT/STOP всегда, DATA - первые и каждый 200-й
                audioTx++
                if (m.data.size <= 16 || audioTx <= 3 || audioTx % 200 == 0L) {
                    AppLog.i(
                        "TX ${Ch.name(o.chan)} ${Msg.hex(m.type)} ${Msg.name(m.type)} len=${m.data.size} time=${m.time} #$audioTx" +
                            (if (m.data.size <= 16) " ${Hex.compact(m.data, 16)}" else ""), ui = false
                    )
                }
                return
            }
            if (m.data.isEmpty()) { // keepalive: VIDEO_DATA len=0 (Recorder.sendEmptyPacketForeground)
                vBeats++
                if (vBeats <= 3) AppLog.msg("TX", o.chan, o.inner.size, m.type, m.declaredLen, null, o.inner)
                return
            }
            vFrames++
            vBytes += m.data.size
            val key = AnnexB.nalTypes(m.data).contains(5)
            if (key) vKeys++
            val nals = AnnexB.nalTypes(m.data)
            if (vFrames <= 5 || key) {
                AppLog.msg("TX", o.chan, o.inner.size, m.type, m.declaredLen, "nal=$nals", o.inner, ui = vFrames <= 5)
            }
        }
    }

    // ---------------- видео из энкодера ----------------

    override fun onConfig(csd: ByteArray) {
        this.csd = csd
        val types = AnnexB.nalTypes(csd)
        var prof = ""
        if (csd.size > 8 && types.firstOrNull() == 7) {
            // [00 00 00 01][SPS header][profile_idc][constraints][level_idc]
            prof = " profile_idc=${csd[5].toInt() and 0xFF} constraints=0x%02X level_idc=${csd[7].toInt() and 0xFF}".format(csd[6].toInt() and 0xFF)
        }
        AppLog.i("энкодер: codec config ${csd.size} байт nal=$types$prof")
        AppLog.detail(Hex.dump(csd, 128))
    }

    override fun onFrame(data: ByteArray, key: Boolean) {
        if (closed.get() || paused) return
        synchronized(videoLock) {
            if (!acceptEncoder) return   // заглушка скрыта (идёт AA-видео): остаток кадров кодера не нужен
            onEncoderFrame(data, key)
        }
    }

    private fun onEncoderFrame(data: ByteArray, key: Boolean) {
        var payload = data
        if (key) {
            val c = csd
            if (csdPending && c != null && !AnnexB.startsWithSps(data)) payload = AnnexB.glue(c, data)
            csdPending = false
        } else if (csdPending) {
            vDropped++
            return // до первого IDR ничего не шлём (первый пакет обязан начинаться с SPS)
        }
        if (queue.size > MAX_QUEUE) {
            vDropped++
            source?.requestKeyFrame()
            return
        }
        lastVideoTx = SystemClock.elapsedRealtime()
        send(Ch.VIDEO, Wire.dataInner(Msg.VIDEO_DATA, unixSeconds(), payload))
    }

    override fun onError(msg: String, t: Throwable?) {
        AppLog.e(msg, t)
        CarLifeState.status = "ОШИБКА: $msg: ${t?.message}"
    }

    // ---------------- таймер ----------------

    private var lastStatsAt = 0L
    private var lastStatsFrames = 0L

    private fun tick() {
        if (closed.get()) return
        val now = SystemClock.elapsedRealtime()
        // Пустых keepalive 0x20001 len=0 в простое НЕТ: у Baidu это служебные пакеты foreground/background,
        // а ГУ не должен оставаться без кадров - это обеспечивает энкодер (repeat-previous-frame-after 100 мс).
        if (rxBytes == 0L && now - t0 > SILENCE_MS && (now - t0) % 10_000 < 1000) {
            AppLog.i("ГУ не прислало ни одного байта за ${(now - t0) / 1000} с (ждём HU_PROTOCOL_VERSION)")
            CarLifeState.status = "ГУ молчит, байт от него нет"
        }
        if (now - lastStatsAt >= 5000 && (vFrames > 0 || vBeats > 0)) {
            AppLog.i("видео: отправлено кадров=$vFrames (ключевых=$vKeys) байт=$vBytes heartbeat=$vBeats сброшено=$vDropped очередь=${queue.size} (+${vFrames - lastStatsFrames} за ${(now - lastStatsAt) / 1000}с)")
            lastStatsAt = now
            lastStatsFrames = vFrames
            CarLifeState.video = "$chosen; кадров=$vFrames hb=$vBeats"
        }
    }

    private fun logSummary(reason: String) {
        val sb = StringBuilder("== РЕЗЮМЕ СЕССИИ (${(SystemClock.elapsedRealtime() - t0) / 1000} с, причина: $reason)\n")
        sb.append("  принято байт: $rxBytes\n")
        sb.append("  версия протокола ГУ: $huVersion\n")
        sb.append("  HU_AUTHEN_REQUEST: ${if (sawAuthRequest) "ПРИШЁЛ randomValue=$authRandom" else "не приходил"}\n")
        sb.append("  HU_AUTHEN_RESULT: ${huAuthResult ?: "не приходил"}\n")
        sb.append("  запрошенное разрешение: $requested; ответ: $chosen\n")
        sb.append("  тач: $touchMode, событий=$touchCount\n")
        sb.append("  видео: кадров=$vFrames ключевых=$vKeys heartbeat=$vBeats сброшено=$vDropped\n")
        sb.append("  HU_INFO: ${huInfo ?: "не приходил"}\n")
        sb.append("  принятые типы: ")
        for ((t, c) in rxByType) sb.append("${Msg.hex(t)} ${Msg.name(t)} x$c; ")
        AppLog.i(sb.toString().trimEnd(), ui = false)
        AppLog.i("резюме записано в лог (сессия ${reason})")
    }

    private fun deviceInfo(): DeviceInfo {
        // SDK_INT >= 33: как в 8.6.7 врём "29"/29
        val (sdk, sdkInt) = DeviceInfo.reportedSdk(Build.VERSION.SDK_INT)
        return DeviceInfo(
            os = "Android",
            board = Build.BOARD,
            bootloader = Build.BOOTLOADER,
            brand = Build.BRAND,
            cpuAbi = Build.SUPPORTED_ABIS.getOrElse(0) { "" },
            cpuAbi2 = Build.SUPPORTED_ABIS.getOrElse(1) { "" },
            device = Build.DEVICE,
            display = Build.DISPLAY,
            fingerprint = Build.FINGERPRINT,
            hardware = Build.HARDWARE,
            host = Build.HOST,
            cid = Build.ID,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            product = Build.PRODUCT,
            serial = "unknown",
            codename = Build.VERSION.CODENAME,
            incremental = Build.VERSION.INCREMENTAL,
            release = Build.VERSION.RELEASE,
            sdk = sdk,
            sdkInt = sdkInt,
            btaddress = "unknown", // BT-адрес нам недоступен; Baidu шлёт "unknown", если не получил
            carlifeVersion = Handshake.MD_VERSION,
            carlifeType = Handshake.MD_TYPE_CARLIFE_APP,
        )
    }

    companion object {
        const val FPS = 30
        private const val MAX_QUEUE = 90
        private const val SILENCE_MS = 10_000L
        private const val FEATURE_CONTENT_ENCRYPTION = "CONTENT_ENCRYPTION"

        /** moduleID голосового помощника в MODULE_STATUS (ModuleStatusManage: 4 = VR). */
        private const val MODULE_VR = 4
        private const val VR_ECHO_MS = 1500L

        /** SkinDisplayModeManager.getDisplayModeInt: 0 светлая, 1 тёмная, 2 авто. */
        private const val THEME_LIGHT = 0

        /** Поле time видеопакета: unix-секунды (Recorder.sendVideoData :930-962). */
        private fun unixSeconds() = (System.currentTimeMillis() / 1000).toInt()
    }
}
