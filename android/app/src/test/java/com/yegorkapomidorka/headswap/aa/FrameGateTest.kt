package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FrameGateTest {
    /** Сколько кадров пропустит гейт за seconds секунд входа с частотой inFps (джиттер +-jitterMs). */
    private fun passed(gate: FrameGate, inFps: Int, seconds: Int, jitterMs: Int = 0): Int {
        val rnd = Random(42)
        val periodNs = 1_000_000_000L / inFps
        var n = 0
        for (i in 0 until inFps * seconds) {
            val j = if (jitterMs > 0) (rnd.nextInt(2 * jitterMs + 1) - jitterMs) * 1_000_000L else 0L
            if (gate.shouldEmit(1_000_000_000L + i * periodNs + j)) n++
        }
        return n
    }

    @Test
    fun input30Target15GivesAbout15() {
        assertEquals(150.0, passed(FrameGate(15), 30, 10).toDouble(), 3.0)
        assertEquals(150.0, passed(FrameGate(15), 30, 10, jitterMs = 3).toDouble(), 8.0)
    }

    @Test
    fun input30Target30LosesNothing() {
        assertEquals(300, passed(FrameGate(30), 30, 10))
        // джиттер входа +-5 мс: цель 30 пропускает всё
        assertEquals(300, passed(FrameGate(30), 30, 10, jitterMs = 5))
    }

    @Test
    fun lowInputPassesEverything() {
        assertEquals(100, passed(FrameGate(30), 10, 10))
        assertEquals(100, passed(FrameGate(15), 10, 10))
        assertEquals(100, passed(FrameGate(15), 10, 10, jitterMs = 5))
    }

    @Test
    fun slotsKeepRateWhenInputIsFasterThanTarget() {
        // 30 -> 20 (слоты по 50 мс на входе 33.3 мс): средняя частота выхода 20
        assertEquals(200.0, passed(FrameGate(20), 30, 10).toDouble(), 3.0)
        assertEquals(200.0, passed(FrameGate(20), 30, 10, jitterMs = 4).toDouble(), 8.0)
    }

    @Test
    fun unlimitedAndFirstFrame() {
        assertEquals(300, passed(FrameGate(0), 30, 10))
        val g = FrameGate(15)
        assertTrue(g.shouldEmit(5L))                    // первый кадр - сразу, даже при нулевом времени
        assertFalse(g.shouldEmit(5L + 10_000_000L))
        assertFalse(g.shouldEmit(5L + 30_000_000L))     // слот 66.7 мс, берём с 33.3 мс
        assertTrue(g.shouldEmit(5L + 40_000_000L))
        g.reset()
        assertTrue(g.shouldEmit(5L + 61_000_000L))
    }

    @Test
    fun lagBeyondPeriodRestartsGrid() {
        val g = FrameGate(15)
        assertTrue(g.shouldEmit(0L))
        assertTrue(g.shouldEmit(300_000_000L))          // пауза входа: кадр берётся, сетка с нового места
        assertFalse(g.shouldEmit(310_000_000L))         // без серии догоняющих кадров
        assertFalse(g.shouldEmit(330_000_000L))
        assertTrue(g.shouldEmit(370_000_000L))
    }

    @Test
    fun targetChangesOnTheFly() {
        val g = FrameGate(30)
        assertTrue(g.shouldEmit(0L))
        assertTrue(g.shouldEmit(33_000_000L))
        g.targetFps = 10
        assertEquals(100_000_000L, g.periodNs())
        assertTrue(g.shouldEmit(66_000_000L))           // после перехода с пропуска всего сетка начинается с первого кадра
        assertFalse(g.shouldEmit(99_000_000L))
        assertTrue(g.shouldEmit(66_000_000L + 100_000_000L))
        g.targetFps = 30
        assertTrue(g.shouldEmit(170_000_000L))
        assertTrue(g.shouldEmit(171_000_000L))
    }
}

class RelayTickTest {
    private val ms = 1_000_000L

    @Test
    fun staticScreenWakesOnlyForRedraw() {
        // только что нарисовали: следующий tick ровно через idle, а не через 20 мс
        assertEquals(100L, RelayTick.delayMs(1000 * ms, 1000 * ms, 100, false, 1000 * ms, 0, false))
        assertEquals(60L, RelayTick.delayMs(1040 * ms, 1000 * ms, 100, false, 1000 * ms, 0, false))
        // доли миллисекунды округляются вверх: tick не придёт раньше срока
        assertEquals(1L, RelayTick.delayMs(1099 * ms + 1, 1000 * ms, 100, false, 0, 0, false))
        // срок прошёл, а кадр не ушёл (гейт не пустил): повтор шагом, как прежний tick
        assertEquals(RelayTick.STEP_MS, RelayTick.delayMs(1100 * ms, 1000 * ms, 100, false, 0, 0, false))
        assertEquals(RelayTick.STEP_MS, RelayTick.delayMs(5000 * ms, 1000 * ms, 100, false, 0, 0, false))
    }

    @Test
    fun gatedFrameIsDueAfterGatePeriod() {
        // 15 fps: кадр задержан гейтом в 1010 мс -> tick в 1010 + 66.7 мс, раньше перерисовки по idle
        val period = 1_000_000_000L / 15
        assertEquals(67L, RelayTick.delayMs(1010 * ms, 1000 * ms, 100, true, 1010 * ms, period, false))
        // без задержанного кадра период гейта не важен
        assertEquals(90L, RelayTick.delayMs(1010 * ms, 1000 * ms, 100, false, 1010 * ms, period, false))
        // гейт выключен (0): skipped не сокращает срок
        assertEquals(90L, RelayTick.delayMs(1010 * ms, 1000 * ms, 100, true, 1010 * ms, 0, false))
    }

    @Test
    fun introAnimatesEveryStep() {
        assertEquals(RelayTick.STEP_MS, RelayTick.delayMs(1000 * ms, 1000 * ms, 100, false, 1000 * ms, 0, true))
    }
}
