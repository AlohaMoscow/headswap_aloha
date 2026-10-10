package com.yegorkapomidorka.headswap.ui

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.aa.AaPrefs
import com.yegorkapomidorka.headswap.aa.AaState
import com.yegorkapomidorka.headswap.autosrv.AutoResult
import com.yegorkapomidorka.headswap.autosrv.AutoServer

/** Сводка состояния для главного экрана и уведомления. Чистая логика без Android: считается из Inputs. */
enum class Phase { OFF, WAITING, CONNECTED, RECONNECTING, ERROR }

enum class StageState { DONE, ACTIVE, PAUSE, ERR, TODO, PEND }

enum class Tone { PRI, OK, WARN, ERR }

enum class HeroIcon { POWER, PLUG, CAR, REFRESH, ALERT }

enum class PrimaryAction { START_SERVER, RESTART_SERVER }

enum class OutlineAction { OPEN_AA, STOP }

data class HomeState(
    val phase: Phase,
    val tone: Tone,
    val icon: HeroIcon,
    val title: String,
    val sub: String,
    val stages: List<Pair<String, Pair<StageState, String>>>,
    /** Оставшиеся секунды grace (только RECONNECTING), иначе -1. */
    val graceLeftS: Int,
    val graceTotalS: Int,
    val primary: PrimaryAction?,
    val outline: OutlineAction?,
    val logLink: Boolean,
    val showChain: Boolean,
    val notifTitle: String,
    val notifText: String,
)

class Inputs(
    val bridgeRunning: Boolean,
    val aaStatus: String,
    val protocolKnown: Boolean,
    /** Сервер ГУ AA запущен (по нашему запуску), пока сессии нет. */
    val serverUp: Boolean,
    /** Автозапуск включён и служба спецвозможностей подключена: сервер поднимется сам при подключении кабеля. */
    val autoReady: Boolean,
    /** Предыдущая сессия оборвалась не по кнопке «Стоп». */
    val lost: Boolean,
    val graceLeftS: Int,
    val graceTotalS: Int,
    val usbAudio: Boolean,
    /** Мост не стартует из-за настройки (нет сертификата head unit): текст для пользователя. */
    val setupError: String? = null,
    /** Размер видео на ГУ («1280×720»): фактический режим сессии. */
    val videoRes: String = "1280×720",
)

object HomeLogic {
    val LABELS = listOf("Сервер ГУ", "Магнитола", "Android Auto", "Видео", "Звук по USB")
    private const val TEXT_START_CHAIN = "Запускайте перед каждой поездкой: после сессии сервер выключается."

