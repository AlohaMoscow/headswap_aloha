package com.yegorkapomidorka.headswap.aa

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Целевой битрейт энкодера по состоянию видео-очереди CarLife: ГУ принимает видео с ограниченной скоростью
 * (Kia Seltos ~210 КиБ/с), энкодер на 6 Мбит/с забивает очередь и задержка растёт до секунд. Вниз - до 0.8 скорости слива,
 * вверх - ступенями +10% после долгого затишья. Чистая логика: время приходит параметром, кодек не трогает.
 * Зовётся с одной нити; [current] читается с любой.
 */
class BitratePolicy(val ceilingBps: Int, floorBps: Int = FLOOR_BPS) {
    val floorBps = min(floorBps, ceilingBps)

    /** Текущая цель, бит/с. Старт с потолка (mode.bitrate). */
    @Volatile var current = ceilingBps
        private set

    /** Бэклог после сглаживания, мс (для лога и тестов). */
    var smoothedMs = 0.0
        private set

    /** Описание последнего изменения (для одной строки лога у вызывающего). */
    var lastReason = ""
        private set

    private var lastSampleMs = NEVER
    private var lastDownMs = NEVER
    private var calmSinceMs = NEVER
    private var upNotBeforeMs = NEVER

    /**
     * Очередной замер: бэклог очереди (мс) и скорость слива (байт/с, 0 = ещё не измерена). Возвращает новый битрейт,
     * если он изменился, иначе null. Бэклог сглаживается EWMA по времени (tau 1 с), поэтому одиночный IDR на 160 КБ
     * не даёт отката, а результат не зависит от частоты вызовов.
     */
    fun update(nowMs: Long, backlogMs: Double, drainBytesPerSec: Double): Int? {
        smoothedMs = if (lastSampleMs == NEVER) backlogMs else {
            val dt = max(nowMs - lastSampleMs, 0L)
            smoothedMs + (1 - exp(-dt / TAU_MS)) * (backlogMs - smoothedMs)
        }
        lastSampleMs = nowMs

        if (smoothedMs < UP_BACKLOG_MS) {
            if (calmSinceMs == NEVER) calmSinceMs = nowMs
        } else {
            calmSinceMs = NEVER
        }

        if (smoothedMs > DOWN_BACKLOG_MS && drainBytesPerSec > 0 && (lastDownMs == NEVER || nowMs - lastDownMs >= DOWN_INTERVAL_MS)) {
            val target = max(floorBps, min(current, (drainBytesPerSec * 8 * DOWN_FACTOR).toInt()))
            if (target < current) {
                lastReason = "бэклог %.0f мс (сглаженный) > %d мс, слив %.0f КиБ/с".format(smoothedMs, DOWN_BACKLOG_MS, drainBytesPerSec / 1024)
                current = target
                lastDownMs = nowMs
                calmSinceMs = NEVER
                upNotBeforeMs = nowMs + UP_WAIT_MS
                return target
            }
        }

        if (calmSinceMs != NEVER && nowMs - calmSinceMs >= UP_WAIT_MS && nowMs >= upNotBeforeMs && current < ceilingBps) {
            val target = min(ceilingBps, max(current + 1, (current * UP_FACTOR).toInt()))
            lastReason = "бэклог < %d мс уже %d с".format(UP_BACKLOG_MS, (nowMs - calmSinceMs) / 1000)
            current = target
            calmSinceMs = nowMs       // следующая ступень - через ещё 10 с затишья
            upNotBeforeMs = nowMs + UP_WAIT_MS
            return target
        }
        return null
    }

    companion object {
        const val FLOOR_BPS = 800_000
        const val DOWN_BACKLOG_MS = 300L
        const val UP_BACKLOG_MS = 100L
        const val DOWN_INTERVAL_MS = 1000L
        const val UP_WAIT_MS = 10_000L
        const val DOWN_FACTOR = 0.8
        const val UP_FACTOR = 1.1
        private const val TAU_MS = 1000.0
        private const val NEVER = Long.MIN_VALUE
    }
}
