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