    fun compute(i: Inputs): HomeState {
        val st = i.aaStatus
        val reconnecting = i.bridgeRunning && "USB потерян" in st
        val connected = i.bridgeRunning && st.startsWith("мост: AA -> CarLife")
        val refused = i.bridgeRunning && "жду сервер ГУ" in st
        val total = if (i.graceTotalS > 0) i.graceTotalS else 8
        return when {
            i.setupError != null && !i.bridgeRunning -> state(
                Phase.ERROR, Tone.ERR, HeroIcon.ALERT, "Мост не запущен", i.setupError,
                listOf(
                    StageState.ERR to "нет сертификата", StageState.PEND to "", StageState.PEND to "",
                    StageState.PEND to "", StageState.PEND to "",
                ),
                logLink = true,
                notifTitle = "Мост не запущен", notifText = i.setupError,
            )
            reconnecting -> {
                val left = i.graceLeftS.coerceAtLeast(0)
                state(
                    Phase.RECONNECTING, Tone.WARN, HeroIcon.REFRESH, "Восстанавливаю связь",
                    "USB отключился. Сессия Android Auto ждёт до $total с.",
                    listOf(
                        StageState.DONE to "держит сессию", StageState.ACTIVE to "переподключаю",
                        StageState.PAUSE to "на паузе", StageState.PAUSE to "на паузе", StageState.PAUSE to "на паузе",
                    ),
                    outline = OutlineAction.STOP, graceLeft = left, graceTotal = total,
                    notifText = "USB отключился · ещё $left с",
                )
            }
            connected -> state(
                Phase.CONNECTED, Tone.OK, HeroIcon.CAR, "Всё работает",
                "Android Auto на экране машины. Телефон можно убрать.",
                listOf(
                    StageState.DONE to "работает", StageState.DONE to "CarLife 2.0", StageState.DONE to "подключён",
                    StageState.DONE to i.videoRes,
                    if (i.usbAudio) StageState.DONE to "USB" else StageState.PEND to "выключен",
                ),
                outline = OutlineAction.STOP,
                notifText = if (i.usbAudio) "Видео · звук по USB" else "Видео",
            )
            refused -> state(
                Phase.OFF, Tone.WARN, HeroIcon.POWER, "Сервер не запущен",
                "Кабель подключён, но сервер ГУ не отвечает. $TEXT_START_CHAIN",
                listOf(
                    StageState.TODO to "не запущен", StageState.DONE to "CarLife 2.0", StageState.PEND to "",
                    StageState.PEND to "", StageState.PEND to "",
                ),
                primary = PrimaryAction.START_SERVER, showChain = true,
                notifText = "Сервер ГУ не запущен",
            )
            i.bridgeRunning -> state(
                Phase.WAITING, Tone.PRI, HeroIcon.PLUG, "Подключаюсь",
                if ("отключился" in st) "Android Auto отключился, перезапускаю сервер ГУ." else "Кабель подключён. Жду Android Auto.",
                listOf(
                    StageState.ACTIVE to "подключаюсь",
                    if (i.protocolKnown) StageState.DONE to "CarLife 2.0" else StageState.ACTIVE to "рукопожатие",
                    StageState.PEND to "", StageState.PEND to "", StageState.PEND to "",
                ),
                outline = OutlineAction.STOP,
                notifText = "Кабель подключён · жду Android Auto",
            )
            i.lost -> state(
                Phase.ERROR, Tone.ERR, HeroIcon.ALERT, "Связь потеряна",
                "Обрыв дольше $total с. Перезапустите сервер ГУ и переподключите кабель.",
                listOf(
                    StageState.ERR to "закрыт", StageState.DONE to "CarLife 2.0", StageState.ERR to "отключён",
                    StageState.PEND to "", StageState.PEND to "",
                ),
                primary = PrimaryAction.RESTART_SERVER, logLink = true,
                notifTitle = "Перезапустите сервер ГУ", notifText = "Связь потеряна дольше $total с",
            )
            i.serverUp || i.autoReady -> state(
                Phase.WAITING, Tone.PRI, HeroIcon.PLUG, "Готов к поездке",
                if (i.serverUp) "Подключите телефон к магнитоле кабелем." else "Подключите телефон к магнитоле кабелем. Сервер ГУ запущу сам.",
                listOf(
                    StageState.DONE to (if (i.serverUp) "запущен" else "запущу сам"), StageState.ACTIVE to "ждём кабель",
                    StageState.PEND to "", StageState.PEND to "", StageState.PEND to "",
                ),
                outline = OutlineAction.OPEN_AA,
                notifText = if (i.serverUp) "Сервер ГУ запущен" else "Жду кабель",
            )
            else -> state(
                Phase.OFF, Tone.WARN, HeroIcon.POWER, "Сервер не запущен", TEXT_START_CHAIN,
                listOf(
                    StageState.TODO to "не запущен", StageState.PEND to "", StageState.PEND to "",
                    StageState.PEND to "", StageState.PEND to "",
                ),
                primary = PrimaryAction.START_SERVER, showChain = true,
                notifText = "Сервер ГУ не запущен",
            )
        }
    }

