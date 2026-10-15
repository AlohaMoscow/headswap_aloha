package com.yegorkapomidorka.headswap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.yegorkapomidorka.headswap.aa.AaBridgeCore
import com.yegorkapomidorka.headswap.aa.AaConnector
import com.yegorkapomidorka.headswap.aa.AaCertsMissingException
import com.yegorkapomidorka.headswap.aa.AaCreds
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaState
import com.yegorkapomidorka.headswap.aa.AapOptions
import com.yegorkapomidorka.headswap.aa.BridgeHost
import com.yegorkapomidorka.headswap.aa.MetaRelay
import com.yegorkapomidorka.headswap.aa.MicMode
import com.yegorkapomidorka.headswap.aa.PhoneMicCapture
import com.yegorkapomidorka.headswap.aa.ServerPhase
import com.yegorkapomidorka.headswap.aa.VideoMode
import com.yegorkapomidorka.headswap.autosrv.AutoServer
import com.yegorkapomidorka.headswap.autosrv.AutoServerPolicy
import com.yegorkapomidorka.headswap.autosrv.Trigger
import com.yegorkapomidorka.headswap.link.CarTransport
import com.yegorkapomidorka.headswap.link.LinkSetupException
import com.yegorkapomidorka.headswap.link.StreamTransport
import com.yegorkapomidorka.headswap.link.Tcp6Listener
import com.yegorkapomidorka.headswap.link.TransportCaps
import com.yegorkapomidorka.headswap.link.diag.NetProbe
import com.yegorkapomidorka.headswap.link.diag.WLog
import com.yegorkapomidorka.headswap.link.wifi.HotspotBeacon
import com.yegorkapomidorka.headswap.ui.HeadswapNotification
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * Foreground-сервис (connectedDevice): держит транспорт (USB-аксессуар или debug-TCP) и сессию с ГУ.
 * AA-сторона моста (BridgeHost) живёт на время сервиса, а не одной USB-сессии: обрыв USB закрывает
 * CarLife-сессию, AA остаётся живой BridgeHost.GRACE_MS и подхватывается следующей USB-сессией.
 * Всё состояние сервиса (session, host, stopRequested) трогает только главная нить.
 */
