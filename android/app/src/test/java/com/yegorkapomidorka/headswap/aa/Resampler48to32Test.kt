package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class Resampler48to32Test {
    /** Стерео-синус (R = L) в int16 LE на 48 кГц. */
    private fun sine(freq: Double, amp: Double, frames: Int): ByteArray {
        val b = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val s = (amp * sin(2 * PI * freq * i / 48000.0)).roundToInt()
            for (c in 0..1) {
                b[i * 4 + c * 2] = s.toByte()
                b[i * 4 + c * 2 + 1] = (s shr 8).toByte()
            }
        }
        return b
    }

    /** Левый канал выхода как Int. */
    private fun left(pcm: ByteArray) = IntArray(pcm.size / 4) { i ->
        ((pcm[i * 4].toInt() and 0xFF) or (pcm[i * 4 + 1].toInt() shl 8)).toShort().toInt()
    }

    @Test
    fun ratioIsTwoThirds() {
        val rs = Resampler48to32()
        val first = rs.process(ByteArray(8192))
        assertTrue("8192 Б -> ~5461 Б, получили ${first.size}", abs(first.size - 5461) <= 4)
        var inFrames = 2048L
        var outFrames = (first.size / 4).toLong()
        repeat(999) {
            outFrames += rs.process(ByteArray(8192)).size / 4
            inFrames += 2048
        }
        val expected = inFrames * 2 / 3
        assertTrue("кадров на выходе $outFrames, ждали $expected", abs(outFrames - expected) <= 1)
    }

    @Test
    fun emptyAndPartialInput() {
        val rs = Resampler48to32()
        assertEquals(0, rs.process(ByteArray(0)).size)
        assertEquals(0, rs.process(ByteArray(3)).size)
    }

    @Test
    fun chunkingDoesNotChangeResult() {
        val src = sine(1000.0, 10000.0, 20000)
        // добавим шум в правый канал, чтобы каналы различались
        val rnd = java.util.Random(1)
        for (i in 0 until 20000) {
            val v = rnd.nextInt(2000) - 1000
            src[i * 4 + 2] = v.toByte()
            src[i * 4 + 3] = (v shr 8).toByte()
        }
        val whole = Resampler48to32().process(src)

        val rs = Resampler48to32()
        val parts = java.io.ByteArrayOutputStream()
        var off = 0
        val sizes = intArrayOf(4, 8, 12, 4, 400, 36, 8192, 20, 1000, 4)
        var k = 0
        while (off < src.size) {
            val n = minOf(sizes[k++ % sizes.size], src.size - off)
            parts.write(rs.process(src.copyOfRange(off, off + n)))
            off += n
        }
        assertArrayEquals(whole, parts.toByteArray())
    }

    @Test
    fun resetRestartsFromScratch() {
        val src = sine(3000.0, 8000.0, 4096)
        val rs = Resampler48to32()
        val a = rs.process(src)
        rs.process(sine(500.0, 20000.0, 777))
        rs.reset()
        assertArrayEquals(a, rs.process(src))
    }

    @Test
    fun sine1kHzKeepsFrequencyAndAmplitude() {
        val frames = 48000
        val out = left(Resampler48to32().process(sine(1000.0, 10000.0, frames)))
        val tail = out.copyOfRange(100, out.size)
        var crossings = 0
        for (i in 1 until tail.size) if ((tail[i - 1] < 0) != (tail[i] < 0)) crossings++
        val seconds = tail.size / 32000.0
        val hz = crossings / 2.0 / seconds
        assertEquals("частота по переходам через ноль", 1000.0, hz, 10.0)
        val peak = tail.maxOf { abs(it) }
        assertTrue("амплитуда $peak", peak in 9000..11000)
    }

    @Test
    fun sine20kHzIsAttenuated() {
        val out = left(Resampler48to32().process(sine(20000.0, 10000.0, 48000)))
        val peak = out.copyOfRange(100, out.size).maxOf { abs(it) }
        assertTrue("20 кГц должны гаситься минимум в 10 раз, пик $peak", peak < 1000)
    }

    @Test
    fun saturatesInsteadOfWrapping() {
        // полный размах на низкой частоте: выход не должен «перескакивать» знак
        val out = left(Resampler48to32().process(sine(100.0, 32767.0, 48000)))
        var maxJump = 0
        for (i in 1 until out.size) maxJump = maxOf(maxJump, abs(out[i] - out[i - 1]))
        assertTrue("скачок $maxJump", maxJump < 3000)
    }
}
