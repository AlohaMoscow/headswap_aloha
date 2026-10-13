package com.yegorkapomidorka.headswap.aa

import com.yegorkapomidorka.headswap.R
import java.util.Locale

/** Центральный прямоугольник исходного кадра в долях (u вправо, v вверх; кроп симметричный, поэтому ориентация v не важна). */
data class VideoCrop(val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    val isNone: Boolean get() = this == NONE

    companion object {
        val NONE = VideoCrop(0f, 0f, 1f, 1f)
    }
}

/**
 * Разрешение видео: единственный источник правды для AA (SDR), выхода CarLife (INIT_DONE, энкодер), кропа и тача.
 * Режим снимается один раз на подключение (AaBridgeCore.videoMode) и дальше везде берётся оттуда, prefs не читаются.
 */
enum class VideoMode(
    val titleRes: Int,
    val subRes: Int,
    /** Разрешение AA в SDR (SDR-строка AapMessages.serviceDiscoveryResponse). */
    val aaResolution: String,
    /** Поля margin_width / margin_height в VideoConfiguration SDR (суммарно, делятся пополам по краям). */
    val marginW: Int,
    val marginH: Int,
    val density: Int,
    /** Размер кадра, который уходит на ГУ (INIT_DONE и энкодер). */
    val outW: Int,
    val outH: Int,
    val bitrate: Int,
) {
    R800(R.string.video_r800_title, R.string.video_r800_sub, "800x480", 0, 0, 120, 800, 480, 4_000_000),
    R720(R.string.video_r720_title, R.string.video_r720_sub, "1280x720", 0, 0, 160, 1280, 720, 4_000_000),
    R1080(R.string.video_r1080_title, R.string.video_r1080_sub, "1920x1080", 0, 0, 240, 1920, 1080, 8_000_000),
    R1440(R.string.video_r1440_title, R.string.video_r1440_sub, "2560x1440", 0, 0, 320, 2560, 1440, 12_000_000),
    WIDE(R.string.video_wide_title, R.string.video_wide_sub, "1280x720", 0, 240, 160, 1280, 480, 4_000_000),
    /** 1728×648 - нативный экран Desay SV G6S (Мазда): из кадра AA 1920×1080 вырезается центр, поля по обеим осям. */
    WIDE_MID(R.string.video_wide_mid_title, R.string.video_wide_mid_sub, "1920x1080", 192, 432, 160, 1728, 648, 5_000_000),
    WIDE_HD(R.string.video_wide_hd_title, R.string.video_wide_hd_sub, "1920x1080", 0, 360, 160, 1920, 720, 6_000_000),
    /** 2240×976 - окно CarLife на VW CNS3 (Tharu XR, экран 2240×1260 с полосами штатного ГУ): из кадра AA 1920×1080 вырезается центральная полоса 1920×836 и масштабируется ×1,167. */
    WIDE_976(R.string.video_wide_976_title, R.string.video_wide_976_sub, "1920x1080", 0, 244, 160, 2240, 976, 8_000_000);

    val aaW: Int = aaResolution.substringBefore('x').toInt()
    val aaH: Int = aaResolution.substringAfter('x').toInt()

    /** Кроп транскодера: центральная полоса без margin-ов (AA рисует интерфейс только внутри них). */
    val crop: VideoCrop =
        if (marginW == 0 && marginH == 0) VideoCrop.NONE
        else VideoCrop(marginW / 2f / aaW, marginH / 2f / aaH, 1f - marginW / 2f / aaW, 1f - marginH / 2f / aaH)

    /** Кроп возможен только при перекодировании: в passthrough WIDE недоступен. */
    val needsTranscode: Boolean get() = !crop.isNone

    /** Режим, который реально применится при данном переключателе перекодирования. */
    fun effective(transcode: Boolean): VideoMode = if (!transcode && needsTranscode) R720 else this

    /** Метка выхода для UI: «1280×720». */
    val outLabel: String get() = "${outW}×$outH"

    /**
     * Координаты тача ГУ (кадр outW×outH) -> координаты AA. Единственное место такого маппинга.
     * С margin-ами AA ждёт тач в координатах области интерфейса (без полей), а не всего кадра:
     * сдвиг на margin/2 давал промах на 120 px вниз (Desay G6S, лог 2026-10-10). Поэтому без сдвига, только clamp в область.
     * Если область интерфейса масштабируется в кадр ГУ (WIDE_976: 1920×836 -> 2240×976), координаты пересчитываются пропорционально.
     * Без margin-ов - 1:1 как есть, без clamp.
     */
    fun mapTouch(x: Int, y: Int): Pair<Int, Int> {
        if (marginW == 0 && marginH == 0) return x to y
        val cw = aaW - marginW
        val ch = aaH - marginH
        val sx = if (cw == outW) x else (x.toLong() * cw / outW).toInt()
        val sy = if (ch == outH) y else (y.toLong() * ch / outH).toInt()
        return sx.coerceIn(0, cw - 1) to sy.coerceIn(0, ch - 1)
    }

    /** Опции SDR для AA этого режима. */
    fun sdOptions(fullAudio: Boolean) = AapSdOptions(
        resolution = aaResolution, density = density, marginW = marginW, marginH = marginH, fullAudio = fullAudio,
    )

    /** Строка для лога: «видео-режим WIDE: AA 1280x720 margins 0x240 density 160, CarLife 1280x480, кроп v 0.167..0.833». */
    fun describe(): String {
        fun f(v: Float) = String.format(Locale.US, "%.3f", v)
        val cropText = if (crop.isNone) "без кропа" else buildString {
            append("кроп")
            if (crop.u0 != 0f) append(" u ${f(crop.u0)}..${f(crop.u1)}")
            if (crop.v0 != 0f) append(" v ${f(crop.v0)}..${f(crop.v1)}")
        }
        return "видео-режим $name: AA $aaResolution margins ${marginW}x$marginH density $density, CarLife ${outW}x$outH, $cropText"
    }

    companion object {
        val DEFAULT = R720

        /** По имени из prefs; неизвестное/пустое = по умолчанию. */
        fun parse(key: String?): VideoMode = values().firstOrNull { it.name == key } ?: DEFAULT
    }
}
