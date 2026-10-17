package com.yegorkapomidorka.headswap

/**
 * Приоритет нитей видео/звука моста. По умолчанию все нити приложения равны (nice 0) и на нагруженном телефоне делят
 * CPU с фоном наравне: кадр ждёт планировщика, отсюда рывки. THREAD_PRIORITY_DISPLAY (-4) - умеренно, ниже нитей
 * отрисовки самой AA (URGENT_DISPLAY), чтобы не отнимать у неё время. Работы не прибавляет, меняет только очередь.
 * Отказ (SecurityException, JVM-тесты) молча игнорируется.
 */
object ThreadPrio {
    /** = android.os.Process.THREAD_PRIORITY_DISPLAY. */
    const val DISPLAY = -4

    fun raise(level: Int = DISPLAY) {
        try {
            android.os.Process.setThreadPriority(level)
        } catch (_: Throwable) {
        }
    }
}