class BridgeService : Service() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: CarLifeSession? = null
    private var host: BridgeHost? = null
    private var stopRequested = false
    /** debug tcp6: слушатель ждёт 6/6 (нить debug-tcp6); Стоп его отменяет. */
    @Volatile private var pendingTcp6: Tcp6Listener? = null
    /** Идёт подъём tcp6/беспровода (от старта до 6/6 или отказа); сервис в это время не гасим. Только главная нить. */
    private var linkWaiting = false
    /** Беспровод (бета, ветка B) включён пользователем: после обрыва снова ждём машину. Пишет главная нить, читает и коннектор (автозапуск сервера AA). */
    @Volatile private var wifiBMode = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val audioDiag = AudioDiag()
    /** Автозапуск пропущен из-за неподключённой службы спецвозможностей: RETRY запустит его, когда она появится. */
    @Volatile private var serverStartPending = false
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bridge-grace").also { it.isDaemon = true } }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                AppLog.i("USB_ACCESSORY_DETACHED")
                // беспроводную сессию отключение USB-аксессуара не касается
                session?.takeIf { it.transportKind == TransportCaps.USB_AOA.kind }?.close("аксессуар отключён (broadcast)")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel_bridge), NotificationManager.IMPORTANCE_LOW))
        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), Context.RECEIVER_EXPORTED)
        audioDiag.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService требует startForeground в течение 5 с, делаем сразу
        val n = HeadswapNotification.build(this, CHANNEL, NOTIF_ID)
        startForegroundTyped(n)

        if (intent?.action == ACTION_STOP) {
            AppLog.i("сервис: стоп по команде")
            stopRequested = true
            wifiBMode = false
            CarLifeState.wireless = null
            pendingTcp6?.abort()
            val s = session
            if (s != null) {
                s.close("остановлено пользователем")        // onSessionClosed увидит stopRequested: AA закрывается сразу
            } else {
                val h = host
                if (h != null) h.shutdown("стоп пользователем") else finish()
            }
            return START_NOT_STICKY
        }
        if (session != null) {
            AppLog.i("сервис: сессия уже идёт, повторный старт игнорирую")
            return START_NOT_STICKY
        }

        // только debug-сборка: вместо USB-аксессуара TCP к эмулятору ГУ на ПК (adb reverse), в release port() всегда null
        val dbgPort = DebugTransport.port(intent)
        if (dbgPort != null) {
            AppLog.i("сервис: debug-транспорт, подключаюсь к 127.0.0.1:$dbgPort")
            Thread({
                val pfd = DebugTransport.connect(dbgPort)
                main.post {
                    if (pfd == null) {
                        CarLifeState.status = "debug TCP: нет соединения"
                        failStart()
                    } else if (session != null) {
                        pfd.close()
                    } else {
                        startSession(StreamTransport.fromPfd(pfd, TransportCaps.TCP_DEBUG, "TCP 127.0.0.1:$dbgPort"))
                    }
                }
            }, "debug-tcp").start()
            return START_NOT_STICKY
        }

        // только debug-сборка: беспроводной формат, 6 TCP-сокетов на 127.0.0.1 (adb forward), ГУ подключается сама
        if (DebugTransport.tcp6(intent)) {
            startTcp6Debug(DebugTransport.tcp6Bind(intent))
            return START_NOT_STICKY
        }
        // беспровод, ветка B (хотспот телефона + маяк): кнопка в настройках бета-сборки или debug-вход
        if (intent?.action == ACTION_WIFI_B || DebugTransport.wifiB(intent)) {
            if (!BuildConfig.WIRELESS_BETA && !DebugTransport.wifiB(intent)) {
                AppLog.i("сервис: беспровод в этой сборке выключен")
                failStart()
                return START_NOT_STICKY
            }
            wifiBMode = true
            stopRequested = false
            startWifiB()
            return START_NOT_STICKY
        }

        val usb = getSystemService(UsbManager::class.java)
        val acc = usb.accessoryList?.firstOrNull()
        if (acc == null) {
            AppLog.i("сервис: аксессуара нет, останавливаюсь")
            CarLifeState.status = "аксессуара нет"
            failStart()
            return START_NOT_STICKY
        }
        if (!usb.hasPermission(acc)) {
            AppLog.i("сервис: нет разрешения на аксессуар, останавливаюсь")
            CarLifeState.status = "нет разрешения на аксессуар"
            failStart()
            return START_NOT_STICKY
        }
        val pfd = try {
            usb.openAccessory(acc)
        } catch (e: Exception) {
            AppLog.e("openAccessory", e)
            null
        }
        if (pfd == null) {
            AppLog.i("сервис: openAccessory вернул null")
            CarLifeState.status = "openAccessory: ошибка"
            failStart()
            return START_NOT_STICKY
        }
        AppLog.i("сервис: аксессуар открыт: ${MainActivity.describe(acc)}")
        if (wifiBMode || pendingTcp6 != null) {
            // кабель побеждает воздух: ожидание машины по Wi-Fi отменяем, после USB-сессии беспровод не возобновляем
            AppLog.i("сервис: кабель подключён, ожидание по Wi-Fi отменяю (USB побеждает)")
            WLog.ev(WLog.LINK, "usb-wins")
            wifiBMode = false
            CarLifeState.wireless = null
            pendingTcp6?.abort()
        }
        startSession(StreamTransport.fromPfd(pfd, TransportCaps.USB_AOA, MainActivity.describe(acc)))
        return START_NOT_STICKY
    }

    /** debug tcp6: слушать 6 портов на loopback, ждать ГУ (pc-headunit --transport tcp6), затем обычная сессия. */
    private fun startTcp6Debug(bind: String) = startTcp6("tcp6", bind, TCP6_WAIT_MS, beacon = null)

    /**
     * Ветка B (хотспот телефона): маяк «CarlifeHost» на 7999 и ответчик 8999, 6 портов на 0.0.0.0, пускаем только
     * адреса из подсетей, куда идёт маяк. Хотспот включает человек. Пока только debug-вход (`--ez debug_wifi_b true`).
     */
    private fun startWifiB() {
        CarLifeState.wireless = getString(R.string.wl_state_waiting, "…")
        val beacon = HotspotBeacon(onIfaces = { list ->
            CarLifeState.wireless = if (list.isEmpty()) getString(R.string.wl_state_no_hotspot)
            else getString(R.string.wl_state_waiting, list.joinToString(", ") { it.name })
        })
        startTcp6("wifi-b", "0.0.0.0", WIFI_B_WAIT_MS, beacon)
    }

    private fun startTcp6(kind: String, bind: String, waitMs: Long, beacon: HotspotBeacon?) {
        if (linkWaiting) {
            AppLog.i("сервис: $kind: уже жду ГУ, повторный старт игнорирую")
            return
        }
        linkWaiting = true
        WLog.begin(kind, "bind" to bind, "ACCESS_LOCAL_NETWORK" to NetProbe.localNetworkGranted(this))
        AppLog.i("сервис: $kind, слушаю 6 портов на $bind, жду ГУ ${waitMs / 1000} с")
        CarLifeState.status = "$kind: жду ГУ"
        Thread({
            val result: Any = try {
                val peerOk: (java.net.InetAddress) -> Boolean =
                    if (beacon == null) { _ -> true } else { a -> beacon.current.any { it.contains(a) } }
                val l = Tcp6Listener(java.net.InetAddress.getByName(bind), peerOk = peerOk).open()
                pendingTcp6 = l
                beacon?.start()
                l.await(waitMs, "$kind $bind")
            } catch (e: Exception) {
                e
            } finally {
                pendingTcp6 = null
            }
            beacon?.stop(if (result is CarTransport) "6/6" else "нет ГУ")
            main.post {
                linkWaiting = false
                when {
                    result is CarTransport && session != null -> result.close()
                    result is CarTransport -> {
                        if (beacon != null) CarLifeState.wireless = getString(R.string.wl_state_connected)
                        startSession(result)
                    }
                    else -> {
                        val msg = (result as Exception).message
                        if (result !is LinkSetupException) AppLog.e(kind, result)
                        AppLog.i("сервис: $msg")
                        if (beacon != null && wifiBMode) {
                            wifiBMode = false
                            CarLifeState.wireless = getString(R.string.wl_state_gave_up, (WIFI_B_WAIT_MS / 60_000).toInt())
                        }
                        // отмена ожидания кабелем: статус уже USB-сессии, не перетираем
                        if (session == null) CarLifeState.status = msg ?: "$kind: ошибка"
                        failStart()
                    }
                }
            }
        }, "link-$kind").start()
    }

    private fun startSession(transport: CarTransport) {
        CarLifeState.accessory = transport.label
        if (transport.caps.kind != TransportCaps.TCP6.kind) WLog.begin(transport.caps.kind)

        val aaPrefs = AaPrefs(this)
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val cfg = SessionConfig(
            screenOn, aaPrefs.micVrStatus, aaPrefs.fpsSetting,
            metaMusic = aaPrefs.metaMusic, metaNavi = aaPrefs.metaNavi, naviField6 = aaPrefs.naviField6,
        )
        MetaRelay.PROGRESS_UNIT = if (aaPrefs.progressSeconds) 1 else 1000

        // AA-сессия к серверу ГУ на 127.0.0.1:5277 живёт на уровне сервиса (BridgeHost), а не этой USB-сессии
        var h = host
        // режим фиксируется на подключение; тёплая AA с другой геометрией не годится (SDR уже ушёл), её закрываем
        val wantMode = aaPrefs.videoMode.effective(aaPrefs.transcode)
        if (h != null && !h.isDead && h.core.videoMode != wantMode) {
            AppLog.i("BRIDGE: видео-режим сменился (${h.core.videoMode.name} -> ${wantMode.name}), тёплую AA закрываю и поднимаю заново")
            h.shutdown("смена видео-режима")
        }
        if (h == null || h.isDead) {
            h = createHost(aaPrefs, wantMode, previous = h)
            host = h
            if (h == null) {
                // нет сертификата head unit или мост не собрался: причина уже в AaState.setupError, USB-сессию не открываем
                transport.close()
                CarLifeState.status = AaState.setupError ?: "мост не стартовал"
                failStart()
                return
            }
        } else {
            AppLog.i("BRIDGE: AA-хост уже есть, переиспользую (grace=${h.isInGrace})")
        }
        // на воздухе перекодирование обязательно: в passthrough битрейт не регулируется. Режим хоста не трогаем
        // (без перекодирования он уже R720, его можно перекодировать), хост не пересоздаём: сервер AA одноразовый
        val transcode = aaPrefs.transcode || transport.caps == TransportCaps.TCP6
        if (h.core.transcodeEnabled != transcode) {
            AppLog.i("BRIDGE видео: ${if (transcode) "воздух, перекодирование включаю принудительно" else "кабель, возвращаю passthrough по настройке"}")
            h.core.transcodeEnabled = transcode
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "headswap:session")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        }
        CarLifeState.running = true
        stopRequested = false

        val sref = arrayOfNulls<CarLifeSession>(1)
        val s = CarLifeSession(transport, cfg, h.core) { reason -> main.post { onSessionClosed(sref[0]!!, reason) } }
        sref[0] = s
        session = s
        s.start()
        h.onUsbSessionStarted()
    }

    /** Новый AA-хост: ядро, коннектор, сертификаты. null = мост не поднялся (причина в AaState.setupError). */
    private fun createHost(aaPrefs: AaPrefs, mode: VideoMode, previous: BridgeHost?): BridgeHost? {
        return try {
            val loaded = AaCreds.load(this, aaPrefs.certSet)
            AaState.setupError = null
            AaState.reset()
            AaState.cert = loaded.creds.certSet + if (loaded.error != null) " (ОТКАТ с ${loaded.requested})" else ""
            AaState.status = "мост: старт"
            val log = { line: String, ui: Boolean -> AppLog.i(line, ui) }
            val core = AaBridgeCore(log, clockUs = { SystemClock.elapsedRealtimeNanos() / 1_000 })
            core.micModeProvider = { aaPrefs.micMode }
            core.phoneMic = PhoneMicCapture(applicationContext)
            core.usbAudioEnabled = aaPrefs.usbAudio
            core.transcodeEnabled = aaPrefs.transcode
            core.videoMode = mode
            core.expectFeatureConfig = true
            if (aaPrefs.videoMode != mode) AppLog.i("BRIDGE видео-режим ${aaPrefs.videoMode.name} недоступен без перекодирования, использую ${mode.name}")
            // аудио-синки (MEDIA/SPEECH) объявляются в момент запроса телефона, когда уже известно решение по звуку
            val conn = AaConnector(
                core, loaded.creds, log,
                options = { AapOptions(sdProvider = { mode.sdOptions(fullAudio = core.audioViaUsb) }) },
                serverPrep = { phase -> prepareServer(core, phase) },
            )
            AppLog.i("BRIDGE звук по USB: ${if (aaPrefs.usbAudio) "включён (решение по FEATURE_CONFIG ГУ)" else "выключен в UI"}")
            AppLog.i("BRIDGE: AA cert=${loaded.creds.certSet}, grace=${BridgeHost.GRACE_MS} мс")
            val ref = arrayOfNulls<BridgeHost>(1)
            val h = BridgeHost(core, conn, scheduler, log, previous = previous, onTornDown = {
                main.post {
                    if (host === ref[0]) host = null
                    AppLog.i("BRIDGE: AA закрыта полностью")
                    if (session == null && host == null && !linkWaiting && !wifiBMode) finish()
                }
            })
            ref[0] = h
            h
        } catch (e: AaCertsMissingException) {
            AppLog.e("BRIDGE не стартовал: нет сертификата head unit", e)
            AaState.setupError = e.message
            AaState.status = "ошибка: нет сертификата"
            null
        } catch (e: Throwable) {
            AppLog.e("BRIDGE не стартовал (сертификаты AA)", e)
            AaState.setupError = getString(R.string.svc_bridge_failed, "${e.javaClass.simpleName}: ${e.message}")
            AaState.status = "ошибка: мост не стартовал"
            null
        }
    }

    /**
     * Foreground-тип connectedDevice; microphone добавляем, только если есть RECORD_AUDIO и микрофон телефона
     * вообще может понадобиться (режим не «машина»): иначе SecurityException. Из фона Android 14+ тип microphone
     * не даёт (while-in-use) - тогда остаёмся на connectedDevice.
     */
    private fun startForegroundTyped(n: Notification) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val micWanted = AaPrefs(this).micMode != MicMode.CAR &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micWanted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, n, type)
            if (micWanted) AppLog.i("сервис: foreground-тип connectedDevice|microphone")
        } catch (e: Exception) {      // SecurityException или ForegroundServiceStartNotAllowedException
            AppLog.e("сервис: foreground-тип с microphone не дали, остаюсь на connectedDevice", e)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
    }

    /**
     * Автозапуск сервера ГУ AA на нити коннектора: до первой попытки подключения (USB подключён), после конца
     * AA-сессии и после блокировки экрана. Ждём окончания не дольше AutoServerPolicy.waitMs, дальше коннектор идёт как обычно.
     */
    private fun prepareServer(core: AaBridgeCore, phase: ServerPhase) {
        val trigger = when (phase) {
            ServerPhase.FIRST -> Trigger.USB_ATTACH
            ServerPhase.AFTER_SESSION -> Trigger.AFTER_SESSION
            ServerPhase.RETRY -> Trigger.RETRY
        }
        val autoPref = AaPrefs(this).autoServer
        // после старта процесса служба спецвозможностей подключается позже сервиса: даём ей время
        if (trigger != Trigger.RETRY && autoPref && !AutoServer.isConnected && AutoServer.isListed(this)) {
            AutoServer.log("жду подключения службы спецвозможностей (${trigger.label})")
            AutoServer.awaitService(SERVICE_WAIT_MS)
        }
        val skip = AutoServerPolicy.skipReason(
            trigger, autoPref, AutoServer.isConnected,
            // беспровод: пока ждём машину по Wi-Fi или она подключена, сервер перезапускать есть для кого
            usbAlive = core.hasCarlifeLink || wifiBMode, warmAaSession = false, lastLocked = AutoServer.lastLocked,
            startPending = serverStartPending, consentHold = AutoServer.consentPending(),
        )
        if (skip != null) {
            if (skip == AutoServerPolicy.CONSENT_SKIP) core.showStatus(AaBridgeCore.TEXT_AA_CONSENT)
            if (trigger != Trigger.RETRY && !AutoServer.isConnected) serverStartPending = true
            // RETRY вызывается каждые несколько секунд и почти всегда пропускается: молча
            if (trigger != Trigger.RETRY) AutoServer.log("пропуск (${trigger.label}): $skip")
            return
        }
        serverStartPending = false
        core.showStatus(AutoServerPolicy.runningText(trigger))
        val run = AutoServer.start(trigger)
        val out = AutoServer.await(run, AutoServerPolicy.waitMs(trigger))
        if (out == null) {
            AutoServer.log("автоматизация ещё идёт после ${AutoServerPolicy.waitMs(trigger) / 1000} с, подключаюсь как обычно")
            return
        }
        AutoServer.log("итог для коннектора (${trigger.label}): $out")
        AutoServerPolicy.resultText(trigger, out.result)?.let { core.showStatus(it) }
    }

    /** Старт не удался: если жив AA-хост (идёт grace), сервис не гасим, иначе finish. */
    private fun failStart() {
        if (linkWaiting) return      // идёт ожидание машины по воздуху: сервис нужен
        val h = host
        if (h != null && !h.isDead) return
        if (h == null) finish()
    }

    private fun onSessionClosed(s: CarLifeSession, reason: String) {
        AppLog.i("сервис: сессия завершена ($reason)")
        if (session !== s) return
        session = null
        val h = host
        if (h == null) {
            if (!wifiBMode || stopRequested) finish()
        } else {
            // обрыв USB: CarLife закрыт, AA держим graceMs (или закрываем сразу, если Стоп)
            h.onUsbSessionClosed(immediate = stopRequested)
        }
        if (wifiBMode && !stopRequested) {
            // беспровод: ГУ ушла (обрыв Wi-Fi, выключили машину) - снова маяк и ожидание, AA пока тёплая (grace)
            CarLifeState.wireless = getString(R.string.wl_state_lost)
            WLog.ev(WLog.LINK, "rearm", "after" to reason)
            main.postDelayed({
                if (wifiBMode && !stopRequested && session == null && !linkWaiting) startWifiB()
            }, WIFI_B_REARM_MS)
        }
    }

    private fun finish() {
        CarLifeState.running = false
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(detachReceiver)
        } catch (_: Exception) {
        }
        stopRequested = true
        pendingTcp6?.abort()
        audioDiag.stop()
        session?.close("сервис уничтожен")
        host?.shutdown("сервис уничтожен")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.yegorkapomidorka.headswap.STOP"
        /** Беспровод (бета): ждать машину по Wi-Fi, ветка B (хотспот телефона + маяк). */
        const val ACTION_WIFI_B = "com.yegorkapomidorka.headswap.WIFI_B"
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
        /** Сколько автозапуск ждёт подключения включённой службы спецвозможностей. */
        private const val SERVICE_WAIT_MS = 3_000L
        /** debug tcp6: сколько ждать все 6 сокетов ГУ. */
        private const val TCP6_WAIT_MS = 60_000L
        /** Ветка B: сколько маячить и ждать ГУ (человек включает хотспот и открывает CarLife на ГУ). */
        private const val WIFI_B_WAIT_MS = 10 * 60_000L
        /** Пауза перед новым ожиданием после обрыва беспроводной сессии. */
        private const val WIFI_B_REARM_MS = 1_500L
    }
}
