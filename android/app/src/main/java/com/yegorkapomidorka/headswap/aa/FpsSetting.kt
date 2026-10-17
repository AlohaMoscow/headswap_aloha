package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R

/**
 * Настройка «Частота кадров»: Авто (как просит ГУ в VIDEO_ENCODER_INIT) или фиксированная.
 * Читается один раз на подключение (SessionConfig), в passthrough не действует.
 */
enum class FpsSetting(val titleRes: Int, val subRes: Int, val fixed: Int?) {
    AUTO(R.string.fps_auto_title, R.string.fps_auto_sub, null),
    FPS15(R.string.fps_15_title, R.string.fps_15_sub, 15),
    FPS30(R.string.fps_30_title, R.string.fps_30_sub, 30);

    companion object {
        val DEFAULT = AUTO

        /** По имени из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): FpsSetting = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}

/**
 * Гейт кадров перед энкодером: по таймстемпам решает, отдавать ли кадр. Энкодер с Surface-входом кодирует
 * каждый swap, поэтому KEY_FRAME_RATE частоту не ограничивает - её режет только этот гейт.
 * Цель >= 30 (или <= 0) - пропускает всё: вход AA быстрее 30 не бывает, а допуск по таймстемпам на 30/30 терял бы кадры
 * от джиттера входа. Ниже 30 - сетка слотов: слот каждые period, кадр берётся за полпериода до слота и позже.
 * Вызывается с одной нити (GL-реле); targetFps можно менять с любой.
 */
class FrameGate(initialFps: Int) {
    @Volatile var targetFps: Int = initialFps
    private var nextNs = NONE

    /** true = кадр отдать, false = пропустить. */
    fun shouldEmit(nowNs: Long): Boolean {
        val fps = targetFps
        if (fps <= 0 || fps >= PASS_ALL_FPS) {
            nextNs = NONE           // при переключении на меньшую цель сетка начнётся заново
            return true
        }
        val period = 1_000_000_000L / fps
        if (nextNs == NONE) {
            nextNs = nowNs + period
            return true
        }
        if (nowNs < nextNs - period / 2) return false
        nextNs += period
        if (nowNs - nextNs > period) nextNs = nowNs + period     // отстали больше чем на период (вход медленнее цели): сетка заново
        return true
    }

    /** Период целевой частоты в нс (0 = без ограничения). */
    fun periodNs(): Long = targetFps.let { if (it <= 0) 0L else 1_000_000_000L / it }

    /** Новая AA-сессия: первый кадр отдаётся сразу. */
    fun reset() {
        nextNs = NONE
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val PASS_ALL_FPS = 30
    }
}

/**
 * Когда GL-реле просыпаться в следующий раз. Раньше tick шёл каждые 20 мс всегда (50 пробуждений в секунду и на
 * статичной картинке, и без кадров вовсе), хотя работа нужна только к двум моментам: перерисовка на статике
 * (последний кадр + idle) и выдача кадра, который задержал гейт fps (свежий кадр + период гейта). Чистая функция.
 */
object RelayTick {
    /** Шаг анимации перехода и повтор, если кадр уже должен уйти, а гейт его не пустил (как прежний tick). */
    const val STEP_MS = 20L

    /**
     * Задержка до следующего tick, мс. [skipped] - последний кадр задержан гейтом с периодом [gatePeriodNs] (0 - гейта нет).
     * Пока идёт поток кадров, каждый кадр рисуется сам (onFrame) и переносит tick: реле не просыпается вовсе.
     */
    fun delayMs(nowNs: Long, lastDrawNs: Long, idleMs: Long, skipped: Boolean, lastFreshNs: Long, gatePeriodNs: Long, intro: Boolean): Long {
        if (intro) return STEP_MS
        var dueNs = lastDrawNs + idleMs * 1_000_000
        if (skipped && gatePeriodNs > 0) dueNs = minOf(dueNs, lastFreshNs + gatePeriodNs)
        val leftNs = dueNs - nowNs
        if (leftNs <= 0) return STEP_MS
        return ((leftNs + 999_999) / 1_000_000).coerceIn(1, idleMs)
    }
}
