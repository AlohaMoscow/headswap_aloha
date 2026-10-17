package com.yegorkapomidorka.headswap.aa

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ресэмплер стерео PCM 16 бит LE 48000 -> 32000 Гц (ровно 3:2), с состоянием между вызовами.
 *
 * Полифазная реализация «×2 нулями -> FIR-ФНЧ на 96 кГц -> ÷3»: нули не считаем, на каждый выходной кадр
 * приходится TAPS/2 умножений на канал. ФНЧ - окно Кайзера, срез 15 кГц (по уровню -6 дБ) при 96 кГц,
 * т.е. всё выше Найквиста выходной частоты (16 кГц) давится. История фильтра и фаза живут между вызовами,
 * поэтому границы пакетов бесшовны: результат не зависит от того, как вход нарезан на куски.
 * Вход - кадры по 4 байта; неполный хвост (размер не кратен 4) отбрасывается.
 */
class Resampler48to32 {
    private val hist = ShortArray(HIST * 2)   // последние HIST входных кадров (L, R) предыдущих вызовов
    private var m = 0                         // позиция следующего выхода на сетке 96 кГц относительно начала нового входа

    fun reset() {
        hist.fill(0)
        m = 0
    }

    fun process(pcm: ByteArray): ByteArray {
        val n = pcm.size / 4
        if (n == 0) return ByteArray(0)
        val x = ShortArray((HIST + n) * 2)    // историю и новый вход кладём подряд: x[(HIST + i) * 2 + ch] = кадр i
        System.arraycopy(hist, 0, x, 0, HIST * 2)
        var p = 0
        var q = HIST * 2
        while (p < n * 4) {
            x[q++] = ((pcm[p].toInt() and 0xFF) or (pcm[p + 1].toInt() shl 8)).toShort()
            x[q++] = ((pcm[p + 2].toInt() and 0xFF) or (pcm[p + 3].toInt() shl 8)).toShort()
            p += 4
        }
        val outFrames = if (2 * n > m) (2 * n - m - 1) / 3 + 1 else 0
        val out = ByteArray(outFrames * 4)
        var mm = m
        var o = 0
        for (k in 0 until outFrames) {
            val c = if ((mm and 1) == 0) COEF_EVEN else COEF_ODD
            var base = (HIST + (mm shr 1)) * 2    // кадр, с которого идёт свёртка назад по времени
            var l = 0f
            var r = 0f
            for (i in 0 until PHASE_TAPS) {
                val h = c[i]
                l += h * x[base]
                r += h * x[base + 1]
                base -= 2
            }
            val sl = sat(l)
            val sr = sat(r)
            out[o++] = sl.toByte()
            out[o++] = (sl shr 8).toByte()
            out[o++] = sr.toByte()
            out[o++] = (sr shr 8).toByte()
            mm += 3
        }
        m = mm - 2 * n
        System.arraycopy(x, n * 2, hist, 0, HIST * 2)
        return out
    }

    private fun sat(v: Float): Int {
        val i = Math.round(v)
        return if (i > 32767) 32767 else if (i < -32768) -32768 else i
    }

    companion object {
        const val TAPS = 64
        const val CUTOFF_HZ = 15000.0
        const val KAISER_BETA = 6.0
        private const val PHASE_TAPS = TAPS / 2
        private const val HIST = PHASE_TAPS

        /** Прототип ФНЧ на 96 кГц с усилением 2 (компенсирует вставку нулей), разложенный по фазам ×2. */
        private val COEF_EVEN = FloatArray(PHASE_TAPS)
        private val COEF_ODD = FloatArray(PHASE_TAPS)

        init {
            val fc = CUTOFF_HZ / 96000.0
            val mid = (TAPS - 1) / 2.0
            val h = DoubleArray(TAPS)
            for (j in 0 until TAPS) {
                val t = j - mid
                val s = if (t == 0.0) 2 * fc else sin(2 * PI * fc * t) / (PI * t)
                val u = t / mid
                h[j] = s * bessel0(KAISER_BETA * sqrt(max(0.0, 1 - u * u))) / bessel0(KAISER_BETA)
            }
            val gain = 2.0 / h.sum()
            for (i in 0 until PHASE_TAPS) {
                COEF_EVEN[i] = (h[2 * i] * gain).toFloat()
                COEF_ODD[i] = (h[2 * i + 1] * gain).toFloat()
            }
        }

        private fun bessel0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (k < 50) {
                val f = x / (2 * k)
                term *= f * f
                sum += term
                if (term < 1e-12 * sum) break
                k++
            }
            return sum
        }
    }
}