    private fun state(
        phase: Phase, tone: Tone, icon: HeroIcon, title: String, sub: String,
        stages: List<Pair<StageState, String>>,
        primary: PrimaryAction? = null, outline: OutlineAction? = null, logLink: Boolean = false,
        showChain: Boolean = false, graceLeft: Int = -1, graceTotal: Int = 0,
        notifTitle: String = title, notifText: String,
    ) = HomeState(
        phase, tone, icon, title, sub, LABELS.zip(stages), graceLeft, graceTotal,
        primary, outline, logLink, showChain, notifTitle, notifText,
    )
}

/**
 * Наблюдаемая сводка (только чтение из CarLifeState/AaState/AutoServer + то, что сама знает UI-часть):
 * собирает Inputs и помнит переходы (оборвалась ли сессия, когда подключились, до какого момента держится grace).
 * Вызывается с главной нити: экраном и тикером уведомления.
 */
object HeadswapStatus {
    private var prevRunning = false
    private var sawConnected = false
    private var userStopped = false
    private var lost = false
    private var serverUp = false
    private var lastOutcome: Any? = null
    private var graceDeadline = 0L
    private var graceTotalS = 0
    var connectedSince = 0L
        private set

    private val graceRe = Regex("""жива\s+(\d+)\s*с""")

    /** Пользователь нажал «Запустить сервер ГУ» (или открыл настройки AA): считаем сервер запущенным. */
    fun markServerRequested() {
        serverUp = true
        lost = false
        userStopped = false
    }

    /** «Остановить»: оборвавшейся связью это не считается, одноразовый сервер ГУ закрыт. */
    fun markUserStop() {
        userStopped = true
        lost = false
        serverUp = false
    }

    fun inputs(ctx: Context): Inputs {
        val now = SystemClock.elapsedRealtime()
        val running = CarLifeState.running
        val st = AaState.status
        val connectedNow = running && st.startsWith("мост: AA -> CarLife")
        val reconnectingNow = running && "USB потерян" in st

        if (running && !prevRunning) {
            lost = false
            userStopped = false
            sawConnected = false
            connectedSince = 0
        }
        if (connectedNow || reconnectingNow) sawConnected = true
        if (connectedNow) {
            if (connectedSince == 0L) connectedSince = System.currentTimeMillis()
        } else if (!reconnectingNow) {
            connectedSince = 0
        }
        if (reconnectingNow) {
            if (graceDeadline == 0L) {
                graceTotalS = graceRe.find(st)?.groupValues?.get(1)?.toIntOrNull() ?: 8
                graceDeadline = now + graceTotalS * 1000L
            }
        } else {
            graceDeadline = 0
        }
        if (!running && prevRunning) {
            if (sawConnected && !userStopped) lost = true
            serverUp = false
            sawConnected = false
            connectedSince = 0
        }
        prevRunning = running

        val last = AutoServer.last
        if (last !== lastOutcome) {
            lastOutcome = last
            if (last?.result == AutoResult.OK && !running) serverUp = true
        }

        val aaPrefs = AaPrefs(ctx)
        val left = if (graceDeadline > 0) ((graceDeadline - now + 999) / 1000).toInt() else -1
        return Inputs(
            bridgeRunning = running,
            aaStatus = st,
            protocolKnown = CarLifeState.protocol != "-",
            serverUp = serverUp,
            autoReady = aaPrefs.autoServer && AutoServer.isConnected,
            lost = lost,
            graceLeftS = left,
            graceTotalS = graceTotalS,
            usbAudio = aaPrefs.usbAudio,
            setupError = AaState.setupError,
            videoRes = (CarLifeState.videoMode ?: aaPrefs.videoMode.effective(aaPrefs.transcode)).outLabel,
        )
    }

    fun current(ctx: Context): HomeState = HomeLogic.compute(inputs(ctx))

    /** Подключён ли USB-аксессуар (для подсказок на экране). */
    fun accessoryPresent(ctx: Context): Boolean =
        ctx.getSystemService(UsbManager::class.java).accessoryList?.isNotEmpty() == true
}
