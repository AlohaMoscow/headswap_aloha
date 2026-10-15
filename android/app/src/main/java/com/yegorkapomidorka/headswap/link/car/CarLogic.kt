package com.yegorkapomidorka.headswap.link.car

/** Чистая логика автостарта по Bluetooth машины, без android.*: проверяется JVM-тестами. */
object CarMac {
    /** Один и тот же адрес? Регистр не важен (CDM отдаёт MAC строчными, BluetoothDevice - заглавными), null никогда не равен. */
    fun same(a: String?, b: String?): Boolean = a != null && b != null && a.equals(b, ignoreCase = true)
}

/**
 * Debounce отключения машины: ГУ при переподключении даёт всплески ACL_DISCONNECTED/ACL_CONNECTED (в логе Vezel 5 циклов за 7 с),
 * гасить ожидание сразу нельзя. [arm] запускает отсчёт (повторный - сдвигает), [cancel] снимает (пришёл ACL_CONNECTED),
 * [fire] по таймеру отвечает, пора ли гасить: срок вышел и отмены не было; срабатывает один раз. Нить одна (главная).
 */
class AutoOffDebounce(private val delayMs: Long, private val clock: () -> Long) {
    private var dueAt = -1L

    val pending: Boolean get() = dueAt >= 0

    fun arm() {
        dueAt = clock() + delayMs
    }

    fun cancel() {
        dueAt = -1L
    }

    fun fire(): Boolean {
        if (dueAt < 0 || clock() < dueAt) return false
        dueAt = -1L
        return true
    }
}

/** Не чаще раза в [intervalMs]: чужие устройства шлют ACL постоянно, лог не засоряем. */
class MinInterval(private val intervalMs: Long, private val clock: () -> Long) {
    private var last = -1L

    fun tryAcquire(): Boolean {
        val now = clock()
        if (last >= 0 && now - last < intervalMs) return false
        last = now
        return true
    }
}

/** Через сколько ожидания по Wi-Fi без 6/6 просим вход в сеть машины (до этого ГУ может успеть сама прийти в сеть телефона). */
const val CAR_NET_DELAY_MS = 15_000L

/** Сколько система ищет сеть машины и ждёт одобрения; не нашла - подсказка про Wi-Fi вручную. */
const val CAR_NET_TIMEOUT_MS = 90_000L

/** События, при которых решается судьба запроса сети машины. */
enum class CarNetEvent { AUTO_OFF, STOP, USB_WINS, DESTROY, SESSION_START, SESSION_END }

/** Чистая логика запроса сети машины, без android.*: проверяется JVM-тестами. */
object CarNetPolicy {
    /**
     * Пора ли слать запрос: сеть машины задана, идёт ожидание по Wi-Fi (авто или ручное), никакой сессии (ни USB, ни
     * воздух) и 6/6 ещё нет, запрос не висит и не выдан, с начала ожидания прошло не меньше [CAR_NET_DELAY_MS].
     */
    fun shouldRequest(
        carWifiSet: Boolean,
        autoOrManualWaiting: Boolean,
        sessionActive: Boolean,
        usbActive: Boolean,
        alreadyRequesting: Boolean,
        waitedMs: Long,
        sixOfSixReady: Boolean,
    ): Boolean = carWifiSet && autoOrManualWaiting && !sessionActive && !usbActive && !alreadyRequesting &&
        !sixOfSixReady && waitedMs >= CAR_NET_DELAY_MS

    /**
     * Отпускать ли сеть. Остановка ожидания, кабель и смерть сервиса - да. Начало и конец беспроводной сессии - нет: сеть
     * машины держит её TCP, а после конца сессии ожидание обычно возобновляется на той же сети.
     */
    fun shouldRelease(event: CarNetEvent): Boolean = when (event) {
        CarNetEvent.AUTO_OFF, CarNetEvent.STOP, CarNetEvent.USB_WINS, CarNetEvent.DESTROY -> true
        CarNetEvent.SESSION_START, CarNetEvent.SESSION_END -> false
    }
}
